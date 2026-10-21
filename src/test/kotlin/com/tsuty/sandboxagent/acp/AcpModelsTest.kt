package com.tsuty.sandboxagent.acp

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Serialization checks against the payloads of the ACP v1 specification. */
class AcpModelsTest {

    @Test
    fun `initialize params match the spec payload`() {
        val encoded = AcpJson.encodeToJsonElement(InitializeParams.serializer(), InitializeParams()).jsonObject

        assertEquals(1, encoded["protocolVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals("SandboxAgentConnector", encoded["clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val capabilities = encoded["clientCapabilities"]!!.jsonObject
        // The sandbox is self contained: the IDE offers neither file system nor terminal.
        assertEquals(false, capabilities["terminal"]!!.jsonPrimitive.content.toBoolean())
        val fs = capabilities["fs"]!!.jsonObject
        assertEquals(false, fs["readTextFile"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, fs["writeTextFile"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `initialize result of the spec is decoded`() {
        val json = """
            {
              "protocolVersion": 1,
              "agentInfo": { "name": "claude-agent-acp", "version": "0.1.0" },
              "agentCapabilities": {
                "loadSession": true,
                "sessionCapabilities": { "additionalDirectories": false, "close": false },
                "mcpCapabilities": { "http": false, "sse": false }
              },
              "authMethods": []
            }
        """.trimIndent()

        val result = AcpJson.decodeFromString(InitializeResult.serializer(), json)

        assertEquals(1, result.protocolVersion)
        assertEquals("claude-agent-acp", result.agentInfo?.name)
        assertTrue(result.agentCapabilities.loadSession)
        assertTrue(result.authMethods.isEmpty())
    }

    @Test
    fun `session new params carry the cwd and an empty mcp server list`() {
        val encoded = AcpJson
            .encodeToJsonElement(SessionNewParams.serializer(), SessionNewParams(cwd = "/workspace/project-root"))
            .jsonObject

        assertEquals("/workspace/project-root", encoded["cwd"]!!.jsonPrimitive.content)
        assertTrue(encoded["mcpServers"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `session new result exposes models and modes`() {
        val json = """
            {
              "sessionId": "sess_01HXYZ",
              "models": {
                "currentModelId": "claude-3-7-sonnet-20250219",
                "availableModels": [{ "modelId": "claude-3-7-sonnet-20250219", "name": "Sonnet" }]
              },
              "modes": {
                "currentModeId": "default",
                "availableModes": [{ "id": "default", "name": "Default" }]
              }
            }
        """.trimIndent()

        val result = AcpJson.decodeFromString(SessionNewResult.serializer(), json)

        assertEquals("sess_01HXYZ", result.sessionId)
        assertEquals("claude-3-7-sonnet-20250219", result.models?.currentModelId)
        assertEquals("Sonnet", result.models?.availableModels?.single()?.presentableName)
        assertEquals("default", result.modes?.availableModes?.single()?.id)
    }

    @Test
    fun `prompt content blocks are encoded with the type discriminator`() {
        val params = SessionPromptParams(
            sessionId = "sess_1",
            prompt = listOf(
                TextContentBlock("Fix the build error."),
                AcpContextItem(path = "/a/B.kt", content = "class B", startLine = 1, endLine = 2).toContentBlock(),
            ),
        )

        val encoded = AcpJson.encodeToJsonElement(SessionPromptParams.serializer(), params).jsonObject
        val blocks = encoded["prompt"]!!.jsonArray

        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Fix the build error.", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("resource", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        val resource = blocks[1].jsonObject["resource"]!!.jsonObject
        assertEquals("file:///a/B.kt#L1-2", resource["uri"]!!.jsonPrimitive.content)
        assertEquals("class B", resource["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prompt result reports the stop reason`() {
        val result = AcpJson.decodeFromString(SessionPromptResult.serializer(), """{"stopReason":"end_turn"}""")
        assertEquals(StopReason.END_TURN, result.stopReason)
        assertEquals("Cancelled", StopReason.presentableText(StopReason.CANCELLED))
    }

    @Test
    fun `agent message chunk of the spec is decoded`() {
        val json = """
            {
              "sessionId": "sess_01HXYZ",
              "update": {
                "sessionUpdate": "agent_message_chunk",
                "content": { "type": "text", "text": "file content" }
              }
            }
        """.trimIndent()

        val notification = AcpJson.decodeFromString(SessionNotification.serializer(), json)

        val update = assertIs<AgentMessageChunkUpdate>(notification.update)
        assertEquals("file content", update.content.plainText)
    }

    @Test
    fun `both thought chunk spellings are decoded`() {
        listOf("agent_thought_chunk", "thought_chunk").forEach { tag ->
            val json = """{"sessionUpdate":"$tag","content":{"type":"text","text":"hmm"}}"""
            val update = AcpCodec.decodeSessionUpdate(AcpJson.parseToJsonElement(json))
            assertEquals("hmm", assertIs<ThoughtChunkUpdate>(update).content.plainText)
        }
    }

    @Test
    fun `tool call and its diff content are decoded`() {
        val json = """
            {
              "sessionUpdate": "tool_call",
              "toolCallId": "call_1",
              "title": "Edit main.kt",
              "kind": "edit",
              "status": "pending",
              "content": [
                { "type": "diff", "path": "/workspace/main.kt", "oldText": "a\nb\n", "newText": "a\nc\n" }
              ],
              "locations": [{ "path": "/workspace/main.kt", "line": 2 }],
              "rawInput": { "path": "/workspace/main.kt" }
            }
        """.trimIndent()

        val update = assertIs<ToolCallUpdateEvent>(AcpCodec.decodeSessionUpdate(AcpJson.parseToJsonElement(json)))
        val toolCall = update.toolCall

        assertEquals("call_1", toolCall.toolCallId)
        assertEquals(ToolKind.EDIT, toolCall.kind)
        assertEquals(ToolCallStatus.PENDING, toolCall.status)
        val diff = toolCall.diffs.single()
        assertEquals("/workspace/main.kt", diff.path)
        assertEquals("a\nc\n", diff.newText)
        assertEquals(2, toolCall.locations.single().line)
    }

    @Test
    fun `tool call update merges into the previous snapshot`() {
        val started = ToolCall(toolCallId = "call_1", title = "Edit main.kt", kind = ToolKind.EDIT, status = ToolCallStatus.PENDING)
        val progress = AcpJson.decodeFromString(
            ToolCallProgressUpdate.serializer(),
            """{"toolCallId":"call_1","status":"completed","rawOutput":{"ok":true}}""",
        )

        val merged = started.mergeWith(progress.toolCall)

        assertEquals("Edit main.kt", merged.title)
        assertEquals(ToolCallStatus.COMPLETED, merged.status)
        assertEquals(ToolKind.EDIT, merged.kind)
    }

    @Test
    fun `current mode and model updates are decoded`() {
        val mode = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement("""{"sessionUpdate":"current_mode_update","currentModeId":"code"}"""),
        )
        assertEquals("code", assertIs<CurrentModeUpdate>(mode).currentModeId)

        val model = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement("""{"sessionUpdate":"current_model_update","currentModelId":"opus"}"""),
        )
        assertEquals("opus", assertIs<CurrentModelUpdate>(model).currentModelId)
    }

    @Test
    fun `available commands update carries the input hint`() {
        val json = """
            {
              "sessionUpdate": "available_commands_update",
              "availableCommands": [
                { "name": "web", "description": "Search the web", "input": { "hint": "search query" } },
                { "name": "test", "description": "Run the tests" }
              ]
            }
        """.trimIndent()

        val update = assertIs<AvailableCommandsUpdate>(AcpCodec.decodeSessionUpdate(AcpJson.parseToJsonElement(json)))

        assertEquals(listOf("web", "test"), update.availableCommands.map { it.name })
        assertEquals("search query", update.availableCommands.first().input?.hint)
        assertNull(update.availableCommands.last().input)
    }

    @Test
    fun `config options of session new carry the model selector`() {
        val json = """
            {
              "sessionId": "sess_1",
              "configOptions": [
                {
                  "id": "model",
                  "name": "Model",
                  "category": "model",
                  "type": "select",
                  "currentValue": "default",
                  "options": [
                    { "value": "default", "name": "Default (recommended)" },
                    { "value": "sonnet", "name": "Sonnet" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = AcpJson.decodeFromString(SessionNewResult.serializer(), json)
        val option = result.configOptions.single()

        assertTrue(option.isCategory(SessionConfigCategory.MODEL))
        assertEquals("default", option.currentValue)
        assertEquals(listOf("default", "sonnet"), option.choices.map { it.value })
        assertEquals("Sonnet", option.choices.last().presentableName)
    }

    @Test
    fun `the effort option is recognised by its category and by its id`() {
        val json = """
            {
              "sessionId": "sess_1",
              "configOptions": [
                {
                  "id": "effort",
                  "name": "Effort",
                  "category": "thought_level",
                  "currentValue": "medium",
                  "options": [
                    { "value": "low", "name": "Low" },
                    { "value": "medium", "name": "Medium" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val option = AcpJson.decodeFromString(SessionNewResult.serializer(), json).configOptions.single()

        // The category and the id disagree on this one, so both spellings must name it.
        assertTrue(option.isCategory(SessionConfigCategory.THOUGHT_LEVEL, SessionConfigCategory.EFFORT))
        assertTrue(
            SessionConfigOption(id = "effort").isCategory(
                SessionConfigCategory.THOUGHT_LEVEL,
                SessionConfigCategory.EFFORT,
            ),
        )
        assertEquals(listOf("low", "medium"), option.choices.map { it.value })
        assertEquals("medium", option.currentValue)
    }

    @Test
    fun `an option without a well known category keeps its choices`() {
        // `fast` shares the generic `model_config` category with any future option, so nothing
        // but its own choices can drive it (see `SandboxAgentMainPanel.populateExtraConfigOptions`).
        val json = """
            {
              "sessionId": "sess_1",
              "configOptions": [
                {
                  "id": "fast",
                  "name": "Fast mode",
                  "description": "Faster responses on supported models",
                  "category": "model_config",
                  "type": "select",
                  "currentValue": "off",
                  "options": [
                    { "value": "on", "name": "On" },
                    { "value": "off", "name": "Off" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val option = AcpJson.decodeFromString(SessionNewResult.serializer(), json).configOptions.single()

        assertFalse(option.isCategory(SessionConfigCategory.MODE, SessionConfigCategory.MODEL))
        assertEquals("Fast mode", option.presentableName)
        assertEquals(listOf("on", "off"), option.choices.map { it.value })
        assertEquals(listOf("On", "Off"), option.choices.map { it.presentableName })
        assertEquals("off", option.currentValue)
    }

    @Test
    fun `config option update flattens grouped choices`() {
        val update = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement(
                """
                {
                  "sessionUpdate": "config_option_update",
                  "configOptions": [
                    {
                      "id": "model",
                      "category": "model",
                      "currentValue": "opus",
                      "options": [
                        { "name": "Group", "options": [ { "value": "opus", "name": "Opus" } ] }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val option = assertIs<ConfigOptionUpdate>(update).configOptions.single()
        assertEquals(listOf("opus"), option.choices.map { it.value })
    }

    @Test
    fun `set config option params carry the config id and the value`() {
        val encoded = AcpJson
            .encodeToJsonElement(
                SessionSetConfigOptionParams.serializer(),
                SessionSetConfigOptionParams(sessionId = "sess_1", configId = "model", value = "sonnet"),
            )
            .jsonObject
        assertEquals(setOf("sessionId", "configId", "value"), encoded.keys)
        assertEquals("sonnet", encoded["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plan and unknown updates never break the parser`() {
        val plan = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement("""{"sessionUpdate":"plan","entries":[{"content":"step","status":"pending"}]}"""),
        )
        assertEquals("step", assertIs<PlanUpdate>(plan).entries.single().content)

        val usage = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement("""{"sessionUpdate":"usage_update","usage":{"inputTokens":10}}"""),
        )
        assertIs<UsageUpdate>(usage)

        val future = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement("""{"sessionUpdate":"brand_new_thing","payload":42}"""),
        )
        assertEquals("brand_new_thing", assertIs<UnknownSessionUpdate>(future).rawType)

        val futureContent = AcpCodec.decodeContentBlock(AcpJson.parseToJsonElement("""{"type":"hologram"}"""))
        assertEquals("hologram", assertIs<UnknownContentBlock>(futureContent).rawType)
    }

    @Test
    fun `request permission params of the spec are decoded`() {
        val json = """
            {
              "sessionId": "sess_01HXYZ",
              "toolCall": {
                "toolCallId": "call_123",
                "title": "Bash command: git status",
                "kind": "execute",
                "status": "pending",
                "rawInput": { "command": "git status" }
              },
              "options": [
                { "optionId": "allow", "name": "Allow", "kind": "allow_once", "isDefault": true },
                { "optionId": "deny", "name": "Deny", "kind": "reject_once" }
              ]
            }
        """.trimIndent()

        val params = AcpJson.decodeFromString(RequestPermissionParams.serializer(), json)

        assertEquals("call_123", params.toolCall.toolCallId)
        assertTrue(params.options.first().isAllow)
        assertTrue(params.options.first().isDefault)
        assertEquals(false, params.options.last().isAllow)
    }

    @Test
    fun `permission outcome carries the acp shape and the decision alias`() {
        val allow = PermissionOutcome.selected(PermissionOption(optionId = "allow", kind = PermissionOptionKind.ALLOW_ONCE))
        val encoded = AcpJson
            .encodeToJsonElement(RequestPermissionResult.serializer(), RequestPermissionResult(allow))
            .jsonObject["outcome"]!!
            .jsonObject

        assertEquals("selected", encoded["outcome"]!!.jsonPrimitive.content)
        assertEquals("allow", encoded["optionId"]!!.jsonPrimitive.content)
        assertEquals("allow", encoded["decision"]!!.jsonPrimitive.content)

        val cancelled = AcpJson
            .encodeToJsonElement(PermissionOutcome.serializer(), PermissionOutcome.cancelled())
            .jsonObject
        assertEquals("cancelled", cancelled["outcome"]!!.jsonPrimitive.content)
        assertNull(cancelled["optionId"])
    }

    @Test
    fun `session cancel params only carry the session id`() {
        val encoded = AcpJson
            .encodeToJsonElement(SessionCancelParams.serializer(), SessionCancelParams("sess_01HXYZ"))
            .jsonObject
        assertEquals(setOf("sessionId"), encoded.keys)
    }

    @Test
    fun `json rpc envelopes keep the id type`() {
        val numeric = AcpJson.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(id = rpcId(7), method = AcpMethods.SESSION_PROMPT),
        )
        assertTrue(numeric.contains("\"id\":7"), numeric)

        val textual = AcpJson.encodeToString(
            JsonRpcResponse.serializer(),
            JsonRpcResponse(id = rpcId("srv-9")),
        )
        assertTrue(textual.contains("\"id\":\"srv-9\""), textual)
        assertEquals("srv-9", rpcId("srv-9").key)
    }

    @Test
    fun `initialize result with object capabilities is decoded correctly`() {
        val json = """
            {
              "protocolVersion": 1,
              "agentInfo": { "name": "sandbox-agent", "version": "1.0.0" },
              "agentCapabilities": {
                "loadSession": {},
                "sessionCapabilities": {
                  "additionalDirectories": {},
                  "close": false
                },
                "promptCapabilities": {
                  "image": {},
                  "audio": false,
                  "embeddedContext": {}
                },
                "mcpCapabilities": {
                  "http": {},
                  "sse": true
                }
              },
              "authMethods": []
            }
        """.trimIndent()

        val result = AcpJson.decodeFromString(InitializeResult.serializer(), json)

        assertEquals(1, result.protocolVersion)
        assertEquals("sandbox-agent", result.agentInfo?.name)
        assertTrue(result.agentCapabilities.loadSession)
        assertEquals(true, result.agentCapabilities.sessionCapabilities?.additionalDirectories)
        assertEquals(false, result.agentCapabilities.sessionCapabilities?.close)
        assertEquals(true, result.agentCapabilities.promptCapabilities?.image)
        assertEquals(false, result.agentCapabilities.promptCapabilities?.audio)
        assertEquals(true, result.agentCapabilities.promptCapabilities?.embeddedContext)
        assertEquals(true, result.agentCapabilities.mcpCapabilities?.http)
        assertEquals(true, result.agentCapabilities.mcpCapabilities?.sse)
    }

    @Test
    fun `session management capabilities are decoded from booleans and objects`() {
        val json = """
            {
              "protocolVersion": 1,
              "agentCapabilities": {
                "loadSession": true,
                "sessionCapabilities": { "list": {}, "resume": true, "delete": false }
              }
            }
        """.trimIndent()

        val capabilities = AcpJson.decodeFromString(InitializeResult.serializer(), json).agentCapabilities

        assertEquals(true, capabilities.sessionCapabilities?.list)
        assertEquals(true, capabilities.sessionCapabilities?.resume)
        assertEquals(false, capabilities.sessionCapabilities?.delete)
    }

    @Test
    fun `session list result is decoded with tolerant timestamps`() {
        val json = """
            {
              "sessions": [
                { "sessionId": "sess_1", "title": "Fix the parser", "updatedAt": 1767225600000 },
                { "sessionId": "sess_2", "updatedAt": 1767225600 },
                { "sessionId": "sess_3", "updatedAt": "2026-01-01T00:00:00Z" },
                { "sessionId": "sess_4", "updatedAt": "not a date" },
                { "sessionId": "sess_5" }
              ]
            }
        """.trimIndent()

        val sessions = AcpJson.decodeFromString(SessionListResult.serializer(), json).sessions

        assertEquals(5, sessions.size)
        assertEquals("Fix the parser", sessions[0].title)
        // Epoch seconds and an ISO-8601 instant end up as the same epoch milliseconds value.
        assertEquals(1767225600000L, sessions[0].lastUpdatedMillis)
        assertEquals(1767225600000L, sessions[1].lastUpdatedMillis)
        assertEquals(1767225600000L, sessions[2].lastUpdatedMillis)
        // An unparsable or missing timestamp means "the agent does not track one".
        assertNull(sessions[3].lastUpdatedMillis)
        assertNull(sessions[4].lastUpdatedMillis)
    }

    @Test
    fun `session list and delete params carry the expected keys`() {
        val list = AcpJson
            .encodeToJsonElement(SessionListParams.serializer(), SessionListParams(cwd = "/workspace"))
            .jsonObject
        assertEquals(setOf("cwd"), list.keys)

        val resume = AcpJson
            .encodeToJsonElement(
                SessionResumeParams.serializer(),
                SessionResumeParams(sessionId = "sess_1", cwd = "/workspace"),
            )
            .jsonObject
        assertEquals("sess_1", resume["sessionId"]!!.jsonPrimitive.content)

        val delete = AcpJson
            .encodeToJsonElement(SessionDeleteParams.serializer(), SessionDeleteParams("sess_1"))
            .jsonObject
        assertEquals(setOf("sessionId"), delete.keys)
    }

    @Test
    fun `session info update is decoded through its spec name and its alias`() {
        val update = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement("""{"sessionUpdate":"session_info_update","title":"Fix the parser"}"""),
        )
        assertEquals("Fix the parser", assertIs<SessionInfoUpdate>(update).title)

        val alias = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement("""{"sessionUpdate":"session_info","title":"Alias","sessionId":"sess_1"}"""),
        )
        assertEquals("sess_1", assertIs<SessionInfoUpdate>(alias).sessionId)
    }

    /**
     * Chunks taken from a real `session/load` replay of a prompt with one attached file: the agent
     * replays the mention and the file body as separate chunks of the same message.
     */
    @Test
    fun `a replayed prompt keeps the typed text and drops its attachments`() {
        assertEquals(
            "Sort the array by key",
            TextContentBlock("Sort the array by key").userPromptText,
        )
        assertNull(
            TextContentBlock("[@sample.json](file:///workspace/tmp/sample.json)").userPromptText,
        )
        assertNull(
            TextContentBlock(
                "\n<context ref=\"file:///workspace/tmp/sample.json\">\n[{\"a\": 1}]\n</context>",
            ).userPromptText,
        )
    }

    @Test
    fun `attachment markup is stripped out of a chunk that also carries text`() {
        val block = TextContentBlock(
            "Sort it [@sample.json](file:///workspace/tmp/sample.json)\n" +
                "<context ref=\"file:///workspace/tmp/sample.json\">[]</context>",
        )

        assertEquals("Sort it", block.userPromptText)
    }

    /**
     * `session/request_permission` of the session modes documentation: a `switch_mode` whose
     * options are the modes themselves and whose plan is a bare content block, without the
     * `{"type":"content"}` wrapper the tool call examples use.
     */
    @Test
    fun `the switch_mode permission request of the spec is decoded`() {
        val json = """
            {
              "sessionId": "sess_abc123",
              "toolCall": {
                "toolCallId": "call_001",
                "title": "Ready for implementation",
                "kind": "switch_mode",
                "status": "pending",
                "content": [
                  { "type": "text", "text": "## Implementation Plan\n\n1. Add the card" }
                ]
              },
              "options": [
                { "optionId": "code", "name": "Yes, and auto-accept edits", "kind": "allow_always" },
                { "optionId": "ask", "name": "Yes, and manually approve edits", "kind": "allow_once" },
                { "optionId": "reject", "name": "No, keep planning", "kind": "reject_once" }
              ]
            }
        """.trimIndent()

        val params = AcpJson.decodeFromString(RequestPermissionParams.serializer(), json)

        assertEquals(ToolKind.SWITCH_MODE, params.toolCall.kind)
        // The options are the modes to continue in, not an invented allow / deny pair.
        assertEquals(listOf("code", "ask", "reject"), params.options.map { it.optionId })
        val content = assertIs<ToolCallContentBlock>(params.toolCall.content.single())
        assertEquals("## Implementation Plan\n\n1. Add the card", assertIs<TextContentBlock>(content.content).text)
    }

    @Test
    fun `current_mode_update is accepted with either field name`() {
        val spec = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement("""{"sessionUpdate":"current_mode_update","modeId":"code"}"""),
        )
        val schema = AcpCodec.decodeSessionUpdate(
            AcpJson.parseToJsonElement("""{"sessionUpdate":"current_mode_update","currentModeId":"architect"}"""),
        )

        assertEquals("code", assertIs<CurrentModeUpdate>(spec).modeId)
        assertEquals("architect", assertIs<CurrentModeUpdate>(schema).modeId)
    }

    @Test
    fun `an attachment content block is not shown as a prompt`() {
        assertNull(
            EmbeddedResourceContentBlock(EmbeddedResource(uri = "file:///workspace/a.txt", text = "body"))
                .userPromptText,
        )
        assertNull(ResourceLinkContentBlock(uri = "file:///workspace/a.txt").userPromptText)
    }

    /** The body of a replayed attachment is dropped, but its chip is what the prompt keeps. */
    @Test
    fun `an attachment content block is reduced to its chip`() {
        val file = EmbeddedResourceContentBlock(
            EmbeddedResource(uri = "file:///workspace/tmp/sample.json", text = "[]"),
        )
        val selection = ResourceLinkContentBlock(uri = "file:///workspace/tmp/sample.json#L5-5")

        assertEquals(
            PromptAttachment(path = "/workspace/tmp/sample.json"),
            file.promptAttachments.single(),
        )
        assertEquals(
            PromptAttachment(path = "/workspace/tmp/sample.json", startLine = 5, endLine = 5),
            selection.promptAttachments.single(),
        )
        assertEquals("sample.json:5-5", selection.promptAttachments.single().label)
    }

    @Test
    fun `the chips of a replayed prompt are read out of its attachment markup`() {
        val mention = TextContentBlock("[@sample.json](file:///workspace/tmp/sample.json)")
        val embedded = TextContentBlock(
            "<context ref=\"file:///workspace/tmp/sample.json#L5-L9\">[]</context>",
        )
        val encoded = TextContentBlock("[@a b.txt](file:///workspace/a%20b.txt)")

        assertEquals("/workspace/tmp/sample.json", mention.promptAttachments.single().path)
        assertEquals(
            PromptAttachment(path = "/workspace/tmp/sample.json", startLine = 5, endLine = 9, content = "[]"),
            embedded.promptAttachments.single(),
        )
        assertEquals("/workspace/a b.txt", encoded.promptAttachments.single().path)
        // Nothing to show for a prompt that carries no attachment at all.
        assertTrue(TextContentBlock("Sort the array by key").promptAttachments.isEmpty())
    }

    /**
     * A selection is the one attachment the file itself cannot stand in for, so a replay keeps
     * the snippet it was sent with; the body of a whole file is left where it already is.
     */
    @Test
    fun `only a replayed selection keeps the content it was sent with`() {
        val selection = EmbeddedResourceContentBlock(
            EmbeddedResource(uri = "file:///workspace/tmp/sample.json#L5-9", text = "[{\"a\": 1}]"),
        )
        val file = EmbeddedResourceContentBlock(
            EmbeddedResource(uri = "file:///workspace/tmp/sample.json", text = "[{\"a\": 1}]"),
        )

        assertEquals("[{\"a\": 1}]", selection.promptAttachments.single().content)
        assertNull(file.promptAttachments.single().content)
    }

    /**
     * The URI an attachment is sent with is percent-encoded, so the mention an agent replays it
     * as is still one URI: a space or a parenthesis no longer cuts the path short.
     */
    @Test
    fun `an attachment with spaces, parentheses and non ASCII characters survives the replay`() {
        val path = "/workspace/資料 (1)+a.md"
        val sent = AcpContextItem(path = path, content = "# 資料").toContentBlock() as EmbeddedResourceContentBlock
        val selection = AcpContextItem(path = path, content = "x", startLine = 2, endLine = 3)
            .toContentBlock() as EmbeddedResourceContentBlock

        assertEquals("file:///workspace/%E8%B3%87%E6%96%99%20%281%29%2Ba.md", sent.resource.uri)
        val mention = TextContentBlock("Read this [@資料 (1)+a.md](${sent.resource.uri})")
        assertEquals(PromptAttachment(path = path), mention.promptAttachments.single())
        assertEquals("Read this", mention.userPromptText)
        assertEquals(
            PromptAttachment(path = path, startLine = 2, endLine = 3),
            ResourceLinkContentBlock(uri = selection.resource.uri).promptAttachments.single(),
        )
    }

    @Test
    fun `a plain path is left as it is in the URI`() {
        assertEquals("/workspace/src/Main_v2.kt", encodeUriPath("/workspace/src/Main_v2.kt"))
        assertEquals("C:/work/a~b.txt", encodeUriPath("C:/work/a~b.txt"))
    }

    /** The same attachment replayed twice is one chip, and the snippet is not lost to the copy. */
    @Test
    fun `an attachment replayed as both markups keeps its content`() {
        val block = TextContentBlock(
            "<context ref=\"file:///workspace/tmp/sample.json#L5-5\">[]</context>\n" +
                "[@sample.json](file:///workspace/tmp/sample.json#L5-5)",
        )

        assertEquals("[]", block.promptAttachments.single().content)
    }
}
