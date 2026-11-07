package com.tsuty.sandboxagent.session

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Where the plugin keeps the session files and the ACP traffic logs of a project: under the data
 * directory every JetBrains IDE shares. The `system` and `log` directories of an IDE are named
 * after its product and version, so an IDE update would leave these files behind, and the IDE
 * cleans up the directories of old versions on its own.
 *
 * The directory of a project depends on its location alone, so all the IDEs that open the same
 * project see the same sessions.
 */
object PluginDataDirectories {
    /** Replaces the root, so an IDE started by `runIde` stays away from the files of the real one. */
    const val ROOT_OVERRIDE_PROPERTY: String = "sandboxAgent.dataDir"

    private const val ROOT_NAME = "SandboxAgentConnector"
    private const val HASH_HEX_LENGTH = 16

    private val ILLEGAL_FILENAME_CHARS = Regex("[^A-Za-z0-9_.-]")

    fun sessions(project: Project): File = File(projectDirectory(project), "sessions")

    fun logs(project: Project): File = File(projectDirectory(project), "logs")

    private fun projectDirectory(project: Project): File =
        File(File(root(), "projects"), projectKey(project.basePath ?: project.name))

    private fun root(): File =
        System.getProperty(ROOT_OVERRIDE_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: PathManager.getCommonDataPath().resolve(ROOT_NAME).toFile()

    /**
     * Name of the directory of the project at [location]: its readable name, then a hash of the
     * location. Computed here rather than taken from the platform, whose key could change with a
     * version of the IDE, which is the very thing this directory has to survive.
     */
    internal fun projectKey(location: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(location.toByteArray(StandardCharsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }.take(HASH_HEX_LENGTH)
        return "${File(location).name.replace(ILLEGAL_FILENAME_CHARS, "_")}.$hash"
    }
}
