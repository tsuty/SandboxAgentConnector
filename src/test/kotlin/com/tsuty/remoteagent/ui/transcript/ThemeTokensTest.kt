package com.tsuty.remoteagent.ui.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThemeTokensTest {

    private val fallbacks = listOf("system-ui", "sans-serif")

    /**
     * The IDE happily uses a font the operating system does not offer (JetBrains Mono ships inside
     * the IDE): without a fallback list the browser falls back to its own default family, which is
     * a serif one, and the whole transcript turns into Mincho / Times.
     */
    @Test
    fun `a font is asked for by name and followed by the generic families`() {
        assertEquals(""""JetBrains Mono", system-ui, sans-serif""", ThemeTokens.fontStack("JetBrains Mono", fallbacks))
    }

    /** `.AppleSystemUIFont` is not even a legal CSS identifier, so it must not be asked for. */
    @Test
    fun `a private system font alias is left out`() {
        assertEquals("system-ui, sans-serif", ThemeTokens.fontStack(".AppleSystemUIFont", fallbacks))
        assertEquals("system-ui, sans-serif", ThemeTokens.fontStack(null, fallbacks))
    }

    /** The quiet rows follow the base size, and stay one step below it however small it gets. */
    @Test
    fun `the small size is derived from the base size`() {
        assertEquals(11, ThemeTokens.smallFontSize(13))
        assertEquals(17, ThemeTokens.smallFontSize(20))
        assertEquals(1, ThemeTokens.smallFontSize(1))
    }
}
