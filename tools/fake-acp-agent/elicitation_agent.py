#!/usr/bin/env python3
"""ACP agent that asks `elicitation/create` questions of every shape the plugin has to draw.

A real agent only asks what its model decides to ask: Claude's `AskUserQuestion` never produces a
text field with a `format`, a number, a check box or a drop down. This script sends each of them
on request, so the form card can be looked at (and its answers read back) without depending on a
model.

Every prompt names a scenario (`text`, `number`, `all`, ...; `list` prints them). The agent sends
the matching request, waits for the answer, and writes what it received back into the transcript
as JSON, so what the card sent can be read next to the card itself.
"""

from __future__ import annotations

import json
import os
import sys
import threading
import uuid

# `slow` in a prompt delays the question by this long, so there is time to press Stop or to switch
# tabs before the form appears. `FAKE_SLOW_SECONDS` overrides it (the tests of this script use 1).
SLOW_SECONDS = float(os.environ.get("FAKE_SLOW_SECONDS", "10"))

_write_lock = threading.Lock()


def send(payload: dict) -> None:
    # The delayed question is sent from a timer thread, so two lines must never interleave.
    with _write_lock:
        sys.stdout.write(json.dumps(payload) + "\n")
        sys.stdout.flush()


def notify(method: str, params: dict) -> None:
    send({"jsonrpc": "2.0", "method": method, "params": params})


def respond(request_id, result: dict) -> None:
    send({"jsonrpc": "2.0", "id": request_id, "result": result})


# ---------------------------------------------------------------------------------------------
# Schema building blocks
# ---------------------------------------------------------------------------------------------

PREVIEW_META_KEY = "_claude/askUserQuestionOption"


def option(value: str, title: str | None = None, description: str | None = None, preview: str | None = None) -> dict:
    entry = {"const": value, "title": title or value}
    if description:
        entry["description"] = description
    if preview:
        # Where claude-agent-acp hides the `preview` of an AskUserQuestion option.
        entry["_meta"] = {PREVIEW_META_KEY: {"preview": preview}}
    return entry


def form(message: str, properties: dict, required: list | None = None, **extra) -> dict:
    schema = {"type": "object", "properties": properties}
    if required:
        schema["required"] = required
    return {"mode": "form", "message": message, "requestedSchema": schema, **extra}


def custom_answer() -> dict:
    """The free text "Other" field `claude-agent-acp` adds after every AskUserQuestion question."""
    return {
        "type": "string",
        "title": "Other",
        "description": "Type your own answer, or add a note to the option you chose above (optional).",
    }


def ask_option(label: str, description: str | None = None, preview: str | None = None) -> dict:
    entry = {"label": label}
    if description:
        entry["description"] = description
    if preview:
        entry["preview"] = preview
    return entry


def ask_question(header: str, question: str, multi: bool, options: list) -> dict:
    return {"header": header, "question": question, "multiSelect": multi, "options": options}


LANGUAGES = [
    ask_option("Kotlin", "Statically typed, runs on the JVM"),
    ask_option("Python", "Dynamically typed, batteries included"),
    ask_option("Rust", "No garbage collector, no data races"),
]

CHECKS = [
    ask_option("Unit tests", "Fast"),
    ask_option("Lint"),
    ask_option("End to end", "Slow"),
]

RUST_ERRORS = ask_question("エラー処理", "Rust でのエラーハンドリングの書き方として、どれを使いたいですか（単一選択）", False, [
    ask_option("? 演算子で伝播", "エラーを上位にそのまま返し、簡潔に書けます。", (
        "use std::fs;\nuse std::io;\n\nfn read_config(path: &str) -> Result<String, io::Error> {\n"
        " let text = fs::read_to_string(path)?;\n Ok(text)\n}")),
    ask_option("match で明示的に処理", "各エラーに対して方針を明示的に書き、振る舞いを決めます。", (
        "use std::fs;\n\nfn read_config(path: &str) -> String {\n match fs::read_to_string(path) {\n"
        " Ok(text) => text,\n Err(e) => {\n eprintln!(\"config の読み込みに失敗: {e}\");\n"
        " String::new()\n }\n }\n}")),
    ask_option("unwrap で即時停止", "プロトタイプやテストに向いていますが、本番では避けるべきです。", (
        "use std::fs;\n\nfn read_config(path: &str) -> String {\n"
        " // 失敗すると panic してプログラムが停止します\n fs::read_to_string(path).unwrap()\n}")),
    ask_option("preview なし", "この選択肢には preview がなく、箱は隠れます。"),
])

# What the model asked (the `rawInput` of the tool call) by scenario. The form is built from it the
# way claude-agent-acp does, so the record of the call and the form always agree.
ASK_QUESTIONS = {
    "ask-single": [ask_question("Language", "Which language should I use?", False, LANGUAGES)],
    "ask-multi": [ask_question("Checks", "Which checks should I run?", True, CHECKS)],
    "ask-many": [
        ask_question("Language", "Which language should I use?", False, LANGUAGES),
        ask_question("Checks", "Which checks should I run?", True, CHECKS),
    ],
    "ask-preview": [RUST_ERRORS],
}


def ask_form(questions: list) -> dict:
    """The `elicitation/create` claude-agent-acp makes of the questions of an AskUserQuestion."""
    properties = {}
    for index, question in enumerate(questions):
        options = [option(o["label"], description=o.get("description"), preview=o.get("preview")) for o in question["options"]]
        field = {"title": question["header"]}
        if len(questions) > 1:
            field["description"] = question["question"]
        if question["multiSelect"]:
            properties[f"question_{index}"] = {"type": "array", **field, "items": {"anyOf": options}}
        else:
            properties[f"question_{index}"] = {"type": "string", **field, "oneOf": options}
        properties[f"question_{index}_custom"] = custom_answer()
    message = questions[0]["question"] if len(questions) == 1 else "Please answer the following questions."
    return form(message, properties)


COLORS = ["Red", "Orange", "Yellow", "Green", "Blue", "Indigo", "Violet", "Black"]

TEXT_FIELDS = {
    "plain": {"type": "string", "title": "Plain text", "description": "Anything goes", "default": "hello"},
    "short": {"type": "string", "title": "Short (3 to 8 characters)", "minLength": 3, "maxLength": 8},
    "email": {"type": "string", "title": "Email", "format": "email", "default": "octocat@github.com"},
    "uri": {"type": "string", "title": "URI", "format": "uri", "description": "https://example.com"},
    "date": {"type": "string", "title": "Date", "format": "date", "description": "2026-11-02"},
    "date-time": {"type": "string", "title": "Date and time", "format": "date-time", "description": "2026-11-02T09:30:00Z"},
    "pattern": {"type": "string", "title": "Ticket", "pattern": "^[A-Z]+-[0-9]+$", "description": "ABC-123"},
}

NUMBER_FIELDS = {
    "age": {"type": "integer", "title": "Age (18 to 99)", "minimum": 18, "maximum": 99, "default": 30},
    "ratio": {"type": "number", "title": "Ratio (0 to 1)", "minimum": 0, "maximum": 1, "description": "Decimals are fine"},
    "free": {"type": "number", "title": "Any number"},
}

BOOLEAN_FIELDS = {
    "on": {"type": "boolean", "title": "Defaults to on", "default": True, "description": "A check box"},
    "off": {"type": "boolean", "title": "Defaults to off", "default": False},
    "bare": {"type": "boolean", "title": "No default"},
}

SELECT_FIELDS = {
    "plain": {"type": "string", "title": "Untitled enum", "enum": ["alpha", "beta", "gamma"], "default": "beta"},
    "titled": {
        "type": "string",
        "title": "Titled oneOf",
        "oneOf": [option("#ff0000", "Red", "Warm"), option("#00ff00", "Green", "Natural"), option("#0000ff", "Blue")],
        "default": "#00ff00",
    },
}

MULTI_FIELDS = {
    "plain": {
        "type": "array", "title": "Untitled enum (1 to 2)", "minItems": 1, "maxItems": 2,
        "items": {"type": "string", "enum": ["Red", "Green", "Blue"]}, "default": ["Red"],
    },
    "titled": {
        "type": "array", "title": "Titled anyOf", "description": "Pick any",
        "items": {"anyOf": [option("a", "Option A", "First one"), option("b", "Option B"), option("c", "Option C", "Third one")]},
    },
}


def scenario_long() -> dict:
    long_text = "This is a very long sentence that has to wrap inside the card instead of pushing it wider than the tool window. " * 3
    return form(long_text, {
        "q": {
            "type": "string", "title": "A title that is long enough to need more than one line in a narrow tool window",
            "description": long_text,
            "oneOf": [option("x" * 80, "A" * 120, long_text), option("short", "Short")],
        },
    })


def scenario_dropdown() -> dict:
    return form("More than six options become a drop down.", {
        "optional": {"type": "string", "title": "Optional colour", "enum": COLORS},
        "required": {"type": "string", "title": "Required colour", "enum": COLORS, "default": "Green"},
    }, required=["required"])


def scenario_required() -> dict:
    return form("Fields marked with * must be filled in.", {
        "name": {"type": "string", "title": "Name"},
        "color": {"type": "string", "title": "Colour", "enum": ["Red", "Green", "Blue"]},
        "tags": {"type": "array", "title": "Tags", "minItems": 1, "items": {"type": "string", "enum": ["x", "y"]}},
        "note": {"type": "string", "title": "Note (optional)"},
    }, required=["name", "color", "tags"])


def scenario_all() -> dict:
    properties = {}
    for prefix, fields in (("text", TEXT_FIELDS), ("number", NUMBER_FIELDS), ("bool", BOOLEAN_FIELDS),
                           ("select", SELECT_FIELDS), ("multi", MULTI_FIELDS)):
        for name, schema in fields.items():
            properties[f"{prefix}_{name}"] = schema
    properties["dropdown"] = {"type": "string", "title": "Drop down", "enum": COLORS}
    return form("Every kind of field in one form.", properties, required=["text_plain", "select_plain"])


SCENARIOS = {
    # Shapes of the Claude adapter's AskUserQuestion tool.
    "ask-single": lambda: ask_form(ASK_QUESTIONS["ask-single"]),
    "ask-multi": lambda: ask_form(ASK_QUESTIONS["ask-multi"]),
    "ask-many": lambda: ask_form(ASK_QUESTIONS["ask-many"]),
    "ask-preview": lambda: ask_form(ASK_QUESTIONS["ask-preview"]),
    # Only the record of the call, as a restored session replays it: nothing is asked.
    "ask-record": lambda: ask_form(ASK_QUESTIONS["ask-many"]),
    # The call is first reported with an empty input, which only arrives in full afterwards.
    "ask-late": lambda: ask_form(ASK_QUESTIONS["ask-many"]),
    # One kind of field at a time.
    "text": lambda: form("Text fields and their formats.", TEXT_FIELDS),
    "number": lambda: form("Number fields and their bounds.", NUMBER_FIELDS),
    "boolean": lambda: form("Check boxes.", BOOLEAN_FIELDS),
    "select": lambda: form("Single select fields.", SELECT_FIELDS),
    "multi": lambda: form("Multi select fields.", MULTI_FIELDS),
    "dropdown": lambda: scenario_dropdown(),
    "required": lambda: scenario_required(),
    "all": lambda: scenario_all(),
    # Layout and edge cases.
    "empty": lambda: form("Nothing to fill in, only a decision.", {}),
    "long": lambda: scenario_long(),
    "tool-call": lambda: form("Tied to a tool call.", {"q": {"type": "string", "enum": ["yes", "no"]}}, toolCallId="call_1"),
    "string-id": lambda: form("The request id is a string.", {"q": {"type": "string", "enum": ["yes", "no"]}}),
    "twice": lambda: form("First of two questions.", {"q": {"type": "string", "enum": ["one", "two"]}}),
    "hang": lambda: form("Press Stop in the tool window: this form must lock as Cancelled.", {"q": {"type": "string"}}),
    # Requests the plugin has to refuse with -32602.
    "bad-type": lambda: form("A property type the form cannot draw.", {"x": {"type": "object"}}),
    "bad-items": lambda: form("An array that is not an enumeration.", {"x": {"type": "array", "items": {"type": "string"}}}),
    "no-mode": lambda: {"message": "mode is missing", "requestedSchema": {"type": "object", "properties": {}}},
    "url": lambda: {
        "mode": "url", "elicitationId": "e-1", "url": "https://example.com/connect", "message": "URL mode is not advertised.",
    },
}

RECORD_ONLY = {"ask-record"}
LATE_INPUT = {"ask-late"}
EXPECT_REFUSED = {"bad-type", "bad-items", "no-mode", "url"}


class FakeAgent:
    def __init__(self) -> None:
        self.session_id = ""
        self.client_capabilities: dict = {}
        self.prompt_id = None
        self.next_request_id = 2000
        self.waiting: dict = {}
        self.delayed: threading.Timer | None = None

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
            self.client_capabilities = (message.get("params") or {}).get("clientCapabilities") or {}
            respond(message["id"], {
                "protocolVersion": 1,
                "agentInfo": {"name": "fake-elicitation-agent", "version": "1.0.0"},
                "agentCapabilities": {"loadSession": False},
                "authMethods": [],
            })
        elif method == "session/new":
            self.session_id = str(uuid.uuid4())
            respond(message["id"], {"sessionId": self.session_id})
        elif method == "session/prompt":
            self.start_turn(message)
        elif method == "session/cancel":
            if self.delayed is not None:
                self.delayed.cancel()
                self.delayed = None
            self.finish_turn("cancelled")
        elif "id" in message:
            respond(message["id"], {})

    def say(self, text: str) -> None:
        notify("session/update", {
            "sessionId": self.session_id,
            "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": text}},
        })

    def start_turn(self, message: dict) -> None:
        self.prompt_id = message["id"]
        text = " ".join(block.get("text", "") for block in (message.get("params") or {}).get("prompt", [])
                        if isinstance(block, dict)).lower()
        name = next((key for key in sorted(SCENARIOS, key=len, reverse=True) if key in text), None)
        if name in RECORD_ONLY:
            # A replayed history has no client to ask: the call is just reported, finished.
            self.report_ask_call(name, "pending")
            self.report_ask_call(name, "completed", answer="User has answered your questions.")
            self.finish_turn("end_turn")
            return
        if name is None:
            self.say("Say one of these (anywhere in the prompt; add `slow` to wait "
                     f"{SLOW_SECONDS:g} seconds before the question):\n\n" + "\n".join(f"- `{key}`" for key in SCENARIOS))
            self.finish_turn("end_turn")
            return
        # An agent must not ask for a mode the client did not advertise.
        # `{}` is how a mode is advertised, so only an absent or null field means "unsupported".
        if (self.client_capabilities.get("elicitation") or {}).get("form") is None:
            self.say(f"`{name}`: the client did not advertise `elicitation.form`, so nothing is asked.\n\n"
                     f"`clientCapabilities`: `{json.dumps(self.client_capabilities)}`")
            self.finish_turn("end_turn")
            return
        if "slow" in text:
            self.say(f"Scenario `{name}`: asking in {SLOW_SECONDS:g} seconds. Press Stop now to cancel before the "
                     "form appears, or switch to another tab and back.")
            self.delayed = threading.Timer(SLOW_SECONDS, self.ask_after_delay, args=(name,))
            self.delayed.start()
            return
        self.ask(name, 1)

    def ask_after_delay(self, name: str) -> None:
        self.delayed = None
        if self.prompt_id is not None:
            self.ask(name, 1)

    def ask_call_id(self, name: str) -> str:
        return f"toolu_{name}_{self.next_request_id + 1}"

    def report_ask_call(self, name: str, status: str, answer: str | None = None, late: bool = False) -> None:
        """The `tool_call` (and its update) claude-agent-acp reports for an AskUserQuestion."""
        questions = ASK_QUESTIONS[name if name in ASK_QUESTIONS else "ask-many"]
        call_id = self.ask_call_id(name)
        title = questions[0]["question"] if len(questions) == 1 else "Asking for your input"
        shown = [{"type": "content", "content": {"type": "text", "text": q["question"]}} for q in questions]
        if status == "pending":
            session_update = {
                "sessionUpdate": "tool_call", "toolCallId": call_id, "title": title, "kind": "other",
                "status": "pending", "content": shown, "rawInput": {} if late else {"questions": questions},
            }
        else:
            session_update = {
                "sessionUpdate": "tool_call_update", "toolCallId": call_id, "status": status,
                "content": [{"type": "content", "content": {"type": "text", "text": answer or ""}}],
            }
        notify("session/update", {"sessionId": self.session_id, "update": session_update})

    def ask(self, name: str, round_number: int) -> None:
        params = SCENARIOS[name]()
        params["sessionId"] = self.session_id
        call_id = None
        if name in ASK_QUESTIONS or name in LATE_INPUT:
            call_id = self.ask_call_id(name)
            params["toolCallId"] = call_id
            self.report_ask_call(name, "pending", late=name in LATE_INPUT)
            if name in LATE_INPUT:
                # The input of the call completes after the call was first reported.
                notify("session/update", {"sessionId": self.session_id, "update": {
                    "sessionUpdate": "tool_call_update", "toolCallId": call_id, "status": "pending",
                    "rawInput": {"questions": ASK_QUESTIONS["ask-many"]},
                }})
        self.next_request_id += 1
        request_id = f"elicitation-{self.next_request_id}" if name == "string-id" else self.next_request_id
        self.waiting[request_id] = (name, round_number, call_id)
        self.say(f"Scenario `{name}`" + (f" (question {round_number})" if name == "twice" else "") + ":")
        send({"jsonrpc": "2.0", "id": request_id, "method": "elicitation/create", "params": params})

    def handle_response(self, message: dict) -> None:
        key = message.get("id")
        if key not in self.waiting:
            return
        name, round_number, call_id = self.waiting.pop(key)
        error = message.get("error")
        if call_id:
            result = message.get("result") or {}
            accepted = result.get("action") == "accept"
            content = result.get("content") or {}
            answer = ("User has answered your questions: " + "; ".join(f'"{k}"="{v}"' for k, v in content.items())
                      if accepted else "The user did not answer.")
            notify("session/update", {"sessionId": self.session_id, "update": {
                "sessionUpdate": "tool_call_update", "toolCallId": call_id,
                "status": "completed" if accepted else "failed",
                "content": [{"type": "content", "content": {"type": "text", "text": answer}}],
            }})
        if error:
            verdict = "as expected" if name in EXPECT_REFUSED else "UNEXPECTED"
            self.say(f"Refused {verdict}: `{error.get('code')}` {error.get('message')}")
        elif name in EXPECT_REFUSED:
            self.say("UNEXPECTED: the plugin answered instead of refusing:\n\n```json\n"
                     + json.dumps(message.get("result"), indent=2) + "\n```")
        else:
            self.say("The plugin answered:\n\n```json\n" + json.dumps(message.get("result"), indent=2) + "\n```")
        if name == "twice" and round_number == 1:
            self.ask(name, 2)
            return
        self.finish_turn("end_turn")

    def finish_turn(self, stop_reason: str) -> None:
        if self.prompt_id is None:
            return
        respond(self.prompt_id, {"stopReason": stop_reason})
        self.prompt_id = None


if __name__ == "__main__":
    FakeAgent().run()
