# ACP Support Status

The [Agent Client Protocol](https://agentclientprotocol.com/) keeps evolving: every
[release](https://github.com/agentclientprotocol/agent-client-protocol/releases) adds unstable
features, stabilizes some of them and tightens the wording of the stable ones. This document
records how far the plugin follows it, so that "does it support X?" has one answer.

A feature also needs the agent: the plugin can only use what the agent declares in its
`initialize` answer. What the registry agents declare is in
[AGENT_SESSION_CAPABILITIES.md](development/AGENT_SESSION_CAPABILITIES.md).

---

## 1. Baseline

| Item | Value |
|---|---|
| Protocol version | `1` (sent in `initialize`; an agent answering less than 1 is an error) |
| Checked against | ACP release `v1.15.1` / `schema-v1.29.1` (2026-11-02), `schema/v1/schema.json` and `meta.json` |
| Plugin version | 0.0.6 |
| Unstable features (`schema.unstable.json`) | Not implemented, see §7 |
| ACP v2 (`schema-v2.0.0-alpha.*`) | Not implemented |

Legend used below:

* **Yes** — implemented.
* **Partial** — implemented with the limits stated.
* **By design** — deliberately not implemented: it would break the isolation of the sandbox, or
  the plugin covers the need another way.
* **No** — not implemented yet.

---

## 2. Client capabilities advertised in `initialize`

| Capability | Advertised | Notes |
|---|---|---|
| `fs.readTextFile` / `fs.writeTextFile` | `false` (by design) | The agent works on the files inside the sandbox. |
| `terminal` | `false` (by design) | Commands run inside the sandbox. |
| `elicitation.form` | `{}` while JCEF is available | Without JCEF the field is omitted. |
| `elicitation.url` | Omitted | URL mode would need the target shown and consented to before navigating. |
| `auth.terminal` | Omitted (by design) | Authentication is set up in the sandbox beforehand. |
| `session.configOptions.boolean` | `{}` | Drawn as a toggle (§5), with or without JCEF. |
| `session.notices` | `{}` | Shown as a line of the transcript (§4), with or without JCEF. |
| `session.compaction` | Omitted | Compaction updates are not shown yet (§4). |

An omitted capability means "unsupported" to the agent, which then must not use the feature.

---

## 3. Methods

### 3.1. Client → Agent

| Method | Status | Notes |
|---|---|---|
| `initialize` | Yes | Idle timeout instead of a hard deadline (first-run `npx` downloads). |
| `authenticate` | By design | `authMethods` are only logged; authentication is done in the sandbox. |
| `logout` | No | |
| `session/new` | Yes | `mcpServers` is always `[]` (MCP servers are configured on the agent side). `additionalDirectories` is not sent. |
| `session/load` | Yes | When `loadSession` is declared. |
| `session/resume` | Yes | When `sessionCapabilities.resume` is declared. |
| `session/list` | Yes | When `sessionCapabilities.list` is declared; pages are followed. |
| `session/delete` | Yes | When `sessionCapabilities.delete` is declared. |
| `session/close` | Yes | When `sessionCapabilities.close` is declared: for the session left by `New session`, by entering another session, and by closing the tab or the connection. Best effort, waits at most 3 s. |
| `session/prompt` | Partial | See §5 for the content sent. No timeout. |
| `session/cancel` | Yes | Pending permission requests and forms are answered as cancelled. |
| `session/set_mode` | Yes | |
| `session/set_config_option` | Yes | `select` and `boolean` options (§5). |
| `session/set_model` | Yes (legacy) | Not in the v1.15 schema; kept for agents that still report `models` instead of a `model` config option. |
| `$/cancel_request` | No (sending) | The plugin never withdraws a request it sent. |

### 3.2. Agent → Client

| Method | Status | Notes |
|---|---|---|
| `session/update` | Yes | Variants in §4. |
| `session/request_permission` | Yes | Inline permission card; answered `cancelled` when the turn is stopped. |
| `elicitation/create` | Partial | Form mode only; a form with a property the plugin cannot draw is refused with `-32602`. |
| `elicitation/complete` | No | Belongs to URL mode, which is not advertised. |
| `$/cancel_request` | Yes | Withdraws a pending permission request or form. |
| `fs/*`, `terminal/*` | By design | Answered with `-32601` (method not found), as is any other unknown request. |

---

## 4. `session/update` variants

| Variant | Status | Notes |
|---|---|---|
| `user_message_chunk` | Yes | Replayed history. |
| `agent_message_chunk` | Yes | `messageId` is used to compare replayed history with the local transcript. |
| `agent_thought_chunk` | Yes | |
| `tool_call` / `tool_call_update` | Yes | `title`, `name`, `kind`, `status`, `content`, `locations`, `rawInput`, `rawOutput`. `name` is the title of a card without `title`. |
| `plan` | Yes | |
| `available_commands_update` | Yes | Slash command popup. |
| `current_mode_update` | Yes | |
| `current_model_update` | Yes (legacy) | Not in the v1.15 schema. |
| `config_option_update` | Yes | |
| `session_info_update` | Yes | Title / update time. |
| `usage_update` | Yes | Context usage and cost. |
| `notice` | Yes | One line of plain text, colored by `severity`; live only, never stored in the snapshot. |
| `compaction_update` / `compaction_summary_chunk` | No | Not advertised; ignored if received. |

Any variant the plugin does not know is ignored and logged, never a decoding failure.

---

## 5. Content

| Item | Status | Notes |
|---|---|---|
| Prompt: `text` | Yes | |
| Prompt: `resource` (embedded) | Yes | Attached files and selections, when the agent declares `promptCapabilities.embeddedContext`. Otherwise they are inlined into a `text` block as `<context ref="file://…">…</context>`. |
| Prompt: `resource_link` | No | |
| Prompt: `image` | No | Planned: attached image files, when `promptCapabilities.image` is declared, with a thumbnail chip. Until then a binary file cannot be attached at all. |
| Prompt: `audio` | No | |
| Shown: `text` | Yes | Markdown, sanitized. |
| Shown: `resource_link` / `resource` | Partial | As text: the link's text, title, name or URI; the resource's text. |
| Shown: `image` | Yes | In agent messages and tool call results: PNG, JPEG, GIF and WebP (told by their bytes, not by `mimeType`) up to about 5 MiB. Anything else, and every image of a restored snapshot (images are not stored), is a `[image <mime>]` placeholder. |
| Shown: `audio` | Partial | Shown as an `[audio <mime>]` placeholder. |
| Tool call content: `content` / `diff` | Yes | Diffs open in the IDE diff viewer. |
| Tool call content: `terminal` | By design | Only meaningful with the `terminal` client capability. |
| Config option type `select` | Yes | Grouped options are flattened. `mode`, `model`, `thought_level` categories are recognized. |
| Config option type `boolean` | Yes | A toggle in the prompt action bar; set with `type: "boolean"` and a JSON boolean. |
| Stop reasons | Yes | `end_turn`, `max_tokens`, `max_turn_requests`, `refusal`, `cancelled`. |

---

## 6. Stabilized features by release

The features each release promoted from unstable to stable, and where the plugin stands. Releases
that only clarified wording are left out.

| Release | Stabilized | Plugin |
|---|---|---|
| v0.10.8 | Session config options | Yes (`select`) |
| v0.11.1 | `session/list`, `session_info_update` | Yes |
| v0.12.2 | `session/resume` | Yes |
| v0.12.2 | `session/close` | Yes |
| v0.13.3 | `logout` | No |
| v0.13.5 | `additionalDirectories` for sessions | No |
| v0.13.6 | Optional message IDs (`messageId`) | Yes |
| v0.13.6 | `usage_update` | Yes |
| v0.13.6 | `session/delete` | Yes |
| v1.1.0 | `model` config option category | Yes |
| v1.2.0 | Request cancellation (`$/cancel_request`) | Yes (receiving) |
| v1.3.0 | Boolean session config options | Yes |
| v1.7.0 | Elicitation | Partial (form mode) |
| v1.7.0 | Terminal authentication | By design |
| v1.8.0 | Tool call `name` | Yes |
| v1.11.0 | Session notices | Yes |
| v1.11.0 | Session compaction | No |

---

## 7. Unstable features

Nothing of `schema.unstable.json` is implemented. As of v1.15.1 that includes, among others:
`session/fork`, `session/archive` / `session/unarchive`, `session/status`, `session/set_title`,
`providers/*`, `auth/status` / `auth/status_update`, `mcp/message`, and the proxy methods
`proxy/initialize` / `proxy/successor`. An unstable feature is looked at once it is stabilized.

---

## 8. Known deviations

None known.

---

## 9. Keeping this document current

Before every plugin release (it is a step of [RELEASE.md](development/RELEASE.md)), and whenever ACP
handling changes:

1. Read the release notes: `gh api repos/agentclientprotocol/agent-client-protocol/releases`.
   The `v<x.y.z>` releases are the ones to read; `schema-v*` releases repeat their schema part.
2. Add every newly stabilized feature to §6, and to §3–§5 where it belongs.
3. Compare the methods with `schema/v1/meta.json` and the `session/update` variants with
   `SessionUpdate` in `schema/v1/schema.json` of the release tag.
4. Update the release in §1.
