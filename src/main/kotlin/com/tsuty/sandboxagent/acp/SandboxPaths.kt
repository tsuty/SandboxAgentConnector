package com.tsuty.sandboxagent.acp

/**
 * Translates paths of the project between the host and the sandbox: the project root on the
 * host ([hostRoot]) is the ACP `cwd` in the sandbox ([sandboxRoot]), and every file below it has
 * the same relative path in both. A path outside the root is left as it is — attachments are
 * always inside the project, so that only happens to a path an agent wrote.
 *
 * Both roots use `/` separators: the IDE spells host paths that way on Windows as well
 * (`C:/Users/…`), and the sandbox is POSIX.
 */
class SandboxPaths(hostRoot: String, sandboxRoot: String) {
    private val host = hostRoot.trimEnd('/')
    private val sandbox = sandboxRoot.trimEnd('/')

    fun toSandbox(hostPath: String): String = relativeTo(hostPath, host)?.let { join(sandbox, it) } ?: hostPath

    fun toHost(sandboxPath: String): String = relativeTo(sandboxPath, sandbox)?.let { join(host, it) } ?: sandboxPath

    /** The part of [path] below [root] (`""` for the root itself); `null` outside it. */
    private fun relativeTo(path: String, root: String): String? = when {
        path == root || (root.isEmpty() && path == "/") -> ""
        path.startsWith("$root/") -> path.substring(root.length + 1)
        else -> null
    }

    private fun join(root: String, relative: String): String = when {
        relative.isEmpty() -> root.ifEmpty { "/" }
        else -> "$root/$relative"
    }

    companion object {
        /** No translation: the sandbox sees the project where the host has it. */
        val IDENTITY = SandboxPaths("", "")
    }
}
