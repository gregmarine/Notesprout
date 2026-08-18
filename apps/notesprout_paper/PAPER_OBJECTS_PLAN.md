# Paper — Extensions arc 4: content objects, the selection toolbar, the Markdown point + the Heading extension

> **This file is the project's memory across sessions for arc 4.** Context is cleared between
> phases. Everything a fresh session needs — decisions, non-goals, the contract, per-phase tasks,
> tests, status — is here or in the files this document points at. If it isn't written down here (or
> in the repo / project memory), it doesn't exist. **Read this file top to bottom at the start of
> every session**, after `PAPER_PLAN.md` (v0 — architecture), `PAPER_EXTENSIONS_PLAN.md` (arc 1 —
> extension API v1 + Templates), `PAPER_NAMING_PLAN.md` (arc 2 — the extension store + Naming),
> `PAPER_RECOGNITION_PLAN.md` (arc 3 — the recognizer capability point + ML Kit) and both
> `CLAUDE.md` files. `docs/extensions.md` is the subsystem reference all arcs write into;
> `docs/notebook.md` and `docs/data.md` gain sections in this arc.
>
> **Status: H0 ✅ (8c5361f) · H1 ✅ (62771f3) · H2 ⬜ · H3 ⬜ · H4 ⬜ · H5 ⬜ — H0 user-verified 2026-08-17.**

## Why

Arcs 1–3 settled how an extension is shaped, where it keeps data, and how one extension provides a
capability others consume (the recognizer, lent by the core through a proxy — *designed* in arc 3,
*built* here, with the first consumer). Arc 4 settles the fourth question: **how an extension puts
something on the page that isn't ink** — a content object the core stores, positions, selects, moves,
deletes and undoes, but never interprets — and how an extension **contributes UI** to the notebook
screen (a selection-toolbar button) without ever drawing over the paper itself.

The first object is the **heading**: the user writes a word or words, lassos them, taps **H** on the
selection toolbar, picks a size **H1–H6** from a sub-toolbar; the ink is recognized (through the
`HANDWRITING_RECOGNIZER` point — today `NSE · ML Kit`, tomorrow whatever recognizer is installed;
the heading never learns which), the recognized words become the heading's text with `#`s prepended
for the level, the ink is removed, and the heading is drawn on the page by a **Markdown renderer**
that is itself an extension. A selected heading can be re-sized by picking another level, and tapping
its text opens a plain-words edit dialog (no `#`s in the field).

That is **two new extensions** (`NSE · Markdown`, `NSE · Heading`), **two new points**
(`MARKDOWN_RENDERER` — a capability point; `OBJECT_PROVIDER` — the generic object point the heading is
the first implementation of), a **fresh look at the `.soil` schema** (object rows with x/y — no
migration; existing test notebooks are abandoned), a **g-paper 0.1.1** (tap inside the selection box),
and the core's **selection toolbar** with its extension-contribution API. The original Notesprout's
heading (`apps/notesprout_android`, `docs/content-objects.md`) is the behavioural reference; its
`core/markdown/` package is what the Markdown extension ports.

There is still no Extensions UI; extensions are installed and removed by hand.

---

## Working protocol

Identical to `PAPER_RECOGNITION_PLAN.md` §"Working protocol" — each phase in a **fresh session**:

1. **Phase start (no-assumption QA):** read this file (all of it), the four earlier plans' Locked
   decisions + Architecture + Appendices, `docs/extensions.md`, `docs/notebook.md`, `docs/data.md`,
   the root `CLAUDE.md`, and `apps/notesprout_paper/CLAUDE.md`. Confirm the next `⬜` phase with the
   user, flip it to `🔄`, then **ask the phase's "Questions to resolve at phase start"** one at a time
   in the wizard (option-select) format before writing code — recommended default first, plus
   "Other". Do not assume answers. If a new ambiguity surfaces mid-phase that would materially change
   the work, stop and ask.
2. **Code** — auto mode; inline; **frugal with agents** (see "Model note"); no Gradle dependency
   beyond Appendix B without asking; deliverables **exactly as written** — no added scope, no
   "improving" adjacent code, no scaffolding for later phases.
3. **Test** — `./gradlew testDebugUnitTest` (all modules), then build + install the debug APKs on the
   requested **test devices** and hand the user the phase's numbered on-device checklist (copy it,
   don't invent). EPD overlays are invisible to screencap — the user verifies by eye and reports;
   the automated per-device agent run (arc 3 / M2 pattern) covers what adb + uiautomator can reach.
4. **Fix → test again** until every test passes (JVM + device checklist).
5. **Docs / memory / CLAUDE.md** — `apps/notesprout_paper/CLAUDE.md` (standing rules + build facts
   only), `docs/extensions.md` (+ `docs/notebook.md`, `docs/data.md` where named), this file's status
   marker + **Outcome** note, and the project memory
   (`~/.claude/projects/-Users-gregmarine-git-Notesprout/memory/project_paper_objects.md` + its
   `MEMORY.md` index line).
6. **Commit & push** on `paper`. Then the user runs `/clear`.

**Status markers:** `⬜ Not started` · `🔄 In progress` · `🧪 Awaiting device verification` ·
`✅ Complete (commit <hash>)`. Update the marker **the moment the state changes**.

**Test devices** (user verifies by eye; always `-s <serial>`; never install on a device the user didn't
ask for; offline → say so and wait). The ML Kit model must already be on the device (arc 3) or Wi-Fi
is needed once.

| Nickname | Device | Serial | Engine |
|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` |

**Model note (user decision 2026-08-17): Fable 5 runs every phase.** Within a phase the work is
inline; agents are used only for (a) read-only exploration when a survey spans many files (`Explore`,
Sonnet is fine) and (b) the automated per-device verification run — **one Sonnet agent per device,
at most two running at a time**, given the phase's checklist verbatim plus the arc-3 device traps
(SNN: IME invisible to uiautomator, `adb input text` swallowed; NA5C: re-enable the app *and* every
extension after install and confirm `pm list packages -d`; MIP11: `log.tag=I` drops `Log.d` — set
`log.tag DEBUG`, re-check before every log-dependent step, restore after). Never more than two
agents at once; never an agent for code that changes a verified path. **Written for Opus 4.8 too:**
every phase names its files, signatures, constants and failure texts so no design happens mid-phase;
if something is genuinely undecided it is a phase-start question, not an inference.

---

## Locked decisions (planning Q&A, 2026-08-17)

| Area | Decision |
|---|---|
| Where a heading lives (Q1) | **In the `.soil`, as core-owned object rows** in the `notebook` table, **parented to the page** — `type = "object"`. The core stores the row (identity, geometry, an opaque text payload) and never interprets the payload; it hands the payload to the owning extension to render / edit / act on. The notebook stays self-contained. **Only strokes convert into a heading in this arc** (a future Text extension may add text → heading). Audit rule 16 ("the core stores no result of the capability") is amended for object payloads: an object's payload is the object, not a cached recognizer result — the recognizer's output is stored only *as* the payload the object provider chose to make of it. |
| Rendering + fallback (Q2) | **Text only in the row — no pre-rendered bitmap is ever stored.** At runtime the core asks the owning object provider to render the payload; the Heading extension forwards the markdown to the **Markdown proxy** and returns the bitmap; the core caches rendered bitmaps **in memory for the open session only** (keyed by object id + payload + width + dpi) and draws them through g-paper's `ContentRenderer`. Provider or Markdown extension absent / disabled / failing → the object draws as a **dashed placeholder box at its bounds** (still selectable, movable, deletable; not editable, not re-sizable). |
| Schema (Q3) | **Fresh look, no migration.** `x REAL` and `y REAL` are added to the `notebook` DDL and `SoilObjectEntity`; `SoilSchema.SOIL_VERSION` → **2** with **no migration code** — a v1 file fails to open with a clear reason (the app never deletes it; the user removes test notebooks by hand from the library). Strokes/pages leave x/y null. Recorded in `docs/data.md` and the portable format note. |
| Extension UI (Q4) | **Description → the core renders; icons from a core catalog by name.** An extension describes selection-toolbar actions (`SelectionAction`: id, short label, catalog icon name, sub-actions, applicability, requirements) and edit dialogs (`EditSpec`); the core draws every button, sub-toolbar and dialog under its own e-ink rules (tap dimens, exclusion rects, `releaseRender`, pen gating, frame silence, no colour). Unknown / absent icon name → the label is drawn as text. **Recorded, not built:** extension-owned *screens* off the paper (an Activity the core launches for a result) are the future escape hatch (Extensions-UI arc); embedded remote UI (`SurfaceControlViewHost`, RemoteViews) and in-process extension code are **never** allowed over the paper (the tiered rule goes into `docs/extensions.md`). |
| Core actions on the selection toolbar (Q5) | **Delete only.** One core button (Tabler `trash`) deleting the whole selection — strokes and/or objects — as one undoable action. Everything else on the bar is an extension contribution. |
| Tap-to-edit (Q6) | **g-paper 0.1.0 → 0.1.1: `PaperListener.onSelectionTapped(x, y)`** for a sub-threshold **stylus or single-finger** tap inside the active selection box (paper coordinates); **stylus and single-finger drags of the selection stay exactly as they are** (the tap fires only when the drag threshold was never crossed; the finger tap is palm-gated + `PEN_ACTIVE_TAIL_MS` escrow like every finger tap-action). Republished to mavenLocal; `docs/api.md` updated in the same g-paper commit. |
| Conversion outcome (Q7) | **Success:** the object is created **and** the source strokes are soft-deleted in **one undoable action** (undo restores the ink and removes the heading; redo the reverse); the new heading lands **selected** (`setSelection`) with the toolbar showing its object actions. **Empty / failed recognition:** `Dialogs.problem` ("Couldn't read the handwriting — try writing larger or clearer") and the ink is untouched; nothing is created. |
| Typography + geometry (Q8) | **The original, as-is, baked into the Markdown extension:** base **24 sp** (sp → px via the rendering device's dpi: `24 × dpi / 160`), **bold** sans, multipliers **H1 2.0 · H2 1.75 · H3 1.5 · H4 1.25 · H5 1.1 · H6 1.0**; **single line**, ellipsized (`END`) where it would pass the page's right edge; **8 dp inner padding** on all sides (part of the rendered image, so the object bounds include it); anchored at the **lasso box's top-left**; height = the rendered line + padding. The core knows none of these numbers. |
| Point shape (Q9) | **Generic `OBJECT_PROVIDER` point** (`IObjectProvider`): describe actions · create-from-ink · apply an action to an object · describe / apply an edit · render. The core stores rows as `object` with a provider identity `<pkg>:<typeId>`; the Heading extension is the reference implementation; a future Text / Shape / Link extension implements the same AIDL. |
| Recognizer choice + consent flow (Q10) | **`ExtensionRegistry.handwritingRecognizer` first-of stays; no chooser** (Extensions-UI territory — Deferred). The heading path is engine-neutral: the Heading extension only ever sees `IHandwritingRecognizer`. The debug menu's model-consent flow (Recognition model needed → Download → progress → auto-continue; offline pre-check) is **promoted to a main-source helper `RecognizerReadiness`** the H action uses; the debug ⋯ keeps using it. |
| Phases + devices (Q11) | **Six phases** H0–H5 (below), each a fresh session, verified on **SNN + NA5C + MIP11**. |
| Models + agents (Q12) | **Fable 5 for every phase**; agents only for read-only exploration and the per-device verification runs, ≤ 2 at a time (Model note above). |
| Names (Q13) | Points **`MARKDOWN_RENDERER`** (`IMarkdownRenderer`) and **`OBJECT_PROVIDER`** (`IObjectProvider`); extensions **`NSE · Markdown`** (`:ext-markdown`, `com.symmetricalpalmtree.notesprout.ext.markdown`, debug `.dev`) and **`NSE · Heading`** (`:ext-heading`, `…ext.heading`, debug `.dev`); Tabler `puzzle` icons; object row `type = "object"`, provider identity in `style`, payload in `text`; this file `PAPER_OBJECTS_PLAN.md`. |
| Trust / artifacts / version | Unchanged: same-signature only, debug-only APKs, no app version bump, `ExtensionContract.API_VERSION` stays **1** (two new points = two new actions + two AIDLs + the same meta-data key; every parcelable is new). |

## Deferred (recorded 2026-08-17, not built in this arc)

- **Recognizer chooser** (which installed recognizer the core binds) — Extensions-UI arc; until then
  first-of by (label, package). The heading path needs no change when it arrives (it sees the proxy).
- **Text → heading** (a Text object extension converting into a heading) — with a Text extension.
- **Extension-owned screens off the paper** (an Activity launched by the core for a result — settings,
  wizards) — Extensions-UI arc. Recorded as the allowed escape hatch of the UI rule; nothing here.
- **Standard-widget XML layouts supplied by an extension** for its edit dialog (UI rule option "A + B")
  — only if a point needs a richer form than `EditSpec` (title + one text field). Not built.
- **Extension-supplied vector icons loaded from the extension's own resources** — rejected for this arc
  in favour of the core catalog; may return with third-party trust.
- **Copy / Cut / Paste on the selection toolbar** (a clipboard model for strokes + objects) — later.
- **Multi-object actions** (align, distribute) — later; this arc's object actions apply to exactly one
  selected object.
- **Object z-order UI** — rows carry `"order"` among the page's objects (creation order); no UI.
- **A persisted render cache** (rendered bitmap in the row) — the user chose text-only; revisit only
  if runtime rendering proves too slow on e-ink for a page full of objects.
- **Page dpi in the page row** — objects render at the *current* device's dpi (page px ≈ device px on
  the creating device; v0 has no export/import). When a page can move devices, store the authored dpi
  on the page row and pass that instead.
- **`RecognizerProxyBinder` reuse for further consumers** — the proxy built here is the reference;
  a second consumer point copies it (mint per bind, revoke in `finally`).
- **Wrapping headings** (multi-line) — single line, ellipsized, as the original.
- **The Markdown point serving other consumers** (a Text object, an export) — the point is generic
  (markdown in, image out); nothing else calls it yet.

## Non-goals for this arc (do not build, do not scaffold "for later")

No Extensions UI · no third-party trust · no publishing of `:extension-api` · no release-build change
beyond compiling · no clipboard · no Text / Shape / Link objects (only the point they would implement)
· no `.soil` migration (fresh schema, old test files abandoned) · no `page_text` cache or page-level
recognition feature (the debug ⋯ "Recognize page" stays as is) · no other recognizer engines · no
recognizer chooser · no changes to the Templates / Naming / ML Kit extensions' *behaviour* (the one
permitted touch: nothing — the ML Kit extension already exposes `recognizeInk`) · no persisted render
cache · no colour · no release signing · no version bump · no `kotlin-parcelize` · no new Gradle
dependency anywhere (Appendix B).

---

## Architecture

### Module layout (after this arc)

```
apps/notesprout_paper/
├── settings.gradle.kts            include(":app", ":extension-api", ":ext-templates", ":ext-naming", ":ext-mlkit",
│                                          ":ext-markdown", ":ext-heading")
├── PAPER_OBJECTS_PLAN.md          this file
├── docs/extensions.md             gains: "MarkdownRenderer", "The Markdown extension", "ObjectProvider",
│                                  "The Heading extension", "Selection-toolbar contributions", host behaviour for
│                                  both points, the UI rule (tiered), the built proxies, audit rows 18–24,
│                                  "Adding an object point" rules
├── docs/notebook.md               gains: "Content objects", "Selection toolbar", "Edit dialog", undo actions
├── docs/data.md                   gains: object rows, x/y columns, SOIL_VERSION 2 (fresh schema)
├── app/…/notesprout/
│   ├── data/soil/                 SoilSchema (x, y; SOIL_VERSION 2), SoilObjectEntity (x, y), SoilDao (object queries)
│   ├── extension/                 + MarkdownClient, ObjectProviderClient, ExtensionRegistry.markdownRenderer /
│   │                              objectProviders, RecognizerProxyBinder, MarkdownProxyBinder, ProxyGate,
│   │                              ActionCaps / EditCaps / RenderCaps, IconCatalog (core side), RecognizerReadiness
│   └── notebook/                  + PageObject, ObjectRows, ObjectStore, ObjectRenderer (g-paper ContentRenderer),
│                                  ObjectRenderCache, SelectionToolbar (+ SubToolbar), SelectionActions (merge/order),
│                                  ObjectEditDialog, UndoRedoStack actions (ObjectCreated / ObjectsDeleted /
│                                  ObjectEdited / Moved+contentIds), NotebookActivity wiring
├── extension-api/src/main/
│   ├── aidl/…/extension/{IMarkdownRenderer, IObjectProvider, RenderedImage, SelectionAction, EditSpec, CreatedObject}.aidl
│   └── kotlin/…/extension/{ExtensionContract (+ constants), RenderedImage, SelectionAction, EditSpec, CreatedObject,
│                            IconNames, ActionApplies}.kt
├── ext-markdown/                  Android APPLICATION, com.symmetricalpalmtree.notesprout.ext.markdown (.dev)
│   └── src/main/kotlin/…/ext/markdown/{MarkdownRendererService, MarkdownParser, MarkdownSpans, MarkdownBitmap}.kt
└── ext-heading/                   Android APPLICATION, com.symmetricalpalmtree.notesprout.ext.heading (.dev)
    └── src/main/kotlin/…/ext/heading/{ObjectProviderService, HeadingText, HeadingActions}.kt
```

Dependency direction unchanged: `:app → :extension-api`; every `:ext-* → :extension-api` only.
`:ext-heading` and `:ext-markdown` never depend on each other or on `:ext-mlkit` — the Heading
extension reaches the recognizer and the Markdown renderer **only through the proxies the core hands
it as in-parameters**.

### The `.soil` object row (core-owned; `docs/data.md`)

Fresh schema (no migration; `SOIL_VERSION = 2`):

```sql
CREATE TABLE notebook (
    id TEXT NOT NULL PRIMARY KEY, parentId TEXT NOT NULL, type TEXT NOT NULL,
    "order" INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
    deletedAt INTEGER, text TEXT, refId TEXT, x REAL, y REAL, width REAL, height REAL, color TEXT,
    strokeWidth REAL, style TEXT, flags INTEGER, blob BLOB);
```

An **object row** (`SoilSchema.TYPE_OBJECT = "object"`): `parentId` = page id · `style` = the
**provider identity** `<extension package>:<typeId>` (`ExtensionContract.objectIdentity(pkg, typeId)`,
same shape and same `.dev`-alias caveat as template identities) · `text` = the provider's **opaque
payload** (≤ `MAX_OBJECT_TEXT_CHARS`; for a heading it is the markdown source, e.g. `## Meeting notes`)
· `x`/`y`/`width`/`height` = bounds in **page px** · `"order"` = z-order among the page's objects
(`MAX("order")+1` at creation, like strokes) · `refId`, `color`, `strokeWidth`, `flags`, `blob` = null.
Soft delete like everything else. **The core never parses `text`.**

`PageObject(id, providerIdentity, payload, x, y, width, height, order)` is the in-memory form;
`ObjectRows` (pure, JVM-tested) maps `PageObject ⇄ SoilObjectEntity`; `ObjectStore` writes rows through
**the same serial IO writer as `StrokeStore`** (one `Channel` — objects and strokes never race; the
`updatedAt` touch discipline is shared). Page delete / undo reconcile soft-delete and restore **objects
together with strokes** (`SoilDao.liveChildIds(pageId)` replaces `liveStrokeIds` where the page's whole
content is meant).

### Contract additions (`:extension-api`) — exact

`ExtensionContract` gains:

| Constant | Value |
|---|---|
| `ACTION_MARKDOWN_RENDERER` | `"com.symmetricalpalmtree.notesprout.extension.MARKDOWN_RENDERER"` |
| `ACTION_OBJECT_PROVIDER` | `"com.symmetricalpalmtree.notesprout.extension.OBJECT_PROVIDER"` |
| `MAX_MARKDOWN_CHARS` | `20_000` — longest markdown source one `render` accepts (host truncates before the call; extension re-checks) |
| `MAX_OBJECT_TEXT_CHARS` | `20_000` — longest object payload the host stores / hands to a provider |
| `MAX_IMAGE_EDGE_PX` | `4_096` — a `RenderedImage` may not exceed this on either side (host + extension) |
| `MAX_RENDER_BYTES` | unchanged (16 MiB) — also caps `RenderedImage.byteCount` |
| `MAX_ACTIONS` / `MAX_SUB_ACTIONS` | `16` / `16` — per provider / per action |
| `MAX_ACTION_ID_CHARS` / `MAX_ACTION_LABEL_CHARS` / `MAX_ACTION_HINT_CHARS` | `32` / `6` / `40` |
| `MAX_TYPE_ID_CHARS` | `32` — `typeId` is `[a-z0-9_-]+` |
| `MAX_EDIT_TITLE_CHARS` / `MAX_EDIT_HINT_CHARS` / `MAX_EDIT_TEXT_CHARS` | `40` / `60` / `4_000` |
| `MAX_OBJECTS_PER_PAGE` | `200` — host cap on creation |
| `RENDER_PADDING_MAX_PX` | `64` — cap on the padding a markdown render may ask for |
| `RECOGNIZER_REQUIRED` / `MARKDOWN_REQUIRED` | exact `IllegalStateException` messages a provider throws when the in-parameter it needs is null |
| `objectIdentity(pkg, typeId)` | `"$pkg:$typeId"` (parsed by the existing `parseIdentity`) |

`ActionApplies` (Kotlin `object` of `Int` bit flags — AIDL carries `int`): `INK = 1` (a pure-stroke
selection) · `OBJECT = 2` (exactly one selected object of one of this provider's types). `Requires`
(`Int` bit flags): `RECOGNIZER = 1` · `MARKDOWN = 2`.

`IconNames` (Kotlin `object` of `String` constants — the **core icon catalog**; the core maps each to
a Tabler drawable it ships; unknown → the label is drawn as text): `HEADING = "heading"`, `H1 = "h-1"`
… `H6 = "h-6"`, `TEXT = "text"`, `EDIT = "edit"`, `X = "x"`, `CHECK = "check"`, `PLUS = "plus"`,
`TRASH = "trash"` (Delete's own icon; listed so an extension may reuse the glyph).

AIDL (`extension-api/src/main/aidl/com/symmetricalpalmtree/notesprout/extension/`):

```aidl
// RenderedImage.aidl · SelectionAction.aidl · EditSpec.aidl · CreatedObject.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable RenderedImage;   parcelable SelectionAction;   parcelable EditSpec;   parcelable CreatedObject;

// IMarkdownRenderer.aidl — the MARKDOWN_RENDERER capability point (arc 4 / H0).
// Markdown in, image out. Stateless. Lent to object providers by the core through a proxy.
package com.symmetricalpalmtree.notesprout.extension;
import com.symmetricalpalmtree.notesprout.extension.RenderedImage;
interface IMarkdownRenderer {
    /** Render [markdown] (≤ MAX_MARKDOWN_CHARS) as black text on a transparent background:
     *  natural width capped at [maxWidthPx] (> 0), [dpi] the panel density (sp/dp → px), [maxLines]
     *  0 = unlimited else ellipsize END past that many lines, [paddingPx] 0..RENDER_PADDING_MAX_PX added
     *  on all four sides. Returns a lossless WEBP with alpha whose declared size equals the encoded
     *  size, ≤ MAX_IMAGE_EDGE_PX per side; null if the source renders to nothing. Called on a Binder
     *  thread. IllegalArgumentException over the caps. */
    RenderedImage render(String markdown, int maxWidthPx, float dpi, int maxLines, int paddingPx);
}

// IObjectProvider.aidl — the OBJECT_PROVIDER point (arc 4 / H3). A provider owns one or more object
// types (typeIds). The core stores an opaque payload per object and asks the provider to act on it.
package com.symmetricalpalmtree.notesprout.extension;
import com.symmetricalpalmtree.notesprout.extension.SelectionAction;
import com.symmetricalpalmtree.notesprout.extension.EditSpec;
import com.symmetricalpalmtree.notesprout.extension.CreatedObject;
import com.symmetricalpalmtree.notesprout.extension.RenderedImage;
import com.symmetricalpalmtree.notesprout.extension.InkStroke;
import com.symmetricalpalmtree.notesprout.extension.IHandwritingRecognizer;
import com.symmetricalpalmtree.notesprout.extension.IMarkdownRenderer;
interface IObjectProvider {
    /** The typeIds this provider owns ([a-z0-9_-]+, ≤ MAX_TYPE_ID_CHARS, ≤ 16). */
    List<String> describeTypes();
    /** Selection-toolbar contributions in display order (≤ MAX_ACTIONS; one level of sub-actions). Pure. */
    List<SelectionAction> describeActions();
    /** For a selected object: which of this provider's action ids are "active" (drawn selected —
     *  e.g. the heading's current level). Pure; empty if none. */
    List<String> activeActionIds(String typeId, String payload);
    /** Turn a pure-stroke selection into an object. [actionId] = the tapped leaf action; [strokes] in
     *  page px, [areaWidth]/[areaHeight] = the selection bounds' size; [recognizer] = the core's proxy or
     *  null when none is installed (throw IllegalStateException(RECOGNIZER_REQUIRED) if it is needed).
     *  Returns the new object's typeId + payload, or null when nothing usable was recognized. */
    CreatedObject createFromInk(String actionId, in List<InkStroke> strokes, float areaWidth, float areaHeight,
                                IHandwritingRecognizer recognizer);
    /** Apply a leaf action to an existing object. Returns the new payload, or null for "no change". Pure. */
    String applyAction(String actionId, String typeId, String payload);
    /** How the core should draw the edit dialog for this object (null = not editable). Pure. */
    EditSpec describeEdit(String typeId, String payload);
    /** The payload after the user saved [text] in the edit dialog; null = no change (e.g. blank). Pure. */
    String applyEdit(String typeId, String payload, String text);
    /** Render the object: [maxWidthPx] > 0 (page width minus the object's x), [dpi] the panel density,
     *  [markdown] = the core's proxy or null when none is installed (throw
     *  IllegalStateException(MARKDOWN_REQUIRED) if it is needed). Returns null if there is nothing to draw. */
    RenderedImage render(String typeId, String payload, int maxWidthPx, float dpi, IMarkdownRenderer markdown);
}
```

Hand-written parcelables (`@JvmField CREATOR`, write order fixed forever, tails may be appended):

- **`RenderedImage(memory: SharedMemory, byteCount: Int, mimeType: String, widthPx: Int, heightPx: Int)`**
  — `writeParcelable(memory); writeInt(byteCount); writeString(mimeType); writeInt(widthPx);
  writeInt(heightPx)`; `describeContents = CONTENTS_FILE_DESCRIPTOR`. Same SharedMemory handshake as
  `RenderedTemplate` (extension creates + writes + `setProtect(PROT_READ)`, closes its handle in
  `onTransact`'s `finally`; host maps read-only, copies out, unmaps, closes). The host **verifies** the
  WEBP header size (`Bitmaps.imageSize`) equals `widthPx × heightPx` and both ≤ `MAX_IMAGE_EDGE_PX`.
- **`SelectionAction(id: String, label: String, iconName: String?, appliesTo: Int, requires: Int,
  subActions: List<SelectionAction>)`** — `writeString ×3 (null-safe icon); writeInt ×2;
  writeTypedList(subActions)`. A **leaf** has no sub-actions and is what `createFromInk` /
  `applyAction` receive; a **parent** with sub-actions opens the sub-toolbar and is never performed
  itself. `label` ≤ 6 chars (drawn as text when the icon is unknown, and always the long-press hint
  together with the provider label). Nested sub-actions (depth > 1) are dropped by the host.
- **`EditSpec(title: String, text: String, hint: String, maxChars: Int, multiLine: Boolean)`** —
  `writeString ×3; writeInt; writeInt(0/1)`. `text` is what the field is prefilled with (for a heading:
  the words **without** the `#`s), `maxChars` ≤ `MAX_EDIT_TEXT_CHARS`.
- **`CreatedObject(typeId: String, payload: String)`** — `writeString ×2`.

Everything inward is untrusted: strings truncated to their caps, lists capped, ids validated
(`[A-Za-z0-9_.-]+`), unknown `iconName` → label, `appliesTo == 0` → the action is dropped, a `typeId`
not in `describeTypes()` → dropped, `RenderedImage` verified as above, an `IllegalStateException`
whose message equals `RECOGNIZER_REQUIRED` / `MARKDOWN_REQUIRED` typed on the host so the dialog can
name the missing extension.

### Host side (`:app`)

**Registry + clients (`extension/`)** — all over the shared `ExtensionBinder` (bind ≤ 3 s, signature
re-check at bind, unbind in `finally`, one `ExtensionCallException`):

- `ExtensionRegistry.markdownRenderer(ctx): ProviderRef?` — first by (label, package);
  `ExtensionRegistry.objectProviders(ctx): List<ProviderRef>` — all trusted, sorted by (label,
  package). Manifest `<queries>` gains both actions.
- **`MarkdownClient(ctx, ref)`**: `render(markdown, maxWidthPx, dpi, maxLines, paddingPx): ByteArray?`
  (≤ **5 s**; caps before the bind — `RenderCaps`; inward: mime + byteCount + header size + edge caps
  → bytes or failure; log tag `MarkdownClient`, counts + durations, **never the text**).
- **`ObjectProviderClient(ctx, ref)`**: `describeTypes()` / `describeActions()` / `activeActionIds()`
  / `applyAction()` / `describeEdit()` / `applyEdit()` ≤ **2 s** each; `createFromInk(...)` ≤ **15 s**
  (one recognizer hop inside: `RecognizerClient.INK_TIMEOUT_MS` 10 s + margin); `render(...)` ≤ **8 s**
  (one markdown hop inside: 5 s + margin). `ActionCaps` / `EditCaps` (pure, JVM-tested) validate every
  inward list/string. **The two proxies are minted per bind and revoked in the client's own
  `finally`, right after the shared unbind** (the `NamerClient` store shape): `RecognizerProxyBinder`
  is passed only to `createFromInk`; `MarkdownProxyBinder` only to `render`; both are `null` when the
  corresponding registry lookup is empty (the core never fakes a capability). Log tag
  `ObjectProviderClient` — never a payload.
- **`RecognizerProxyBinder(client: RecognizerClient, extUid: Int)`** (`IHandwritingRecognizer.Stub`,
  built here — the arc-3 recipe verbatim): every method first `ProxyGate.check()` (`getCallingUid()
  == extUid && !revoked`, else `SecurityException`); `status()` / `prepare()` forward; `recognizeInk`
  re-applies `InkCaps.check` + `preContext` inward then forwards; `recognizePage` forwards with caps.
  Forwarding = **`runBlocking` on the host's Binder thread** (never Main — a Binder thread is not the
  UI thread; the inner call has its own bind, timeout and signature check). Failures → the
  marshalable set only (`IllegalStateException(RECOGNIZER_NOT_READY)` when typed so, else
  `IllegalStateException(<class>)`; caps → `IllegalArgumentException`).
- **`MarkdownProxyBinder(client: MarkdownClient, extUid: Int)`** (`IMarkdownRenderer.Stub`): same
  gate; `render` re-applies `RenderCaps` inward, forwards, and re-wraps the returned bytes into a fresh
  `RenderedImage` region for the caller (the proxy owns the copy: map, write, `PROT_READ`, close after
  marshal). `ProxyGate` (pure, JVM-tested) is the shared `ExtensionStoreGate`-shaped uid/revoke check.
- **`RecognizerReadiness`** (main source, `extension/`): the flow moved out of `NotebookDebugMenu` —
  `ensureReady(activity, client, onReady, onGaveUp)`: `status()` → READY → `onReady` · NEEDS_DOWNLOAD →
  "Recognition model needed" dialog (offline pre-check `Connectivity.isOnline` → offline dialog) →
  Download → `prepare()` + progress dialog with elapsed counter (2 s poll, 5 consecutive failed polls or
  5 min → "Download failed"; Cancel hides only) → READY → `onReady` · DOWNLOADING → progress dialog ·
  UNAVAILABLE → problem dialog. Strings move to main `strings.xml`; the debug menu calls the same
  helper (behaviour unchanged, M1/M2 checklist items re-run as regression).
- **`IconCatalog`** (`core/`): `IconNames` → `@DrawableRes` (`ic_heading`, `ic_h_1`…`ic_h_6`,
  `ic_cursor_text`, `ic_edit`, `ic_x`, `ic_check`, `ic_plus`, `ic_trash`); the H1–H6 + heading Tabler
  vectors are added to `res/drawable` (copy `ic_h_1..3` + `ic_heading` from the original; download
  Tabler `h-4`, `h-5`, `h-6`, 24 dp, stroke 2, round caps).

**Notebook screen (`notebook/`)**

- **`ObjectStore`** (shares `StrokeStore`'s writer): `loadPage(pageId): List<PageObject>` · `create(pageId,
  obj)` · `updatePayloadAndBounds(id, payload, x, y, w, h)` · `move(ids, dx, dy)` · `remove(ids)` /
  `restore(pageId, objects)` (soft delete / un-delete, undo shape) — every write bumps the touch.
- **`ObjectRenderer : ContentRenderer`** (`layer = BELOW_STROKES`; **implements the live-drag pair**
  `draw(canvas, excluded)` + `drawObject(canvas, id)`): for each live object, draw the cached bitmap at
  (x, y) if `ObjectRenderCache` has one, else a **dashed 1 px inkBlack placeholder rect** at its bounds;
  `hitTargets()` = every live object's bounds. `ObjectRenderCache` = `Map<objectId, (payloadHash,
  maxWidth, dpi) → Bitmap>` for the open notebook, cleared on close; a miss schedules **one render pass
  per page load** (IO): group the page's objects by provider identity → one `ObjectProviderClient` bind
  per provider → `render` each object → decode (`Bitmaps.decodeBounded`, edge cap) → on Main: cache +
  set the object's `width/height` from the image if they differ (persisted; anchored top-left) →
  `paper.notifyContentChanged()` **through `whenPenIdle`** (frame-silence rule). Unknown provider /
  failure → placeholder stays; nothing is retried until the next page load or an edit.
- **`SelectionToolbar`** (`res/layout/view_selection_toolbar.xml`, a bordered horizontal
  `LinearLayout` overlay in `activity_notebook.xml`, `GONE` by default; buttons are
  `Widget.Notesprout.ToolbarButton` `AppCompatImageButton`s or, for text-fallback labels, same-dimen
  `AppCompatButton`s; long-press hint = label (+ "· <provider label>" for contributions)). Shown by
  `onSelectionCreated` (via `whenPenIdle`) **anchored 8 dp below the selection bounds, centred**, flipped
  above when it would clip the bottom strip, clamped inside the paper between the top bar and the
  bottom strip; hidden by `onSelectionDragStarted` and re-anchored by `onSelectionMoved`; hidden by
  `onSelectionDismissed` and on every page navigation. **Its rect (and the sub-toolbar's) is pushed
  with the chrome exclusion rects** and included in `overChrome()` so a finger tap releases the render.
  Contents = `SelectionActions.merge(coreActions, contributions, selection)`: **Delete** first, then
  every provider's actions in registry order, filtered by `appliesTo` (INK when the selection is
  strokes-only; OBJECT when the selection is exactly one object of that provider's types — a mixed
  selection shows core actions only); an action with sub-actions opens **`SubToolbar`** (a second
  bordered row anchored to the toolbar, not the selection, so they never overlap; sub-buttons whose id
  is in `activeActionIds` are drawn `state_selected`); tapping a leaf performs it; the sub-toolbar
  closes on any selection change / hide. Contributions are fetched **once per notebook open** (IO,
  `describeTypes` + `describeActions` per provider) and re-fetched on `onResume` if the extension set
  changed (cheap `queryIntentServices` compare).
- **Performing an action** (`NotebookActivity`, all under `pageOps`):
  - **Delete**: `store.erase(strokeIds)` + `objectStore.remove(objectIds)` → undo `ObjectsDeleted(pageId,
    strokes, objects)` (one action) → `paper.removeStrokes` + `notifyContentChanged` → dismiss.
  - **INK leaf** (`createFromInk`): guard `requires`: RECOGNIZER bit and no recognizer installed →
    `Dialogs.problem` "This action needs a handwriting recognizer extension"; MARKDOWN bit and none →
    "…needs the Markdown extension"; else `RecognizerReadiness.ensureReady` (dialog flow) → "Working…"
    Opening-style popup → `InkPayload.fromStrokes(selected)` → `ObjectProviderClient.createFromInk(action,
    ink, bounds.w, bounds.h)` → null → problem dialog "Couldn't read the handwriting" (ink untouched) ·
    result → `PageObject(uuid, identity, payload, x = bounds.left, y = bounds.top, w = bounds.width, h =
    bounds.height, order = max+1)` → **one transaction-shaped pair**: `objectStore.create` +
    `store.erase(strokeIds)` → undo `ObjectCreated(pageId, object, removedStrokes)` → `paper.removeStrokes`
    → render pass for the new object (sets real w/h) → `paper.setSelection(∅, {id}, bounds)` → toolbar
    shows the object's actions. Cap: `MAX_OBJECTS_PER_PAGE` → problem dialog. Failure/timeout → problem
    dialog "The <provider label> extension didn't respond"; the ink is untouched.
  - **OBJECT leaf** (`applyAction`): → new payload or null (no-op) → `objectStore.updatePayloadAndBounds`
    → undo `ObjectEdited(pageId, id, before(payload, bounds), after)` → re-render → `setSelection` again
    (bounds may have changed) → toolbar refresh (`activeActionIds`).
  - **`onSelectionTapped(x, y)`** with exactly one selected object whose bounds contain (x, y) →
    `describeEdit` → null → nothing · spec → **`ObjectEditDialog`** (`AlertDialog`, title = spec.title,
    one `AppCompatEditText` prefilled with spec.text, hint, `maxChars`, single/multi-line, IME shown
    per `docs/design-system.md`; **Save / Cancel**) → Save → `applyEdit(typeId, payload, text)` → null
    (blank / unchanged) → close · payload → same path as an OBJECT leaf (`ObjectEdited`, re-render,
    re-select). A tap with strokes selected, or outside the object, does nothing.
  - **`onSelectionMoved`** gains `contentIds`: `objectStore.move` + `liveObjects` update; undo `Moved`
    carries `objectIds` too. **`Action.Page` reconcile** restores/deletes objects with strokes.
- **Undo actions** (`UndoRedoStack.Action`): `ObjectCreated(pageId, obj, removedStrokes)` (undo: restore
  strokes + remove object; redo: reverse) · `ObjectsDeleted(pageId, strokes, objects)` · `ObjectEdited(
  pageId, id, before, after)` · `Moved(pageId, strokeIds, objectIds, dx, dy)` · `Page(Structural)` with
  `childIds` (strokes + objects). Replay stays **store → drain → reload the affected page** (objects
  reload with strokes: `refreshToPage` loads both and re-runs the render pass from cache).

### Extension side — `:ext-markdown` (`NSE · Markdown`)

- Gradle mirrors `:ext-naming` (application, `minSdk 29`, `compileSdk`/`targetSdk 35`, `HOST_PACKAGE`
  per build type, `.dev` suffix, no Activity, label `NSE · Markdown` (debug ` Dev`), puzzle icon). No
  dependency beyond `:extension-api` + junit.
- **`MarkdownRendererService`**: `onBind` → `IMarkdownRenderer.Stub`; `HostCallerCheck.enforce` first
  in `render`; caps re-checked (`IllegalArgumentException`); `onTransact` `finally` closes the
  per-thread pending `SharedMemory` (the Templates pattern, verbatim).
- **`MarkdownParser`** — **verbatim port** of the original `core/markdown/MarkdownParser.kt` (pure
  Kotlin, no Android; blocks: heading 1–6, paragraph, list item (ordered/unordered/task, depth), quote,
  rule; inlines: text, bold, italic, strike, code, link, image-alt), JVM-tested with the port's cases.
- **`MarkdownSpans`** — port of `MarkdownRenderer.kt` (blocks → `SpannableStringBuilder` with stock
  spans; **`headingSizeMultiplier` 2.0 / 1.75 / 1.5 / 1.25 / 1.1 / 1.0 + bold**; list glyphs, margins,
  quote, rule span); the one Android-text-only class.
- **`MarkdownBitmap`** — port of `TextObjectRenderer.kt`'s measure+draw into `render(markdown,
  maxWidthPx, dpi, maxLines, paddingPx): Bitmap?`: `TextPaint(ANTI_ALIAS)`, black, `textSize = 24 ×
  dpi / 160` px, `density = dpi / 160` for dp-based spans; `StaticLayout` at `maxWidthPx − 2·padding`;
  natural width = max line width (ceil) capped; height = layout height; ellipsize END when `maxLines >
  0`; `ARGB_8888`, transparent background, padding on all sides; null when the trimmed source is
  blank. Encoded **lossless WEBP** (`WEBP_LOSSLESS` on API ≥ 30 else `WEBP` q100 — the E2 guard) into
  a `RenderedImage` with the real size. Edge > `MAX_IMAGE_EDGE_PX` → `IllegalArgumentException`.
- Logs: `if (BuildConfig.DEBUG) Log.d(...)` — sizes + durations, **never the text**.

### Extension side — `:ext-heading` (`NSE · Heading`)

- Gradle as above; label `NSE · Heading` (debug ` Dev`); depends on `:extension-api` only.
- **`ObjectProviderService`**: `IObjectProvider.Stub`, `HostCallerCheck.enforce` first everywhere.
  `describeTypes()` = `["heading"]`. `describeActions()` = one parent action `heading` (label `H`,
  icon `IconNames.HEADING`, `appliesTo = INK | OBJECT`, `requires = RECOGNIZER | MARKDOWN`) with leaf
  sub-actions `h1`…`h6` (labels `H1`…`H6`, icons `h-1`…`h-6`, same flags). `activeActionIds` = the
  payload's level → `["h<n>"]`. `createFromInk(actionId, strokes, w, h, recognizer)`: `recognizer ==
  null` → `IllegalStateException(RECOGNIZER_REQUIRED)`; `text = recognizer.recognizeInk(strokes, w, h,
  "")` (the lasso box as the writing area, no pre-context — the original's single-shot path); newlines
  → spaces, trimmed; blank → **null**; else `CreatedObject("heading", HeadingText.withLevel(text,
  level(actionId)))`. `applyAction(h<n>, …)` = re-prefix (same level → null). `describeEdit` =
  `EditSpec("Edit heading", stripped words, "Heading text", 500, false)`. `applyEdit` = blank → null,
  else re-prefix with the current level. `render(typeId, payload, maxW, dpi, markdown)`: `markdown ==
  null` → `IllegalStateException(MARKDOWN_REQUIRED)`; else `markdown.render(payload, maxW, dpi,
  maxLines = 1, paddingPx = round(8 × dpi / 160))` returned **as-is** (the proxy's region is the
  reply — the heading never decodes pixels).
- **`HeadingText`** (pure, JVM-tested): `prefix(level) = "#".repeat(level in 1..6) + " "`,
  `strip(text)`, `withLevel(text, level)`, `levelOf(payload)` (1..6; malformed → 1) — the original's
  `HeadingObject` helpers widened to six levels.
- Logs: counts + durations only — never text.

### g-paper 0.1.1 (the one library change)

`PaperListener.onSelectionTapped(x: Float, y: Float)` — fires for a **stylus** or **single-finger**
sub-threshold tap inside the active selection box (paper coordinates); the finger variant is
palm-gated + escrowed like the component's own tap-to-dismiss; drags (stylus and finger) are
unchanged, and a tap outside the box still dismisses. Bump `GPAPER_VERSION` 0.1.0 → 0.1.1,
`publishToMavenLocal`, `docs/api.md` (Tools/selection §, PaperListener) + `host-responsibilities.md`
in the same g-paper commit; Paper's `app/build.gradle.kts` pins 0.1.1 and `CLAUDE.md` records it.

### Rules followed (arcs 1–3) and added

Rules 1–5 (point), 12–17 (capability — the recognizer **and** the Markdown point are capabilities;
both are lent through the proxy recipe, built here) apply. New, written into `docs/extensions.md`
in H5 as **"Adding an object point" (rules 18–23)**:

18. **The core stores objects; providers never do.** An object is a core row (identity, geometry,
    opaque payload) under its page; a provider keeps nothing about a specific object anywhere (its
    host store, if it has one, is for settings — none in this arc).
19. **The payload is opaque to the core** — never parsed, never logged, capped at
    `MAX_OBJECT_TEXT_CHARS`, shown only inside a provider-described edit dialog.
20. **The core draws every piece of contributed UI** under its own e-ink rules from a description
    (`SelectionAction`, `EditSpec`, catalog icons); no extension pixels, layouts or code over the
    paper. Extension-owned screens are allowed only off the paper (future).
21. **Absent provider = placeholder, never a broken page.** Rows render as a dashed box, stay
    selectable/movable/deletable, and come back to life when the provider returns.
22. **Every action is one undoable step**, including the strokes it consumes.
23. **Capabilities reach a provider only as in-parameters** (`IHandwritingRecognizer`,
    `IMarkdownRenderer` proxies), null when absent; a provider says what it needs
    (`requires`) so the core can explain before binding.

---

## Phases

### Phase H0 — The Markdown point + `NSE · Markdown` (no host change)
**Status:** ✅ Complete (commit 8c5361f; user-verified SNN + NA5C + MIP11 2026-08-17)

**Goal:** `IMarkdownRenderer` + `RenderedImage` exist in the contract; `NSE · Markdown` installs, is
discovered by nothing yet, and its parser/renderer are exercised end-to-end from JVM tests (parser,
span mapping, the pure size math) and by a scratch host-side probe **only if** one already exists —
it doesn't, so device verification is install + `dumpsys` sanity (like M0). No user-visible change.

**Questions to resolve at phase start** (one at a time; recommended default first):
1. Encoding of the render result — lossless WEBP with alpha (rec.; the Templates handshake, and the
   E2 `WEBP_LOSSLESS` API-30 guard) / raw ARGB in the region (bigger, no encode cost)?
2. Should `render` on blank source return null (rec.) or a 1×1 transparent image?
3. `MarkdownParser` port scope — verbatim incl. lists/quotes/rules/links (rec.; the point is generic)
   / headings + inline emphasis only?
4. Tests — port the original's markdown tests if any exist under
   `apps/notesprout_android/app/src/test/` (check), else write: heading levels 1–6, bold/italic,
   list glyphs, blank source, ellipsize at maxLines 1, size math (`MarkdownBitmap` factored so the
   Android-free parts are testable) — rec.: port + fill gaps.

**Resolved at phase start (2026-08-17):** Q1 lossless WEBP with alpha (Templates handshake) · Q2
blank source → null · Q3 verbatim parser port · Q4 port the original's `MarkdownParserImageTest` +
`MarkdownParserOrderedListTest`, then fill the gaps listed.

**Deliverables**
1. `:extension-api`: `IMarkdownRenderer.aidl`, `RenderedImage.aidl` + Kotlin Parcelable,
   `ExtensionContract.ACTION_MARKDOWN_RENDERER` + `MAX_MARKDOWN_CHARS` / `MAX_IMAGE_EDGE_PX` /
   `RENDER_PADDING_MAX_PX`; require-checks on `RenderedImage` (positive size, byteCount > 0) with a JVM
   test.
2. `settings.gradle.kts` `include(":ext-markdown")`; `:ext-markdown` exactly as in "Extension side"
   (Gradle, manifest with one exported `<service>` + `API_VERSION` meta, `MarkdownRendererService`,
   `MarkdownParser`, `MarkdownSpans`, `MarkdownBitmap`, strings, icon, `HOST_PACKAGE`, debug label).
3. JVM tests: `MarkdownParserTest` (per Q4), `HeadingScaleTest` (the six multipliers), a pure
   `MarkdownBitmap` sizing helper test (padding + cap arithmetic).
4. Docs: `docs/extensions.md` §"MarkdownRenderer (contract)" + §"The Markdown extension"; `README.md`
   install line; `CLAUDE.md` build lines for `:ext-markdown`.

**Tests**
- JVM green (all modules); `assembleDebug` builds six modules.
- Shell sanity per device after installing `ext-markdown-debug.apk`: `pm list packages | grep
  ext.markdown`; `dumpsys package … | grep -A6 MARKDOWN_RENDERER`; `pm resolve-activity --brief -c
  android.intent.category.LAUNCHER <pkg>` → "No activity found"; NA5C enable dance. APK size noted.
- **User device checklist:** (1) Settings → Apps shows "NSE · Markdown Dev" with the puzzle icon; (2)
  Paper still creates/opens/writes; Templates + Naming + Recognize page unchanged.

**Close-out:** status ✅ + Outcome (M0 base commit hash for H5's review range = the commit before
H0's first commit); docs; memory; commit + push.

**Outcome (2026-08-17):** **H5 review base = `08e0f5b`** (the commit before H0's first commit).
Built exactly as specified: `:extension-api` + `ACTION_MARKDOWN_RENDERER`, `MAX_MARKDOWN_CHARS`,
`MAX_IMAGE_EDGE_PX`, `RENDER_PADDING_MAX_PX`, `IMarkdownRenderer.aidl`, `RenderedImage` (+ pure
`requireValid`, 5 JVM tests); `:ext-markdown` (Gradle/manifest = `:ext-naming` shape, label
`NSE · Markdown` / ` Dev`, puzzle icon, `MarkdownRendererService` with the Templates `ThreadLocal`
+ `onTransact` finally handshake, `MarkdownParser` verbatim, `MarkdownSpans` = original
`MarkdownRenderer` port, `MarkdownBitmap` = `TextObjectRenderer` port + pure `Sizing`); 28 JVM
tests in `:ext-markdown` (two original suites ported + `MarkdownParserTest` 10 + `HeadingScaleTest`
3 + `MarkdownBitmapSizingTest` 4). `assembleDebug` builds six modules; `:app:assembleRelease`
compiles; all-module `testDebugUnitTest` green. APK 2.5 MB. Devices: installed on SNN + NA5C +
MIP11 — `MARKDOWN_RENDERER` resolves to `MarkdownRendererService` on all three, no launcher
activity, signature `2c85d31` = the app's, NA5C enable dance done (nothing of ours disabled), the
app launches with no crash lines. Two test-expectation fixes during the run (no code change): the
parser's unclosed-`**` case is the original's behaviour (a later single `*` still closes an italic),
and a rendered width can never exceed the edge cap because `maxWidthPx ≤ MAX_IMAGE_EDGE_PX` is
checked first — only the height can. Nothing binds the point until H3.

---

### Phase H1 — Core content objects: fresh schema, store, renderer bridge, undo, g-paper 0.1.1
**Status:** ✅ Complete (commit 62771f3) — user-verified SNN + NA5C + MIP11 2026-08-17

**Outcome (2026-08-17, user-verified all 9 checklist items on all three devices; items 5/7 read over adb: taps fire on ratta/onyx/generic engines, objects survive `am force-stop`):** g-paper **0.1.1 = commit `e76e305`**
(`PaperListener.onSelectionTapped(x, y)`: `CanvasPaperView.lassoDragFinish(x, y, fromFinger)` fires it in
the sub-threshold branch — stylus at once, finger via `scheduleEscrowedTap` (`PEN_ACTIVE_TAIL_MS`, dropped
if the pen turns active or the selection changed); engine-agnostic — the Onyx raw path and Ratta's base
path both end in `lassoDragFinish`; demo log line; `api.md` + `host-responsibilities.md`; version pins
in README / integration-guide / consumer-smoke; `publishToMavenLocal`; core JVM tests green). Paper:
`SOIL_VERSION` **stays 1** (Q1) — `x`/`y` in DDL + entity, `TYPE_OBJECT`, `SoilDao.liveChildIds` /
`objectsOf` / `updateObject` / `moveObjects`; `ExtensionContract.MAX_OBJECT_TEXT_CHARS = 20 000` (pulled
forward from the H3 contract list — `ObjectRows` caps with it); `notebook/PageObject`, `ObjectRows`,
**`SoilWriter`** (the channel + touch discipline lifted out of `StrokeStore`; both stores share it;
`NotebookSession.writer`), `ObjectStore`, `ObjectRenderer` (placeholder + cache path, live-drag pair,
`hitTargets`), `ObjectRenderCache`, `DebugHooks`; `UndoRedoStack` `ObjectCreated` / `ObjectsDeleted` /
`ObjectEdited` / `Moved.objectIds` / `Page.childIds` with `revert` / `reapply` tables; `NotebookActivity`
`liveObjects` + `currentSelection`, objects loaded with strokes on open / navigate, `onSelectionMoved`
contentIds, `onSelectionTapped` logged; debug ⋯ "Insert test object" / "Delete selection". JVM:
`ObjectRowsTest` (7) + `UndoRedoStackTest` (4), whole suite green; `assembleDebug` + `:app:assembleRelease`
build. MIP11 adb smoke: new notebook → Insert test object → dashed box at the page centre (screencap) →
`am force-stop` → reopen → "loaded: 0 strokes, 1 objects". Trap re-hit: MIP11 resets `log.tag` to `I`
by itself — re-set before every log read. Close-out fix from the user's verification: the Phase-4 page-delete
dialog still said "Its ink cannot be recovered" — false since page delete joined undo; now "Undo
(two-finger double-tap) brings it back until you close the notebook." Docs: `docs/notebook.md` §"Content objects" + undo rows,
`docs/data.md` §"Object rows" + the no-migration note, `CLAUDE.md` (content-objects rule, g-paper 0.1.1).

**Phase-start answers (2026-08-17):** Q1 **keep `SOIL_VERSION = 1`** — no bump, no migration; the
DDL/entity gain `x`/`y` and a v1 file fails to open on Room's identity-hash check (the reason surfaces
through the existing open-failed toast; the library card is unchanged, the user deletes the notebook
by hand) · Q2 `liveChildIds` covers strokes + objects (page delete / undo carry both) · Q3 the debug
"Insert test object" / "Delete selection" items are removed in H5 · Q4 finger tap escrowed + palm-gated,
stylus tap fires on any selection contents (strokes-only included).

**Goal:** the core can store, load, draw (placeholder + cached bitmap), select, move, delete and undo
objects on a page — with **no extension involved**. Verified through JVM tests and a **debug-only ⋯
item "Insert test object"** that creates an object row with identity `debug:box` and payload
`"test"` at the page centre (drawn as the placeholder — no provider exists for it) so selection,
move, delete and undo can be exercised on-device. The g-paper tap callback lands.

**Questions to resolve at phase start:**
1. `SOIL_VERSION` → 2 with no migration (rec.; a v1 file's open fails with reason "made by an older
   Paper build — delete it from the library") / keep 1 and let Room's identity-hash check fail? Also:
   should the library card show anything for an unopenable notebook (rec.: no — the failure is the
   open toast, the user deletes it)?
2. Objects and the page delete / undo reconcile: `liveChildIds` covering strokes + objects (rec.) —
   confirm.
3. The debug "Insert test object" item — keep after this arc (rec.: remove in H5) / keep?
4. `onSelectionTapped` semantics for the finger — escrowed like the component's tap-to-dismiss (rec.)
   — confirm; and does a stylus tap on a *stroke-only* selection also fire (rec.: yes; the host ignores
   what it doesn't need)?

**Deliverables**
1. **g-paper 0.1.1**: `onSelectionTapped(x, y)` per "g-paper 0.1.1" (core selection controller +
   both device engines if the tap path is engine-specific — check `~/git/g-paper/CLAUDE.md`
   architecture notes first), demo hook (log line), `docs/api.md` + `host-responsibilities.md`,
   `GPAPER_VERSION=0.1.1`, `publishToMavenLocal`, g-paper commit. Paper pins 0.1.1.
2. `data/soil`: `SoilSchema` (x/y columns in the DDL, `TYPE_OBJECT`, `SOIL_VERSION = 2`),
   `SoilObjectEntity` (`x`, `y`), `SoilDao` (`liveChildIds(pageId)`, `objectsOf(pageId)`,
   `updateObject(id, text, x, y, w, h, at)`, `moveObjects(ids, dx, dy, at)`); `docs/data.md`.
3. `notebook/`: `PageObject`, `ObjectRows` (+ JVM test: round trip, null x/y rejected, payload cap),
   `ObjectStore` (shared writer with `StrokeStore` — refactor the channel into a small `SoilWriter`
   both use, no behaviour change for strokes), `ObjectRenderer` + `ObjectRenderCache` (placeholder
   path only — no provider calls yet; the cache API takes bitmaps so H4 only fills it), `liveObjects`
   mirror, `NotebookActivity` wiring: `addContentRenderer`, load objects with strokes on every page
   load / navigate, `onSelectionMoved.contentIds` → move + undo, `Action.Page` with `childIds`,
   `onSelectionTapped` logged (`Slog.d`) — nothing more yet.
4. `UndoRedoStack`: `ObjectCreated`, `ObjectsDeleted`, `ObjectEdited`, `Moved` (+ `objectIds`),
   `Page` (`childIds`) + revert/reapply in `NotebookActivity`; **Delete of a selection** is not on a
   toolbar yet — H1 exposes it as the debug ⋯ item "Delete selection" so `ObjectsDeleted` is
   exercisable (removed with the test-object item in H5).
5. Debug ⋯: "Insert test object" + "Delete selection".
6. JVM tests: `ObjectRowsTest`, `PageMathTest` additions if `childIds` changes anything, `UndoRedoStack`
   ordering test for the new actions.
7. Docs: `docs/notebook.md` §"Content objects" (rows, renderer, cache, undo table rows), `docs/data.md`.

**Tests**
- JVM green; `assembleDebug`; `:app:assembleRelease` compiles.
- **User device checklist** (app only; extensions as installed):
  1. Existing (v1) notebooks: open → toast with the "older Paper build" reason → back in the library;
     delete them. New notebook creates and opens.
  2. ⋯ → Insert test object → a dashed box appears at the page centre; pen ink writes over/around it.
  3. Lasso the box → selection box; drag it with the pen → it moves live (real live drag, no ghost);
     drag with a finger → moves; two-finger double-tap (undo) → back; three-finger (redo) → moved again.
  4. Lasso box + strokes together → drag → both move; undo → both back.
  5. Tap inside the selection with the pen → `logcat -s NotebookActivity` shows `selection tapped x,y`;
     with a finger → same; a finger drag still moves; a tap outside dismisses.
  6. ⋯ → Delete selection (box selected) → gone; undo → back; redo → gone.
  7. Flip away and back → the box is where it was; kill the app (`am force-stop`) → reopen → still there.
  8. Delete the page → undo → the page returns **with** its box and strokes.
  9. Regression: Templates / Naming / Recognize page unchanged (E1 1–3, N1 2–3, M1 3 quick form).

**Close-out:** status ✅ + Outcome (incl. the g-paper commit hash + 0.1.1 timings); docs; memory;
commit + push (Paper) and g-paper commit.

---

### Phase H2 — The selection toolbar + the contribution API + the edit-dialog shell
**Status:** ⬜ Not started

**Goal:** a core-owned floating selection toolbar exists (Delete), it can host contributed actions
described by `SelectionAction` (with a sub-toolbar and active-state), and the core has the
`ObjectEditDialog` shell — all driven by **local fakes** in this phase (no extension binding yet): a
debug-only `FakeContributions` object supplying a "T" action with three sub-actions and an `EditSpec`
for the `debug:box` test object, so the chrome is verified on all three devices before a real
extension is behind it.

**Questions to resolve at phase start:**
1. Toolbar anchoring — below the selection, centred, flip above, clamp (rec.; the original's
   `positionPopover`) — confirm; and does the sub-toolbar anchor to the toolbar (rec.) or the selection?
2. Text-fallback buttons — same size as icon buttons, label centred, ≤ 6 chars (rec.) — confirm.
3. Should the toolbar show while the pen is still active (rec.: no — `whenPenIdle`, as every chrome
   change) — confirm; and hide during a selection drag (rec.: yes) — confirm.
4. Edit dialog IME behaviour on BOOX/Ratta — follow `docs/design-system.md` (rec.) — confirm.

**Deliverables**
1. `:extension-api`: `SelectionAction.aidl` + Parcelable, `EditSpec.aidl` + Parcelable,
   `ActionApplies`, `Requires`, `IconNames`, `ExtensionContract.MAX_ACTIONS` / `MAX_SUB_ACTIONS` /
   `MAX_ACTION_*` / `MAX_EDIT_*` (JVM tests: require-checks). No AIDL *interface* yet (H3).
2. `:app` `core/IconCatalog` + drawables (`ic_heading`, `ic_h_1`…`ic_h_6`, others already present);
   `extension/ActionCaps` + `EditCaps` (pure, JVM-tested: label/hint truncation, id validation, depth-2
   sub-actions dropped, `appliesTo == 0` dropped, unknown icon → null).
3. `notebook/SelectionToolbar` + `SubToolbar` + `SelectionActions.merge` (pure, JVM-tested: Delete
   first, provider order, INK/OBJECT filtering, mixed selection → core only), layout XML, exclusion
   rects + `overChrome`, show/hide/anchor rules, `state_selected` for active sub-actions; the toolbar
   invokes a `Listener { onDelete(); onAction(providerRef?, action) }`.
4. `notebook/ObjectEditDialog` (`AlertDialog`, `EditSpec` → field; Save/Cancel; returns the text) +
   `res/layout/dialog_edit_object.xml`.
5. `NotebookActivity`: Delete wired to the H1 path (the debug "Delete selection" item goes away
   now); `onSelectionTapped` on a single selected object → `FakeContributions.editSpec` → dialog →
   log the result (no payload change yet — H4); the debug fake supplies the "T" action whose leaves
   `Slog.d` their id.
6. Docs: `docs/notebook.md` §"Selection toolbar" + §"Edit dialog"; `docs/extensions.md`
   §"Selection-toolbar contributions (contract)" + the **UI rule** (tiered: description/core-drawn ·
   off-paper screens later · never remote UI or code over the paper).

**Tests**
- JVM green; `assembleDebug`; `:app:assembleRelease` compiles (fakes are debug-only).
- **User device checklist:**
  1. Lasso some strokes → after the pen lifts, a bordered toolbar appears just below the selection with
     [🗑] and [T]; no ink lands under it (write across it — nothing); it never overlaps the top bar or
     bottom strip (lasso near the top / bottom edge → it flips / clamps).
  2. Tap [T] → sub-toolbar with three items appears next to the toolbar; tap one → `logcat` line; the
     sub-toolbar closes; tap outside → selection + toolbar gone.
  3. Drag the selection → the toolbar hides during the drag and reappears at the new place.
  4. Insert test object → lasso it → toolbar shows [🗑] and the object's fake actions (not the ink
     ones); lasso object + strokes → [🗑] only.
  5. [🗑] on strokes → gone + undo restores; on the object → same; on both → one undo restores both.
  6. Tap the selected test object with the pen → "Edit" dialog with the fake text; type, Save → log
     shows the text; Cancel → nothing. Keyboard: BOOX shows/dismisses per design-system rules; SNN
     hardware keyboard types (per the arc-2 trap notes, IME visible).
  7. Toolbar text-fallback: the fake action with an unknown icon name shows its label as text.
  8. Regression: flip/insert/undo gestures still work while no selection is active; page delete OK.

**Close-out:** status ✅ + Outcome; docs; memory; commit + push.

---

### Phase H3 — The `OBJECT_PROVIDER` point, `NSE · Heading`, the two proxies, `RecognizerReadiness`
**Status:** ⬜ Not started

**Goal:** `IObjectProvider` exists; `NSE · Heading` installs and answers every call correctly under
JVM tests (`HeadingText`) and a host-side **debug ⋯ "Probe object providers"** action that binds each
provider and logs `describeTypes` / `describeActions` / a `render` of a fixed payload through the real
proxies (timings, sizes — never text) so the two-hop paths are proven before H4 wires the UI. The
consent flow is promoted to main. Nothing user-visible in release.

**Questions to resolve at phase start:**
1. Proxy forwarding on the Binder thread via `runBlocking` (rec.; not Main) — confirm; and the
   two-hop budgets 15 s / 8 s (rec.) — confirm.
2. `createFromInk` writing area = the selection bounds' size, page-absolute coordinates, no
   pre-context (rec.; the original's single-shot path) — confirm.
3. Heading edit `maxChars` 500 (rec.) / other?
4. Which recognizer status the proxy exposes to a provider — all four (rec.; the provider may `status()`
   before `recognizeInk`) — confirm; `prepare()` through the proxy is a **no-op** on the host side
   (consent is the core's, `RecognizerReadiness` runs before the call) — confirm.

**Deliverables**
1. `:extension-api`: `IObjectProvider.aidl`, `CreatedObject.aidl` + Parcelable, `ExtensionContract
   .ACTION_OBJECT_PROVIDER` + `MAX_OBJECT_TEXT_CHARS` / `MAX_TYPE_ID_CHARS` / `MAX_OBJECTS_PER_PAGE` /
   `RECOGNIZER_REQUIRED` / `MARKDOWN_REQUIRED` / `objectIdentity`; JVM tests.
2. `settings.gradle.kts` `include(":ext-heading")`; `:ext-heading` exactly as in "Extension side"
   (`ObjectProviderService`, `HeadingText`, `HeadingActions`, strings, icon, `HOST_PACKAGE`); JVM
   tests `HeadingTextTest` (prefix 1–6, strip, withLevel, levelOf malformed, blank handling, newline
   folding).
3. `:app` `extension/`: `ExtensionRegistry.markdownRenderer` + `objectProviders`; manifest `<queries>`
   ×2; `MarkdownClient` (+ `RenderCaps`, JVM-tested) ; `ObjectProviderClient` (+ the `ActionCaps` /
   `EditCaps` from H2 applied inward, `CreatedObject` validation, identity check of returned `typeId`);
   `ProxyGate` (JVM-tested: uid mismatch, revoked, happy path); `RecognizerProxyBinder`,
   `MarkdownProxyBinder` (minted / revoked in `ObjectProviderClient.createFromInk` / `render`);
   `RecognizerReadiness` (moved out of `NotebookDebugMenu`, strings to main; the debug menu delegates —
   **no behaviour change**, M1 checklist 3/7/8/13 re-run as regression).
4. Debug ⋯ "Probe object providers" (logs only) — removed in H5.
5. Docs: `docs/extensions.md` §"ObjectProvider (contract)", §"The Heading extension", §"MarkdownRenderer
   / ObjectProvider — host behaviour", §"The capability pattern" flipped from "recorded" to "built"
   (`RecognizerProxyBinder`, `MarkdownProxyBinder`, `ProxyGate`); `README.md`; `CLAUDE.md`.

**Tests**
- JVM green (all seven modules); `assembleDebug` seven modules; `:app:assembleRelease` compiles.
- Claude-side per device (all three extensions + Markdown + Heading installed; NA5C enable dance for
  **five** packages + the app): install; `dumpsys` resolves `OBJECT_PROVIDER` → `ObjectProviderService`
  and `MARKDOWN_RENDERER` → `MarkdownRendererService`; ⋯ → Probe → `logcat -s ObjectProviderClient
  MarkdownClient RecognizerClient ObjectProviderService MarkdownRendererService` shows: one bind pair
  per provider, `describeTypes=[heading]`, `actions=1 (6 sub)`, `render 24x… px in T ms` **through the
  Markdown proxy** (a `MarkdownRendererService` line inside the `ObjectProviderService` call), no
  `leaked ServiceConnection`, no `SecurityException`, no text. `pm disable-user` the Markdown ext →
  Probe → render fails with `MARKDOWN_REQUIRED` typed on the host (log), nothing crashes; re-enable.
- **User device checklist:** (1) Settings → Apps shows "NSE · Heading Dev"; (2) debug ⋯ Recognize page
  still works exactly as before (the promoted flow: fresh device → consent dialog → download →
  result; model present → straight to the result); (3) Templates / Naming unchanged.

**Close-out:** status ✅ + Outcome (two-hop timings per device); docs; memory; commit + push.

---

### Phase H4 — End-to-end: write → lasso → H → size → heading; edit; re-size; undo; devices
**Status:** ⬜ Not started

**Goal:** the user story works on all three devices: lasso ink → **H** → **H1–H6** → the words become a
heading drawn by the Markdown extension, the ink is gone, the heading is selected; pick another size
→ it re-renders; tap its text → edit the plain words → re-render; move / delete / undo everything;
extensions absent → placeholders and honest dialogs.

**Questions to resolve at phase start:**
1. Selecting the new heading right after creation (rec.: yes, `setSelection`, toolbar shows H with the
   level active) — confirm.
2. The "Working…" popup text while recognizing/rendering (rec.: "Recognizing…" — reuse the M1
   popup) — confirm.
3. When both recognizer *and* Markdown are absent, which dialog first (rec.: recognizer — nothing can
   be created without it) — confirm.
4. Render pass trigger for the cache: on page load + after any create/edit/apply (rec.) — and should a
   page with many objects render them one bind per provider (rec.) — confirm.

**Deliverables**
1. `NotebookActivity` + `SelectionToolbar`: contributions from `ExtensionRegistry.objectProviders`
   (fetched once per open on IO; refreshed on resume if the set changed) replace the H2 fake (fake +
   "Insert test object" stay debug-only until H5); the INK / OBJECT leaf paths, `requires` guards,
   `RecognizerReadiness` before `createFromInk`, the create → erase → select sequence, `applyAction`,
   `onSelectionTapped` → `describeEdit` → `ObjectEditDialog` → `applyEdit`, all undo actions,
   `MAX_OBJECTS_PER_PAGE`, every failure text (Appendix A strings), the render pass wired to
   `ObjectProviderClient.render` with the Markdown proxy (`ObjectRenderCache` fill, `whenPenIdle`
   `notifyContentChanged`, width/height persisted from the image, anchored top-left).
2. Objects on cover snapshots: `renderToBitmap` already includes host content (verify; if the cover
   misses objects, note it — g-paper draws content renderers into the committed layer, so it should).
3. Docs: `docs/notebook.md` §"Objects — actions, edit, render pass" (flows + failure table);
   `docs/extensions.md` §host behaviour (final flows).

**Tests**
- JVM green; builds.
- **User device checklist** (app + all five extensions; model present):
  1. Write "Meeting notes" → lasso → toolbar [🗑] [H] → tap H → sub-toolbar H1…H6 → tap **H2** →
     "Recognizing…" popup → the ink disappears and a bold "Meeting notes" heading appears at the ink's
     top-left, selected, toolbar showing H with **H2** marked. Log: one `createFromInk` (with a nested
     `RecognizerClient` line) + one `render` (nested `MarkdownRendererService`), durations, no text.
  2. With it selected tap H → **H5** → smaller heading, still selected, H5 marked; undo → H2 again;
     redo → H5.
  3. Tap the heading text with the pen → "Edit heading" dialog prefilled `Meeting notes` (**no** `#`s)
     → change to `Agenda` → Save → re-rendered; undo → `Meeting notes`; Cancel path leaves it.
     Same with a finger tap.
  4. Undo repeatedly from a fresh heading → the heading vanishes **and the ink returns** in one step;
     redo → ink gone, heading back.
  5. Drag the heading (pen and finger) → moves live; lasso heading + strokes → move both; [🗑] on the
     heading → gone; undo → back.
  6. Write scribble that isn't words → H → H1 → dialog "Couldn't read the handwriting"; the ink is
     untouched; no object.
  7. Long heading (write across the page) → H1 → single line ellipsized at the page's right edge.
  8. Six headings H1–H6 on one page → sizes step down visibly; flip away/back → all six re-render from
     rows (log: one provider bind, six renders); force-stop the app → reopen → same.
  9. `pm disable-user` **NSE · Markdown** → reopen the notebook → each heading is a dashed box; lasso
     one → [🗑] + H; tap H → H3 → dialog "…needs the Markdown extension" (nothing changes); tap the
     text → dialog opens (edit works: payload changes, box re-sizes only when Markdown returns);
     re-enable → reopen → headings back at their new levels.
  10. `pm disable-user` **NSE · ML Kit** → lasso ink → H → H1 → dialog "…needs a handwriting
      recognizer extension"; existing headings still render, edit, re-size; re-enable.
  11. `pm disable-user` **NSE · Heading** → toolbar shows [🗑] only; headings are dashed boxes, movable
      and deletable; re-enable → everything back.
  12. Fresh device path (MIP11 or after `pm clear` of the ML Kit ext): first H → consent dialog →
      Download → progress → the heading appears with no second tap.
  13. Cover in the library shows the heading text after closing the notebook.
  14. Airplane mode → the whole flow still works (model present; Markdown is local).
  15. Regression: page flip/insert/delete, Templates, Naming, Recognize page.
- Claude-side log hygiene on SNN: binds = unbinds, no leaked connections, no `SecurityException`, no
  recognized text or payload in any line on either side; the automated per-device agent run covers
  items 1, 5 (finger), 6, 8–11, 13–15 where uiautomator can reach (SNN typing impossible; note which
  were user-verified).

**Close-out:** status ✅ + Outcome (timings per device: create, render warm/cold, page-load render
pass for six headings); docs; memory; commit + push.

---

### Phase H5 — Hardening, review, boundary audit, docs freeze
**Status:** ⬜ Not started

**Goal:** the object model, the two points, the proxies and the toolbar API are trustworthy enough to
be the pattern the next object extensions follow.

**Questions to resolve at phase start:**
1. Anything observed in H4 the user wants changed before freezing (wording, sizes, anchoring)?
   (rec.: no — freeze as built)
2. Confirm scope freeze: fixes only; remove the debug "Insert test object" / fake contributions /
   "Probe object providers" (rec.: remove all three).

**Deliverables**
1. `/code-review high` over the arc's range (`git diff <H0 base>...HEAD` — the **range**; base
   recorded in H0's Outcome); fix confirmed findings. Also review the g-paper 0.1.1 diff.
2. **Boundary audit** rows added to `docs/extensions.md` and walked:
   - **18 — Outward payload of MarkdownRenderer is markdown text + layout numbers only** (source ≤ cap,
     maxWidth, dpi, maxLines, padding; no ids, names, keys, paths).
   - **19 — Outward payload of ObjectProvider is the object's own payload + geometry + the two proxies**;
     `createFromInk` adds bare ink (row 14's widening re-recorded); never ids, names, keys, paths.
   - **20 — Both proxies are uid-bound, per-bind, revocable, capped** (`ProxyGate`; minted in the
     client's call, revoked in its `finally`; caps re-applied inward; forwarding through the core's own
     clients with their own bind/timeout/signature check).
   - **21 — Inward payloads are validated** (`ActionCaps`, `EditCaps`, `RenderCaps`, `CreatedObject`
     typeId ∈ describeTypes, `RenderedImage` header size == declared, edge caps, payload cap).
   - **22 — The core stores objects, never renders/parses them; nothing but the payload the provider
     chose is stored** (rule 16 amended and recorded).
   - **23 — Absent provider = placeholder; failure changes nothing** (ink untouched on failed create;
     payload untouched on failed apply/edit; every failure a core-owned dialog).
   - **24 — Contributed UI is drawn only by the core** (no extension pixels/layouts/code over the paper).
   - Re-walk rows 1, 6, 7 for the two new clients and the proxies' inner calls.
3. `docs/extensions.md` final: "Adding an object point" (rules 18–23) after rule 17; "The capability
   pattern" marked built (both proxies); "Writing an extension" gains an object-provider paragraph
   and a markdown-renderer paragraph; `README.md`; `CLAUDE.md` standing rules (object rows, the UI
   rule, proxies, `SOIL_VERSION 2`, g-paper 0.1.1, `:ext-markdown` / `:ext-heading` build lines).
4. Remove the debug scaffolding (per Q2). This file frozen; memory updated (arc complete).

**Tests:** full H4 checklist on all three devices + H1 items 3–8 + H2 items 1–5 + M1 3/7/8/13 + E1 1–3
+ N1 2–3 + v0 regression subset (create/open/write/flip, library create/rename/move/delete,
cold-launch reopen).

**Close-out:** status ✅ + Outcome; commit + push `paper`.

---

## Appendix A — Constants + strings (this arc)

| Name | Value |
|---|---|
| `ACTION_MARKDOWN_RENDERER` / `ACTION_OBJECT_PROVIDER` | `…extension.MARKDOWN_RENDERER` / `…extension.OBJECT_PROVIDER` |
| `META_API_VERSION` / `API_VERSION` | unchanged (`…extension.API_VERSION` / `1`) |
| `MAX_MARKDOWN_CHARS` / `MAX_OBJECT_TEXT_CHARS` | 20 000 / 20 000 |
| `MAX_IMAGE_EDGE_PX` / `MAX_RENDER_BYTES` / `RENDER_PADDING_MAX_PX` | 4 096 / 16 MiB / 64 |
| `MAX_ACTIONS` / `MAX_SUB_ACTIONS` / `MAX_ACTION_ID_CHARS` / `MAX_ACTION_LABEL_CHARS` / `MAX_ACTION_HINT_CHARS` | 16 / 16 / 32 / 6 / 40 |
| `MAX_TYPE_ID_CHARS` / `MAX_EDIT_TITLE_CHARS` / `MAX_EDIT_HINT_CHARS` / `MAX_EDIT_TEXT_CHARS` / `MAX_OBJECTS_PER_PAGE` | 32 / 40 / 60 / 4 000 / 200 |
| `ActionApplies` | `INK 1` · `OBJECT 2` |
| `Requires` | `RECOGNIZER 1` · `MARKDOWN 2` |
| `IconNames` | `heading` `h-1`…`h-6` `text` `edit` `x` `check` `plus` `trash` |
| Timeouts | bind 3 s · Markdown `render` 5 s · ObjectProvider describe/apply/edit 2 s · `createFromInk` 15 s · `render` 8 s |
| Typography (Markdown ext) | base 24 sp bold; H1 2.0 · H2 1.75 · H3 1.5 · H4 1.25 · H5 1.1 · H6 1.0; heading padding 8 dp; single line, ellipsize END |
| `.soil` | `SOIL_VERSION 2` (fresh, no migration); `TYPE_OBJECT = "object"`; object identity `<pkg>:<typeId>` in `style`; payload in `text`; bounds `x y width height` |
| g-paper | 0.1.1 — `PaperListener.onSelectionTapped(x, y)` |
| Packages | `…ext.markdown` / `…ext.heading` (debug `.dev`) |
| Strings (main) | `objects_working` "Recognizing…" · `objects_unreadable_title` "Couldn't read the handwriting" / body "Try writing larger or clearer, then lasso the words again." · `objects_needs_recognizer` "This action needs a handwriting recognizer extension (for example NSE · ML Kit)." · `objects_needs_markdown` "This action needs the NSE · Markdown extension." · `objects_provider_failed` "The %1$s extension didn't respond — try again." · `objects_page_full` "This page already holds the maximum number of objects." · `objects_edit_save` "Save" · `selection_delete_hint` "Delete selection" · `notebook_open_failed_version` "made by an older Paper build — delete it from the library" |

## Appendix B — Allowed dependencies (in addition to arcs 1–3)

```
:extension-api   — unchanged (none)
:ext-markdown    — project(":extension-api"), testImplementation junit:junit:4.13.2
:ext-heading     — project(":extension-api"), testImplementation junit:junit:4.13.2
:app             — g-paper 0.1.1 (mavenLocal) — the version pin only; nothing else
```
No markdown library (the parser is the original's zero-dependency port), no image library, no
`kotlin-parcelize`, no new plugins.

## Appendix C — Build & install (this arc)

```sh
cd ~/git/g-paper && ./gradlew publishToMavenLocal          # H1: 0.1.1
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug && ./gradlew testDebugUnitTest      # seven modules after H3
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-markdown/build/outputs/apk/debug/ext-markdown-debug.apk
adb -s <serial> install -r ext-heading/build/outputs/apk/debug/ext-heading-debug.apk
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.markdown.dev   # BOOX: re-run after a few
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.heading.dev    #   seconds; confirm `pm list packages -d`
adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.markdown.dev
adb -s <serial> logcat -s NotebookActivity ObjectProviderClient MarkdownClient RecognizerClient \
    ObjectProviderService MarkdownRendererService HandwritingRecognizerService ExtensionRegistry
```

## Appendix D — Reference map

| Concern | Where |
|---|---|
| Original heading (behaviour to mirror) | `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/data/{HeadingObject,HeadingStroke,ObjectColumns}.kt`, `NotebookActivity.createHeadingFromStrokes` (~L6956), `showHeadingTextEditDialog` (~L7814), `changeHeadingLevel` (~L7936), `updateFloatingSelectionToolbar` (~L8042), `positionPopover` (~L8125); `docs/content-objects.md` |
| Original markdown (to port verbatim) | `…/core/markdown/{MarkdownParser,MarkdownRenderer,TextObjectRenderer}.kt` (parser pure; renderer = spans incl. `headingSizeMultiplier`; TextObjectRenderer = StaticLayout measure/draw) |
| Original selection toolbar (layout to mirror) | `apps/notesprout_android/app/src/main/res/layout/activity_notebook.xml` `floatingSelectionToolbar` (~L240) + `headingTypeSubmenu` (~L398); icons `ic_heading`, `ic_h_1..3`, `bg_heading_type_selected` |
| g-paper host content + selection | `~/git/g-paper/gpaper-core/src/main/java/com/symmetricalpalmtree/gpaper/core/{PaperListener.kt, render/ContentRenderer.kt, model/Selection.kt}`, `docs/api.md` §"Host content extension point" + selection §, `host-responsibilities.md` (tap escrow), demo `MainActivity` (host-object tap + live-drag renderer) |
| Client / registry / bind path | `app/.../extension/{ExtensionBinder,ExtensionRegistry,RecognizerClient,InkCaps,NamerClient,TemplateProviderClient}.kt` |
| Gate to copy for the proxies | `app/.../data/extstore/{ExtensionStoreBinder,ExtensionStoreGate}.kt` (+ its JVM test) |
| Consent flow to promote | `app/src/debug/.../notebook/NotebookDebugMenu.kt` (`promptDownload`, `downloadThenRecognize`, `showDownloadFailed`), `core/Connectivity.kt`, `core/Dialogs.kt` |
| Notebook screen | `notebook/{NotebookActivity,NotebookSession,StrokeStore,StrokeRows,UndoRedoStack,PageGestures,NotebookToolbar}.kt`, `res/layout/activity_notebook.xml`, `docs/notebook.md` |
| Schema | `data/soil/{SoilSchema,SoilObjectEntity,SoilDao,SoilDatabase}.kt`, `docs/data.md`, root `docs/soil-file-format.md` (portable note) |
| SharedMemory handshake to copy | `ext-templates/.../TemplateProviderService.kt` (`onTransact` finally, `ThreadLocal<SharedMemory>`), `TemplateProviderClient.copyOut` |
| Extension reference implementations | `ext-naming/`, `ext-mlkit/` (Gradle, manifest, `HostCallerCheck`, Binder-safe exceptions, `BuildConfig.DEBUG` logging) |
| Design system for the toolbar / dialog | root `docs/design-system.md` (AlertDialog styling, BOOX IME), `docs/toolbar.md` (dimen-driven buttons), `res/values/dimens.xml`, `Widget.Notesprout.ToolbarButton` |
