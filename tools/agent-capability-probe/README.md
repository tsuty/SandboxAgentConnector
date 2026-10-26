# Agent capability probe

`probe_capabilities.py` asks the agents of the
[ACP registry](https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) what they
support for sessions. It sends nothing but `initialize` — no session is created and no prompt is
sent, so no account or API key is needed — and records `agentCapabilities`, `loadSession` and
`sessionCapabilities` above all.

## Running it in Docker

The usual way: a local container with Python, `npx` and `uvx`, and nothing of the host — no
credential, no home directory. Outputs land in `work/` next to this file.

```bash
cd tools/agent-capability-probe
docker compose build
docker compose run --rm probe commands               # -> work/agents.tsv
docker compose run --rm probe install                # binary agents, enabled in work/agents.tsv
docker compose run --rm probe probe --jobs 4         # -> work/results/
docker compose run --rm probe probe --only gemini    # a few agents only
docker compose run --rm probe summary                # -> work/results/SUMMARY.md
docker compose down -v                               # drop the npx / uvx download caches
```

* The script is mounted over the copy in the image: an edit takes effect without a rebuild.
* The `env` the registry entries ask for (auto updates off, a model for fast-agent) is set in
  `compose.yaml`, as a sandbox set up for the plugin would have it.
* Each agent starts in an empty working directory of its own, so the files agents drop there stay
  out of `work/`.
* `install` puts the binary agents into a named volume (`agents`), so they survive between runs;
  `docker compose down -v` drops them with the caches.

## Running it directly

Work in a scratch directory: the outputs land in the current directory.

```bash
mkdir -p /tmp/probe && cd /tmp/probe
P=<repo>/tools/agent-capability-probe/probe_capabilities.py

# 1. One start command per registry agent -> agents.tsv
python3 -I "$P" commands

# 2. (optional) install the binary agents built for this machine -> ~/agents, enabled in agents.tsv
python3 -I "$P" install

# 3. initialize each agent -> results/<agent id>.json and results/SUMMARY.md
python3 -I "$P" probe --jobs 4
python3 -I "$P" probe --only claude-acp,codex-acp          # a few agents only
python3 -I "$P" probe --exec-prefix "sbx exec -i my-sandbox"  # inside the sandbox, as the plugin does

# 4. rebuild the summary after editing or deleting result files
python3 -I "$P" summary
```

## agents.tsv

`<agent id><TAB><command>`, one agent per line; lines starting with `#` are skipped. The command
is spelled the way the plugin spells it (`AcpAgentRegistry`, `AgentLauncher`):

| Distribution | Line |
|---|---|
| `npx` | `npx -y <package> <args>` |
| `uvx` | `uvx <package> <args>` |
| `binary` | commented out, with `/path/in/sandbox/to/<executable>` to replace once it is installed; the archive and executable of the sandbox platform are noted above it. `install` replaces it |

`npx` and `uvx` win over `binary` when an entry offers several, as in the plugin. The `env` a
registry entry asks for is noted as a comment only: the plugin passes no environment into the
sandbox. The commands are split like a shell would split them and started directly, never through
a shell.

## What `install` does

* Takes the agents the plugin starts as a `binary` (an entry also offering `npx` / `uvx` is
  started through those) and the build for this machine (`--platform` to pick another), e.g.
  `linux-aarch64` in Docker on an Apple silicon Mac. An agent without such a build is skipped.
* Downloads the archive, checks its `sha256` when the registry gives one (some entries do not),
  and unpacks it into `<dest>/<agent id>/<version>/` (`--dest`, `~/agents` by default):
  `.tar.gz` / `.tar.bz2` / `.tar.xz`, `.zip` (with the executable bits and symbolic links it
  records), or the bare executable itself. Nothing may be written outside that directory, nor
  through a link leading out of it; a symbolic link pointing outside is kept though (cortex-code
  bundles a venv pointing at the system python of the machine that built it), and dangles unless
  the container has what it points at.
* A version already installed is not downloaded again.
* Turns the commented-out line of every installed agent of agents.tsv into the command starting
  the executable, with the registry's `args`; every other line is kept as it is.
* An agent failing to download or unpack is reported and leaves the others alone.

An installed agent may still fail in `probe`: a shared library or a newer glibc it needs, a first
run waiting for an answer on the terminal, and so on. The stderr lines in its result tell.

## What `probe` does

* Sends the very `initialize` the plugin sends (protocol version 1, no `fs` / `terminal` client
  capability).
* Waits with the plugin's *idle* timeout (180 s by default, `--idle-timeout`), reset by any stdout
  or stderr output, since a first `npx` run downloads for a while.
* Kills the whole process group once answered.
* Writes `results/<agent id>.json`: the command, the raw `initialize` result, the elapsed time, the
  last stderr lines, and any non-JSON line the agent printed on stdout (which breaks ACP).

## SUMMARY.md

A capability counts as supported when it is `true` or an object (`{}`), like
`FlexibleBooleanSerializer`. Columns:

* `loadSession` — `agentCapabilities.loadSession` (`session/load`, history replayed)
* `restorable (load∨resume)` — whether the plugin can restore a session at all
* `list`, `resume`, `delete`, `close`, `fork`, `additionalDirectories` — `sessionCapabilities.*`;
  the plugin models all but `fork`, and uses `list`, `resume` and `delete`
* `other sessionCapabilities` — keys the agent reports beyond those
* `note` — number of `authMethods`, or the error

The capabilities are what the agent *declares*. Whether `session/list` gives titles and times,
whether a replay carries `messageId`s, and so on, can only be seen by driving a real session.
