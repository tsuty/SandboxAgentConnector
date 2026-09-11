package com.tsuty.sandboxagent.acp

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Message flow checks of the bidirectional ACP client. */
class AcpClientTest {

    @Test
    fun `line parser classifies messages and skips noise`() {
        assertIs<AcpIncoming.Response>(parseAcpLine("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
        assertIs<AcpIncoming.Notification>(parseAcpLine("""{"jsonrpc":"2.0","method":"session/update","params":{}}"""))
        assertIs<AcpIncoming.ServerRequest>(
            parseAcpLine("""{"jsonrpc":"2.0","id":2,"method":"session/request_permission"}"""),
        )

        assertNull(parseAcpLine(""))
        assertNull(parseAcpLine("   "))
        assertNull(parseAcpLine("npm WARN deprecated something"))
        assertNull(parseAcpLine("""{"broken": """))
        assertNull(parseAcpLine("""{"jsonrpc":"2.0"}"""))
        // An error response without an id is not routable.
        assertNull(parseAcpLine("""{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"parse"}}"""))
    }

    @Test
    fun `line accumulator joins split chunks`() {
        val accumulator = LineAccumulator()
        assertEquals(emptyList(), accumulator.append("""{"jsonrpc":"2.0","""))
        assertEquals(
            listOf("""{"jsonrpc":"2.0","id":1,"result":{}}"""),
            accumulator.append(""""id":1,"result":{}}""" + "\n"),
        )
        assertEquals(listOf("a", "b"), accumulator.append("a\r\nb\nc"))
        assertEquals("c", accumulator.flush())
        assertNull(accumulator.flush())
    }

    @Test
    fun `client correlates responses by numeric id`() {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val future = client.sendRequest(AcpMethods.INITIALIZE)
        val id = AcpJson.decodeFromString(JsonRpcRequest.serializer(), sent.single()).id

        client.handleStdoutChunk("""{"jsonrpc":"2.0","id":${id.content},"result":{"ok":true}}""" + "\n")

        val response = future.get(1, TimeUnit.SECONDS)
        assertEquals(id, response.id)
        assertEquals(true, response.result!!.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `initialize decodes the negotiated capabilities`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val deferred = async { client.initialize() }
        val request = awaitRequest(sent)
        assertEquals(AcpMethods.INITIALIZE, request.method)
        assertEquals(1, request.params!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content.toInt())

        client.handleStdoutChunk(
            """{"jsonrpc":"2.0","id":${request.id.content},"result":{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}}""" + "\n",
        )

        val result = withTimeout(2_000) { deferred.await() }
        assertTrue(result.agentCapabilities.loadSession)
    }

    @Test
    fun `session prompt turn ends with the stop reason`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val deferred = async {
            client.prompt(SessionPromptParams(sessionId = "sess_1", prompt = listOf(TextContentBlock("hi"))))
        }
        val request = awaitRequest(sent)
        assertEquals(AcpMethods.SESSION_PROMPT, request.method)

        client.handleStdoutChunk("""{"jsonrpc":"2.0","id":${request.id.content},"result":{"stopReason":"end_turn"}}""" + "\n")

        assertEquals(StopReason.END_TURN, withTimeout(2_000) { deferred.await() }.stopReason)
    }

    @Test
    fun `set config option answers with the state that took effect`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val deferred = async { client.setConfigOption("sess_1", "mode", "auto") }
        val request = awaitRequest(sent)
        assertEquals(AcpMethods.SESSION_SET_CONFIG_OPTION, request.method)
        assertEquals("auto", request.params!!.jsonObject["value"]!!.jsonPrimitive.content)

        client.handleStdoutChunk(
            """{"jsonrpc":"2.0","id":${request.id.content},"result":{"configOptions":[""" +
                """{"id":"mode","category":"mode","currentValue":"acceptEdits"}]}}""" + "\n",
        )

        // The agent coerced `auto` to `acceptEdits`: the answer, not the request, is the state.
        val option = withTimeout(2_000) { deferred.await() }.configOptions.single()
        assertEquals("mode", option.id)
        assertEquals("acceptEdits", option.currentValue)
    }

    @Test
    fun `a set config option error is raised as an exception`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val deferred = async { runCatching { client.setConfigOption("sess_1", "mode", "auto") } }
        val request = awaitRequest(sent)
        client.handleStdoutChunk(
            """{"jsonrpc":"2.0","id":${request.id.content},"error":{"code":-32602,"message":"unknown option"}}""" + "\n",
        )

        val failure = withTimeout(2_000) { deferred.await() }.exceptionOrNull()
        assertEquals(-32602, assertIs<AcpRemoteException>(failure).error.code)
    }

    @Test
    fun `set mode and set model tolerate an unreported state`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val mode = async { client.setMode("sess_1", "plan") }
        val modeRequest = awaitRequest(sent)
        assertEquals(AcpMethods.SESSION_SET_MODE, modeRequest.method)
        client.handleStdoutChunk("""{"jsonrpc":"2.0","id":${modeRequest.id.content},"result":{}}""" + "\n")
        assertNull(withTimeout(2_000) { mode.await() }.modes)

        sent.clear()
        val model = async { client.setModel("sess_1", "sonnet") }
        val modelRequest = awaitRequest(sent)
        assertEquals(AcpMethods.SESSION_SET_MODEL, modelRequest.method)
        client.handleStdoutChunk(
            """{"jsonrpc":"2.0","id":${modelRequest.id.content},"result":{"models":{"currentModelId":"haiku"}}}""" + "\n",
        )
        assertEquals("haiku", withTimeout(2_000) { model.await() }.models?.currentModelId)
    }

    @Test
    fun `remote errors are raised as exceptions`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val deferred = async {
            runCatching { client.newSession(SessionNewParams(cwd = "/workspace")) }
        }
        val request = awaitRequest(sent)
        client.handleStdoutChunk(
            """{"jsonrpc":"2.0","id":${request.id.content},"error":{"code":-32602,"message":"bad cwd"}}""" + "\n",
        )

        val failure = withTimeout(2_000) { deferred.await() }.exceptionOrNull()
        assertEquals(-32602, assertIs<AcpRemoteException>(failure).error.code)
    }

    @Test
    fun `remote error details are appended to the exception message`() {
        val error = AcpJson.decodeFromString(
            JsonRpcError.serializer(),
            """{"code":-32603,"message":"Internal error","data":{"details":"bypassPermissions is disabled"}}""",
        )

        assertEquals("bypassPermissions is disabled", error.details)
        assertEquals("ACP error -32603: Internal error: bypassPermissions is disabled", AcpRemoteException(error).message)
    }

    @Test
    fun `an error without details keeps the plain message`() {
        val error = AcpJson.decodeFromString(
            JsonRpcError.serializer(),
            """{"code":-32602,"message":"bad cwd","data":{"other":"x"}}""",
        )

        assertNull(error.details)
        assertNull(JsonRpcError(JsonRpcError.INVALID_PARAMS, "bad cwd").details)
        assertEquals("ACP error -32602: bad cwd", AcpRemoteException(error).message)
    }

    @Test
    fun `session cancel is sent as a notification`() {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        client.cancel("sess_1")

        val message = AcpJson.parseToJsonElement(sent.single()).jsonObject
        assertEquals(AcpMethods.SESSION_CANCEL, message["method"]!!.jsonPrimitive.content)
        assertEquals("sess_1", message["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertNull(message["id"])
    }

    @Test
    fun `permission request is answered with the acp outcome and the original id`() {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })
        var received: JsonRpcRequest? = null
        client.onServerRequest = { request ->
            received = request
            true
        }

        client.handleStdoutChunk(
            """{"jsonrpc":"2.0","id":42,"method":"session/request_permission","params":{"toolCall":{"toolCallId":"call_1","kind":"execute"},"options":[{"optionId":"allow","kind":"allow_once","isDefault":true}]}}""" + "\n",
        )

        val request = assertNotNull(received)
        val params = AcpJson.decodeFromJsonElement(RequestPermissionParams.serializer(), assertNotNull(request.params))
        client.respondToPermission(request.id, PermissionOutcome.selected(params.options.single()))

        val response = AcpJson.decodeFromString(JsonRpcResponse.serializer(), sent.single())
        assertEquals(rpcId(42), response.id)
        assertNull(response.error)
        val outcome = AcpJson
            .decodeFromJsonElement(RequestPermissionResult.serializer(), assertNotNull(response.result))
            .outcome
        assertEquals(PermissionOutcome.SELECTED, outcome.outcome)
        assertEquals("allow", outcome.optionId)
        // The reply must be a plain response, not another request or notification.
        assertNull(AcpJson.parseToJsonElement(sent.single()).jsonObject["method"])
    }

    @Test
    fun `unsupported agent requests are answered with method not found`() {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })
        client.onServerRequest = { false }

        client.handleStdoutChunk("""{"jsonrpc":"2.0","id":5,"method":"fs/read_text_file","params":{}}""" + "\n")

        val response = AcpJson.decodeFromString(JsonRpcResponse.serializer(), sent.single())
        assertEquals(rpcId(5), response.id)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun `notifications are dispatched to the handler`() {
        val client = AcpClient(writeLine = { })
        var notification: JsonRpcNotification? = null
        client.onNotification = { notification = it }

        client.handleStdoutChunk(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}}}}""" + "\n",
        )

        assertEquals(AcpMethods.SESSION_UPDATE, assertNotNull(notification).method)
        val payload = AcpJson.decodeFromJsonElement(SessionNotification.serializer(), notification!!.params!!)
        assertEquals("hi", assertIs<AgentMessageChunkUpdate>(payload.update).content.plainText)
    }

    @Test
    fun `session list returns the remembered sessions`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val deferred = async { client.listSessions(SessionListParams(cwd = "/workspace")) }
        val request = awaitRequest(sent)
        assertEquals(AcpMethods.SESSION_LIST, request.method)
        assertEquals("/workspace", request.params!!.jsonObject["cwd"]!!.jsonPrimitive.content)

        client.handleStdoutChunk(
            """{"jsonrpc":"2.0","id":${request.id.content},"result":{"sessions":[{"sessionId":"sess_1","title":"Fix"}]}}""" + "\n",
        )

        val sessions = withTimeout(2_000) { deferred.await() }.sessions
        assertEquals("sess_1", sessions.single().sessionId)
        assertEquals("Fix", sessions.single().title)
    }

    @Test
    fun `session delete succeeds even when the agent answers with an empty result`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val deferred = async { runCatching { client.deleteSession(SessionDeleteParams("sess_1")) } }
        val request = awaitRequest(sent)
        assertEquals(AcpMethods.SESSION_DELETE, request.method)

        client.handleStdoutChunk("""{"jsonrpc":"2.0","id":${request.id.content},"result":null}""" + "\n")

        assertTrue(withTimeout(2_000) { deferred.await() }.isSuccess)
    }

    @Test
    fun `session resume reconnects without replaying the history`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val deferred = async {
            client.resumeSession(SessionResumeParams(sessionId = "sess_1", cwd = "/workspace"))
        }
        val request = awaitRequest(sent)
        assertEquals(AcpMethods.SESSION_RESUME, request.method)

        client.handleStdoutChunk("""{"jsonrpc":"2.0","id":${request.id.content},"result":{"sessionId":"sess_1"}}""" + "\n")

        assertEquals("sess_1", withTimeout(2_000) { deferred.await() }.sessionId)
    }

    @Test
    fun `suspending request times out without blocking forever`() = runBlocking {
        val client = AcpClient(writeLine = { })
        assertFailsWith<AcpTimeoutException> { client.request(AcpMethods.INITIALIZE, timeoutMillis = 50) }
        Unit
    }

    @Test
    fun `an idle wait survives a slow answer as long as the agent keeps talking`() = runBlocking {
        val sent = mutableListOf<String>()
        val client = AcpClient(writeLine = { sent += it })

        val deferred = async {
            client.request(AcpMethods.INITIALIZE, timeoutMillis = null, idleTimeoutMillis = 300)
        }
        val request = awaitRequest(sent)
        // `npx` progress on stderr, reported through noteActivity, well past the idle timeout.
        repeat(5) {
            delay(150)
            client.noteActivity()
        }
        client.handleStdoutChunk("""{"jsonrpc":"2.0","id":${request.id.content},"result":{}}""" + "\n")

        assertEquals(request.id, withTimeout(2_000) { deferred.await() }.id)
    }

    @Test
    fun `an idle wait ends once the agent goes silent`() = runBlocking {
        val client = AcpClient(writeLine = { })

        val failure = assertFailsWith<AcpTimeoutException> {
            client.request(AcpMethods.INITIALIZE, timeoutMillis = null, idleTimeoutMillis = 200)
        }

        assertTrue(failure.message!!.contains("no output"), failure.message)
    }

    @Test
    fun `pending requests fail when the process dies`() {
        val client = AcpClient(writeLine = { })
        val future = client.sendRequest(AcpMethods.SESSION_PROMPT)
        client.failPending(IllegalStateException("terminated"))
        assertTrue(future.isCompletedExceptionally)
    }

    private suspend fun awaitRequest(sent: List<String>): JsonRpcRequest = withTimeout(2_000) {
        while (sent.isEmpty()) delay(5)
        AcpJson.decodeFromString(JsonRpcRequest.serializer(), sent.last())
    }
}
