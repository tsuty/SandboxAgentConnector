# AGENT.md

This file provides guidance for AI coding agents working in this repository.

## Project Overview

**Sandbox Agent Connector** is a plugin for JetBrains IDEs (IntelliJ IDEA, etc.) designed to interact with and
collaboratively develop alongside AI agents running in isolated sandbox environments (Docker Sandboxes / MicroVM,
etc.).

- While leveraging the IDE's lightweight nature and rich GUI (editor, Diff viewers, debugger, etc.), it isolates and
  offloads only AI agent execution, file modifications, and tool operations to the sandbox.
- Communication with the agent is handled via [ACP (Agent Client Protocol)](https://agentclientprotocol.com/)
  (JSON-RPC 2.0 based).

For detailed specifications and architecture, see [docs/Specification.md](docs/Specification.md).

## Prerequisites / Environment

- **Target IDEs**: JetBrains IDE 2025.3 (Build 253) or later, with the bundled Markdown plugin enabled.
  - The plugin depends on `com.intellij.modules.platform` and `org.intellij.plugins.markdown`, so it cannot be
    enabled in IDEs where the Markdown plugin is missing or disabled.
  - `org.jetbrains.plugins.terminal` is an optional dependency, used by `Open Sandbox Terminal`.
  - The transcript needs JCEF (`ide.browser.jcef.enabled`). Where JCEF is unavailable, a plain text fallback view is
    shown.
- **JDK**: JDK 21 is required for building (`kotlin { jvmToolchain(21) }`). The JVM running Gradle itself only needs
  17 or higher.
- **Sandbox Environment**: Docker Sandboxes (`sbx`), Docker, Apple Container, WSL Containers, etc.

## Build, Test & Run Commands

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

- Test framework is JUnit Platform (`tasks.test { useJUnitPlatform() }`).
- After a successful build, `build/distributions/SandboxAgentConnector-<version>.zip` is generated.

## Repository Structure

```
src/main/kotlin/com/tsuty/sandboxagent/
├── ui/            # Tool window, transcript rendering, and other UI layers
│   └── transcript/  # JCEF based transcript web view
├── settings/      # Settings > Tools > Sandbox Agent Connector
├── vfs/           # Virtual file system related code (file change sync, etc.)
├── actions/       # IDE actions (menus, toolbars, etc.)
├── service/       # Project-level services
├── acp/           # ACP (Agent Client Protocol) client implementation
└── session/       # Agent session management

src/main/resources/
├── META-INF/      # plugin.xml, etc.
├── icons/         # SVG icons (see docs/development/ICONS.md)
└── transcript/    # HTML/JS/CSS for the transcript view (loaded into JCEF)

src/test/kotlin/com/tsuty/sandboxagent/   # Unit tests, mirroring the packages above

docs/
├── Specification.md                       # Specification & architecture design
└── development/
    ├── LOCAL_INSTALL.md                    # Build from source & installation guide
    ├── TRANSCRIPT_WEBVIEW.md               # JCEF transcript design (host <-> web protocol, security)
    └── ICONS.md                            # Icon inventory & drawing conventions
```

## Coding Conventions

- The language is Kotlin. Follow the Java version specified in `kotlin { jvmToolchain(...) }`.
- `kotlinx-coroutines` is bundled with the IntelliJ Platform, so do not add it as a dependency.
- Match the existing code style (indentation, naming conventions, import order, comment language and frequency).
- When changing host <-> web communication in the JCEF transcript, check the security boundary description in
  [docs/development/TRANSCRIPT_WEBVIEW.md](docs/development/TRANSCRIPT_WEBVIEW.md).
- When adding or changing icons, follow the drawing rules and sprite generation process described in
  [docs/development/ICONS.md](docs/development/ICONS.md).

## Documentation Updates

- If you make changes that affect `README.md` or this `AGENT.md` (build steps, dependencies, directory structure,
  etc.), update the relevant sections accordingly.

## Other Notes

- Do not delete the `.junie/plans` directory or any files inside it.
- The `tmp/` directory is for temporary working files; do not make permanent changes there.
