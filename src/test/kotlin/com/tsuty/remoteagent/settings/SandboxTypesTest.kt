package com.tsuty.remoteagent.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SandboxTypesTest {

    @Test
    fun `docker sandboxes is the default`() {
        assertEquals("docker-sandboxes", SandboxTypes.DEFAULT.id)
        assertEquals("sbx", SandboxTypes.DEFAULT.command)
    }

    /** Only CLIs that spell `exec -i <name> <command>` the same way are offered. */
    @Test
    fun `every supported sandbox is listed with its command`() {
        assertEquals(
            listOf(
                "docker-sandboxes" to "sbx",
                "docker" to "docker",
                "apple-container" to "container",
                "wsl-containers" to "wslc",
            ),
            SandboxTypes.ALL.map { it.id to it.command },
        )
        assertEquals(
            listOf("Docker sandboxes", "Docker", "Apple container", "WSL Containers"),
            SandboxTypes.ALL.map { it.label },
        )
    }

    @Test
    fun `an unknown id degrades to the default`() {
        assertEquals(SandboxTypes.DOCKER_SANDBOXES, SandboxTypes.byId("podman"))
        assertEquals("docker", SandboxTypes.byId("docker").command)
    }
}
