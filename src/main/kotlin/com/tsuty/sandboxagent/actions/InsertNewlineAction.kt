package com.tsuty.sandboxagent.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.tsuty.sandboxagent.ui.PromptInputPanel
import javax.swing.SwingUtilities

/**
 * Registered purely so its shortcut is rebindable under `Settings > Keymap`. The actual key
 * handling is wired locally per editor in [PromptInputPanel] via
 * `KeymapUtil.getActiveKeymapShortcuts(ID)`, this action is a fallback for invocation through
 * the action system (e.g. Find Action).
 */
class InsertNewlineAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) ?: return
        SwingUtilities.getAncestorOfClass(PromptInputPanel::class.java, component)
            ?.let { it as PromptInputPanel }
            ?.insertNewlineFromAction()
    }

    companion object {
        const val ID = "com.tsuty.sandboxagent.actions.InsertNewline"
    }
}
