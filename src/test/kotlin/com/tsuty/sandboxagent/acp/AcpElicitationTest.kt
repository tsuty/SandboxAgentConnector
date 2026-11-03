package com.tsuty.sandboxagent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Decoding and checking of form mode elicitations, against the payloads the ACP docs and the Claude adapter use. */
class AcpElicitationTest {

    private fun params(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** The shape `askUserQuestionsToCreateRequest` of claude-agent-acp sends for one single select question. */
    private val askUserQuestion = """
        {
          "sessionId": "sess_1", "toolCallId": "toolu_1", "mode": "form",
          "message": "How should I approach this refactoring?",
          "requestedSchema": {
            "type": "object",
            "properties": {
              "question_0": {
                "type": "string", "title": "Approach",
                "oneOf": [
                  {"const": "Conservative", "title": "Conservative", "description": "Small steps"},
                  {"const": "Aggressive", "title": "Aggressive"}
                ]
              },
              "question_0_custom": {"type": "string", "title": "Other"}
            }
          }
        }
    """

    @Test
    fun `capabilities advertise the form mode only`() {
        val encoded = AcpJson.encodeToJsonElement(
            ClientCapabilities.serializer(),
            ClientCapabilities(elicitation = ElicitationCapabilities.FORM_ONLY),
        ).jsonObject

        val elicitation = encoded["elicitation"]!!.jsonObject
        assertEquals(JsonObject(emptyMap()), elicitation["form"])
        assertNull(elicitation["url"])
    }

    @Test
    fun `capabilities leave elicitation out unless asked for`() {
        val encoded = AcpJson.encodeToJsonElement(ClientCapabilities.serializer(), ClientCapabilities()).jsonObject

        assertNull(encoded["elicitation"])
    }

    @Test
    fun `an AskUserQuestion form is decoded`() {
        val form = ElicitationForm.parse(params(askUserQuestion))

        assertEquals("sess_1", form.sessionId)
        assertEquals("toolu_1", form.toolCallId)
        assertEquals("How should I approach this refactoring?", form.message)
        val choice = assertIs<ElicitationField.Choice>(form.fields[0])
        assertEquals("question_0", choice.key)
        assertEquals("Approach", choice.title)
        assertEquals(listOf("Conservative", "Aggressive"), choice.options.map { it.value })
        assertEquals("Small steps", choice.options[0].description)
        assertTrue(!choice.required)
        assertIs<ElicitationField.Text>(form.fields[1])
    }

    @Test
    fun `the preview of an option is read from the meta of the adapter`() {
        val form = ElicitationForm.parse(
            params(
                """
                {"mode":"form","message":"m","requestedSchema":{"type":"object","properties":{"q":{"type":"string","oneOf":[
                  {"const":"a","title":"A","_meta":{"_claude/askUserQuestionOption":{"preview":"fn a() {}\n"}}},
                  {"const":"b","title":"B","_meta":{"_claude/askUserQuestionOption":{"preview":"  "}}},
                  {"const":"c","title":"C","_meta":{"other":{"preview":"ignored"}}},
                  {"const":"d","title":"D"}
                ]}}}}
                """,
            ),
        )

        val options = assertIs<ElicitationField.Choice>(form.fields.single()).options
        assertEquals("fn a() {}\n", options[0].preview)
        // A blank preview, one under another key and none at all are all "no preview".
        assertEquals(listOf(null, null, null), options.drop(1).map { it.preview })
    }

    @Test
    fun `every primitive of the schema is decoded`() {
        val form = ElicitationForm.parse(
            params(
                """
                {"mode":"form","message":"m","requestedSchema":{"type":"object","required":["name"],"properties":{
                  "name":{"type":"string","minLength":3,"maxLength":50,"format":"email","default":"a@b.co"},
                  "age":{"type":"integer","minimum":18,"maximum":99,"default":30},
                  "ratio":{"type":"number"},
                  "ok":{"type":"boolean","default":true},
                  "color":{"type":"string","enum":["Red","Green"],"default":"Red"},
                  "colors":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string","enum":["Red","Green","Blue"]},"default":["Red"]},
                  "tags":{"type":"array","items":{"anyOf":[{"const":"a","title":"A"}]}}
                }}}
                """,
            ),
        )

        val byKey = form.fields.associateBy { it.key }
        val name = assertIs<ElicitationField.Text>(byKey.getValue("name"))
        assertTrue(name.required)
        assertEquals(3, name.minLength)
        assertEquals("email", name.format)
        val age = assertIs<ElicitationField.Numeric>(byKey.getValue("age"))
        assertTrue(age.integer)
        assertEquals(18.0, age.minimum)
        assertEquals(30.0, age.default)
        assertTrue(!assertIs<ElicitationField.Numeric>(byKey.getValue("ratio")).integer)
        assertEquals(true, assertIs<ElicitationField.Flag>(byKey.getValue("ok")).default)
        assertEquals("Red", assertIs<ElicitationField.Choice>(byKey.getValue("color")).default)
        val colors = assertIs<ElicitationField.MultiChoice>(byKey.getValue("colors"))
        assertEquals(listOf("Red"), colors.default)
        assertEquals(2, colors.maxItems)
        assertEquals("A", assertIs<ElicitationField.MultiChoice>(byKey.getValue("tags")).options.single().title)
    }

    @Test
    fun `a mode this client did not advertise is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ElicitationForm.parse(params("""{"mode":"url","message":"m","url":"https://example.com","elicitationId":"e"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            ElicitationForm.parse(params("""{"message":"m","requestedSchema":{"type":"object","properties":{}}}"""))
        }
    }

    @Test
    fun `a schema the form cannot show is refused instead of shown with a field missing`() {
        fun parse(property: String) = ElicitationForm.parse(
            params("""{"mode":"form","message":"m","requestedSchema":{"type":"object","properties":{"x":$property}}}"""),
        )

        assertFailsWith<IllegalArgumentException> { parse("""{"type":"object"}""") }
        assertFailsWith<IllegalArgumentException> { parse("""{"type":"array","items":{"type":"string"}}""") }
        assertFailsWith<IllegalArgumentException> { parse("""{"type":"string","oneOf":[]}""") }
        assertFailsWith<IllegalArgumentException> { parse("""{"title":"no type"}""") }
    }

    @Test
    fun `coerce keeps the answer and leaves a skipped optional field out`() {
        val form = ElicitationForm.parse(params(askUserQuestion))

        val answered = form.coerce(JsonObject(mapOf("question_0" to JsonPrimitive("Aggressive"), "stray" to JsonPrimitive(1))))
        assertEquals(JsonObject(mapOf("question_0" to JsonPrimitive("Aggressive"))), answered)

        assertEquals(JsonObject(emptyMap()), form.coerce(JsonObject(mapOf("question_0_custom" to JsonPrimitive("")))))
    }

    @Test
    fun `coerce rejects an answer that does not fit the schema`() {
        val form = ElicitationForm.parse(
            params(
                """
                {"mode":"form","message":"m","requestedSchema":{"type":"object","properties":{
                  "name":{"type":"string","minLength":3,"format":"email"},
                  "age":{"type":"integer","minimum":18},
                  "color":{"type":"string","enum":["Red","Green"]},
                  "colors":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string","enum":["Red","Green","Blue"]}},
                  "must":{"type":"array","items":{"type":"string","enum":["x"]}}
                }, "required":["name","must"]}}
                """,
            ),
        )
        fun answer(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
            JsonObject(mapOf("name" to JsonPrimitive("a@b.co"), "must" to JsonArray(listOf(JsonPrimitive("x")))) + pairs)

        form.coerce(answer())
        assertFailsWith<IllegalArgumentException> { form.coerce(JsonObject(emptyMap())) }
        assertFailsWith<IllegalArgumentException> { form.coerce(answer("must" to JsonArray(emptyList()))) }
        assertFailsWith<IllegalArgumentException> { form.coerce(answer("name" to JsonPrimitive("not an email"))) }
        assertFailsWith<IllegalArgumentException> { form.coerce(answer("age" to JsonPrimitive(17))) }
        assertFailsWith<IllegalArgumentException> { form.coerce(answer("age" to JsonPrimitive(18.5))) }
        assertFailsWith<IllegalArgumentException> { form.coerce(answer("age" to JsonPrimitive("18"))) }
        assertFailsWith<IllegalArgumentException> { form.coerce(answer("color" to JsonPrimitive("Blue"))) }
        // minItems binds a selection that is there, not an optional question that was skipped.
        assertNull(form.coerce(answer("colors" to JsonArray(emptyList())))["colors"])
        assertFailsWith<IllegalArgumentException> {
            form.coerce(answer("colors" to JsonArray(listOf("Red", "Green", "Blue").map(::JsonPrimitive))))
        }

        val ok = form.coerce(answer("age" to JsonPrimitive(30), "colors" to JsonArray(listOf(JsonPrimitive("Red")))))
        assertEquals(30, ok["age"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("Red"), ok["colors"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `results are encoded the way the response expects`() {
        fun encode(result: ElicitationResult) = AcpJson.encodeToJsonElement(ElicitationResult.serializer(), result).jsonObject

        val accepted = encode(ElicitationResult.accept(JsonObject(mapOf("a" to JsonPrimitive("b")))))
        assertEquals("accept", accepted["action"]!!.jsonPrimitive.content)
        assertEquals("b", accepted["content"]!!.jsonObject["a"]!!.jsonPrimitive.content)

        val declined = encode(ElicitationResult.decline())
        assertEquals("decline", declined["action"]!!.jsonPrimitive.content)
        assertNull(declined["content"])
        assertEquals("cancel", encode(ElicitationResult.cancel())["action"]!!.jsonPrimitive.content)
    }
}
