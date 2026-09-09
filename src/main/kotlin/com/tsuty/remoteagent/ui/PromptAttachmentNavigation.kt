package com.tsuty.remoteagent.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.tsuty.remoteagent.acp.PromptAttachment

/**
 * What following an attachment chip does (see [PromptAttachmentChips]).
 *
 * A chip of the strip above the prompt editor always points at the workspace, because the prompt
 * has not been sent yet: the file opens, and an attached selection is selected again so the user
 * sees exactly what they are about to send.
 *
 * A chip of the transcript points at what was sent instead. The file is still opened by path
 * alone — it may well have been edited since, and that is fine, the chip only claims which file
 * was attached — but a selection is shown from the snippet the prompt carried
 * ([PromptAttachment.content]), because line numbers say nothing about a file that moved on.
 */
object PromptAttachmentNavigation {

    /** `true` when following the chip leads somewhere, which is what makes it clickable. */
    fun isNavigable(attachment: PromptAttachment): Boolean =
        attachment.isSelection || findFile(attachment.path) != null

    /** Opens the attached file, selecting the attached lines. `false` when the file is gone. */
    fun openInEditor(project: Project, attachment: PromptAttachment): Boolean {
        val file = findFile(attachment.path) ?: return false
        val line = ((attachment.startLine ?: 1) - 1).coerceAtLeast(0)
        val descriptor = OpenFileDescriptor(project, file, line, 0)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true) ?: return true
        val startLine = attachment.startLine ?: return true
        val endLine = attachment.endLine ?: return true
        val document = editor.document
        val lastLine = (document.lineCount - 1).coerceAtLeast(0)
        val from = (startLine - 1).coerceIn(0, lastLine)
        val to = (endLine - 1).coerceIn(from, lastLine)
        editor.selectionModel.setSelection(document.getLineStartOffset(from), document.getLineEndOffset(to))
        return true
    }

    /**
     * Shows the snippet the prompt carried in a read only editor tab, named after the chip so it
     * is recognizable among the real files. Falls back to the file itself for an attachment stored
     * before the snippet was kept (or replayed without it).
     */
    fun showAttachedContent(project: Project, attachment: PromptAttachment): Boolean {
        val content = attachment.content ?: return openInEditor(project, attachment)
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(attachment.presentableName)
        val file = LightVirtualFile(attachment.label, fileType, content).apply { isWritable = false }
        FileEditorManager.getInstance(project).openFile(file, true)
        return true
    }

    /**
     * The attached path as it is: a chip claims nothing about where the file ended up, so a path
     * that no longer resolves — or resolves to a directory, which no editor opens — is a chip that
     * simply does not react.
     */
    private fun findFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(path)?.takeIf { it.isValid && !it.isDirectory }
}
