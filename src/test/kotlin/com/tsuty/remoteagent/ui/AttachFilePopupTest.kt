package com.tsuty.remoteagent.ui

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachFilePopupTest {

    private class FakeVirtualFile(
        private val fakePath: String,
        private val fakeName: String,
        private val isDir: Boolean,
        private val fakeParent: VirtualFile? = null,
    ) : LightVirtualFile(fakeName, PlainTextFileType.INSTANCE, "") {
        override fun getPath(): String = fakePath
        override fun getName(): String = fakeName
        override fun isDirectory(): Boolean = isDir
        override fun getParent(): VirtualFile? = fakeParent
        override fun isValid(): Boolean = true
    }

    @Test
    fun testAttachFilePopupStepStructureAndSeparators() {
        val file1 = FakeVirtualFile("/project/src/Main.kt", "Main.kt", false)
        val file2 = FakeVirtualFile("/project/src/Utils.kt", "Utils.kt", false)
        val dir1 = FakeVirtualFile("/project/src", "src", true)

        val items = listOf(
            AttachItem.Search,
            AttachItem.FileItem(file1, file1.name, "src/Main.kt"),
            AttachItem.FileItem(file2, file2.name, "src/Utils.kt"),
            AttachItem.DirectoryItem(dir1, dir1.name, "src"),
        )

        var chosenFile: VirtualFile? = null
        var searchChosen = false

        val step = AttachFilePopupStep(
            items = items,
            onChosen = { chosenFile = it },
            onSearch = { searchChosen = true },
        )

        // Text & Secondary text verification
        assertEquals("Select from file search...", step.getTextFor(items[0]))
        assertNull(step.getSecondaryTextFor(items[0]))

        assertEquals("Main.kt", step.getTextFor(items[1]))
        assertEquals("src/Main.kt", step.getSecondaryTextFor(items[1]))

        assertEquals("Utils.kt", step.getTextFor(items[2]))
        assertEquals("src/Utils.kt", step.getSecondaryTextFor(items[2]))

        assertEquals("src", step.getTextFor(items[3]))
        assertEquals("src", step.getSecondaryTextFor(items[3]))

        // Separator verification
        assertNull(step.getSeparatorAbove(items[0]))
        assertEquals("Open Files", step.getSeparatorAbove(items[1])?.text)
        assertNull(step.getSeparatorAbove(items[2]))
        assertEquals("Directories", step.getSeparatorAbove(items[3])?.text)

        // Tooltip verification
        assertEquals("Search and attach files in project", step.getTooltipTextFor(items[0]))
        assertEquals("/project/src/Main.kt", step.getTooltipTextFor(items[1]))
        assertEquals("/project/src", step.getTooltipTextFor(items[3]))

        // Selection callbacks
        step.onChosen(items[0], true)
        assertTrue(searchChosen)

        step.onChosen(items[1], true)
        assertEquals(file1, chosenFile)

        step.onChosen(items[3], true)
        assertEquals(dir1, chosenFile)
    }

    @Test
    fun testExtractVirtualFile() {
        val file = FakeVirtualFile("/project/src/Main.kt", "Main.kt", false)
        assertEquals(file, AttachFilePopup.extractVirtualFile(file))
        assertNull(AttachFilePopup.extractVirtualFile("some string"))
        assertNull(AttachFilePopup.extractVirtualFile(null))
    }

    @Test
    fun testDirectoryDeduplication() {
        val dir = FakeVirtualFile("/project/src/ui", "ui", true)
        val file1 = FakeVirtualFile("/project/src/ui/PanelA.kt", "PanelA.kt", false, dir)
        val file2 = FakeVirtualFile("/project/src/ui/PanelB.kt", "PanelB.kt", false, dir)

        val openFiles = listOf(file1, file2)
        val dirs = openFiles.mapNotNull { it.parent }.distinctBy { it.path }
        assertEquals(1, dirs.size)
        assertEquals("/project/src/ui", dirs.first().path)
        assertEquals("ui", dirs.first().name)
    }
}
