package com.tsuty.sandboxagent.settings

import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSerializationTest {

    @Test
    fun `defaults mirror SPEC section 5`() {
        val settings = SandboxAgentSettingsState()
        assertEquals("docker-sandboxes", settings.sandboxTypeId)
        assertEquals("", settings.sandboxName)
        assertEquals("bash", settings.loginShellId)
        assertEquals("/bin/bash -l", settings.loginShell().command)
        assertEquals(listOf("claude-acp"), settings.enabledAgents().map { it.id })
        assertEquals("npx -y @agentclientprotocol/claude-agent-acp", settings.defaultAgent().agent().command)
        assertEquals("", settings.sandboxWorkspacePath)
        assertFalse(settings.restoreLastSession)
        assertFalse(settings.autoAttachContext)
        assertEquals(180, settings.initializeIdleTimeoutSeconds)
    }

    @Test
    fun `the startup idle timeout stays inside the offered range`() {
        val settings = SandboxAgentSettingsState()
        assertEquals(180, settings.apply { initializeIdleTimeoutSeconds = 0 }.initializeIdleTimeoutSecondsOrDefault())
        assertEquals(10, settings.apply { initializeIdleTimeoutSeconds = 1 }.initializeIdleTimeoutSecondsOrDefault())
        assertEquals(1800, settings.apply { initializeIdleTimeoutSeconds = 9_999 }.initializeIdleTimeoutSecondsOrDefault())
        assertEquals(300, settings.apply { initializeIdleTimeoutSeconds = 300 }.initializeIdleTimeoutSecondsOrDefault())
    }

    private fun roundTrip(settings: SandboxAgentSettingsState): SandboxAgentSettingsState {
        val element = XmlSerializer.serialize(settings.state)
        return SandboxAgentSettingsState().apply {
            loadState(XmlSerializer.deserialize(element, SandboxAgentSettingsState::class.java))
        }
    }

    private val gemini = AcpAgent(
        id = "gemini",
        name = "Gemini CLI",
        packageName = "@google/gemini-cli@0.58.0",
        args = listOf("--acp"),
        version = "0.58.0",
        description = "Google's official CLI for Gemini",
        iconUrl = "https://cdn.example/gemini.svg",
    )

    /**
     * The one agent whose command is stored as text: a pre-built binary is installed into the
     * sandbox by hand, so there is no package to derive a command from (see [AgentLauncher.BINARY]).
     */
    private val goose = AcpAgent(
        id = "goose",
        name = "goose",
        packageName = "",
        launcher = AgentLauncher.BINARY,
        args = listOf("acp"),
        version = "1.50.0",
        customCommand = "/opt/goose/goose acp",
    )

    @Test
    fun `state survives a serialization round trip`() {
        val original = SandboxAgentSettingsState().apply {
            sandboxTypeId = "docker"
            sandboxName = "my sandbox"
            loginShellId = "zsh"
            updateAgents(
                listOf(AgentEntry.of(AcpAgentRegistry.FALLBACK_AGENT), AgentEntry.of(gemini, iconSvg = "<svg/>"), AgentEntry.of(goose)),
                defaultId = "gemini",
            )
            sandboxWorkspacePath = "/workspace"
            autoAttachContext = true
            initializeIdleTimeoutSeconds = 300
        }

        val restored = roundTrip(original)

        assertEquals(original.sandboxTypeId, restored.sandboxTypeId)
        assertEquals(original.sandboxName, restored.sandboxName)
        assertEquals(original.loginShellId, restored.loginShellId)
        assertEquals("/bin/zsh -l", restored.loginShell().command)
        assertEquals(original.agents, restored.agents)
        assertEquals("gemini", restored.defaultAgent().id)
        assertEquals("npx -y @google/gemini-cli@0.58.0 --acp", restored.defaultAgent().agent().command)
        assertEquals("<svg/>", restored.agentById("gemini")?.iconSvg)
        assertEquals("/opt/goose/goose acp", restored.agentById("goose")?.agent()?.command)
        assertEquals(original.sandboxWorkspacePath, restored.sandboxWorkspacePath)
        assertEquals(original.autoAttachContext, restored.autoAttachContext)
        assertEquals(original.initializeIdleTimeoutSeconds, restored.initializeIdleTimeoutSeconds)
    }

    @Test
    fun `the agent of a version that ran a single agent becomes the one enabled agent`() {
        val old = SandboxAgentSettingsState().apply {
            agentId = "gemini"
            agentPackage = "@google/gemini-cli@0.58.0"
            agentArgs = "--acp"
            agentVersion = "0.58.0"
            agentName = "Gemini CLI"
            agentIconUrl = "https://cdn.example/gemini.svg"
            agentIconSvg = "<svg/>"
        }

        val migrated = roundTrip(old)

        val entry = migrated.enabledAgents().single()
        assertEquals("gemini", migrated.defaultAgent().id)
        assertEquals("npx -y @google/gemini-cli@0.58.0 --acp", entry.agent().command)
        assertEquals("Gemini CLI", entry.name)
        assertEquals("<svg/>", entry.iconSvg)
        // The old fields are emptied, which keeps them out of the file from now on: a component
        // state is written with its default values skipped.
        assertEquals("", migrated.agentId)
        val element = XmlSerializer.serialize(migrated.state, SkipDefaultsSerializationFilter())
        val written = com.intellij.openapi.util.JDOMUtil.write(element)
        assertFalse(written.contains("agentPackage"), written)
    }

    /** Such a version stored a value equal to its default (the fallback agent's) as nothing. */
    @Test
    fun `an old file that named nothing ran the fallback agent`() {
        val old = SandboxAgentSettingsState().apply { sandboxName = "box" }

        val migrated = roundTrip(old)

        assertEquals(listOf("claude-acp"), migrated.enabledAgents().map { it.id })
        assertEquals("Claude Agent", migrated.defaultAgent().name)
        assertEquals("npx -y @agentclientprotocol/claude-agent-acp", migrated.defaultAgent().agent().command)
    }

    @Test
    fun `an old binary agent keeps its command and gets no package`() {
        val old = SandboxAgentSettingsState().apply {
            agentId = "goose"
            agentLauncher = "binary"
            agentCommand = "/opt/goose/goose acp"
        }

        val entry = roundTrip(old).defaultAgent()

        assertEquals("", entry.packageName)
        assertEquals("/opt/goose/goose acp", entry.agent().command)
    }

    /**
     * An installation from before there were binary agents holds no command, and it is never read
     * for the `npx` / `uvx` launcher it does hold: nothing has to be migrated.
     */
    @Test
    fun `an unknown launcher degrades to the derived command`() {
        val entry = AgentEntry.of(AcpAgentRegistry.FALLBACK_AGENT).apply { launcher = "pipx" }

        assertEquals(AgentLauncher.NPX, entry.agent().launcher)
        assertEquals("npx -y @agentclientprotocol/claude-agent-acp", entry.agent().command)
    }

    @Test
    fun `an agent is enabled once and the default falls back to the first`() {
        val settings = SandboxAgentSettingsState()

        settings.updateAgents(listOf(AgentEntry.of(gemini), AgentEntry.of(gemini.copy(version = "1")), AgentEntry.of(goose)), "codex")

        assertEquals(listOf("gemini", "goose"), settings.agents.map { it.id })
        assertEquals("0.58.0", settings.agents.first().version)
        assertEquals("gemini", settings.defaultAgent().id)
        assertTrue(settings.isEnabled("goose"))
        assertFalse(settings.isEnabled("claude-acp"))
        assertNull(settings.agentById("claude-acp"))
    }

    @Test
    fun `maps onto the sandbox config of an agent`() {
        val settings = SandboxAgentSettingsState().apply {
            sandboxTypeId = "apple-container"
            sandboxName = "my sandbox"
            sandboxWorkspacePath = "/workspace"
            initializeIdleTimeoutSeconds = 300
        }

        val config = settings.toSandboxConfig(gemini)

        assertEquals("container", config.sandboxCommand)
        assertEquals(settings.sandboxName, config.sandboxName)
        assertEquals("npx -y @google/gemini-cli@0.58.0 --acp", config.agentCommand)
        assertEquals(settings.sandboxWorkspacePath, config.sandboxWorkspacePath)
        assertEquals(settings.initializeIdleTimeoutSeconds, config.initializeIdleTimeoutSeconds)
    }

    @Test
    fun `default settings map onto the default sandbox config`() {
        assertEquals(
            com.tsuty.sandboxagent.acp.SandboxConfig(),
            SandboxAgentSettingsState().toSandboxConfig(),
        )
    }

    @Test
    fun `the legacy owner of the snapshots is recorded once from the stored agent`() {
        val loaded = roundTrip(SandboxAgentSettingsState().apply { agentId = "gemini" })
        assertEquals("gemini", loaded.legacyAgentIdOrDefault())

        // Enabling other agents later does not move it.
        loaded.updateAgents(listOf(AgentEntry.of(goose)), "goose")
        assertEquals("gemini", roundTrip(loaded).legacyAgentIdOrDefault())
    }

    @Test
    fun `a project without stored settings ran the default agent`() {
        assertEquals("claude-acp", SandboxAgentSettingsState().legacyAgentIdOrDefault())
    }
}
