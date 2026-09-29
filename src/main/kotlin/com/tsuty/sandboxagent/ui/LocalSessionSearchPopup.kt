package com.tsuty.sandboxagent.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.tsuty.sandboxagent.session.LocalSessionIndex
import com.tsuty.sandboxagent.session.LocalSessionKind
import com.tsuty.sandboxagent.session.LocalSessionPreview
import com.tsuty.sandboxagent.session.LocalSessionSearch
import com.tsuty.sandboxagent.session.LocalSessionSearchResult
import com.tsuty.sandboxagent.session.SessionSnapshotStore
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultHighlighter

/**
 * `Search Local Sessions` (the tool window options menu): a search field, the `Include deleted
 * sessions` checkbox, the matching local session files and, under them, a preview of the selected
 * one: the matching part of its JSON, pretty printed, with the query highlighted
 * ([LocalSessionSearch.preview]).
 *
 * Every keystroke restarts a debounced search ([DEBOUNCE_MILLIS]) off the EDT, and a search that
 * a newer one overtook stops between two files and never reaches the list (see [generation]).
 * The preview is read off the EDT too, and one overtaken by a newer selection is dropped the same
 * way (see [previewGeneration]).
 */
internal class LocalSessionSearchPopup private constructor(
    private val project: Project,
    private val onChoose: (LocalSessionSearchResult) -> Unit,
) {
    private val searchField = SearchTextField(false)
    private val includeDeleted = JBCheckBox("Include deleted sessions")
    private val model = DefaultListModel<LocalSessionSearchResult>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ResultRenderer()
        emptyText.text = TYPE_TO_SEARCH
    }
    private val preview = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        border = JBUI.Borders.empty(4, 6)
        emptyText.text = "Select a result to preview the match"
    }

    /** Bumped by every new search; a search whose number is not the latest one is discarded. */
    private val generation = AtomicInteger()

    /** Bumped by every new selection; a preview whose number is not the latest one is discarded. */
    private val previewGeneration = AtomicInteger()

    /** The query the results in the list were found with, highlighted in their preview. */
    private var resultsQuery = ""

    private lateinit var popup: JBPopup
    private lateinit var alarm: Alarm
    private lateinit var previewAlarm: Alarm

    private fun component(): JComponent {
        val north = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(searchField, BorderLayout.CENTER)
            add(includeDeleted, BorderLayout.SOUTH)
        }
        val panel = JPanel(BorderLayout())
        panel.add(north, BorderLayout.NORTH)
        val splitter = OnePixelSplitter(true, RESULTS_PROPORTION).apply {
            firstComponent = JBScrollPane(list).apply { border = JBUI.Borders.empty() }
            secondComponent = JBScrollPane(preview).apply { border = JBUI.Borders.empty() }
        }
        panel.add(splitter, BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)
        return panel
    }

    private fun install() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch()
        })
        includeDeleted.addActionListener { scheduleSearch() }
        // The caret stays in the field: the arrows move through the results and Enter opens one.
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> ScrollingUtil.moveDown(list, e.modifiersEx).also { e.consume() }
                    KeyEvent.VK_UP -> ScrollingUtil.moveUp(list, e.modifiersEx).also { e.consume() }
                    KeyEvent.VK_ENTER -> chooseSelected().also { e.consume() }
                }
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) chooseSelected()
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) chooseSelected().also { e.consume() }
            }
        })
        list.addListSelectionListener { e -> if (!e.valueIsAdjusting) schedulePreview() }
    }

    private fun schedulePreview() {
        val result = list.selectedValue
        val query = resultsQuery
        val current = previewGeneration.incrementAndGet()
        previewAlarm.cancelAllRequests()
        if (result == null) {
            showPreview(null)
            return
        }
        previewAlarm.addRequest({
            val text = runCatching { result.file.file.readText(StandardCharsets.UTF_8) }.getOrNull()
            val found = text?.let { LocalSessionSearch.preview(it, query) }
            ApplicationManager.getApplication().invokeLater(
                { if (previewGeneration.get() == current && !popup.isDisposed) showPreview(found) },
                ModalityState.any(),
            )
        }, 0)
    }

    /** Shows [found] with every match highlighted, scrolled to the first one. */
    private fun showPreview(found: LocalSessionPreview?) {
        preview.highlighter.removeAllHighlights()
        preview.text = found?.text.orEmpty()
        val matches = found?.matches.orEmpty()
        val painter = DefaultHighlighter.DefaultHighlightPainter(matchBackground())
        matches.forEach { range -> preview.highlighter.addHighlight(range.first, range.last + 1, painter) }
        preview.caretPosition = matches.firstOrNull()?.first ?: 0
    }

    private fun matchBackground() =
        EditorColorsManager.getInstance().globalScheme
            .getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor
            ?: JBColor.YELLOW

    private fun scheduleSearch() {
        val query = searchField.text.orEmpty()
        val deleted = includeDeleted.isSelected
        val current = generation.incrementAndGet()
        alarm.cancelAllRequests()
        if (query.isBlank()) {
            show(emptyList(), query, TYPE_TO_SEARCH)
            return
        }
        alarm.addRequest({ runSearch(query, deleted, current) }, DEBOUNCE_MILLIS)
    }

    private fun runSearch(query: String, includeDeleted: Boolean, current: Int) {
        val isCancelled = { generation.get() != current }
        val files = runCatching { LocalSessionIndex.scan(SessionSnapshotStore.directoryFor(project)) }
            .getOrDefault(LocalSessionIndex.EMPTY)
            .searchable(includeDeleted)
        val results = LocalSessionSearch.search(files, query, isCancelled)
        if (isCancelled()) return
        ApplicationManager.getApplication().invokeLater(
            { if (!isCancelled() && !popup.isDisposed) show(results, query, "No local session contains \"$query\"") },
            ModalityState.any(),
        )
    }

    private fun show(results: List<LocalSessionSearchResult>, query: String, emptyText: String) {
        resultsQuery = query
        model.clear()
        results.forEach { model.addElement(it) }
        list.emptyText.text = emptyText
        if (results.isNotEmpty()) list.selectedIndex = 0 else schedulePreview()
    }

    private fun chooseSelected() {
        val result = list.selectedValue ?: return
        popup.cancel()
        onChoose(result)
    }

    /** Icon, title and — for a backup or a deleted file — its kind and date; the match is in the preview. */
    private class ResultRenderer : ColoredListCellRenderer<LocalSessionSearchResult>() {
        override fun customizeCellRenderer(
            list: JList<out LocalSessionSearchResult>,
            value: LocalSessionSearchResult,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            icon = LocalSessionPresentation.kindIcon(value.file.kind)
            append(value.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.file.kind != LocalSessionKind.CURRENT) {
                append("  " + LocalSessionPresentation.readOnlyStatus(value.file), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            toolTipText = value.sessionId
        }
    }

    companion object {
        private const val DEBOUNCE_MILLIS = 200
        private const val PREFERRED_WIDTH = 560
        private const val PREFERRED_HEIGHT = 480

        /** Share of the height the results take; the preview gets the rest (about 6–10 lines). */
        private const val RESULTS_PROPORTION = 0.55f
        private const val TYPE_TO_SEARCH = "Type to search the local sessions"

        /** Opens the popup centred on [anchor], the tool window component it belongs to. */
        fun show(project: Project, anchor: JComponent, onChoose: (LocalSessionSearchResult) -> Unit) {
            val search = LocalSessionSearchPopup(project, onChoose)
            val component = search.component()
            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(component, search.searchField.textEditor)
                .setTitle("Search Local Sessions")
                .setProject(project)
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .setFocusable(true)
                .setCancelOnClickOutside(true)
                .setMinSize(Dimension(JBUI.scale(320), JBUI.scale(200)))
                .setDimensionServiceKey(project, "SandboxAgentConnector.LocalSessionSearch", true)
                .createPopup()
            search.popup = popup
            search.alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, popup)
            search.previewAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, popup)
            // Any search or preview still running is discarded once the popup is gone.
            Disposer.register(popup) {
                search.generation.incrementAndGet()
                search.previewGeneration.incrementAndGet()
            }
            search.install()
            popup.showInCenterOf(anchor)
        }
    }
}
