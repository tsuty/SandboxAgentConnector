package com.tsuty.sandboxagent.settings

/**
 * One sandbox CLI the plugin can attach to, reduced to the name of its command.
 *
 * The rest of the command line is not configurable: all four of them attach to an already running
 * sandbox with the very same `exec -i <name> <command>`, and offering only those is what makes the
 * generated command line predictable (see `SandboxCommand`).
 */
data class SandboxType(val id: String, val label: String, val command: String)

object SandboxTypes {
    val DOCKER_SANDBOXES = SandboxType("docker-sandboxes", "Docker sandboxes", "sbx")

    val ALL: List<SandboxType> = listOf(
        DOCKER_SANDBOXES,
        SandboxType("docker", "Docker", "docker"),
        SandboxType("apple-container", "Apple container", "container"),
        SandboxType("wsl-containers", "WSL Containers", "wslc"),
    )

    /** Default type (SPEC §5): Docker sandboxes. */
    val DEFAULT: SandboxType get() = DOCKER_SANDBOXES

    /** The persisted id, degrading to [DEFAULT] when it named a type that is no longer offered. */
    fun byId(id: String): SandboxType = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
