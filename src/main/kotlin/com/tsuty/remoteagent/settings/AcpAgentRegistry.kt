package com.tsuty.remoteagent.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Official ACP agent registry, the source of the `ACP Agent` drop down. */
const val ACP_REGISTRY_URL: String = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"

@Serializable
private data class RegistryDocument(val agents: List<RegistryAgent> = emptyList())

@Serializable
private data class RegistryAgent(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val distribution: RegistryDistribution? = null,
    val icon: String? = null,
)

/**
 * `npx` and `uvx` run an agent in a single command the plugin can spell out itself. A `binary`
 * distribution cannot be spelled out: it would have to be downloaded and unpacked inside the
 * sandbox first, so only the arguments of its entry are read — as a hint for the command the user
 * types (see [AgentLauncher.BINARY]).
 */
@Serializable
private data class RegistryDistribution(
    val npx: RegistryPackage? = null,
    val uvx: RegistryPackage? = null,
    /**
     * Keyed by `<os>-<arch>` — of the machine the agent runs on, which is the sandbox and not the
     * IDE, so the key cannot be picked by looking at the host. The entries differ in the path of
     * the executable, which is of no use here, and agree on the arguments, which are.
     */
    val binary: Map<String, RegistryBinary> = emptyMap(),
)

@Serializable
private data class RegistryPackage(
    @SerialName("package") val packageName: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

/**
 * One platform of a `binary` distribution. Its `archive`, `cmd` and `sha256` are left unread:
 * nothing is downloaded, unpacked or verified, and the path of the executable inside the sandbox
 * is known to whoever installed it there.
 */
@Serializable
private data class RegistryBinary(
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

/**
 * Builds the `ACP Agent` options from the ACP registry.
 *
 * `npx` and `uvx` come first for an entry that offers several distributions: their command is
 * derived from the package, so picking them asks nothing of the user. An entry that ships as a
 * pre-built `binary` only is still offered, but the plugin installs nothing into the sandbox — the
 * executable is put there by hand and the command that starts it is typed into the settings.
 */
object AcpAgentRegistry {
    private val log = Logger.getInstance(AcpAgentRegistry::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    /** Id of the agent selected by default (SPEC §5). */
    private const val DEFAULT_AGENT_ID = "claude-acp"

    /**
     * Used until the registry answers, and whenever it cannot be reached. Its package is left
     * unpinned: without the registry there is no version to pin it to.
     */
    val FALLBACK_AGENT = AcpAgent(
        id = DEFAULT_AGENT_ID,
        name = "Claude Agent",
        packageName = "@agentclientprotocol/claude-agent-acp",
        launcher = AgentLauncher.NPX,
    )

    /** Agents available without any network access. */
    fun fallbackAgents(): List<AcpAgent> = listOf(FALLBACK_AGENT)

    /** Parses [content] keeping the registry order. */
    fun parse(content: String): List<AcpAgent> {
        val agents = json.decodeFromString<RegistryDocument>(content).agents.mapNotNull(::toAgent)
        return agents.ifEmpty { fallbackAgents() }
    }

    /** Cached for the IDE session: the registry is a static file and the settings dialog is reopened often. */
    @Volatile
    private var cached: List<AcpAgent>? = null

    /** Downloads and parses the registry; falls back to [fallbackAgents] when it is unreachable. */
    fun load(): List<AcpAgent> {
        cached?.let { return it }
        return try {
            parse(HttpRequests.request(ACP_REGISTRY_URL).productNameAsUserAgent().readString())
                .also { cached = it }
        } catch (e: Exception) {
            log.warn("Cannot read the ACP agent registry from $ACP_REGISTRY_URL", e)
            fallbackAgents()
        }
    }

    /**
     * Agent selected by default (SPEC §5): the Claude agent when the registry still ships it, the
     * first entry of the registry otherwise.
     */
    fun defaultAgent(agents: List<AcpAgent> = load()): AcpAgent =
        agents.firstOrNull { it.id == DEFAULT_AGENT_ID } ?: agents.firstOrNull() ?: FALLBACK_AGENT

    /**
     * A single-command distribution wins over a `binary` one: it needs no command to be typed.
     * The `binary` entry read for the arguments is the first the registry lists, all of them
     * naming the same ones — the key says which platform the executable is for, and only the
     * sandbox knows that.
     */
    private fun toAgent(agent: RegistryAgent): AcpAgent? {
        val id = agent.id.takeIf { it.isNotBlank() } ?: return null
        val distribution = agent.distribution ?: return null
        distribution.npx?.takeIf { it.packageName.isNotBlank() }?.let {
            return agent.toAgent(id, AgentLauncher.NPX, it.packageName, it.args, it.env)
        }
        distribution.uvx?.takeIf { it.packageName.isNotBlank() }?.let {
            return agent.toAgent(id, AgentLauncher.UVX, it.packageName, it.args, it.env)
        }
        val binary = distribution.binary.values.firstOrNull() ?: return null
        return agent.toAgent(id, AgentLauncher.BINARY, packageName = "", binary.args, binary.env)
    }

    private fun RegistryAgent.toAgent(
        id: String,
        launcher: AgentLauncher,
        packageName: String,
        args: List<String>,
        env: Map<String, String>,
    ): AcpAgent = AcpAgent(
        id = id,
        name = name.takeIf { it.isNotBlank() } ?: id,
        packageName = packageName,
        launcher = launcher,
        args = args.filter { it.isNotBlank() },
        version = version,
        description = description,
        iconUrl = icon?.takeIf { it.isNotBlank() },
        env = env,
    )
}
