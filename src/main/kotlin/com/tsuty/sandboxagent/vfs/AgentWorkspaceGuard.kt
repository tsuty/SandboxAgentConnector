package com.tsuty.sandboxagent.vfs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.tsuty.sandboxagent.settings.SandboxAgentSettingsState

/**
 * Keeps the IDE in sync with the files the agent edits inside the sandbox, and keeps the user out
 * of them while a turn runs.
 *
 * The agent writes to the workspace on its own (the project is bind mounted into the sandbox), so
 * two things have to happen around a turn:
 *
 * 1. **Editing lock** — the editors of the workspace are made read-only for the duration of the
 *    turn, so a local edit cannot race the agent and be silently overwritten by it. Only the IDE
 *    is locked: the file permissions on disk are untouched, which is why the sandbox keeps writing
 *    without noticing anything (see [AgentWritingAccessProvider]).
 * 2. **VFS refresh** — files written outside the IDE are only picked up once the Virtual File
 *    System is refreshed, so every tool call and every turn boundary triggers one.
 *
 * Unsaved documents are flushed to disk before the turn starts: the agent reads the files, not the
 * in-memory buffers of the IDE.
 *
 * Every tab runs its own session, so turns of several tabs may overlap: each turn is tracked by
 * its owner (see [AgentTurns]), and the workspace stays locked while any of them runs.
 */
class AgentWorkspaceGuard(private val project: Project) {
    private val log = Logger.getInstance(AgentWorkspaceGuard::class.java)

    private val turns = AgentTurns()

    val isTurnRunning: Boolean
        get() = turns.isAnyRunning

    /** Names of the agents running a turn right now, each once, in the order they started. */
    val runningAgentNames: List<String>
        get() = turns.runningAgentNames

    /**
     * Flushes the unsaved documents and locks the workspace for the turn of [owner], whose agent
     * is [agentName] (named by the read-only message, see [AgentWritingAccessProvider]). The lock
     * is skipped when the user turned it off in the settings; the flush is not, because the agent
     * would otherwise read a stale file.
     */
    fun turnStarted(owner: Any, agentName: String) {
        if (!turns.start(owner, agentName)) return
        saveDocuments()
    }

    /**
     * Ends the turn of [owner] and pulls in whatever its agent wrote. The workspace is unlocked
     * once no other turn is running.
     */
    fun turnFinished(owner: Any) {
        if (!turns.finish(owner)) return
        refreshWorkspace()
    }

    /** Called when the session of [owner] goes away without a turn boundary (disconnect, dispose). */
    fun reset(owner: Any) {
        turns.finish(owner)
    }

    /**
     * `true` when [file] must not be edited in the IDE right now: a turn is running, the lock is
     * enabled, and the file belongs to the workspace the agent works in.
     */
    fun isEditingBlocked(file: VirtualFile): Boolean {
        if (!isTurnRunning || !isLockEnabled()) return false
        if (file.fileSystem !is LocalFileSystem) return false
        return AgentWorkspacePaths.isUnderWorkspace(file.path, project.basePath)
    }

    /**
     * Refreshes the files touched by a tool call. The paths are sandbox side, so they are only
     * refreshed when the very same path exists locally too (bind mounted workspace); otherwise the
     * whole project root is refreshed instead.
     */
    fun refreshPaths(paths: Collection<String>) {
        val fileSystem = LocalFileSystem.getInstance()
        val files = paths.distinct().mapNotNull { fileSystem.findFileByPath(it) }
        refresh(files.ifEmpty { projectRoots() })
    }

    /** Refreshes the whole workspace; used at the turn boundary, where the touched paths are gone. */
    fun refreshWorkspace() {
        refresh(projectRoots())
    }

    private fun refresh(targets: List<VirtualFile>) {
        if (targets.isEmpty()) return
        // Asynchronous and recursive: the refresh must not block the transport thread it is
        // called from, and the agent may have created files anywhere below the roots.
        VfsUtil.markDirtyAndRefresh(true, true, false, *targets.toTypedArray())
    }

    private fun projectRoots(): List<VirtualFile> =
        listOfNotNull(project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) })

    /**
     * Writes the in-memory buffers to disk, so the agent sees what the user sees. Saving needs the
     * EDT, and the turn starts on a background thread.
     */
    private fun saveDocuments() {
        val application = ApplicationManager.getApplication() ?: return
        application.invokeLater({
            if (project.isDisposed) return@invokeLater
            runCatching { FileDocumentManager.getInstance().saveAllDocuments() }
                .onFailure { log.warn("Failed to save the documents before the turn", it) }
        }, project.disposed)
    }

    /** `false` when the settings service is unavailable (light tests), which disables the lock. */
    private fun isLockEnabled(): Boolean = runCatching {
        SandboxAgentSettingsState.getInstance(project).lockEditorsDuringTurn
    }.getOrDefault(false)

    companion object {
        fun getInstance(project: Project): AgentWorkspaceGuard =
            project.getService(AgentWorkspaceGuard::class.java)

        /** `null` when the project service is unavailable (light tests). */
        fun getInstanceOrNull(project: Project): AgentWorkspaceGuard? = runCatching { getInstance(project) }.getOrNull()
    }
}

/** The owners (one per tab session) whose turn is running; kept free of the IDE so it can be unit tested. */
class AgentTurns {
    /** Running turns by owner, with the name of the agent running each; in the order they started. */
    private val owners = LinkedHashMap<Any, String>()

    val isAnyRunning: Boolean
        @Synchronized get() = owners.isNotEmpty()

    /** Names of the agents running a turn, each once: two tabs of one agent read as that agent. */
    val runningAgentNames: List<String>
        @Synchronized get() = owners.values.distinct()

    /** `false` when the turn of [owner] was already running. */
    @Synchronized
    fun start(owner: Any, agentName: String = ""): Boolean {
        if (owner in owners) return false
        owners[owner] = agentName
        return true
    }

    /** `false` when [owner] had no running turn. */
    @Synchronized
    fun finish(owner: Any): Boolean = owners.remove(owner) != null
}

/** Path arithmetic of the guard, kept free of the IDE so it can be unit tested. */
object AgentWorkspacePaths {
    /** `true` when [path] is [root] itself or lives below it. */
    fun isUnderWorkspace(path: String, root: String?): Boolean {
        val base = root?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return false
        val candidate = path.trimEnd('/')
        return candidate == base || candidate.startsWith("$base/")
    }
}
