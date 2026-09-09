package com.tsuty.remoteagent.acp

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Accumulates raw process output chunks and emits complete lines.
 * Handles `\n`, `\r\n` and chunk boundaries that split a single JSON line.
 */
class LineAccumulator {
    private val buffer = StringBuilder()

    fun append(chunk: String): List<String> {
        buffer.append(chunk)
        val lines = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            if (buffer[i] == '\n') {
                lines += buffer.substring(start, i).removeSuffix("\r")
                start = i + 1
            }
            i++
        }
        buffer.delete(0, start)
        return lines
    }

    /** Returns and clears whatever is left in the buffer (no trailing newline). */
    fun flush(): String? {
        if (buffer.isEmpty()) return null
        val rest = buffer.toString().removeSuffix("\r")
        buffer.setLength(0)
        return rest.ifEmpty { null }
    }
}

sealed interface AcpIncoming {
    data class Response(val response: JsonRpcResponse) : AcpIncoming
    data class Notification(val notification: JsonRpcNotification) : AcpIncoming
    data class ServerRequest(val request: JsonRpcRequest) : AcpIncoming
}

/**
 * Parses a single stdout line into an ACP message.
 * Blank lines and non-JSON noise are ignored (`null`).
 */
fun parseAcpLine(line: String, json: Json = AcpJson): AcpIncoming? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
    val element = try {
        json.parseToJsonElement(trimmed)
    } catch (e: Exception) {
        return null
    }
    val obj = element as? JsonObject ?: return null
    val id = obj["id"].asRpcId()
    val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
    return try {
        when {
            method != null && id != null -> AcpIncoming.ServerRequest(json.decodeFromJsonElement(JsonRpcRequest.serializer(), obj))
            method != null -> AcpIncoming.Notification(json.decodeFromJsonElement(JsonRpcNotification.serializer(), obj))
            id != null -> AcpIncoming.Response(json.decodeFromJsonElement(JsonRpcResponse.serializer(), obj))
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

class AcpRemoteException(val error: JsonRpcError) :
    RuntimeException("ACP error ${error.code}: ${error.fullMessage}")

class AcpTimeoutException(message: String) : RuntimeException(message)

/**
 * JSON-RPC 2.0 client speaking ACP v1 over a line oriented transport.
 *
 * The client is transport agnostic: outgoing lines are handed to [writeLine], and incoming
 * **stdout** lines must be pushed via [handleStdoutLine]. `stderr` must never reach this class.
 *
 * Both directions are supported: client initiated requests / notifications
 * (`initialize`, `session/new`, `session/prompt`, `session/cancel`, ...) and agent initiated
 * ones (`session/update` notifications, `session/request_permission` requests).
 */
class AcpClient(
    private val writeLine: (String) -> Unit,
    private val json: Json = AcpJson,
    private val trafficLogger: AcpTrafficLogger? = null,
) {
    private val log = Logger.getInstance(AcpClient::class.java)

    private val idSequence = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JsonRpcResponse>>()
    private val lineAccumulator = LineAccumulator()

    /** Last moment the agent process produced any output, on `stdout` or on `stderr`. */
    @Volatile
    private var lastActivityAt: Long = System.nanoTime()

    /** Milliseconds since the agent process last produced output. */
    val idleMillis: Long
        get() = (System.nanoTime() - lastActivityAt) / 1_000_000

    /**
     * Records output that never reaches this class, i.e. `stderr`. An idle timeout is measured
     * against it, so a slow `npx` download keeps the wait alive as long as it makes progress.
     */
    fun noteActivity() {
        lastActivityAt = System.nanoTime()
    }

    /** Notifications pushed by the agent (`session/update`, ...). */
    var onNotification: ((JsonRpcNotification) -> Unit)? = null

    /**
     * Requests initiated by the agent (`session/request_permission`). The handler owns the
     * response: it must eventually call [respondToPermission] or [sendResponse] with the same id.
     * Returning `false` makes the client answer with a JSON-RPC "method not found" error.
     */
    var onServerRequest: ((JsonRpcRequest) -> Boolean)? = null

    /** Numeric ids are used, which every ACP agent accepts. */
    fun nextId(): JsonRpcId = rpcId(idSequence.incrementAndGet())

    fun sendRequest(method: String, params: JsonElement? = null, id: JsonRpcId = nextId()): CompletableFuture<JsonRpcResponse> {
        val future = CompletableFuture<JsonRpcResponse>()
        pending[id.key] = future
        val payload = json.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = method, params = params))
        try {
            trafficLogger?.logOutgoing(payload)
            writeLine(payload)
        } catch (e: Exception) {
            pending.remove(id.key)
            future.completeExceptionally(e)
        }
        return future
    }

    fun sendNotification(method: String, params: JsonElement? = null) {
        val payload = json.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method, params = params))
        trafficLogger?.logOutgoing(payload)
        writeLine(payload)
    }

    fun sendResponse(id: JsonRpcId, result: JsonElement? = null, error: JsonRpcError? = null) {
        val payload = json.encodeToString(JsonRpcResponse.serializer(), JsonRpcResponse(id = id, result = result, error = error))
        trafficLogger?.logOutgoing(payload)
        writeLine(payload)
    }

    /**
     * Answers a `session/request_permission` with a JSON-RPC response whose `result` holds
     * [RequestPermissionResult] — the single reply shape defined by ACP v1.
     */
    fun respondToPermission(requestId: JsonRpcId, outcome: PermissionOutcome) {
        sendResponse(
            requestId,
            json.encodeToJsonElement(RequestPermissionResult.serializer(), RequestPermissionResult(outcome)),
        )
    }

    /**
     * Suspending request/response round trip; never blocks the calling thread.
     *
     * [idleTimeoutMillis] replaces the fixed [timeoutMillis] deadline with an idle one: the
     * request only fails once the agent has been silent — no `stdout`, no `stderr` — for that
     * long. That is what tells "slow" (an agent still being downloaded) from "stuck".
     */
    suspend fun request(
        method: String,
        params: JsonElement? = null,
        timeoutMillis: Long? = DEFAULT_TIMEOUT_SECONDS * 1000,
        idleTimeoutMillis: Long? = null,
    ): JsonRpcResponse {
        val id = nextId()
        val future = sendRequest(method, params, id)
        val await: suspend () -> JsonRpcResponse = {
            suspendCancellableCoroutine { continuation ->
                future.whenComplete { response, error ->
                    if (error != null) continuation.resumeWithException(error) else continuation.resume(response)
                }
                continuation.invokeOnCancellation { future.cancel(false) }
            }
        }
        return try {
            when {
                idleTimeoutMillis != null -> awaitWhileAgentSpeaks(method, id, future, await, idleTimeoutMillis)
                timeoutMillis != null -> withTimeout(timeoutMillis) { await() }
                else -> await()
            }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id.key)
            throw AcpTimeoutException("ACP request '$method' timed out after ${timeoutMillis}ms")
        }
    }

    /**
     * Waits for [future] as long as the agent keeps producing output, polling instead of arming a
     * deadline: the response may legitimately arrive minutes after the request when the agent has
     * to be downloaded first.
     */
    private suspend fun awaitWhileAgentSpeaks(
        method: String,
        id: JsonRpcId,
        future: CompletableFuture<JsonRpcResponse>,
        await: suspend () -> JsonRpcResponse,
        idleTimeoutMillis: Long,
    ): JsonRpcResponse {
        noteActivity()
        while (!future.isDone) {
            try {
                delay(IDLE_POLL_MILLIS)
            } catch (e: Exception) {
                pending.remove(id.key)
                future.cancel(false)
                throw e
            }
            val idle = idleMillis
            if (idle < idleTimeoutMillis) continue
            pending.remove(id.key)
            future.cancel(false)
            throw AcpTimeoutException(
                "ACP request '$method' timed out: the agent produced no output for ${idle / 1000}s",
            )
        }
        return await()
    }

    /** Round trip that decodes the `result` payload and turns a JSON-RPC error into an exception. */
    suspend fun <T> requestResult(
        method: String,
        deserializer: DeserializationStrategy<T>,
        params: JsonElement? = null,
        timeoutMillis: Long? = DEFAULT_TIMEOUT_SECONDS * 1000,
        idleTimeoutMillis: Long? = null,
    ): T {
        val response = request(method, params, timeoutMillis, idleTimeoutMillis)
        response.error?.let { throw AcpRemoteException(it) }
        val result = response.result
            ?: throw IllegalStateException("ACP response for '$method' carries neither result nor error")
        return json.decodeFromJsonElement(deserializer, result)
    }

    /**
     * Sends `initialize` and suspends until the response arrives.
     *
     * An idle timeout is used instead of a deadline: the first run downloads the agent through
     * `npx`, which may take minutes on a slow line while reporting progress on `stderr`. A fixed
     * deadline cut those runs off even though nothing was wrong with them.
     */
    suspend fun initialize(
        params: InitializeParams = InitializeParams(),
        idleTimeoutSeconds: Long = INITIALIZE_IDLE_TIMEOUT_SECONDS,
    ): InitializeResult = requestResult(
        AcpMethods.INITIALIZE,
        InitializeResult.serializer(),
        json.encodeToJsonElement(InitializeParams.serializer(), params),
        timeoutMillis = null,
        idleTimeoutMillis = idleTimeoutSeconds * 1000,
    )

    suspend fun newSession(params: SessionNewParams): SessionNewResult = requestResult(
        AcpMethods.SESSION_NEW,
        SessionNewResult.serializer(),
        json.encodeToJsonElement(SessionNewParams.serializer(), params),
    )

    suspend fun loadSession(params: SessionLoadParams): SessionLoadResult = requestResult(
        AcpMethods.SESSION_LOAD,
        SessionLoadResult.serializer(),
        json.encodeToJsonElement(SessionLoadParams.serializer(), params),
    )

    /** Lists the sessions the agent remembers; only available when `sessionCapabilities.list` is set. */
    suspend fun listSessions(params: SessionListParams = SessionListParams()): SessionListResult = requestResult(
        AcpMethods.SESSION_LIST,
        SessionListResult.serializer(),
        json.encodeToJsonElement(SessionListParams.serializer(), params),
    )

    /**
     * Reconnects to an existing session without replaying its history — unlike [loadSession],
     * which streams every past `session/update` again.
     */
    suspend fun resumeSession(params: SessionResumeParams): SessionResumeResult = requestResult(
        AcpMethods.SESSION_RESUME,
        SessionResumeResult.serializer(),
        json.encodeToJsonElement(SessionResumeParams.serializer(), params),
    )

    /**
     * Deletes a session on the agent side. The result is empty by definition, so an agent that
     * answers with no `result` at all is still treated as a success.
     */
    suspend fun deleteSession(params: SessionDeleteParams): SessionDeleteResult {
        val response = request(
            AcpMethods.SESSION_DELETE,
            json.encodeToJsonElement(SessionDeleteParams.serializer(), params),
        )
        response.error?.let { throw AcpRemoteException(it) }
        return SessionDeleteResult()
    }

    /**
     * Runs a prompt turn. The turn is finished exactly when this response arrives, so no
     * timeout is applied: a turn may legitimately run for a long time, and a dying sandbox
     * fails the request through [failPending].
     */
    suspend fun prompt(params: SessionPromptParams): SessionPromptResult = requestResult(
        AcpMethods.SESSION_PROMPT,
        SessionPromptResult.serializer(),
        json.encodeToJsonElement(SessionPromptParams.serializer(), params),
        timeoutMillis = null,
    )

    /** `session/cancel` is a notification: the agent still answers the pending `session/prompt`. */
    fun cancel(sessionId: String) = sendNotification(
        AcpMethods.SESSION_CANCEL,
        json.encodeToJsonElement(SessionCancelParams.serializer(), SessionCancelParams(sessionId)),
    )

    suspend fun setMode(sessionId: String, modeId: String) {
        request(
            AcpMethods.SESSION_SET_MODE,
            json.encodeToJsonElement(SessionSetModeParams.serializer(), SessionSetModeParams(sessionId, modeId)),
        ).error?.let { throw AcpRemoteException(it) }
    }

    suspend fun setModel(sessionId: String, modelId: String) {
        request(
            AcpMethods.SESSION_SET_MODEL,
            json.encodeToJsonElement(SessionSetModelParams.serializer(), SessionSetModelParams(sessionId, modelId)),
        ).error?.let { throw AcpRemoteException(it) }
    }

    /** Generic selector of newer agents, used for the model when `session/set_model` is absent. */
    suspend fun setConfigOption(sessionId: String, configId: String, value: String) {
        request(
            AcpMethods.SESSION_SET_CONFIG_OPTION,
            json.encodeToJsonElement(
                SessionSetConfigOptionParams.serializer(),
                SessionSetConfigOptionParams(sessionId, configId, value),
            ),
        ).error?.let { throw AcpRemoteException(it) }
    }

    /** Feeds a raw stdout chunk (possibly partial or multi-line). */
    fun handleStdoutChunk(chunk: String) {
        noteActivity()
        lineAccumulator.append(chunk).forEach { handleStdoutLine(it) }
    }

    fun handleStdoutLine(line: String) {
        val incoming = parseAcpLine(line, json)
        if (incoming != null) trafficLogger?.logIncoming(line.trim())
        when (incoming) {
            null -> if (line.isNotBlank()) log.debug("Skipping non-JSON stdout line: $line")
            is AcpIncoming.Response -> {
                val future = pending.remove(incoming.response.id.key)
                if (future == null) {
                    log.warn("Received response for unknown request id ${incoming.response.id}")
                } else {
                    future.complete(incoming.response)
                }
            }
            is AcpIncoming.Notification -> onNotification?.invoke(incoming.notification)
            is AcpIncoming.ServerRequest -> dispatchServerRequest(incoming.request)
        }
    }

    /**
     * Hands an agent initiated request to [onServerRequest]. Unhandled methods are answered with
     * a "method not found" error instead of being silently dropped, which would leave the agent
     * waiting forever.
     */
    private fun dispatchServerRequest(request: JsonRpcRequest) {
        val handled = runCatching { onServerRequest?.invoke(request) ?: false }
            .onFailure { log.warn("ACP request handler failed for ${request.method}", it) }
            .getOrDefault(false)
        if (handled) return
        log.debug("Answering unsupported ACP request '${request.method}' with method-not-found")
        runCatching { sendResponse(request.id, error = JsonRpcError.methodNotFound(request.method)) }
            .onFailure { log.warn("Failed to answer ACP request ${request.method}", it) }
    }

    /** Fails every request still waiting for a response, e.g. when the sandbox process dies. */
    fun failPending(reason: Throwable) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            entry.value.completeExceptionally(reason)
        }
        lineAccumulator.flush()
    }

    companion object {
        /** Silence, in seconds, that ends the wait for `initialize` (see [initialize]). */
        const val INITIALIZE_IDLE_TIMEOUT_SECONDS: Long = 60
        const val DEFAULT_TIMEOUT_SECONDS: Long = 120

        /** How often an idle wait re-checks the pending response; small enough to feel instant. */
        private const val IDLE_POLL_MILLIS: Long = 200
    }
}
