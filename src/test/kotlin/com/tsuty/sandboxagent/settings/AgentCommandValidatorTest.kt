package com.tsuty.sandboxagent.settings

import com.tsuty.sandboxagent.acp.SandboxCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentCommandValidatorTest {

    @Test
    fun `a path with its arguments is what is expected here`() {
        assertNull(AgentCommandValidator.validate("/opt/goose/goose acp"))
        assertNull(AgentCommandValidator.validate("./kimchi --mode acp"))
        assertNull(AgentCommandValidator.validate("vtcode acp"))
    }

    /**
     * Quotes have to stay allowed: an installation path may hold spaces, and quoting is how the
     * argument vector is told that it is still one argument.
     */
    @Test
    fun `a quoted path keeps its spaces as one argument`() {
        val command = """"/Users/foo/my agent/bin/agent" acp"""

        assertNull(AgentCommandValidator.validate(command))
        assertEquals(
            listOf("/Users/foo/my agent/bin/agent", "acp"),
            SandboxCommand.toArguments(command),
            "the quoted path was split into two arguments",
        )
    }

    @Test
    fun `an empty command is refused`() {
        assertNotNull(AgentCommandValidator.validate(""))
        assertNotNull(AgentCommandValidator.validate("   "))
    }

    /**
     * None of these does anything: the agent is started as an argument vector handed to the OS,
     * never through a shell, so they would only travel into the agent as literal text.
     */
    @Test
    fun `what needs a shell is refused with the reason`() {
        listOf(
            "agent acp | tee log",
            "agent acp > out.log",
            "agent acp >> out.log",
            "agent acp 2> err.log",
            "agent acp < in.txt",
            "agent acp; other",
            "agent acp && other",
            "agent acp & ",
            "agent acp \$HOME",
            "agent acp `id`",
            "agent (acp)",
        ).forEach {
            val message = AgentCommandValidator.validate(it)
            assertNotNull(message, "<$it> was accepted")
            assertEquals(true, message.contains("not through a shell"), "<$it> was refused for another reason")
        }
    }

    /** A line break would travel into the stdio the agent speaks ACP over. */
    @Test
    fun `a command spanning more than one line is refused`() {
        assertNotNull(AgentCommandValidator.validate("agent\nacp"))
        assertNotNull(AgentCommandValidator.validate("agent\tacp"))
        assertNotNull(AgentCommandValidator.validate("agent acp\r"))
    }

    /** `ParametersListUtil` reads an unclosed quote silently, splitting somewhere unintended. */
    @Test
    fun `an unclosed quote is refused`() {
        assertNotNull(AgentCommandValidator.validate(""""/opt/my agent/agent acp"""))
        assertNotNull(AgentCommandValidator.validate("'/opt/agent acp"))
        assertNull(AgentCommandValidator.validate("""agent "acp" "-v""""))
    }

    @Test
    fun `a command longer than the limit is refused`() {
        val limit = "a".repeat(AgentCommandValidator.MAX_LENGTH)

        assertNull(AgentCommandValidator.validate(limit))
        assertNotNull(AgentCommandValidator.validate(limit + "a"))
    }
}
