# Sketch (arcs 43–44)

**NSE · Sketch** gives a notebook page a second surface beside its ink: one raster picture, a
graphite pencil and a rubbing eraser, over a page-sized bitmap. A notebook created from the third
radio (Handwritten / Text / **Sketch**) opens straight into this face; any page of it may carry one
sketch, the way any page may carry one document. The host owns every `.soil` read and write, as it
does for the document editor; the extension owns nothing but the pixels on the glass while the
showing lasts.

This is the user's 2026-09-15 decision, landing SN's **tenth** extension point (`ACTION_SKETCH` /
`ACTION_SKETCH_SCREEN`) and its **sixteenth** module — the second, after **NSE · Bible**, living
outside `apps/notesprout_sn`'s own Gradle root (`extensions/sketch/`, included by
`settings.gradle.kts`'s `projectDir`). `extensions/sketch/SKETCH_PLAN.md` is both the plan and the
ledger: the fourteen locked decisions, the derived shape, and the phase-by-phase record (K0–K7)
that every number and trap below comes from. Built on branch `sketch`; frozen at K8. The seam
itself — `ISketch`, `ISketchHost`, the two host-side-stub tails, the boundary-audit rows — is
documented once, in
[`apps/notesprout_sn/docs/extensions.md`](../../../apps/notesprout_sn/docs/extensions.md) § "The
Sketch point (arc 43)"; this doc is the feature side and links there rather than repeating it.

Arc 44 "Pencils" (2026-09-17, branch `pencils`, `PENCILS_PLAN.md` the plan + ledger, phases
T0–T5) grew the one pencil into **six shades, twelve leads and a gel pen**, all of it remembered
on the device.

**Engine.** g-paper is pinned at **0.1.38** (Phase 25, "Graphite on paper", 2026-09-17, post-freeze
maintenance on the user's Manta screencap — the 96 px lead baked as "a series of tiny lines": each
cross-section is a rigid comb turned to the smoothed travel direction, and ~2° of wobble × a 48 px
lever arm piles the rim's combs up every ~4 px. `GraphiteGrain` now loosens flecks along the
stroke in proportion to their distance from the centre line, streaks the skate *along* the stroke
instead of banding it across, and mottles by a **page-space** tooth field the sheet shares under
every stroke; total ink within 1 %, so the shade ladder below did not move. `gpaper-core` only, no
API change; walked by the user's hand on the Manta: "Sooooo much better"), over 0.1.37 (Phase 24,
"The lead goes wide on Ratta", the T3 hand walk of 2026-09-17 — `RattaEmr.EMR_MAX` lifted from 1200 to **9600**, 96 px, so every lead the
sketch face now offers previews at the width it bakes), over 0.1.36 (Phase 23, "Pencil preview
tones", T1 — `RattaInkMap.pencilPreviewFor`, arc 44's own preview ladder beside the untouched
`firmwareColorFor`), 0.1.35 (Phase 22, "The pencil bakes upright on Ratta", the 2026-09-17 Manta
walk — see Traps), 0.1.34 (Phase 21, "The door closes", K8), 0.1.33 (Phase 20, "A mark says where
it landed", K6) and 0.1.32 (Phase 19, "Ratta and the raster page", K1). The
four values K1 measured on the Nomad — cadence **16 ms**, `PENCIL`'s EMR floor **120**, a
`DARK_GRAY` preview needle, and a **constant bake pressure of 0.5** for `PENCIL` on Ratta — are
plain constants in `gpaper-ratta` and `gpaper-core`; the measurement door (`RattaTuning`, "a
measurement door, not host API") closed at K8 (g-paper Phase 21 → 0.1.34) and no longer exists; the numbers did not change, only their mutability. Nothing reads a system property
to pick a cadence any more. **Arc 44 moved none of the four**: the EMR floor **120**, the bake
pressure **0.5**, the bake tilt **0** (0.1.35's own fix) and the **16 ms** cadence all stand exactly
as K1/K8 froze them — only the EMR *ceiling* moved, and only for the pencil's widest leads.

---

## The decisions

The user's, locked 2026-09-15 (`SKETCH_PLAN.md`); phase-start questions could not reopen them:

1. **Creation — a third radio, Handwritten / Text / Sketch**, exclusive. A Sketch notebook opens
   into the sketch face; every page keeps its ink canvas and optional document, and may also carry
   one sketch. A foreign file claiming both Text and Sketch bits reads as Text (logged).
2. **Where — `extensions/sketch` (`:ext-sketch`)**, the Bible module's own pattern: the tenth
   extension point, screen-owning, the Scratch Pad's shape. The host owns every `.soil` write.
3. **Pencil — Paintsprout's one pencil, as is**: `StrokeStyle.PENCIL`, 1.2 px, `#505050`, pressure
   → darkness on BOOX/Paintsprout, no tilt, no width choice, no colour. (Ratta bakes it at a
   constant pressure instead — see K1's measurement below; decision 3 stands unchanged for every
   other engine.) **Amended by arc 44** ("Pencils", the user's fresh decision of 2026-09-17 — §
   "Arc 44's decisions" below): "no width choice, no colour" is withdrawn — the face offers six
   greyscale shades and twelve leads, plus a gel pen. "No tilt" stands (the bake is upright since
   g-paper 0.1.35), which is exactly why a chosen tone and lead are the only way to shade a
   sketch on Supernote.
4. **Eraser — the rubbing eraser** (g-paper's `RasterRubbing`, 12 px). Live lifting on Supernote
   was a measurement, not a design choice going in; K1 found it real.
5. **Export — the sketch is its own page**, after the ink page, in PDF/PNG export. Document-source
   export is unchanged.
6. **Cover — the sketch of the last-shown page** over paper white; falls back to the ink bake only
   when that page has no sketch.
7. **Page turns — the sketch screen turns pages itself**; the notebook catches up to the page it
   ended on when the screen closes. **Amended at the K5 hand walk** ("that was a mistake if I
   decided that"): the face also inserts and deletes pages, exactly as the notebook does (K5b).
   **Amended again the same day**: the face's own undo/redo gestures reverse one of those, so a
   delete can be taken back without leaving for the notebook (K5b).
8. **Ink bake — a "Bring in ink" door**: the page's bare strokes cross the bind and are composited
   as drawn, in black pen, as one undo entry. Link-wrapped and sticky ink are excluded.
9. **Toggle door — `btnSketch` on the notebook's bottom strip, left of Bible** — a further granted
   exception to "bottom bars are pager-only" (the third, after `btnBible` and the reader's `btnNotes`). Sketch notebooks only; `GONE`
   never disabled; mirrored in the collapsed overflow. The sketch screen's own top bar carries
   **Show pages** back.
10. **Paper — plain white, always**, under the sketch; no template crosses the seam.
11. **Erase page — ink only.** Erase page never touches the sketch row.
12. **Undo chrome — gestures only** (two-/three-finger taps via `PageGestures`); no arrows.
13. **Engine owner — Opus writes the g-paper Ratta phases on a Fable brief**; Fable reviews each
    diff and the Nomad numbers before `publishToMavenLocal` and the SN re-pin.
14. **Recipe** — Fable plans and orchestrates; Opus features; Sonnet scaffolding, XML, strings,
    docs, adb walks on the Nomad (`SN078D10012852`, `.dev` builds); no Haiku; no code review this
    arc (the same waiver family arcs 37–42 took).

### Arc 44's decisions (2026-09-17)

The user's, locked in `PENCILS_PLAN.md`; phase-start questions could not reopen them. Decisions 2,
3 and 4 were later **amended by the hand** on the T3 Nomad walk — both the original and the final
value are given.

1. **Ratta live preview across shades** — map the pencil shade to the nearest usable firmware tone
   (a g-paper change, `RattaInkMap.pencilPreviewFor`); thresholds settled by the user's hand at T1.
   The bake is always the true shade.
2. **Which shades** — original: fifteen, `#000000 … #EEEEEE` in `0x11` steps, default `#555555`
   (level 5). **Amended at T3's walk**: only levels 0, 5 and 9 preview *exactly* as they bake (the
   firmware's three tones), so the list settled at **six** — levels **1, 3, 5, 7, 9, 11** — every
   other rung from 1 to 11, no pure black on the pencil (the gel pen is black). Default stays
   level 5.
3. **Which sizes** — original: five, 1.2 / 2 / 4 / 7 / 12 px. **Amended at T3's walk**: the hand
   walked every lead above 12 px against a lifted EMR ceiling and found none of them laggy, so the
   list widened to **twelve** — 1.2 · 2 · 4 · 7 · 12 · 16 · 20 · 24 · 32 · 48 · 64 · 96 px — in two
   rows of six under the one row of shades. Default stays the finest, 1.2 px.
4. **Gel pen** — `StrokeStyle.PEN`, black, one size, no options. **Amended at T3's walk**: the
   starting width of 3 px (the notebook pen's own) read "a tad small" by hand, moved to 7 px, then
   proved too heavy, and settled at **5 px**.
5. **Chrome** — a third top-bar tool button, Pencil · **Pen** · Eraser. Re-tapping the armed
   Pencil opens one `AnchoredBar` under it — shade swatches over a size row, the first tool-options
   bar on an SN paper screen since P1 removed the tool panels, granted for this face alone. The
   collapsed mini toolbar gains Pen.
6. **Memory** — remembered on the device: two `ISketchHost` tails, `API_VERSION` 18 → 19; the host
   keeps it in device-local prefs, one setting for all notebooks, never in the `.soil`, never in a
   backup.
7. **Pen glyph** — Tabler `ballpen` → `ic_ballpen` in `:sn-screen`'s shared drawables, hint "Pen".
   `ic_pen` (Tabler's pencil) stays on the Pencil.
8. **Naming** — Arc 44 "Pencils" · Notesprout branch `pencils` · g-paper branch `pencil-tones` ·
   letter T · `PENCILS_PLAN.md`.
9. **Models** — Fable orchestrates, writes the AIDL seam and reviews every g-paper diff and every
   doc draft; Opus codes; Sonnet scaffolds (XML / strings / drawables / doc drafts), runs JVM
   tests and walks the Nomad; no Haiku; no code review (arc 43's waiver carried).

---

## The data

### The row

`type='sketch'` (`SoilSchema.TYPE_SKETCH`) is the seventh additive row type, ordered outside every
mark's stacking space at `SoilSchema.SKETCH_ORDER = -1`. One live row per page, parented to the
page; `blob` is a PNG, ARGB_8888, transparent where empty, **exactly** the page's `width`×`height`
— nothing else about the row is used (`SketchRows.toRow`).

**Minted on the first save, never on open.** `SketchRepository.get` only reads; a page nobody has
drawn on has no row at all, so a Sketch notebook costs what its sketches cost. The first save
`upsert`s a whole row with a fresh id; every save after it rewrites the same row's pixels in place
(`SoilDao.setBlob`, `createdAt` kept) — a row per save would make a notebook's size a function of
how long the person worked rather than how much they drew.

**Blank means absent.** The wire form for "clear this page" is an empty byte array; `save` routes
it to `clear`, which soft-deletes the live row (if any) and writes nothing else — a page with no
sketch has no row.

**The IHDR guard stands in front of every decode, both ways.** `PngHeader.matches` (pure, no
Android classes, hand-parsed big-endian off the first 33 bytes) is checked on a save before
anything is written and on a read before anything is composited. A save whose PNG is not exactly
the page's declared size throws `SKETCH_BAD_PNG` and writes nothing. **A row already stored that
fails the guard on read is soft-deleted, never overwritten** (`SketchRepository.get`) — the pixels
are unreadable either way, and leaving them would hand the same refusal to every future reader.

**Size caps.** `SketchContract.WATCH_BYTES` = 4 MB is a silent log line — a page on its way to the
real limit shows up in a walk's log before it is a problem. `SketchContract.MAX_BYTES` = **6 MiB is
a hard refusal**, thrown as `SKETCH_TOO_LARGE`, nothing written, the stored row untouched. This is
the sketch seam's one deliberate deviation from "never refuse" in the whole app: the row crosses
back out through a SQLCipher cursor window, and a PNG written above that window can never be *read
back* — accepting it would trade a refusal the person can see today for pixels that quietly stop
existing later.

**`updatedAt` is sacred.** `SketchDao.sketchDigest` (blob-free, `length(blob)`) is asked first, so
an unequal length settles most saves without reading a megabyte of pixels back out of the file; a
save whose bytes are already stored writes nothing at all, so opening and closing a sketch untouched
never re-flags the notebook for backup.

### Index and meta

`NotebookFlags.SKETCH = 8` (bit 4) travels with `NotebookFlags.TEXT_DOCUMENT` and is mirrored into
`notebook_meta.sketch` at every meta site: `NewNotebookActivity`, `TextDocumentCreate`,
`IndexRepository.createNotebook`/`importNotebookRow`, `NotebookImport.refreshMeta`, `ImportFlow`,
`ExportArtifact.stampExportedAt`, `NotebookSession.refreshMeta` — the `TEXT_DOCUMENT` wipe-trap
rule, applied a second time.

`NotebookKind { HANDWRITTEN, TEXT, SKETCH }` is the one place that turns flags into a kind and
back (`of`/`flagBits`/`fromMeta`/`metaConflicting`). It is pure — it cannot log — so the two sites
that can meet a foreign row (`NotebookSession.open`, the importer) ask `conflicting` beside it and
log the choice themselves. **TEXT wins when both bits are set**: no build of this app can write
both, so a row that does is foreign or damaged, and the two mistakes are not the same size — a text
document opened as a sketchbook shows a blank page where its words are, while a sketchbook opened as
a text document shows an empty editor with **Show pages** one tap away. The recoverable answer wins.

### Reads that must not see the sketch row

- `SoilDao.childrenOf` (untyped, blob-inclusive; feeds export bake, previews, labels, the link
  picker) excludes `type != 'sketch'`.
- `SoilDao.liveDescendantIds` (page copy/cut/paste/delete/undo) **carries** the sketch row — a page
  copy or delete must take its sketch with it.
- `SoilDao.liveErasableIds` = `liveDescendantIds` **minus** the sketch row — what **Erase page**
  actually clears (decision 11). Erase page's undo/redo replay is unchanged: it still replays by id.
- Not in `liveContentIds`, not in any document-staleness whitelist, not in `ORDERED_TYPES` (never
  reachable from an objects paste).

`SketchDao` (separate from `SoilDao`, `DocumentDao`'s shape) holds three reads and no write:
`sketchDigest` (blob-free existence + length), `sketchFor` (the whole row, pixels included — the
one read in the app that pulls a page-sized PNG), and `pagesWithSketch` (which of a notebook's live
pages carry a live sketch, asked once per notebook, ids only — K4's cover and K7's export bundle
both use it instead of a digest per page). `SketchRepository` is the **only writer** — `has` / `get`
/ `save` / `clear` — layered over `SketchDao` + `SoilDao`, pure `suspend`, no Android classes, so
every rule above is provable off-device.

### Clipboard

`ClipEnvelope.MAX_BYTES` (6 MB) is unchanged; a refused envelope that happens to be carrying a
sketch row names it (`ClipMessages.tooLarge`, `clip_too_large_sketch`: *"This page's sketch makes
the copy too large to hold on the clipboard. The clipboard was left unchanged."*) rather than
giving the generic size message. A cross-notebook paste into a non-Sketch notebook carries the row
along and never shows it — the destination simply has a page with a sketch nobody can see.

---

## The seam

`ExtensionContract.API_VERSION` moved 16 → **17** for the birth of `ACTION_SKETCH` /
`ACTION_SKETCH_SCREEN` (`SketchContract.MIN_API_VERSION_FOR_SKETCH = 17`, a birth floor in
`MIN_API_VERSIONS` — no existing floor moved), and → **18** at K5b for a compatible **method** tail
(`SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES = 18`, gating five later `ISketchHost` methods;
only `:ext-sketch` redeclares it, and `MIN_API_VERSION_FOR_SKETCH` itself stays 17 — a screen
declaring 17 still binds and draws, it simply never turns a page into a new one).

`ISketch` carries **no store** — the sixth screen-owning point and the second with none (the tag
manager's shape): nothing here has anything of its own to remember. The bookmark is the notebook's
own page and undo is in-session and dies with the showing; a store lent here would still be an
empty file and a binder to revoke, for nothing. **The one thing that does persist — which tool is
armed, and the pencil's shade and lead (arc 44 / T2) — crosses as three small indices on the held
`ISketchHost`** rather than through a store of the face's own: `toolSettings()` /
`putToolSettings()` are two more tails on the *host's* binder, answered from the host's own
device-local prefs, never from a file this extension owns.

```
interface ISketch {
    void begin(ISketchHost host);   // hold host for the showing
    void end();                     // drop it, and any parked save
}
```

`ISketchHost` is the **second host-side stub** on any SN seam, after `IDocumentHost`, built to its
recipe: minted per showing, uid-gated in every method, revoked with the unbind.

```
interface ISketchHost {
    SketchPageState current();
    SketchPageState requestPage(int direction);            // PAGE_PREV/PAGE_NEXT; same page = edge
    byte[] readSketchChunk(int chunkIndex);
    void   saveSketchChunk(String pageKey, int chunkIndex, byte[] chunk, boolean last);
    int    requestInk(String pageKey);                      // chunk count; 0 = no bare ink, no exception
    List<WireStroke> readInkChunk(int chunkIndex);
    // K5b — method floor 18:
    SketchPageState insertPage(int direction);
    SketchPageState deletePage(String pageKey);
    int    pageContent(String pageKey);                     // bit 1 ink/objects, bit 2 document
    SketchPageState undoPage(String token);
    SketchPageState redoPage(String token);
    // T2 — method floor 19:
    SketchToolSettings toolSettings();
    void   putToolSettings(SketchToolSettings settings);
}
```

**PNG crosses chunked both ways**, at `SketchContract.SKETCH_CHUNK_BYTES` = 512 KiB (`ByteChunks`,
the `TextChunks` recipe applied to bytes — no surrogate pairs, so `join` is plain concatenation).
**An empty image is one empty chunk**, never zero chunks: clearing a page's sketch rides the same
shape as every other save, and the host's accumulator has one path rather than two. Reads are a
pull — every state-answering call parks its PNG atomically in the host's **read window** alongside
the `SketchPageState` it returns, and `readSketchChunk` serves that window; writes are a push —
`saveSketchChunk` accumulates in order from chunk 0, the last chunk commits, and the accumulator
re-checks the running total against `MAX_BYTES` on every chunk (over it: `SKETCH_TOO_LARGE`, the
accumulation reset, nothing written).

**A save is accepted for any live page of the open notebook**, named by `pageKey` — unlike the
document editor's mode-routed target, there is no routing guard here: the screen turns its own
pages, and a save flushed a moment after a turn still belongs to the page it was drawn on. What the
key still guarantees is that pixels can never land on a page nobody drew them on; a key naming a
deleted page is `IllegalArgumentException("Unknown page")`.

`SketchPageState` (7 fields plus K5b's tail, every one validated in `init` — unmarshal *is*
validation): `pageKey`, `pageIndex`, `pageCount`, `width`, `height`, `sketchBytes`, `sketchChunks`
(pinned to `ByteChunks.countFor(sketchBytes)` so a hand-built state can never disagree with the
chunker that will serve it), and `structuralToken` (K5b's compatible tail, empty on every answer
that is not a page insert or delete — an exhausted parcel reads `readString()` null, which becomes
the empty token, exactly what a pre-K5b answer meant).

**Only `SecurityException` / `IllegalArgumentException` / `IllegalStateException` may cross.** The
two typed `IllegalStateException` strings — `SKETCH_TOO_LARGE`, `SKETCH_BAD_PNG` — are compared
`==`, never `contains`. **There is deliberately no "no ink" refusal**: `requestInk` on a bare page
answers **0**, a legal count for a legal question (the pad's zero-chunk park precedent) — the
screen words its own "No ink on this page" from the number, rather than handling an exception where
a count would do.

### K5b's five tails

Added the same day as decisions 7's second amendment, all behind the method floor 18:

- **`insertPage(direction)`** — a blank page next to **the face's target**, not the notebook's
  displayed page, on the `PAGE_PREV`/`PAGE_NEXT` side named. The host inserts it exactly as its own
  notebook does (inherits the target's template and size), records **one entry on the notebook's
  own undo stack**, loads the read window with the new (empty) page, and answers its state carrying
  a fresh `structuralToken`.
- **`deletePage(pageKey)`** — must be the face's current target (`IllegalArgumentException`
  otherwise: pixels and pages can never be acted on at a distance). Soft-deletes the page and every
  live descendant it owns — strokes, objects, document, **and sketch** — as one entry on the
  notebook's own undo stack. Deleting a notebook's only page answers a fresh blank page rather than
  an empty notebook. The face **flushes the doomed page first** (`deletePageNow` →
  `saver.flushAndAwait()`), because what comes back on undo is what was last saved; a flush that
  fails is logged and the pixels stay parked. A push already in flight when the row goes is refused
  as an unknown page.
- **`pageContent(pageKey)`** — `PAGE_HAS_INK` (bit 1, bare strokes/objects) / `PAGE_HAS_DOCUMENT`
  (bit 2, a live document row); zero means nothing but the sketch. Exists purely so the delete
  confirm can name what else goes, rather than warn about content that is not there.
- **`undoPage(token)` / `redoPage(token)`** — take back or put back the insert/delete `token`
  names. `token` is a *name*, never a payload: the snapshot the edit needs to be reversible (the
  live page ids either side, the rows soft-deleted, where the notebook was standing) is the
  notebook's own undo record, kept on the notebook's own stack — the host looks the token up and
  replays it through the **same arm the notebook's own undo runs**, then moves that very entry
  across the notebook's stack. An unknown token (already undone, or the host died and came back) is
  `IllegalArgumentException`; the face drops that entry and carries on.

`SketchContract.MAX_STRUCTURAL_TOKEN_CHARS` = 64, space-free (a token is one word in a log line,
never displayed, never parsed).

### T2's two tails

Added arc 44 / T2 (2026-09-17), behind the method floor `SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS`
= 19 (`API_VERSION` 18 → 19, no action floor moved — `MIN_API_VERSION_FOR_SKETCH` stays 17,
`MIN_API_VERSION_FOR_SKETCH_PAGES` stays 18 untouched):

- **`toolSettings(): SketchToolSettings?` / `putToolSettings(settings)`** — what this device last
  remembered the face's tools to be, and how the face pushes a new pick. **What crosses is
  indices, never values**: `SketchToolSettings(tool, shade, size)` is three small `Int`s, each
  bounded `0..SketchContract.MAX_TOOL_SETTING_INDEX` (255) — a **sanity** bound on an unmarshalled
  integer, not the palette's own size. The host never learns how many shades or sizes exist, never
  clamps a value against a palette it does not know, and a stored index this build no longer
  offers is not a wire error — `SketchToolState.fromSettings` reads it as an ordinary miss and
  falls back to that field's own default.
- **Null is the answer, not a failure.** `toolSettings()` answering null is the seam's word for
  "nothing has been remembered on this device yet" — a first showing, cleared app data — never an
  exception; the defaults live in `SketchToolState` on the face's side precisely so that answer
  can be given honestly.
- **The host never clamps or knows the palette.** `SketchToolCodec.decode` (the pure half of the
  host's prefs) repeats none of `SketchToolState`'s bounds; it only reads three stored integers as
  a legal `SketchToolSettings` or answers null. Retuning a shade or a lead, or shortening the
  ladder, is a change inside `:ext-sketch` alone.
- **The eraser is never remembered.** It is not a field of `SketchToolSettings` at all — a face
  that opened on the eraser would read as a broken pencil, and remembering it would need a fourth
  index for no reason the seam can name.
- **`SketchToolPrefs`** (`data/prefs/SketchToolPrefs.kt`) is the host's own device-local store:
  `SharedPreferences("sn_sketch_tools")` — deliberately **not** `sn_tool`, the name
  `SnApplication.LEGACY_TOOL_PREFS` deletes at every process start — one setting for every
  notebook, never the `.soil`, never a backup. `put` is one `edit()`/`apply()` of all three keys,
  so a reader can never see a new shade against an old size.
- **Pushed on every pick, fire-and-forget, on IO under a fair mutex.** `SketchActivity`'s
  `toolPushes` mutex serialises the pushes in pick order — Fable's one T3 review fix, after two
  quick picks raced on separate IO hops and left the device remembering the first of the two
  rather than the second.

### `SketchHostSession` and `SketchHostBinder`

`SketchHostSession` (pure) holds the read window (a PNG staged as `ByteChunks`), the ink window
(staged `WireStroke` chunks, 0 legal), and the save accumulator: a key is taken from the save's
chunk 0 and every later chunk must repeat it, in order; per-chunk and running-total caps enforce
`MAX_BYTES`; an empty join is a clear. **A window swap mid-save leaves the accumulation exactly
where it was** — a flush a moment after a page turn still lands on the page it was drawn on.
`SketchHostBinder` is the `ISketchHost.Stub`: `gate()` is the first statement of all thirteen methods,
a `hook {}` funnel keeps only marshalable exceptions crossing, and `revoke()` clears the session.

### `SketchClient` and `SketchEntry`

`SketchClient` is `DocumentEditorClient`'s shape minus the store: mint the binder, `hold`,
`begin(host)` ≤ 2 s, launch the package-pinned screen Intent; `finish()` is `end()` ≤ 15 s then
unbind + revoke in `finally`. `SketchEntry` (`DocumentEditorEntry` + `ExtensionScreenEntry`'s paper
pieces) is the notebook's door: open drains, binds, begins, sets `EXTRA_CHROME_HIDDEN`, dismisses
floating chrome, ends any running transform, calls `releaseForHandoff`, then launches and attaches
to the surface stack; the result pops chrome, reclaims the pipeline, runs a lazy finish job, and
calls `onClosed(code)`. `SketchEntry.discovered()` is true only when the notebook **is** a Sketch
notebook *and* a trusted `NSE · Sketch` is installed — one answer, one place visibility is decided,
and `openIntoSketch` **awaits** it rather than reading a cached flag (reading `isAvailable` loses
the discovery race about half the time).

---

## The screen

`SketchActivity : PaperScreenActivity` — SN's sixth screen-owning extension point, built on
`:ext-ink`'s `PaperScreenActivity` (lifted out of `InkScreenActivity` at K2 for exactly this
reuse): chrome bars and their toggle, the collapsed corner button, the free band, stylus-vs-finger
dispatch, the pen-idle gate, and the EPD handoff are all inherited; what lives in this file is what
a *raster page* is.

**The caller check runs first**, before anything is inflated: the screen is exported (it has to be
— the host launches it by action) and `HostCallerCheck.enforce` refuses anything but a
`startActivityForResult` from the host package. `onScreenDestroyed` still runs on a bounced launch
(the Bible's B2 lesson) — guarded with `::saver.isInitialized`, after one bug shipped without it
(see Traps).

**Tools.** `pageMode = RASTER` before any content loads; the toolbar carries three tools — Pencil ·
Pen · Eraser (arc 44 / T3) — over `RasterRubbing()` defaults, **nothing set in the removed
`RattaTuning`** — the K1 measurements are the engine's own defaults now. Both pens are `Tool.PEN`
to g-paper; which kind is armed, and the pencil's own shade and lead, live in `SketchToolbar`'s
`SketchToolState` and are applied to the engine as `penStyle`/`penWidth`/`penColor` — never a
second tool for g-paper to know about. `smartLassoEnabled = false`, `scribbleEraseEnabled = false`
(hatching is not a gesture); `collapsedTools()` still answers `[PEN, ERASER]` — two slots, not
three — because the PEN slot itself carries **both** kinds via `collapsedPenKinds()`
(`CollapsedChrome.PenKinds`), so the collapsed mini row reads Pencil · Pen · Eraser, the top bar's
own order, with no change to how many tools the base class thinks this screen has. Overflow behind
`…` is unchanged: Back · Bring in ink · Show pages. There is no `EraserBar` — Point and Lasso are
stroke erasers, and a raster page has neither.

### Tools (arc 44)

- **`SketchPalette`** (`:ext-sketch`, pure Kotlin) is the one place the greys and the widths are
  written down. A stored shade is **a level of the e-paper ladder (0–14), never a position in
  `SHADE_LEVELS`** — the list of levels this build offers, `listOf(1, 3, 5, 7, 9, 11)` today — so
  the level means the same thing whichever subset a build happens to offer, and `SHADE_LEVELS` is
  the one line to change to offer a different set. `ROW_BREAK` (8, decision 5's "8 over 7" when
  there were fifteen shades) is inert at six offered levels — the row simply never reaches it — and
  is kept as the rule `shadeRows()` derives from rather than deleted. `SIZE_ROW_BREAK` = 6 is where
  the twelve lead widths wrap onto their second row. `PEN_WIDTH_PX` = 5 (px) is the gel pen's one
  width, settled at T3's walk.
- **`SketchToolState`** (`:ext-sketch`, pure Kotlin) is the armed kind plus the pencil's shade level
  and size index — three fields, one seam parcel (`toSettings()`/`fromSettings()`). Both kinds are
  `Tool.PEN` to g-paper, so "which pen is armed" has nowhere else to live; the shade and the lead
  are kept **through** a switch to the gel pen and back — picking the pen is not forgetting the
  pencil. Every field read from outside (a remembered index, a level this build no longer offers)
  comes in through `of`/`fromSettings` and falls back to its own default independently, never the
  whole state at once. `reportedShade` is the pencil's own shade **whatever kind is armed** — what
  makes the Pencil button's report different from the engine's actual `penColor`.
- **`PencilBar`** (`:ext-sketch`, over `:sn-screen`'s `AnchoredBar`) is the pencil's own options bar,
  the `EraserBar` pattern applied to three rows instead of one. A shade swatch is not an icon
  button: it is a black ring with the grey itself as the fill, painted in `onDraw` rather than a
  drawable, because the fill has to read at every level offered — the selected swatch alone gains a
  **white gap ring** between the ring and the fill, the one mark that works as well on `#000000` as
  on the palest lead offered. A size dot is a **legible dp ladder, never the literal px** — 1.2 px
  would be a single dark pixel and 96 px would not fit a cell, so the twelve widths are drawn on a
  ladder linear in the index, clamped to the cell so it holds at both button tiers. Re-tapping the
  armed Pencil opens the bar under it; it **stays open after a pick** — a visit is usually "this
  grey, that lead" — and closes on the Pencil's own re-tap, any tool change, a page swap, a finger
  gesture, a chrome flip, a contact outside it, or the exit; every dismissal path is in T3's ledger.
- **`PencilIcon`** (`:ext-sketch`) is a `LayerDrawable`: `ic_pen_fill`'s body, tinted with the armed
  shade, under `ic_pen`'s untouched black outline — how the Pencil button "reports the armed shade"
  (T3's phase-start answer): black armed reads as a fully black pencil, every other level as a black
  outline with that grey inside it. Worn by the top bar's own Pencil button, the collapsed corner
  knob, and the mini row's pencil alike — one recipe, not three spellings of it. The fill stays put
  while the gel pen or the rubber is armed, because a button says what a tap on it will bring back.
- **`ic_ballpen`** (`:sn-screen`, Tabler's `ballpen`) is the gel pen's glyph, in the shared
  vocabulary rather than `:ext-sketch` alone, beside `PaperToolbar`'s new `btnAltPen` /
  `altPenArmed` / `onPenKindPicked` / `onPenReTap` (a defaulted, trailing parameter set — every
  existing caller with one pen is unchanged) and `CollapsedChrome`'s `PenKinds` (the alt button
  built right after the primary one, so the row reads Pencil · Pen · Eraser) and the three
  defaulted `PaperScreenActivity` hooks the sketch face overrides (`collapsedPenKinds` ·
  `onCollapsedClosing` · `keepCollapsedUnder`). Both bars test which kind is armed against
  `CollapsedTools.penButtonSelected(tool, altArmed, isAltButton)` — one rule, asked by both, never
  two spellings of "is this the armed pen?".
- **Restore and Bring in ink.** `SketchActivity.restoreTools()` reads `toolSettings()` and applies
  the answer inside `openPage`, **before** `opened = true` — before the first mark on the glass is
  possible — so nothing is ever drawn with a pencil the person did not choose. `restorePen()` puts
  the armed pen back on the engine after "Bring in ink" or the debug fill door composite: those two
  bake **strokes**, which carry their own colour/width/style in `PageMode.RASTER` rather than the
  armed pen's, so nothing is disturbed today — the call is defence against that ever changing.
- **The Ratta preview.** Only levels 0, 5 and 9 preview *exactly* as they bake — the firmware's
  three tones (BLACK / DARK_GRAY / GRAY, `RattaInkMap.pencilPreviewFor`). Every other offered level
  previews in its band's tone (0–2 → BLACK, 3–6 → DARK_GRAY, 7–14 → GRAY) while baking its own
  grey — the live line is an approximation, the bake is always the true shade.

**Page turns, inserts, deletes** all go through `runPageOp`, which serializes page ops on the
lifecycle scope and turns any thrown exception into a `Log.w` rather than a crash. A turn
(`turnPageNow`) flushes the current page first, asks the host to `requestPage`, and compares
`pageKey`: the **same key back means the edge**, never an exception or a null the screen has to
word, and the arrows (well, gestures — decision 12) simply do nothing there. An insert
(`insertPageNow`) is the same shape with `insertPage`; a delete (`deletePageNow`) flushes the
doomed page, asks `deletePage`, and lands on whatever page the host answers. Every successful
structural call records its `structuralToken` into the face's own history (`rememberStructural`) so
the face's own undo/redo gesture can reach it later.

**Delete confirm.** A one-finger long-press on the page opens the pager's sheet
(`confirmDeletePage` → `askAboutDelete`), which asks the host `pageContent` first and words the
body from the two bits: *"Its handwriting will be deleted too."* / *"Its document will be deleted
too."* / *"Its handwriting and document will be deleted too."* / a plain confirm when the page
carries nothing but its sketch.

**Page insert gestures**: a single-finger swipe past the **last** page inserts a page after it; a
two-finger swipe inserts in the direction swiped (before or after the current page) — both route to
`insertPageNow` through `PageGestures.Listener.onInsertAfter`/`onInsertBefore`.

**Bring in ink** (`bringInInk`): stages the page's bare strokes over `requestInk` (0 = "No ink on
this page" alert, never an exception), reads them chunk by chunk (`readInkChunk`), turns them into
strokes via `InkBake.toBakedStrokes` — `InkWire.toStrokes` plus one colour override, opaque black,
width and style kept as drawn — and composites them as one undo entry (`composite`). A page dense
past the pad's own transfer caps (`MAX_TRANSFER_STROKES` 10 000 / `MAX_TRANSFER_POINTS` 400 000)
arrives **cut**: the prefix that fits, in writing order, logged host-side — a partial bake, never no
bake at all.

**The debug fill door** (`BuildConfig.DEBUG` only): a long-press on the page indicator composites
`TEST_PATTERN_LINES` = 12 diagonal `PENCIL` strokes as one undo entry, so adb (which cannot draw)
can still produce a non-blank save to walk against.

**Saves and reconnect.** `SketchSession.FlushHook` (`flushBlocking`/`pushBlocking`) and
`BeginListener` are the screen's static hand-off to `SketchService`: a host restart while the
screen is up delivers a fresh `begin` to the **live screen** rather than the screen re-launching,
and the service's `end()` calls `flushBlocking` through the screen's own push lock before
re-pushing anything parked.

**Exit.** Back / Show pages both route through `exit(resultCode)`, which cancels the save timers
and awaits a final flush (`leaveWhenFlushed`, non-cancellable). A flush that fails opens the "Sketch
not saved" dialog (see Failure table) rather than a log line — pixels have no other copy. Show
pages answers `SketchContract.RESULT_SKETCH_SHOW_PAGES` (1); Back answers `RESULT_CANCELED`.
Nothing else rides the result Intent but `EXTRA_CHROME_HIDDEN`.

### The host side (`NotebookActivity`, `SketchHostHooks`)

`NotebookActivity` holds `sketchEntry` (the door — `btnSketch`, left of `btnBible`, mirrored in the
collapsed overflow with `ic_brush`) and `sketchHooks` (the callback binder's four original hooks
plus K5b's three). The open route forks on `session.isSketch` through `SketchRouting`
(`FaceRouting`'s generic open/close tables under the sketch face's own names): a fresh Sketch
notebook launches the face; a live showing reconnects; a notebook whose canvas has already been
shown this incarnation is ordinary from then on. **The one place the sketch and text faces differ**:
the editor's advisory is a field it sets on the way out, so silence is a genuine unanswered
question; the sketch face's advisory is the **Activity result code**, and the framework always
delivers one — so `RESULT_CANCELED`, null, and any unknown code all read as to-library, with no
extra rule needed (`SketchRouting`'s own KDoc).

`SketchHostHooks.target` (`@Volatile`) is the host's memory of **where the face is** — moved only by
`requestPage`/`insertPage`/`deletePage`, restored from saved state on a reconnect, falling back to
the displayed page if the face's target no longer exists. **The two undo histories are one
history**: a page insert or delete is recorded on the **notebook's own** undo stack
(`NotebookUndo.Action.Page`, via `onStructural`, posted to Main since the hook runs on a pooled
Binder thread) so the notebook's own undo still restores it after Show pages — but a small
per-showing **ledger** (`undoable`/`redoable`, tokens `s1`, `s2`, …, capped at 100, cleared with the
showing) lets the face's own gesture ask for that very edit back (`undoPage`/`redoPage` →
`onStructuralUndone`/`onStructuralRedone`, which pop-and-verify-then-push the matching entry across
the notebook's stack rather than trusting the token blindly). **Known looseness, written down**: a
pixel edit on the face clears the face's own redo side but not the notebook's, so an insert undone
on the face and drawn past can still be redone later from the notebook — benign (the page comes
back whole), and the alternative is a Binder call per mark.

---

## Saves

`SketchSaveGovernor` (pure Kotlin, no Android types) is the whole decision of whether a drawing gets
written; `SketchSaver` is the plumbing (timers, threads, the binder call) that carries out what the
governor says.

**Dirty is a flag, not a comparison** — a page of pixels cannot be compared without re-encoding it,
which is the work the question exists to avoid. `markDirty()` follows the engine's own
`onRasterChanged`; the flag is cleared **when the copy is taken**, not when the write lands, so a
mark arriving mid-encode re-dirties the page and a second save follows rather than being lost in the
gap between an old copy and a flag cleared too late.

**States**: `request()` (an ordinary trigger — the debounce tick, a turn, `onPause`, a retry) answers
`Idle` / `Save` (take the copy now) / `Wait` (a push is already in flight; its own completion
re-asks) — **newest wins, and wins after**, never beside, the one already running (two overlapping
chunk streams would interleave on the host's one accumulator). `flushRequest()` (a **leave**
trigger: Back, Show pages, `end()`) ignores `inFlight` on purpose — the real exclusion is the push
lock around the chunk stream, and a leave flush queued behind an in-flight push still lands, in
order, rather than leaving with the newest pixels unwritten.

**Cadence**: 3 s pen-idle-gated debounce; a failed push retries after 2 s; flush points are a page
turn/insert/delete, `onPause`, Back, and Show pages. Each save is a **Main-thread bitmap copy** →
PNG encode on IO → chunked push under one `Mutex` (one push in flight at a time, ever). A failed
copy (an allocation the device refuses) leaves the page dirty and re-arms the retry with nothing
pushed; a failed push leaves the page dirty, parks the pixels, and re-arms the retry.

**`PendingPngPark`** is the one place a page's pixels live when a push could not be delivered — the
host process died, its binder was revoked, a chunk was refused. **One slot, keyed by page**: a
second failure on a *different* page displaces the first (the newer pixels, the ones the hand is
closest to, win; the caller logs the displacement). `ISketch.end()` re-pushes whatever is parked
while the host binder is still valid — the last moment it is. Unlike the document editor's park, a
sketch save is accepted for any live page, so a park never has to match what the host is currently
showing to be worth writing back.

---

## Undo

`RasterTiles` (`CELL` = 64 px) divides the page into a fixed grid aligned to its own top-left
corner. `RasterEditBuilder` opens at the first `onRasterWillChange` of a contact (a pen stroke, an
eraser sweep, or the "Bring in ink" bake) and reads each cell the paper touches **once per
contact**, never again for the rest of that contact — an eraser sweep is dozens of engine batches a
second whose rectangles overlap heavily, and reading a tile per batch would let one slow rub pile up
tens of megabytes of near-duplicate pixels inside a single entry. Sixty-four is small enough that a
hairline stroke costs 16–64 KB and large enough that a page-wide sweep is not tens of thousands of
tiny reads.

**One contact is one entry, and it is its own inverse** (`SketchEdit.RasterChanged(pageKey,
pageIndex, tiles)`): the tiles go onto the page via g-paper's `swapPageRaster` and come back holding
what the page was holding, so the entry that undid a change is the entry that redoes it, with no
second copy and no second shape of call. **Undo bytes bound the whole entry to the page itself** — a
9.5 MB Nomad page or an 18.4 MB Manta page is the absolute ceiling for one contact, well under the
48 MB budget (`SketchEdit.UNDO_BUDGET_BYTES`), which `UndoRedoStack`'s `evictForBudget` enforces by
dropping the **oldest costed entry**, never the newest.

**`SketchEdit.Structural` is the one zero-byte kind** (K5b): a `PageInserted`/`PageDeleted` entry
carries only its host `token`, no pixels at all, so the byte budget can **never** evict one to make
room for pixel entries. **The history is screen-level, not page-level** — undoing "the last thing I
did" after turning pages away from where it happened has to turn back first, which is why every
entry (pixel or structural) carries a `pageIndex` alongside its `pageKey`: the key names the page,
the index says which way and how far to walk (`PageTurn.directionTowards`/`maxSteps`, bounded — the
recorded distance plus one step of slack — because pages can move under the entry after K5b).

**Replay** (`doReplay`): walk to the edit's page (turning, bounded) → `awaitPenIdle` → a generation
re-check (if a mark landed on the page while the replay waited, the edit is put back **beneath** it
rather than applied out of order) → the swap (pixel entry) or the Binder replay (structural entry)
→ mark dirty → **never a reload from disk**. A fresh delete drops the deleted page's pixel history
entries (`dropPixelsOf`); a **replay never drops entries**, only re-indexes them
(`PageTurn.reindexAfterInsert`/`reindexAfterDelete`) — this is the one bug K5b's hand walk found and
fixed: an *undo* of an insert had been dropping the page's own pixel entries even though they sat
waiting on the redo side one step above, which meant insert → draw → undo → undo → redo → redo did
not bring the drawing back. Fixed by making a replay re-index only, never drop; only a fresh delete
drops.

---

## Export, clipboard, erase, cover

**Export** (`ExportRender`, K7): a bundle interleaves each page's ink then, if it has one, its
sketch — page 1 ink, page 1 sketch, page 2 ink, … — with endnotes after the last sketch. Which
pages carry one is **planned, not discovered**: one blob-free `SketchDao.pagesWithSketch` query
before the first page is drawn, so the bundle can declare its header page count up front.
`bundlePositions` shifts every endnote's `fromPage` by the sketch pages ahead of it (`fromPageLabel`
keeps the notebook's own page number). The sketch image is read through `SketchDao.sketchFor` +
`SketchRows.pngBytes`/`fitsPage` — **never `SketchRepository.get`**, which would soft-delete a row
the render must not touch — and a row that is gone or refused between the plan and the bake writes
a **plain white page of the page's own size** (`SketchRaster.blank`, logged) rather than closing the
bundle short by one page. `SketchRaster.toWebp` composites the guarded PNG over opaque white into
`RGB_565`, one page bitmap alive at a time, plain white never the template (decision 10).
`ExportNaming.pageStem(sketch = true)` appends **` sketch`** to the ink page's own stem
(`K7 - Heading.png` / `K7 - Heading sketch.png`) — the phase-start answer, so the two files sort
together and the suffix never eats into the title's own cap.

**Page-scope export of a sketched page is a folder, not a single file** (`ExportDelivery.perPage`,
the K7 hand-found bug): a two-page bundle handed to a single-file image exporter as "one page" threw
`bundle carries 2 pages; one expected` with nothing written. Fixed by counting a sketched page at
page scope like the calendar's Day — `perPage(delivery, scope, pageHasSketch)` — with
`SoilDao.hasLiveSketch(pageId)` (blob-free) answering the Export screen's one open.

**Clipboard/erase**: see § The data above — `clip_too_large_sketch`, `liveErasableIds`.

**Cover** (`SketchCover`, decision 6): rendered from the **stored row**, never the extension's live
surface — the pixels are in another process, and the only honest picture is the one that landed,
which is why the caller reads the row *after* `end()`'s save has been joined. Composited over
opaque white (a transparent card would show the library's own background through the strokes),
decoded **sampled** (`sampleFor`, pure, the largest power-of-two `inSampleSize` that keeps the long
edge at or above `CoverSnapshot.LONG_EDGE_PX`) so the decoder never allocates a full page for a
512 px card. Falls back to the ordinary ink bake when the page carries no sketch, and only once the
canvas has actually loaded (an unloaded surface's "bake" would be a blank card).

**Compaction**: unchanged. A soft-deleted sketch row purges like any row at close; a purged page
cascades and takes its sketch with it.

---

## The door

`btnSketch` sits on the notebook's bottom strip, immediately left of `btnBible` (decision 9), `GONE`
— never disabled — for a non-Sketch notebook or with no trusted `NSE · Sketch` installed; mirrored
in the arc-36 collapsed overflow (`ic_brush`). It is the **third** deliberate exception to "bottom
bars are pager-only," after `btnBible` (arc 37) and the reader's `btnNotes` (arc 42). The sketch screen's own top bar carries **Show pages** back
out; there is no door the other way from the library (a Sketch notebook always opens through the
notebook screen first).

---

## Failure table

| Situation | What happens |
|---|---|
| A save's running total passes `MAX_BYTES` (6 MiB) | `SKETCH_TOO_LARGE` thrown at the accumulator; nothing written; stored row unchanged; the screen keeps the pixels on the glass (they have no other copy) |
| Committed save bytes are not a PNG of exactly the page's size | `SKETCH_BAD_PNG`; nothing written |
| A stored row fails the header guard on read | Soft-deleted on the way past, never overwritten; `get` answers null |
| The host dies mid-save (Binder revoked, `DeadObjectException`) | The pixels are parked (`PendingPngPark`) by page key; the governor keeps the page dirty and retries |
| Final flush before Back/Show pages fails | "Sketch not saved" dialog — Try again / Leave anyway; "leave anyway" still leaves the pixels parked for `end()`'s own retry |
| A page turn/insert/delete lands at the notebook's boundary | The host answers the **same page unchanged**; the screen compares `pageKey` and does nothing — no dialog, no toast |
| `deletePage` is asked for a page that is not the face's current target | `IllegalArgumentException` |
| `undoPage`/`redoPage` is asked for an unknown token | `IllegalArgumentException`; the face drops that history entry |
| A `readInkChunk`/`readSketchChunk` index is out of range | Refused (contract-typed exception) |
| A page has no bare ink for "Bring in ink" | `requestInk` answers **0** (not an exception); the screen shows "No ink on this page" |
| A page is denser than the transfer caps | Ink arrives cut to the prefix that fits, in writing order; a partial bake, never none |
| `am start` targets the exported screen directly | `HostCallerCheck` refuses before anything is inflated; `onScreenDestroyed` still runs and must not crash the process (see Traps) |
| No trusted `NSE · Sketch` is installed | A Sketch notebook loads its ordinary canvas silently; `btnSketch` stays `GONE` |
| A replay's page-history entry names a page since renumbered | Bounded walk (`PageTurn.maxSteps`); a stale key that never resolves drops the entry rather than looping |
| A structural replay lands mid-mark | Put back **beneath** the newer mark (generation re-check), never applied out of order |
| The host answers **null** for `toolSettings()` | Read as "nothing remembered yet," never a failure — the face arms its own defaults (pencil, level 5, the finest lead) |
| A remembered index (shade level or size position) this build does not offer | That field alone falls to its own default (`SketchToolState.of`) — the other two remembered fields are unaffected |
| A host below API 19 | `:ext-sketch` never binds it at all — the manifest declaration is the guard, K5b's arrangement repeated; there is no runtime version test in `restoreTools()` |

---

## Frame-silence ledger

- `loadPageRaster` — **silent**, as `swapPageRaster` always was, since g-paper 0.1.33 (K6): a page
  the host replaced is the host's own news, not the engine's. The screen's earlier `loadingRaster`
  guard against a spurious dirty flag on load is gone with it — there is nothing left to guard
  against.
- `swapPageRaster` (an undo/redo swap) — silent, unchanged from every earlier pin.
- `addStrokes` in RASTER (the ink bake) and `clear()` — **report**, as ordinary raster changes.
- `commitCapturedStroke` / the eraser's batches — report per-segment dirty rects (0.1.33's
  `RasterDirty.along`), not one page-wide rect — the K6 change that took the worst-case undo entry
  from 550 cells / 8.98 MB to 134 cells / 2.19 MB on a corner-to-corner Nomad hairline.
- Two page turns after drawing produce **no** undo entry and **no** dirty flag — the load being
  silent is not merely unnoticed, it was measured to produce nothing.
- **`PencilBar`'s open and its repaint after a pick (arc 44 / T3) are the floating-bar exception,
  not a new silence** — one chrome frame each at a deliberate tap, with the pen that made it still
  hovering (`isPenActive` counts hover; gating the frame itself would hold the bar back until the
  hand left the glass, which every other sub-bar in the family already refuses). What *is*
  pen-gated is the render release: `pick()` calls `PenIdle.releaseRenderIfIdle(paper)` before
  `onPicked` assigns the new state, `EraserBar`'s own contract — an ungated release inside the
  pen-active window can cost a live stroke.

---

## Traps

- **The pencil bakes upright on Ratta (g-paper 0.1.35, 2026-09-17, branch `sketch-manta`).** The
  stable build went onto the Manta unwalked, and there the baked hairline landed **10–15× wider**
  than the firmware's live line. Not a panel difference and not the EMR size (suspected first —
  a `setprop` door was re-opened for it and closed again unused): g-paper's graphite widens a
  leaned lead up to ~11×, and the Supernote live line is one width whatever the tilt. Both
  devices deliver `AXIS_TILT`; the Nomad walks had simply been at an upright grip. g-paper's
  `bakeTilt` seam (beside `bakePressure`) bakes Ratta's `PENCIL` at tilt 0 — decision 3's "no
  tilt", now true in the pixels. Walked by hand on both the Manta and the Nomad. **The size of a
  mismatch names its cause: an EMR error is tens of percent, a lean error an order of magnitude.**
- **`RattaNotebookView`-style sibling-copy risk was avoided on purpose.** `FaceRouting` is one
  generic open/close table; `TextDocRouting` and `SketchRouting` are thin facades naming its
  answers under each face's own words. A fix to the shared table helps both faces at once — do not
  fork it.
- **`am start` of the exported sketch screen crashed the extension process** the first time it was
  tried: the caller check correctly refused the launch, but the refused instance's
  `onScreenDestroyed` then threw `UninitializedPropertyAccessException` on the `lateinit` saver,
  taking down the process **with the legitimate face already running in it**. Fixed by guarding on
  `::saver.isInitialized`; any future `lateinit` field touched from a lifecycle callback that a
  bounced launch still receives needs the same guard (the root `IndexGuard.bounced` rule, restated
  here for a screen with no index).
- **A finger `input swipe` never draws real ink on Ratta** — adb cannot exercise the pencil, the
  eraser, or the undo/redo gestures; every one of those is a hand-only walk item, and an agent
  "pass" on one gets re-driven by the user before a phase counts as done.
- **`adb shell input tap` costs about a second per call on the Nomad**, which rules out injecting a
  double-tap (chrome collapse, the undo/redo gestures) — the hand, or raw `sendevent`, only.
- **The Nomad's page size moved between K1's demo (1404×1685) and the shipped host (1404×1872 in
  one walk, matching the walk agent's screen height)** — budget arithmetic for undo/export should
  be re-derived from the live device, not assumed from an earlier measurement.
- **A stale incremental-dex snapshot** pointing at the pre-rename `apps/notesprout_ratta/…` path
  broke a dependent module's `mergeLibDexDebug` the first time a class was deleted from `:ext-ink`;
  `./gradlew :ext-ink:clean` clears it — expect it again after the next class deletion in a
  long-lived module.
- **A NUL byte inside a Kotlin char literal compiles and passes silently** — only `cat -v` shows it.
  A defect of exactly this shape was caught in review before K2's commit (`SketchPageState`'s key
  check carried a literal NUL byte where the `'\u0000'` escape belonged), and this arc's own
  Markdown carried one twice — `SKETCH_PLAN.md` from K2's ledger until K4, and the first draft of
  this very doc — which makes `grep` treat the file as binary (silent no-match; `file` says
  "data"). Check any new `require` line against `DocumentPageState`'s existing escapes, and
  `file` any doc that grep goes quiet on.
- **The Supernote DocumentsUI *file* picker (`ACTION_OPEN_DOCUMENT`) is as adb-proof as the folder
  picker** — synthetic taps on rows are ignored; walk import by hand, and know that a second tap on
  Import can reopen the picker with no back door but `am force-stop com.android.documentsui`
  (confirmed safe: it returns `RESULT_CANCELED`, nothing double-imported).
- **The ext-sketch debug APK reads larger on disk than its JVM test byte count would suggest** — the
  known zipflinger inflation, not a dependency regression.
- **The demo's Shade / Lead cyclers are raster-only and the bar scrolls sideways (T1)** — say so in
  any walk checklist that uses g-paper's own demo rather than the shipped face.
- **`adb shell monkey` exits 251 on the Nomad (T1)**, with the app launched or not launched — don't
  chain a walk step on it.
- **No test implements `SketchHostBinder.Hooks` (T2)** — the binder shell has no JVM coverage
  (`Binder.getCallingUid`), so `toolSettings`/`putToolSettings` were proven only by T3's device
  walk (picks surviving a reopen and a process death).
- **The fill door draws on whatever page is showing (T3).** The adb walk composited its test
  pattern over the user's own tree sketch on the dev library's K7 page 2, and a process-death step
  in the same walk took the undo history with it. **Every walk brief now says "turn to a blank page
  first."**
- **Two `Tool.PEN` kinds are one tool to g-paper (T3)** — the screen alone owns which kind is
  armed, and any "is the pen armed?" test in shared chrome has to ask the screen (`altPenArmed`),
  never the tool alone.
- **`SketchPalette.ROW_BREAK` (8) is inert at six offered shades (T3)** — kept anyway, as the rule
  `shadeRows()` derives from, rather than deleted for a list that may grow again.
- **`SketchActivity` is ~1230 lines (T3)**, past the ~800-line guide — what remains past that point
  is screen wiring (the tools, the pencil bar's toggle/dismiss paths), not a candidate for a quick
  split.

---

## Nomad numbers

**K1 (g-paper demo, before the pin was even in SN):**

| Measurement | Result |
|---|---|
| Cadence | 16 ms chosen — 756 frames / 82% janky / p50 20 p90 28 p99 40 over the measurement window, but "the eraser works better on Ratta hardware than it does on Onyx"; 100 ms and 60 ms were smoother on paper and worse by hand |
| `PENCIL` EMR floor | 120 — matches the baked hairline; 150/200 unneeded |
| Preview tone | `DARK_GRAY` needle — "spot on"; plain black was "way off", pressure-tracked INK was indistinguishable from a flat tone on Ratta's one-tone firmware |
| Eraser-end pressure | Real: 2 852 batches measured, 0.06–0.49, median 0.24 |
| Undo swap (demo page) | 10–14 tiles, 14–20 ms |
| Page swap (Nomad demo, 1404×1685 ≈ 9.46 MB) | 79–126 ms; demo PSS 78 MB |

**K5 (first face walk, fresh `.dev` install):**

- Fill door → **undo entry: 414 tiles, 6 782 976 B** (before K6's per-segment rects)
- Save: 133 482 B encoded in 610 ms, pushed in 24 ms, host committed in 20 ms, 1 chunk
- Reopen after Back: **0-pixel screencap diff** against the pre-close capture
- `am kill` of the host behind the face: save failed (`DeadObjectException`) and parked; Back →
  dialog → Try again failed again → Leave anyway → Bootstrap relaunch → **the parked 136 554 B was
  pushed and committed on the very next attempt**
- PSS: host 65.8 MB / extension 64.2 MB (Paintsprout's Onyx equivalent: 255 MB)
- Idle `gfxinfo`: 0 frames / 30 s

**K6 (g-paper 0.1.32 → 0.1.33, one corner-to-corner pencil hairline on a fresh 1404×1685 page):**

| | 0.1.32 (before) | 0.1.33 (after) |
|---|---|---|
| 64 px cells read at pen-up | 550 | **134** |
| Undo entry bytes | 8 985 600 | **2 190 336** |
| Main-thread ms reading the before-image | 40 | **18** |
| Undo/redo swap | 550 tiles both ways | 134 tiles both ways |

4.1× fewer cells and bytes for the worst-case mark; two page turns after drawing produced no undo
entry and no dirty flag.

**K7 (clipboard/erase/export, notebook "K7", page 1 fill-door sketched, page 2 blank):**

- Copy/paste page: pasted sketch **byte-identical** (screencap lattice pixel-equal)
- Whole-notebook PDF: `rendered 2 page(s) + 1 sketch(es) + 0 endnote(s)`, pulled `/Count 3`
- Page-scope PDF: `/Count 2`
- Whole-notebook PNG: 3 files — `K7 - page 1.png` 11 964 B · `K7 - page 1 sketch.png` 109 995 B ·
  `K7 - page 2.png` 11 964 B
- `.soil` 159 744 B → delete the sketched page → close → `SoilCompactor: purged 2 row(s)` →
  **24 576 B**

**Across every phase (`assembleDebug`, JVM):** `:extension-api` 281 → 286 (K5b) → **292** (arc 44 /
T2); `:sn-screen` 101 → 109 → 114 → **118** (arc 44 / T3); `:ext-sketch` 0 → 52 → 60 → **87** (arc
44 / T3); `:app` 1717 → 1816 → **1822** (arc 44 / T2). **3592 tests in the SN root** at
arc 44's freeze, counting `extensions/bible`'s 155 and `extensions/sketch`'s 87 (3549 at K7,
counting `extensions/sketch`'s 60).

**T1–T3 (arc 44):**

| Measurement | Result |
|---|---|
| T1 starting ladder (0–2 BLACK, 3–9 DARK_GRAY, 10–14 GRAY) vs. baked | 7–9 previewed **darker than they baked**; DARK_GRAY ceiling moved 161.5 → 110.5 |
| T1 final ladder | **0–2 BLACK · 3–6 DARK_GRAY · 7–14 GRAY** — 12–14 also preview darker as GRAY than they bake; LIGHT_GRAY trialled for them and rejected ("that doesn't work") — GRAY stays the palest rung |
| T1 lead sizes (1.2 / 2 / 4 / 7 / 12 px) | Preview ↔ bake width agree at all five; no EMR change |
| T3 sizes (1.2 / 2 / 4 / 7 / 12 px) | Preview ↔ bake agree, as at T1 |
| T3 gel pen width | 3 → 7 → **5 px** ("5 is perfect") |
| T3 shades (of the fifteen offered) | Only **0, 5, 9** previewed exactly as they bake (the firmware's three tones); the offered list settled at six — **1, 3, 5, 7, 9, 11** |
| T3 wide-lead walk (EMR ceiling lifted, g-paper demo) | 16 / 20 / 24 / 32 / 48 / 64 / 96 px all walked — "all of those wide-lead sizes work", no lag |
| T3 pencil bar position (Nomad, 1404 px wide) | x 8–940, the 8 + 7 swatch layout before the cut to six — fully on-screen |
| T3 tool memory across a reopen and a process death | `tools restored: remembered — tool=1, shade=0, size=4` after `am force-stop` — the T2 binder overrides proven only here |
| T3 heaviest save, 12 px black, four fill-door passes | 607 519 → 591 880 → 559 914 → **524 533 B** — an order of magnitude under the 4 MB watch |

The 96 px lead was not measured for save bytes at T3 (a later walk's number if it matters).
