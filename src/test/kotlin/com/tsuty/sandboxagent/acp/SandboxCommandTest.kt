package com.tsuty.sandboxagent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SandboxCommandTest {

    private val config = SandboxConfig()

    @Test
    fun `builds the attach argv from the sandbox and the agent command`() {
        assertEquals(
            listOf("sbx", "exec", "-i", "MyProject", "npx", "-y", "@agentclientprotocol/claude-agent-acp"),
            SandboxCommand.arguments(config.sandboxCommand, "MyProject", config.agentCommand),
        )
    }

    /** The name is one argument and never goes through a shell, so it may hold anything. */
    @Test
    fun `a sandbox name with spaces stays a single argument`() {
        assertEquals(
            listOf("docker", "exec", "-i", "My Project", "node", "agent.js"),
            SandboxCommand.arguments("docker", " My Project ", "node agent.js"),
        )
    }

    @Test
    fun `agent arguments honour quotes`() {
        assertEquals(
            listOf("container", "exec", "-i", "box", "uvx", "agent", "--name", "My Agent"),
            SandboxCommand.arguments("container", "box", "uvx agent --name \"My Agent\""),
        )
    }

    @Test
    fun `an incomplete configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> { SandboxCommand.arguments("  ", "box", "agent") }
        assertFailsWith<IllegalArgumentException> { SandboxCommand.arguments("sbx", "  ", "agent") }
        assertFailsWith<IllegalArgumentException> { SandboxCommand.arguments("sbx", "box", "  ") }
    }

    /** A shell driven by a human needs the TTY the agent must not get. */
    @Test
    fun `the terminal argv adds a tty and runs the login shell`() {
        assertEquals(
            listOf("sbx", "exec", "-i", "-t", "MyProject", "/bin/bash", "-l"),
            SandboxCommand.terminalArguments(config.sandboxCommand, "MyProject", "/bin/bash -l"),
        )
    }

    @Test
    fun `the terminal command line quotes the sandbox name`() {
        assertEquals(
            "docker exec -i -t \"My Project\" /bin/sh",
            SandboxCommand.terminalCommandLine("docker", "My Project", "/bin/sh"),
        )
    }

    @Test
    fun `an incomplete terminal configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> { SandboxCommand.terminalArguments("  ", "box", "/bin/sh") }
        assertFailsWith<IllegalArgumentException> { SandboxCommand.terminalArguments("sbx", "  ", "/bin/sh") }
        assertFailsWith<IllegalArgumentException> { SandboxCommand.terminalArguments("sbx", "box", "  ") }
    }

    @Test
    fun `the shown command quotes the sandbox name`() {
        assertEquals(
            "wslc exec -i \"My Project\" npx -y agent",
            SandboxCommand.display("wslc", "My Project", "npx -y agent"),
        )
    }
}
