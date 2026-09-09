package com.tsuty.remoteagent.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class LoginShellsTest {

    @Test
    fun `a login bash is the default`() {
        assertEquals("bash", LoginShells.DEFAULT.id)
        assertEquals("/bin/bash -l", LoginShells.DEFAULT.command)
    }

    /** `sh` is the one without `-l`: the POSIX shell of a minimal image does not know it. */
    @Test
    fun `every offered shell is listed with its command`() {
        assertEquals(
            listOf(
                "bash" to "/bin/bash -l",
                "zsh" to "/bin/zsh -l",
                "ash" to "/bin/ash -l",
                "sh" to "/bin/sh",
            ),
            LoginShells.ALL.map { it.id to it.command },
        )
        assertEquals(
            listOf("bash (/bin/bash)", "zsh (/bin/zsh)", "ash (/bin/ash)", "sh (/bin/sh)"),
            LoginShells.ALL.map { it.label },
        )
    }

    @Test
    fun `an unknown id degrades to the default`() {
        assertEquals(LoginShells.BASH, LoginShells.byId("fish"))
        assertEquals("/bin/zsh -l", LoginShells.byId("zsh").command)
    }
}
