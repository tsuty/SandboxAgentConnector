package com.tsuty.remoteagent.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AcpAgentRegistryTest {

    private val registry = """
        {
          "version": "1.0.0",
          "agents": [
            {
              "id": "goose",
              "name": "goose",
              "version": "1.50.0",
              "distribution": {
                "binary": {
                  "darwin-aarch64": {
                    "archive": "x", "sha256": "y", "cmd": "./goose", "args": ["acp"],
                    "env": { "GOOSE_ACP": "1" }
                  },
                  "linux-x86_64": { "archive": "z", "cmd": "./goose-linux", "args": ["acp"] }
                }
              }
            },
            {
              "id": "kilo",
              "name": "Kilo Code",
              "distribution": {
                "binary": { "darwin-aarch64": { "archive": "x", "cmd": "./kilo", "args": ["acp"] } },
                "npx": { "package": "@kilocode/cli@7.5.16", "args": ["acp"] }
              }
            },
            {
              "id": "claude-acp",
              "name": "Claude Agent",
              "version": "0.73.0",
              "description": "ACP wrapper for Anthropic's Claude",
              "distribution": { "npx": { "package": "@agentclientprotocol/claude-agent-acp@0.73.0" } },
              "icon": "https://cdn.agentclientprotocol.com/registry/v1/latest/claude-acp.svg"
            },
            {
              "id": "gemini",
              "name": "Gemini CLI",
              "version": "0.58.0",
              "distribution": { "npx": { "package": "@google/gemini-cli@0.58.0", "args": ["--acp"] } }
            },
            {
              "id": "fast-agent",
              "name": "fast-agent",
              "version": "0.10.1",
              "distribution": { "uvx": { "package": "fast-agent-acp==0.10.1", "args": ["-x"] } }
            },
            { "id": "no-distribution", "name": "No distribution" }
          ]
        }
    """.trimIndent()

    @Test
    fun `keeps the registry order and skips the agents without a distribution`() {
        val agents = AcpAgentRegistry.parse(registry)

        assertEquals(listOf("goose", "kilo", "claude-acp", "gemini", "fast-agent"), agents.map { it.id })
        assertEquals("goose", agents.first().name)
    }

    /**
     * A pre-built binary is offered, but its command is the user's: nothing is installed into the
     * sandbox, so only the arguments of the entry are kept — as the hint of the field that is
     * typed into (see [AgentSelector]). The path of the executable and its `sha256` are of no use
     * here and are left unread.
     */
    @Test
    fun `a binary agent brings its arguments and no command`() {
        val agent = AcpAgentRegistry.parse(registry).first { it.id == "goose" }

        assertEquals(AgentLauncher.BINARY, agent.launcher)
        assertEquals(listOf("acp"), agent.args)
        assertEquals("", agent.packageName)
        assertEquals("", agent.command, "a command was invented for an agent nobody installed yet")
        assertEquals(mapOf("GOOSE_ACP" to "1"), agent.env)
    }

    /** A single-command distribution wins: picking it asks for no command to be typed. */
    @Test
    fun `an agent shipped both ways is started with the single command`() {
        val agent = AcpAgentRegistry.parse(registry).first { it.id == "kilo" }

        assertEquals(AgentLauncher.NPX, agent.launcher)
        assertEquals("npx -y @kilocode/cli@7.5.16 acp", agent.command)
    }

    @Test
    fun `npx commands are auto approved with -y`() {
        val agents = AcpAgentRegistry.parse(registry).associateBy { it.id }

        assertEquals("npx -y @agentclientprotocol/claude-agent-acp@0.73.0", agents.getValue("claude-acp").command)
        assertEquals("npx -y @google/gemini-cli@0.58.0 --acp", agents.getValue("gemini").command)
    }

    /** `uvx` runs a package without prompting, so it has no `-y` counterpart. */
    @Test
    fun `uvx commands keep the package and its arguments`() {
        val agent = AcpAgentRegistry.parse(registry).first { it.id == "fast-agent" }

        assertEquals(AgentLauncher.UVX, agent.launcher)
        assertEquals("uvx fast-agent-acp==0.10.1 -x", agent.command)
    }

    /** The version and the description drive the update offer and the tooltip of the drop down. */
    @Test
    fun `an agent keeps the version and the description of its registry entry`() {
        val agents = AcpAgentRegistry.parse(registry).associateBy { it.id }

        assertEquals("0.73.0", agents.getValue("claude-acp").version)
        assertEquals("ACP wrapper for Anthropic's Claude", agents.getValue("claude-acp").description)
        assertEquals("", agents.getValue("gemini").description, "an entry without a description has none")
    }

    /** The icon of an entry is kept next to its command: the UI draws the agent with it. */
    @Test
    fun `an agent keeps the icon of its registry entry`() {
        val agents = AcpAgentRegistry.parse(registry).associateBy { it.id }

        assertEquals(
            "https://cdn.agentclientprotocol.com/registry/v1/latest/claude-acp.svg",
            agents.getValue("claude-acp").iconUrl,
        )
        assertNull(agents.getValue("gemini").iconUrl, "an entry without an icon has none")
    }

    /** Shown as a hint, so an entry that names none is simply an entry with an empty hint. */
    @Test
    fun `the environment of a package is kept as a hint`() {
        val agents = AcpAgentRegistry.parse(
            """{"agents":[
                {"id":"auggie","distribution":{"npx":{"package":"a","env":{"AUGMENT_DISABLE_AUTO_UPDATE":"1"}}}},
                {"id":"gemini","distribution":{"npx":{"package":"b"}}}
            ]}""",
        ).associateBy { it.id }

        assertEquals(mapOf("AUGMENT_DISABLE_AUTO_UPDATE" to "1"), agents.getValue("auggie").env)
        assertEquals(emptyMap<String, String>(), agents.getValue("gemini").env)
    }

    @Test
    fun `claude is selected by default`() {
        assertEquals("claude-acp", AcpAgentRegistry.defaultAgent(AcpAgentRegistry.parse(registry)).id)
        assertEquals("claude-acp", AcpAgentRegistry.defaultAgent(AcpAgentRegistry.fallbackAgents()).id)
    }

    @Test
    fun `an empty registry degrades to the offline agents`() {
        assertEquals(AcpAgentRegistry.fallbackAgents(), AcpAgentRegistry.parse("""{"agents":[]}"""))
    }

    @Test
    fun `unknown fields do not break the parser`() {
        val agents = AcpAgentRegistry.parse(
            """{"future":true,"agents":[{"id":"a","name":"A","icon":"i","distribution":{"npx":{"package":"a","env":{"K":"V"}}}}]}""",
        )

        assertEquals("npx -y a", agents.first().command)
    }
}
