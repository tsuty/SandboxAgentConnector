package com.tsuty.remoteagent.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.tsuty.remoteagent.acp.AcpClient
import com.tsuty.remoteagent.acp.AcpContextItem
import com.tsuty.remoteagent.acp.AcpJson
import com.tsuty.remoteagent.acp.AcpMethods
import com.tsuty.remoteagent.acp.AcpTimeoutException
import com.tsuty.remoteagent.acp.AcpTrafficLogger
import com.tsuty.remoteagent.acp.AgentCapabilities
import com.tsuty.remoteagent.acp.AgentMessageChunkUpdate
import com.tsuty.remoteagent.acp.AgentStderrTail
import com.tsuty.remoteagent.acp.AvailableCommand
import com.tsuty.remoteagent.acp.AvailableCommandsUpdate
import com.tsuty.remoteagent.acp.ConfigOptionUpdate
import com.tsuty.remoteagent.acp.ContentBlock
import com.tsuty.remoteagent.acp.CurrentModeUpdate
import com.tsuty.remoteagent.acp.CurrentModelUpdate
import com.tsuty.remoteagent.acp.InitializeResult
import com.tsuty.remoteagent.acp.JsonRpcId
import com.tsuty.remoteagent.acp.JsonRpcNotification
import com.tsuty.remoteagent.acp.JsonRpcRequest
import com.tsuty.remoteagent.acp.PermissionOption
import com.tsuty.remoteagent.acp.PermissionOutcome
import com.tsuty.remoteagent.acp.PlanEntry
import com.tsuty.remoteagent.acp.PlanUpdate
import com.tsuty.remoteagent.acp.PromptAttachment
import com.tsuty.remoteagent.acp.RequestPermissionParams
import com.tsuty.remoteagent.acp.SandboxConfig
import com.tsuty.remoteagent.acp.SandboxProcessListener
import com.tsuty.remoteagent.acp.SandboxProcessManager
import com.tsuty.remoteagent.acp.SandboxVariables
import com.tsuty.remoteagent.acp.SessionConfigOption
import com.tsuty.remoteagent.acp.SessionDeleteParams
import com.tsuty.remoteagent.acp.SessionInfoUpdate
import com.tsuty.remoteagent.acp.SessionListEntry
import com.tsuty.remoteagent.acp.SessionListParams
import com.tsuty.remoteagent.acp.SessionLoadParams
import com.tsuty.remoteagent.acp.SessionModeState
import com.tsuty.remoteagent.acp.SessionModelState
import com.tsuty.remoteagent.acp.SessionNewParams
import com.tsuty.remoteagent.acp.SessionNotification
import com.tsuty.remoteagent.acp.SessionPromptParams
import com.tsuty.remoteagent.acp.SessionResumeParams
import com.tsuty.remoteagent.acp.StopReason
import com.tsuty.remoteagent.acp.TextContentBlock
import com.tsuty.remoteagent.acp.ThoughtChunkUpdate
import com.tsuty.remoteagent.acp.ToolCall
import com.tsuty.remoteagent.acp.ToolCallProgressUpdate
import com.tsuty.remoteagent.acp.ToolCallUpdateEvent
import com.tsuty.remoteagent.acp.ToolKind
import com.tsuty.remoteagent.acp.UsageCost
import com.tsuty.remoteagent.acp.UsageUpdate
import com.tsuty.remoteagent.acp.UserMessageChunkUpdate
import com.tsuty.remoteagent.acp.key
import com.tsuty.remoteagent.acp.plainText
import com.tsuty.remoteagent.acp.promptAttachments
import com.tsuty.remoteagent.acp.userPromptText
import com.tsuty.remoteagent.session.SessionLockRegistry
import com.tsuty.remoteagent.session.SessionMetadataStore
import com.tsuty.remoteagent.session.SessionSnapshot
import com.tsuty.remoteagent.session.SessionSnapshotStore
import com.tsuty.remoteagent.session.SessionTranscriptRecorder
import com.tsuty.remoteagent.settings.RemoteAgentSettingsState
import com.tsuty.remoteagent.vfs.AgentWorkspaceGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class SessionState { DISCONNECTED, CONNECTING, READY, ERROR }

/**
 * A pending `session/request_permission`. The transport thread never blocks: it publishes this
 * object and a coroutine awaits [outcome], which the UI (or the auto-allow settings) completes.
 */
class PermissionRequest(
    val requestId: JsonRpcId,
    val params: RequestPermissionParams,
) {
    val outcome: CompletableDeferred<PermissionOutcome> = CompletableDeferred()

    val toolCall: ToolCall get() = params.toolCall
    val options: List<PermissionOption> get() = params.options

    val isResolved: Boolean get() = outcome.isCompleted

    /** Answers the request; the first call wins, later ones are ignored. */
    fun resolve(outcome: PermissionOutcome): Boolean = this.outcome.complete(outcome)

    fun resolve(option: PermissionOption): Boolean = resolve(PermissionOutcome.selected(option))

    fun cancel(): Boolean = resolve(PermissionOutcome.cancelled())

    /**
     * Runs [action] once this request is answered, whichever way: the user picked an option, the
     * auto-allow settings answered it before the UI ever saw it, or it lapsed with the turn (see
     * [RemoteAgentService.cancelPendingPermissions]).
     *
     * There is no event for any of that, and none is worth adding: a request that goes away is not
     * news for the transcript, only for whoever put up an affordance for it. A tab marks the
     * request on its chip (`RemoteAgentTabsPanel`, [RemoteAgentEvent.PermissionRequested]) and has
     * to take that mark down again even when nobody ever clicked it.
     *
     * [action] runs on whichever thread completed the request, so a listener touching Swing has to
     * hop to the EDT itself.
     */
    fun onResolved(action: () -> Unit) {
        outcome.invokeOnCompletion { action() }
    }
}

/** UI facing events emitted by the service. */
sealed interface RemoteAgentEvent {
    data class StateChanged(val state: SessionState, val message: String? = null) : RemoteAgentEvent

    /** Emitted right after `session/new` (or a successful `session/load`). */
    data class SessionEstablished(
        val sessionId: String,
        val models: SessionModelState?,
        val modes: SessionModeState?,
        val configOptions: List<SessionConfigOption>,
        val restored: Boolean,
        val agentCapabilities: AgentCapabilities,
    ) : RemoteAgentEvent

    /** A prompt the user sent, with the files / selections attached to it (see [PromptAttachment]). */
    data class UserPrompt(
        val text: String,
        val attachments: List<PromptAttachment> = emptyList(),
    ) : RemoteAgentEvent

    /**
     * Attachments of the prompt that is already on screen. A replay sends the typed text and each
     * attachment as separate `user_message_chunk`s, so the chips of a restored prompt only become
     * known after its [UserPrompt] was published.
     */
    data class UserPromptAttachments(val attachments: List<PromptAttachment>) : RemoteAgentEvent

    data object TurnStarted : RemoteAgentEvent
    data class TurnFinished(val stopReason: String?) : RemoteAgentEvent
    data class UsageUpdated(
        val usedTokens: Long?,
        val contextSize: Long?,
        val cost: UsageCost?,
    ) : RemoteAgentEvent
    data class AgentMessageChunk(val text: String) : RemoteAgentEvent
    data class AgentThoughtChunk(val text: String) : RemoteAgentEvent
    data class ToolCallStarted(val toolCall: ToolCall) : RemoteAgentEvent
    data class ToolCallUpdated(val toolCall: ToolCall) : RemoteAgentEvent
    data class PlanUpdated(val entries: List<PlanEntry>) : RemoteAgentEvent
    data class ModeChanged(val modeId: String) : RemoteAgentEvent
    data class ModelChanged(val modelId: String) : RemoteAgentEvent

    /** Generic session selectors (model, mode, ...) reported through `configOptions`. */
    data class ConfigOptionsChanged(val configOptions: List<SessionConfigOption>) : RemoteAgentEvent

    /**
     * Model / mode state of the current session, as reported by `session/load` or
     * `session/resume`. Entering an existing session does not establish a new one, so the
     * selectors are published separately from [SessionEstablished].
     */
    data class SessionSelectorsUpdated(
        val models: SessionModelState?,
        val modes: SessionModeState?,
        val configOptions: List<SessionConfigOption>,
    ) : RemoteAgentEvent

    data class CommandsAvailable(val commands: List<AvailableCommand>) : RemoteAgentEvent

    /** Result of `session/list`; empty on agents that do not support it. */
    data class SessionsListed(val sessions: List<SessionListEntry>) : RemoteAgentEvent

    /** The `loading-sessions` state: a listing or a session switch is in flight. */
    data class SessionListLoading(val loading: Boolean) : RemoteAgentEvent

    /**
     * A session was selected in the history dropdown or in the management screen.
     *
     * [snapshot] is the local transcript to draw right away (`null` when the history is replayed
     * live through `session/load` instead), and [contextRestored] tells whether the agent side
     * context was reconnected — it is `false` for a snapshot-only, visual restore.
     */
    data class SessionRestored(
        val sessionId: String,
        val title: String?,
        val snapshot: SessionSnapshot?,
        val contextRestored: Boolean,
    ) : RemoteAgentEvent

    /**
     * The `session/load` replay of a restore is about to start. The history is streamed as regular
     * live events, so without this marker the UI cannot tell a past turn from one running right
     * now and would time the replayed tool calls as if the agent were working through them (see
     * `TranscriptPanel` tool call groups).
     */
    data object ReplayStarted : RemoteAgentEvent

    /**
     * The `session/load` replay of a restore is over. A replay carries no turn boundary of its own,
     * so this event closes the streamed chain of thought exactly like [TurnFinished] does for a
     * live turn — without it the `Thinking` strip would keep the replayed thought forever.
     */
    data object ReplayFinished : RemoteAgentEvent

    /** The session cannot be restored at all, so the current session is left untouched. */
    data class SessionRestoreRejected(val sessionId: String, val message: String) : RemoteAgentEvent

    /** The panel must go back to "no current session": the transcript is cleared. */
    data object SessionCleared : RemoteAgentEvent

    /** Session title to show in the toolbar, either reported by the agent or renamed locally. */
    data class SessionTitleChanged(val sessionId: String, val title: String?) : RemoteAgentEvent

    /** The agent asks for permission. */
    data class PermissionRequested(
        val request: PermissionRequest,
    ) : RemoteAgentEvent

    data class Error(val message: String, val throwable: Throwable? = null) : RemoteAgentEvent
}

fun interface RemoteAgentEventListener {
    fun onEvent(event: RemoteAgentEvent)
}

/** Model / mode state of a session, as reported by `session/new` / `session/load` / `session/resume`. */
data class SessionSelectors(
    val models: SessionModelState?,
    val modes: SessionModeState?,
    val configOptions: List<SessionConfigOption>,
) {
    val isEmpty: Boolean get() = models == null && modes == null && configOptions.isEmpty()

    fun toEvent(): RemoteAgentEvent.SessionSelectorsUpdated =
        RemoteAgentEvent.SessionSelectorsUpdated(models, modes, configOptions)
}

/**
 * Project level service owning the `sbx` sandbox lifecycle and the ACP v1 session.
 *
 * Connection flow: health check → agent start → `initialize` → `session/list` → `session/new`.
 * The session is created eagerly, because the model and the mode of a session are only reported by
 * `session/new` / `session/load` / `session/resume` — without a session the selectors of the prompt
 * action bar would have nothing to show until the first prompt was already sent.
 *
 * A session that was created but never ran a prompt is a *pristine* session (see
 * [pristineSessionRef]): it is reused instead of creating another one, kept out of the local history
 * and out of the history dropdown, and deleted on the agent side when it is left behind.
 */
class RemoteAgentService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(RemoteAgentService::class.java)

    /** Owns every background operation of the session; cancelled together with the service. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("RemoteAgentService"))

    private val listeners = CopyOnWriteArrayList<RemoteAgentEventListener>()
    private val stateRef = AtomicReference(SessionState.DISCONNECTED)

    @Volatile
    private var processManager: SandboxProcessManager? = null

    @Volatile
    private var client: AcpClient? = null

    @Volatile
    private var trafficLogger: AcpTrafficLogger? = null

    @Volatile
    private var initializeResult: InitializeResult? = null

    private val sessionIdRef = AtomicReference<String?>(null)

    /**
     * `sessionId` of a session that was created but never ran a prompt. Such a session is
     * indistinguishable from a brand new one, so it is reused rather than replaced, and it is not
     * worth keeping anywhere: see [pristineSessionId], [startNewSessionSuspending] and
     * [discardPristineSession].
     */
    private val pristineSessionRef = AtomicReference<String?>(null)

    /**
     * Selector state (model / mode) of the current session, remembered so a reused pristine
     * session can hand it to the UI again after the transcript was cleared.
     */
    private val selectorsRef = AtomicReference<SessionSelectors?>(null)

    private val promptJobRef = AtomicReference<Job?>(null)

    /**
     * `true` between [RemoteAgentEvent.TurnStarted] and [RemoteAgentEvent.TurnFinished].
     *
     * The liveness of [promptJobRef] cannot answer this: the job outlives the turn boundary it
     * publishes (it still saves the snapshot and refreshes `session/list` afterwards), so a UI
     * asking the job would see a turn that is already over as still running.
     */
    private val turnRunningRef = AtomicBoolean(false)
    private val permissions = ConcurrentHashMap<String, PermissionRequest>()

    /**
     * Identifies this tab for the 1st tier of the lock ([SessionLockRegistry]): unique per
     * instance, so a sibling tab of the same project (a different [RemoteAgentService] instance)
     * is still told apart from this one even though [lockRegistry] is shared project wide.
     */
    private val tabId: String = UUID.randomUUID().toString()

    /**
     * Fallback lock owner id used only when [lockRegistry] is unavailable (light tests without a
     * project service container): the 1st tier is then effectively disabled, exactly as before it
     * moved to the shared [SessionLockRegistry].
     */
    private val fallbackOwnerId: String by lazy { UUID.randomUUID().toString() }

    /**
     * Owner id used with the 2nd tier ([SessionMetadataStore]): the project wide id of
     * [lockRegistry] when available, so every tab of the project is recognised as the same owner
     * (Ph2 SS3.3) — duplicate opens between tabs of this very same window are already caught by
     * the 1st tier before the 2nd tier is even consulted.
     */
    private fun lockOwnerId(): String = lockRegistry()?.ownerId ?: fallbackOwnerId

    /**
     * `sessionId`s currently locked by this instance, tracked locally because [lockRegistry] is
     * now shared by every tab of the project: releasing "every lock this instance holds" must not
     * touch a lock a sibling tab is holding for a different session.
     */
    private val heldSessionIds = ConcurrentHashMap.newKeySet<String>()

    /** Heartbeat job of the lock currently held for [sessionIdRef]; refreshes both tiers. */
    private val lockHeartbeatRef = AtomicReference<Job?>(null)

    /**
     * Latest `available_commands_update` of the session. Agents advertise the slash commands once,
     * right after the session is created, so the list is remembered here for UI opened later.
     */
    private val commandsRef = AtomicReference<List<AvailableCommand>>(emptyList())

    /** Latest `session/list` result; empty while disconnected or on agents without `session/list`. */
    private val sessionsRef = AtomicReference<List<SessionListEntry>>(emptyList())

    /** Title shown for the current session (agent reported, or the local rename override). */
    private val sessionTitleRef = AtomicReference<String?>(null)

    /**
     * Sessions whose `session/update` notifications must not reach the UI: a `session/load` is
     * running in the background only to reconnect the agent context, and its replay would
     * duplicate the snapshot that is already on screen.
     */
    private val suppressedReplays = ConcurrentHashMap.newKeySet<String>()

    /** Display state of the current session, written to [snapshotStore] at every turn boundary. */
    private val recorder = SessionTranscriptRecorder()

    /** Created lazily: the directory resolves IDE paths, which light tests do not have. */
    private val snapshotStore: SessionSnapshotStore by lazy {
        SessionSnapshotStore(SessionSnapshotStore.directoryFor(project))
    }


    /**
     * Sandbox configuration used for the next session; overridable for tests.
     * Kept in sync with the persisted settings by [com.tsuty.remoteagent.settings.RemoteAgentSettingsBridge].
     */
    @Volatile
    var config: SandboxConfig = persistedConfig() ?: SandboxConfig()

    val state: SessionState
        get() = stateRef.get()

    /** `sessionId` returned by `session/new` / `session/load`; `null` while disconnected. */
    val sessionId: String?
        get() = sessionIdRef.get()

    val agentCapabilities: AgentCapabilities
        get() = initializeResult?.agentCapabilities ?: AgentCapabilities()

    val isTurnRunning: Boolean
        get() = turnRunningRef.get()

    /** Slash commands advertised by the agent for the current session; empty while disconnected. */
    val availableCommands: List<AvailableCommand>
        get() = commandsRef.get()

    /** Sessions reported by the last `session/list`, with the local renames applied. */
    val sessions: List<SessionListEntry>
        get() = sessionsRef.get()

    val sessionTitle: String?
        get() = sessionTitleRef.get()

    /**
     * Text to show in place of the session title, chosen by [state] rather than by whether
     * [sessionTitle] happens to be set yet. Opening a tab auto-connects (Ph2 §2), so once that
     * succeeds ([SessionState.READY]) a session already exists even though the agent may not
     * have named it yet — this reads "New session" then, never the disconnected placeholder.
     * [SessionState.ERROR] still reads "No session": the failure itself is surfaced elsewhere
     * (e.g. `chatLog.appendError`), not through this label.
     */
    fun presentableSessionTitle(): String = when (state) {
        SessionState.DISCONNECTED, SessionState.ERROR -> "No session"
        SessionState.CONNECTING -> "Connecting…"
        SessionState.READY -> sessionTitle?.takeIf { it.isNotBlank() } ?: "New session"
    }

    /** `true` when the agent can put the IDE back into an existing session. */
    val canRestoreSessions: Boolean
        get() = agentCapabilities.loadSession || agentCapabilities.sessionCapabilities?.resume == true

    val canListSessions: Boolean
        get() = agentCapabilities.sessionCapabilities?.list == true

    val canDeleteSessions: Boolean
        get() = agentCapabilities.sessionCapabilities?.delete == true

    fun addListener(listener: RemoteAgentEventListener) {
        listeners += listener
    }

    fun removeListener(listener: RemoteAgentEventListener) {
        listeners -= listener
    }

    /**
     * Attaches to an already running sandbox and starts the ACP session in the background;
     * returns immediately. The sandbox lifecycle itself is not managed by the plugin.
     */
    fun connect(agentCommand: String = config.agentCommand): Job =
        scope.launch { connectSuspending(agentCommand) }

    /**
     * Attach flow, made of four steps:
     * 1. Health check — a lightweight probe that confirms the sandbox is reachable (exit code only).
     * 2. Agent start — launches the ACP agent inside the sandbox.
     * 3. `initialize` — negotiates the protocol version and the capabilities.
     * 4. `session/list` — publishes the sessions the user may enter; nothing is restored yet.
     */
    suspend fun connectSuspending(agentCommand: String = config.agentCommand) {
        if (state == SessionState.CONNECTING || state == SessionState.READY) return
        persistedConfig()?.let { config = it }
        setState(SessionState.CONNECTING, "Checking sandbox...")
        val manager = SandboxProcessManager(config, variables(agentCommand))
        val logger = AcpTrafficLogger()
        val acpClient = AcpClient(writeLine = { manager.writeLine(it) }, trafficLogger = logger)
        acpClient.onNotification = ::handleNotification
        acpClient.onServerRequest = ::handleServerRequest
        processManager = manager
        client = acpClient
        trafficLogger = logger
        // Kept outside the `try`: the last lines the agent wrote are what makes a failed start
        // readable (see [startupFailureMessage]).
        val stderrTail = AgentStderrTail()
        try {
            val healthExitCode = withContext(Dispatchers.IO) { manager.healthCheck() }
            if (healthExitCode != 0) {
                throw IllegalStateException(
                    "Sandbox is not reachable (health check exit code $healthExitCode). Start the sandbox before attaching.",
                )
            }
            setState(SessionState.CONNECTING, "Starting agent...")
            manager.startAgent(object : SandboxProcessListener {
                override fun onStdout(chunk: String) = acpClient.handleStdoutChunk(chunk)

                override fun onStderr(chunk: String) {
                    // `npx` reports the download of the agent here, and on a first run that is the
                    // only sign of life there is: it feeds both the progress message and the idle
                    // timeout of `initialize`.
                    acpClient.noteActivity()
                    stderrTail.append(chunk)
                    log.debug("stderr: ${chunk.trimEnd()}")
                }

                override fun onTerminated(exitCode: Int) {
                    acpClient.failPending(IllegalStateException("Sandbox process terminated with exit code $exitCode"))
                    cancelPendingPermissions()
                    setState(SessionState.DISCONNECTED, "Sandbox process terminated (exit code $exitCode)")
                }
            })
            val initialize = withStartupProgress(stderrTail) {
                acpClient.initialize(idleTimeoutSeconds = config.initializeIdleTimeoutSeconds.toLong())
            }
            initializeResult = initialize
            requireSupportedProtocol(initialize)
            if (initialize.authMethods.isNotEmpty()) {
                // Authentication is out of scope: the sandbox is expected to be pre-authenticated.
                log.info("Agent advertises auth methods ${initialize.authMethods.map { it.id }}; skipping authenticate")
            }
            setState(SessionState.CONNECTING, "Loading sessions...")
            refreshSessionsSuspending()
            // `READY` first: the panel clears the transcript when the connection comes up, which
            // would otherwise wipe the notice of the session created right below.
            setState(SessionState.READY)
            // Eager `session/new`: the model / mode choices of a session are only known once it
            // exists, so the first prompt would otherwise be the only one without the selectors.
            createSessionReporting(acpClient)
        } catch (e: Exception) {
            log.warn("Failed to start the remote agent session", e)
            val message = startupFailureMessage(e, stderrTail)
            setState(SessionState.ERROR, message)
            publish(RemoteAgentEvent.Error(message, e))
            closeSession()
        }
    }

    /**
     * Runs [block] while a ticker publishes the elapsed time and the newest `stderr` line as the
     * connection message. Without it the panel shows "Negotiating ACP..." and nothing else for as
     * long as `npx` needs to download the agent, which reads as a freeze.
     */
    private suspend fun <T> withStartupProgress(stderrTail: AgentStderrTail, block: suspend () -> T): T {
        val startedAt = System.currentTimeMillis()
        setState(SessionState.CONNECTING, startupMessage(startedAt, stderrTail))
        val ticker = scope.launch {
            while (true) {
                delay(STARTUP_TICK_MILLIS)
                setState(SessionState.CONNECTING, startupMessage(startedAt, stderrTail))
            }
        }
        return try {
            block()
        } finally {
            ticker.cancel()
        }
    }

    /** `Negotiating ACP… 42s · <last stderr line>`; the elapsed time alone proves progress. */
    private fun startupMessage(startedAt: Long, stderrTail: AgentStderrTail): String {
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        val detail = stderrTail.lastLine
            ?.let { " · " + it.take(STARTUP_DETAIL_LIMIT) }
            .orEmpty()
        return "Negotiating ACP… ${seconds}s$detail"
    }

    /**
     * Turns a failed attach into something actionable. A bare "initialize timed out" says nothing
     * about the `npx` download that never finished, while the last `stderr` lines of the agent
     * usually do.
     */
    private fun startupFailureMessage(error: Throwable, stderrTail: AgentStderrTail): String {
        val reason = error.message ?: "Failed to start the remote agent session"
        if (error !is AcpTimeoutException) return reason
        return buildString {
            append(reason)
            append(". The agent may not have finished downloading: retry (the download is cached), ")
            append("pre-install the agent in the VM, or raise \"Agent startup idle timeout\" in the settings.")
            val tail = stderrTail.tail(STARTUP_TAIL_LINES)
            if (tail.isNotEmpty()) append(tail.joinToString("\n", prefix = "\nLast agent output:\n"))
        }
    }

    /**
     * Creates the session the next prompt will run in; it stays pristine until that prompt. The
     * session state is left untouched: `session/new` runs while the connection is already up.
     */
    private suspend fun createSession(acpClient: AcpClient): String {
        // `mcpServers` stays empty on purpose: MCP is configured by the agent inside the sandbox.
        val created = acpClient.newSession(SessionNewParams(cwd = resolvedCwd(), mcpServers = emptyList()))
        adoptSession(created.sessionId, title = null, pristine = true)
        // A session freshly created by `session/new` never has a lock owner yet, so this only
        // fails if another owner raced us with a `session/load` of the very same id in between.
        tryAcquireLock(created.sessionId)
        selectorsRef.set(SessionSelectors(created.models, created.modes, created.configOptions))
        publish(
            RemoteAgentEvent.SessionEstablished(
                sessionId = created.sessionId,
                models = created.models,
                modes = created.modes,
                configOptions = created.configOptions,
                restored = false,
                agentCapabilities = agentCapabilities,
            ),
        )
        return created.sessionId
    }

    /**
     * Creates the session and reports a failure to the UI. A failed `session/new` leaves the
     * connection up — the session is created with the next prompt instead — but reporting it only
     * to the log reads as "Ready without a session": the transcript stays empty and the model /
     * mode selectors of the prompt action bar have nothing to show, with no hint of why.
     */
    private suspend fun createSessionReporting(acpClient: AcpClient) {
        try {
            createSession(acpClient)
        } catch (e: Exception) {
            log.info("session/new failed; the session is created with the next prompt instead", e)
            publish(RemoteAgentEvent.Error(sessionCreationFailureMessage(e), e))
        }
    }

    /**
     * Names the `cwd` that was sent along: a working directory the agent cannot see inside the
     * sandbox is what rejects a `session/new`, and it is the one thing the agent's own message
     * cannot point at (see [resolvedCwd]).
     */
    private fun sessionCreationFailureMessage(error: Throwable): String = buildString {
        append("session/new failed, so no session was created: the model and the mode selectors stay ")
        append("empty until the next prompt opens one. Make sure the ACP cwd \"")
        append(resolvedCwd())
        append("\" exists inside the sandbox, or set \"Remote workspace path (CWD)\" in ")
        append("Settings | Tools | Remote Agent Connector.")
        error.message?.takeIf { it.isNotBlank() }?.let {
            append("\nThe agent reported: ")
            append(it)
        }
    }

    /**
     * Makes [sessionId] the current session: the recorded transcript, the title and the traffic log
     * all follow it.
     */
    private fun adoptSession(sessionId: String, title: String?, pristine: Boolean = false) {
        sessionIdRef.set(sessionId)
        recorder.clear()
        sessionTitleRef.set(metadataStore()?.renameOverride(sessionId) ?: title)
        if (pristine) {
            // An unused session is not part of the history yet: it is only recorded there once it
            // actually ran a prompt (see [markSessionUsed]).
            pristineSessionRef.set(sessionId)
        } else {
            pristineSessionRef.set(null)
            metadataStore()?.touch(sessionId)
        }
        trafficLogger?.onSessionEstablished(sessionId)
    }

    /**
     * Claims the duplicate-open lock of [sessionId] for this instance (Ph2 SS3.3). Fails only when
     * another live owner holds it in either tier; on success a heartbeat keeps both tiers fresh so
     * the TTL of SS3.4 never expires a lock that is actually still in use.
     */
    private fun tryAcquireLock(sessionId: String): Boolean {
        val now = System.currentTimeMillis()
        val registry = lockRegistry()
        val local = registry?.tryAcquire(sessionId, tabId, LOCK_TTL_MILLIS, now)
        if (registry != null && local == null) return false
        val store = metadataStore()
        if (store != null && !store.tryLock(sessionId, lockOwnerId(), LOCK_TTL_MILLIS, now)) {
            local?.let { registry?.rollback(sessionId, it) }
            return false
        }
        heldSessionIds += sessionId
        startLockHeartbeat(sessionId)
        return true
    }

    /** Releases both tiers of the lock of [sessionId] (Ph2 SS4.5); safe to call when unlocked. */
    private fun releaseLock(sessionId: String) {
        heldSessionIds -= sessionId
        lockRegistry()?.release(sessionId, tabId)
        metadataStore()?.unlock(sessionId, lockOwnerId())
        lockHeartbeatRef.getAndSet(null)?.cancel()
    }

    /** Releases every lock still held by this instance, e.g. on [dispose]. */
    private fun releaseAllLocks() {
        lockHeartbeatRef.getAndSet(null)?.cancel()
        heldSessionIds.toList().forEach { sessionId ->
            heldSessionIds -= sessionId
            lockRegistry()?.release(sessionId, tabId)
            metadataStore()?.unlock(sessionId, lockOwnerId())
        }
    }

    /**
     * Ph2 SS3.3 order for a switch between sessions: the session left behind is unlocked first,
     * then the destination is locked, so re-entering the very same session never fights itself.
     */
    private fun acquireSessionLock(sessionId: String): Boolean {
        sessionIdRef.get()?.let { releaseLock(it) }
        return tryAcquireLock(sessionId)
    }

    /** Keeps [sessionId]'s lock alive in both tiers while it is the current session. */
    private fun startLockHeartbeat(sessionId: String) {
        lockHeartbeatRef.getAndSet(null)?.cancel()
        val job = scope.launch {
            while (true) {
                delay(LOCK_HEARTBEAT_MILLIS)
                val now = System.currentTimeMillis()
                lockRegistry()?.heartbeat(sessionId, tabId, now)
                metadataStore()?.heartbeat(sessionId, lockOwnerId(), now)
            }
        }
        lockHeartbeatRef.set(job)
    }

    /** `sessionId` of the current session while it has not run a prompt yet. */
    private fun pristineSessionId(): String? = pristineSessionRef.get()?.takeIf { it == sessionIdRef.get() }

    /**
     * The session ran a prompt, so it stops being pristine: from now on it is a real session that
     * belongs to the history and is never deleted behind the user's back.
     */
    private fun markSessionUsed(sessionId: String) {
        if (pristineSessionRef.compareAndSet(sessionId, null)) {
            metadataStore()?.touch(sessionId)
        }
    }

    /**
     * Deletes a pristine session that is about to be left behind (another session is entered, or
     * the connection goes away). Best effort: agents without `session/delete` simply keep it.
     */
    private suspend fun discardPristineSession(keep: String? = null) {
        val pristine = pristineSessionRef.get() ?: return
        if (pristine == keep) return
        pristineSessionRef.set(null)
        val acpClient = client ?: return
        if (!canDeleteSessions) return
        runCatching { acpClient.deleteSession(SessionDeleteParams(pristine)) }
            .onFailure { log.info("session/delete of the unused session $pristine failed", it) }
    }

    /** Starts a fresh session in the background, which is what the new session button does. */
    fun startNewSession(): Job = scope.launch { startNewSessionSuspending() }

    /**
     * Rewinds the panel to a fresh session. A pristine session is already exactly that, so it is
     * reused instead of leaving an unused session behind on the agent side; otherwise the current
     * session is dropped and a new one is created right away, so the selectors of the prompt
     * action bar are populated before the first prompt.
     */
    suspend fun startNewSessionSuspending() {
        recorder.clear()
        sessionTitleRef.set(null)
        val pristine = pristineSessionId()
        if (pristine != null) {
            publish(RemoteAgentEvent.SessionCleared)
            // The session survived the clearing, so its selectors are handed to the UI again.
            selectorsRef.get()?.let { publish(it.toEvent()) }
            return
        }
        sessionIdRef.getAndSet(null)?.let { releaseLock(it) }
        commandsRef.set(emptyList())
        selectorsRef.set(null)
        publish(RemoteAgentEvent.SessionCleared)
        val acpClient = client ?: return
        if (state != SessionState.READY) return
        createSessionReporting(acpClient)
    }

    /** Refreshes `session/list` in the background; returns immediately. */
    fun refreshSessions(): Job = scope.launch { refreshSessionsSuspending() }

    /**
     * Calls `session/list` and publishes the result. Agents without the capability (or with a
     * failing implementation) simply end up with an empty list instead of a broken connection.
     */
    suspend fun refreshSessionsSuspending(): List<SessionListEntry> {
        val acpClient = client ?: return emptyList()
        if (!canListSessions) {
            log.debug("Agent does not advertise session/list; the history dropdown stays empty")
            publish(RemoteAgentEvent.SessionsListed(emptyList()))
            return emptyList()
        }
        publish(RemoteAgentEvent.SessionListLoading(true))
        // The pristine session has nothing in it yet, so it is not offered as a session to enter.
        val pristine = pristineSessionId()
        val sessions = try {
            acpClient.listSessions(SessionListParams(cwd = resolvedCwd())).sessions
                .filterNot { it.sessionId == pristine }
                .map(::applyLocalTitle)
        } catch (e: Exception) {
            log.info("session/list failed", e)
            emptyList()
        } finally {
            publish(RemoteAgentEvent.SessionListLoading(false))
        }
        sessionsRef.set(sessions)
        publish(RemoteAgentEvent.SessionsListed(sessions))
        return sessions
    }

    /** A session the user renamed locally is listed under that title, never under the agent's. */
    private fun applyLocalTitle(entry: SessionListEntry): SessionListEntry {
        val override = metadataStore()?.renameOverride(entry.sessionId) ?: return entry
        return entry.copy(title = override)
    }

    /**
     * Enters an existing session, choosing between the five restore paths of the design:
     *
     * | agent | local snapshot | behaviour |
     * |---|---|---|
     * | `resume` | fresh | draw the snapshot, reconnect through `session/resume` |
     * | `load` only | fresh | draw the snapshot, reconnect through a *suppressed* `session/load` |
     * | `load` / `resume` | stale or missing | replay `session/load` live, then save a new snapshot |
     * | none | present | draw the snapshot only (visual restore, no agent context) |
     * | none | missing | reject the selection and keep the current session |
     */
    fun selectSession(entry: SessionListEntry): Job = scope.launch {
        selectSessionSuspending(entry)
        // Entering a session changes what the agent reports about it (its last update at least).
        refreshSessionsSuspending()
    }

    suspend fun selectSessionSuspending(entry: SessionListEntry) {
        val acpClient = client
        if (acpClient == null) {
            publish(RemoteAgentEvent.SessionRestoreRejected(entry.sessionId, "The remote agent session is not connected"))
            return
        }
        val sessionId = entry.sessionId
        // Ph2 SS3.3/SS3.4: the previous session's lock is released before the new one is claimed,
        // so re-entering the very same session never has to fight its own lock.
        if (sessionId != sessionIdRef.get() && !acquireSessionLock(sessionId)) {
            publish(
                RemoteAgentEvent.SessionRestoreRejected(
                    sessionId,
                    "This session is already open in another window; close it there first.",
                ),
            )
            return
        }
        val snapshot = runCatching { snapshotStore.load(sessionId) }.getOrNull()
        if (!canRestoreSessions && snapshot == null) {
            publish(
                RemoteAgentEvent.SessionRestoreRejected(
                    sessionId,
                    "This session cannot be restored: the agent supports neither session/load nor session/resume, " +
                        "and no local history is available.",
                ),
            )
            return
        }
        publish(RemoteAgentEvent.SessionListLoading(true))
        try {
            // The session that is left behind was never used: it must not pile up on the agent.
            discardPristineSession(keep = sessionId)
            val title = titleOf(entry, snapshot)
            if (!canRestoreSessions) {
                // Visual restore only: the agent keeps no context for this session.
                adoptSession(sessionId, title)
                snapshot?.let { recorder.restore(it) }
                publish(RemoteAgentEvent.SessionRestored(sessionId, title, snapshot, contextRestored = false))
                return
            }
            if (snapshot != null && snapshot.isFreshFor(entry.lastUpdatedMillis)) {
                adoptSession(sessionId, title)
                recorder.restore(snapshot)
                // Draw first, reconnect after: the transcript is already known locally.
                publish(RemoteAgentEvent.SessionRestored(sessionId, title, snapshot, contextRestored = true))
                reconnectSilently(acpClient, sessionId)
                return
            }
            adoptSession(sessionId, title)
            publish(RemoteAgentEvent.SessionRestored(sessionId, title, snapshot = null, contextRestored = true))
            replayHistory(acpClient, entry, title)
        } catch (e: Exception) {
            log.warn("Failed to restore session ${entry.sessionId}", e)
            publish(
                RemoteAgentEvent.SessionRestoreRejected(
                    entry.sessionId,
                    e.message ?: "Failed to restore the session",
                ),
            )
        } finally {
            publish(RemoteAgentEvent.SessionListLoading(false))
        }
    }

    /**
     * Reconnects the agent context of a session whose transcript is already on screen:
     * `session/resume` does not replay anything, and a `session/load` fallback is muted for the
     * duration of the call (see [suppressedReplays]).
     */
    private suspend fun reconnectSilently(acpClient: AcpClient, sessionId: String) {
        val cwd = resolvedCwd()
        if (agentCapabilities.sessionCapabilities?.resume == true) {
            runCatching { acpClient.resumeSession(SessionResumeParams(sessionId = sessionId, cwd = cwd)) }
                .onSuccess { publishSelectors(it.models, it.modes, it.configOptions) }
                .onFailure { log.info("session/resume failed for $sessionId", it) }
            return
        }
        suppressedReplays += sessionId
        try {
            val loaded = acpClient.loadSession(SessionLoadParams(sessionId = sessionId, cwd = cwd))
            publishSelectors(loaded.models, loaded.modes, loaded.configOptions)
        } catch (e: Exception) {
            log.info("session/load failed for $sessionId", e)
        } finally {
            suppressedReplays -= sessionId
        }
    }

    /**
     * Hands the model / mode state of a restored session to the UI. The state is part of the
     * `session/load` and `session/resume` responses, so it would be lost otherwise and the
     * selectors of the prompt action bar would stay empty (and therefore hidden).
     */
    private fun publishSelectors(
        models: SessionModelState?,
        modes: SessionModeState?,
        configOptions: List<SessionConfigOption>,
    ) {
        val selectors = SessionSelectors(models, modes, configOptions)
        if (selectors.isEmpty) return
        selectorsRef.set(selectors)
        publish(selectors.toEvent())
    }

    /** Replays the whole history as regular live streaming, then stores it as the new snapshot. */
    private suspend fun replayHistory(acpClient: AcpClient, entry: SessionListEntry, title: String?) {
        val sessionId = entry.sessionId
        publish(RemoteAgentEvent.ReplayStarted)
        try {
            val loaded = acpClient.loadSession(SessionLoadParams(sessionId = sessionId, cwd = resolvedCwd()))
            publishSelectors(loaded.models, loaded.modes, loaded.configOptions)
        } finally {
            // The replay never reports a turn boundary: close the thought strip here instead.
            publish(RemoteAgentEvent.ReplayFinished)
        }
        saveSnapshot(sessionId, entry.lastUpdatedMillis, title)
    }

    private fun titleOf(entry: SessionListEntry, snapshot: SessionSnapshot?): String? =
        metadataStore()?.renameOverride(entry.sessionId) ?: entry.title ?: snapshot?.title

    /**
     * Persists the transcript of [sessionId]. [agentUpdatedAt] must be the last update time the
     * agent reports for the session, so the freshness check of the next restore is meaningful;
     * when the agent tracks none, the local time is stored instead.
     */
    private fun saveSnapshot(sessionId: String, agentUpdatedAt: Long?, title: String? = sessionTitleRef.get()) {
        val entries = recorder.entries()
        if (entries.isEmpty()) return
        runCatching {
            snapshotStore.save(
                sessionId = sessionId,
                entries = entries,
                agentUpdatedAt = agentUpdatedAt ?: System.currentTimeMillis(),
                title = title,
            )
        }.onFailure { log.warn("Failed to save the snapshot of $sessionId", it) }
    }

    /** Local title override of a session; the agent has no `session/rename` counterpart. */
    fun renameSession(sessionId: String, title: String?) {
        metadataStore()?.rename(sessionId, title)
        val override = metadataStore()?.renameOverride(sessionId)
        sessionsRef.set(sessionsRef.get().map { if (it.sessionId == sessionId) it.copy(title = override ?: it.title) else it })
        if (sessionIdRef.get() == sessionId) {
            sessionTitleRef.set(override ?: sessionTitleRef.get())
        }
        publish(RemoteAgentEvent.SessionTitleChanged(sessionId, override))
        publish(RemoteAgentEvent.SessionsListed(sessionsRef.get()))
    }

    /**
     * Deletes a session. The agent side deletion is best effort — the local metadata and snapshot
     * are dropped either way, so the session consistently disappears from the IDE.
     */
    fun deleteSession(entry: SessionListEntry): Job = scope.launch {
        val sessionId = entry.sessionId
        val acpClient = client
        if (acpClient != null && canDeleteSessions) {
            runCatching { acpClient.deleteSession(SessionDeleteParams(sessionId)) }
                .onFailure { log.info("session/delete failed for $sessionId", it) }
        }
        runCatching { snapshotStore.delete(sessionId) }
            .onFailure { log.warn("Failed to delete the snapshot of $sessionId", it) }
        metadataStore()?.delete(sessionId)
        if (sessionIdRef.get() == sessionId) {
            pristineSessionRef.compareAndSet(sessionId, null)
            startNewSessionSuspending()
        }
        sessionsRef.set(sessionsRef.get().filterNot { it.sessionId == sessionId })
        publish(RemoteAgentEvent.SessionsListed(sessionsRef.get()))
        refreshSessionsSuspending()
    }

    private fun requireSupportedProtocol(initialize: InitializeResult) {
        if (initialize.protocolVersion < 1) {
            throw IllegalStateException("Agent reported unsupported ACP protocol version ${initialize.protocolVersion}")
        }
    }

    /**
     * The sandbox side working directory: the configured remote path, or the local project root
     * as a fallback so a sandbox that mirrors the project layout still works out of the box.
     */
    fun resolvedCwd(): String =
        config.remoteWorkspacePath.trim().ifEmpty { project.basePath.orEmpty() }

    /** Sends a prompt in the background; returns immediately. */
    fun sendPrompt(prompt: String, context: List<AcpContextItem> = emptyList()): Job {
        val job = scope.launch { sendPromptSuspending(prompt, context) }
        promptJobRef.set(job)
        return job
    }

    suspend fun sendPromptSuspending(prompt: String, context: List<AcpContextItem> = emptyList()) {
        val acpClient = client
        if (acpClient == null) {
            publish(RemoteAgentEvent.Error("Remote agent session is not connected"))
            return
        }
        // The session is normally created with the connection; it is only created here when that
        // eager `session/new` failed, or when the agent lost the session in between.
        val sessionId = sessionIdRef.get() ?: try {
            createSession(acpClient)
        } catch (e: Exception) {
            publish(RemoteAgentEvent.Error(e.message ?: "session/new failed", e))
            return
        }
        markSessionUsed(sessionId)
        publish(RemoteAgentEvent.UserPrompt(prompt, context.map { it.toAttachment() }))
        // The agent edits the workspace from the sandbox: the IDE flushes its buffers and locks
        // the editors for the turn, so a local edit cannot race those writes.
        workspaceGuard()?.turnStarted()
        turnRunningRef.set(true)
        publish(RemoteAgentEvent.TurnStarted)
        try {
            val result = acpClient.prompt(
                SessionPromptParams(sessionId = sessionId, prompt = promptBlocks(prompt, context)),
            )
            finishTurn(result.stopReason)
        } catch (e: Exception) {
            // The turn may already have been ended locally by [cancelPrompt]; the late failure of
            // its abandoned `session/prompt` is then no longer worth reporting.
            if (turnRunningRef.get()) {
                publish(RemoteAgentEvent.Error(e.message ?: "session/prompt failed", e))
            }
            finishTurn(null)
        } finally {
            finishTurn(null)
            cancelPendingPermissions()
            // Unlocks the editors again and pulls in whatever the agent wrote during the turn.
            workspaceGuard()?.turnFinished()
            // The turn boundary is the only point where the transcript is complete and stable.
            saveSnapshotAfterTurn(sessionId)
        }
    }

    /**
     * Stores the transcript once the turn ended. The agent side update time is re-read from
     * `session/list` so the next restore can tell a fresh snapshot from a stale one.
     */
    private suspend fun saveSnapshotAfterTurn(sessionId: String) {
        val agentUpdatedAt = if (canListSessions) {
            refreshSessionsSuspending().firstOrNull { it.sessionId == sessionId }?.lastUpdatedMillis
        } else {
            null
        }
        saveSnapshot(sessionId, agentUpdatedAt)
    }

    /** Text first, then every attached file / selection as an embedded resource block. */
    private fun promptBlocks(prompt: String, context: List<AcpContextItem>): List<ContentBlock> =
        buildList {
            add(TextContentBlock(prompt))
            context.forEach { add(it.toContentBlock()) }
        }

    /**
     * Interrupts the running turn with a `session/cancel` notification. The agent still answers
     * the pending `session/prompt`, usually with `stopReason = cancelled`.
     */
    fun cancelPrompt(): Job = scope.launch {
        val acpClient = client ?: return@launch
        val sessionId = sessionIdRef.get() ?: return@launch
        try {
            cancelPendingPermissions()
            acpClient.cancel(sessionId)
        } catch (e: Exception) {
            publish(RemoteAgentEvent.Error(e.message ?: "session/cancel failed", e))
        }
        endTurnWhenCancelIsIgnored()
    }

    /**
     * Safety net for agents that never answer the cancelled `session/prompt`: the turn would keep
     * running forever, leaving the Send button stuck on `Stop` and refusing every further prompt.
     * The turn boundary is published locally instead; a late answer of the agent is then ignored
     * by [finishTurn].
     */
    private suspend fun endTurnWhenCancelIsIgnored() {
        if (!turnRunningRef.get()) return
        val ended = withTimeoutOrNull(CANCEL_GRACE_MILLIS) {
            while (turnRunningRef.get()) delay(CANCEL_POLL_MILLIS)
            true
        }
        if (ended != null) return
        log.warn("The agent did not answer session/prompt after session/cancel; ending the turn locally")
        finishTurn(StopReason.CANCELLED)
    }

    /**
     * Publishes the turn boundary exactly once, whichever path ends the turn (the answer of the
     * agent, a failure, or the local fallback of [cancelPrompt]).
     */
    private fun finishTurn(stopReason: String?) {
        if (turnRunningRef.compareAndSet(true, false)) {
            publish(RemoteAgentEvent.TurnFinished(stopReason))
        }
    }

    /** Answers a pending `session/request_permission`. Safe to call twice. */
    fun respondToPermission(request: PermissionRequest, option: PermissionOption): Boolean =
        request.resolve(option)

    fun selectMode(modeId: String): Job = scope.launch {
        val acpClient = client ?: return@launch
        val sessionId = sessionIdRef.get() ?: return@launch
        try {
            acpClient.setMode(sessionId, modeId)
            publish(RemoteAgentEvent.ModeChanged(modeId))
        } catch (e: Exception) {
            publish(RemoteAgentEvent.Error(e.message ?: "session/set_mode failed", e))
        }
    }

    fun selectModel(modelId: String): Job = scope.launch {
        val acpClient = client ?: return@launch
        val sessionId = sessionIdRef.get() ?: return@launch
        try {
            acpClient.setModel(sessionId, modelId)
            publish(RemoteAgentEvent.ModelChanged(modelId))
        } catch (e: Exception) {
            publish(RemoteAgentEvent.Error(e.message ?: "session/set_model failed", e))
        }
    }

    /**
     * Applies a generic `configOptions` selector (`session/set_config_option`). Agents that report
     * the model this way do not implement `session/set_model`.
     */
    fun setConfigOption(configId: String, value: String): Job = scope.launch {
        val acpClient = client ?: return@launch
        val sessionId = sessionIdRef.get() ?: return@launch
        try {
            acpClient.setConfigOption(sessionId, configId, value)
        } catch (e: Exception) {
            publish(RemoteAgentEvent.Error(e.message ?: "session/set_config_option failed", e))
        }
    }

    /** Tears the session down in the background; returns immediately. */
    fun disconnect(): Job = scope.launch {
        // An unused session would otherwise survive the connection it was created for.
        discardPristineSession()
        closeSession()
    }

    private fun closeSession() {
        // Detach only tears down the ACP agent process; the sandbox itself is left running
        // so it can keep serving other clients (IDE-independent work, other attach sessions, ...).
        cancelPendingPermissions()
        client?.failPending(IllegalStateException("Session closed"))
        processManager?.stopAgent()
        trafficLogger?.close()
        client = null
        processManager = null
        trafficLogger = null
        initializeResult = null
        // Ph2 SS4.5: closing the session releases both lock tiers right away, so the same session
        // can be reopened immediately from another window/tab instead of waiting out the TTL.
        sessionIdRef.getAndSet(null)?.let { releaseLock(it) }
        pristineSessionRef.set(null)
        selectorsRef.set(null)
        promptJobRef.set(null)
        // A closed session has no turn either: the send button must not stay on `Stop`.
        turnRunningRef.set(false)
        commandsRef.set(emptyList())
        sessionsRef.set(emptyList())
        sessionTitleRef.set(null)
        suppressedReplays.clear()
        recorder.clear()
        // A closed session runs no turn: the editors must not stay locked behind it.
        workspaceGuard()?.reset()
        if (state != SessionState.ERROR) {
            setState(SessionState.DISCONNECTED)
        }
    }

    override fun dispose() {
        scope.cancel()
        closeSession()
        // Belt and braces: [closeSession] already released the current session's lock, this also
        // catches anything left over (e.g. a lock claimed but never adopted because of a race).
        releaseAllLocks()
        listeners.clear()
    }

    private fun variables(agentCommand: String) = SandboxVariables(
        projectName = project.name,
        projectRoot = project.basePath.orEmpty(),
        agentCommand = agentCommand,
    )

    private fun handleNotification(notification: JsonRpcNotification) {
        when (notification.method) {
            AcpMethods.SESSION_UPDATE -> handleSessionUpdate(notification.params as? JsonObject)
            else -> log.debug("Unhandled ACP notification: ${notification.method}")
        }
    }

    /** Decoding never throws: unknown variants degrade to `_unknown` and are only logged. */
    private fun handleSessionUpdate(params: JsonObject?) {
        if (params == null) return
        val notification = runCatching {
            AcpJson.decodeFromJsonElement(SessionNotification.serializer(), params)
        }.getOrElse {
            log.warn("Failed to decode session/update payload", it)
            return
        }
        val updatedSession = notification.sessionId ?: sessionIdRef.get()
        if (updatedSession != null && updatedSession in suppressedReplays) {
            // A background `session/load` is replaying a transcript the UI already shows.
            log.debug("Suppressed a replayed session/update of $updatedSession")
            return
        }
        when (val update = notification.update) {
            is SessionInfoUpdate -> handleSessionInfo(updatedSession, update)
            is AgentMessageChunkUpdate -> publish(RemoteAgentEvent.AgentMessageChunk(update.content.plainText))
            is ThoughtChunkUpdate -> publish(RemoteAgentEvent.AgentThoughtChunk(update.content.plainText))
            is UserMessageChunkUpdate -> publishUserMessageChunk(update.content)
            is ToolCallUpdateEvent -> publish(RemoteAgentEvent.ToolCallStarted(update.toolCall))
            is ToolCallProgressUpdate -> publish(RemoteAgentEvent.ToolCallUpdated(update.toolCall))
            is PlanUpdate -> publish(RemoteAgentEvent.PlanUpdated(update.entries))
            // Agents spell the switched mode as `currentModeId` or as `modeId`; a notification
            // that names neither says nothing worth publishing.
            is CurrentModeUpdate -> update.modeId?.let { publish(RemoteAgentEvent.ModeChanged(it)) }
            is CurrentModelUpdate -> publish(RemoteAgentEvent.ModelChanged(update.currentModelId))
            is ConfigOptionUpdate -> publish(RemoteAgentEvent.ConfigOptionsChanged(update.configOptions))
            is AvailableCommandsUpdate -> {
                commandsRef.set(update.availableCommands)
                log.debug("available_commands_update: ${update.availableCommands.size} commands")
                publish(RemoteAgentEvent.CommandsAvailable(update.availableCommands))
            }
            is UsageUpdate -> {
                log.debug("usage_update: used=${update.used} size=${update.size} cost=${update.cost}")
                publish(RemoteAgentEvent.UsageUpdated(update.used, update.size, update.cost))
            }
            else -> log.debug("Ignored session/update variant: $update")
        }
    }

    /**
     * Attachments are replayed as extra chunks of the same prompt: only the typed text belongs in
     * the transcript as a message, while the attachment itself is reduced to its chip and joins
     * the prompt block it was sent with.
     */
    private fun publishUserMessageChunk(content: ContentBlock) {
        val text = content.userPromptText
        val attachments = content.promptAttachments
        when {
            text != null -> publish(RemoteAgentEvent.UserPrompt(text, attachments))
            attachments.isNotEmpty() -> publish(RemoteAgentEvent.UserPromptAttachments(attachments))
        }
    }

    /**
     * Applies a `session_info_update`. An explicit rename wins: the user asked for that title, and
     * agents keep deriving their own one from the conversation.
     */
    private fun handleSessionInfo(sessionId: String?, update: SessionInfoUpdate) {
        val target = update.sessionId ?: sessionId ?: return
        val override = metadataStore()?.renameOverride(target)
        val title = override ?: update.title
        if (sessionIdRef.get() == target) sessionTitleRef.set(title)
        sessionsRef.set(sessionsRef.get().map { if (it.sessionId == target) it.copy(title = title) else it })
        publish(RemoteAgentEvent.SessionTitleChanged(target, title))
    }

    /**
     * Handles agent initiated requests. Returns `false` for unsupported methods so [AcpClient]
     * answers them with a JSON-RPC "method not found" error.
     */
    private fun handleServerRequest(request: JsonRpcRequest): Boolean {
        if (request.method != AcpMethods.SESSION_REQUEST_PERMISSION) return false
        val params = request.params as? JsonObject ?: return false
        val permissionParams = runCatching {
            AcpJson.decodeFromJsonElement(RequestPermissionParams.serializer(), params)
        }.getOrElse {
            log.warn("Failed to decode session/request_permission payload", it)
            return false
        }
        val permission = PermissionRequest(request.id, permissionParams)
        permissions[request.id.key] = permission
        awaitPermissionOutcome(permission)
        publish(RemoteAgentEvent.PermissionRequested(permission))
        if (!permission.isResolved && listeners.isEmpty()) {
            // Nobody can answer (no tool window attached): cancel instead of letting the agent hang.
            log.info("No listener can answer session/request_permission; cancelling it")
            permission.cancel()
        }
        return true
    }

    /** Waits for the UI without blocking the transport thread. */
    private fun awaitPermissionOutcome(permission: PermissionRequest) {
        scope.launch {
            val outcome = permission.outcome.await()
            val acpClient = client
            if (acpClient == null) {
                log.debug("Dropping permission outcome: the session is already closed")
            } else {
                runCatching { acpClient.respondToPermission(permission.requestId, outcome) }
                    .onFailure { publish(RemoteAgentEvent.Error(it.message ?: "Failed to answer session/request_permission", it)) }
            }
            permissions.remove(permission.requestId.key)
        }
    }

    /** Releases the agent from waiting when the turn ends or the session goes away. */
    private fun cancelPendingPermissions() {
        permissions.values.forEach { it.cancel() }
    }

    private fun setState(newState: SessionState, message: String? = null) {
        stateRef.set(newState)
        publish(RemoteAgentEvent.StateChanged(newState, message))
    }

    private fun publish(event: RemoteAgentEvent) {
        runCatching { recorder.record(event) }.onFailure { log.warn("Failed to record the transcript", it) }
        listeners.forEach {
            runCatching { it.onEvent(event) }.onFailure { failure -> log.warn("Remote agent listener failed", failure) }
        }
    }

    /** Reads the persisted settings; `null` when the project service is unavailable (light tests). */
    private fun persistedConfig(): SandboxConfig? = runCatching {
        RemoteAgentSettingsState.getInstance(project).toSandboxConfig()
    }.getOrNull()

    /** `null` when the project service is unavailable (light tests), which disables rename/touch. */
    private fun metadataStore(): SessionMetadataStore? =
        runCatching { SessionMetadataStore.getInstance(project) }.getOrNull()

    /** `null` when the project service is unavailable (light tests), which disables the 1st tier. */
    private fun lockRegistry(): SessionLockRegistry? =
        runCatching { SessionLockRegistry.getInstance(project) }.getOrNull()

    /** `null` when the project service is unavailable (light tests), which disables the lock. */
    private fun workspaceGuard(): AgentWorkspaceGuard? = AgentWorkspaceGuard.getInstanceOrNull(project)

    companion object {
        fun getInstance(project: Project): RemoteAgentService = project.getService(RemoteAgentService::class.java)

        /** How long the answer of the agent to a cancelled `session/prompt` is waited for. */
        private const val CANCEL_GRACE_MILLIS = 5_000L
        private const val CANCEL_POLL_MILLIS = 50L

        /** Refresh interval of the elapsed time shown while the agent starts. */
        private const val STARTUP_TICK_MILLIS = 1_000L

        /** The status row is one line: a long `npx` progress line is cut instead of stretching it. */
        private const val STARTUP_DETAIL_LIMIT = 120

        /** `stderr` lines quoted in a failed start; enough for the npm error, short enough to read. */
        private const val STARTUP_TAIL_LINES = 5

        /**
         * TTL of the duplicate-open lock (Ph2 SS3.4): long enough that the heartbeat keeps a live
         * lock well within it, short enough that a crashed owner's lock is not stuck for long.
         */
        private const val LOCK_TTL_MILLIS = 60_000L

        /** Heartbeat interval of a held lock; well under [LOCK_TTL_MILLIS] so it never lapses. */
        private const val LOCK_HEARTBEAT_MILLIS = 20_000L
    }
}

/** Convenience helper for the transcript: `"Turn finished"`, `"Cancelled"`, ... */
fun RemoteAgentEvent.TurnFinished.presentableText(): String = StopReason.presentableText(stopReason)
