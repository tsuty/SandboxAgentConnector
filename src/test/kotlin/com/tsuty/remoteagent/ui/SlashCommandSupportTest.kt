package com.tsuty.remoteagent.ui

import com.tsuty.remoteagent.acp.AvailableCommand
import com.tsuty.remoteagent.acp.AvailableCommandInput
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlashCommandSupportTest {

    private val commands = listOf(
        AvailableCommand("web", "Search the web", AvailableCommandInput("search query")),
        AvailableCommand("test", "Run the tests"),
        AvailableCommand("plan", "Draft a plan", AvailableCommandInput("  ")),
        AvailableCommand("edit", "Edit a file"),
    )

    @Test
    fun testQueryOnlyMatchesALoneCommandToken() {
        assertEquals("", SlashCommandSupport.queryFor("/"))
        assertEquals("pl", SlashCommandSupport.queryFor("/pl"))
        assertEquals("plan", SlashCommandSupport.queryFor("/plan"))

        // Anything else in the prompt keeps the completion closed.
        assertNull(SlashCommandSupport.queryFor(""))
        assertNull(SlashCommandSupport.queryFor("hello"))
        assertNull(SlashCommandSupport.queryFor("hello /plan"))
        assertNull(SlashCommandSupport.queryFor("/plan refactor"))
        assertNull(SlashCommandSupport.queryFor("/plan\n"))
    }

    @Test
    fun testFilterPrefersPrefixMatches() {
        assertEquals(listOf("web", "test", "plan", "edit"), SlashCommandSupport.filter(commands, "").map { it.name })
        assertEquals(listOf("test"), SlashCommandSupport.filter(commands, "TE").map { it.name })
        // `edit` matches by prefix, `web` and `test` only as a substring.
        assertEquals(listOf("edit", "web", "test"), SlashCommandSupport.filter(commands, "e").map { it.name })
        assertEquals(emptyList<String>(), SlashCommandSupport.filter(commands, "zzz").map { it.name })
    }

    @Test
    fun testNormalizeSortsAndDropsDuplicates() {
        val normalized = SlashCommandSupport.normalize(
            listOf(
                AvailableCommand("web"),
                AvailableCommand("plan"),
                AvailableCommand("web", "duplicate"),
                AvailableCommand(" "),
            ),
        )
        assertEquals(listOf("plan", "web"), normalized.map { it.name })
    }

    @Test
    fun testPresentation() {
        assertEquals("/web", SlashCommandSupport.presentableName(commands[0]))
        assertEquals("/web ", SlashCommandSupport.completionText(commands[0]))
        assertEquals("<search query>", SlashCommandSupport.presentableHint(commands[0]))
        assertEquals("Search the web", SlashCommandSupport.presentableDescription(commands[0]))
        assertNull(SlashCommandSupport.presentableHint(commands[1]))
        assertEquals("Run the tests", SlashCommandSupport.presentableDescription(commands[1]))
        // A blank hint adds nothing.
        assertNull(SlashCommandSupport.presentableHint(commands[2]))
        assertNull(SlashCommandSupport.presentableDescription(AvailableCommand("web")))
    }

    @Test
    fun testDescriptionIsCollapsedToASingleShortLine() {
        val wordy = AvailableCommand("dataviz", "Use this skill\n   whenever you are about\tto create ANY chart.")
        assertEquals("Use this skill whenever you are about to create ANY chart.", SlashCommandSupport.presentableDescription(wordy))

        val long = AvailableCommand("doctor", "x".repeat(400))
        val presentable = SlashCommandSupport.presentableDescription(long)!!
        assertEquals(121, presentable.length)
        assertTrue(presentable.endsWith("…"))
    }
}
