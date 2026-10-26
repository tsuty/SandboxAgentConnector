#!/usr/bin/env python3
"""Asks every agent of the ACP registry for its session capabilities.

Two steps, so the commands can be looked at and edited in between:

  commands  Reads the ACP registry and writes one start command per agent, spelled the way the
            plugin spells it (`AcpAgentRegistry` / `AgentLauncher`): `npx -y <package> <args>`,
            `uvx <package> <args>`. A `binary` agent has to be installed into the sandbox by hand,
            so its line is written commented out, with a dummy path to replace.
  install   Downloads and unpacks the `binary` agents built for the machine it runs on, and turns
            their commented-out lines of agents.tsv into commands starting the installed executable.
  probe     Starts each command (optionally behind `sbx exec -i <sandbox>` or the like), sends the
            same `initialize` the plugin sends, and records `agentCapabilities` — `loadSession` and
            `sessionCapabilities` above all — as one JSON file per agent plus a Markdown summary.
  summary   Rebuilds the Markdown summary from the JSON files of an earlier probe.

Only `initialize` is sent: no session is created, nothing is prompted, no credential is needed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.request
from collections import deque
from concurrent.futures import ThreadPoolExecutor
import zipfile
from pathlib import Path

REGISTRY_URL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"

# Mirrors `InitializeParams` of AcpModels.kt: the plugin offers no fs / terminal capability.
INITIALIZE_PARAMS = {
    "protocolVersion": 1,
    "clientInfo": {"name": "SandboxAgentConnector", "version": "1.0.0"},
    "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False},
}

# Same idle timeout as the plugin's `initialize`: reset by any output, since a first `npx` run
# may download for minutes before the agent answers.
DEFAULT_IDLE_TIMEOUT = 180.0

# The session capabilities the plugin models (`SessionCapabilities`), plus `fork`, which it does not
# model yet. Any other key an agent reports is listed in the summary under "other".
SESSION_KEYS = ["list", "resume", "delete", "close", "fork", "additionalDirectories"]

# Placeholder of a commented-out `binary` line; `probe` refuses to start a line still holding it.
BINARY_PLACEHOLDER = "/path/in/sandbox/to"

USER_AGENT = "SandboxAgentConnector-capability-probe"


def current_platform() -> str:
    """The `<os>-<arch>` key of the registry's `binary` entries for the machine this runs on."""
    arch = {"arm64": "aarch64", "amd64": "x86_64"}.get(platform.machine().lower(), platform.machine().lower())
    return f"{'darwin' if sys.platform == 'darwin' else sys.platform.rstrip('0123456789')}-{arch}"


# Platforms of the sandbox, in the order the hint of a `binary` line prefers them: this machine's
# first, since `install` installs that one (in Docker on an Apple silicon Mac: linux-aarch64).
SANDBOX_PLATFORMS = list(dict.fromkeys([current_platform(), "linux-x86_64", "linux-aarch64"]))


# ---------------------------------------------------------------------------------------------
# commands
# ---------------------------------------------------------------------------------------------


def load_registry(source: str) -> dict:
    if source.startswith(("http://", "https://")):
        # The CDN (Cloudflare, error 1010) refuses the default `Python-urllib` user agent.
        request = urllib.request.Request(source, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    return json.loads(Path(source).read_text(encoding="utf-8"))


def command_line(agent: dict) -> tuple[str, list[str], bool]:
    """Returns the launcher, the lines to write, and whether the agent can run as written.

    Same preference as `AcpAgentRegistry.toAgent`: npx, then uvx, then binary.
    """
    distribution = agent.get("distribution") or {}
    for launcher, prefix in (("npx", ["npx", "-y"]), ("uvx", ["uvx"])):
        package = distribution.get(launcher) or {}
        if package.get("package"):
            args = [a for a in package.get("args", []) if a.strip()]
            lines = env_hint(package.get("env"))
            lines.append(f"{agent['id']}\t{shlex.join(prefix + [package['package']] + args)}")
            return launcher, lines, True

    binary = distribution.get("binary") or {}
    if binary:
        platform = next((p for p in SANDBOX_PLATFORMS if p in binary), next(iter(binary)))
        entry = binary[platform]
        executable = Path(entry.get("cmd", agent["id"])).name
        args = [a for a in entry.get("args", []) if a.strip()]
        lines = [
            f"# binary: install it into the sandbox first, then fix the path and uncomment.",
            f"#   {platform}: {entry.get('archive', '?')} -> {entry.get('cmd', '?')}",
            f"#   other platforms: {', '.join(p for p in binary if p != platform) or '-'}",
        ]
        lines += env_hint(entry.get("env"))
        lines.append(f"# {agent['id']}\t{shlex.join([f'{BINARY_PLACEHOLDER}/{executable}'] + args)}")
        return "binary", lines, False

    return "none", [f"# {agent['id']}: no distribution the plugin can start"], False


def env_hint(env: dict | None) -> list[str]:
    # The plugin passes no environment into the sandbox; these have to be set there beforehand.
    if not env:
        return []
    return [f"# env (set in the sandbox, not passed by the plugin): " + " ".join(f"{k}={v}" for k, v in env.items())]


def cmd_commands(args: argparse.Namespace) -> None:
    registry = load_registry(args.registry)
    out = [
        f"# Start commands of the ACP registry ({args.registry}), registry version {registry.get('version', '?')}.",
        "# Format: <agent id><TAB><command>. Lines starting with '#' are skipped by `probe`.",
        "",
    ]
    counts: dict[str, int] = {}
    for agent in registry.get("agents", []):
        if not agent.get("id"):
            continue
        launcher, lines, _ = command_line(agent)
        counts[launcher] = counts.get(launcher, 0) + 1
        out.append(f"# --- {agent['id']} ({agent.get('name', '')} {agent.get('version', '')}) [{launcher}]")
        out += lines
        out.append("")
    Path(args.output).write_text("\n".join(out), encoding="utf-8")
    summary = ", ".join(f"{k}: {v}" for k, v in sorted(counts.items()))
    print(f"wrote {args.output} ({summary})", file=sys.stderr)


# ---------------------------------------------------------------------------------------------
# install
# ---------------------------------------------------------------------------------------------


def binary_entry(agent: dict, target: str) -> dict | None:
    """The `binary` entry `install` takes for [agent]: only for an agent the plugin starts as a
    binary (no npx / uvx distribution), and only when it is built for [target]."""
    distribution = agent.get("distribution") or {}
    if any((distribution.get(k) or {}).get("package") for k in ("npx", "uvx")):
        return None
    return (distribution.get("binary") or {}).get(target)


def inside(root: Path, name: str) -> Path:
    path = (root / name).resolve()
    if path != root and root not in path.parents:
        raise ValueError(f"archive member outside the target directory: {name}")
    return path


def keep_symlinks(member: tarfile.TarInfo, path: str) -> tarfile.TarInfo:
    """The `data` filter, refusing members that leave `root`, but for a symbolic link pointing
    outside: a bundled venv points at the system python of the machine that built it
    (cortex-code: `python3.12 -> /usr/bin/python3.12`, and `python -> python3.12` through it).

    Creating a symbolic link writes nothing outside, and a member written through one later is
    still refused, since the filter resolves the links already extracted. A hard link pointing
    outside stays refused: it would be the outside file itself."""
    try:
        return tarfile.data_filter(member, path)
    except (tarfile.AbsoluteLinkError, tarfile.LinkOutsideDestinationError):
        # Both are raised only once the member's own path has passed the checks.
        if member.issym():
            return member
        raise


def unpack_zip(archive: Path, root: Path) -> None:
    # zipfile restores neither the executable bit nor symbolic links: both are read back from the
    # Unix mode the archive keeps in `external_attr`.
    with zipfile.ZipFile(archive) as zf:
        for info in zf.infolist():
            path = inside(root, info.filename)
            mode = info.external_attr >> 16
            if info.is_dir():
                path.mkdir(parents=True, exist_ok=True)
            elif stat.S_ISLNK(mode):
                path.parent.mkdir(parents=True, exist_ok=True)
                target = zf.read(info).decode()
                # Kept wherever it points, as in `keep_symlinks`: what is written later goes
                # through `inside`, which resolves the links already created.
                path.symlink_to(target)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as src, open(path, "wb") as dst:
                    shutil.copyfileobj(src, dst)
                if mode & 0o777:
                    path.chmod(mode & 0o777)


def unpack(archive: Path, url: str, root: Path, cmd: str) -> None:
    name = url.lower().split("?")[0]
    if name.endswith(".zip"):
        unpack_zip(archive, root)
    elif any(name.endswith(s) for s in (".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".tar")):
        with tarfile.open(archive) as tf:
            if hasattr(tarfile, "data_filter"):
                tf.extractall(root, filter=keep_symlinks)
            else:
                for member in tf.getmembers():
                    inside(root, member.name)
                tf.extractall(root)
    else:
        # A bare executable (sigit): it is the command itself.
        shutil.copyfile(archive, inside(root, cmd))


def download(url: str, path: Path) -> str:
    """Downloads [url] into [path] and returns its sha256."""
    digest = hashlib.sha256()
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response, open(path, "wb") as out:
        while chunk := response.read(1 << 20):
            digest.update(chunk)
            out.write(chunk)
    return digest.hexdigest()


def install_one(agent: dict, entry: dict, dest: Path) -> tuple[str, str]:
    """Installs one agent into `<dest>/<id>/<version>/`; returns (status, command or reason)."""
    root = dest / agent["id"] / (agent.get("version") or "unversioned")
    executable = inside(root, entry["cmd"])
    args = [a for a in entry.get("args", []) if a.strip()]
    command = shlex.join([str(executable)] + args)
    marker = root / ".installed"
    if marker.exists() and executable.exists():
        return "cached", command

    shutil.rmtree(root, ignore_errors=True)
    root.mkdir(parents=True)
    try:
        with tempfile.TemporaryDirectory(prefix=f"install-{agent['id']}-") as tmp:
            archive = Path(tmp) / "download"
            sha256 = download(entry["archive"], archive)
            expected = entry.get("sha256")
            if expected and sha256.lower() != expected.lower():
                raise ValueError(f"sha256 mismatch: expected {expected}, got {sha256}")
            unpack(archive, entry["archive"], root, entry["cmd"])
        if not executable.is_file():
            raise FileNotFoundError(f"{entry['cmd']} is not in the archive")
        executable.chmod(executable.stat().st_mode | 0o755)
        marker.write_text(json.dumps({"archive": entry["archive"], "sha256": sha256}) + "\n", encoding="utf-8")
        return ("installed" if expected else "installed (no sha256 to check)"), command
    except Exception as e:  # noqa: BLE001 — one agent failing must not stop the others
        shutil.rmtree(root, ignore_errors=True)
        return "error", f"{type(e).__name__}: {e}"


def enable_lines(path: Path, commands: dict[str, str]) -> int:
    """Replaces the commented-out line of each installed agent in agents.tsv by its command.

    Every other line is kept as it is, edits included.
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    enabled = 0
    for i, line in enumerate(lines):
        body = line.lstrip("#").strip()
        agent_id, sep, command = body.partition("\t")
        if sep and agent_id in commands and (line.startswith("#") or BINARY_PLACEHOLDER in command):
            lines[i] = f"{agent_id}\t{commands[agent_id]}"
            enabled += 1
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return enabled


def cmd_install(args: argparse.Namespace) -> None:
    agents_file = Path(args.agents)
    if not agents_file.exists():
        sys.exit(f"{agents_file} does not exist: run `commands` first")
    registry = load_registry(args.registry)
    target = args.platform or current_platform()
    dest = Path(args.dest).expanduser().resolve()
    wanted = set(args.only.split(",")) if args.only else None

    todo, skipped = [], []
    for agent in registry.get("agents", []):
        if not agent.get("id") or (wanted and agent["id"] not in wanted):
            continue
        distribution = agent.get("distribution") or {}
        if not distribution.get("binary"):
            continue
        entry = binary_entry(agent, target)
        if entry:
            todo.append((agent, entry))
        elif any((distribution.get(k) or {}).get("package") for k in ("npx", "uvx")):
            skipped.append((agent["id"], "started through npx / uvx"))
        else:
            skipped.append((agent["id"], f"no {target} build"))

    print(f"installing {len(todo)} binary agents for {target} into {dest}", file=sys.stderr)

    def run(item: tuple[dict, dict]) -> tuple[str, str, str]:
        agent, entry = item
        print(f"[start] {agent['id']} {agent.get('version', '')}: {entry['archive']}", file=sys.stderr)
        status, detail = install_one(agent, entry, dest)
        print(f"[{status}] {agent['id']}", file=sys.stderr)
        return agent["id"], status, detail

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(run, todo))

    commands = {agent_id: detail for agent_id, status, detail in results if status != "error"}
    enabled = enable_lines(agents_file, commands)
    print(f"\nenabled {enabled} line(s) of {agents_file}\n")
    for agent_id, status, detail in results:
        print(f"{status:32} {agent_id:20} {detail}")
    for agent_id, reason in skipped:
        print(f"{'skipped':32} {agent_id:20} {reason}")


# ---------------------------------------------------------------------------------------------
# probe
# ---------------------------------------------------------------------------------------------


def read_agent_lines(path: str) -> list[tuple[str, str]]:
    agents = []
    for number, raw in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        agent_id, sep, command = line.partition("\t")
        if not sep or not command.strip():
            sys.exit(f"{path}:{number}: expected '<agent id><TAB><command>'")
        agents.append((agent_id.strip(), command.strip()))
    return agents


def probe_one(agent_id: str, command: str, exec_prefix: list[str], idle_timeout: float) -> dict:
    record: dict = {"agentId": agent_id, "command": command, "argv": None, "status": "error"}
    if BINARY_PLACEHOLDER in command:
        record["error"] = f"the command still holds the placeholder {BINARY_PLACEHOLDER}"
        return record

    # An argument vector, never a shell — like `SandboxProcessManager`.
    argv = exec_prefix + shlex.split(command)
    record["argv"] = argv
    # Agents write their own state into the working directory (fast-agent its `.fast-agent/`), so
    # each starts in an empty one of its own, thrown away afterwards, rather than among the results.
    workdir = tempfile.mkdtemp(prefix=f"probe-{agent_id}-")
    try:
        return run_initialize(record, argv, workdir, idle_timeout)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def run_initialize(record: dict, argv: list[str], workdir: str, idle_timeout: float) -> dict:
    started = time.monotonic()
    try:
        process = subprocess.Popen(
            argv,
            cwd=workdir,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,  # npx / uvx fork children: the whole group is killed at the end
        )
    except OSError as e:
        record["error"] = f"cannot start: {e}"
        return record

    last_output = [time.monotonic()]
    stderr_tail: deque[str] = deque(maxlen=40)
    stdout_noise: deque[str] = deque(maxlen=20)
    response: dict = {}
    done = threading.Event()

    def read_stdout() -> None:
        for raw in process.stdout:
            last_output[0] = time.monotonic()
            text = raw.decode("utf-8", "replace").strip()
            if not text:
                continue
            try:
                message = json.loads(text)
            except json.JSONDecodeError:
                stdout_noise.append(text[:500])  # an agent logging to stdout breaks the protocol
                continue
            if isinstance(message, dict) and message.get("id") == 1 and ("result" in message or "error" in message):
                response.update(message)
                done.set()
                return
            if isinstance(message, dict) and "method" in message and "id" in message:
                # An agent request before `initialize` is answered is unexpected; refuse it.
                reply = {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "Method not found"}}
                write(reply)
        done.set()

    def read_stderr() -> None:
        for raw in process.stderr:
            last_output[0] = time.monotonic()
            stderr_tail.append(raw.decode("utf-8", "replace").rstrip()[:500])

    def write(message: dict) -> None:
        try:
            process.stdin.write((json.dumps(message) + "\n").encode())
            process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass

    threading.Thread(target=read_stdout, daemon=True).start()
    threading.Thread(target=read_stderr, daemon=True).start()
    write({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": INITIALIZE_PARAMS})

    while not done.wait(1.0):
        if time.monotonic() - last_output[0] > idle_timeout:
            record["error"] = f"no output for {idle_timeout:.0f}s"
            break

    record["elapsedSeconds"] = round(time.monotonic() - started, 1)
    stop(process)
    record["exitCode"] = process.returncode
    record["stderrTail"] = list(stderr_tail)
    if stdout_noise:
        record["stdoutNonJson"] = list(stdout_noise)

    if "result" in response:
        record["status"] = "ok"
        record["initialize"] = response["result"]
    elif "error" in response:
        record["error"] = f"initialize failed: {json.dumps(response['error'])}"
    elif "error" not in record:
        record["error"] = f"the agent exited (code {process.returncode}) without answering initialize"
    return record


def stop(process: subprocess.Popen) -> None:
    try:
        process.stdin.close()
    except OSError:
        pass
    for sig, wait in ((signal.SIGTERM, 5), (signal.SIGKILL, 5)):
        if process.poll() is not None:
            return
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            return
        try:
            process.wait(wait)
        except subprocess.TimeoutExpired:
            continue


def cmd_probe(args: argparse.Namespace) -> None:
    agents = read_agent_lines(args.agents)
    if args.only:
        wanted = set(args.only.split(","))
        agents = [a for a in agents if a[0] in wanted]
    if not agents:
        sys.exit("no agent to probe")
    exec_prefix = shlex.split(args.exec_prefix) if args.exec_prefix else []
    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    def run(agent: tuple[str, str]) -> dict:
        agent_id, command = agent
        print(f"[start] {agent_id}: {command}", file=sys.stderr)
        record = probe_one(agent_id, command, exec_prefix, args.idle_timeout)
        (out_dir / f"{agent_id}.json").write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"[{record['status']}] {agent_id} ({record.get('elapsedSeconds', 0)}s) {record.get('error', '')}", file=sys.stderr)
        return record

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        list(pool.map(run, agents))
    write_summary(out_dir)


# ---------------------------------------------------------------------------------------------
# summary
# ---------------------------------------------------------------------------------------------


def flag(value) -> str:
    # Same leniency as `FlexibleBooleanSerializer`: `true` or an object (`{}`) means supported.
    if value is True or isinstance(value, dict):
        return "✓"
    return "-" if value in (None, False) else f"?{json.dumps(value)}"


def summary_row(record: dict) -> list[str]:
    result = record.get("initialize") or {}
    caps = result.get("agentCapabilities") or {}
    session = caps.get("sessionCapabilities") or {}
    info = result.get("agentInfo") or {}
    if record.get("status") != "ok":
        error = (record.get("error") or "").replace("|", "\\|")
        return [record["agentId"], "ERROR", "", "", *[""] * (2 + len(SESSION_KEYS)), "", error]
    restorable = "✓" if flag(caps.get("loadSession")) == "✓" or flag(session.get("resume")) == "✓" else "-"
    others = sorted(set(session) - set(SESSION_KEYS))
    return [
        record["agentId"],
        "ok",
        str(result.get("protocolVersion", "")),
        f"{info.get('name', '')} {info.get('version', '')}".strip(),
        flag(caps.get("loadSession")),
        restorable,
        *[flag(session.get(k)) for k in SESSION_KEYS],
        ", ".join(others),
        f"auth: {len(result.get('authMethods') or [])}",
    ]


def write_summary(out_dir: Path) -> None:
    records = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(out_dir.glob("*.json"))]
    header = ["agent", "status", "protocol", "agentInfo", "loadSession", "restorable (load∨resume)",
              *SESSION_KEYS, "other sessionCapabilities", "note"]
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(summary_row(r)) + " |" for r in records]
    text = "\n".join(lines) + "\n"
    (out_dir / "SUMMARY.md").write_text(text, encoding="utf-8")
    print(text)


def cmd_summary(args: argparse.Namespace) -> None:
    write_summary(Path(args.output))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("commands", help="write the start command of every registry agent")
    p.add_argument("--registry", default=REGISTRY_URL, help="registry URL or local file")
    p.add_argument("-o", "--output", default="agents.tsv")
    p.set_defaults(func=cmd_commands)

    p = sub.add_parser("install", help="install the binary agents built for this machine and enable their lines")
    p.add_argument("--registry", default=REGISTRY_URL, help="registry URL or local file")
    p.add_argument("--agents", default="agents.tsv", help="file written by `commands`, updated in place")
    p.add_argument("--dest", default="~/agents", help="installs into <dest>/<agent id>/<version>/")
    p.add_argument("--platform", help=f"registry platform key (default: this machine, {current_platform()})")
    p.add_argument("--only", help="comma separated agent ids")
    p.add_argument("--jobs", type=int, default=4, help="downloads in parallel")
    p.set_defaults(func=cmd_install)

    p = sub.add_parser("probe", help="send initialize to every command and record the capabilities")
    p.add_argument("--agents", default="agents.tsv", help="file written by `commands` (edited or not)")
    p.add_argument("--exec-prefix", default="",
                   help='prepended to every command, e.g. "sbx exec -i my-sandbox" (split like a shell would)')
    p.add_argument("--only", help="comma separated agent ids")
    p.add_argument("--jobs", type=int, default=1, help="agents probed in parallel")
    p.add_argument("--idle-timeout", type=float, default=DEFAULT_IDLE_TIMEOUT)
    p.add_argument("-o", "--output", default="results")
    p.set_defaults(func=cmd_probe)

    p = sub.add_parser("summary", help="rebuild SUMMARY.md from the JSON files of a probe")
    p.add_argument("-o", "--output", default="results")
    p.set_defaults(func=cmd_summary)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
