package com.tsuty.sandboxagent.acp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime

// ---------------------------------------------------------------------------------------------
// elicitation/create (form mode)
// ---------------------------------------------------------------------------------------------

/**
 * ACP `ElicitationCapabilities`. A mode is supported only when its field is present and non-null,
 * and `{}` as a whole advertises nothing, so each mode is spelled out. Only `form` is implemented:
 * `url` has to show the target and ask for consent, and is not advertised.
 */
@Serializable
data class ElicitationCapabilities(
    val form: JsonObject? = null,
    val url: JsonObject? = null,
) {
    companion object {
        val FORM_ONLY = ElicitationCapabilities(form = JsonObject(emptyMap()))
    }
}

/**
 * One choice of a single or multi select field: [value] is what is answered, [title] what is shown.
 *
 * [preview] is what the Claude adapter attaches to the option of an `AskUserQuestion` (a code
 * snippet or a mockup, as text) under `_meta`; ACP has no field of its own for it yet.
 */
data class ElicitationOption(
    val value: String,
    val title: String,
    val description: String? = null,
    val preview: String? = null,
)

/** One property of the flat `requestedSchema` of a form elicitation. */
sealed interface ElicitationField {
    val key: String
    val title: String
    val description: String?
    val required: Boolean

    data class Text(
        override val key: String,
        override val title: String,
        override val description: String?,
        override val required: Boolean,
        val minLength: Int?,
        val maxLength: Int?,
        val format: String?,
        val pattern: String?,
        val default: String?,
    ) : ElicitationField

    data class Numeric(
        override val key: String,
        override val title: String,
        override val description: String?,
        override val required: Boolean,
        val integer: Boolean,
        val minimum: Double?,
        val maximum: Double?,
        val default: Double?,
    ) : ElicitationField

    data class Flag(
        override val key: String,
        override val title: String,
        override val description: String?,
        override val required: Boolean,
        val default: Boolean?,
    ) : ElicitationField

    data class Choice(
        override val key: String,
        override val title: String,
        override val description: String?,
        override val required: Boolean,
        val options: List<ElicitationOption>,
        val default: String?,
    ) : ElicitationField

    data class MultiChoice(
        override val key: String,
        override val title: String,
        override val description: String?,
        override val required: Boolean,
        val options: List<ElicitationOption>,
        val minItems: Int?,
        val maxItems: Int?,
        val default: List<String>,
    ) : ElicitationField
}

/**
 * A form mode `elicitation/create` request, decoded.
 *
 * Decoding is strict about what the form can show: a property of a type this client does not know
 * must not be rendered as anything (ACP forbids guessing), so the whole request is refused with
 * [IllegalArgumentException] instead of being shown with a field missing.
 */
data class ElicitationForm(
    val sessionId: String?,
    val toolCallId: String?,
    val message: String,
    val fields: List<ElicitationField>,
) {
    /**
     * Checks the answer of the user against the schema and returns the `content` to send.
     *
     * Only keys of the schema are kept, and an optional field left empty is left out, which is how
     * a question the user chose to skip is told apart from one answered with an empty string.
     * Throws [IllegalArgumentException] naming the field that does not fit.
     */
    fun coerce(answer: JsonObject): JsonObject {
        val content = linkedMapOf<String, JsonElement>()
        for (field in fields) {
            val value = coerceField(field, answer[field.key])
            if (value != null) content[field.key] = value
        }
        return JsonObject(content)
    }

    private fun coerceField(field: ElicitationField, raw: JsonElement?): JsonElement? {
        val value = if (raw == null || raw is JsonNull) null else raw
        fun missing(): JsonElement? {
            require(!field.required) { "'${field.title}' is required" }
            return null
        }
        return when (field) {
            is ElicitationField.Text -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text.isNullOrEmpty()) return missing()
                require(field.minLength == null || text.length >= field.minLength) {
                    "'${field.title}' needs at least ${field.minLength} characters"
                }
                require(field.maxLength == null || text.length <= field.maxLength) {
                    "'${field.title}' takes at most ${field.maxLength} characters"
                }
                require(matchesFormat(field.format, text)) { "'${field.title}' is not a valid ${field.format}" }
                require(matchesPattern(field.pattern, text)) { "'${field.title}' does not match the expected pattern" }
                JsonPrimitive(text)
            }

            is ElicitationField.Numeric -> {
                if (value == null) return missing()
                val primitive = value as? JsonPrimitive
                val number = primitive?.takeIf { !it.isString }?.doubleOrNull
                requireNotNull(number) { "'${field.title}' is not a number" }
                require(!field.integer || number == Math.rint(number)) { "'${field.title}' must be a whole number" }
                require(field.minimum == null || number >= field.minimum) { "'${field.title}' is below ${field.minimum}" }
                require(field.maximum == null || number <= field.maximum) { "'${field.title}' is above ${field.maximum}" }
                if (field.integer) JsonPrimitive(number.toLong()) else primitive
            }

            is ElicitationField.Flag -> {
                if (value == null) return missing()
                val flag = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                JsonPrimitive(requireNotNull(flag) { "'${field.title}' is not a yes/no value" })
            }

            is ElicitationField.Choice -> {
                val chosen = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (chosen.isNullOrEmpty()) return missing()
                require(field.options.any { it.value == chosen }) { "'${field.title}' is not one of the choices" }
                JsonPrimitive(chosen)
            }

            is ElicitationField.MultiChoice -> {
                val chosen = (value as? JsonArray)?.map {
                    requireNotNull((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content) {
                        "'${field.title}' holds a value that is not text"
                    }
                }.orEmpty().distinct()
                // Constraints only bind a value that is there: an optional field left empty is skipped.
                if (chosen.isEmpty()) return missing()
                require(chosen.all { picked -> field.options.any { it.value == picked } }) {
                    "'${field.title}' holds a value that is not one of the choices"
                }
                require(field.minItems == null || chosen.size >= field.minItems) {
                    "'${field.title}' needs at least ${field.minItems} choices"
                }
                require(field.maxItems == null || chosen.size <= field.maxItems) {
                    "'${field.title}' takes at most ${field.maxItems} choices"
                }
                JsonArray(chosen.map { JsonPrimitive(it) })
            }
        }
    }

    private fun matchesFormat(format: String?, text: String): Boolean = when (format) {
        "email" -> EMAIL.matches(text)
        "uri" -> runCatching { URI(text).isAbsolute }.getOrDefault(false)
        "date" -> runCatching { LocalDate.parse(text) }.isSuccess
        "date-time" -> runCatching { OffsetDateTime.parse(text) }.isSuccess
        else -> true
    }

    /** JSON Schema patterns are not anchored; one this JVM cannot compile is not held against the user. */
    private fun matchesPattern(pattern: String?, text: String): Boolean {
        if (pattern == null) return true
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return true
        return regex.containsMatchIn(text)
    }

    companion object {
        private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

        /** Key under which `claude-agent-acp` carries the preview of an option (`_meta`). */
        private const val PREVIEW_META_KEY = "_claude/askUserQuestionOption"

        /** Decodes the `params` of an `elicitation/create`; see the class comment for the refusals. */
        fun parse(params: JsonObject): ElicitationForm {
            val mode = params.string("mode")
            require(mode == "form") { "Unsupported elicitation mode: ${mode ?: "(none)"}" }
            val schema = params["requestedSchema"] as? JsonObject
                ?: throw IllegalArgumentException("requestedSchema is missing")
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val required = (schema["required"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .toSet()
            val fields = properties.entries.map { (key, element) ->
                val definition = element as? JsonObject
                    ?: throw IllegalArgumentException("Property '$key' is not a schema object")
                parseField(key, definition, key in required)
            }
            return ElicitationForm(
                sessionId = params.string("sessionId"),
                toolCallId = params.string("toolCallId"),
                message = params.string("message").orEmpty(),
                fields = fields,
            )
        }

        private fun parseField(key: String, definition: JsonObject, required: Boolean): ElicitationField {
            val title = definition.string("title") ?: key
            val description = definition.string("description")
            return when (val type = definition.string("type")) {
                "string" -> {
                    val options = choices(definition, key)
                    if (options != null) {
                        ElicitationField.Choice(key, title, description, required, options, definition.string("default"))
                    } else {
                        ElicitationField.Text(
                            key, title, description, required,
                            minLength = definition.int("minLength"),
                            maxLength = definition.int("maxLength"),
                            format = definition.string("format"),
                            pattern = definition.string("pattern"),
                            default = definition.string("default"),
                        )
                    }
                }

                "number", "integer" -> ElicitationField.Numeric(
                    key, title, description, required,
                    integer = type == "integer",
                    minimum = definition.double("minimum"),
                    maximum = definition.double("maximum"),
                    default = definition.double("default"),
                )

                "boolean" -> ElicitationField.Flag(
                    key, title, description, required,
                    default = (definition["default"] as? JsonPrimitive)?.booleanOrNull,
                )

                "array" -> {
                    val items = definition["items"] as? JsonObject
                    val options = items?.let { choices(it, key) }
                        ?: throw IllegalArgumentException("Property '$key' is an array without enumerated items")
                    ElicitationField.MultiChoice(
                        key, title, description, required, options,
                        minItems = definition.int("minItems"),
                        maxItems = definition.int("maxItems"),
                        default = (definition["default"] as? JsonArray).orEmpty()
                            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    )
                }

                else -> throw IllegalArgumentException("Property '$key' has an unsupported type: ${type ?: "(none)"}")
            }
        }

        /** The choices of an `enum` / `oneOf` / `anyOf` schema; `null` when it declares none. */
        private fun choices(schema: JsonObject, key: String): List<ElicitationOption>? {
            val titled = (schema["oneOf"] as? JsonArray) ?: (schema["anyOf"] as? JsonArray)
            val options = if (titled != null) {
                titled.map { entry ->
                    val option = entry as? JsonObject
                        ?: throw IllegalArgumentException("Property '$key' has a choice that is not an object")
                    val value = option.string("const")
                        ?: throw IllegalArgumentException("Property '$key' has a choice without a const")
                    ElicitationOption(value, option.string("title") ?: value, option.string("description"), preview(option))
                }
            } else {
                val plain = schema["enum"] as? JsonArray ?: return null
                plain.map { entry ->
                    val value = (entry as? JsonPrimitive)?.contentOrNull
                        ?: throw IllegalArgumentException("Property '$key' has an enum value that is not text")
                    ElicitationOption(value, value)
                }
            }
            require(options.isNotEmpty()) { "Property '$key' offers no choice" }
            return options
        }

        /** The preview the adapter hides under the `_meta` of an option; `null` when there is none. */
        private fun preview(option: JsonObject): String? =
            ((option["_meta"] as? JsonObject)?.get(PREVIEW_META_KEY) as? JsonObject)?.string("preview")?.takeIf { it.isNotBlank() }

        private fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

        private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

        private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
    }
}

/** The `result` of an `elicitation/create`: what the user did, and the answer for `accept`. */
@Serializable
data class ElicitationResult(
    val action: String,
    val content: JsonObject? = null,
) {
    companion object {
        const val ACCEPT = "accept"
        const val DECLINE = "decline"
        const val CANCEL = "cancel"

        fun accept(content: JsonObject) = ElicitationResult(ACCEPT, content)
        fun decline() = ElicitationResult(DECLINE)
        fun cancel() = ElicitationResult(CANCEL)
    }
}
