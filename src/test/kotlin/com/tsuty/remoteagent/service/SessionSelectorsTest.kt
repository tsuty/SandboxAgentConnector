package com.tsuty.remoteagent.service

import com.tsuty.remoteagent.acp.SessionConfigOption
import com.tsuty.remoteagent.acp.SessionMode
import com.tsuty.remoteagent.acp.SessionModeState
import com.tsuty.remoteagent.acp.SessionModelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Folding a reported selector state into the cached one (`session/set_*` answers, notifications). */
class SessionSelectorsTest {

    private fun option(id: String, value: String) = SessionConfigOption(id = id, currentValue = value)

    private val selectors = SessionSelectors(
        models = null,
        modes = null,
        configOptions = listOf(option("mode", "default"), option("model", "sonnet"), option("effort", "medium")),
    )

    @Test
    fun `a partially reported option replaces the one it has and keeps the others`() {
        val merged = selectors.withConfigOptions(
            listOf(option("mode", "acceptEdits"), option("model", "haiku")),
            replace = false,
        )

        // `effort` is not part of the answer, so dropping it would hide its selector.
        assertEquals(
            listOf("mode=acceptEdits", "model=haiku", "effort=medium"),
            merged.configOptions.map { "${it.id}=${it.currentValue}" },
        )
    }

    @Test
    fun `an unknown option is appended and an empty answer changes nothing`() {
        val merged = selectors.withConfigOptions(listOf(option("fast", "on")), replace = false)

        assertEquals(listOf("mode", "model", "effort", "fast"), merged.configOptions.map { it.id })
        assertSame(selectors, selectors.withConfigOptions(emptyList(), replace = false))
        assertSame(selectors, selectors.withConfigOptions(emptyList(), replace = true))
    }

    @Test
    fun `a whole reported state replaces every option`() {
        // `session/new` and `config_option_update` report the options of the session as a whole:
        // the ones they leave out do not exist for it (`fast` is model dependent).
        val replaced = selectors.withConfigOptions(
            listOf(option("mode", "plan"), option("model", "opus"), option("fast", "on")),
            replace = true,
        )

        assertEquals(
            listOf("mode=plan", "model=opus", "fast=on"),
            replaced.configOptions.map { "${it.id}=${it.currentValue}" },
        )
    }

    @Test
    fun `the current mode and model are folded into the reported state`() {
        val withState = selectors
            .withModes(SessionModeState("default", listOf(SessionMode("default"), SessionMode("plan"))))
            .withModels(SessionModelState("sonnet"))

        val merged = withState.withCurrentMode("plan").withCurrentModel("haiku")

        assertEquals("plan", merged.modes?.currentModeId)
        assertEquals(2, merged.modes?.availableModes?.size)
        assertEquals("haiku", merged.models?.currentModelId)
        // An agent that reports no dedicated state has nothing to update.
        assertSame(selectors, selectors.withCurrentMode("plan").withCurrentModel("haiku"))
        assertSame(selectors, selectors.withModes(null).withModels(null))
    }

    @Test
    fun `the presentable state names every selector`() {
        val merged = SessionSelectors.EMPTY
            .withModels(SessionModelState("sonnet"))
            .withConfigOptions(listOf(option("effort", "high")), replace = false)

        assertEquals("model=sonnet, effort=high", merged.presentableState())
        assertTrue(SessionSelectors.EMPTY.isEmpty)
        assertEquals("", SessionSelectors.EMPTY.presentableState())
    }
}
