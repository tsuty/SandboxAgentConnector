package com.tsuty.sandboxagent.ui

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.editor.SpellCheckingEditorCustomizationProvider
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.tsuty.sandboxagent.acp.AvailableCommand
import com.tsuty.sandboxagent.actions.InsertNewlineAction
import com.tsuty.sandboxagent.actions.SendPromptAction
import org.intellij.plugins.markdown.lang.MarkdownFileType
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.border.Border

/**
 * Prompt editor with 2..8 line auto grow, a configurable submit shortcut and IME awareness.
 *
 * The document is a Markdown one, which buys two things from the platform for free: the Markdown
 * lexer highlights what the user types (`**bold**`, `` `code` ``, `~~strike~~`, headers, links) and
 * `MarkdownCompletionContributor` completes the language of a fenced code block. Both are what the
 * prompt is eventually sent as anyway, so the field now reads like the transcript it produces.
 */
class PromptInputPanel(
    private val project: Project,
    private val onSubmit: (String) -> Unit,
) : EditorTextField(null, project, MarkdownFileType.INSTANCE, false, false) {

    /** `true` while an input method composition (e.g. Japanese conversion) is in progress. */
    @Volatile
    private var composing: Boolean = false

    private val log = Logger.getInstance(PromptInputPanel::class.java)

    /** Slash commands last advertised by the agent through `available_commands_update`. */
    private var availableCommands: List<AvailableCommand> = emptyList()
    private var slashPopup: SlashCommandPopup? = null

    init {
        setPlaceholder(DEFAULT_PLACEHOLDER)
        border = JBUI.Borders.empty()
        background = SandboxAgentColors.PANEL_BACKGROUND
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                revalidate()
                repaint()
                scheduleSlashPopupUpdate()
            }
        })
        // The field is created without a document, and EditorTextField only starts forwarding
        // document events once one exists, so the freshly created document is installed here;
        // otherwise `documentChanged` above would never fire and typing "/" would go unnoticed.
        // `document` lazily creates it via PsiDocumentManager, which is PSI/VFS read access and
        // therefore must run inside a read action (see RuntimeExceptionWithAttachments reports).
        ApplicationManager.getApplication().runReadAction { setDocument(document) }
    }

    /**
     * The editor background follows this value ([EditorTextField.initEditor] applies it), and the
     * new UI tool window decorator cannot override it either.
     */
    override fun getBackground(): Color = SandboxAgentColors.PANEL_BACKGROUND

    override fun createEditor(): EditorEx {
        val editor = super.createEditor()
        editor.settings.apply {
            isUseSoftWraps = true
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isRightMarginShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        editor.backgroundColor = SandboxAgentColors.PANEL_BACKGROUND
        editor.setVerticalScrollbarVisible(true)
        // Draws the frame of a transcript block instead of the platform one, which is square in
        // some themes and rounder in others.
        editor.setBorder(RoundedFrameBorder())
        // The rest of the padding: the frame itself already reserves FRAME_INSET around the text
        // (see RoundedFrameBorder), and the two together are the padding of a transcript block.
        // It has to live on the editor's contentComponent rather than on this panel's border,
        // because EditorTextField resets its own border on focus changes (updateBorder).
        editor.contentComponent.border = JBUI.Borders.empty(TEXT_PADDING)
        // A prompt is prose, so the spell checker would underline half of it (and every identifier
        // the user pastes in); the code inspections of the Markdown plugin stay on, because that is
        // what injects — and highlights — the code inside a fence.
        // Customizing the editor restarts the daemon code analyzer, which accesses PSI/Document
        // and therefore must run inside a read action.
        ApplicationManager.getApplication().runReadAction {
            SpellCheckingEditorCustomizationProvider.getInstance().disabledCustomization?.customize(editor)
        }
        // Opens the fence language completion while typing instead of on Ctrl+Space only. The
        // contributor is bound to FENCE_LANG, so the popup cannot appear in ordinary prose.
        editor.putUserData(AutoPopupController.ALWAYS_AUTO_POPUP, true)
        installSubmitShortcut(editor)
        installSlashCommandShortcuts(editor)
        installImeGuard(editor.contentComponent)
        return editor
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        val editor = editor ?: return base
        val lines = document.lineCount.coerceIn(MIN_LINES, MAX_LINES)
        val insets = insets
        val height =
            lines * editor.lineHeight + insets.top + insets.bottom + JBUI.scale(2 * FRAMED_PADDING) + JBUI.scale(4)
        return Dimension(base.width, height)
    }

    override fun getMinimumSize(): Dimension {
        val base = super.getMinimumSize()
        val editor = editor ?: return base
        return Dimension(base.width, MIN_LINES * editor.lineHeight + JBUI.scale(2 * FRAMED_PADDING) + JBUI.scale(8))
    }

    /**
     * Registers the submit and newline shortcuts using whatever keys are currently bound to
     * [SendPromptAction.ID] and [InsertNewlineAction.ID] in the active Keymap (Enter / Shift+Enter
     * by default), so users configure them under `Settings > Keymap` instead of a plugin setting.
     */
    private fun installSubmitShortcut(editor: EditorEx) {
        val component = editor.contentComponent
        val sendAction = object : DumbAwareAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            // Enter belongs to the completion popup of the platform while it is showing, otherwise
            // picking a fence language would send the prompt instead of inserting the language.
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !isLookupActive(editor)
            }

            // Enter completes the highlighted slash command instead of sending the prompt.
            override fun actionPerformed(e: AnActionEvent) {
                if (slashPopup?.applySelected() == true) return
                submit()
            }
        }
        sendAction.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(SendPromptAction.ID), component)

        val newlineAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = insertNewline(editor)
        }
        newlineAction.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(InsertNewlineAction.ID), component)
    }

    /** Inserts a newline at the caret instead of submitting the prompt. */
    private fun insertNewline(editor: EditorEx) {
        WriteCommandAction.runWriteCommandAction(project) {
            val caret = editor.caretModel
            val offset = caret.offset
            editor.document.insertString(offset, "\n")
            caret.moveToOffset(offset + 1)
        }
    }

    /**
     * Routes the completion keys to the slash command popup while it is showing. The actions stay
     * disabled otherwise, so the editor keeps its own caret movement, Tab and Escape behaviour.
     */
    private fun installSlashCommandShortcuts(editor: EditorEx) {
        registerPopupAction(editor, KeyEvent.VK_DOWN) { it.moveSelection(1) }
        registerPopupAction(editor, KeyEvent.VK_UP) { it.moveSelection(-1) }
        registerPopupAction(editor, KeyEvent.VK_TAB) { it.applySelected() }
        registerPopupAction(editor, KeyEvent.VK_ESCAPE) { it.hide(); true }
        val component = editor.contentComponent
        component.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) {
                // Showing the popup itself shuffles the focus around on some window managers, so a
                // temporary loss must be ignored: it would close the popup right after it opened.
                if (!event.isTemporary) hideSlashPopup()
            }
        })
    }

    private fun registerPopupAction(editor: EditorEx, keyCode: Int, handler: (SlashCommandPopup) -> Boolean) {
        val action = object : DumbAwareAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = slashPopup?.isVisible == true && !isLookupActive(editor)
            }

            override fun actionPerformed(e: AnActionEvent) {
                slashPopup?.let { handler(it) }
            }
        }
        action.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(keyCode, 0)), editor.contentComponent)
    }

    /**
     * `true` while the completion popup of the platform (the fence language list) is showing. The
     * locally registered keys step aside then, so the popup keeps its own Enter, Tab, arrows and
     * Escape; the slash command popup never shows at the same time (it needs a lone `/` token).
     */
    private fun isLookupActive(editor: EditorEx): Boolean =
        LookupManager.getInstance(project).activeLookup?.editor === editor

    /**
     * Replaces the advertised slash commands; an empty list disables the completion entirely (the
     * agent either reported no command or the session went away).
     */
    fun setAvailableCommands(commands: List<AvailableCommand>) {
        availableCommands = SlashCommandSupport.normalize(commands)
        log.debug("slash commands available: ${availableCommands.size}")
        setPlaceholder(if (availableCommands.isEmpty()) DEFAULT_PLACEHOLDER else COMMAND_PLACEHOLDER)
        scheduleSlashPopupUpdate()
        repaint()
    }

    /**
     * Popups may not be opened from inside the write action that typing runs in, so the update is
     * always pushed to a later EDT event.
     */
    private fun scheduleSlashPopupUpdate() {
        ApplicationManager.getApplication().invokeLater(
            { updateSlashPopup() },
            ModalityState.stateForComponent(this),
            project.disposed,
        )
    }

    /** Opens, refreshes or closes the completion for the text currently held by the editor. */
    private fun updateSlashPopup() {
        if (availableCommands.isEmpty() || !isShowing) {
            log.debug("slash popup skipped: commands=${availableCommands.size} showing=$isShowing")
            return hideSlashPopup()
        }
        val query = SlashCommandSupport.queryFor(text)
        if (query == null) {
            log.debug("slash popup skipped: prompt is not a lone command token")
            return hideSlashPopup()
        }
        val matches = SlashCommandSupport.filter(availableCommands, query)
        if (matches.isEmpty()) {
            log.debug("slash popup skipped: no command matches '$query'")
            return hideSlashPopup()
        }
        val popup = slashPopup ?: SlashCommandPopup(this) { applyCommand(it) }.also { slashPopup = it }
        popup.show(matches)
    }

    private fun hideSlashPopup() {
        slashPopup?.hide()
    }

    /** The whole prompt is the command token being completed, so it is rewritten as a whole. */
    private fun applyCommand(command: AvailableCommand) {
        val editor = editor as? EditorEx ?: return
        val completion = SlashCommandSupport.completionText(command)
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(0, editor.document.textLength, completion)
            editor.caretModel.moveToOffset(editor.document.textLength)
        }
    }

    override fun removeNotify() {
        hideSlashPopup()
        slashPopup = null
        super.removeNotify()
    }

    /** Tracks IME composition so a conversion confirming Enter never submits the prompt. */
    private fun installImeGuard(component: JComponent) {
        component.addInputMethodListener(object : InputMethodListener {
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                val text = event.text
                val composedLength = if (text == null) 0 else text.endIndex - event.committedCharacterCount
                composing = composedLength > 0
            }

            override fun caretPositionChanged(event: InputMethodEvent) = Unit
        })
    }

    private fun submit() {
        if (composing) return
        hideSlashPopup()
        val text = text.trim()
        if (text.isEmpty()) return
        onSubmit(text)
    }

    /** Entry point for [SendPromptAction] when invoked through the action system rather than the locally registered shortcut. */
    fun submitFromAction() = submit()

    /** Entry point for [InsertNewlineAction] when invoked through the action system rather than the locally registered shortcut. */
    fun insertNewlineFromAction() = (editor as? EditorEx)?.let { insertNewline(it) }

    fun clearInput() {
        text = ""
    }

    /**
     * The 1px frame of a transcript block (`.framed` in `transcript.css`): same color, same corner
     * radius, so the prompt field reads as one more block of the same document.
     *
     * The insets are [FRAME_INSET] rather than the 1px the line takes: this border belongs to the
     * editor's scroll pane, which paints its background — and therefore the corners of the rounded
     * rectangle — over everything inside its insets. A corner arc of radius `r` reaches `0.3 * r`
     * inward, so the band has to be wider than that for the corners to survive.
     */
    private class RoundedFrameBorder : Border {

        override fun getBorderInsets(component: Component): Insets = JBUI.insets(FRAME_INSET)

        override fun isBorderOpaque(): Boolean = false

        override fun paintBorder(component: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = JBColor.border()
                g2.stroke = BasicStroke(1f)
                // Half a pixel in, so the 1px wide stroke lands on the pixel row it is drawn along
                // instead of being spread over two by the antialiasing.
                val diameter = JBUIScale.scale(2f * SandboxAgentLayout.CONTENT_ARC)
                g2.draw(
                    RoundRectangle2D.Float(
                        x + 0.5f,
                        y + 0.5f,
                        width - 1f,
                        height - 1f,
                        diameter,
                        diameter,
                    )
                )
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        const val MIN_LINES = 2
        const val MAX_LINES = 8
        const val DEFAULT_PLACEHOLDER = "Ask the sandbox agent…"
        const val COMMAND_PLACEHOLDER = "Ask the sandbox agent… (\"/\" for commands)"

        /**
         * Padding, in pixels, between the editor text and the field's border on every side. Matches
         * the inner padding of the transcript blocks so both columns of text line up.
         */
        const val FRAMED_PADDING = SandboxAgentLayout.CONTENT_PADDING

        /** Width, in pixels, of the band [RoundedFrameBorder] draws its line in. */
        const val FRAME_INSET = 5

        /** The part of [FRAMED_PADDING] the frame itself does not already reserve. */
        const val TEXT_PADDING = FRAMED_PADDING - FRAME_INSET
    }
}
