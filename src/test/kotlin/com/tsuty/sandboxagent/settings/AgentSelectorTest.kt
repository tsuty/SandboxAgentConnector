package com.tsuty.sandboxagent.settings

import com.intellij.ui.components.JBTextField
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSelectorTest {

    private val binary = AcpAgent(
        id = "goose",
        name = "goose",
        packageName = "",
        launcher = AgentLauncher.BINARY,
        args = listOf("acp"),
        version = "1.50.0",
    )

    /** Laid out with an update offered, which is the widest the row ever gets. */
    private fun laidOut(): AgentSelector = AgentSelector().apply {
        setState(AcpAgentRegistry.FALLBACK_AGENT.copy(version = "1.0.0"))
        setAgents(listOf(AcpAgentRegistry.FALLBACK_AGENT.copy(version = "1.1.0")))
        setSize(600, preferredSize.height)
        layoutTree()
    }

    /**
     * The version and the update offer share a row of their own with the drop down, so laying the
     * selector out alone leaves them all at the origin.
     */
    private fun Container.layoutTree() {
        doLayout()
        components.filterIsInstance<Container>().forEach { it.layoutTree() }
    }

    /**
     * The editors of the selector, the row that holds the drop down opened up. Only that one row
     * is: descending any further would reach into the drop down, whose own arrow button and cell
     * renderer are none of this test's business.
     */
    private val AgentSelector.parts: List<Component>
        get() = components.flatMap { if (it is JPanel) it.components.toList() else listOf(it) }

    private inline fun <reified T> AgentSelector.part(): T = parts.filterIsInstance<T>().single()

    /** The version of the selected entry, which is the first of the two quiet labels. */
    private val AgentSelector.versionLabel: JLabel get() = parts.filterIsInstance<JLabel>().first()

    /** What the selected entry expects in the sandbox, shown under the command. */
    private val AgentSelector.envLabel: JLabel get() = parts.filterIsInstance<JLabel>().last()

    /** Where a part sits in the coordinates of the selector itself. */
    private fun AgentSelector.xOf(component: Component): Int =
        SwingUtilities.convertPoint(component.parent, component.location, this).x

    /**
     * The drop down starts at the left edge of the panel: the cell of the row is what lines the
     * `ACP Agent` editor up with every other one of the dialog, so nothing may be inserted before
     * it (a `FlowLayout` would add its own gap there).
     */
    @Test
    fun `the drop down starts at the left edge of the panel`() {
        val selector = laidOut()

        assertEquals(0, selector.xOf(selector.part<JComboBox<*>>()))
    }

    /** The version and the update offer read as one line with the drop down, not below it. */
    @Test
    fun `the version and the update button sit next to the drop down`() {
        val selector = laidOut()
        val combo = selector.part<JComboBox<*>>()
        val version = selector.versionLabel
        val update = selector.part<JButton>()

        assertTrue(version.x >= combo.x + combo.width, "the version is not next to the drop down")
        assertTrue(update.x >= version.x + version.width, "the update button is not next to the version")
        listOf(version, update).forEach {
            assertEquals(combo.y + combo.height / 2, it.y + it.height / 2, "$it is not on the row of the drop down")
        }
        assertTrue(
            selector.preferredSize.height < 2 * combo.preferredSize.height,
            "the version is stacked under the drop down instead of sitting next to it",
        )
    }

    /** The update button only shows up once the registry ships a newer version than the picked one. */
    @Test
    fun `the update button is offered only for a newer version`() {
        val selector = AgentSelector()
        val pinned = AcpAgentRegistry.FALLBACK_AGENT.copy(version = "1.0.0")
        val update = selector.part<JButton>()

        selector.setState(pinned)
        assertFalse(update.isVisible, "an agent the registry did not answer for is offered an update")

        selector.setAgents(listOf(pinned.copy(version = "1.1.0")))
        assertTrue(update.isVisible)
        assertEquals("1.0.0", selector.agent.version, "the newer version was taken without asking")
    }

    /** The command of a `binary` agent is the one thing the plugin cannot know, so it is typed. */
    @Test
    fun `the command field shows up only for a binary agent`() {
        val selector = AgentSelector()
        val field = selector.part<JBTextField>()

        selector.setState(AcpAgentRegistry.FALLBACK_AGENT)
        assertFalse(field.isVisible, "a command derived from a package is typed as well")

        selector.setState(binary)
        assertTrue(field.isVisible)
        assertEquals("", field.text, "the registry named a command it cannot know the path of")
        assertEquals("/path/to/agent acp", field.emptyText.text, "the arguments to keep are not hinted at")
    }

    /** Typing is what selects the agent here: the field *is* the command that gets persisted. */
    @Test
    fun `the typed command is what the settings hold`() {
        val selector = AgentSelector()
        val field = selector.part<JBTextField>()
        selector.setState(binary)

        assertNotNull(selector.commandError(), "a binary agent without a command can be applied")

        field.text = "/opt/goose/goose acp"

        assertNull(selector.commandError())
        assertEquals("/opt/goose/goose acp", selector.agent.command)
    }

    /**
     * A typed command pins no version, so a newer registry entry is not an update to offer: the
     * version shown is simply the one the registry ships today.
     */
    @Test
    fun `a binary agent is never offered an update`() {
        val selector = AgentSelector()
        val update = selector.part<JButton>()
        val version = selector.versionLabel

        selector.setState(binary)
        selector.setAgents(listOf(binary.copy(version = "1.51.0")))

        assertFalse(update.isVisible)
        assertEquals("Version 1.51.0", version.text)
        assertEquals("1.50.0", selector.agent.version, "the stored entry followed the registry")
    }

    /** The plugin passes no environment into the sandbox, so what an entry needs is only shown. */
    @Test
    fun `the environment an entry asks for is shown as a hint`() {
        val selector = AgentSelector()

        selector.setState(AcpAgentRegistry.FALLBACK_AGENT)
        assertFalse(selector.envLabel.isVisible, "an entry without environment shows a hint about it")

        selector.setAgents(
            listOf(AcpAgentRegistry.FALLBACK_AGENT.copy(env = mapOf("AUGMENT_DISABLE_AUTO_UPDATE" to "1"))),
        )

        assertTrue(selector.envLabel.isVisible)
        assertEquals(
            "Expects in the sandbox: AUGMENT_DISABLE_AUTO_UPDATE=1",
            selector.envLabel.text,
        )
    }
}
