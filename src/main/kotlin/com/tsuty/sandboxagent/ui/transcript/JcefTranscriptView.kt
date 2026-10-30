package com.tsuty.sandboxagent.ui.transcript

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.net.URI
import javax.swing.JComponent
import javax.swing.Timer

/**
 * The transcript rendered by the browser bundled with the JetBrains Runtime.
 *
 * One instance per tab: a Chromium renderer costs a process and tens of megabytes, so a session
 * switch inside a tab replaces the content of this document (see [ResetEvent]) instead of creating
 * another browser. A tab in the background holds its events back (see [onHidden]) instead of
 * patching a document nobody looks at.
 *
 * The browser is created **off screen**: a windowed JCEF component is a native child window and
 * would swallow every lightweight Swing popup drawn over it — the `/` command completion, the
 * `@file` chooser and the session history all open right over the transcript. Off screen rendering
 * hands the frames to Swing as an ordinary component instead, which puts the popups back on top.
 * The prompt editor stays a Swing component, so the usual downside of that mode (the IME of a
 * text field inside the browser) does not apply here.
 *
 * The document never navigates: a link of it is handed to the IDE instead (see [follow]), and a
 * document that was lost all the same is loaded again and filled from the log (see [recover]) —
 * a browser showing an error page where the transcript was is a tool window that cannot be used
 * any more, since nothing else ever draws it.
 *
 * Every method must be called on the EDT.
 *
 * [onPageSource] receives the current markup of the document when the developer asks for it
 * through the context menu, which only happens in an internal mode IDE (see [contextMenuHandler]).
 */
class JcefTranscriptView(
    parent: Disposable,
    private val onAction: (TranscriptAction) -> Unit,
    private val onPageSource: (String) -> Unit = {},
) : TranscriptView {

    private val browser = JBCefBrowser.createBuilder()
        .setOffScreenRendering(true)
        .build()

    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

    /**
     * Everything the document was told since the last [ResetEvent], which is both the queue and
     * the memory of the session.
     *
     * As a queue it has two reasons to exist: the page is still loading right after the tool
     * window opened, and a streamed answer arrives in far more chunks than a browser needs
     * repaints — the log is flushed once per [COALESCE_MS] instead of once per chunk.
     *
     * As a memory it is what a document that had to be loaded again is filled from: the events
     * are a patch protocol, so a fresh document without them is an empty transcript, and nothing
     * above this class keeps the history (see [record] for what is dropped from it).
     */
    private val log = mutableListOf<TranscriptEvent>()

    /** How many entries of [log] the document has already been given. */
    private var flushed = 0

    /** Set while the next flush has to drop the entries of the document first (see [onLoaded]). */
    private var resetPending = false

    private var ready = false

    /**
     * `false` while the tab of this transcript is in the background (see [onHidden]): the log keeps
     * growing, but nothing is flushed until the tab is shown again (see [onShown]).
     */
    private var shown = true

    /** Loads of our own, capped so a document that cannot be loaded is not loaded forever. */
    private var recoveries = 0

    private val flushTimer = Timer(COALESCE_MS) { flush() }.apply {
        isRepeats = false
    }

    override val component: JComponent get() = browser.component

    init {
        query.addHandler { payload ->
            // The handler runs on a CEF thread; the actions all end up touching the IDE.
            TranscriptProtocol.decodeAction(payload)?.let { action -> onEdt { onAction(action) } }
            null
        }
        browser.jbCefClient.addLoadHandler(loadHandler(), browser.cefBrowser)
        browser.jbCefClient.addRequestHandler(requestHandler(), browser.cefBrowser)
        browser.jbCefClient.addLifeSpanHandler(lifeSpanHandler(), browser.cefBrowser)
        browser.jbCefClient.addContextMenuHandler(contextMenuHandler(), browser.cefBrowser)
        Disposer.register(parent, query)
        Disposer.register(parent, browser)
        browser.loadHTML(TranscriptDocument.html())
    }

    override fun post(events: List<TranscriptEvent>) {
        // A toast is a moment's confirmation: shown later, after a tab switch, it confirms nothing.
        val kept = if (shown) events else events.filter { it !is ToastEvent }
        if (kept.isEmpty()) return
        kept.forEach { record(it) }
        if (!ready || !shown) return
        if (!flushTimer.isRunning) flushTimer.restart()
    }

    override fun showSearch() {
        execute("window.transcript.showSearch();")
    }

    /**
     * The document is only moved, not pinned — and that is enough: a batch of events keeps the
     * transcript at its bottom edge whenever it already was there, so scrolling down now is what
     * makes the entries arriving afterwards scroll along.
     */
    override fun scrollToBottom() {
        execute("window.transcript.scrollToBottom();")
    }

    /**
     * Wakes the browser up after its card was brought back to the front.
     *
     * A hidden card is not laid out: a tool window resized, or moved to a screen of another scale,
     * while the tab was in the background leaves Chromium drawing for the old size, and Swing
     * keeps stretching the last frame it got — a transcript that looks shrunk and does not scroll.
     * Telling the browser its size and screen again makes it render a fresh frame.
     *
     * A document that ran out of [recoveries] is given another chance as well: being looked at
     * again is the moment a reader expects it to work.
     *
     * Whatever arrived while the tab was hidden goes to the document now, as one batch.
     */
    override fun onShown() {
        shown = true
        val component = browser.component
        val cefBrowser = browser.cefBrowser
        cefBrowser.notifyScreenInfoChanged()
        if (component.width > 0 && component.height > 0) {
            cefBrowser.wasResized(component.width, component.height)
        }
        component.revalidate()
        component.repaint()
        if (!ready && recoveries >= MAX_RECOVERIES) {
            recoveries = 0
            recover()
        }
        flushTimer.stop()
        flush()
    }

    /**
     * Holds the events back while the tab of this transcript is in the background.
     *
     * Every tab stays connected and keeps receiving its session, but a hidden off screen browser
     * may have its rendering throttled or stopped by Chromium (or by macOS App Nap), and a DOM
     * patched there the whole time is the one that came back frozen. The log already is the queue
     * of a document that is not ready yet, so a hidden one simply waits the same way.
     */
    override fun onHidden() {
        shown = false
        flushTimer.stop()
    }

    /**
     * Installs the bridge the document reports its actions through and hands over the log.
     *
     * Everything here happens on every load end, not once: a document that was loaded again (a
     * crashed renderer, a `Reload` from the developer tools, a [recover] of our own) starts
     * without the bridge and without a single entry, so the whole log goes in again.
     *
     * The replay drops the entries of the document first, which makes it safe to run against a
     * document that is *not* empty: a load end also arrives for a navigation inside the page, and
     * a transcript with every block twice is worse than one that lost its folding state.
     */
    private fun onLoaded() {
        execute("window.transcriptHost = function(payload) { ${query.inject("payload")} };")
        ready = true
        recoveries = 0
        flushed = 0
        resetPending = true
        flush()
    }

    /**
     * Loads the document again after it was lost: a navigation that got through all the same, a
     * renderer that died, an error page of Chromium where the transcript was. The log is left
     * untouched, so [onLoaded] fills the fresh document with the whole session.
     */
    private fun recover() {
        ready = false
        if (recoveries >= MAX_RECOVERIES) return
        recoveries++
        browser.loadHTML(TranscriptDocument.html())
    }

    // ------------------------------------------------------------------ navigation

    /**
     * Watches the loads of the browser: our own document is what it is allowed to show, anything
     * else — and every load that failed — is answered by loading the document again.
     */
    private fun loadHandler() = object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame != null && !frame.isMain) return
            val url = frame?.url ?: cefBrowser?.url
            onEdt {
                when {
                    isDocument(url) -> onLoaded()
                    // The page a browser starts on, before `loadHTML` was even called.
                    isEmptyPage(url) -> Unit
                    else -> recover()
                }
            }
        }

        override fun onLoadError(
            cefBrowser: CefBrowser?,
            frame: CefFrame?,
            errorCode: CefLoadHandler.ErrorCode?,
            errorText: String?,
            failedUrl: String?,
        ) {
            if (frame != null && !frame.isMain) return
            // A navigation we cancelled ourselves is reported as an error and changes nothing.
            if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
            onEdt { if (!isDocument(cefBrowser?.url)) recover() }
        }
    }

    /**
     * Cancels every navigation that would leave the document.
     *
     * The rows of a tool call ask the host to open a file, but the rendered Markdown of an answer
     * can carry a real link, and following one navigated the browser away from a page that is
     * never loaded again — the transcript was replaced by whatever the link led to, or by the
     * error page of Chromium. This is the layer that catches all of it: a click, a middle click,
     * a keyboard activation, a `window.location`, a redirect.
     */
    private fun requestHandler() = object : CefRequestHandlerAdapter() {
        override fun onBeforeBrowse(
            cefBrowser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
            userGesture: Boolean,
            isRedirect: Boolean,
        ): Boolean {
            val url = request?.url ?: return false
            if (isDocument(url) || isEmptyPage(url)) return false
            follow(url)
            return true
        }

        /**
         * A renderer that died (a crash, or macOS reclaiming memory) leaves the last frame on
         * screen and never reports a load end, so nothing else would ever load the document again.
         */
        override fun onRenderProcessTerminated(
            cefBrowser: CefBrowser?,
            status: CefRequestHandler.TerminationStatus?,
            errorCode: Int,
            errorString: String?,
        ) {
            onEdt { recover() }
        }
    }

    /** Keeps a `target="_blank"` from opening a native window of its own. */
    private fun lifeSpanHandler() = object : CefLifeSpanHandlerAdapter() {
        override fun onBeforePopup(
            cefBrowser: CefBrowser?,
            frame: CefFrame?,
            targetUrl: String?,
            targetFrameName: String?,
        ): Boolean {
            targetUrl?.let { follow(it) }
            return true
        }
    }

    /** Hands the link a reader followed to the IDE, which is where it belongs. */
    private fun follow(url: String) {
        val action = when (url.substringBefore(':', "").lowercase()) {
            "http", "https", "mailto" -> OpenUrlAction(url)
            "file" -> filePath(url)?.let { OpenFileAction(it) }
            else -> null
        } ?: return
        // The handlers run on a CEF thread; every action ends up touching the IDE.
        onEdt { onAction(action) }
    }

    /**
     * `true` for the URL of the transcript document itself, the one page the browser may show.
     *
     * `loadHTML` serves the document as `file:///jbcefbrowser/<random>#url=…`, so a link of a
     * relative path becomes a sibling of it (`file:///jbcefbrowser/docs/ToDo.md`): the prefix is
     * the same and only the shape of the path tells the two apart.
     */
    private fun isDocument(url: String?): Boolean {
        val path = url?.substringBefore('#') ?: return false
        return path.startsWith(DOCUMENT_URL) && !path.removePrefix(DOCUMENT_URL).contains('/')
    }

    /** The empty page a browser sits on, which is neither our document nor a navigation. */
    private fun isEmptyPage(url: String?): Boolean = url.isNullOrEmpty() || url.startsWith("about:")

    /**
     * The path a `file:` URL points at, with the internal prefix of the document taken off: what
     * the writer of a relative link meant is the part behind it, which `ToolCallPresentation`
     * resolves against the project root the same way it resolves a path of the sandbox.
     */
    private fun filePath(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return path.removePrefix(DOCUMENT_PATH).takeIf { it.isNotBlank() }
    }

    /**
     * The context menu of the browser, with `View Page Source` taken out: the document is handed
     * to the browser as a string ([JBCefBrowser.loadHTML]), and `view-source:` asks the network
     * stack for the URL again — which for this document yields nothing, so the entry only ever
     * looked broken.
     *
     * The markup is still worth reading while working on the transcript itself, so an internal
     * mode IDE (`-Didea.is.internal=true`, which is how the plugin is run from the sandbox) gets
     * `Show Page Source` instead: it serializes the live DOM and hands it to [onPageSource], which
     * shows it in an editor tab. A released IDE shows neither entry.
     */
    private fun contextMenuHandler() = object : CefContextMenuHandlerAdapter() {
        override fun onBeforeContextMenu(
            cefBrowser: CefBrowser?,
            frame: CefFrame?,
            params: CefContextMenuParams?,
            model: CefMenuModel?,
        ) {
            if (model == null) return
            model.remove(CefMenuModel.MenuId.MENU_ID_VIEW_SOURCE)
            if (!ApplicationManager.getApplication().isInternal) return
            model.addSeparator()
            model.addItem(SHOW_SOURCE_COMMAND, "Show Page Source")
        }

        override fun onContextMenuCommand(
            cefBrowser: CefBrowser?,
            frame: CefFrame?,
            params: CefContextMenuParams?,
            commandId: Int,
            eventFlags: Int,
        ): Boolean {
            if (commandId != SHOW_SOURCE_COMMAND) return false
            // The visitor is called back on a CEF thread, and the editor needs the EDT.
            cefBrowser?.getSource { source -> onEdt { onPageSource(source ?: "") } }
            return true
        }
    }

    // ------------------------------------------------------------------ the log

    /**
     * Appends [event] to the log, dropping what it makes stale — the log is replayed, so it keeps
     * what the transcript is, not how it came to be.
     *
     * A [ResetEvent] drops the entries before it, the theme and the agent excepted: neither the
     * colors nor who the agent is are part of what a reset clears, and the host only sends them
     * again when the IDE theme or the settings change. The Markdown rendering of a finished stream
     * ([BlockHtmlEvent]) makes every chunk of it weight.
     */
    private fun record(event: TranscriptEvent) {
        when (event) {
            is ThemeEvent -> forget { it is ThemeEvent }
            is AgentEvent -> forget { it is AgentEvent }
            is ResetEvent -> forget { it !is ThemeEvent && it !is AgentEvent }
            is BlockHtmlEvent -> forget { it is StreamEvent && it.id == event.id }
            else -> Unit
        }
        log += event
    }

    /** Drops every logged event [stale] answers for, keeping [flushed] on the same boundary. */
    private fun forget(stale: (TranscriptEvent) -> Boolean) {
        var index = 0
        while (index < log.size) {
            if (!stale(log[index])) {
                index++
                continue
            }
            log.removeAt(index)
            if (index < flushed) flushed--
        }
    }

    private fun flush() {
        if (!ready || !shown) return
        if (!resetPending && flushed >= log.size) return
        val events =
            if (resetPending) listOf(ResetEvent()) + log
            else log.subList(flushed, log.size).toList()
        resetPending = false
        flushed = log.size
        // A toast is shown for a moment: a document loaded again must not show it a second time.
        forget { it is ToastEvent }
        execute("window.transcript.apply(${jsLiteral(TranscriptProtocol.encode(events))});")
    }

    private fun execute(script: String) {
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url ?: "", 0)
    }

    /**
     * JSON is a JavaScript literal already, except for the two line separators that are legal in
     * a JSON string but end a line in a script.
     */
    private fun jsLiteral(json: String): String = json
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action)
    }

    private companion object {
        /**
         * Window a batch of events is collected in. Long enough to fold the chunks of a streamed
         * answer into one repaint, short enough to keep the answer looking like it is streaming.
         */
        private const val COALESCE_MS = 33

        /** Id of the one menu entry of our own; CEF reserves this range for the client. */
        private const val SHOW_SOURCE_COMMAND = CefMenuModel.MenuId.MENU_ID_USER_FIRST

        /** Where `JBCefBrowser.loadHTML` serves a document (`JBCefFileSchemeHandlerFactory`). */
        private const val DOCUMENT_URL = "file:///jbcefbrowser/"

        /** The same prefix as the path of a `file:` URL, which is what a link is resolved to. */
        private const val DOCUMENT_PATH = "/jbcefbrowser/"

        /** Loads of our own in a row; past this the document is broken, not merely lost. */
        private const val MAX_RECOVERIES = 3
    }
}
