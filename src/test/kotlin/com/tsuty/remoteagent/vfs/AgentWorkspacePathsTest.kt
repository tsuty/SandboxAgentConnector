package com.tsuty.remoteagent.vfs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentWorkspacePathsTest {

    @Test
    fun `files below the workspace are locked`() {
        assertTrue(AgentWorkspacePaths.isUnderWorkspace("/home/me/project/src/Main.kt", "/home/me/project"))
    }

    @Test
    fun `the workspace root itself is locked`() {
        assertTrue(AgentWorkspacePaths.isUnderWorkspace("/home/me/project", "/home/me/project"))
        assertTrue(AgentWorkspacePaths.isUnderWorkspace("/home/me/project/", "/home/me/project/"))
    }

    @Test
    fun `a sibling with the same prefix is not locked`() {
        assertFalse(AgentWorkspacePaths.isUnderWorkspace("/home/me/project-other/Main.kt", "/home/me/project"))
    }

    @Test
    fun `files outside the workspace stay writable`() {
        assertFalse(AgentWorkspacePaths.isUnderWorkspace("/tmp/scratch.kt", "/home/me/project"))
    }

    @Test
    fun `nothing is locked without a workspace root`() {
        assertFalse(AgentWorkspacePaths.isUnderWorkspace("/home/me/project/src/Main.kt", null))
        assertFalse(AgentWorkspacePaths.isUnderWorkspace("/home/me/project/src/Main.kt", ""))
    }
}
