package com.tsuty.sandboxagent.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PluginDataDirectoriesTest {

    @Test
    fun `the key is the readable name of the project and a stable hash of its location`() {
        assertEquals("MyProject", PluginDataDirectories.projectKey("/work/MyProject").substringBefore('.'))
        assertEquals(PluginDataDirectories.projectKey("/work/MyProject"), PluginDataDirectories.projectKey("/work/MyProject"))
        assertEquals(16, PluginDataDirectories.projectKey("/work/MyProject").substringAfterLast('.').length)
    }

    @Test
    fun `projects of the same name at another location get their own key`() {
        assertNotEquals(PluginDataDirectories.projectKey("/work/a/app"), PluginDataDirectories.projectKey("/work/b/app"))
    }

    @Test
    fun `characters a file name should not hold are replaced`() {
        assertEquals("my_app", PluginDataDirectories.projectKey("/work/my app").substringBeforeLast('.'))
    }
}
