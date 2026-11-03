package com.tsuty.sandboxagent.ui.transcript

import com.tsuty.sandboxagent.acp.ElicitationForm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The form of an `elicitation/create` as the document receives it, and the answer it sends back. */
class TranscriptElicitationTest {

    private val form = ElicitationForm.parse(
        Json.parseToJsonElement(
            """
            {"mode":"form","message":"Which?","requestedSchema":{"type":"object","required":["pick"],"properties":{
              "pick":{"type":"string","title":"Pick","oneOf":[{"const":"a","title":"A","description":"first","_meta":{"_claude/askUserQuestionOption":{"preview":"fn a() {}"}}},{"const":"b","title":"B"}],"default":"b"},
              "many":{"type":"array","items":{"type":"string","enum":["x","y"]},"minItems":1},
              "n":{"type":"integer","minimum":1},
              "ok":{"type":"boolean","default":true},
              "t":{"type":"string","maxLength":5}
            }}}
            """,
        ).jsonObject,
    )

    @Test
    fun `the fields of the form are described for the document`() {
        val event = TranscriptElicitation.event("req-1", form)
        val byKey = event.fields.associateBy { it.key }

        assertEquals("req-1", event.id)
        assertEquals("Which?", event.message)
        assertEquals("choice", byKey.getValue("pick").kind)
        assertTrue(byKey.getValue("pick").required)
        assertEquals(JsonPrimitive("b"), byKey.getValue("pick").default)
        assertEquals("first", byKey.getValue("pick").options[0].description)
        assertEquals("fn a() {}", byKey.getValue("pick").options[0].preview)
        assertNull(byKey.getValue("pick").options[1].preview)
        assertEquals("multi", byKey.getValue("many").kind)
        assertEquals(1, byKey.getValue("many").minItems)
        assertEquals("number", byKey.getValue("n").kind)
        assertTrue(byKey.getValue("n").integer)
        assertEquals(1.0, byKey.getValue("n").minimum)
        assertEquals(JsonPrimitive(true), byKey.getValue("ok").default)
        assertEquals(5, byKey.getValue("t").maxLength)
    }

    @Test
    fun `the event is dispatched as elicitation`() {
        val json = TranscriptProtocol.encode(
            listOf(TranscriptElicitation.event("req-1", form), ElicitationResolvedEvent("req-1", "Submitted")),
        )

        assertTrue(json.contains(""""type":"elicitation","id":"req-1","message":"Which?""""), json)
        assertTrue(json.contains("""{"type":"elicitationResolved","id":"req-1","status":"Submitted"}"""), json)
    }

    @Test
    fun `the answer of the document is parsed back`() {
        assertEquals(
            ElicitationAction("req-1", "accept", JsonObject(mapOf("pick" to JsonPrimitive("a")))),
            TranscriptProtocol.decodeAction("""{"type":"elicitation","id":"req-1","action":"accept","content":{"pick":"a"}}"""),
        )
        assertEquals(
            ElicitationAction("req-1", "decline"),
            TranscriptProtocol.decodeAction("""{"type":"elicitation","id":"req-1","action":"decline","content":null}"""),
        )
        assertNull(TranscriptProtocol.decodeAction("""{"type":"elicitation","id":"req-1"}"""))
    }
}
