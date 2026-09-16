package com.tsuty.sandboxagent.service

import kotlin.test.Test
import kotlin.test.assertEquals

/** Rewriting the Windows fallback `cwd` into the POSIX form Git Bash / MSYS / WSL sandboxes expect. */
class WindowsPathToPosixTest {

    @Test
    fun `a forward-slash drive path is rewritten to its posix form`() {
        assertEquals("/c/Users/tutty/workspace/shovel", windowsPathToPosix("C:/Users/tutty/workspace/shovel"))
    }

    @Test
    fun `a backslash drive path is normalized and rewritten`() {
        assertEquals("/c/Users/tutty/workspace/shovel", windowsPathToPosix("C:\\Users\\tutty\\workspace\\shovel"))
    }

    @Test
    fun `the drive letter is lower-cased regardless of its original case`() {
        assertEquals("/d/Projects/app", windowsPathToPosix("D:/Projects/app"))
    }

    @Test
    fun `a path without a drive prefix is left untouched`() {
        assertEquals("/home/tutty/workspace/shovel", windowsPathToPosix("/home/tutty/workspace/shovel"))
        assertEquals("", windowsPathToPosix(""))
    }
}
