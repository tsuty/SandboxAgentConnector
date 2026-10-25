package com.tsuty.sandboxagent.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.tsuty.sandboxagent.acp.AcpClient
import com.tsuty.sandboxagent.acp.AcpContextItem
import com.tsuty.sandboxagent.acp.AcpJson
import com.tsuty.sandboxagent.acp.AcpMethods
import com.tsuty.sandboxagent.acp.AcpTimeoutException
import com.tsuty.sandboxagent.acp.AcpTrafficLogger
import com.tsuty.sandboxagent.acp.AgentCapabilities
import com.tsuty.sandboxagent.acp.AgentMessageChunkUpdate
import com.tsuty.sandboxagent.acp.AgentStderrTail
import com.tsuty.sandboxagent.acp.AvailableCommand
import com.tsuty.sandboxagent.acp.AvailableCommandsUpdate
import com.tsuty.sandboxagent.acp.ConfigOptionUpdate
import com.tsuty.sandboxagent.acp.ContentBlock
import com.tsuty.sandboxagent.acp.CurrentModeUpdate
import com.tsuty.sandboxagent.acp.CurrentModelUpdate
import com.tsuty.sandboxagent.acp.InitializeResult
import com.tsuty.sandboxagent.acp.JsonRpcId
import com.tsuty.sandboxagent.acp.JsonRpcNotification
import com.tsuty.sandboxagent.acp.JsonRpcRequest
import com.tsuty.sandboxagent.acp.PermissionOption
import com.tsuty.sandboxagent.acp.PermissionOutcome
import com.tsuty.sandboxagent.acp.PlanEntry
import com.tsuty.sandboxagent.acp.PlanUpdate
import com.tsuty.sandboxagent.acp.PromptAttachment
import com.tsuty.sandboxagent.acp.RequestPermissionParams
import com.tsuty.sandboxagent.acp.SandboxConfig
import com.tsuty.sandboxagent.acp.SandboxProcessListener
import com.tsuty.sandboxagent.acp.SandboxProcessManager
import com.tsuty.sandboxagent.acp.SandboxVariables
import com.tsuty.sandboxagent.acp.SessionConfigOption
import com.tsuty.sandboxagent.acp.SessionDeleteParams
import com.tsuty.sandboxagent.acp.SessionInfoUpdate
import com.tsuty.sandboxagent.acp.SessionListEntry
import com.tsuty.sandboxagent.acp.SessionListParams
import com.tsuty.sandboxagent.acp.SessionLoadParams
import com.tsuty.sandboxagent.acp.SessionModeState
import com.tsuty.sandboxagent.acp.SessionModelState
import com.tsuty.sandboxagent.acp.SessionNewParams
import com.tsuty.sandboxagent.acp.SessionNotification
import com.tsuty.sandboxagent.acp.SessionPromptParams
import com.tsuty.sandboxagent.acp.SessionResumeParams
import com.tsuty.sandboxagent.acp.SessionUpdate
import com.tsuty.sandboxagent.acp.StopReason
import com.tsuty.sandboxagent.acp.TextContentBlock
import com.tsuty.sandboxagent.acp.ThoughtChunkUpdate
import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.acp.ToolCallProgressUpdate
import com.tsuty.sandboxagent.acp.ToolCallUpdateEvent
import com.tsuty.sandboxagent.acp.ToolKind
import com.tsuty.sandboxagent.acp.UsageCost
import com.tsuty.sandboxagent.acp.UsageUpdate
import com.tsuty.sandboxagent.acp.UserMessageChunkUpdate
import com.tsuty.sandboxagent.acp.key
import com.tsuty.sandboxagent.acp.plainText
import com.tsuty.sandboxagent.acp.promptAttachments
import com.tsuty.sandboxagent.acp.userPromptText
import com.tsuty.sandboxagent.session.LocalSessionFile
import com.tsuty.sandboxagent.session.LocalSessionIndex
import com.tsuty.sandboxagent.session.SaveResult
import com.tsuty.sandboxagent.session.SessionLockRegistry
import com.tsuty.sandboxagent.session.SessionMetadataStore
import com.tsuty.sandboxagent.session.SessionSnapshot
import com.tsuty.sandboxagent.session.SessionSnapshotStore
import com.tsuty.sandboxagent.session.SessionTranscriptRecorder
import com.tsuty.sandboxagent.session.SnapshotAgent
import com.tsuty.sandboxagent.session.TranscriptEntry
import com.tsuty.sandboxagent.settings.AgentEntry
import com.tsuty.sandboxagent.settings.SandboxAgentSettingsState
import com.tsuty.sandboxagent.vfs.AgentWorkspaceGuard
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
     * [SandboxAgentService.cancelPendingPermissions]).
     *
     * There is no event for any of that, and none is worth adding: a request that goes away is not
     * news for the transcript, only for whoever put up an affordance for it. A tab marks the
     * request on its chip (`SandboxAgentTabsPanel`, [SandboxAgentEvent.PermissionRequested]) and has
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
sealed interface SandboxAgentEvent {
    data class StateChanged(val state: SessionState, val message: String? = null) : SandboxAgentEvent

    /** Emitted right after `session/new` (or a successful `session/load`). */
    data class SessionEstablished(
        val sessionId: String,
        val models: SessionModelState?,
        val modes: SessionModeState?,
        val configOptions: List<SessionConfigOption>,
        val restored: Boolean,
        val agentCapabilities: AgentCapabilities,
    ) : SandboxAgentEvent

    /** A prompt the user sent, with the files / selections attached to it (see [PromptAttachment]). */
    data class UserPrompt(
        val text: String,
        val attachments: List<PromptAttachment> = emptyList(),
    ) : SandboxAgentEvent

    /**
     * Attachments of the prompt that is already on screen. A replay sends the typed text and each
     * attachment as separate `user_message_chunk`s, so the chips of a restored prompt only become
     * known after its [UserPrompt] was published.
     */
    data class UserPromptAttachments(val attachments: List<PromptAttachment>) : SandboxAgentEvent

    data object TurnStarted : SandboxAgentEvent
    data class TurnFinished(val stopReason: String?) : SandboxAgentEvent
    data class UsageUpdated(
        val usedTokens: Long?,
        val contextSize: Long?,
        val cost: UsageCost?,
    ) : SandboxAgentEvent
    data class AgentMessageChunk(val text: String, val messageId: String? = null) : SandboxAgentEvent
    data class AgentThoughtChunk(val text: String) : SandboxAgentEvent
    data class ToolCallStarted(val toolCall: ToolCall) : SandboxAgentEvent
    data class ToolCallUpdated(val toolCall: ToolCall) : SandboxAgentEvent
    data class PlanUpdated(val entries: List<PlanEntry>) : SandboxAgentEvent
    data class ModeChanged(val modeId: String) : SandboxAgentEvent
    data class ModelChanged(val modelId: String) : SandboxAgentEvent

    /** Generic session selectors (model, mode, ...) reported through `configOptions`. */
    data class ConfigOptionsChanged(val configOptions: List<SessionConfigOption>) : SandboxAgentEvent

    /**
     * Model / mode state of the current session, as reported by `session/load` or
     * `session/resume`. Entering an existing session does not establish a new one, so the
     * selectors are published separately from [SessionEstablished].
     */
    data class SessionSelectorsUpdated(
        val models: SessionModelState?,
        val modes: SessionModeState?,
        val configOptions: List<SessionConfigOption>,
    ) : SandboxAgentEvent

    data class CommandsAvailable(val commands: List<AvailableCommand>) : SandboxAgentEvent

    /** Result of `session/list`; empty on agents that do not support it. */
    data class SessionsListed(val sessions: List<SessionListEntry>) : SandboxAgentEvent

    /** The `loading-sessions` state: a listing or a session switch is in flight. */
    data class SessionListLoading(val loading: Boolean) : SandboxAgentEvent

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
    ) : SandboxAgentEvent

    /**
     * The `session/load` replay of a restore is about to start. The history is streamed as regular
     * live events, so without this marker the UI cannot tell a past turn from one running right
     * now and would time the replayed tool calls as if the agent were working through them (see
     * `TranscriptPanel` tool call groups).
     */
    data object ReplayStarted : SandboxAgentEvent

    /**
     * The `session/load` replay of a restore is over. A replay carries no turn boundary of its own,
     * so this event closes the streamed chain of thought exactly like [TurnFinished] does for a
     * live turn — without it the `Thinking` strip would keep the replayed thought forever.
     */
    data object ReplayFinished : SandboxAgentEvent

    /** The session cannot be restored at all, so the current session is left untouched. */
    data class SessionRestoreRejected(val sessionId: String, val message: String) : SandboxAgentEvent

    /** The panel must go back to "no current session": the transcript is cleared. */
    data object SessionCleared : SandboxAgentEvent

    /** Session title to show in the toolbar, either reported by the agent or renamed locally. */
    data class SessionTitleChanged(val sessionId: String, val title: String?) : SandboxAgentEvent

    /** The agent asks for permission. */
    data class PermissionRequested(
        val request: PermissionRequest,
    ) : SandboxAgentEvent

    data class Error(val message: String, val throwable: Throwable? = null) : SandboxAgentEvent
}

fun interface SandboxAgentEventListener {
    fun onEvent(event: SandboxAgentEvent)
}

/** Model / mode state of a session, as reported by `session/new` / `session/load` / `session/resume`. */
data class SessionSelectors(
    val models: SessionModelState?,
    val modes: SessionModeState?,
    val configOptions: List<SessionConfigOption>,
) {
    val isEmpty: Boolean get() = models == null && modes == null && configOptions.isEmpty()

    /**
     * Applies a reported `configOptions` state. The two sources of such a state differ in how
     * complete they are, which is what [replace] tells apart:
     *
     * - [replace] `= true` for `session/new` / `session/load` / `session/resume` and the
     *   `config_option_update` notification: these carry the **whole** state, so an option that is
     *   not part of it does not exist for this session (the `fast` option only appears on the
     *   models that support it) and its selector has to go away.
     * - [replace] `= false` for the answer of `session/set_config_option`, which is **partial**:
     *   it reports `mode` and `model` but never `effort` / `fast`, so an option missing from
     *   [reported] keeps the state it had instead of vanishing from the action bar.
     */
    fun withConfigOptions(reported: List<SessionConfigOption>, replace: Boolean): SessionSelectors {
        if (reported.isEmpty()) return this
        if (replace) return copy(configOptions = reported)
        val byId = reported.associateBy { it.id }
        val merged = configOptions.map { byId[it.id] ?: it } +
            reported.filter { option -> configOptions.none { it.id == option.id } }
        return copy(configOptions = merged)
    }

    fun withModels(reported: SessionModelState?): SessionSelectors =
        if (reported == null) this else copy(models = reported)

    fun withModes(reported: SessionModeState?): SessionSelectors =
        if (reported == null) this else copy(modes = reported)

    /** Records the model that took effect without touching the list of available ones. */
    fun withCurrentModel(modelId: String): SessionSelectors =
        if (models == null) this else copy(models = models.copy(currentModelId = modelId))

    fun withCurrentMode(modeId: String): SessionSelectors =
        if (modes == null) this else copy(modes = modes.copy(currentModeId = modeId))

    /**
     * The state after `session/set_mode` for [requested] was answered. The answer is the state
     * that took effect when it reports one; otherwise the requested mode is assumed — unless a
     * `current_mode_update` arrived while waiting (the current mode is no longer
     * [modeBeforeRequest]): it names the mode the agent actually switched to, which may differ
     * from the requested one, so it is kept.
     */
    fun withRequestedMode(requested: String, reported: SessionModeState?, modeBeforeRequest: String?): SessionSelectors =
        when {
            reported != null -> withModes(reported)
            modes?.currentModeId != modeBeforeRequest -> this
            else -> withCurrentMode(requested)
        }

    /** `id=value` of every selector, so the `idea.log` trail shows what actually took effect. */
    fun presentableState(): String = (
        listOfNotNull(
            models?.currentModelId?.let { "model=$it" },
            modes?.currentModeId?.let { "mode=$it" },
        ) + configOptions.map { "${it.id}=${it.currentValue}" }
        ).joinToString(", ")

    fun toEvent(): SandboxAgentEvent.SessionSelectorsUpdated =
        SandboxAgentEvent.SessionSelectorsUpdated(models, modes, configOptions)

    companion object {
        val EMPTY: SessionSelectors = SessionSelectors(null, null, emptyList())
    }
}

/**
 * A local snapshot file shown in a Read Only tab: a backup or a deleted session (see
 * [SandboxAgentService.readOnlySource]). [title] is resolved once, when the tab is opened.
 */
data class ReadOnlySource(val file: LocalSessionFile, val snapshot: SessionSnapshot, val title: String)

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
class SandboxAgentService(
    private val project: Project,
    /**
     * Registry id of the agent this tab runs, fixed for the life of the tab; `null` runs the
     * default agent of the settings. Ignored for a Read Only tab, whose agent is the owner of
     * its snapshot.
     */
    agentId: String?,
    /**
     * Set for the service of a Read Only tab: such a service never connects, never takes a lock
     * and only redraws [ReadOnlySource.snapshot] (see [openReadOnly]).
     */
    val readOnlySource: ReadOnlySource?,
) : Disposable {
    constructor(project: Project) : this(project, null, null)

    private val log = Logger.getInstance(SandboxAgentService::class.java)

    /**
     * The agent of this tab as it was when the tab opened. It is what the tab keeps running when
     * the agent is taken out of the settings meanwhile (see [agent]); `null` without settings
     * (light tests).
     */
    private val pinnedAgent: AgentEntry? = runCatching {
        val settings = SandboxAgentSettingsState.getInstance(project)
        if (readOnlySource != null) {
            val owner = readOnlySource.snapshot.ownerAgentId(settings.legacyAgentIdOrDefault())
            settings.agentById(owner)?.copy()
                ?: AgentEntry(id = owner, name = readOnlySource.snapshot.agentName.orEmpty())
        } else {
            (agentId?.let { settings.agentById(it) } ?: settings.defaultAgent()).copy()
        }
    }.getOrNull()

    /** Registry id of the agent of this tab; `null` without settings (light tests). */
    val agentId: String?
        get() = pinnedAgent?.id

    /**
     * The agent of this tab: its current entry of the settings, which follows a newer version or
     * a drawing downloaded later, or the entry it was opened with once it is no longer enabled.
     */
    fun agent(): AgentEntry? {
        val pinned = pinnedAgent ?: return null
        return settingsOrNull()?.agentById(pinned.id) ?: pinned
    }

    /** Name shown for the agent of this tab. */
    fun agentName(): String = agent()?.nameOrDefault() ?: AgentEntry.DEFAULT_AGENT_NAME

    /** `true` while the agent of this tab is enabled in the settings. */
    val isAgentEnabled: Boolean
        get() {
            val id = agentId ?: return true
            return settingsOrNull()?.isEnabled(id) ?: true
        }

    /** Owns every background operation of the session; cancelled together with the service. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("SandboxAgentService"))

    private val listeners = CopyOnWriteArrayList<SandboxAgentEventListener>()
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
     * `true` between [SandboxAgentEvent.TurnStarted] and [SandboxAgentEvent.TurnFinished].
     *
     * The liveness of [promptJobRef] cannot answer this: the job outlives the turn boundary it
     * publishes (it still saves the snapshot and refreshes `session/list` afterwards), so a UI
     * asking the job would see a turn that is already over as still running.
     */
    private val turnRunningRef = AtomicBoolean(false)
    private val permissions = ConcurrentHashMap<String, PermissionRequest>()

    /**
     * Identifies this tab for the 1st tier of the lock ([SessionLockRegistry]): unique per
     * instance, so a sibling tab of the same project (a different [SandboxAgentService] instance)
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

    /**
     * Sessions of the last `session/list` whose local snapshot belongs to another agent: the
     * session list shows them as kept nowhere, and entering one is refused (see [conflictingOwner]).
     */
    private val foreignSessionsRef = AtomicReference<Set<String>>(emptySet())

    /** Title shown for the current session (agent reported, or the local rename override). */
    private val sessionTitleRef = AtomicReference<String?>(null)

    /**
     * Sessions whose `session/update` notifications must not reach the UI: a `session/load` is
     * running in the background to reconnect the agent context, and its replay would duplicate
     * the snapshot that is already on screen. The replay is recorded aside instead, into the
     * recorder of the session, so it can be compared with the local record once the load is done
     * (see [SessionSnapshotStore.replaceIfChanged]).
     */
    private val suppressedReplays = ConcurrentHashMap<String, SessionTranscriptRecorder>()

    /** Display state of the current session, written to [snapshotStore] at every turn boundary. */
    private val recorder = SessionTranscriptRecorder()

    /** Created lazily: the directory resolves IDE paths, which light tests do not have. */
    private val snapshotStore: SessionSnapshotStore by lazy {
        SessionSnapshotStore.forProject(project)
    }


    /**
     * Sandbox configuration used for the next session; overridable for tests.
     * Kept in sync with the persisted settings by [com.tsuty.sandboxagent.settings.SandboxAgentSettingsBridge].
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

    /** Listed sessions whose local files belong to another agent (see [foreignSessionsRef]). */
    val foreignSessionIds: Set<String>
        get() = foreignSessionsRef.get()

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
    fun presentableSessionTitle(): String = readOnlySource?.title ?: when (state) {
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

    fun addListener(listener: SandboxAgentEventListener) {
        listeners += listener
    }

    fun removeListener(listener: SandboxAgentEventListener) {
        listeners -= listener
    }

    /**
     * Attaches to an already running sandbox and starts the ACP session in the background;
     * returns immediately. The sandbox lifecycle itself is not managed by the plugin.
     */
    fun connect(agentCommand: String = config.agentCommand): Job =
        scope.launch { connectSuspending(agentCommand) }

    /** `true` for the service of a Read Only tab (see [readOnlySource]). */
    val isReadOnly: Boolean
        get() = readOnlySource != null

    /**
     * Draws the snapshot of a Read Only tab. The session is deliberately not adopted: no
     * `sessionId` is set, so no lock is taken or released, and nothing is ever saved back.
     */
    fun openReadOnly() {
        val source = readOnlySource ?: return
        recorder.restore(source.snapshot)
        publish(
            SandboxAgentEvent.SessionRestored(
                source.snapshot.sessionId,
                source.title,
                source.snapshot,
                contextRestored = false,
            ),
        )
    }

    /**
     * Attach flow, made of four steps:
     * 1. Health check — a lightweight probe that confirms the sandbox is reachable (exit code only).
     * 2. Agent start — launches the ACP agent inside the sandbox.
     * 3. `initialize` — negotiates the protocol version and the capabilities.
     * 4. `session/list` — publishes the sessions the user may enter; nothing is restored yet.
     */
    suspend fun connectSuspending(agentCommand: String = config.agentCommand) {
        if (isReadOnly) return
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
            log.warn("Failed to start the sandbox agent session", e)
            val message = startupFailureMessage(e, stderrTail)
            setState(SessionState.ERROR, message)
            publish(SandboxAgentEvent.Error(message, e))
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
        val reason = error.message ?: "Failed to start the sandbox agent session"
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
        conflictingOwner(created.sessionId)?.let { throw SessionIdConflictException(created.sessionId, it) }
        adoptSession(created.sessionId, title = null, pristine = true)
        // A session freshly created by `session/new` never has a lock owner yet, so this only
        // fails if another owner raced us with a `session/load` of the very same id in between.
        tryAcquireLock(created.sessionId)
        selectorsRef.set(SessionSelectors(created.models, created.modes, created.configOptions))
        publish(
            SandboxAgentEvent.SessionEstablished(
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
        } catch (e: SessionIdConflictException) {
            failWithConflict(e)
        } catch (e: Exception) {
            log.info("session/new failed; the session is created with the next prompt instead", e)
            publish(SandboxAgentEvent.Error(sessionCreationFailureMessage(e), e))
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
        append("\" exists inside the sandbox, or set \"Sandbox workspace path (CWD)\" in ")
        append("Settings | Tools | Sandbox Agent Connector.")
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
            local?.let { registry.rollback(sessionId, it) }
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
        pristineSessionRef.compareAndSet(sessionId, null)
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
            publish(SandboxAgentEvent.SessionCleared)
            // The session survived the clearing, so its selectors are handed to the UI again.
            selectorsRef.get()?.let { publish(it.toEvent()) }
            return
        }
        sessionIdRef.getAndSet(null)?.let { releaseLock(it) }
        commandsRef.set(emptyList())
        selectorsRef.set(null)
        publish(SandboxAgentEvent.SessionCleared)
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
            publish(SandboxAgentEvent.SessionsListed(emptyList()))
            return emptyList()
        }
        publish(SandboxAgentEvent.SessionListLoading(true))
        // The pristine session has nothing in it yet, so it is not offered as a session to enter.
        val pristine = pristineSessionId()
        val sessions = try {
            val listed = acpClient.listSessions(SessionListParams(cwd = resolvedCwd())).sessions
            // Deleted in the IDE, but still known to an agent that could not delete it.
            val local = runCatching { snapshotStore.index() }
                .onFailure { log.warn("Failed to list the local sessions", it) }
                .getOrDefault(LocalSessionIndex.EMPTY)
            val shown = listed
                .filterNot { it.sessionId == pristine || local.isDeleted(it.sessionId) }
                .map(::applyLocalTitle)
            foreignSessionsRef.set(foreignAmong(shown.map { it.sessionId }.filter { local.of(it)?.current != null }))
            shown
        } catch (e: Exception) {
            log.info("session/list failed", e)
            emptyList()
        } finally {
            publish(SandboxAgentEvent.SessionListLoading(false))
        }
        sessionsRef.set(sessions)
        publish(SandboxAgentEvent.SessionsListed(sessions))
        return sessions
    }

    /** The [sessionIds] whose current snapshot belongs to an agent other than the one of this tab. */
    private fun foreignAmong(sessionIds: List<String>): Set<String> {
        val agent = snapshotAgent() ?: return emptySet()
        return sessionIds.filterTo(HashSet()) { id ->
            val owner = runCatching { snapshotStore.ownerOf(id, agent.legacyId) }.getOrNull()
            owner != null && owner != agent.id
        }
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
     * | `load` | present | draw the snapshot, reconnect through a *suppressed* `session/load` whose replay is compared with it |
     * | `resume` only | present | draw the snapshot, reconnect through `session/resume` |
     * | `load` / `resume` | missing or empty | replay `session/load` live, then save a new snapshot |
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
            publish(SandboxAgentEvent.SessionRestoreRejected(entry.sessionId, "The sandbox agent session is not connected"))
            return
        }
        val sessionId = entry.sessionId
        conflictingOwner(sessionId)?.let { owner ->
            publish(SandboxAgentEvent.SessionRestoreRejected(sessionId, conflictMessage(sessionId, owner)))
            return
        }
        // Ph2 SS3.3/SS3.4: the previous session's lock is released before the new one is claimed,
        // so re-entering the very same session never has to fight its own lock.
        if (sessionId != sessionIdRef.get() && !acquireSessionLock(sessionId)) {
            publish(
                SandboxAgentEvent.SessionRestoreRejected(
                    sessionId,
                    "This session is already open in another window; close it there first.",
                ),
            )
            return
        }
        val snapshot = runCatching { snapshotStore.load(sessionId) }.getOrNull()
        if (!canRestoreSessions && snapshot == null) {
            publish(
                SandboxAgentEvent.SessionRestoreRejected(
                    sessionId,
                    "This session cannot be restored: the agent supports neither session/load nor session/resume, " +
                        "and no local history is available.",
                ),
            )
            return
        }
        publish(SandboxAgentEvent.SessionListLoading(true))
        try {
            // The session that is left behind was never used: it must not pile up on the agent.
            discardPristineSession(keep = sessionId)
            val title = titleOf(entry, snapshot)
            if (!canRestoreSessions) {
                // Visual restore only: the agent keeps no context for this session.
                adoptSession(sessionId, title)
                snapshot?.let { recorder.restore(it) }
                publish(SandboxAgentEvent.SessionRestored(sessionId, title, snapshot, contextRestored = false))
                return
            }
            // The local record is drawn whenever there is one: the agent's last update time moves
            // on a mere load / resume, so it cannot tell whether the conversation changed. The
            // entries themselves are compared instead, once the background load has replayed them.
            if (snapshot != null && snapshot.isRestorable) {
                adoptSession(sessionId, title)
                recorder.restore(snapshot)
                // Draw first, reconnect after: the transcript is already known locally.
                publish(SandboxAgentEvent.SessionRestored(sessionId, title, snapshot, contextRestored = true))
                val replayed = reconnectSilently(acpClient, sessionId) ?: return
                adoptReplayIfChanged(sessionId, snapshot, replayed, title)
                return
            }
            // No local history: the replay is the only source, so there is nothing to back up.
            adoptSession(sessionId, title)
            publish(SandboxAgentEvent.SessionRestored(sessionId, title, snapshot = null, contextRestored = true))
            replayHistory(acpClient, entry, title)
        } catch (e: Exception) {
            log.warn("Failed to restore session ${entry.sessionId}", e)
            publish(
                SandboxAgentEvent.SessionRestoreRejected(
                    entry.sessionId,
                    e.message ?: "Failed to restore the session",
                ),
            )
        } finally {
            publish(SandboxAgentEvent.SessionListLoading(false))
        }
    }

    /**
     * Reconnects the agent context of a session whose transcript is already on screen, and
     * returns what the agent replayed on the way, or `null` when there is nothing to compare.
     *
     * `session/load` is preferred even when `session/resume` is available: its replay is the only
     * way to learn whether the agent's history still matches the local record. It is muted for the
     * duration of the call and recorded aside (see [suppressedReplays]). An agent without
     * `session/load` reconnects through `session/resume`, which replays nothing.
     */
    private suspend fun reconnectSilently(acpClient: AcpClient, sessionId: String): List<TranscriptEntry>? {
        val cwd = resolvedCwd()
        if (!agentCapabilities.loadSession) {
            runCatching { acpClient.resumeSession(SessionResumeParams(sessionId = sessionId, cwd = cwd)) }
                .onSuccess { publishSelectors(it.models, it.modes, it.configOptions) }
                .onFailure { log.info("session/resume failed for $sessionId", it) }
            return null
        }
        val capture = SessionTranscriptRecorder()
        suppressedReplays[sessionId] = capture
        try {
            val loaded = acpClient.loadSession(SessionLoadParams(sessionId = sessionId, cwd = cwd))
            publishSelectors(loaded.models, loaded.modes, loaded.configOptions)
        } catch (e: Exception) {
            log.info("session/load failed for $sessionId", e)
            return null
        } finally {
            suppressedReplays.remove(sessionId, capture)
        }
        capture.record(SandboxAgentEvent.ReplayFinished)
        return capture.entries()
    }

    /**
     * Compares the history the agent [replayed] in the background with the local [snapshot] on
     * screen. When the conversation changed on the agent side, the local record is backed up and
     * the replayed history becomes the current snapshot and is redrawn; otherwise the local record
     * stays as it is (see [SessionSnapshotStore.replaceIfChanged]).
     *
     * Nothing is replaced once the tab moved on: another session was entered, or a new turn was
     * recorded on top of the snapshot in the meantime.
     */
    private fun adoptReplayIfChanged(
        sessionId: String,
        snapshot: SessionSnapshot,
        replayed: List<TranscriptEntry>,
        title: String?,
    ) {
        if (sessionIdRef.get() != sessionId || recorder.entries() != snapshot.entries) return
        val replaced = runCatching { snapshotStore.replaceIfChanged(sessionId, replayed, title) }
            .onFailure { log.warn("Failed to compare the local record of $sessionId with the agent's", it) }
            .getOrNull() ?: return
        log.info("The agent side history of $sessionId changed; the local record was backed up")
        recorder.restore(replaced)
        publish(SandboxAgentEvent.SessionRestored(sessionId, title, replaced, contextRestored = true))
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
        publish(SandboxAgentEvent.ReplayStarted)
        try {
            val loaded = acpClient.loadSession(SessionLoadParams(sessionId = sessionId, cwd = resolvedCwd()))
            publishSelectors(loaded.models, loaded.modes, loaded.configOptions)
        } finally {
            // The replay never reports a turn boundary: close the thought strip here instead.
            publish(SandboxAgentEvent.ReplayFinished)
        }
        saveSnapshot(sessionId, title)
    }

    private fun titleOf(entry: SessionListEntry, snapshot: SessionSnapshot?): String? =
        metadataStore()?.renameOverride(entry.sessionId) ?: entry.title ?: snapshot?.title

    /** Persists the transcript of [sessionId]. */
    private fun saveSnapshot(sessionId: String, title: String? = sessionTitleRef.get()) {
        val entries = recorder.entries()
        if (entries.isEmpty()) return
        val agent = snapshotAgent()
        val result = runCatching {
            snapshotStore.save(
                sessionId = sessionId,
                entries = entries,
                title = title,
                agent = agent,
            )
        }.onFailure { log.warn("Failed to save the snapshot of $sessionId", it) }.getOrNull()
        // Only reachable when the files appeared after the session was entered: both the creation
        // and the entering of a session already refuse an id another agent owns.
        if (result == SaveResult.CONFLICT && agent != null) {
            val owner = runCatching { snapshotStore.conflictingOwner(sessionId, agent) }.getOrNull() ?: return
            failWithConflict(SessionIdConflictException(sessionId, owner))
        }
    }

    /** The agent the snapshots of this tab are written for; `null` without settings (light tests). */
    private fun snapshotAgent(): SnapshotAgent? {
        val agent = agent() ?: return null
        val settings = settingsOrNull() ?: return null
        return SnapshotAgent(agent.id, agent.nameOrDefault(), settings.legacyAgentIdOrDefault())
    }

    /**
     * The agent owning the local files of [sessionId] when that is not the agent of this tab (see
     * [SessionSnapshotStore.conflictingOwner]); `null` when there is none, or nothing to check.
     */
    private fun conflictingOwner(sessionId: String): String? {
        val agent = snapshotAgent() ?: return null
        return runCatching { snapshotStore.conflictingOwner(sessionId, agent) }
            .onFailure { log.warn("Failed to check the owner of the local files of $sessionId", it) }
            .getOrNull()
    }

    private fun conflictMessage(sessionId: String, owner: String): String =
        "The session id \"$sessionId\" is already used by a local session of the agent \"$owner\". " +
            "Its local history is left untouched, and this session cannot be used in the IDE."

    /**
     * Puts the tab into the error state for a session id that another agent owns locally. Using
     * the session would overwrite that agent's history, and the id is the agent's choice, so
     * there is nothing to retry: the connection is closed, like a failed start.
     */
    private fun failWithConflict(conflict: SessionIdConflictException) {
        log.warn(conflict.message)
        val message = conflictMessage(conflict.sessionId, conflict.owner)
        setState(SessionState.ERROR, message)
        publish(SandboxAgentEvent.Error(message, conflict))
        closeSession()
    }

    /** Local title override of a session; the agent has no `session/rename` counterpart. */
    fun renameSession(sessionId: String, title: String?) {
        metadataStore()?.rename(sessionId, title)
        val override = metadataStore()?.renameOverride(sessionId)
        sessionsRef.set(sessionsRef.get().map { if (it.sessionId == sessionId) it.copy(title = override ?: it.title) else it })
        if (sessionIdRef.get() == sessionId) {
            sessionTitleRef.set(override ?: sessionTitleRef.get())
        }
        publish(SandboxAgentEvent.SessionTitleChanged(sessionId, override))
        publish(SandboxAgentEvent.SessionsListed(sessionsRef.get()))
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
        // The snapshot is moved aside, not erased: a deleted session stays searchable locally.
        runCatching { snapshotStore.moveToDeleted(sessionId) }
            .onFailure { log.warn("Failed to delete the snapshot of $sessionId", it) }
        metadataStore()?.delete(sessionId)
        if (sessionIdRef.get() == sessionId) {
            pristineSessionRef.compareAndSet(sessionId, null)
            startNewSessionSuspending()
        }
        sessionsRef.set(sessionsRef.get().filterNot { it.sessionId == sessionId })
        publish(SandboxAgentEvent.SessionsListed(sessionsRef.get()))
        refreshSessionsSuspending()
    }

    private fun requireSupportedProtocol(initialize: InitializeResult) {
        if (initialize.protocolVersion < 1) {
            throw IllegalStateException("Agent reported unsupported ACP protocol version ${initialize.protocolVersion}")
        }
    }

    /**
     * The sandbox side working directory: the configured sandbox path, or the local project root
     * as a fallback so a sandbox that mirrors the project layout still works out of the box.
     */
    fun resolvedCwd(): String {
        val configured = config.sandboxWorkspacePath.trim()
        if (configured.isNotEmpty()) return configured
        val basePath = project.basePath.orEmpty()
        return if (SystemInfo.isWindows) windowsPathToPosix(basePath) else basePath
    }

    /** Sends a prompt in the background; returns immediately. */
    fun sendPrompt(prompt: String, context: List<AcpContextItem> = emptyList()): Job {
        val job = scope.launch { sendPromptSuspending(prompt, context) }
        promptJobRef.set(job)
        return job
    }

    suspend fun sendPromptSuspending(prompt: String, context: List<AcpContextItem> = emptyList()) {
        val acpClient = client
        if (acpClient == null) {
            publish(SandboxAgentEvent.Error("Sandbox agent session is not connected"))
            return
        }
        // The session is normally created with the connection; it is only created here when that
        // eager `session/new` failed, or when the agent lost the session in between.
        val sessionId = sessionIdRef.get() ?: try {
            createSession(acpClient)
        } catch (e: SessionIdConflictException) {
            failWithConflict(e)
            return
        } catch (e: Exception) {
            publish(SandboxAgentEvent.Error(e.message ?: "session/new failed", e))
            return
        }
        markSessionUsed(sessionId)
        publish(SandboxAgentEvent.UserPrompt(prompt, context.map { it.toAttachment() }))
        // The agent edits the workspace from the sandbox: the IDE flushes its buffers and locks
        // the editors for the turn, so a local edit cannot race those writes.
        workspaceGuard()?.turnStarted(this, agentName())
        turnRunningRef.set(true)
        publish(SandboxAgentEvent.TurnStarted)
        try {
            val result = acpClient.prompt(
                SessionPromptParams(sessionId = sessionId, prompt = promptBlocks(prompt, context)),
            )
            finishTurn(result.stopReason)
        } catch (e: Exception) {
            // The turn may already have been ended locally by [cancelPrompt]; the late failure of
            // its abandoned `session/prompt` is then no longer worth reporting.
            if (turnRunningRef.get()) {
                publish(SandboxAgentEvent.Error(e.message ?: "session/prompt failed", e))
            }
            finishTurn(null)
        } finally {
            finishTurn(null)
            cancelPendingPermissions()
            // Unlocks the editors again and pulls in whatever the agent wrote during the turn.
            workspaceGuard()?.turnFinished(this)
            // The turn boundary is the only point where the transcript is complete and stable.
            saveSnapshotAfterTurn(sessionId)
        }
    }

    /**
     * Stores the transcript once the turn ended. `session/list` is re-read to keep the session
     * list current.
     */
    private suspend fun saveSnapshotAfterTurn(sessionId: String) {
        if (canListSessions) refreshSessionsSuspending()
        saveSnapshot(sessionId)
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
            publish(SandboxAgentEvent.Error(e.message ?: "session/cancel failed", e))
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
            publish(SandboxAgentEvent.TurnFinished(stopReason))
        }
    }

    /** Answers a pending `session/request_permission`. Safe to call twice. */
    fun respondToPermission(request: PermissionRequest, option: PermissionOption): Boolean =
        request.resolve(option)

    fun selectMode(modeId: String): Job = scope.launch {
        val acpClient = client ?: return@launch
        val sessionId = sessionIdRef.get() ?: return@launch
        try {
            val modeBeforeRequest = selectorsRef.get()?.modes?.currentModeId
            val reported = acpClient.setMode(sessionId, modeId).modes
            val merged = mergeSelectors { it.withRequestedMode(modeId, reported, modeBeforeRequest) }
            if (reported != null) publish(merged.toEvent())
            publish(SandboxAgentEvent.ModeChanged(merged.modes?.currentModeId ?: modeId))
        } catch (e: Exception) {
            publish(SandboxAgentEvent.Error(e.message ?: "session/set_mode failed", e))
        }
    }

    fun selectModel(modelId: String): Job = scope.launch {
        val acpClient = client ?: return@launch
        val sessionId = sessionIdRef.get() ?: return@launch
        try {
            val reported = acpClient.setModel(sessionId, modelId).models
            val merged = mergeSelectors { it.withModels(reported).withCurrentModel(modelId) }
            if (reported != null) publish(merged.toEvent())
            publish(SandboxAgentEvent.ModelChanged(reported?.currentModelId ?: modelId))
        } catch (e: Exception) {
            publish(SandboxAgentEvent.Error(e.message ?: "session/set_model failed", e))
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
            // The agent may reject or coerce the value (`auto` → `acceptEdits`), so the answered
            // state — not `value` — is what the selectors are refreshed from.
            val reported = acpClient.setConfigOption(sessionId, configId, value).configOptions
            if (reported.none { it.id == configId }) {
                // Nothing says whether the change took effect: the selector keeps showing `value`.
                log.debug("session/set_config_option did not report '$configId' back; assuming '$value' took effect")
            }
            applyConfigOptions(reported, replace = false)
        } catch (e: Exception) {
            publish(SandboxAgentEvent.Error(e.message ?: "session/set_config_option failed", e))
        }
    }

    /**
     * Single place a reported `configOptions` state reaches both the cache and the UI; [replace]
     * tells a whole state from a partial answer (see [SessionSelectors.withConfigOptions]).
     */
    private fun applyConfigOptions(reported: List<SessionConfigOption>, replace: Boolean) {
        if (reported.isEmpty()) return
        val merged = mergeSelectors { it.withConfigOptions(reported, replace) }
        publish(SandboxAgentEvent.ConfigOptionsChanged(merged.configOptions))
    }

    /**
     * Folds a reported selector state into the cached one, so a tab switch or a `session/resume`
     * re-publishes the state that took effect instead of the one the session started with.
     */
    private fun mergeSelectors(transform: (SessionSelectors) -> SessionSelectors): SessionSelectors {
        val merged = transform(selectorsRef.get() ?: SessionSelectors.EMPTY)
        selectorsRef.set(merged)
        log.debug("Selector state applied: ${merged.presentableState()}")
        return merged
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
        foreignSessionsRef.set(emptySet())
        sessionTitleRef.set(null)
        suppressedReplays.clear()
        recorder.clear()
        // A closed session runs no turn: the editors must not stay locked behind it.
        workspaceGuard()?.reset(this)
        if (state != SessionState.ERROR) {
            setState(SessionState.DISCONNECTED)
        }
    }

    override fun dispose() {
        scope.cancel()
        if (isReadOnly) {
            // Nothing was started or locked, and resetting the workspace guard here would unlock
            // the editors behind a turn another tab is running.
            listeners.clear()
            return
        }
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
        val capture = updatedSession?.let { suppressedReplays[it] }
        if (capture != null) {
            // A background `session/load` is replaying a transcript the UI already shows: it is
            // only recorded aside, for the comparison with the local record.
            log.debug("Suppressed a replayed session/update of $updatedSession")
            runCatching { replayedEventsOf(notification.update).forEach(capture::record) }
                .onFailure { log.warn("Failed to record the replayed history of $updatedSession", it) }
            return
        }
        when (val update = notification.update) {
            is SessionInfoUpdate -> handleSessionInfo(updatedSession, update)
            is AgentMessageChunkUpdate ->
                publish(SandboxAgentEvent.AgentMessageChunk(update.content.plainText, update.messageId))
            is ThoughtChunkUpdate -> publish(SandboxAgentEvent.AgentThoughtChunk(update.content.plainText))
            is UserMessageChunkUpdate -> publishUserMessageChunk(update.content)
            is ToolCallUpdateEvent -> publish(SandboxAgentEvent.ToolCallStarted(update.toolCall))
            is ToolCallProgressUpdate -> publish(SandboxAgentEvent.ToolCallUpdated(update.toolCall))
            is PlanUpdate -> publish(SandboxAgentEvent.PlanUpdated(update.entries))
            // Agents spell the switched mode as `currentModeId` or as `modeId`; a notification
            // that names neither says nothing worth publishing.
            // The cache is folded along, so a tab switch or a `session/resume` does not restore
            // the value the session started with (see [mergeSelectors]).
            is CurrentModeUpdate -> update.modeId?.let {
                mergeSelectors { selectors -> selectors.withCurrentMode(it) }
                publish(SandboxAgentEvent.ModeChanged(it))
            }
            is CurrentModelUpdate -> {
                mergeSelectors { it.withCurrentModel(update.currentModelId) }
                publish(SandboxAgentEvent.ModelChanged(update.currentModelId))
            }
            // A notification carries the whole selector state, so an option it stops reporting is
            // gone for this session (see [SessionSelectors.withConfigOptions]).
            is ConfigOptionUpdate -> applyConfigOptions(update.configOptions, replace = true)
            is AvailableCommandsUpdate -> {
                commandsRef.set(update.availableCommands)
                log.debug("available_commands_update: ${update.availableCommands.size} commands")
                publish(SandboxAgentEvent.CommandsAvailable(update.availableCommands))
            }
            is UsageUpdate -> {
                log.debug("usage_update: used=${update.used} size=${update.size} cost=${update.cost}")
                publish(SandboxAgentEvent.UsageUpdated(update.used, update.size, update.cost))
            }
            else -> log.debug("Ignored session/update variant: $update")
        }
    }

    /** The transcript events a suppressed replay [update] stands for (see [suppressedReplays]). */
    private fun replayedEventsOf(update: SessionUpdate): List<SandboxAgentEvent> = when (update) {
        is AgentMessageChunkUpdate ->
            listOf(SandboxAgentEvent.AgentMessageChunk(update.content.plainText, update.messageId))
        is ThoughtChunkUpdate -> listOf(SandboxAgentEvent.AgentThoughtChunk(update.content.plainText))
        is UserMessageChunkUpdate -> {
            val text = update.content.userPromptText
            val attachments = update.content.promptAttachments
            when {
                text != null -> listOf(SandboxAgentEvent.UserPrompt(text, attachments))
                attachments.isNotEmpty() -> listOf(SandboxAgentEvent.UserPromptAttachments(attachments))
                else -> emptyList()
            }
        }
        is ToolCallUpdateEvent -> listOf(SandboxAgentEvent.ToolCallStarted(update.toolCall))
        is ToolCallProgressUpdate -> listOf(SandboxAgentEvent.ToolCallUpdated(update.toolCall))
        is PlanUpdate -> listOf(SandboxAgentEvent.PlanUpdated(update.entries))
        else -> emptyList()
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
            text != null -> publish(SandboxAgentEvent.UserPrompt(text, attachments))
            attachments.isNotEmpty() -> publish(SandboxAgentEvent.UserPromptAttachments(attachments))
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
        publish(SandboxAgentEvent.SessionTitleChanged(target, title))
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
        publish(SandboxAgentEvent.PermissionRequested(permission))
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
                    .onFailure { publish(SandboxAgentEvent.Error(it.message ?: "Failed to answer session/request_permission", it)) }
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
        publish(SandboxAgentEvent.StateChanged(newState, message))
    }

    private fun publish(event: SandboxAgentEvent) {
        runCatching { recorder.record(event) }.onFailure { log.warn("Failed to record the transcript", it) }
        listeners.forEach {
            runCatching { it.onEvent(event) }.onFailure { failure -> log.warn("Sandbox agent listener failed", failure) }
        }
    }

    /** Reads the persisted settings; `null` when the project service is unavailable (light tests). */
    private fun persistedConfig(): SandboxConfig? {
        val settings = settingsOrNull() ?: return null
        val agent = agent() ?: return null
        return settings.toSandboxConfig(agent.agent())
    }

    private fun settingsOrNull(): SandboxAgentSettingsState? =
        runCatching { SandboxAgentSettingsState.getInstance(project) }.getOrNull()

    /** `null` when the project service is unavailable (light tests), which disables rename/touch. */
    private fun metadataStore(): SessionMetadataStore? =
        runCatching { SessionMetadataStore.getInstance(project) }.getOrNull()

    /** `null` when the project service is unavailable (light tests), which disables the 1st tier. */
    private fun lockRegistry(): SessionLockRegistry? =
        runCatching { SessionLockRegistry.getInstance(project) }.getOrNull()

    /** `null` when the project service is unavailable (light tests), which disables the lock. */
    private fun workspaceGuard(): AgentWorkspaceGuard? = AgentWorkspaceGuard.getInstanceOrNull(project)

    companion object {
        fun getInstance(project: Project): SandboxAgentService = project.getService(SandboxAgentService::class.java)

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

/** A session id the agent handed out is owned by another agent in the local session directory. */
class SessionIdConflictException(val sessionId: String, val owner: String) :
    IllegalStateException("Session id $sessionId is owned locally by the agent $owner")

/** Convenience helper for the transcript: `"Turn finished"`, `"Cancelled"`, ... */
fun SandboxAgentEvent.TurnFinished.presentableText(): String = StopReason.presentableText(stopReason)

/**
 * Rewrites a Windows drive path (`C:/Users/...`, or `C:\Users\...` before IntelliJ normalizes it
 * with [Project.getBasePath]) into the POSIX form (`/c/Users/...`) that Git Bash / MSYS / WSL
 * sandboxes expect as a `cwd`. Left untouched when the path is not a recognizable drive path.
 */
fun windowsPathToPosix(path: String): String {
    val normalized = path.replace('\\', '/')
    val match = Regex("^([A-Za-z]):/(.*)$").matchEntire(normalized) ?: return normalized
    val (drive, rest) = match.destructured
    return "/${drive.lowercase()}/$rest"
}
