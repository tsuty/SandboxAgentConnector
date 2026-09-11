package com.tsuty.sandboxagent.ui.transcript

import com.tsuty.sandboxagent.acp.ToolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The icons of the transcript document (`docs/development/ICONS.md`).
 *
 * The drawings are files, and a name the document asks for without a file behind it does not fail
 * anywhere at runtime — the icon silently becomes `other`. That is what these tests are for.
 */
class TranscriptIconsTest {

    private val directory = File("src/main/resources/icons/transcript")

    @Test
    fun `every icon the document asks for has a drawing`() {
        TranscriptIcons.NAMES.forEach { name ->
            assertTrue(File(directory, "$name.svg").isFile, "icons/transcript/$name.svg is missing")
        }
    }

    /** A kind is used as the icon name verbatim, so a new `ToolKind` needs a new drawing. */
    @Test
    fun `every tool kind is covered`() {
        val kinds = ToolKind::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }

        assertTrue(kinds.isNotEmpty(), "no ToolKind constants found")
        kinds.forEach { kind ->
            assertTrue(TranscriptIcons.KINDS.contains(kind), "ToolKind '$kind' has no icon")
        }
    }

    /** Nothing but a square drawing survives the 16x16 box the document paints it in. */
    @Test
    fun `every drawing is square`() {
        val files = directory.listFiles { file -> file.extension == "svg" }.orEmpty()
        assertTrue(files.isNotEmpty(), "no icons found")
        files.forEach { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val box = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+"))
            assertEquals(4, box.size, "${file.name} has no viewBox")
            assertEquals(box[2], box[3], "${file.name} is not square")
        }
    }

    /**
     * The sprite is one document, so an `id` of an export (Illustrator writes `id="uuid-…"`) would
     * collide with the one of the next file and send `<use>` to the wrong drawing.
     */
    @Test
    fun `the sprite carries no id and no color of its own`() {
        val sprite = TranscriptIcons.sprite()

        assertEquals(
            TranscriptIcons.NAMES.size,
            Regex("<symbol").findAll(sprite).count(),
            "one symbol per icon is expected",
        )
        TranscriptIcons.NAMES.forEach { name ->
            assertTrue(sprite.contains("""id="${TranscriptIcons.PREFIX}$name""""), "no symbol for $name")
        }
        assertFalse(Regex("""id="(?!ra-icon-)""").containsMatchIn(sprite), "a foreign id survived: $sprite")
        assertFalse(Regex("""(fill|stroke)="#""").containsMatchIn(sprite), "a color literal survived: $sprite")
    }

    /** An export may be dropped in as the drawing tool wrote it. */
    @Test
    fun `an export is normalized into a symbol`() {
        val symbol = TranscriptIcons.symbol(
            "sample",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- Generator: Adobe Illustrator -->
            <svg id="uuid-2926c75e" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
              <title>sample</title>
              <path id="uuid-1" d="M0 0h16v16H0z" fill="#4d4d4d"/>
              <circle cx="8" cy="8" r="4" stroke="#4d4d4d" stroke-width="1.6" fill="none"/>
            </svg>
            """.trimIndent(),
        )

        assertTrue(symbol.startsWith("""<symbol id="ra-icon-sample" viewBox="0 0 16 16""""), symbol)
        assertFalse(symbol.contains("uuid"), symbol)
        assertFalse(symbol.contains("<title>"), symbol)
        assertFalse(symbol.contains("<?xml"), symbol)
        assertTrue(symbol.contains("""fill="currentColor""""), symbol)
        assertTrue(symbol.contains("""stroke="currentColor""""), symbol)
        // An outline icon draws its shape with the stroke: filling it would turn it into a blob.
        assertTrue(symbol.contains("""fill="none""""), symbol)
        assertTrue(symbol.contains("""stroke-width="1.6""""), symbol)
    }

    /**
     * The drawing an agent brought along takes the place of the bundled `agent` icon, so its id has
     * to be that one — and its colors have to survive, which is the whole point of showing it.
     */
    @Test
    fun `the drawing of an agent replaces the agent symbol with its colors`() {
        val symbol = TranscriptIcons.agentSymbol(
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">
              <path id="uuid-1" d="M4 4h16v16H4z" fill="#D97757"/>
            </svg>
            """.trimIndent(),
        )

        assertTrue(symbol.startsWith("""<symbol id="ra-icon-agent" viewBox="0 0 24 24""""), symbol)
        assertTrue(symbol.contains("""fill="#D97757""""), symbol)
        // The ids of an export still have to go: the sprite is one document.
        assertFalse(symbol.contains("uuid"), symbol)
    }
}
