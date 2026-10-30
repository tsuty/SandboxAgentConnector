package com.tsuty.sandboxagent.service

import com.tsuty.sandboxagent.acp.ModelInfo
import com.tsuty.sandboxagent.acp.SessionConfigChoice
import com.tsuty.sandboxagent.acp.SessionConfigOption
import com.tsuty.sandboxagent.acp.SessionMode
import com.tsuty.sandboxagent.acp.SessionModeState
import com.tsuty.sandboxagent.acp.SessionModelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which remembered selector value a new session is set to next (see [nextRememberedChange]). */
class SelectorChangeTest {

    private fun option(id: String, current: String, vararg values: String, category: String? = null) =
        SessionConfigOption(
            id = id,
            category = category,
            currentValue = current,
            options = values.map { SessionConfigChoice(value = it) },
        )

    private val configSelectors = SessionSelectors(
        models = null,
        modes = null,
        configOptions = listOf(
            option("mode", "default", "default", "plan", "acceptEdits", category = "mode"),
            option("effort", "medium", "low", "medium", "high", category = "thought_level"),
            option("model", "sonnet", "sonnet", "opus", category = "model"),
        ),
    )

    @Test
    fun `the model goes first, then the mode, then the other options`() {
        val remembered = mapOf("config:effort" to "high", "config:mode" to "plan", "config:model" to "opus")

        assertEquals(SelectorChange.ConfigOption("model", "opus"), configSelectors.nextRememberedChange(remembered))
        assertEquals(
            SelectorChange.ConfigOption("mode", "plan"),
            configSelectors.nextRememberedChange(remembered, attempted = setOf("config:model")),
        )
        assertEquals(
            SelectorChange.ConfigOption("effort", "high"),
            configSelectors.nextRememberedChange(remembered, attempted = setOf("config:model", "config:mode")),
        )
        assertNull(configSelectors.nextRememberedChange(remembered, attempted = remembered.keys))
    }

    @Test
    fun `a value that is already set or not offered is skipped`() {
        val remembered = mapOf(
            "config:model" to "sonnet",
            "config:effort" to "max",
            "config:fast" to "on",
            "unknown" to "x",
        )

        assertNull(configSelectors.nextRememberedChange(remembered))
    }

    @Test
    fun `the dedicated model and mode state is driven through its own methods`() {
        val selectors = SessionSelectors(
            models = SessionModelState("sonnet", listOf(ModelInfo("sonnet"), ModelInfo("opus"))),
            modes = SessionModeState("default", listOf(SessionMode("default"), SessionMode("plan"))),
            configOptions = emptyList(),
        )
        val remembered = mapOf("mode" to "plan", "model" to "opus")

        assertEquals(SelectorChange.Model("opus"), selectors.nextRememberedChange(remembered))
        assertEquals(SelectorChange.Mode("plan"), selectors.nextRememberedChange(remembered, setOf("model")))
        assertNull(selectors.nextRememberedChange(mapOf("mode" to "yolo")))
    }

    @Test
    fun `a key maps back to the change it was remembered from`() {
        listOf(
            SelectorChange.Model("opus"),
            SelectorChange.Mode("plan"),
            SelectorChange.ConfigOption("effort", "high"),
        ).forEach { assertEquals(it, SelectorChange.of(it.key, it.value)) }
        assertNull(SelectorChange.of("config:", "x"))
    }
}
