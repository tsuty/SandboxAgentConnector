# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Sandbox Agent Connector** is a JetBrains IDE plugin (Kotlin) that lets a developer pair-program with an
AI coding agent running inside an isolated sandbox (Docker Sandboxes `sbx`, Docker, Apple Container, WSL
Containers). The IDE stays lightweight on the host; only agent execution, file edits and command
execution are offloaded to the sandbox. The plugin talks to the agent over stdio using
[ACP (Agent Client Protocol) v1](https://agentclientprotocol.com/), a JSON-RPC 2.0 based protocol.

The plugin never starts/stops the sandbox itself — it only attaches to (and detaches from) an already
running one via `<sandbox command> exec -i "<sandbox name>" <agent command>`.

For full architecture and protocol details, see `docs/Specification.md` (long, authoritative — read it
before touching ACP message handling, session restoration, or settings). Also useful:
`docs/development/TRANSCRIPT_WEBVIEW.md` (JCEF host↔web protocol and security boundary) and
`docs/development/ICONS.md` (icon inventory/drawing conventions).

## Build, Test & Run Commands

```bash
./gradlew test                       # run all unit tests (JUnit Platform)
./gradlew test --tests "com.tsuty.sandboxagent.acp.AcpClientTest"          # single test class
./gradlew test --tests "com.tsuty.sandboxagent.acp.AcpClientTest.someTest" # single test method
./gradlew buildPlugin                # build the plugin zip -> build/distributions/SandboxAgentConnector-<version>.zip
./gradlew runIde                     # launch a sandbox IDE with the plugin installed, for manual testing
./gradlew verifyPlugin               # plugin configuration + binary compatibility check (IU 2025.3.6.1, IU 2026.2.3)
```

- JDK 21 is required to build (`kotlin { jvmToolchain(21) }`); the Gradle daemon JVM itself only needs 17+.
- `kotlinx-coroutines` is bundled by the IntelliJ Platform — never add it as a Gradle dependency.
- There is a hand-written fake ACP agent at `tools/fake-acp-agent/switch_mode_agent.py` for exercising
  `switch_mode` permission flows without a real agent (see its README for how to point a connection at it).
- Raising the version: follow `docs/development/RELEASE.md` — `pluginVersion` in `gradle.properties` and a new
  `<change-notes>` section in `plugin.xml` go together in one commit, before the `v<version>` tag is pushed.

## Architecture

Package root: `com.tsuty.sandboxagent`, under `src/main/kotlin/...` (tests mirror this under
`src/test/kotlin/...`).

- **`acp/`** — The ACP client itself: `AcpClient` (JSON-RPC 2.0 over stdio, request/response + notification
  dispatch, timeouts), `AcpModels` (protocol data classes, tolerant of schema variance — capability flags
  accept `true` or `{}`, timestamps accept ms/s/ISO-8601), `SandboxProcessManager` (builds and launches the
  `exec -i "<name>" <command>` argument vector — never through a shell), `AcpTrafficLogger` (per-session
  JSON-RPC audit log under the IDE log dir), `AgentStderrTail` (keeps last stderr lines for
  timeout/error messages).
- **`session/`** — Session lifecycle independent of any single connection: `SessionMetadataStore`
  (history: list/rename/delete), `SessionSnapshotStore` (JSON transcript snapshots at turn boundaries,
  under the IDE per-project cache dir, atomic writes, `schemaVersion`), `SessionTranscriptRecorder`
  (builds snapshots from live transcript state), `SessionLockRegistry` (prevents the same session being
  opened twice), `SandboxAgentStartupActivity` / `SandboxAgentProjectCloseListener` (clear stale
  duplicate-open locks on project open/close so a crash never wedges the next launch).
- **`service/`** — `SandboxAgentService`, the project-level service that is the main entry point tying
  connection lifecycle, sessions and settings together.
- **`settings/`** — Persistent plugin settings (`SandboxAgentSettingsState`, `PersistentStateComponent`,
  per-project; a list of enabled `AgentEntry`s with one default, sharing one sandbox), the settings UI
  (`SandboxAgentSettingsConfigurable`, whose agent list is `AgentListPanel` over `AgentListEdits`), the ACP agent registry fetched from
  the [ACP registry](https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json)
  (`AcpAgentRegistry`, `AcpAgent`), and validation for hand-entered `binary` agent commands
  (`AgentCommandValidator` — rejects shell metacharacters since commands are never run through a shell).
- **`vfs/`** — `AgentWorkspaceGuard` + `AgentWritingAccessProvider` implement the read-only workspace lock
  while a turn runs (in-IDE only; disk permissions and the sandbox's own writes are unaffected).
- **`ui/`** — Swing tool window shell: `SandboxAgentToolWindowFactory`, `SandboxAgentMainPanel`,
  tabs (`SandboxAgentTabsPanel`/`TabsController` — one sandbox connection and one fixed agent per tab;
  `RecentSessionsPanel` is shown while no tab is open), prompt input
  (`PromptInputPanel`, context chips, slash command popup, session history popup), diff support
  (`UnifiedDiffSupport`), and `SandboxTerminal` (types the same attach command with `-t` into a bundled
  Terminal plugin tab; degrades to a notification when that optional dependency is disabled).
- **`ui/transcript/`** — The chat/thinking/tool-call log is *not* Swing: it's a single JCEF
  (`JBCefBrowser`) instance per tool window rendering bundled HTML/CSS/JS
  (`src/main/resources/transcript/`) fed by a JSON event protocol (`TranscriptEvent`,
  `TranscriptDocument` builds/diffs state, `TranscriptHtml` sanitizes Markdown-derived HTML against an
  allow list under a strict CSP). `PlainTextTranscriptView` is the fallback when
  `JBCefApp.isSupported()` is false (permission requests can't be answered there). Read
  `docs/development/TRANSCRIPT_WEBVIEW.md` before changing anything in the host↔web boundary.
- **`actions/`** — Registered IDE actions (`SendPromptAction`/`InsertNewlineAction` bound to
  Enter/Shift+Enter via Keymap, terminal/settings entries in the tool window's Options gear menu).

### Key architectural invariants worth knowing before changing behavior

- The plugin advertises `fs.readTextFile`, `fs.writeTextFile` and `terminal` client capabilities as all
  `false`, and never sends real environment/secrets to the sandbox — full isolation is the point.
- The attach/agent command is always built as an argument vector and executed directly, never through a
  shell; this is why `AgentCommandValidator` rejects shell metacharacters in hand-entered `binary` agent
  commands, and why the sandbox name is always passed as a single argument.
- `session/prompt` has no timeout (turns can run indefinitely); most other ACP requests default to 120s;
  `initialize` uses a 180s *idle* timeout (reset by any stdout/stderr output) rather than a hard deadline,
  because first-run `npx` downloads can take minutes.
- Each tool window tab owns one sandbox process/connection; there is no "detach" action — closing the tab
  ends the session's process.
- Session snapshots name their agent (`agentId`, optional, added without a `schemaVersion` bump); files
  without one belong to `legacyAgentId`. A session id whose local files belong to another agent is an
  error of the tab — another agent's snapshot is never overwritten.
