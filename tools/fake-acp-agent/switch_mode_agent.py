#!/usr/bin/env python3
"""Minimal ACP agent that only knows how to ask for a mode switch.

Waiting for a real agent to leave its plan mode is not a reproducible way to look at the
`switch_mode` card, so this script plays the part: it speaks just enough of ACP v1 over stdio to
get a session going and then, on every prompt, asks for a mode switch exactly the way the session
modes documentation spells it out — options that are the modes themselves and a plan written as a
bare `{"type": "text"}` content block.

Point the plugin at it (see README.md), send any prompt, and the transcript shows the real card.
"""

from __future__ import annotations

import json
import sys
import uuid

MODES = [
    {"id": "architect", "name": "Architect", "description": "Plan before touching anything"},
    {"id": "code", "name": "Code", "description": "Write the code"},
]

PLAN = """## Implementation Plan

1. Add the `switch_mode` card to the transcript
2. Render the plan as Markdown instead of raw JSON
3. Follow the mode selector when the agent reports the switch

Ready to start?
"""

TOOL_CALL_ID = "call_switch_mode"


def send(payload: dict) -> None:
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def notify(method: str, params: dict) -> None:
    send({"jsonrpc": "2.0", "method": method, "params": params})


def respond(request_id, result: dict) -> None:
    send({"jsonrpc": "2.0", "id": request_id, "result": result})


def session_update(session_id: str, update: dict) -> None:
    notify("session/update", {"sessionId": session_id, "update": update})


class FakeAgent:
    def __init__(self) -> None:
        self.session_id = ""
        self.current_mode = MODES[0]["id"]
        # `session/prompt` is answered only after the permission round trip is finished.
        self.pending_prompt_id = None
        self.permission_request_id = 1000

    def run(self) -> None:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            self.handle(message)

    def handle(self, message: dict) -> None:
        method = message.get("method")
        if method is None:
            self.handle_response(message)
        elif method == "initialize":
            respond(message["id"], {
                "protocolVersion": 1,
                "agentInfo": {"name": "fake-switch-mode-agent", "version": "1.0.0"},
                "agentCapabilities": {"loadSession": False},
                "authMethods": [],
            })
        elif method == "session/new":
            self.session_id = str(uuid.uuid4())
            respond(message["id"], {
                "sessionId": self.session_id,
                "modes": {"currentModeId": self.current_mode, "availableModes": MODES},
            })
        elif method == "session/set_mode":
            self.current_mode = message.get("params", {}).get("modeId", self.current_mode)
            respond(message["id"], {})
        elif method == "session/prompt":
            self.start_turn(message)
        elif method == "session/cancel":
            self.finish_turn("cancelled")
        elif "id" in message:
            respond(message["id"], {})

    def start_turn(self, message: dict) -> None:
        self.pending_prompt_id = message["id"]
        session_update(self.session_id, {
            "sessionUpdate": "agent_message_chunk",
            "content": {"type": "text", "text": "Here is what I would like to do next."},
        })
        self.permission_request_id += 1
        send({
            "jsonrpc": "2.0",
            "id": self.permission_request_id,
            "method": "session/request_permission",
            "params": {
                "sessionId": self.session_id,
                "toolCall": {
                    "toolCallId": TOOL_CALL_ID,
                    "title": "Ready for implementation",
                    "kind": "switch_mode",
                    "status": "pending",
                    # A bare content block, as written in the session modes documentation.
                    "content": [{"type": "text", "text": PLAN}],
                },
                "options": [
                    {"optionId": "code", "name": "Yes, and auto-accept edits", "kind": "allow_always"},
                    {"optionId": "ask", "name": "Yes, and manually approve edits", "kind": "allow_once"},
                    {"optionId": "reject", "name": "No, keep planning", "kind": "reject_once"},
                ],
            },
        })

    def handle_response(self, message: dict) -> None:
        if message.get("id") != self.permission_request_id:
            return
        outcome = (message.get("result") or {}).get("outcome") or {}
        option_id = outcome.get("optionId")
        if outcome.get("outcome") != "selected" or option_id == "reject":
            self.report_tool_call("failed")
            self.finish_turn("end_turn")
            return

        self.current_mode = "code" if option_id in ("code", "ask") else option_id
        self.report_tool_call("completed")
        # The documentation spells the notification with `modeId`, the schema with
        # `currentModeId`: this agent uses the documented one on purpose.
        session_update(self.session_id, {"sessionUpdate": "current_mode_update", "modeId": self.current_mode})
        self.finish_turn("end_turn")

    def report_tool_call(self, status: str) -> None:
        session_update(self.session_id, {
            "sessionUpdate": "tool_call",
            "toolCallId": TOOL_CALL_ID,
            "title": "Ready for implementation",
            "kind": "switch_mode",
            "status": status,
            "rawInput": {"mode": self.current_mode},
            "content": [{"type": "text", "text": PLAN}],
        })

    def finish_turn(self, stop_reason: str) -> None:
        if self.pending_prompt_id is None:
            return
        respond(self.pending_prompt_id, {"stopReason": stop_reason})
        self.pending_prompt_id = None


if __name__ == "__main__":
    FakeAgent().run()
