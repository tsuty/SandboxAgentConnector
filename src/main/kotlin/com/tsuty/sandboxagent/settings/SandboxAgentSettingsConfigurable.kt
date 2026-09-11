package com.tsuty.sandboxagent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel

/** `Settings > Tools > Sandbox Agent Connector`. */
class SandboxAgentSettingsConfigurable(private val project: Project) : BoundConfigurable("Sandbox Agent Connector") {
    private val settings get() = SandboxAgentSettingsState.getInstance(project)

    private val sandboxTypeCombo = ComboBox(DefaultComboBoxModel(SandboxTypes.ALL.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }

    private val sandboxNameField = JBTextField().apply {
        emptyText.text = project.name
    }

    private val loginShellCombo = ComboBox(DefaultComboBoxModel(LoginShells.ALL.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }

    // The command line these settings produce is deliberately not shown: it carries nothing the
    // user has to act on and can grow long (an agent may need environment variables of its own).
    // What was actually started is written to the IDE log instead (see `SandboxProcessManager`).
    // The one exception is an agent shipped as a pre-built binary, whose command is typed here
    // because nothing else can know it (see `AgentSelector`).
    private val agentSelector = AgentSelector()

    // Every section shares the grid of the panel (`groupRowsRange` instead of `group`), which is
    // what keeps the label column — and with it the left edge of every drop down, field and
    // spinner — the same one from the first section to the last.
    override fun createPanel(): DialogPanel = panel {
        groupRowsRange("Sandbox") {
            row("Sandbox type:") {
                cell(sandboxTypeCombo)
                    .onReset { sandboxTypeCombo.selectedItem = SandboxTypes.byId(settings.sandboxTypeId) }
                    .onIsModified { selectedSandboxType().id != settings.sandboxTypeId }
                    .onApply { settings.sandboxTypeId = selectedSandboxType().id }
            }
            row {
                comment(
                    "The sandbox is attached to with <code>exec -i</code>, which every listed CLI spells the " +
                        "same way; the plugin never starts or stops the sandbox itself.",
                )
            }
            row("Sandbox name:") {
                cell(sandboxNameField)
                    .align(AlignX.FILL)
                    .onReset { sandboxNameField.text = settings.sandboxName }
                    .onIsModified { sandboxNameField.text.trim() != settings.sandboxName }
                    .onApply { settings.sandboxName = sandboxNameField.text.trim() }
            }
            row {
                comment("Name of the running sandbox. Empty uses the project name (<code>${project.name}</code>).")
            }
            row("Login shell:") {
                cell(loginShellCombo)
                    .onReset { loginShellCombo.selectedItem = LoginShells.byId(settings.loginShellId) }
                    .onIsModified { selectedLoginShell().id != settings.loginShellId }
                    .onApply { settings.loginShellId = selectedLoginShell().id }
            }
            row {
                comment(
                    "Shell started by <b>Open Sandbox Terminal</b> in the options menu of the tool window, " +
                        "which runs <code>exec -i -t</code> with it instead of the agent.",
                )
            }
            row("ACP Agent:") {
                // Not filled: the version and the update offer sit next to the drop down and are
                // laid out from the left edge of the cell (see `AgentSelector`).
                cell(agentSelector)
                    .onReset {
                        agentSelector.setState(settings.agent())
                        agentSelector.setAgents(AcpAgentRegistry.fallbackAgents())
                        loadAgentRegistry()
                    }
                    .onIsModified { !agentSelector.agent.isSameSelection(settings.agent()) }
                    // A binary agent is started with a typed command, and a command that cannot
                    // work is refused here rather than at the sandbox (see `AgentCommandValidator`).
                    .validationOnApply { agentSelector.commandError()?.let { error(it) } }
                    .onApply {
                        val agent = agentSelector.agent
                        settings.setAgent(agent)
                        applyAgentIcon(agent)
                    }
            }
            row {
                comment(
                    "Agents are read from the <a href='$ACP_REGISTRY_URL'>ACP registry</a>. One runnable with a " +
                        "single <code>npx</code> / <code>uvx</code> command needs nothing else: the command is " +
                        "built from the registry entry, which pins the version of the agent, so a newer one is " +
                        "offered as an update instead of being taken silently.",
                )
            }
            row {
                comment(
                    "An agent shipped as a pre-built <code>binary</code> is started with the command entered " +
                        "below the drop down: the plugin never installs anything into the sandbox, so the " +
                        "executable has to be there already and only its path says where. It is started as an " +
                        "argument list and not through a shell, so pipes, redirections and variables are refused " +
                        "— they would reach the agent as literal text. No update is offered for it either, the " +
                        "command being the one the user owns.",
                )
            }
            row {
                comment(
                    "Credentials and any other environment the agent needs (<code>ANTHROPIC_API_KEY</code> and " +
                        "the like) have to be set inside the sandbox beforehand: the plugin passes no environment " +
                        "into it. The registry entries that name environment variables of their own show them " +
                        "next to the drop down.",
                )
            }
            row("Agent startup idle timeout (s):") {
                spinner(
                    SandboxAgentSettingsState.MIN_INITIALIZE_IDLE_TIMEOUT_SECONDS..
                        SandboxAgentSettingsState.MAX_INITIALIZE_IDLE_TIMEOUT_SECONDS,
                    step = 10,
                ).bindIntValue(settings::initializeIdleTimeoutSeconds)
            }
            row {
                comment(
                    "Not a deadline: attaching is only given up when the agent produced no output at all " +
                        "for that long. A first run downloads the agent with <code>npx</code>, which reports " +
                        "its progress next to the connection status, and that progress keeps the wait alive.",
                )
            }
        }

        groupRowsRange("ACP Session") {
            row("Remote workspace path (CWD):") {
                textField().bindText(settings::remoteWorkspacePath).align(AlignX.FILL)
            }
            row {
                comment("Sent as the ACP <code>cwd</code> of <code>session/new</code>, e.g. /workspace. Empty uses the local project root.")
            }
            row {
                checkBox("Restore the previous session when the agent supports session/load")
                    .bindSelected(settings::restoreLastSession)
            }
        }

        groupRowsRange("Workspace") {
            row {
                checkBox("Make the workspace read-only in the IDE while a turn is running")
                    .bindSelected(settings::lockEditorsDuringTurn)
            }
            row {
                comment(
                    "Prevents a local edit from racing the agent. Only the IDE is locked: the sandbox keeps " +
                        "writing to the files, and the changes are refreshed into the IDE when the turn ends.",
                )
            }
        }

        groupRowsRange("Context Attach") {
            row {
                checkBox("Auto-attach context (active file or selection)").bindSelected(settings::autoAttachContext)
            }
        }

        groupRowsRange("Appearance") {
            row("Transcript font size:") {
                spinner(
                    SandboxAgentSettingsState.MIN_TRANSCRIPT_FONT_SIZE..SandboxAgentSettingsState.MAX_TRANSCRIPT_FONT_SIZE,
                ).bindIntValue(settings::transcriptFontSize)
            }
            row {
                comment(
                    "Base size of the transcript, in pixels. Every other size is derived from it: code blocks " +
                        "read at the same size, and the quiet rows (timestamps, tool calls) one step smaller.",
                )
            }
        }
    }

    override fun apply() {
        super.apply()
        SandboxAgentSettingsBridge.apply(project)
    }

    private fun selectedSandboxType(): SandboxType =
        sandboxTypeCombo.selectedItem as? SandboxType ?: SandboxTypes.DEFAULT

    private fun selectedLoginShell(): LoginShell =
        loginShellCombo.selectedItem as? LoginShell ?: LoginShells.DEFAULT

    /** The registry is fetched off the EDT; the drop down is refilled once it answers. */
    private fun loadAgentRegistry() {
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val agents = AcpAgentRegistry.load()
            application.invokeLater({ if (!project.isDisposed) agentSelector.setAgents(agents) }, ModalityState.any())
            // Fills in the drawing of the persisted agent, which an installation from before there
            // was one does not have yet — and which is not a modification of the settings, so
            // nothing here waits for an `Apply` that has no reason to happen.
            agents.firstOrNull { it.id == settings.agentId }?.let { storeAgentIcon(it) }
        }
    }

    /** Keeps the drawing of the picked agent next to its command (see [AgentIcon]). */
    private fun applyAgentIcon(agent: AcpAgent) {
        if (agent.iconUrl.orEmpty() == settings.agentIconUrl && settings.agentIconSvg.isNotBlank()) return
        settings.agentIconUrl = agent.iconUrl.orEmpty()
        // The drawing is downloaded, so it cannot be part of applying the dialog: the icon simply
        // appears once it is there, and the settings say so themselves (see the bridge below).
        settings.agentIconSvg = ""
        val url = agent.iconUrl ?: return
        ApplicationManager.getApplication().executeOnPooledThread { downloadAgentIcon(url) }
    }

    /**
     * Runs off the EDT: downloads the drawing the registry ships for [agent] when that is what is
     * missing. Nothing the dialog shows is touched, so this cannot fight the user.
     */
    private fun storeAgentIcon(agent: AcpAgent) {
        val iconUrl = agent.iconUrl.orEmpty()
        val known = settings.agentIconUrl == iconUrl && (iconUrl.isBlank() || settings.agentIconSvg.isNotBlank())
        if (known) return
        val svg = iconUrl.takeIf { it.isNotBlank() }?.let { AgentIcon.download(it) }.orEmpty()
        onEdt {
            // A selection applied in the meantime wins: this describes the agent of before it.
            if (settings.agentId != agent.id) return@onEdt
            settings.agentIconUrl = iconUrl
            settings.agentIconSvg = svg
            SandboxAgentSettingsBridge.apply(project)
        }
    }

    /** Runs off the EDT: stores the drawing of [url] and tells the UI to draw it. */
    private fun downloadAgentIcon(url: String) {
        val svg = AgentIcon.download(url) ?: return
        onEdt {
            // The icon of an agent that is no longer the selected one is not what to draw.
            if (settings.agentIconUrl != url) return@onEdt
            settings.agentIconSvg = svg
            SandboxAgentSettingsBridge.apply(project)
        }
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            { if (!project.isDisposed) action() },
            ModalityState.any(),
        )
    }
}
