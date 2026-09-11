package com.tsuty.sandboxagent.ui

/**
 * The few sizes the Swing part of the tool window shares with the transcript document.
 *
 * The transcript is styled by `transcript/transcript.css`, and the controls around it are laid out
 * by Swing: both have to agree on where the content column starts and on how a bordered block is
 * drawn, or the prompt editor and the entries above it read as two different columns of text.
 * These are the values of that stylesheet, so a change there has exactly one place to be mirrored.
 *
 * Every value is in unscaled pixels: the users apply `JBUI.scale` / `JBUI.Borders`, which do.
 */
object SandboxAgentLayout {

    /** Distance from the tool window edge to the content, the `body` padding of the stylesheet. */
    const val CONTENT_MARGIN = 14

    /** Inner padding of a bordered block, the `.framed` padding of the stylesheet. */
    const val CONTENT_PADDING = 14

    /** Corner radius of a bordered block, the `.framed` border-radius of the stylesheet. */
    const val CONTENT_ARC = 10

    /** Distance from the top edge of the tool window to the first toolbar row. */
    const val ROW_TOP_MARGIN = 7
}
