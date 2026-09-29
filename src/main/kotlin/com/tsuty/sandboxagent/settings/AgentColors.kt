package com.tsuty.sandboxagent.settings

import java.awt.Color

/**
 * The color an agent drawing is painted in, by the `id` of its ACP registry entry ([AcpAgent.id]).
 *
 * A color chosen in the settings wins ([SandboxAgentSettingsState.agentColors]); without one the
 * agents below keep a color of their own, and every other agent is drawn the way it always was
 * (the grey of the bundled icons on the toolbar, the text color in the transcript). There is one
 * color for every theme: it is the color of the agent, not of the IDE.
 *
 * Only what the drawing leaves to its surroundings (`currentColor`, or no color at all) takes the
 * color; a shape the drawing colored itself keeps its brand color.
 */
object AgentColors {

    /** Colors the agents of the registry are known by, used until the settings name another. */
    val DEFAULTS: Map<String, String> = mapOf(
        "claude-acp" to "#CC7C5E",
        "junie" to "#74D664",
        "codex-acp" to "#4245DB",
        "gemini" to "#4A83F7",
    )

    /** The default color of [agentId], `null` for an agent that has none. */
    fun defaultOf(agentId: String): String? = DEFAULTS[agentId]

    /**
     * The color [agentId] is drawn in: the one of [overrides] when it is a valid one, otherwise
     * its default; `null` leaves the drawing to the existing logic.
     */
    fun resolve(agentId: String, overrides: Map<String, String>): String? =
        overrides[agentId]?.let { normalize(it) } ?: defaultOf(agentId)

    /** `#RRGGBB` in upper case, or `null` for anything that is not a color. */
    fun normalize(value: String): String? {
        val hex = value.trim().removePrefix("#")
        if (!HEX.matches(hex)) return null
        return "#" + hex.uppercase()
    }

    fun toColor(value: String?): Color? = value?.let { normalize(it) }?.let { Color(it.substring(1).toInt(16)) }

    fun toHex(color: Color): String = "#%06X".format(color.rgb and 0xFFFFFF)

    private val HEX = Regex("[0-9A-Fa-f]{6}")
}
