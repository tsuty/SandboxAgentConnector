package com.tsuty.sandboxagent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tsuty.sandboxagent.session.RecentSession
import com.tsuty.sandboxagent.session.RecentSessions
import com.tsuty.sandboxagent.session.SessionMetadataStore
import com.tsuty.sandboxagent.session.SessionSnapshotStore
import com.tsuty.sandboxagent.settings.SandboxAgentSettingsState
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * What the tool window shows while it has no tab: the IDE opens it without one, and closing the
 * last tab comes back here. The [RecentSessions] of every agent are listed from the local files
 * alone — nothing is connected until a row is picked — and `+` / its caret above stay the way to
 * start a new session.
 *
 * A row is handed to [onOpen], which enters the session with its agent, or opens it read only
 * when that agent is not enabled (see `SandboxAgentTabsPanel.openRecentSession`).
 */
class RecentSessionsPanel(
    private val project: Project,
    private val onOpen: (RecentSession) -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val model = DefaultListModel<RecentSession>()

    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = Renderer()
        emptyText.text = "No sessions are stored locally yet"
        emptyText.appendSecondaryText("Start one with + above", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = locationToIndex(e.point)
                if (index >= 0 && getCellBounds(index, index)?.contains(e.point) == true) onOpen(model.getElementAt(index))
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) selectedValue?.let(onOpen)
            }
        })
    }

    /** Bumped by every [refresh], so an older listing that finishes late is dropped. */
    @Volatile
    private var generation = 0

    @Volatile
    private var disposed = false

    init {
        background = UIUtil.getListBackground()
        val header = JBLabel("Recent sessions").apply {
            border = JBUI.Borders.empty(10, 12, 6, 12)
            foreground = UIUtil.getContextHelpForeground()
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    /** Lists the directory again off the EDT; called whenever this panel is about to be shown. */
    fun refresh() {
        val current = ++generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessions = runCatching { load() }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                if (disposed || current != generation) return@invokeLater
                model.clear()
                sessions.forEach(model::addElement)
                // The relative times are computed while rendering: a fresh paint is a fresh "now".
                list.repaint()
            }, project.disposed)
        }
    }

    private fun load(): List<RecentSession> {
        val store = SessionSnapshotStore.forProject(project)
        val metadata = runCatching { SessionMetadataStore.getInstance(project) }.getOrNull()
        return RecentSessions.load(
            index = store.index(),
            read = { store.loadFile(it.file) },
            legacyAgentId = SandboxAgentSettingsState.getInstance(project).legacyAgentIdOrDefault(),
            renameOverride = { metadata?.renameOverride(it) },
        )
    }

    override fun dispose() {
        disposed = true
    }

    /** The agent, the title, its name — marked when it is not enabled — and how long ago it was saved. */
    private inner class Renderer : ColoredListCellRenderer<RecentSession>() {
        override fun customizeCellRenderer(
            list: JList<out RecentSession>,
            value: RecentSession,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val settings = SandboxAgentSettingsState.getInstance(project)
            val enabled = settings.agentById(value.agentId)
            ipad = JBUI.insets(4, 12)
            icon = SandboxAgentIcons.agentIcon(
                enabled?.iconSvgOrNull(),
                if (enabled == null) AgentMark.DISCONNECTED else AgentMark.NONE,
                settings.agentColorOrNull(value.agentId),
            )
            append(value.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  " + (enabled?.nameOrDefault() ?: value.agentName ?: value.agentId), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (enabled == null) append(" (not enabled · read only)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            RelativeTime.format(value.savedAt)?.let { append("  · $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            toolTipText = value.sessionId
        }
    }
}
