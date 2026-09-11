package com.tsuty.sandboxagent.ui

import com.intellij.openapi.Disposable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Numbering, the 10-tab cap and the disposal order of the active session tabs (Ph2 §4).
 * [TabsController] is generic in its service/panel types precisely so this can run with plain
 * fake [Disposable]s: a real `SandboxAgentService` needs a project service container a plain unit
 * test does not have.
 */
class TabsControllerTest {

    /** A fake service/panel resource that records when it was disposed. */
    private class FakeResource(private val name: String, private val disposed: MutableList<String>) : Disposable {
        override fun dispose() {
            disposed += name
        }
    }

    private fun controller(
        maxTabs: Int = TabsController.MAX_TABS,
        disposed: MutableList<String> = mutableListOf(),
    ): TabsController<FakeResource, FakeResource> = TabsController(
        maxTabs = maxTabs,
        serviceFactory = { FakeResource("service", disposed) },
        panelFactory = { FakeResource("panel", disposed) },
    )

    @Test
    fun `a fresh controller has no tabs and can add one`() {
        val controller = controller()

        assertEquals(0, controller.size)
        assertTrue(controller.canAddTab)
    }

    @Test
    fun `addTab appends a tab and numbers it by position`() {
        val controller = controller()

        val first = controller.addTab()
        val second = controller.addTab()

        assertEquals(0, first?.let(controller::numberOf))
        assertEquals(1, second?.let(controller::numberOf))
        assertEquals(listOf(first, second), controller.tabs)
    }

    @Test
    fun `addTab fails once the limit is reached`() {
        val controller = controller(maxTabs = 2)

        val first = controller.addTab()
        val second = controller.addTab()
        val third = controller.addTab()

        assertTrue(first != null && second != null)
        assertNull(third)
        assertFalse(controller.canAddTab)
        assertEquals(2, controller.size)
    }

    @Test
    fun `closing a tab disposes its panel before its service`() {
        val disposed = mutableListOf<String>()
        val controller = controller(disposed = disposed)
        val tab = controller.addTab()!!

        controller.closeTab(tab)

        assertEquals(listOf("panel", "service"), disposed)
        assertEquals(0, controller.size)
    }

    @Test
    fun `closing a tab renumbers the tabs that shift left`() {
        val controller = controller()
        val first = controller.addTab()!!
        val second = controller.addTab()!!
        val third = controller.addTab()!!

        controller.closeTab(first)

        assertEquals(0, controller.numberOf(second))
        assertEquals(1, controller.numberOf(third))
    }

    @Test
    fun `closing an already closed tab is a no-op`() {
        val disposed = mutableListOf<String>()
        val controller = controller(disposed = disposed)
        val tab = controller.addTab()!!
        controller.closeTab(tab)
        disposed.clear()

        controller.closeTab(tab)

        assertTrue(disposed.isEmpty())
    }

    @Test
    fun `closing a tab frees a slot for a new one at the limit`() {
        val controller = controller(maxTabs = 1)
        val first = controller.addTab()!!
        assertNull(controller.addTab())

        controller.closeTab(first)
        val second = controller.addTab()

        assertTrue(second != null)
        assertEquals(1, controller.size)
    }

    @Test
    fun `moveTab renumbers every tab between the old and the new position`() {
        val controller = controller()
        val first = controller.addTab()!!
        val second = controller.addTab()!!
        val third = controller.addTab()!!

        controller.moveTab(0, 2)

        assertEquals(listOf(second, third, first), controller.tabs)
        assertEquals(0, controller.numberOf(second))
        assertEquals(1, controller.numberOf(third))
        assertEquals(2, controller.numberOf(first))
    }

    @Test
    fun `moveTab ignores an out-of-range or identity move`() {
        val controller = controller()
        val first = controller.addTab()!!
        val second = controller.addTab()!!

        controller.moveTab(0, 0)
        controller.moveTab(-1, 1)
        controller.moveTab(0, 5)

        assertEquals(listOf(first, second), controller.tabs)
    }

    @Test
    fun `tabAt resolves the tab currently showing a given shortcut number`() {
        val controller = controller()
        val first = controller.addTab()!!
        val second = controller.addTab()!!

        assertSame(first, controller.tabAt(0))
        assertSame(second, controller.tabAt(1))
        assertNull(controller.tabAt(2))
    }

    @Test
    fun `numberOf is null for a tab the controller does not hold`() {
        val controllerA = controller()
        val controllerB = controller()
        val tabOfB = controllerB.addTab()!!

        assertNull(controllerA.numberOf(tabOfB))
    }

    @Test
    fun `dispose closes every open tab`() {
        val disposed = mutableListOf<String>()
        val controller = controller(disposed = disposed)
        controller.addTab()
        controller.addTab()

        controller.dispose()

        assertEquals(0, controller.size)
        assertEquals(listOf("panel", "service", "panel", "service"), disposed)
    }
}
