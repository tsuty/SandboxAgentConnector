package com.tsuty.sandboxagent.ui.transcript

import com.tsuty.sandboxagent.acp.ElicitationField
import com.tsuty.sandboxagent.acp.ElicitationForm
import com.tsuty.sandboxagent.acp.ElicitationOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** What the document needs to draw the form of an `elicitation/create`. */
object TranscriptElicitation {

    fun event(id: String, form: ElicitationForm): ElicitationEvent =
        ElicitationEvent(id, form.message, form.fields.map(::field))

    private fun field(field: ElicitationField): TranscriptElicitationField {
        val base = TranscriptElicitationField(
            key = field.key,
            kind = "",
            title = field.title,
            description = field.description,
            required = field.required,
        )
        return when (field) {
            is ElicitationField.Text -> base.copy(
                kind = "text",
                default = field.default?.let(::JsonPrimitive),
                minLength = field.minLength,
                maxLength = field.maxLength,
                format = field.format,
                pattern = field.pattern,
            )

            is ElicitationField.Numeric -> base.copy(
                kind = "number",
                default = field.default?.let(::JsonPrimitive),
                integer = field.integer,
                minimum = field.minimum,
                maximum = field.maximum,
            )

            is ElicitationField.Flag -> base.copy(kind = "boolean", default = field.default?.let(::JsonPrimitive))

            is ElicitationField.Choice -> base.copy(
                kind = "choice",
                default = field.default?.let(::JsonPrimitive),
                options = field.options.map(::option),
            )

            is ElicitationField.MultiChoice -> base.copy(
                kind = "multi",
                default = JsonArray(field.default.map(::JsonPrimitive)),
                options = field.options.map(::option),
                minItems = field.minItems,
                maxItems = field.maxItems,
            )
        }
    }

    private fun option(option: ElicitationOption) =
        TranscriptElicitationOption(option.value, option.title, option.description, option.preview)
}
