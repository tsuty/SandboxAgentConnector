package com.tsuty.sandboxagent.service

import com.tsuty.sandboxagent.acp.SessionConfigCategory
import com.tsuty.sandboxagent.acp.SessionConfigOption

/**
 * One selector set to one value, through the ACP method that drives it. [key] names the selector
 * across sessions, which is how the last picked values are remembered per agent
 * (see [com.tsuty.sandboxagent.session.LastSelectorsStore]).
 */
sealed interface SelectorChange {
    val key: String
    val value: String

    /** `session/set_model`. */
    data class Model(override val value: String) : SelectorChange {
        override val key: String get() = MODEL_KEY
    }

    /** `session/set_mode`. */
    data class Mode(override val value: String) : SelectorChange {
        override val key: String get() = MODE_KEY
    }

    /** `session/set_config_option`. */
    data class ConfigOption(val configId: String, override val value: String) : SelectorChange {
        override val key: String get() = CONFIG_KEY_PREFIX + configId
    }

    companion object {
        const val MODEL_KEY: String = "model"
        const val MODE_KEY: String = "mode"
        const val CONFIG_KEY_PREFIX: String = "config:"

        /** The change [key] stands for with [value]; `null` for a key nothing reads any more. */
        fun of(key: String, value: String): SelectorChange? = when {
            key == MODEL_KEY -> Model(value)
            key == MODE_KEY -> Mode(value)
            key.startsWith(CONFIG_KEY_PREFIX) && key.length > CONFIG_KEY_PREFIX.length ->
                ConfigOption(key.removePrefix(CONFIG_KEY_PREFIX), value)
            else -> null
        }
    }
}

/**
 * The next of the [remembered] values (by [SelectorChange.key]) that [this] state offers but does
 * not have yet, skipping the keys already [attempted]; `null` once there is none left.
 *
 * One change at a time, because a change reshapes the state: another model offers other effort
 * levels, or no `fast` option at all. The model therefore goes first, then the mode, then the
 * remaining options, and the caller asks again with the state each answer left behind.
 */
fun SessionSelectors.nextRememberedChange(
    remembered: Map<String, String>,
    attempted: Set<String> = emptySet(),
): SelectorChange? = remembered
    .filterKeys { it !in attempted }
    .mapNotNull { (key, value) -> SelectorChange.of(key, value) }
    .filter { isPending(it) }
    .minByOrNull { priorityOf(it) }

/** `true` when [change] names a value this state offers and is not already set to. */
private fun SessionSelectors.isPending(change: SelectorChange): Boolean = when (change) {
    is SelectorChange.Model -> models != null && models.currentModelId != change.value &&
        models.availableModels.any { it.modelId == change.value }
    is SelectorChange.Mode -> modes != null && modes.currentModeId != change.value &&
        modes.availableModes.any { it.id == change.value }
    is SelectorChange.ConfigOption -> configOptions.firstOrNull { it.id == change.configId }?.let { option ->
        option.currentValue != change.value && option.choices.any { it.value == change.value }
    } ?: false
}

private fun SessionSelectors.priorityOf(change: SelectorChange): Int = when (change) {
    is SelectorChange.Model -> 0
    is SelectorChange.Mode -> 2
    is SelectorChange.ConfigOption -> {
        val option: SessionConfigOption? = configOptions.firstOrNull { it.id == change.configId }
        when {
            option?.isCategory(SessionConfigCategory.MODEL) == true -> 1
            option?.isCategory(SessionConfigCategory.MODE) == true -> 3
            else -> 4
        }
    }
}
