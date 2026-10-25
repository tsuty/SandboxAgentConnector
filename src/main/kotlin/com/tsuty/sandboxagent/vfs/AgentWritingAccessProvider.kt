package com.tsuty.sandboxagent.vfs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider

/**
 * Makes the workspace read-only in the IDE while the agent runs a turn (see [AgentWorkspaceGuard]).
 *
 * Only the IDE side of the writability is answered here: editors, refactorings and every other
 * write action go through this extension point, while the sandbox writes straight to disk and is
 * therefore not affected.
 */
class AgentWritingAccessProvider(private val project: Project) : WritingAccessProvider() {

    override fun isPotentiallyWritable(file: VirtualFile): Boolean = !isBlocked(file)

    /** The contract is inverted: the files that *cannot* be written are returned. */
    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> =
        files.filter { isBlocked(it) }

    /** Names the agents running a turn: with several tabs, any of them may be the one locking. */
    override fun getReadOnlyMessage(): String =
        readOnlyMessage(AgentWorkspaceGuard.getInstanceOrNull(project)?.runningAgentNames.orEmpty())

    companion object {
        fun readOnlyMessage(agentNames: List<String>): String = when (agentNames.filter { it.isNotBlank() }.size) {
            0 -> "The sandbox agent is running a turn: the workspace is read-only until it finishes."
            1 -> "${agentNames.first { it.isNotBlank() }} is running a turn: the workspace is read-only until it finishes."
            else -> "${agentNames.filter { it.isNotBlank() }.joinToString(", ")} are running turns: " +
                "the workspace is read-only until they finish."
        }
    }

    private fun isBlocked(file: VirtualFile): Boolean =
        AgentWorkspaceGuard.getInstanceOrNull(project)?.isEditingBlocked(file) == true
}
