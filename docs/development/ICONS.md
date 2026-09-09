# Icons

How the drawings of the plugin are kept, and how the ones the transcript document needs are turned
into something a browser can use.

## 1. Two families, two sets of rules

The plugin paints in two completely different places, and an icon cannot serve both:

| | Swing / IDE icons | Transcript icons |
| --- | --- | --- |
| Where | toolbars, buttons, the tool window stripe, the plugin list | inside the JCEF document (`TRANSCRIPT_WEBVIEW.md`) |
| Loaded by | `IconLoader` (`RemoteAgentIcons`), `plugin.xml` | the document itself, as an inlined SVG sprite |
| Theme | two files, `x.svg` and `x_dark.svg` — `IconLoader` picks one | one file; the color comes from CSS (`currentColor`) |
| Colors | baked into the file (`#6c707e` light, `#ced0d6` dark) | none; a color in the file is thrown away |
| Geometry | whatever the drawing needs (`0 0 14 14`, `0 0 13.6 8.89`, …) | **square**, `0 0 16 16` |
| Directory | `src/main/resources/icons/` | `src/main/resources/icons/transcript/` |

Keeping them apart is what lets each side stay simple: the Swing set may keep its exported colors
and its `_dark` twins, and the transcript set is guaranteed to be monochrome and square, which is
the only shape the sprite can normalize automatically.

Platform icons (`AllIcons.*`) are still used on the Swing side wherever the IDE has the right
drawing already. They are **not** available to the transcript: `AllIcons` hands out a Swing `Icon`,
not an SVG resource, so nothing can be handed to the browser. Every icon the document shows has to
exist as a file under `icons/transcript/`.

## 2. Inventory

`src/main/resources/icons/` — Swing / IDE, each with its `_dark` twin:

| File | Used by | Keep? |
| --- | --- | --- |
| `send.svg` | `RemoteAgentIcons.Send` — the send / stop button | yes |
| `stop.svg` | `RemoteAgentIcons.Stop` — the same button while a turn runs | yes |
| `connected.svg` | `RemoteAgentIcons.Connected` — connection toggle, for an agent without a drawing of its own | yes |
| `connectionStatus.svg` | `RemoteAgentIcons.ConnectionStatus` — the dot of `connected.svg` as a layer of its own, painted over the drawing of the agent (see §4) | yes |
| `disconnected.svg` | `RemoteAgentIcons.Disconnected` — the same toggle | yes |
| `tokenUsage.svg` | `RemoteAgentIcons.TokenUsage` — the usage readout | yes |
| `console.svg` | `RemoteAgentIcons.Console`, `plugin.xml` — `Open Sandbox Terminal` | yes |
| `settings.svg` | `RemoteAgentIcons.Settings`, `plugin.xml` — `Settings` | yes |
| `newSession.svg` | `RemoteAgentIcons.NewSession` — `New Session` | yes |
| `toolWindowIcon.svg`, `toolWindowIcon@20x20.svg` | `plugin.xml`, the tool window stripe | yes |

`src/main/resources/META-INF/pluginIcon.svg` stays where it is: the marketplace and the plugin list
look it up by that exact path.

## 3. Transcript icons

### 3.1 The files

```text
src/main/resources/icons/transcript/
    user.svg agent.svg plan.svg error.svg          block headers and notices
    copy.svg up.svg down.svg                       icon buttons
    read.svg edit.svg delete.svg move.svg search.svg execute.svg
    think.svg fetch.svg switch_mode.svg other.svg  ToolKind
```

The file name is the whole mapping table: it is the icon name the document asks for, the id of the
`<symbol>` it becomes (`ra-icon-<name>`), and — for a tool call — the ACP `ToolKind` verbatim. A
kind nobody drew falls back to `other.svg`, and `TranscriptIconsTest` fails when a `ToolKind` has no
file, so the fallback stays a safety net instead of a silent hole.

### 3.2 What the drawing has to look like

Two rules, and they are the only two:

1. **`viewBox` is square, ideally `0 0 16 16`.** The document draws every icon in a square box
   sized from the text (1.25em, 16×16 at the default base font size); a non-square drawing is
   squeezed into it.
2. **One color.** The color is applied by the browser, so whatever the file says is discarded.
   Multi-color icons are not possible here.

Everything else is cleaned up on the way in, so an export can be dropped in as it comes out of the
drawing tool. No `_dark` variant is needed — or wanted: `currentColor` follows the theme by itself.

### 3.3 From files to the document

```text
icons/transcript/*.svg
   |  TranscriptIcons.sprite()   strip, recolor, wrap in <symbol id="ra-icon-…">
   v
<svg class="icon-sprite"><defs><symbol/>…</defs></svg>
   |  TranscriptDocument.html()  replaces {{ICONS}} in transcript.html
   v
transcript.js:  icon(name) -> <svg class="icon"><use href="#ra-icon-name"/></svg>
```

`TranscriptIcons.sprite()` normalizes each file:

* drops the XML declaration, comments, `<title>` / `<desc>` and `<metadata>`;
* **drops every `id`** — the drawing tool writes `id="uuid-…"`, and two files carrying ids would
  collide in one document and break `<use>`;
* drops `width` / `height` (the size is the CSS's business) and keeps `viewBox`, which moves onto
  the `<symbol>`;
* replaces every color literal in `fill` / `stroke` with `currentColor`, leaving `fill="none"`
  alone — that is what makes an outline icon stay an outline;
* wraps the remainder in `<symbol id="ra-icon-<file name>">`.

The sprite is built once per document, next to the style sheet and the script that are inlined the
same way (`TranscriptDocument`). It is a pure function over the resources, so it is unit tested
headless like the rest of the transcript pipeline.

Because the sprite lives in the document, `transcript.js` holds no drawing at all: it asks for a
name and gets a `<use>`. A long transcript with hundreds of tool call rows therefore carries the
geometry exactly once.

### 3.4 Why at runtime and not in the build

A Gradle task generating a `.js` (or a `.svg`) from the files would need the generated artifact to
be kept in sync — a regeneration nobody ran, or a commit nobody made, is a bug that only shows up
in the UI. Reading sixteen small files while the one document is built costs nothing measurable, so
there is no reason to trade that away for a build step.

### 3.5 Adding an icon

1. Draw it 16×16, one color, and export it to `src/main/resources/icons/transcript/<name>.svg`.
   For a tool call the `<name>` is the ACP `ToolKind` string (`execute`, `switch_mode`, …).
2. Use it: `icon('<name>')` in `transcript.js` (a `ToolKind` needs no code at all — the kind of the
   event is the name).
3. `./gradlew test` — `TranscriptIconsTest` checks that every name the document can ask for exists,
   that the sprite carries no `id` and no color literal, and that every `ToolKind` is covered.

## 4. The agent's own icon

An entry of the ACP registry ships an `icon` next to its `name` and its `distribution` (see
`AcpAgentRegistry`), and the agent that was picked is drawn with it instead of the bundled drawing:
the connection toggle of the toolbar, and the header of every agent block in the transcript.

The file is downloaded once, when the agent is picked in the settings, and the markup itself is
persisted (`RemoteAgentSettingsState.agentIconSvg`): the icon is drawn on every start of the IDE,
and reaching the CDN for it would make the toolbar depend on the network. Before it is stored it is
cut down to its shapes (`AgentIcon.sanitize`) — scripts, event handlers and references leaving the
file are dropped, because the markup ends up in two places that both take markup and one of them is
the transcript document.

It is the one drawing whose colors are kept. A brand icon is recognized by them, and it has neither
a `_dark` twin nor a monochrome variant to fall back on:

| | Bundled icons | The icon of the agent |
| --- | --- | --- |
| Source | files of this repository | the ACP registry, stored in the settings |
| Swing | `IconLoader`, `_dark` twin | rasterized with its own colors kept (`RemoteAgentIcons.agent`) |
| Transcript | `<symbol>`, recolored to `currentColor` | `<symbol>`, colors kept (`TranscriptIcons.agentSymbol`) |

Kept colors, however, are not the same thing as a color for everything: every entry of the registry
currently ships a *monochrome* drawing asking for `currentColor`, which is a statement about the
color of its surroundings rather than a color of its own. The transcript has the browser resolve it
against the text, and the Swing side has to resolve it itself — the SVG loader of the platform
turns `currentColor`, and any shape without a `fill`, into black, and black on the toolbar of a
dark theme is the color of nothing: the button showed its connection dot and nothing else.
`RemoteAgentIcons` therefore paints what the drawing left open in the grey of the bundled icons
(`#6c707e` light, `#ced0d6` dark) before it hands the markup to the loader, so an agent icon
carries the weight of the icons next to it. A shape the drawing colored itself is left alone.

The raster belongs to the scale and the theme it was made for, so the markup — not the pixels — is
what the icon holds: it asks for a raster while painting, and the cache is keyed by markup, scale
and color. A moved window or a switched theme renders again instead of painting the drawing that
was made for the other one.

On the Swing side the drawing replaces `connected.svg` / `disconnected.svg`, and the connection dot
is layered over it from `connectionStatus.svg` while the session is up (`RemoteAgentIcons.connection`).
It is fitted into the toolbar rather than painted as it comes: the icon measures 16x16 like a bundled
one, while the drawing itself is scaled into 13x13 in its lower left corner — which is the margin the
bundled pair paints with, and which leaves the upper right corner to the dot. Both states place it
identically, so connecting only adds the dot instead of moving or resizing anything. The raster is
asked for the pixels of the current scale and painted into that box, because an icon reporting the
device pixels as its own size (`JBImageIcon`) shows up twice as large on a HiDPI screen.
In the transcript it replaces the `ra-icon-agent` symbol of the sprite, so the blocks already on
screen follow it — the host sends it as an `agent` event (see `TRANSCRIPT_WEBVIEW.md`).

Without an icon (a registry entry that has none, a download that failed) nothing
changes: the bundled drawings and the title `Agent` stay.

## 5. Tests

| Test | Guards |
| --- | --- |
| `AgentIconTest` | what is stored from the registry is a single `<svg>` of shapes: colors kept, scripts / handlers / external references gone |
| `RemoteAgentIconsTest` | the Swing icons load, carry a geometry, and paint something once they went through the loader the IDE uses (an export with a `c2pa` provenance blob renders as an empty image); the drawing of an agent measures like a bundled icon and keeps that size in both connection states; a drawing without a color of its own is painted in the theme grey instead of black, and one with colors keeps them |
| `TranscriptIconsTest` | every icon name the document uses has a file, every `ToolKind` has one, and the sprite is normalized (no `id`, no color literal, one `<symbol>` per file) |
| `TranscriptProtocolTest` | the built document contains the sprite, the style sheet and the script |

## 6. Notes

* The icons the transcript ships are drawn to *resemble* the IDE ones; they are not the platform
  drawings and will not match them pixel for pixel. Replacing one is a matter of overwriting a file
  under `icons/transcript/`.
* The icons follow the text: `.icon` is sized in `em` (`1.25em`, so 16 px at the default base size
  of 13 px), because the base font size is a plugin setting and an icon left at 16 px would look
  lost next to 20 px text.
* `aria-hidden="true"` is on every icon: the meaning is carried by the text next to it.
