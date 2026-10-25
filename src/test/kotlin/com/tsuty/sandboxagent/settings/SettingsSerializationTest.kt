package com.tsuty.sandboxagent.settings

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsSerializationTest {

    @Test
    fun `defaults mirror SPEC section 5`() {
        val settings = SandboxAgentSettingsState()
        assertEquals("docker-sandboxes", settings.sandboxTypeId)
        assertEquals("", settings.sandboxName)
        assertEquals("bash", settings.loginShellId)
        assertEquals("/bin/bash -l", settings.loginShell().command)
        assertEquals("claude-acp", settings.agentId)
        assertEquals("npx -y @agentclientprotocol/claude-agent-acp", settings.agent().command)
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

    @Test
    fun `state survives a serialization round trip`() {
        val original = SandboxAgentSettingsState().apply {
            sandboxTypeId = "docker"
            sandboxName = "my sandbox"
            loginShellId = "zsh"
            setAgent(
                AcpAgent(
                    id = "gemini",
                    name = "Gemini CLI",
                    packageName = "@google/gemini-cli@0.58.0",
                    args = listOf("--acp"),
                    version = "0.58.0",
                    description = "Google's official CLI for Gemini",
                ),
            )
            sandboxWorkspacePath = "/workspace"
            restoreLastSession = true
            autoAttachContext = true
            initializeIdleTimeoutSeconds = 300
        }

        val element = XmlSerializer.serialize(original.state)
        val restored = SandboxAgentSettingsState()
        restored.loadState(XmlSerializer.deserialize(element, SandboxAgentSettingsState::class.java))

        assertEquals(original.sandboxTypeId, restored.sandboxTypeId)
        assertEquals(original.sandboxName, restored.sandboxName)
        assertEquals(original.loginShellId, restored.loginShellId)
        assertEquals("/bin/zsh -l", restored.loginShell().command)
        assertEquals(original.agent(), restored.agent())
        assertEquals("npx -y @google/gemini-cli@0.58.0 --acp", restored.agent().command)
        assertEquals(original.sandboxWorkspacePath, restored.sandboxWorkspacePath)
        assertEquals(original.restoreLastSession, restored.restoreLastSession)
        assertEquals(original.autoAttachContext, restored.autoAttachContext)
        assertEquals(original.initializeIdleTimeoutSeconds, restored.initializeIdleTimeoutSeconds)
    }

    /**
     * The one agent whose command is stored as text: a pre-built binary is installed into the
     * sandbox by hand, so there is no package to derive a command from (see [AgentLauncher.BINARY]).
     */
    @Test
    fun `the typed command of a binary agent survives a round trip`() {
        val original = SandboxAgentSettingsState().apply {
            setAgent(
                AcpAgent(
                    id = "goose",
                    name = "goose",
                    packageName = "",
                    launcher = AgentLauncher.BINARY,
                    args = listOf("acp"),
                    version = "1.50.0",
                    customCommand = "/opt/goose/goose acp",
                ),
            )
        }

        val element = XmlSerializer.serialize(original.state)
        val restored = SandboxAgentSettingsState()
        restored.loadState(XmlSerializer.deserialize(element, SandboxAgentSettingsState::class.java))

        assertEquals(original.agent(), restored.agent())
        assertEquals(AgentLauncher.BINARY, restored.agent().launcher)
        assertEquals("/opt/goose/goose acp", restored.agent().command)
        assertEquals("/opt/goose/goose acp", restored.toSandboxConfig().agentCommand)
    }

    /**
     * An installation from before there were binary agents holds no command, and it is never read
     * for the `npx` / `uvx` launcher it does hold: nothing has to be migrated.
     */
    @Test
    fun `an unknown launcher degrades to the derived command`() {
        val settings = SandboxAgentSettingsState().apply { agentLauncher = "pipx" }

        assertEquals(AgentLauncher.NPX, settings.agent().launcher)
        assertEquals("npx -y @agentclientprotocol/claude-agent-acp", settings.agent().command)
    }

    @Test
    fun `maps onto the sandbox config`() {
        val settings = SandboxAgentSettingsState().apply {
            sandboxTypeId = "apple-container"
            sandboxName = "my sandbox"
            agentPackage = "@google/gemini-cli@0.58.0"
            agentArgs = "--acp"
            sandboxWorkspacePath = "/workspace"
            restoreLastSession = true
            initializeIdleTimeoutSeconds = 300
        }

        val config = settings.toSandboxConfig()

        assertEquals("container", config.sandboxCommand)
        assertEquals(settings.sandboxName, config.sandboxName)
        assertEquals("npx -y @google/gemini-cli@0.58.0 --acp", config.agentCommand)
        assertEquals(settings.sandboxWorkspacePath, config.sandboxWorkspacePath)
        assertEquals(settings.restoreLastSession, config.restoreLastSession)
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
        val stored = SandboxAgentSettingsState().apply { agentId = "gemini" }
        val element = XmlSerializer.serialize(stored.state)

        val loaded = SandboxAgentSettingsState()
        loaded.loadState(XmlSerializer.deserialize(element, SandboxAgentSettingsState::class.java))
        assertEquals("gemini", loaded.legacyAgentIdOrDefault())

        // Picking another agent later does not move it.
        loaded.agentId = "codex"
        val reloaded = SandboxAgentSettingsState()
        reloaded.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(loaded.state), SandboxAgentSettingsState::class.java))
        assertEquals("gemini", reloaded.legacyAgentIdOrDefault())
    }

    @Test
    fun `a project without stored settings ran the default agent`() {
        assertEquals("claude-acp", SandboxAgentSettingsState().legacyAgentIdOrDefault())
    }
}
