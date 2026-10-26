# Session Capabilities of the ACP Registry Agents

How far the plugin can manage the sessions of each agent of the
[ACP registry](https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) depends on
the session capabilities the agent declares in its `initialize` answer. This document records
what every registry agent declared, and what that means for the plugin.

The capabilities are what the agents **declare**, nothing more: only `initialize` was sent, no
session was created. Whether `session/list` gives titles and update times, whether a replay
carries `messageId`s, and so on, is not covered here (see §6).

---

## 1. Conditions

| Item | Value |
|---|---|
| Date | 2026-10-26 |
| Registry | `registry/v1/latest`, 43 agents |
| Environment | Docker Desktop on an Apple silicon Mac (`linux-aarch64`), image `node:22-trixie-slim` |
| Tool | [`tools/agent-capability-probe`](../../tools/agent-capability-probe/README.md) (`commands` → `install` → `probe`) |
| Request | The very `initialize` the plugin sends: protocol version 1, no `fs` / `terminal` client capability |
| Credentials | None. No agent was logged in |

Commands are spelled as the plugin spells them: `npx -y <package> <args>`, `uvx <package> <args>`,
and, for an agent shipped as a `binary` only, the executable unpacked from the registry's archive.
The `env` the registry entries ask for (auto updates off, `FAST_AGENT_MODEL` for fast-agent) was
set, as a sandbox set up for the plugin would have it.

To repeat the survey, run the tool again (see its README); the agents move fast, and this
document is a snapshot of the date above.

---

## 2. Results

A capability counts as declared when it is `true` or an object (`{}`), as
`FlexibleBooleanSerializer` reads it. `loadSession` is `agentCapabilities.loadSession`; the
other flags are `agentCapabilities.sessionCapabilities.*`. `authMethods` is the number of
authentication methods offered.

| Agent | Version | Launcher | `loadSession` | `list` | `resume` | `delete` | `close` | `fork` | `additionalDirectories` | Other `sessionCapabilities` | `authMethods` |
|---|---|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|---|:-:|
| agoragentic-acp | 1.3.0 | npx | - | - | - | - | - | - | - |  | 1 |
| amp-acp | 0.10.0 | binary | ✓ | - | ✓ | - | - | - | - |  | 1 |
| antigravity-acp | 1.4.3 | binary | ✓ | ✓ | ✓ | - | - | - | - |  | 4 |
| auggie | 0.36.0 | npx | ✓ | ✓ | - | - | - | - | - |  | 0 |
| autohand | 0.2.1 | npx | ✓ | ✓ | ✓ | - | - | ✓ | - |  | 1 |
| bob | 2.0.9 | npx | ✓ | ✓ | ✓ | ✓ | ✓ | - | - |  | 1 |
| claude-acp | 0.95.0 | npx | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | `systemPrompt` | 0 |
| cline | 3.0.74 | npx | (error) | | | | | | | | |
| codebuddy-code | 2.176.0 | npx | ✓ | - | - | - | - | - | - |  | 4 |
| codex-acp | 2.2.2 | npx | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | `subagents` | 2 |
| cortex-code | 1.0.73 | binary | ✓ | ✓ | - | - | - | - | - |  | 0 |
| crow-cli | 0.1.24 | binary | ✓ | - | - | - | - | - | - |  | 1 |
| cursor | 2026.10.22 | binary | ✓ | ✓ | - | - | - | - | - |  | 1 |
| deepagents | 0.1.7 | npx | ✓ | - | - | - | - | - | - | `commands`, `modes` | 3 |
| devin | 3000.11.205 | binary | ✓ | ✓ | - | ✓ | ✓ | - | ✓ |  | 1 |
| dimcode | 0.5.21 | npx | ✓ | ✓ | ✓ | - | ✓ | ✓ | - |  | 0 |
| dirac | 0.5.24 | npx | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |  | 3 |
| factory-droid | 0.249.0 | npx | ✓ | ✓ | ✓ | - | ✓ | - | - |  | 2 |
| fast-agent | 0.10.1 | uvx | ✓ | ✓ | ✓ | - | - | - | - |  | 1 |
| gemini | 0.65.0 | npx | ✓ | - | - | - | - | - | - |  | 4 |
| github-copilot-cli | 1.0.103 | npx | ✓ | ✓ | - | - | ✓ | - | - |  | 1 |
| glm-acp-agent | 1.15.0 | npx | ✓ | ✓ | ✓ | - | ✓ | ✓ | - |  | 2 |
| goose | 1.56.0 | binary | ✓ | ✓ | - | ✓ | ✓ | - | - |  | 1 |
| grok-build | 1.0.70 | npx | ✓ | ✓ | ✓ | - | ✓ | - | - |  | 1 |
| harn | 0.10.180 | binary | ✓ | ✓ | ✓ | - | ✓ | - | - | `cancelToolCall`, `redo`, `restoreToolCall`, `rollback` | 1 |
| junie | 3813.9.0 | binary | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |  | 1 |
| kilo | 7.8.17 | npx | ✓ | ✓ | ✓ | - | ✓ | ✓ | - |  | 1 |
| kimchi | 1.21.0 | binary | ✓ | ✓ | - | - | ✓ | - | - |  | 2 |
| kimi | 1.52.0 | binary | ✓ | ✓ | ✓ | - | - | - | - |  | 1 |
| minimax-code | 0.2.7 | npx | ✓ | ✓ | ✓ | - | ✓ | ✓ | - |  | 0 |
| minion-code | 0.1.44 | uvx | (error) | | | | | | | | |
| mistral-vibe | 3.0.1 | binary | ✓ | ✓ | - | - | ✓ | ✓ | - |  | 1 |
| nova | 1.1.59 | npx | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - | `agents`, `search`, `tools` | 1 |
| opencode-v2 | 2.1.4 | binary | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |  | 1 |
| opencode | 1.19.4 | binary | ✓ | ✓ | ✓ | - | ✓ | ✓ | - |  | 1 |
| pi-acp | 0.0.34 | npx | ✓ | ✓ | - | ✓ | - | - | - |  | 1 |
| poolside | 1.0.16 | binary | ✓ | ✓ | - | ✓ | ✓ | - | - |  | 0 |
| qoder | 0.2.14 | npx | ✓ | ✓ | - | - | - | - | - |  | 2 |
| qwen-code | 0.26.1 | npx | ✓ | ✓ | ✓ | - | - | - | - |  | 1 |
| sigit | 1.7.5 | npx | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |  | 1 |
| stakpak | 0.3.88 | binary | ✓ | - | - | - | - | - | - |  | 1 |

### 2.1 Totals

Of the 43 registry agents, 39 answered `initialize`; 2 could not be started and 2 were not
probed (§4).

| Capability | Method | Agents (of 39) | Used by the plugin |
|---|---|:-:|---|
| `loadSession` | `session/load` | 38 | Yes: restore with history replay |
| `list` | `session/list` | 32 | Yes: session history popup |
| `resume` | `session/resume` | 22 | Yes: restore without replay |
| `close` | `session/close` | 22 | No (modeled, never sent) |
| `fork` | `session/fork` | 14 | No (not modeled) |
| `delete` | `session/delete` | 12 | Yes: deletion on the agent side |
| `additionalDirectories` | — | 6 | No (modeled, never used) |

---

## 3. What It Means for the Plugin

### 3.1 Session restoration

The plugin restores a session when the agent declares `loadSession` or `resume`
(`SandboxAgentService`), and the two decide how (Specification §4.3):

| `loadSession` | `resume` | Agents | Restoration |
|:-:|:-:|:-:|---|
| ✓ | ✓ | 22 | Local snapshot drawn at once, `session/load` replay compared with it in the background |
| ✓ | - | 16 | Same as above |
| - | ✓ | 0 | — |
| - | - | 1 | None: agoragentic-acp |

* Every agent declaring `resume` declares `loadSession` too. The "resume only, no local
  snapshot" case of the restoration table — reconnected, but nothing to draw, since `resume`
  replays no history — does not occur with any current registry agent.
* agoragentic-acp is the only agent whose sessions cannot be restored at all.

### 3.2 Session list

7 agents do not declare `list`: agoragentic-acp, amp-acp, codebuddy-code, crow-cli, deepagents,
gemini and stakpak. For them the session history popup shows nothing reported by the agent: a
session is only reached through the local session search (Specification §4.4), i.e. only when
the plugin saved a snapshot of it. Sessions started outside the plugin (in the agent's own CLI)
cannot be reached.

### 3.3 Deletion

Only 12 agents declare `delete`. For the other 27, deleting a session renames its local files to
`.deleted` and nothing more; the agent keeps the session, and the plugin keeps it out of the
session list itself (Specification §4.3, Rename / Delete).

### 3.4 Capabilities the plugin does not use yet

* `close` (22 agents): the plugin ends a session by ending the agent process when its tab is
  closed. `session/close` would end it while keeping the process.
* `fork` (14 agents): not modeled in `SessionCapabilities`. A candidate for "branch the session
  here".
* `additionalDirectories` (6 agents): the sandbox has one workspace, so there is no use for it
  today.

### 3.5 Keys outside the specification

Some agents put keys of their own into `sessionCapabilities`:

| Agent | Keys |
|---|---|
| claude-acp | `systemPrompt` |
| codex-acp | `subagents` |
| deepagents | `commands`, `modes` |
| harn | `cancelToolCall`, `redo`, `restoreToolCall`, `rollback` |
| nova | `agents`, `search`, `tools` |

The plugin ignores unknown keys (`ignoreUnknownKeys`), so they do no harm.

---

## 4. Agents Not Covered

| Agent | Version | Reason |
|---|---|---|
| cline | 3.0.74 | Cannot start through `npx` on Linux: the package and its platform package `@cline/cli-linux-<arch>` both declare a `cline` bin, both land at the top of `node_modules`, and npm links neither (`sh: 1: cline: not found`, exit code 127). Unchanged since at least 3.0.60. The plugin cannot start it either |
| minion-code | 0.1.44 | Fails at start: `ImportError: cannot import name 'AuthMethod' from 'acp.schema'` (its ACP Python SDK dependency is unpinned, and a newer SDK no longer has the name) |
| corust-agent | 0.6.0 | `binary` only, no `linux-aarch64` build |
| vtcode | 0.96.14 | `binary` only, no `linux-aarch64` build |

---

## 5. Notes from Setting Up the Agents

These concern how an agent is installed into a sandbox rather than its capabilities, but they
will matter to whoever sets one up:

* **Environment variables can be mandatory.** fast-agent does not start without
  `FAST_AGENT_MODEL`, which its registry entry lists in `env`. The plugin passes no environment
  into the sandbox, so the sandbox has to provide it.
* **glibc.** crow-cli needs glibc 2.38 or newer: it fails on Debian bookworm (2.36) and runs on
  trixie (2.41).
* **Archive contents.** The archives are `.tar.gz`, `.tar.bz2`, `.zip` and, for sigit, the bare
  executable. junie's zip needs its executable bits restored; cortex-code's tarball holds a venv
  with symbolic links to the system python of the machine that built it, dangling elsewhere —
  it answers `initialize` nonetheless.
* **sha256.** 7 binary entries give no checksum: cortex-code, corust-agent, crow-cli, cursor,
  devin, junie and stakpak.

An earlier look past `initialize`, without credentials and on three agents only, showed how
differently agents refuse unauthenticated calls: codex-acp answers `-32000 Authentication
required` to everything, gemini fails `session/new` with its own message under the same code
and looks the session up before authenticating on `session/load`, while claude-acp created a
session (credentials may have been injected by the environment there). Error handling cannot
rely on the error code alone.

---

## 6. Not Covered: Behavior Behind the Flags

Verifying these needs a logged-in agent and at least one prompt, and was left out on purpose:

* `session/list`: whether `title` and `updatedAt` are given, whether `cwd` filters, whether
  `nextCursor` pages.
* `session/load`: whether the replayed `agent_message`s carry the same `messageId`s as the live
  turn (the local snapshot comparison of Specification §4.3 depends on it).
* Whether a session is reachable from a new agent process (after its tab was closed), or lives in
  the process only.
* Whether `session/load` / `session/resume` move the session's update time.
