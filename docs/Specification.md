# Sandbox Agent Connector - Specification & Architecture Design (Specification.md)

## 1. Overview & Background

### 1.1. Problems & Background
Development leveraging AI agents faces several challenges:
* **Risk of unintended modification and destruction**: Agents may inadvertently modify or delete local files.
* **Unnecessary inspection and exposure of local environment**: Agents can potentially inspect files and environment variables without restriction under permissions equivalent to the local user.
* **Loss of GUI development environment (IDE)**: Isolation approaches such as Docker sandboxes or MicroVMs tend to confine workflows to CLI-based development, forfeiting the rich GUI benefits specific to IDEs (code navigation, debugger, Diff viewers, etc.).
* **Excessive resource consumption**: Running the entire IDE backend inside a container, such as with JetBrains Gateway or Remote Development, consumes substantial PC resources.

### 1.2. Solution (Core Concept)
"Keep the IDE running lightweight on the host, offloading and isolating only AI agent execution, file editing, and tool execution inside a MicroVM (Docker Sandboxes)."
* Provides native IDE UI (chat, Diff, context management).
* File modifications and command executions require user approval on the IDE (via inline permission cards) before execution by the agent, preventing unintended changes.
* Adopts ACP (Agent Client Protocol) as the communication protocol between the agent and plugin.

---

## 2. System Architecture

```text
┌────────────────────────────────────────────────────────────────────────┐
│ [Host OS] JetBrains IDE                                                │
│                                                                        │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │ ToolWindow UI                                                      │ │
│ │  ├─ Chat Stream / Thinking Log / Tool Cards                        │ │
│ │  ├─ Context Visualization Chips (Current File, Selection, Closeable)│ │
│ │  ├─ Inline Permission Cards (Diff Summary / Command Approval)      │ │
│ │  └─ Session History (Switch / Rename / Delete)                     │ │
│ └─────────────────────────────────┬──────────────────────────────────┘ │
│                                   │                                    │
│ ┌─────────────────────────────────┴──────────────────────────────────┐ │
│ │ Plugin Core                                                        │ │
│ │  ├─ ACP Client (JSON-RPC 2.0 over stdio) + Traffic Logger          │ │
│ │  ├─ Sandbox Process Manager (Attach Command Execution & stdio)     │ │
│ │  ├─ Session Store (History Metadata / Conversation Snapshots)      │ │
│ │  ├─ Workspace Guard (Editor Lock / VFS Refresh)                    │ │
│ │  ├─ Context Extractor (Explicit Context Collection Control)        │ │
│ │  └─ Native Diff Integrator (Full Diff View from Card / Tool Card)  │ │
│ └─────────────────────────────────┬──────────────────────────────────┘ │
└───────────────────────────────────┼────────────────────────────────────┘
                                    │ stdio (Attach Command)
┌───────────────────────────────────┼────────────────────────────────────┐
│ [MicroVM / Docker Sandboxes (sbx)]│                                    │
│                                   ▼                                    │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │ ACP Agent (Claude Code CLI / Custom Agent, etc.)                   │ │
│ ├────────────────────────────────────────────────────────────────────┤ │
│ │ Workspace Live Mount ($PROJECT_ROOT ⇔ Read-Write Mount)            │ │
│ ├────────────────────────────────────────────────────────────────────┤ │
│ │ Isolated Tool Execution (Bash, npm, pytest, git, etc.)             │ │
│ └────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Functional Requirements

### 3.1. File Synchronization & Change Application Flow
1. **Live Mount**: Directly mount the host's `$PROJECT_ROOT` to the MicroVM with Read-Write permissions, avoiding synchronization delays and merge conflicts.
2. **Sandbox-contained Writes**: The agent itself updates physical files directly within the mounted workspace from inside the sandbox. The plugin does not apply patches (it also advertises `false` for the client capability `fs.writeTextFile`).
3. **Inline Review & Approval**: When an agent sends `session/request_permission` before writing, the user reviews the Diff in the ToolWindow's inline permission card and selects an option returned as the `outcome`. The agent performs the write only after permission is granted.
4. **Local Edit Lock During Turns**: At turn start, uncommitted in-memory documents are flushed to disk (since the agent reads files from disk rather than IDE buffers), and files under the project are set to read-only within the IDE until the turn completes to prevent conflicts between local edits and agent writes. Disk permissions are not altered, so writes from the sandbox are unaffected (can be disabled via the `Make the workspace read-only in the IDE while a turn is running` setting, enabled by default).
5. **VFS Refresh**: The plugin asynchronously and recursively refreshes the paths reported by `tool_call` (`locations` / `diffs`) as well as the project root at turn completion, reflecting sandbox-modified contents in the editor. If a sandbox-side path does not exist locally, the entire project root is refreshed.

### 3.2. Sandbox Management (Docker Sandboxes / sbx)
The plugin does not manage starting or stopping the sandbox; it only attaches to and detaches from an already running sandbox.
This ensures versatility across alternative sandbox implementations and allows concurrent work outside the IDE.
* **Execution Command**: The shape is fixed and not editable as text: `<sandbox command> exec -i "<sandbox name>" <agent command>` (e.g. `sbx exec -i "MyProject" npx -y @agentclientprotocol/claude-agent-acp@0.75.1`).
  * `<sandbox command>` comes from the selected **Sandbox type** (§5.1), `<sandbox name>` from the **Sandbox name** setting (the project name when empty), and `<agent command>` is built from the ACP registry entry of the selected agent — except for a `binary` distribution, where it is the command entered in the settings and validated (see §5.1).
  * The argument vector is built directly, never through a shell, so the sandbox name is always passed as a single argument regardless of what it contains. That is also why an entered agent command may not carry shell syntax: it would not be interpreted and is refused by validation (§5.1).
  * The command line is not shown in the settings (it carries nothing to act on and an agent needing environment variables of its own makes it long), the entered command of a `binary` distribution being the exception. It is written to the IDE log every time it is built, so what was started can still be read (see §6.3).
* **Terminal Login**: `Open Sandbox Terminal` of the tool window options menu (§4.2) logs into the very same sandbox with `<sandbox command> exec -i -t "<sandbox name>" <login shell>` (e.g. `sbx exec -i -t "MyProject" /bin/bash -l`): the attach command with a TTY added and the configured **Login shell** (§5.1) in place of the agent command.
  * The command line is *typed into* a terminal tab of the IDE (bundled Terminal plugin) instead of being started as a process of its own: that is what gives the shell a real terminal, and the tab stays usable after the shell exits. It is written to the IDE log as well (see §6.3).
  * The Terminal plugin is an optional dependency. While it is switched off the entry stays disabled, and it reports the reason as a notification instead of failing.
* **Liveness Probe**: Before attaching, a lightweight, environment-independent probe (`echo sandbox-agent-connector`) is executed in place of the agent command, evaluating reachability solely by exit code (fixed internally in the plugin, no configuration required). If the exit code is non-zero, an error is displayed instead of attaching.
* **Process Control**: `GeneralCommandLine` uses the host's project root as its working directory with UTF-8 encoding. Only `stdout` is routed to the ACP parser, while `stderr` is segregated into the IDE log.
* **Detach**: Stopping only terminates the agent process launched via Exec, leaving the sandbox container itself running.

### 3.3. Agent Communication Protocol (ACP: Agent Client Protocol v1)
Complies with **ACP v1** based on JSON-RPC 2.0 to handle prompt exchanges, permission requests (tool execution / file modifications), and streaming notifications.

* **Version Negotiation**: Sends `protocolVersion: 1` and `clientInfo` (`SandboxAgentConnector` / `1.0.0`) in `initialize`. If the agent returns less than 1, the connection is treated as an error.
* **Client Capabilities**: For complete sandbox containment, `fs.readTextFile`, `fs.writeTextFile`, and `terminal` are all reported as `false`.
* **Authentication (`authenticate`)**: Assumes authentication is pre-configured when starting the sandbox; not executed by the plugin (even if `authMethods` are returned, they are only logged).
* **MCP (`mcpServers`)**: Assumes configuration is completed on the agent side and always transmits an empty array.
* **Outbound Methods (Client → Agent)**: `initialize` / `session/new` / `session/load` / `session/list` / `session/resume` / `session/delete` / `session/prompt` / `session/cancel` / `session/set_mode` / `session/set_model` / `session/set_config_option`. Availability of optional session-related methods is determined by inspecting `agentCapabilities.loadSession` and `sessionCapabilities.{list,resume,delete}`.
* **Inbound Methods (Agent → Client)**: Notification `session/update`, request `session/request_permission`.
* **Handling `session/update`**: Processes `agent_message_chunk` / `agent_thought_chunk` / `user_message_chunk` / `tool_call` / `tool_call_update` / `plan` / `current_mode_update` / `current_model_update` / `config_option_update` / `available_commands_update` / `usage_update` / `session_info_update`. Unknown variants are ignored and logged without failing decoding.
* **Unsupported Agent-Initiated Requests**: Returns JSON-RPC error `-32601 (method not found)` to prevent the agent from hanging.
* **Timeouts**: 180 seconds for `initialize`, default 120 seconds for other requests. `session/prompt` has no timeout because turns can run for a prolonged period; all pending requests fail simultaneously if the process terminates.

```text
[User Action]                [Plugin (ACP Client)]                   [Sandbox (ACP Agent)]
      │                                │                                       │
      │── 1. Attach ─────────────────▶│── 2. Liveness Check (echo) ──────────▶│
      │                                │◀── 3. Exit Code ──────────────────────│
      │                                │── 4. initialize ─────────────────────▶│
      │                                │◀── 5. protocolVersion / capabilities ─│
      │                                │── 6. session/list ───────────────────▶│
      │                                │◀── 7. sessions ───────────────────────│
      │                                │── 8. session/new (cwd, mcpServers) ──▶│
      │                                │◀── 9. sessionId / models / modes ─────│
      │── 10. Enter Prompt ──────────▶│── 11. session/prompt ────────────────▶│
      │                                │                                       │ (Thinking / Generating edits)
      │                                │◀── 12. session/update ────────────────│
      │◀── 13. Display Text/Thought/───│    (agent_message_chunk /             │
      │        Tools                   │     agent_thought_chunk / tool_call)  │
      │                                │◀── 14. session/request_permission ────│
      │◀── 15. Inline Permission Card ─│                                       │
      │── 16. Allow / Deny ──────────▶│── 17. Response (outcome) ────────────▶│
      │                                │                                       │ (File update or command exec)
      │                                │◀── 18. session/update (tool_call_update)
      │── (On Cancel) Stop ──────────▶│── 19. session/cancel (Notification) ─▶│
      │                                │◀── 20. session/prompt response        │
      │                                │        (stopReason)                   │
      │◀── 21. Completion / Results ───│                                       │
```

---

## 4. UI/UX Specifications

### 4.1. Context Attachment & Information Leakage Prevention Mechanism
To prevent sensitive information from being unintentionally transmitted to external LLMs, automatic context collection is controllable and attachment state is fully visualized in the UI.
* **Context Chip Display**:
  * Explicitly displays contexts to be sent (active file / selection / arbitrary attached files or directories) as chips above the prompt input field.
  * Each chip has an ✖ button for one-click removal from the transmission targets. If an auto-attached chip is removed, it will not be sent for that turn.
  * Chip tooltips show the target path (line numbers for selections, `(auto-attached)` for auto-attached items). No content previews are rendered.
  * Attachments are made via `Attach File` / `Attach Selection` in the prompt action bar. Files outside the project cannot be attached.
* **Disabling Automatic Attachment (Opt-in Setting)**:
  * "Auto-attachment" of active files and selected code is controlled by a single setting (`Auto-attach context`, default: off), ensuring only explicitly specified information is sent. If there is a selection, the selection is previewed as a chip first; otherwise, the active file is previewed.

### 4.2. ToolWindow (Chat & Prompt Input Interface)
* **Chat & Log View**:
  * Displays prompt history and streaming text output (`agent_message_chunk`).
  * Speakers are indicated by icons only (Agent = Plugin avatar icon, User = `AllIcons.General.User`). No text labels are displayed; tooltips provide supplementary details.
  * The transcript is drawn by one embedded browser per tool window (JCEF, off-screen rendering) fed by a JSON event protocol; only the prompt input, the toolbars, the Thinking area, the context chips and the popups stay Swing. Where `JBCefApp.isSupported()` is `false`, a plain text fallback view plus a notice is shown and permission requests cannot be answered there. The design is documented in [docs/development/TRANSCRIPT_WEBVIEW.md](development/TRANSCRIPT_WEBVIEW.md).
  * Markdown is converted to HTML on the host with `org.intellij.markdown` (GFM, provided by the IDE bundled Markdown plugin) and sanitized against a tag / attribute allow list before it reaches the document, which itself is served under a strict CSP and never navigates. Streamed text is displayed as plain text while in progress and replaced with rendered Markdown once the block is finalized. Applies to agent responses, user prompts, Thinking logs, and Plans.
  * Code fences are rendered as distinct blocks with a monospace font and background styling, lexed by the IDE lexer of the language the fence names, and carry a copy button in their bottom right corner. Every copy is confirmed by a short toast over the bottom edge.
  * A search bar lives inside the document and opens with the IDE `Find` shortcut. A floating button jumps to the newest entry while anything is below the viewport, and a finished turn offers a "scroll to the start of the turn" action anchored to its last block.
  * Agent response block headers feature a subtle `Copy` link at the right end to copy the entire response as raw Markdown source to the clipboard.
  * Colors, font stacks and sizes are handed to the document as CSS custom properties derived from the current IDE theme and from `Transcript font size` (§5). A theme change or a settings change only resends those properties, so the DOM, the scroll position and every folded entry survive.
  * Markdown task lists (`- [ ] option`) in agent responses are treated as interactive choices, rendered with actual checkboxes (multi-select supported) and a right-aligned `Send` button (disabled when no item is selected). `Send` submits the task list reflecting checked states (`- [x] option`) directly as a prompt and disables that choice block after submission. Task lists in Plans, user prompts, and Thinking logs remain read-only.
  * Agent thinking processes (`agent_thought_chunk`) are not streamed directly into the chat log; instead, the latest few lines are continuously updated in a dedicated area above the prompt input field. At the end of the turn, this area is hidden and preserved at the bottom of the log inside a collapsible section (`Thinking` anchor).
  * Tool executions (`tool_call` / `tool_call_update`) are displayed as cards, with their status (pending / running / completed / failed) updated in place on the same card. Tool calls between two conversation blocks are folded into one group, labelled `Working… 12s` while it runs and `Worked 12s` once closed (a replayed history reports no start time and only shows `Worked`).
  * Tool cards adjust their presentation according to their `kind`. `read` / `delete` / `move` are single-line displays without expansion (`Read {filename}` / `Delete {filename}` / `Move {old} → {new}`), where the filename acts as a link to open the file (and line number when available) in the editor. `fetch` is a single-line display (`Fetch {host/path}`) with the URL linking to an external browser, expanding only fetched content via a trailing caret. `search` displays `Search {pattern} · {n} results` on the collapsible anchor, leaving hit lists inside the expanded section. Other `kind`s retain standard collapsible detail anchors (trailing caret).
  * `switch_mode` is an exception to the single-line rule and is displayed as a bordered block (mode transitions alter subsequent agent behavior and should not be overlooked). The header displays `{Title}  →  {Mode ID}` in bold (Mode ID is obtained from `rawInput`; if unavailable, only the title is shown), and the plan body from `content` is permanently expanded as Markdown (raw JSON of `rawInput` / `rawOutput` is not displayed).
  * `plan` updates are displayed as task list cards, updating the same card in place.
  * Auxiliary non-conversational information (session start/restoration, notifications, turn finish reasons, errors) is displayed subtly as small, muted text without borders or titles. Only errors are highlighted with red text and a warning icon.
* **Toolbar (Single Row)**:
  * Left: Connection status (`Disconnected` / `Connecting` / `Ready` / `Error`) with transient status messages in muted text.
  * Right: `New Session` (clears the transcript and starts a new session).
  * Neither the agent icon nor the session title is repeated here: both belong to the tab this panel is shown in (Ph2 §4). The tab carries the agent (icon-only, the selected agent's drawing), because the connection is per tab (one sandbox process per session, Ph2 §2) — every tab shows its own state. Only the *missing* connection is marked (`disconnectedStatus.svg` over the drawing): a tab attaches itself when it is opened, so being connected is the normal state and clicking the icon of a tab that is not attached connects it. There is no detach action — closing the tab is what ends a session's process (Ph2 §4.5). The tab also names the session, where left-clicking it opens the session history popup and right-clicking opens the `Rename` / `Delete` menu of the session currently open in that tab.
  * Everything on a tab except its close button acts on the *active* tab only: on any other tab the same click just brings it to the front (its dropdown caret is hidden accordingly), so switching tabs can never open a popup or connect by accident. Closing works on any tab, active or not.
  * The agent icon of a tab is also where that tab reports itself while the user is looking at another one, since every tab stays live in the background (Ph2 §2):
    * **Permission requested**: `notification.svg` is layered over the drawing while the agent of that tab waits for an answer nobody has given yet, and it shares the one free corner with the disconnected mark (not attached outranks waiting). It is shown only on tabs that are not in front — the active tab shows the permission card itself — and it goes away as soon as the request is answered, auto-allowed, or lapses with the turn. A turn that merely ends is deliberately *not* notified: every tab ends its turns, so that would light the whole strip up.
    * **Turn running**: the agent icon pulses (its opacity breathes between full and 0.35 over a second) while a turn of that tab runs, so a working tab is told apart from an idle one at a glance.
  * No `Clear` button is provided. Transcripts belong to sessions and are cleared only when creating or switching sessions.
* **Prompt Action Bar (Below Input Area)**: From left: `Attach File` / `Attach Selection` / Mode / Model / Effort, followed by every other reported `configOptions` selector; on the right: Token usage / `Send`.
  * Mode / Model are populated from `modes` / `models` returned by `session/new`, presented as flat popup selectors (hidden while the agent reports none). Selection dispatches `session/set_mode` / `session/set_model`, or `session/set_config_option` if reported via `configOptions`.
  * Effort (`category = thought_level` / `id = effort`) exists as a `configOptions` selector only, so it is hidden unless the agent reports it. Every remaining option (e.g. `fast`) is drawn dynamically after the fixed slots, keyed by its `configId`, so an option the plugin knows nothing about is still offered and disappears again once it is no longer reported.
  * The `configOptions` state of `session/new` / `session/load` / `session/resume` and of the `config_option_update` notification is the **whole** state and replaces the cached one; the answer of `session/set_config_option` is **partial** (it reports `mode` / `model` only) and is merged into it, so the options missing from it keep their selector.
  * Token usage is shown only when `usage_update` is received, formatted as a read-only icon with breakdown details on hover.
* **Options Menu (Tool Window Header)**: The `Options` menu of the tool window (the rotated ellipsis at the left end of the header, next to platform entries such as `Show Toolbar`) carries two entries of the plugin, added through `ToolWindowEx.setAdditionalGearActions`:
  * `Open Sandbox Terminal` (`icons/console.svg`) — logs into the sandbox with the configured login shell (see §3.2).
  * `Settings` (`icons/settings.svg`) — opens `Settings > Tools > Sandbox Agent Connector` directly.
  * Both are registered as regular actions (`com.tsuty.sandboxagent.actions.OpenSandboxTerminal` / `...OpenSandboxAgentSettings`), so they are reachable through Find Action and can be bound to a shortcut in Keymap.
* **Interruption**: During turn execution, the `Send` button switches to `Stop`, sending `session/cancel` upon click (not a separate button).
* **Prompt Input Area (Key UX Requirements)**:
  * **Dynamic Height Adjustment**:
    * **Splitter Component (`JBSplitter`)**: Users can manually adjust and expand the area height by dragging the divider between the chat log and input area.
    * **Auto-Grow**: Automatically expands vertically from a minimum of ~2 lines to a maximum of ~8 lines based on input length, transitioning to internal scrolling beyond the limit.
  * **Input Component**: Uses `EditorTextField`, supporting syntax highlighting, code completion, and native JetBrains shortcuts.
  * **Send / Newline Keymaps**:
    * No custom plugin settings; shortcuts are configured via standard IDE `Settings > Keymap` actions (`com.tsuty.sandboxagent.actions.SendPrompt` = `Send Prompt` / default `Enter`, `com.tsuty.sandboxagent.actions.InsertNewline` = `Insert Newline in Prompt` / default `Shift+Enter`). Users desiring Cmd/Ctrl+Enter submission can swap assignments in Keymap.
    * The `Send` button tooltip displays the current keybinding and tracks Keymap changes.
    * An `InputMethodListener` monitors IME composition states to prevent accidental prompt submission when pressing Enter to confirm IME conversions (e.g., Japanese input).
  * **Slash Commands**: Typing `/` at the start of a line opens a completion popup for commands reported by `available_commands_update`.
* **Inline Permission Card**:
  * **File Changes (Diff)**:
    * Displays modified file name and line changes (+15 -3) (generated from `ToolCallContent` `diff` or `oldText` / `newText`) on a single line.
    * No diff is expanded inside the chat. Clicking the line opens JetBrains' native full diff viewer (`DiffManager`), with the full path available as a tooltip.
  * **Command Execution**:
    * Extracts and displays the command to be executed (e.g., `npm test`) from `rawInput`.
    * Execution logs are displayed inside the collapsible detail area of the corresponding tool card.
  * **Mode Switch (`switch_mode`)**:
    * Because `options` represent candidate target modes rather than allow/reject choices, the header is formatted as `Switch mode: {Title}  →  {Mode ID}`, with the plan body rendered directly as Markdown without collapse anchors.
    * The outcome is notified via `session/update` with `current_mode_update`. Since the ACP specification refers to `modeId` while the schema refers to `currentModeId`, both property names are accepted.
    * Tool call `content` may be sent as raw `{"type":"text"}` without wrapping in `{"type":"content"}`; this is also supported as a content block.
    * A test mock agent is available under [tools/fake-acp-agent](../tools/fake-acp-agent/README.md).
  * **Options**: Buttons are generated directly from `options` in `session/request_permission` (`allow_once` / `allow_always` / `reject_once` / `reject_always`), returning the selection as `outcome` (no custom options are invented client-side). Buttons are disabled after response, leaving the chosen outcome displayed on the card. If the turn is cancelled, `cancelled` is returned.

> Note: Auto-approval per tool kind (Auto-Allow) is not implemented yet. Approval always requires explicit user action. To avoid repetitive confirmations, select `allow_always` provided by the agent.

### 4.3. Session Management (History, Restoration, and Persistence)
Sessions are managed across two layers: the agent-side context and the IDE-side visual state (transcript). Even if the agent does not retain history, the IDE visual history can still be restored.

* **Session Listing**: For agents supporting `sessionCapabilities.list`, the session list is retrieved via `session/list` and shown in a history popup when clicking the session title on a tab. The selected session is restored into that very tab (each tab owns its own connection, Ph2 §2). Entries are ordered by last update, newest first, and the popup is at most 20 rows tall and at most as wide as the plugin panel; older sessions are reached by scrolling rather than being dropped from the list, and titles that do not fit the width are elided with the full one kept as the row tooltip.
* **Restoration Strategy**: Determined by combining agent capabilities and local snapshot freshness.

  | Agent Capability | Local Snapshot | Behavior |
  | :--- | :--- | :--- |
  | Supports `resume` | Fresh | Renders snapshot and reconnects context via `session/resume` |
  | Supports `load` only | Fresh | Renders snapshot and reconnects via `session/load` with replay suppressed |
  | Supports `load` / `resume` | Stale or None | Renders live replay from `session/load` and saves a fresh snapshot upon completion |
  | Unsupported | Present | Renders snapshot only (visual restoration; notifies user that agent has no context) |
  | Unsupported | None | Rejects selection and retains current session |

* **Transcript Snapshots**: Persists visual state (messages with merged chunks, finalized tool calls, Plans, notifications) as JSON at turn boundaries. Stored under the IDE's per-project cache directory at `sandbox-agent-sessions/{sessionId}.json` (outside the project tree, not tracked in VCS). Writes use atomic moves via temporary files to avoid corrupted snapshots upon interruption.
* **Schema Version**: Snapshots include `schemaVersion` (currently 1). Versions without a migration path are discarded as "no local history" (preventing load failures from breaking connections).
* **Freshness Check**: Snapshots record save timestamps and the agent's reported last updated timestamp at that time, comparing them against `session/list` updated timestamps to decide if replay is necessary. If the agent does not report timestamps, the snapshot is used.
* **Rename / Delete**: Since ACP lacks `session/rename`, renames are stored in `workspace.xml` as IDE-side title overrides, taking precedence over titles reported by the agent. Deletion issues `session/delete` (when supported; continues even if it fails) and always discards local snapshots and metadata.
* **Discarding Unused Sessions**: Sessions that have never received a prompt are discarded when switching to another session, preventing empty sessions from accumulating on the agent.

---

## 5. Plugin Settings

Configuration items located under `Preferences / Settings > Tools > Sandbox Agent Connector`:

| Setting Item (Property) | Type | Default Value | Description |
| :--- | :--- | :--- | :--- |
| **Sandbox type**<br>(`sandboxTypeId`) | Choice | `Docker sandboxes` (`sbx`) | Sandbox CLI the agent is attached through. Only the four types of §5.1 are offered, because all of them spell `exec -i <name> <command>` the same way; the command line itself is not editable |
| **Sandbox name**<br>(`sandboxName`) | String | `` (empty) | Name of the running sandbox passed to `exec -i`. Empty uses the project name. Free-form on purpose: it is a single argument and is never passed through a shell |
| **Login shell**<br>(`loginShellId`) | Choice | `bash (/bin/bash)` (`/bin/bash -l`) | Shell `Open Sandbox Terminal` logs into, run through `exec -i -t` in place of the agent command. Only the four shells of §5.1 are offered; the command around them is not editable |
| **ACP Agent**<br>(`agentId` / `agentPackage` / `agentLauncher` / `agentArgs` / `agentVersion` / `agentDescription` / `agentCommand`) | Choice + stored distribution | `Claude Agent` / `npx -y @agentclientprotocol/claude-agent-acp` | ACP adapter started inside the sandbox. The entry is picked from the ACP registry and its distribution is stored field by field; for `npx` / `uvx` the command is derived from it and never typed. `agentCommand` is used by `binary` entries only, where it *is* the command and is entered by hand (see §5.1). Hovering an entry shows its registry `description` |
| *(not shown)*<br>(`agentName` / `agentIconUrl` / `agentIconSvg`) | String | `Claude Agent` / `` (empty) | The `name` and the `icon` of the selected registry entry, stored when it is picked so the registry is not fetched on every start. The name heads the agent blocks of the transcript and the drawing is used for them and for the connection toggle; empty falls back to `Agent` and the bundled icons (see `docs/development/ICONS.md`) |
| **Agent startup idle timeout (s)**<br>(`initializeIdleTimeoutSeconds`) | Int (10..1800) | `180` | Silence, in seconds, that gives up on a starting agent (`initialize`). Not a deadline: it is measured from the last output the agent produced on either `stdout` or `stderr` (see §6.2) |
| **Remote workspace path (CWD)**<br>(`remoteWorkspacePath`) | String | `` (empty) | Working directory inside the sandbox passed to `session/new`. Defaults to `project.basePath` when empty |
| **Restore the previous session when the agent supports session/load**<br>(`restoreLastSession`) | Boolean | `false` | Whether to automatically restore previous session. **Currently only persisted and not referenced by connection logic, with no operational effect** (restoration is performed explicitly via session history; see §4.3) |
| **Auto-attach context (active file or selection)**<br>(`autoAttachContext`) | Boolean | `false` | Whether to automatically attach active file or selection (unified setting; selection takes precedence if present) |
| **Make the workspace read-only in the IDE while a turn is running**<br>(`lockEditorsDuringTurn`) | Boolean | `true` | Whether to lock workspace files in the IDE as read-only during turns to prevent editing conflicts (see §3.1) |
| **Transcript font size**<br>(`transcriptFontSize`) | Int (8..32) | `13` | Base font size of the transcript, in pixels. The only setting the look of the transcript has: every other size is derived from it (code blocks read at the base size, the quiet rows and tool call lines at 0.85 of it). Applying the settings takes effect at once without rebuilding the DOM, so the scroll position and every folded entry survive |

Settings are maintained on a per-project basis (`projectConfigurable` / `sandboxAgentConnector.xml`), allowing different sandbox configurations across projects.

The following are not managed in plugin settings:
* **Send Keybinding**: Configured via IDE standard `Settings > Keymap` for `Send Prompt` (`com.tsuty.sandboxagent.actions.SendPrompt`, default `Enter`) / `Insert Newline in Prompt` (`com.tsuty.sandboxagent.actions.InsertNewline`, default `Shift+Enter`).
* **Auto-Approval (Auto-Allow)**: Not implemented (see §4.2).

### 5.1. Sandbox Type, Login Shell and Agent Command

The attach command can never be written by hand, and the agent command only where nothing else
can know it (a `binary` distribution, see below): everything else is assembled from the settings,
which is what makes the behaviour of a connection predictable.

* **Sandbox type** options (attaching to an already running sandbox), each contributing only the name of its CLI:
  * `Docker sandboxes` (default) — `sbx`
  * `Docker` — `docker`
  * `Apple container` — `container`
  * `WSL Containers` — `wslc`
* **Login shell** options (used by the terminal login of §3.2), each contributing only the command that starts it:
  * `bash (/bin/bash)` (default) — `/bin/bash -l`
  * `zsh (/bin/zsh)` — `/bin/zsh -l`
  * `ash (/bin/ash)` — `/bin/ash -l`
  * `sh (/bin/sh)` — `/bin/sh` (the POSIX shell of a minimal image does not know `-l`)
* **ACP Agent** options are generated from the [ACP registry](https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) (fetched in the background when opening settings, cached in memory during IDE runtime):
  * Lists every agent of `agents[]` in registry.json order that names a distribution the plugin can start: `npx`, `uvx` or `binary`. When an entry offers several, `npx` wins over `uvx` and both win over `binary`, because their command needs nothing to be entered.
  * `npx` / `uvx` commands are constructed from `package` and `args`. `-y` is appended to `npx` (`uvx` has no interactive prompts prior to execution, so no equivalent flag is needed).
  * `binary` entries are started with a command **entered by hand** (`agentCommand`), shown as a field under the drop down and only for them. The plugin installs nothing into the sandbox, so the executable has to be there already and only its path inside the sandbox says where; of the `agents[].distribution.binary.<os>-<arch>` objects, only `args` and `env` are read (as hints) — `archive`, `cmd` and `sha256` are not, since nothing is downloaded, unpacked or verified. The `<os>-<arch>` key describes the machine the agent runs on, which is the sandbox and not the IDE, so it cannot be picked by looking at the host.
  * The entered command is validated before it is stored: it must be a single non-empty line of at most 1000 characters, must name an executable, must close every quote it opens, and must not contain `|`, `&`, `;`, `<`, `>`, `(`, `)`, `$` or a backtick. This is not a safety boundary — the command is entered deliberately and `sh -c` walks around any rule — but a statement of what cannot work: the agent is started as an argument vector handed to the OS, never through a shell, so those characters would reach the agent as literal text (see §3.2).
  * A failure to start is surfaced as it comes back from the sandbox, which is what points at the command that has to be corrected.
  * Hovering an entry of the drop down shows the `description` of its registry entry as a tooltip. Apart from the command of a `binary` entry, neither the agent command nor the assembled attach command line is displayed: only the entry, its version and the tooltip are (see §3.2).
  * The distribution of the picked entry is stored with its `version`, and a registry package usually pins that version. The dialog therefore shows the stored version and, when the registry ships a newer one, `Version <current> · <latest> available` next to an `Update` button; only pressing it replaces the stored distribution, so a new registry never changes what starts on its own.
  * A `binary` entry is offered **no** `Update` button and no difference is announced for it: the entered command pins no version and can carry one in its path, so the version shown for it is simply the one the registry ships at that moment.
  * `env` of a registry entry (`npx` / `uvx` package or `binary` platform object) is shown as `Expects in the sandbox: <KEY>=<VALUE>` and is neither passed nor persisted. The plugin hands no environment to the sandbox at all, so credentials and everything else the agent needs (`ANTHROPIC_API_KEY` and the like, which the registry does not list) have to be set inside the sandbox beforehand.
  * Default selection is `claude-acp`. If the registry is unreachable, only that agent is shown, with its package unpinned.

---

## 6. Technical Stack & Implementation Notes

### 6.1. Technologies & APIs (JetBrains SDK)
* **Language**: Kotlin 2.4.x (JVM Toolchain 21)
* **Build Tool**: Gradle (`org.jetbrains.intellij.platform` plugin 2.x)
* **Target Platform**: IntelliJ IDEA Community 2024.2.5 baseline with `sinceBuild = 242`, no `untilBuild`. Verified against IC 2024.2.5 to 2025.2.6 using Plugin Verifier.
* **Dependencies**: `kotlinx-serialization-json` (JSON-RPC / ACP serialization, and the host ⇔ web transcript protocol), bundled plugin `org.intellij.plugins.markdown` (for `org.intellij.markdown` parser), bundled plugin `org.jetbrains.plugins.terminal` as an **optional** dependency (terminal login, see §3.2). `kotlinx-coroutines` utilizes the platform-bundled version without individual dependency declarations. The transcript itself needs no library: its HTML, CSS and JavaScript are bundled resources under `resources/transcript/`.
* **Key JetBrains SDK Components**:
  * `ToolWindowFactory` / `ToolWindow`: Chat and control panel UI construction
  * `JBSplitter`: Resizable divider for chat log and prompt input area
  * `JBCefApp` / `JBCefBrowser` / `JBCefJSQuery`: Embedded browser rendering the transcript and the bridge back to the host (see §4.2)
  * `EditorTextField`: Extensible prompt input area
  * `DiffManager` / `DiffContentFactory` / `SimpleDiffRequest`: Invocation of native Diff viewers
  * `GeneralCommandLine` / `OSProcessHandler`: Exec process management and stdio stream control
  * `Editor` / `SelectionModel`: Safe extraction of context (selection, active file)
  * `VfsUtil.markDirtyAndRefresh` / `LocalFileSystem`: Asynchronous recursive refresh of sandbox-modified files
  * `WritingAccessProvider`: Workspace read-only locking during turns
  * `PersistentStateComponent`: Per-project settings and session metadata persistence

### 6.2. Runtime & Implementation Considerations (Docker Sandboxes / npx)
* **Initial Launch Timeout**: Because initial runs trigger package downloads via `npx`, which may take minutes on a slow line, waiting for the `initialize` response has no deadline at all: it ends on an **idle timeout** instead (default 180 seconds, configurable as `initializeIdleTimeoutSeconds`, see §5). Any output on `stdout` or `stderr` resets the measurement, so a download that keeps progressing is never cut off and only a truly unresponsive agent fails (regular requests keep their 120 second deadline).
* **Startup Progress**: While waiting, the connection message is refreshed every second as `Negotiating ACP… {elapsed}s · {last stderr line}`. The elapsed time alone proves the agent is alive, and an `npx` download line reads as real progress. A timeout error carries what to do about it (retry, pre-install the agent in the VM, raise the timeout) plus the last few `stderr` lines.
* **Stream Segregation (`stdout` / `stderr`)**: `npx` progress logs and warnings are emitted to `stderr`. When listening via `OSProcessHandler`, only `ProcessOutputType.STDOUT` is routed to the ACP protocol parser, while `ProcessOutputType.STDERR` is segregated into plugin debug logs and into the progress message above, preventing JSON-RPC parse errors. Lines meant for display have their ANSI escapes stripped, and a progress line rewritten with `\r` counts as one line.
* **Auto-confirmation Flag (`-y`)**: To prevent stdio communication from hanging on interactive prompts when `npx` packages are uncached, the `-y` flag is mandatory.
* **Pre-guidance for Credentials (Secrets)**: Since agents inside the sandbox communicate with Anthropic APIs, detecting missing API key errors prompts the user with setup instructions such as `sbx secret set anthropic`.
* **Resilience to Schema Variations**: To accommodate discrepancies across agent implementations, capability flags accept both `true` and `{}` as truthy, and timestamps accept epoch milliseconds, epoch seconds, and ISO-8601 strings, normalizing them to milliseconds. Unknown content blocks and `session/update` variants degrade gracefully as unknown elements without failing decoding.

### 6.3. Logging & Auditing
* **Attach Command Log**: The attach command line (`<sandbox command> exec -i "<sandbox name>" <agent command>`) is written to the IDE log whenever it is built, for the liveness probe as well as for the agent, since the settings do not display it. The terminal login command (`... exec -i -t ... <login shell>`) is logged the same way.
* **ACP Traffic Log**: Records all JSON-RPC lines exchanged with agents (serving both debugging and audit trail purposes). Format: `{millisecond-timestamp} {OUT|IN} {raw JSON}` per line.
* **Output Destination**: Located under the IDE log directory at `SandboxAgentConnector/{connection_timestamp}_{sessionId}.log`. Separate log files are kept per session; lines prior to `sessionId` establishment (such as `initialize`) are buffered in memory and flushed upon file creation.
* **Handling Failures**: Log writing failures are recorded as warnings in the IDE log without interrupting the session.
