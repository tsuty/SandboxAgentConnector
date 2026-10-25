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
