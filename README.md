# Sandbox Agent Connector

**Sandbox Agent Connector** is a plugin for JetBrains IDEs (IntelliJ IDEA, etc.) designed to interact with and collaboratively develop alongside AI agents running in isolated sandbox environments (Docker Sandboxes / MicroVM, etc.).

While leveraging the IDE's lightweight nature and rich GUI (editor, Diff viewers, debugger, etc.), it isolates and offloads only AI agent execution, file modifications, and tool operations to the sandbox. Communication with the agent is handled via [ACP (Agent Client Protocol)](https://agentclientprotocol.com/).

---

## Key Features

- **Secure Sandbox Isolation**: File read/write operations and command executions by the agent are entirely confined within the sandbox, preventing unintended destruction of the local development environment or exposure of sensitive information.
- **IDE-Native UI**: Provides an intuitive user experience with in-tool-window chat streaming, thinking log display, tool execution cards, and context attachment chips. The transcript is rendered by an embedded browser (JCEF), so Markdown, tables and code blocks look like the IDE Markdown preview.
- **Inline Approval**: Preview file changes (compact Diff / full Diff) and pre-verify command executions, executing them only after explicit user approval.
- **Local Session Protection**: Transcripts are kept locally and are never lost to the agent. A session with local history is drawn from it right away, and the history the agent replays in the background is compared with it by the ids of the agent messages (ACP `messageId`): only when they differ is the local transcript saved as a backup before the agent's version replaces it (backups are never deleted; the list shows the 20 newest). Deleting a session moves its transcript and its backups aside instead of erasing them. The session list shows at a glance whether a session is stored locally and has backups, and a backup or a deleted session opens in a **Read Only tab** that never connects to the sandbox.
- **Local Session Search**: `Search Local Sessions` in the tool window options menu searches the full text of the locally stored sessions and their backups, deleted sessions included on request. The matching part is previewed as pretty printed JSON with the match highlighted, and a result reopens the session or opens it in a Read Only tab.
- **ACP (Agent Client Protocol) Compliant**: Complies with JSON-RPC 2.0-based ACP v1, enabling connections to ACP-compatible agents such as Claude Code.

---

## Prerequisites

- **Target IDEs**: JetBrains IDE 2024.2 (Build 242) or later, with the bundled Markdown plugin enabled (IntelliJ IDEA, GoLand, WebStorm, PyCharm, etc.)
  - The plugin depends on `com.intellij.modules.platform` and `org.intellij.plugins.markdown`, so it cannot be enabled in IDEs where the Markdown plugin is missing or disabled. The bundled Terminal plugin (`org.jetbrains.plugins.terminal`) is an optional dependency used by `Open Sandbox Terminal`.
  - The transcript needs JCEF (a JetBrains Runtime with the embedded browser, `ide.browser.jcef.enabled`). Where JCEF is unavailable, a plain text fallback view is shown and permission requests cannot be answered there.
- **JDK**: JDK 21 for building (`kotlin { jvmToolchain(21) }`; Gradle must be able to resolve a JDK 21 toolchain, while the JVM running Gradle itself only needs 17 or higher)
- **Sandbox Environment**: Docker Sandboxes (`sbx`), Docker, Apple Container, WSL Containers, etc.

---

## Quick Start

### 1. Installing the Plugin
Build the plugin from the repository and install it into your IDE.

```bash
# Build the plugin
./gradlew buildPlugin
```

After the build completes, `build/distributions/SandboxAgentConnector-<version>.zip` will be generated.
Install it by selecting the ZIP file from `Settings > Plugins > ⚙ > Install Plugin from Disk...` in the IDE, then restart the IDE.

For detailed instructions, refer to the [Local Installation Guide (docs/development/LOCAL_INSTALL.md)](docs/development/LOCAL_INSTALL.md).

### 2. Preparing the Sandbox
Start a sandbox container with the project root mounted (e.g., Docker Sandboxes `sbx`).

### 3. Connecting to the Agent and Usage
1. Open the **Sandbox Agent** tool window from the right sidebar of the IDE.
2. Click the connect icon on the toolbar to attach to the agent.
3. Send instructions from the prompt input field to begin conversations or code modifications.

Settings can be reviewed and configured under `Settings > Tools > Sandbox Agent Connector`.

---

## Development & Build

```bash
# Run tests
./gradlew test

# Build the plugin
./gradlew buildPlugin

# Launch development sandbox IDE
./gradlew runIde

# Verify plugin configuration and binary compatibility
./gradlew verifyPlugin
```

---

## Documentation

For more detailed specifications and design information, refer to the following documents:

- **[Specification & Architecture Design (docs/Specification.md)](docs/Specification.md)**:
  Architecture diagrams, file synchronization flow, ACP protocol specifications, UI/UX details, configuration settings, etc.
- **[Local Installation Guide (docs/development/LOCAL_INSTALL.md)](docs/development/LOCAL_INSTALL.md)**:
  Build from source instructions, IDE installation steps, verification, and troubleshooting.
- **[Transcript Web View (docs/development/TRANSCRIPT_WEBVIEW.md)](docs/development/TRANSCRIPT_WEBVIEW.md)**:
  Design note of the JCEF based transcript: the host ⇔ web event protocol, security barriers, and the plain text fallback.
- **[Icons (docs/development/ICONS.md)](docs/development/ICONS.md)**:
  Icon inventory, drawing rules and how the transcript icon sprite is built.


---

## License

This project is licensed under the [Apache License 2.0](LICENSE).
