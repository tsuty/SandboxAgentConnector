package com.tsuty.sandboxagent.ui.transcript

import javax.swing.JComponent

/**
 * Where the transcript is drawn: the embedded browser ([JcefTranscriptView]) or, when the runtime
 * has no JCEF, the plain text fallback ([PlainTextTranscriptView]).
 *
 * Everything above this interface works in events only, so the transcript logic ([TranscriptPanel])
 * has no idea which of the two is in use.
 */
interface TranscriptView {

    val component: JComponent

    fun post(events: List<TranscriptEvent>)

    fun post(event: TranscriptEvent) = post(listOf(event))

    /** Opens the search bar of the transcript (`Ctrl+F` is handled inside the document as well). */
    fun showSearch() = Unit

    /**
     * Scrolls to the newest entry, wherever the reader had scrolled to.
     *
     * The transcript follows its bottom edge on its own as long as it is read there, so this is
     * for the moments a reader is somewhere else and the newest entry is the one they want: a
     * prompt that was just sent, or the jump of the document itself.
     */
    fun scrollToBottom() = Unit

    /**
     * Called when the tab holding this view is brought to the front again, after it was hidden:
     * whatever changed meanwhile (the size, the screen) is picked up now, and the events held back
     * while it was hidden are drawn.
     */
    fun onShown() = Unit

    /**
     * Called when the tab holding this view was sent to the background: the events keep arriving
     * but may be held back until [onShown].
     */
    fun onHidden() = Unit
}
