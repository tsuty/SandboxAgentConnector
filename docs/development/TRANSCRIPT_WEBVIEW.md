# Transcript Web View — Design Note

Status: implemented (see the phases at the end).
Scope: replaces the Swing rendering of the transcript (`ChatLogPanel` and everything it draws) with
a single embedded browser (JCEF) driven by a JSON event protocol.

---

## 1. Why

The transcript used to be drawn with `JEditorPane` + `HTMLEditorKit` (`MarkdownView.html`,
`MarkdownPane`). That kit only understands a small subset of CSS 1, which is why the Markdown
styling never matched the IDE:

| | IDE Markdown preview | transcript (before) |
| --- | --- | --- |
| parser | `org.intellij.markdown`, GFM | same |
| HTML | `MarkdownUtil.generateMarkdownHtml` | `HtmlGenerator` |
| **renderer** | **JCEF (Chromium)** | **`JEditorPane` + `HTMLEditorKit`** |
| styles | `default.css` (GitHub-like, ~9 KB) + theme variables | ~30 hand written lines |

Measured facts about `HTMLEditorKit` that make the old approach a dead end:

* `border-collapse` is not part of `javax.swing.text.html.CSS$Attribute` and is dropped.
* `border` on `td` / `th` is never painted; table rules are only drawn from the HTML `border` and
  `cellspacing` attributes.
* `border-radius`, `box-shadow`, `nth-child`, `rem`, `:hover`, flexbox are all ignored.

So the goal ("the same styling as the IDE Markdown preview") cannot be reached by tuning CSS. The
renderer has to change.

A second motivation is portability: with the presentation living in HTML/CSS/JS, the same
transcript can later be hosted by a VS Code webview or a standalone app, and only a thin host
adapter has to be rewritten.

## 2. Decisions

| Topic | Decision |
| --- | --- |
| Browser instances | **one per `TranscriptPanel`** (= one per tab of the tool window, up to 10). A session switch inside a tab swaps the document content, it never recreates the browser. |
| Tabs in the background | a hidden tab keeps its connection and its event log, but **holds the events back** and hands them over in one batch once it is shown again; the browser is told its size and screen at that moment as well (see § 10). |
| Rendering mode | **off-screen rendering (OSR)**, so IDE popups (`SlashCommandPopup`, `AttachFilePopup`, `SessionHistoryPopup`, `FlatControls`) can never be hidden behind a native child window. |
| CSS | **bundled**, derived from the GitHub-like Markdown stylesheet; no dependency on internal resources of the bundled Markdown plugin. |
| Markdown → HTML | **Kotlin side**, `org.intellij.markdown` GFM (reuses `MarkdownView.toHtmlBody`). |
| HTML scope | the **whole transcript**: prose, tool call cards, tool call groups, diff lines, anchors, choices, and the permission card (kept inline). |
| Stays Swing | `PromptInputPanel`, toolbars, `ThinkingStatusPanel`, `ContextChipsPanel`, all popups. |
| No JCEF available | a plain text fallback view plus a notice; the Swing renderer is **not** kept. |
| In-transcript search | own implementation (search bar inside the document). |
| Local images | not needed; files are opened in the IDE through `VirtualFileManager` / the platform diff viewer. |
| Navigation | **none**: the document is the only page the browser may show. A link is cancelled and handed to the IDE instead (`openUrl` / `openFile`), and a `target="_blank"` never opens a native window. |
| A document that was lost | the host keeps the event log of the session and loads the document again, filling it from that log — a browser showing something else where the transcript was is a tool window that cannot be used any more. |

## 3. Structure

```text
                      +---------------------------- host (IntelliJ) -----------------------------+
 SandboxAgentService -> | SandboxAgentMainPanel -> TranscriptPanel -> TranscriptView               |
   (ACP events)        |                                              |  JcefTranscriptView (OSR)|
                       |                                              |  PlainTextTranscriptView |
                       +--------------------------------|-------------------------|--------------+
                                                        | events JSON            | action JSON
                                                        v                        ^
                       +---------------------------- transcript-web -------------+--------------+
                       | transcript.html (CSP) + transcript.css + transcript.js                 |
                       +------------------------------------------------------------------------+
```

```text
src/main/kotlin/com/tsuty/sandboxagent/ui/transcript/
    TranscriptEvent.kt          host -> web protocol (@Serializable, host independent)
    TranscriptAction.kt         web -> host protocol (@Serializable, host independent)
    TranscriptHtml.kt           Markdown -> sanitized HTML (pure, unit tested)
    TranscriptToolCall.kt       ToolCall -> TranscriptEvent.ToolCall (pure, unit tested)
    CodeHighlighter.kt          code fence -> hl-* token spans, IDE lexer (IDE only, degrades)
    TranscriptIcons.kt          icons/transcript/*.svg -> one <symbol> sprite (pure, unit tested)
    TranscriptDocument.kt       transcript.html + inlined css/js/sprite + CSP nonce (pure, tested)
    TranscriptView.kt           interface both views implement
    JcefTranscriptView.kt       JBCefBrowser + JBCefJSQuery bridge (IDE only)
    PlainTextTranscriptView.kt  fallback when JCEF is unavailable (IDE only)
    ThemeTokens.kt              IDE theme -> CSS custom properties (IDE only)
    TranscriptPanel.kt          transcript facade used by SandboxAgentMainPanel (IDE only)
src/main/resources/transcript/
    transcript.html transcript.css transcript.js
src/main/resources/icons/transcript/
    <name>.svg                  one drawing per icon (see ICONS.md)
```

Only `JcefTranscriptView`, `PlainTextTranscriptView`, `ThemeTokens`, `CodeHighlighter` and
`TranscriptPanel` touch IDE APIs, and `CodeHighlighter` gives up instead of failing when there is
no IDE to ask. Everything else is plain Kotlin and covered by unit tests, which also keeps the
tests runnable headless (where `JBCefApp.isSupported()` is `false`).

## 4. Protocol — host to web

A batch of events is handed to the document as one call:

```js
window.transcript.apply(eventsJsonArray)
```

Events carry the `id` of the entry they address, so the document is patched instead of rebuilt.
Tool call ids are the ACP `toolCallId`s.

Two calls of the host are *not* events, because they change nothing about the transcript and only
move it: `window.transcript.showSearch()` opens the search bar, and
`window.transcript.scrollToBottom()` jumps to the newest entry — which is what sending a prompt
does (see § 7).

Because of that, the events *are* the transcript: a fresh document without them is empty, and
nothing above `JcefTranscriptView` keeps the history. The view therefore keeps the log of
everything it sent since the last `reset` — it is the queue while the page is still loading (and
the window a streamed answer is coalesced in, 33 ms) and the memory a document that had to be
loaded again is filled from. What a later event makes stale is dropped from it: only the current
`theme` and the current `agent` are kept, a `reset` drops the entries before it (those two excepted
— neither the colors nor who the agent is are part of what a reset clears), the chunks of a stream
go once its `blockHtml` arrives, and a `toast` is dropped as soon as it was shown. A replay starts
with a `reset` of its own, so running it against a document that is not empty rebuilds the
transcript instead of doubling it.

| Event | Payload | Meaning |
| --- | --- | --- |
| `reset` | `sessionId` | drop every entry (session switch, reconnect) |
| `theme` | `tokens`, `dark` | set CSS custom properties; the DOM is kept, so scroll position and folding survive a theme change |
| `agent` | `name`, `iconSymbol` | title of the agent blocks and the `ra-icon-agent` symbol of the sprite, as the selected ACP agent presents itself (see `ICONS.md`) |
| `block` | `id`, `role`, `html`, `streaming`, `choices` | add a transcript block |
| `stream` | `id`, `text` | append raw text to a streaming block (no Markdown while it is incomplete) |
| `blockHtml` | `id`, `html`, `choices` | replace the body of a block with its rendered Markdown |
| `attachments` | `id`, `chips` | add attachment chips to a `USER` block (a replay reports them after the prompt) |
| `group` | `id`, `timed`, `closed`, `elapsedMs` | open / close a tool call group; the label (`Working… 12s`, `Worked 12s`) is rendered and ticked by the web side |
| `toolCall` | see below | add or update a tool call card (upsert by `id`) |
| `permission` | `id`, `toolCallId`, `headline`, `bodyHtml`, `flat`, `options` | inline approval card; `toolCallId` is what the `flat` diff of the card is opened by (`id` names the request, not a tool call) |
| `permissionResolved` | `id`, `status` | disable the buttons, show what was picked |
| `turnEnd` | `id`, `targetId` | anchor the "scroll to the start of the turn" action to block `id` |
| `toast` | `text` | show a message over the bottom edge for a moment |

`role` is `USER`, `AGENT`, `THOUGHT`, `PLAN`, `SYSTEM`, `COMPLETION` or `ERROR`, and it is what
decides the frame, the icon, the title and the quiet style of a block — the document knows the
seven kinds of block, the host does not describe them.

The elapsed seconds of a group are counted by the document itself, so no clock is shared: `timed`
says whether the run is happening now (a replayed history is not) and `elapsedMs` is its final
duration. An attachment chip carries the `key` of its attachment instead of a path, because
following it is the host's business (see `openAttachment` below).

The `tokens` of a `theme` event are the CSS custom properties `transcript.css` is written against
(`--ra-bg`, `--ra-fg`, `--ra-border`, `--ra-code-bg`, …). Two of them are font *lists*, not names:
the IDE draws its UI with a font the operating system may not offer at all (`.AppleSystemUIFont` on
macOS is not even a legal CSS identifier, and the editor font is usually JetBrains Mono, which
ships inside the IDE and is registered with the JVM only). A single unknown name makes Chromium
fall back to *its* default family, which is a serif one — the transcript would render in Times /
Mincho, code blocks included. `ThemeTokens.fontStack` therefore asks for the IDE font first and
names the generic families (`system-ui`, `ui-monospace`, `monospace`, …) behind it.

The *sizes* are not read from the IDE at all. The transcript is read like a document, not like a
tool bar, and the UI font size of the IDE differs wildly between platforms — so the transcript
carries one font size of its own, `transcriptFontSize` of the plugin settings, and every other size
is derived from it: `--ra-mono-size` is the base size (a fence lines up with the prose around it)
and `--ra-small-size` is `ThemeTokens.SMALL_RATIO` of it (the quiet rows: timestamps, tool call
lines, captions). What has to keep its proportion to the text is stated in `em` in
`transcript.css` — the icons (`1.25em`, 16 px at the default base size), the indent of a folded
body and the heading sizes — while borders, radii and the paddings of a block stay in px.
Applying the settings publishes `SandboxAgentSettingsBridge.TOPIC`, which `TranscriptPanel` answers
with a fresh `theme` event — like a look and feel change, it only resets the custom properties, so
the scroll position and every folded entry survive.

The same event carries `--ra-hl-*`, the colors of the code token classes. A fenced code block is
lexed on the host by `CodeHighlighter` with the lexer the IDE would use for the language the fence
names (its file type is resolved from the fence info, with the usual aliases: `bash` → `sh`,
`python` → `py`, …), and every token leaves as a `<span class="hl-keyword">` — the browser only
applies colors. A language no file type is registered for, and a document rendered without an IDE
at all (which is where the tests run), keeps the plain escaped text.

Tool call payload (the fields are what `ToolCallPresentation` already computes):

```jsonc
{
  "type": "toolCall",
  "id": "call_1", "groupId": "g3",
  "kind": "edit", "statusLabel": "", "failed": false,
  "anchor": "rg --files-with-matches TODO",
  "flat": {                       // null when the card is a foldable anchor
    "prefix": "Edit", "separator": "→", "expandable": false,
    "targets": [{ "text": "Foo.kt  +3 / -1", "path": "/w/Foo.kt", "line": null,
                  "url": null, "diffIndex": 0 }]
  },
  "bodyHtml": "<pre>…</pre>",     // folded away details
  "switchMode": null              // { "headline": …, "modeId": …, "planHtml": … }
}
```

## 5. Protocol — web to host

One `JBCefJSQuery` carries a JSON object; the host dispatches on its `type`:

`ready`, `openFile{path,line}`, `openDiff{toolCallId,diffIndex}`, `openUrl{url}`,
`openAttachment{key}`, `copyBlock{id}`, `copyText{text}`, `submitChoices{id,markdown}`,
`permission{id,optionId}`.

`openFile` / `openDiff` reuse `ToolCallPresentation.openFile` / `openFullDiff`, so the IDE side of
the integration (editor navigation, platform diff viewer, `AgentWorkspaceGuard` refresh) is kept
as it is. `openAttachment` goes through `PromptAttachmentNavigation`, which is what makes a
selection chip show the text that was sent instead of the file as it is now. `copyBlock` names a
block instead of carrying its text: the Markdown source the agent wrote stays on the host.
`copyText` is the other way round, and it exists for the copy button every code block of a rendered
answer carries in its bottom right corner: the code only exists in the document at that point, so it
is sent along. The button hangs over a wrapper around the `pre` rather than inside it, because a
`pre` scrolls sideways and would carry the button away with the code.

Every copy is confirmed the same way: the host writes the clipboard and answers with a `toast`
(`Copied to clipboard`), which the document fades in over its bottom edge for 1.6 s and moves above
the search bar while that one is open. The confirmation is deliberately not given by the button
itself — the clipboard belongs to the host, so only the host knows that the copy went through, and
routing it through an event means one mechanism serves the message copy, the code block copy and
whatever copies come later.

## 6. Security

`HtmlGenerator` passes inline HTML from the Markdown source straight through. Under
`HTMLEditorKit` that was harmless; in Chromium it executes. Agent answers and tool output are
untrusted input, so three independent barriers are used:

1. **CSP** in `transcript.html`:
   `default-src 'none'; style-src 'nonce-…'; script-src 'nonce-…'; img-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'`.
   The bundled script and stylesheet are the only ones allowed to run; nothing can be loaded from
   the network.
2. **Sanitizing in Kotlin** (`TranscriptHtml`): a tag / attribute allow list that drops `script`,
   `iframe`, `style`, every `on*` handler and `javascript:` URLs. It is a pure function and is
   unit tested.
3. **No navigation** (`JcefTranscriptView`): `CefRequestHandler.onBeforeBrowse` cancels every
   navigation that would leave the document and reports it as an action instead, and
   `CefLifeSpanHandler.onBeforePopup` keeps a `target="_blank"` from opening a window of its own.
   A link of the transcript is opened by the IDE — an `http(s)` / `mailto` URL through
   `BrowserUtil.browse`, a `file:` URL through `ToolCallPresentation.openFile` — which is where
   the tool call rows already send their targets.

`loadHTML` documents have an opaque origin, so ES modules cannot be used: `transcript.js` and
`transcript.css` are inlined into the document as one classic script and one style element. That
also removes the need for a scheme handler or a local server.

The same opaque origin is why a **path is not a link** here. `loadHTML` serves the document as
`file:///jbcefbrowser/<random>#url=…`, so `[ToDo](docs/ToDo.md)` resolves against *that* instead
of the project: following it loaded `file:///jbcefbrowser/docs/ToDo.md`, which does not exist, and
Chromium replaced the whole transcript with its own error page (`ERR_FILE_NOT_FOUND`) — a page
with no bridge, no renderer and no way back. `TranscriptHtml` therefore drops the `href` / `src`
of every URL without a scheme (a fragment excepted, which never leaves the document), so a path
an agent wrote stays the text it is; the files of the project are reached through the tool call
rows. `JcefTranscriptView` watches the loads on top of that: a load end for anything but the
document URL, and every load error, is answered by loading the document again and replaying the
log (capped at three attempts in a row, so a document that cannot be loaded is not loaded
forever). A renderer process that terminated (`CefRequestHandler.onRenderProcessTerminated` — a
crash, or the OS reclaiming memory) is answered the same way, since it leaves the last frame on
screen and never reports a load end; and a tab that is brought to the front after the attempts ran
out gets three fresh ones.

## 7. Behaviour kept from the Swing implementation

* Streaming shows plain text and switches to rendered Markdown when the block is finished.
* Tool calls between two conversation blocks are folded into one group; the group shows
  `Working… 12s` with a spinner while it runs and `Worked 12s` once closed. A replayed history
  reports no start time, so it only shows `Worked`.
* Folding an entry keeps the clicked line where it is on screen; appending keeps the transcript
  pinned to the bottom only when it already was at the bottom.
* Sending a prompt scrolls down there first (`SandboxAgentMainPanel.submitPrompt` ->
  `TranscriptPanel.scrollToBottom`), so a reader who had scrolled up into the history is taken to
  the turn they just started, and the pinning above follows it from there on. While there is
  anything below the viewport, a `down.svg` button floats over the bottom right corner and jumps
  to the newest entry; it sits above the search bar the same way the toast does.
* GFM task lists become check boxes with a `Send` action, except in read-only blocks (`PLAN`).
* `Copy` on a message copies the Markdown source the agent wrote.
* Icons are svg files under `icons/transcript/`, inlined as one `<symbol>` sprite and referred to
  by name (`<use href="#ra-icon-execute">`); `transcript.js` holds no drawing of its own, and the
  color follows the theme through `currentColor`. The rules for a drawing, and which of the older
  Swing icons are still needed, are in `ICONS.md`.
* A tool call is one line and stays one line: the row does not wrap (an icon pushed onto a row of
  its own turns a quiet log into a wall of text), whatever does not fit is cut off with an
  ellipsis, and the full text is in the tooltip.
* A finished turn gets a "scroll to the start of the turn" action anchored to its last block.

## 8. What is given up

* IDE-wide `Ctrl+F`, Swing text selection and the screen reader support of Swing components.
  Search is reimplemented inside the document; selection and copy are provided by the browser and
  by explicit `Copy` actions.
* `JBCefApp.isSupported() == false` environments (runtime without JCEF, registry
  `ide.browser.jcef.enabled=false`, headless tests, some Remote Development setups) show the plain
  text fallback plus a notice. A permission request cannot be answered there, which the notice
  spells out.
* `View Page Source` of the browser context menu: the document is handed to Chromium as a string
  (`loadHTML`), and `view-source:` asks the network stack for the URL again — which yields nothing
  here, so the entry only ever looked broken. `JcefTranscriptView` removes it
  (`CefMenuModel.MenuId.MENU_ID_VIEW_SOURCE`) and keeps the rest of the default menu, `Print…`
  included. The markup is still readable while working on the transcript: an **internal mode** IDE
  (`-Didea.is.internal=true`, which `runIde` sets) gets `Show Page Source` instead, which
  serializes the live DOM and opens it in a read only, in memory editor tab
  (`TranscriptPanel.showPageSource`). A released IDE shows neither entry.

## 9. Phases

All of them are implemented; the table is kept as the record of what each phase had to achieve.

| Phase | Content | Done when |
| --- | --- | --- |
| P0 | protocol, `TranscriptHtml` (+ sanitizing), `ThemeTokens`, document template, `JcefTranscriptView` with the bridge | empty transcript renders with theme colors, bridge round trip works |
| P1 | prose blocks (user / agent stream / thought / plan / notices), bottom pinning | a conversation reads end to end, tables and code fences look like the IDE preview |
| P2 | tool calls: groups, anchors, flat entries, `switch_mode`, open file / diff, `Copy` | no information lost against the Swing cards |
| P3 | interaction: choices, inline permission card | approvals and choices are answered inside the document |
| P4 | search bar, fallback view, removal of the Swing renderer | `MarkdownPane`, `ToolCallCard`, `ToolCallGroupPanel`, `ToolCallAnchor`, `InlinePermissionCard`, `ChatLogPanel` are gone |

Reused as-is: `MarkdownView.segments` / `toHtmlBody` / `taskListItems` / `choicesMarkdown`,
`ToolCallPresentation`, `UnifiedDiffSupport`, and the file / diff opening helpers.

## 10. Tabs and their browsers

### 10.1 The problem

Every tab of the tool window (Ph2 §4) owns a `SandboxAgentMainPanel`, and with it a
`TranscriptPanel` and a browser of its own; the tabs are the cards of a `CardLayout`, and every one
of them stays connected in the background. Coming back to a tab sometimes showed a transcript that
looked like a shrunk picture and did not scroll — the tab felt hung. With off-screen rendering
Swing only ever paints the last frame Chromium handed over, so all of these look exactly like that:

* a hidden card is not laid out: a tool window resized, or moved to a screen of another scale,
  while the tab was in the background leaves Chromium drawing for the old size, and Swing stretches
  the old frame to the new one;
* a hidden off-screen browser may have its rendering throttled or stopped (by Chromium, or by
  macOS App Nap) while its DOM keeps being patched by the live session, and does not pick up again;
* the renderer process died and nothing ever loaded the document again.

### 10.2 What is done (the specification)

* **Shown** — `SandboxAgentTabsPanel.selectTab` calls `SandboxAgentMainPanel.onShown` →
  `TranscriptPanel.onShown` → `TranscriptView.onShown` once the switch has been laid out
  (`invokeLater`, and only while that tab is still the selected one, so a tab closed in between is
  left alone). `JcefTranscriptView` then sends `notifyScreenInfoChanged()` and
  `wasResized(width, height)` to the browser and repaints the component, which makes Chromium
  render a fresh frame for the current size and scale. A document that ran out of its recovery
  attempts is loaded again.
* **Hidden** — the tab that loses the selection is told `onHidden` along the same chain. From then
  on `JcefTranscriptView` keeps recording into its log (the same log that is the queue of a page
  still loading, § 4) but does not flush it: a document nobody looks at is not patched. `onShown`
  flushes whatever piled up in one batch; the stale-entry rules of the log (a finished stream keeps
  only its `blockHtml`, …) keep that batch small. A `toast` arriving while hidden is dropped, since
  a confirmation shown later confirms nothing.
* **Renderer terminated** — `onRenderProcessTerminated` reloads the document and replays the log,
  like every other lost document (§ 6).
* The scroll position, the folded entries and the search state of a tab survive a switch, because
  its document is never rebuilt for it.
* `PlainTextTranscriptView` ignores both calls.

The cost that remains is memory: every open tab keeps one Chromium renderer process alive (up to
10 tabs).

### 10.3 If that is not enough: one browser per tool window

Should the renderers of many tabs turn out to be a real problem (memory pressure, renderers being
reclaimed by the OS, the freeze coming back all the same), the next step is to go back to **one
browser per tool window** and move the log out of the view:

* every tab keeps only its event log (the `record` / `forget` rules of `JcefTranscriptView` move to
  a per-tab model), and the tool window owns the one `JcefTranscriptView`;
* switching tabs sends a `reset` followed by the log of the tab that is now shown — the same replay
  a reloaded document already goes through; the events of every other tab are only recorded.

| | held back events (current) | one browser, replayed log |
| --- | --- | --- |
| renderer processes | one per tab (up to 10) | one per tool window |
| a freeze of a hidden browser | avoided by waking it up on show | impossible: there is no hidden browser |
| switching tabs | instant | rebuilds the document, slower with a long transcript |
| scroll position, folding, search, selection | kept | lost, unless saved and restored per tab by hand |
| size of the change | contained in `JcefTranscriptView` | the log moves out of the view, the tab panel owns the browser |

The held-back events are the first answer because they are small and keep the switch instant; the
single browser is the fallback for the case they do not cover, which is memory.
