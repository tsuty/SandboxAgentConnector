# Fake ACP agent

`switch_mode_agent.py` is a hand written ACP v1 agent that does nothing but ask for a mode
switch. It exists because a real agent only sends `switch_mode` when it decides to leave its plan
mode, which is not something a UI change can be verified against.

## Running it from the IDE

The settings no longer accept a hand written command: the attach command is built from the picked
**Sandbox type** and **Sandbox name**, and the agent command from a registry entry (see §5.1 of the
specification). The script therefore cannot be selected in the dialog any more.

Run it by starting the connection with the command instead, from a debugger or a scratch:

```kotlin
project.service<SandboxAgentService>().connect("python3 <repo>/tools/fake-acp-agent/switch_mode_agent.py")
```

The sandbox still fronts it, so the sandbox named in the settings has to be running and has to see
the script; the connection health check runs `echo sandbox-agent-connector` through the same sandbox
and only looks at the exit code, so it passes without the script being involved at all.

## Colliding session ids

`--session-id <id>` makes `session/new` answer with that id every time instead of a fresh one.
Started under two different agents (two tabs whose agents each run the script with the same id),
the second tab finds the local snapshot of the first and must refuse the session rather than
overwrite it (see `docs/MultiAgent.ja.md` §3.4).

## What it sends

- `session/new` answers with two modes, `architect` (current) and `code`.
- Every prompt asks for a `switch_mode` permission whose options **are the modes**
  (`code` / `ask` / `reject`), exactly as in the session modes documentation.
- The plan is a bare `{"type": "text"}` content block, not the `{"type": "content"}` wrapper the
  tool call examples use — the flavour that used to be dumped as raw JSON.
- After the decision it reports the tool call as `completed` and sends `current_mode_update` with
  a `modeId` field (the documented spelling; the schema says `currentModeId`, and both are
  accepted).

## Checking it without the IDE

```
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/tmp"}}' \
  '{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"x","prompt":[]}}' \
  '{"jsonrpc":"2.0","id":1001,"result":{"outcome":{"outcome":"selected","optionId":"code"}}}' \
  | python3 tools/fake-acp-agent/switch_mode_agent.py
```

# Elicitation agent

`elicitation_agent.py` asks `elicitation/create` questions of every shape the form card has to
draw. Claude's `AskUserQuestion` only ever produces single select, multi select and a free text
"Other" field, so a text field with a `format`, a number, a check box or a drop down can only be
looked at with this script. It is started the same way as `switch_mode_agent.py` (see above).

Each prompt names a scenario anywhere in its text (`list`, or anything it does not know, prints
them). The agent sends the request, waits for the answer and writes it back into the transcript as
JSON, so what the card sent can be read next to the card.

| Scenario | What to check |
| --- | --- |
| `ask-single`, `ask-multi`, `ask-many` | The shapes `claude-agent-acp` builds from `AskUserQuestion`: radios with option descriptions, check boxes, one "Other" field per question. Several questions share one card and the generic message, and a dotted rule separates one question from the next (an "Other" field stays with its question). |
| `ask-markdown` | Markdown everywhere a model puts it: bold and code in the question, the header, the labels and the descriptions, a list in a question, a fenced preview and an unfenced ASCII mockup. Everything is rendered (the mockup keeps its shape), and the two links stay plain text, not clickable, in the form and in the record of the call. |
| `ask-record` | Only the record of the call, as a restored session replays it, with no form: a folded "Asked 2 questions" row in the `Worked` group that unfolds to the questions, the options and their descriptions, with nothing to answer. |
| `ask-late` | The call is first reported with an empty input and completes it afterwards: its row starts as an ordinary call and, once the input is complete, reads "Asked 2 questions" and unfolds to the questions. |
| `ask-preview` | Options carrying a `preview` under `_meta["_claude/askUserQuestionOption"]`: selecting an option shows its preview in a monospace box under the radios (scrollable, text only), and the last option has none, which hides the box. |
| `text` | Plain, `minLength`/`maxLength`, `email`, `uri`, `date`, `date-time` and `pattern` fields; the default is pre-filled; a bad value shows an error under the field and nothing is sent. |
| `number` | Integer and decimal fields with bounds; `18.5` is refused for the integer, `17` is below the minimum. |
| `boolean` | Check boxes with default on, default off and no default; an unchecked box is sent as `false`. |
| `select` | Untitled `enum` and titled `oneOf` radios with their defaults pre-selected; `Clear` empties an optional one. |
| `dropdown` | Seven or more options are a drop down; the optional one has an empty entry, the required one does not. |
| `multi` | Untitled and titled multi select; `minItems`/`maxItems` errors; an empty optional field is left out of `content`. |
| `required` | `*` marks required fields; submitting with one empty shows the error and focuses it. |
| `all` | Every kind in one form: the card scrolls with the transcript and the answer holds exactly the filled keys. |
| `empty` | No fields: only `Submit`, `Decline` and `Cancel`. |
| `long` | Long message, title, description and option text wrap inside a narrow tool window. |
| `twice` | Two forms in a row; the first is locked while the second is open. |
| `string-id` | A JSON-RPC string id is echoed back unchanged. |
| `tool-call` | A request tied to a `toolCallId`. |
| `hang` | Press Stop while the form is open: it locks as `Cancelled` and the agent gets `cancel`. The turn waits for the answer, so there is no hurry. |
| `withdraw` | Leave the form open: 3 seconds later the agent withdraws it with `$/cancel_request`. The form locks as `Cancelled` on its own and the transcript says "Withdrawn as expected" (the plugin answered `cancel`). |
| `running` | A command reported as running that never reports its end. Press Stop: the row turns to `cancelled` right away, and stays so in a restored session. |
| `bad-type`, `bad-items`, `no-mode`, `url` | The plugin refuses with `-32602` and no card appears; the transcript says "Refused as expected". |

The `ask-*` scenarios also report the `tool_call` of the `AskUserQuestion` before the form (with the
question as its title and the full `rawInput`) and its `tool_call_update` after it, so the record of
the call can be looked at: a folded row in the `Worked` group that unfolds to the questions as asked
and stays once the form is answered. The text of the answer is left out of it on purpose.

The agent also lists five sessions through `session/list`, two a page, so the history popup must show
all five ("Listed session 1 of 5" to "5 of 5") by following `nextCursor`. They can only be listed, not
opened: the agent loads no history.

Add `slow` to any prompt (`slow ask-single`) and the agent waits 10 seconds before it asks
(`FAKE_SLOW_SECONDS` changes that). That is the window for what cannot be done in time against a real
model: press Stop before the form appears (the turn ends as cancelled and no form ever shows up), or
switch to another tab while the question is still on its way.

Also check by hand: `Decline` and `Cancel` (the agent shows `decline` / `cancel` and no `content`),
a form left open in a background tab marks that tab's chip, and with the Terminal/JCEF fallback (a
runtime without JCEF) the agent reports that `elicitation.form` was not advertised.

## Checking it without the IDE

```
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientCapabilities":{"elicitation":{"form":{}}}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/tmp"}}' \
  '{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"x","prompt":[{"type":"text","text":"text"}]}}' \
  '{"jsonrpc":"2.0","id":2001,"result":{"action":"accept","content":{"plain":"hi"}}}' \
  | python3 tools/fake-acp-agent/elicitation_agent.py
```

## Prompts for a real Claude agent

These make the model call `AskUserQuestion`, which is the path users actually meet. Send them to
an agent that is registered as `claude-acp`.

1. Single select: `Use the AskUserQuestion tool to ask me which of Kotlin, Python and Rust I prefer. Give every option a description.`
2. Multi select: `Use AskUserQuestion with multiSelect: true to ask which of unit tests, lint and end-to-end tests I want you to run.`
3. Several questions: `Use one AskUserQuestion call with three questions: a language (single select), checks to run (multi select) and a deploy target (single select, four options).`
4. Long text: `Use AskUserQuestion to ask me something whose question text and option labels are each at least two sentences long.`
5. Option preview: `Use AskUserQuestion with a single select question where every option has a code snippet as its preview.`
6. Skip: answer a question by pressing `Submit` with nothing chosen. Prompt: `Use AskUserQuestion to ask me which editor I use, then tell me exactly what answer you received.`
7. Free text: answer only the "Other" field. Prompt: `Use AskUserQuestion to ask me for a project name, then repeat my answer back.`
8. Decline / cancel: `Use AskUserQuestion to ask me whether to continue, then say what happened.` Press `Decline` once and `Cancel` once; the agent should report that no answer was given and not retry blindly.
9. Stop: send prompt 8, wait for the form and press Stop while it is open. The turn waits for the answer, so there is no need to be quick; the form locks as `Cancelled`. Pressing Stop *before* the form appears cannot be done reliably against a real model; use `slow` with the fake agent for that.
10. Background tab: send prompt 8 in one tab, wait for the form, then switch to another tab (the form stays open) and check that the first tab's chip is marked.
