package com.tsuty.sandboxagent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tsuty.sandboxagent.acp.AcpContextItem
import com.tsuty.sandboxagent.settings.SandboxAgentSettingsState
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shows the files / selections attached to the next prompt as removable chips.
 * Attaching is always explicit unless the user opted into auto attach in the settings.
 *
 * With auto attach on, the active file is previewed as an extra chip that follows the editor, so
 * what the next prompt will carry is visible before it is sent rather than only afterwards. A
 * selection inside that file is previewed as a second chip; it follows the caret with a short
 * delay so dragging over the text does not make the strip flicker on every intermediate range.
 */
class ContextChipsPanel(private val project: Project) :
    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(CHIP_GAP), JBUI.scale(CHIP_GAP))), Disposable {

    private data class Entry(val key: String, val label: String, val item: AcpContextItem)

    private val entries = LinkedHashMap<String, Entry>()
    /** Active file and its selection shown because of auto attach; not part of [contextItems] until sent. */
    private var autoEntries: List<Entry> = emptyList()
    /**
     * Preview chips the user removed with their x button. Auto attach must not bring them straight
     * back, so they stay dismissed until their key disappears from the preview, which happens when
     * another file becomes active or the selection changes.
     */
    private val dismissedAutoKeys = LinkedHashSet<String>()
    /** Coalesces the selection previews produced while the user is still dragging over the text. */
    private val previewAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val emptyLabel = JBLabel("No context attached").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }

    init {
        // FlowLayout already contributes CHIP_GAP on the outer sides, so only the remainder is
        // added here to reach CONTENT_MARGIN from the tool window edges.
        border = JBUI.Borders.empty(2, SandboxAgentLayout.CONTENT_MARGIN - CHIP_GAP)
        isOpaque = true
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = refreshAutoAttachPreview()
            },
        )
        // The multicaster covers every editor, including the ones opened after this panel.
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) = scheduleAutoAttachPreview()
            },
            this,
        )
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) = scheduleAutoAttachPreview()
            },
            this,
        )
        rebuild()
        refreshAutoAttachPreview()
    }

    /** Only the parent of the message bus connection and the listeners made in [init]. */
    override fun dispose() {
    }

    /**
     * The auto attach setting can be toggled while the tool window is hidden, so the preview is
     * re-evaluated whenever the strip comes back on screen.
     */
    override fun addNotify() {
        super.addNotify()
        refreshAutoAttachPreview()
    }

    override fun getBackground(): Color = SandboxAgentColors.PANEL_BACKGROUND

    /** Attaches a virtual file or directory within the project. Safe to call on any VirtualFile. */
    fun attachVirtualFile(file: VirtualFile): Boolean {
        if (!file.isValid || !AttachFileUtils.isInsideProject(file, project)) return false

        if (file.isDirectory) {
            val item = AcpContextItem(path = file.path, content = "")
            addEntry(Entry("dir:${file.path}", file.name.ifEmpty { project.name }, item))
            return true
        }

        val content = ReadAction.compute<String, RuntimeException> {
            val doc = FileDocumentManager.getInstance().getDocument(file)
            doc?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")
        }
        val item = AcpContextItem(path = file.path, content = content)
        addEntry(Entry("file:${file.path}", file.name, item))
        return true
    }

    /** Attaches the file opened in the active editor. Safe to call without any open editor. */
    fun attachActiveFile(): Boolean {
        val snapshot = readEditorSnapshot() ?: return false
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(snapshot.path) ?: return false
        if (!AttachFileUtils.isInsideProject(virtualFile, project)) return false
        val item = AcpContextItem(path = snapshot.path, content = snapshot.text)
        addEntry(Entry("file:${snapshot.path}", snapshot.presentableName, item))
        return true
    }

    /** Attaches the current selection. Returns `false` when nothing is selected. */
    fun attachSelection(): Boolean {
        val snapshot = readEditorSnapshot() ?: return false
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(snapshot.path) ?: return false
        if (!AttachFileUtils.isInsideProject(virtualFile, project)) return false
        val selection = snapshot.selectedText ?: return false
        val key = "selection:${snapshot.path}:${snapshot.startLine}-${snapshot.endLine}"
        val item = AcpContextItem(
            path = snapshot.path,
            content = selection,
            startLine = snapshot.startLine,
            endLine = snapshot.endLine,
        )
        addEntry(Entry(key, "${snapshot.presentableName}:${snapshot.startLine}-${snapshot.endLine}", item))
        return true
    }

    /**
     * Applies the opt-in auto attach setting; called right before a prompt is sent. Attaches
     * exactly what the preview chips show, so a chip the user removed is not sent.
     */
    fun applyAutoAttach() {
        if (!isAutoAttachEnabled()) return
        autoAttachPreviewEntries()
            .filterNot { it.key in dismissedAutoKeys }
            .forEach { entries[it.key] = it }
        rebuild()
    }

    /**
     * Re-reads the active file and its selection into the auto attach preview chips. Called
     * whenever the editor changes so the chips always name what the next prompt would pick up.
     */
    fun refreshAutoAttachPreview() {
        onEdt {
            val next = if (isAutoAttachEnabled()) autoAttachPreviewEntries() else emptyList()
            // A dismissal only holds while the same file / selection is still the one on offer.
            val stillOffered = next.mapTo(HashSet()) { it.key }
            val prunedDismissals = dismissedAutoKeys.retainAll(stillOffered)
            if (!prunedDismissals && next.map { it.key } == autoEntries.map { it.key }) return@onEdt
            autoEntries = next
            rebuild()
        }
    }

    /**
     * Same as [refreshAutoAttachPreview] but delayed, so a selection being dragged out settles
     * before it reaches the strip.
     */
    private fun scheduleAutoAttachPreview() {
        if (Disposer.isDisposed(this)) return
        previewAlarm.cancelAllRequests()
        previewAlarm.addRequest({ refreshAutoAttachPreview() }, PREVIEW_DELAY_MS)
    }

    fun contextItems(): List<AcpContextItem> = entries.values.map { it.item }

    fun clearAll() {
        entries.clear()
        dismissedAutoKeys.clear()
        rebuild()
        refreshAutoAttachPreview()
    }

    private fun isAutoAttachEnabled(): Boolean =
        runCatching { SandboxAgentSettingsState.getInstance(project) }.getOrNull()?.autoAttachContext == true

    /**
     * The active file, plus its selection when there is one, as preview entries. Empty when no
     * editor is open or the file is outside the project.
     */
    private fun autoAttachPreviewEntries(): List<Entry> {
        val snapshot = readEditorSnapshot() ?: return emptyList()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(snapshot.path) ?: return emptyList()
        if (!AttachFileUtils.isInsideProject(virtualFile, project)) return emptyList()
        val fileEntry = Entry(
            "file:${snapshot.path}",
            snapshot.presentableName,
            AcpContextItem(path = snapshot.path, content = snapshot.text),
        )
        val selection = snapshot.selectedText ?: return listOf(fileEntry)
        val selectionEntry = Entry(
            "selection:${snapshot.path}:${snapshot.startLine}-${snapshot.endLine}",
            "${snapshot.presentableName}:${snapshot.startLine}-${snapshot.endLine}",
            AcpContextItem(
                path = snapshot.path,
                content = selection,
                startLine = snapshot.startLine,
                endLine = snapshot.endLine,
            ),
        )
        return listOf(fileEntry, selectionEntry)
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater({ action() }, project.disposed)
    }

    private fun addEntry(entry: Entry) {
        entries[entry.key] = entry
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        val auto = autoEntries.filterNot { entries.containsKey(it.key) || it.key in dismissedAutoKeys }
        if (entries.isEmpty() && auto.isEmpty()) {
            add(emptyLabel)
        } else {
            auto.forEach { add(autoChip(it)) }
            entries.values.forEach { add(chip(it)) }
        }
        revalidate()
        repaint()
    }

    /**
     * A preview chip can be removed like any other one; auto attach then leaves it out of the next
     * prompt until the editor offers a different file or selection.
     */
    private fun autoChip(entry: Entry): JComponent = PromptAttachmentChips.chip(
        label = entry.label,
        icon = PromptAttachmentChips.iconFor(entry.item.path, selection = entry.key.startsWith("selection:")),
        tooltip = "${entry.item.path} (auto-attached)",
        onClick = openAction(entry),
        onRemove = {
            dismissedAutoKeys.add(entry.key)
            rebuild()
        },
    )

    /**
     * The chip itself is drawn by [PromptAttachmentChips], which the transcript reuses to keep a
     * sent prompt showing its attachments exactly as this strip did.
     *
     * The prompt is not sent yet, so following a chip goes to the workspace: the file opens, and
     * a selection is selected again. A directory has nothing to open, so its chip does not react.
     */
    private fun chip(entry: Entry): JComponent = PromptAttachmentChips.chip(
        label = entry.label,
        icon = PromptAttachmentChips.iconFor(entry.item.path, selection = entry.key.startsWith("selection:")),
        tooltip = entry.item.path,
        onClick = openAction(entry),
        onRemove = {
            entries.remove(entry.key)
            rebuild()
        },
    )

    private fun openAction(entry: Entry): (() -> Unit)? {
        if (entry.key.startsWith("dir:")) return null
        return { PromptAttachmentNavigation.openInEditor(project, entry.item.toAttachment()) }
    }

    private class EditorSnapshot(
        val path: String,
        val presentableName: String,
        val text: String,
        val selectedText: String?,
        val startLine: Int,
        val endLine: Int,
    )

    /** Reads the active editor state under a [ReadAction]; `null` when no editor is open. */
    private fun readEditorSnapshot(): EditorSnapshot? = ReadAction.compute<EditorSnapshot?, RuntimeException> {
        if (project.isDisposed) return@compute null
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.selectedTextEditor ?: return@compute null
        val file = manager.selectedFiles.firstOrNull() ?: return@compute null
        val selectionModel = editor.selectionModel
        val document = editor.document
        val selectedText = selectionModel.selectedText?.takeIf { it.isNotBlank() }
        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1
        EditorSnapshot(
            path = file.path,
            presentableName = file.name,
            text = document.text,
            selectedText = selectedText,
            startLine = startLine,
            endLine = endLine,
        )
    }

    private companion object {
        /** Long enough for a drag selection to settle, short enough to still feel immediate. */
        const val PREVIEW_DELAY_MS = 500

        /** Gap between the chips, also applied on the outer sides of the strip by its layout. */
        const val CHIP_GAP = 4
    }
}
