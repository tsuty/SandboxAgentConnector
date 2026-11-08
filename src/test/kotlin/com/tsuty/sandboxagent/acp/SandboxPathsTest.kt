package com.tsuty.sandboxagent.acp

import kotlin.test.Test
import kotlin.test.assertEquals

class SandboxPathsTest {

    private val paths = SandboxPaths("/Users/me/project/", "/workspace")

    @Test
    fun `a path of the project keeps its relative path on both sides`() {
        assertEquals("/workspace/src/A.kt", paths.toSandbox("/Users/me/project/src/A.kt"))
        assertEquals("/Users/me/project/src/A.kt", paths.toHost("/workspace/src/A.kt"))
        assertEquals("/workspace", paths.toSandbox("/Users/me/project"))
    }

    @Test
    fun `a path outside the root, or only sharing its prefix, is left alone`() {
        assertEquals("/Users/me/other/B.kt", paths.toSandbox("/Users/me/other/B.kt"))
        assertEquals("/Users/me/project2/B.kt", paths.toSandbox("/Users/me/project2/B.kt"))
        assertEquals("/tmp/C.kt", paths.toHost("/tmp/C.kt"))
    }

    @Test
    fun `a windows project maps onto the posix cwd its drive path became`() {
        val windows = SandboxPaths("C:/Users/me/project", "/c/Users/me/project")

        assertEquals("/c/Users/me/project/src/A.kt", windows.toSandbox("C:/Users/me/project/src/A.kt"))
        assertEquals("C:/Users/me/project/src/A.kt", windows.toHost("/c/Users/me/project/src/A.kt"))
    }

    @Test
    fun `the sandbox root itself may be the file system root`() {
        val root = SandboxPaths("/Users/me/project", "/")

        assertEquals("/src/A.kt", root.toSandbox("/Users/me/project/src/A.kt"))
        assertEquals("/", root.toSandbox("/Users/me/project"))
        assertEquals("/Users/me/project/src/A.kt", root.toHost("/src/A.kt"))
    }
}
