# Notesprout SN — Claude Code instructions (apps/notesprout_sn)

**The official Supernote version of Notesprout.** Package `com.symmetricalpalmtree.notesproutsn` ·
Label "Notesprout SN" ("Notesprout SN Dev" in debug) · Version `0.1.0-sn` · Lives on `main` since
2026-09-11 (the `ratta` feature branch was merged with `--no-ff` and deleted; tag
`notesprout-sn-0.1.0` marks the merge).

A from-scratch, **Supernote-only** build of Notesprout — a host app plus separately installed
extension APKs (`NSE · <Name>`) over the g-paper Ratta firmware-ink engine. Paper v0
(`git show 87277da:apps/notesprout_paper/...`) and the original app (`apps/notesprout_android`)
are reading references — **no app code is copied from either**. Devices: **Nomad only by default**
(SNN `SN078D10012852`); Manta (SNM `SN100C10023972`) only when the user explicitly asks.
The Manta identifies as a Nomad — target by serial.

**Status: arcs 1–36 are all COMPLETE + FROZEN (2026-09-11) and `PARITY_BACKLOG.md` is closed.**
The build effort ("ratta paper", 2026-08-20 → 2026-09-11) is over; what remains is maintenance,
plus **arc 37 "Bible" — a fresh user decision granted 2026-09-13, COMPLETE + FROZEN 2026-09-13
on branch `bible`, merged to `main` 2026-09-13 with arcs 38–40 and the branch review** (plan + ledger: `extensions/bible/BIBLE_PLAN.md`; reference
`extensions/bible/docs/bible.md`; no code review — the user's call; frozen on the user's own Nomad
walk; **post-freeze B6 2026-09-13 on the user's decision: the index became a Contents-shaped side
panel with a one-finger swipe-down door — `SwipeMath.vertical` + `ListSwipe`'s optional vertical
callbacks landed in `:sn-screen` for it; post-freeze B7 the same day: "Index" renamed "Contents"
(strings and classes), and a Recents panel — the notebook's, mirrored right — of the chapters
picked by name, behind a clock button and a two-finger swipe down; **post-freeze B8 the same
day: a Search panel — reference or words, Biblesprout's shape, FTS4 in the slim build + a
Kotlin BM25 — behind a search button left of the clock** (`ListSwipe`'s optional
`onTwoFingerSwipeDown`, `:sn-screen`); the store grew to `BibleSchema.V2` with a `recent` table**).
**Arc 38 "Reference" (2026-09-13, a fresh user decision, branch `bible`) grew the Bible point
in place** — a lassoed or typed scripture reference becomes a linked passage object in the
notebook, a tap opens NSE · Bible on just those verses with a Full chapter door back to ordinary
reading: R1 the seam (`IBible.resolve`/`beginAt`, `ExtensionContract.API_VERSION` 11 → 12 as a
method-floored tail, `MIN_API_VERSION_FOR_BIBLE_REFERENCE` = 12, the action floor 11 untouched),
R2 the passage view + Full chapter + `BibleSchema.V3`'s `recent_ref` table, R3 the host object
(`LinkPayload.KIND_BIBLE`, `BibleRefFlow`, the two gated doors), **R4 walked on the Nomad
2026-09-13 (adb; the lasso and hand-feel left to the user)** — every path but the lasso conversion
passed, one post-walk fix (the refusal dialog's Edit button), **R5 docs COMPLETE 2026-09-13**:
plan + ledger `extensions/bible/REFERENCE_PLAN.md`; reference `extensions/bible/docs/bible.md`
§ "Bible references". **FROZEN 2026-09-13 on the user's own hand walk on the Nomad** ("Looks and
works good!" — the lasso conversion included; the eleven-button lasso bar fits). No code review —
the user's call, the same waiver arc 37 took. Walk trap, not ours: the `.dev` host binds the
RELEASE ML Kit first (`caller is not the host`), so
`com.symmetricalpalmtree.notesproutsn.ext.mlkit` is `pm disable-user`'d on the Nomad beside the
release pad and calendar. **B9 "Send to notebook" (2026-09-13, the user's decision after B8):**
the reader's far-right Send button (only with a notebook behind it — `EXTRA_BIBLE_SEND_ENABLED`)
parks the current chapter (whole) or passage, `RESULT_BIBLE_SEND`, the host takes it over the held
bind (`IBible.takeOutgoingReference`, `API_VERSION` 12 → 13 as the method floor
`MIN_API_VERSION_FOR_BIBLE_SEND`) and lands it as a selected Bible reference object at the page
centre (`BibleRefFlow.insertResolved`); the reader closes — `docs/bible.md` § Send to notebook.
**Arc 39 "Lookup" (2026-09-13, a fresh user decision, branch `bible`): the Bible from the
document editor** — select a reference in Write or Preview, tap **Bible** in the system
text-selection toolbar, the passage view opens over the editor, Back returns to the editor as it
was. `IDocumentHost.openReference` (`API_VERSION` 13 → 14; `:ext-document`'s editor service
redeclares 14, `DocumentContract.MIN_API_VERSION_FOR_DOCUMENT_LOOKUP`), one boolean on the
editor's Intent (`EXTRA_DOCUMENT_BIBLE_AVAILABLE`, the host's discovery), the host resolves and
parks (`LookupHandoff`), and the editor starts the host's exported `BibleLookupActivity`
trampoline — **the stopped notebook must never launch the reader itself: it cannot see the
result until the editor closes and the bind stays held** (measured; the first design failed
exactly so). Alerts, never toasts, for "not a reference" and "unavailable" (the user's call).
Walked over adb on the Nomad (Write, Preview, Back, a second lookup, the alert) and **FROZEN
2026-09-13 on the user's own hand walk ("It works great!")**. Plan + ledger `extensions/bible/LOOKUP_PLAN.md`; reference
`docs/document.md` § "Bible lookup", `docs/extensions.md` rows 58–59, `bible.md` § "Lookup from
the document editor". No code review — the user's call.
**Arc 40 "Verses" (2026-09-13, a fresh user decision, branch `bible`): the verses themselves on the
page.** A lassoed/typed reference, the reader's Send, and the lasso's Verses on a placed reference
can now land the passage's own words — a bold label line then plain-numbered verses, ten verses at
most, whole chapters refused outright — as an ordinary text object wrapped in the same kind of link
(`LinkPayload.KIND_BIBLE_TEXT` = 4), rather than only a citation of it. Seam: `IBible.passageText`
(`API_VERSION` 14 → 15 as the method floor `MIN_API_VERSION_FOR_BIBLE_TEXT`; `MIN_API_VERSION_FOR_BIBLE`
untouched at 11) is the first and only Bible-seam call to answer with scripture itself rather than a
reference to it. `VersePlacement` puts the column at 10 % of the page width, wrapping at the page's
right edge (the text model re-derives width from `x` on every load, so a centred column cannot
survive a reload); a refusal — too long, or no clear room on the page — is an alert with OK, never a
toast. Walked over adb on the Nomad (`.dev` builds), **COMPLETE + FROZEN 2026-09-13 on the user's own
hand walk ("This looks and works good")**; one finding fixed mid-walk (a successor selection's transfer-paste latch was cleared too
early and left it stranded under PEN) and one after (a page with no clear band no longer stacks the
verses on what is already there — "No room on this page" instead). Plan + ledger
`extensions/bible/VERSES_PLAN.md`; reference `extensions/bible/docs/bible.md` § "Verses on the
page", `docs/extensions.md` § "Arc 40's tail: `passageText`" + audit row 60, `docs/objects.md` §
"Text objects" + "Standing traps", `docs/links.md` § "The Bible kind". No code review — the user's
call, the same waiver arcs 37–39 took.
**Arc 41 "Cross references" (2026-09-14, a fresh user decision, branch `crossref`): the BSB's
cross references tappable.** Every `\r` parallel-passage line's references are underlined and a
**finger** tap opens the cited passage **one screen further** (a second in-process `BibleActivity`
in passage mode — Full chapter's road in reverse; Back returns to the chapter; Send relays up); a
tap on a footnote caller `*` opens the note in a bordered popup under its line ("1 Peter 3:8" +
text) with its own references tappable. The tap rides `ListSwipe.onTap` (`:sn-screen`, an
optional callback: one finger, never past slop, never a swipe); a tap on anything else is nothing.
**The Biblesprout bug it was asked to avoid was upstream data**: the publisher's USFM omits the
book code on both Song of Solomon lines, and the builder fell back to the source book (1 Peter 3
→ 1 Peter 1). `tools/bible/build_bible_db.py` now resolves a code-less target from the display
text's book, refuses non-canon books (Jasher, Enoch, Esdras), reads one-chapter books' bare
numbers as verses, and **fails the build** on any dangling or mis-booked target (`_check_xrefs`);
asset rebuilt, 3,271 rows. No seam change — no `API_VERSION` bump. A one-finger **swipe up**
walks back from any reader a link led to (a cross-reference passage, a Full chapter from one, a
notebook Bible link, the editor's Lookup); the plain Bible-button door is silent. Walked over adb
on the Nomad (`.dev`), then **COMPLETE + FROZEN 2026-09-14 on the user's own hand walk ("This
feels fantastic! My heart is happy!")**; branch `crossref` **merged to `main` 2026-09-15
(`--no-ff`) and deleted** after arc 42 froze on it. Plan + ledger `extensions/bible/CROSSREF_PLAN.md`; reference
`extensions/bible/docs/bible.md` § "Cross references". No code review — the user's call, the arc
37–40 waiver.
**Arc 42 "Notes" (2026-09-14, a fresh user decision, branch `crossref`): a personal commentary.**
A side panel in the reader ("Notes") lists every notebook page or document holding a Bible
reference into the chapter being read — the user's own words: "everything the user writes about
any given book, chapter, or reference will be available when they want to regardless of when they
wrote the note." Eleven locked decisions, compressed: the reader's own store keeps the index
(`note_ref`, `BibleSchema.V4`); the host pushes it live at every act that touches a link plus a
Rebuild door that walks every openable notebook (locked ones skipped, not prompted); only a
Lookup indexes a document, nothing scans text; scope is the chapter in view or a passage's own
ranges; the panel is a right-hand side panel, no mark on the page; a row follows into that
notebook at that page (or its editor), the reader closing first, locked notebooks prompting;
Rebuild lives in the panel's own header; its door is a second granted exception to "bottom bars
are pager-only" (`btnNotes`, `ic_notebook`). Seam: seven `IBible` tails after `passageText`
(`API_VERSION` 15 → 16 as the method floor `MIN_API_VERSION_FOR_BIBLE_NOTES`;
`MIN_API_VERSION_FOR_BIBLE` untouched at 11) — six store-taking, bind-per-call pushes
(`replacePageNotes`/`replaceNotebookNotes`/`renameNotebookNotes`/`deleteNotebookNotes`/`pruneNotes`/
`noteDocumentReference`, the tag manager's `assign` shape) and one held-bind read
(`takeOutgoingNote`). No code review — the user's call, the arc 37–41 waiver. Walked over adb on
the Nomad (`.dev`): Notes opened in 13 ms with "No notes for John 3", Rebuild walked 47 of 50
notebooks (19 references, 2 locked skipped, 1 failed) in 8,550 ms and reopened the reader, a row
tap flipped the notebook to the page it named, and a Send re-pushed the page 750 ms later. One
finding fixed mid-walk: a just-landed link's in-memory `createdAt` is 0 until its row is reloaded,
so it is dated **now** rather than 1970. Plan + ledger `extensions/bible/NOTES_PLAN.md`; reference
`extensions/bible/docs/bible.md` § "Notes — the personal commentary". **COMPLETE + FROZEN 2026-09-15 on the user's own Nomad hand walk ("All tests pass")**; branch
`crossref` merged to `main` 2026-09-15 (`--no-ff`) and deleted — "on crossref" means `main`. The
one recorded gap, left as-is on the user's call: no Notes button on the reader opened from the
editor's Lookup door (`bible.md` § Not in this arc). No code review — the user's call.
**Arc 43 "Sketch" (branch `sketch`, from 2026-09-15, a fresh user decision granted the same day):
a raster sketch beside each page's ink.** Notesprout SN's tenth extension point, `ACTION_SKETCH` +
`ACTION_SKETCH_SCREEN`, served by **`NSE · Sketch`** (`:ext-sketch`) at `extensions/sketch/`, the
Bible `projectDir` pattern repeated (the sixteenth module, the second living outside this app's own
Gradle root). Fourteen locked decisions, compressed: a third notebook-creation radio (Handwritten /
Text / Sketch, TEXT winning any foreign conflict); the Paintsprout pencil as-is (`StrokeStyle.PENCIL`,
1.2 px, `#505050`) but baked at a **constant pressure 0.5 on Ratta** under a `DARK_GRAY` preview — the
firmware paints one tone per pen, so no live preview can track a soft touch, the user's on-device
revision of decision 3 for Ratta only; the rubbing eraser (12 px); the sketch as its own bundle page
after the ink page on export, plain white paper always, never a template; the cover is the last-shown
page's sketch over the ink bake; `btnSketch` on the notebook's bottom strip, left of Bible — a
further granted exception to "bottom bars are pager-only"; Erase page is ink-only, never the sketch;
and, after the first Nomad walk found it missing, page insert/delete **and their own undo/redo
gestures** from the sketch face itself (K5b, the user's same-day follow-up decision), mirrored onto
the **notebook's own** undo stack so the two histories stay one history. Seam: `ISketch.begin(host)`
takes **no store** — the seam's held bind runs backwards, the host minting `ISketchHost` for the
extension rather than lending a store — `API_VERSION` 16 → **17** as a birth floor
(`SketchContract.MIN_API_VERSION_FOR_SKETCH`), then → **18** at K5b for five more `ISketchHost`
tails behind the method floor `MIN_API_VERSION_FOR_SKETCH_PAGES`. `SoilSchema.TYPE_SKETCH` was a
plain PNG-blob row, one per page, minted on first save — history until arc 45, two lossless-WebP
rows since (§ below); **`MAX_BYTES` = 6 MiB is the one written-down hard refusal in the whole
family**, per row (the SQLCipher cursor window — an image above it could never be read back). The engine work landed in `~/git/g-paper` first, on Fable's brief, Opus writing it and Fable
reviewing every diff before publish: Phase 19 → 0.1.32 (the Ratta cadence and hairline measurements),
Phase 20 → 0.1.33 (per-segment dirty rects, a silent `loadPageRaster`), Phase 21 → 0.1.34 at K8
(`RattaTuning` removed, its four measured values now plain constants). K0 through K7 are all
**COMPLETE ✅** on the user's Nomad hand walks (2026-09-15/16) — K8 "Docs + freeze" is the last phase:
`extensions/sketch/docs/sketch.md` is the reference once frozen, `extensions/sketch/SKETCH_PLAN.md`
the plan + ledger (history after freeze, per the maintenance protocol below), and the branch was merged to `main`
(`--no-ff`) and deleted 2026-09-16 after the user's final Nomad walk — "on sketch" means `main`.
No next Bible phase (bookmarks, longer or multi-page passages) without a fresh user decision.
**Arc 44 "Pencils" (branch `pencils`, from 2026-09-17, a fresh user decision granted the same day):
the one pencil becomes six greyscale shades, twelve lead sizes, and a fixed gel pen, all remembered
on the device.** Nine locked decisions, compressed: **six ladder shades** — the hand's own amendment
to the fifteen first built, every other rung from 1 to 11, since only 0 / 5 / 9 preview exactly as
they bake on the firmware's three tones — levels **1 · 3 · 5 · 7 · 9 · 11**
(`#111111` … `#BBBBBB`, no pure black on the pencil, the gel pen owning black), default level 5
— **narrowed again 2026-09-18 (arc 45's decision 6) to the firmware's four tones, levels 0 · 5 ·
9 · 15 — black, grey, light grey, white — the white lead a lightener over graphite, previewing
LIGHT_GRAY on g-paper 0.1.40**;
**twelve sizes** — the hand's own amendment to the five first built, once the wide-lead walk found
no lag — **1.2 / 2 / 4 / 7 / 12 / 16 / 20 / 24 / 32 / 48 / 64 / 96 px** in two rows of six, default
1.2; a fixed **gel pen** (`StrokeStyle.PEN`, black, **5 px** — the hand's own amendment from a
starting 3, by way of 7); a third top-bar tool button, Pencil · Pen · Eraser, re-tapping the armed
Pencil opening `PencilBar` over `AnchoredBar`; the Pencil glyph filled with the armed shade
(`PencilIcon`, `ic_pen_fill`), the fill kept while Pen or Eraser is armed. This is the arc's fresh
decision in the two places the standing rules require one: it **amends arc 43's decision 3** ("no
tilt, no width choice, no colour") and it grants the family's **first tool-options bar on an SN
paper screen** since P1 removed the tool panels — neither is a precedent for the notebook's own
toolbar. Seam: `ExtensionContract.API_VERSION` 18 → **19**, two `ISketchHost` tails after
`redoPage` — `toolSettings()` (null = nothing remembered) / `putToolSettings(in …)` (transaction
codes 12–13) — behind the method floor `SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS` = 19;
`MIN_API_VERSION_FOR_SKETCH` stays 17 and `_PAGES` 18, no action floor moved, only `:ext-sketch`
redeclares 19; the settings live in host prefs (`SharedPreferences("sn_sketch_tools")`, not
`sn_tool`), one setting for all notebooks, never in the `.soil` and never backed up. Engine: the
Ratta pencil's live preview maps each shade to the nearest usable firmware tone
(`RattaInkMap.pencilPreviewFor`, g-paper Phase 23 → **0.1.36**), and the EMR ceiling lifted so a
96 px lead previews at the width it bakes (`RattaEmr.EMR_MAX` 1200 → 9600, g-paper Phase 24 →
**0.1.37**) — both landed in `~/git/g-paper` first on Fable's brief, Opus writing, Fable reviewing
every diff. Plan + ledger `extensions/sketch/PENCILS_PLAN.md`; reference
`extensions/sketch/docs/sketch.md` § "Tools (arc 44)". **COMPLETE + FROZEN 2026-09-17 on the
user's Nomad hand walks; branch `pencils` merged to `main` 2026-09-17 (`--no-ff`) and deleted — "on pencils" means `main`.**
**Arc 45 "Ink" (branch `ink`, from 2026-09-17, a fresh user decision granted the same day): a
sketch is now two rasters, and ink is never erased.** The user's decision that opened it: "in the
real world, ink is more permanent than pencil." Five locked decisions, compressed: the rubber
rubs the **graphite** raster only — ink is never read, rubbed, or in an eraser's undo tiles (a
future `inkLift` "resists rather than refuses" fraction is its own fresh decision, not this arc);
naming (arc 45 **"Ink"**, Notesprout branch **`ink`**, g-paper branch **`two-rasters`** → Phase 26
→ 0.1.39, letter **G**); storage as **two rows per page**, `sketch_graphite` + `sketch_ink`
(`SoilSchema.TYPE_SKETCH_GRAPHITE`/`TYPE_SKETCH_INK`, the dead `TYPE_SKETCH` excluded only), each
a page-sized **lossless WebP with alpha** (RGBA — colour-ready), `MAX_BYTES` = 6 MiB **per row**;
**no legacy** — the Nomad's dev library and the Manta's release library both lose their existing
sketches by the user's word, no migration, no PNG sniffing, no warning; the model recipe
unchanged from arcs 43–44 (Fable orchestrates/seams/reviews, Opus codes, Sonnet scaffolds/tests/
walks, no Haiku, no code review). Derived from the exploration: the two rasters flatten with
`PorterDuff.Mode.DARKEN` everywhere a sketch becomes one picture — order-independent, and right
for a coloured ink later (min per channel); routing is by `StrokeStyle` in the engine
(`RasterLayer.of(style)`), never by colour. Because the seam's own chunk calls
(`ISketchHost.readSketchChunk`/`saveSketchChunk`) changed shape **in place** rather than growing a
tail, `SketchContract.MIN_API_VERSION_FOR_SKETCH` moves **17 → 20** — the family's **third
non-tail break** (after arc 21 / W4's `TagShowing` and arc 22 / X1's store) and the **first to
move a single point's own action floor after its birth** (X1 moved three points' floors at once,
to 6, when floors were introduced in the first place). The engine work — `RasterLayer`, the lazy
two rasters, the `DARKEN` flatten, the graphite-only rubber, the layered API with the un-layered
forms kept meaning graphite — landed in `~/git/g-paper` first, on Fable's brief, Opus writing it
and Fable reviewing every diff before publish: Phase 26 → **0.1.39**, then Phase 27 → **0.1.40**
(a white lead previews LIGHT_GRAY) for the user's pre-merge decision 6 of 2026-09-18: **the pencil
offers the firmware's four tones — black, grey, light grey, white (levels 0 · 5 · 9 · 15)**, the
white lead paling graphite under it (flecks go down `SRC_OVER` on the raster; nothing on bare
paper under `DARKEN`) — the "white/highlight pencil" future, decided. Plan + ledger
`extensions/sketch/INK_PLAN.md`; reference `extensions/sketch/docs/sketch.md` § "Arc 45's
decisions". **COMPLETE + FROZEN 2026-09-18 on the user's Nomad hand walk (the white lead walked
too — "works good enough for now"); `ink` merged to `main` 2026-09-18 (`--no-ff`) and deleted,
g-paper `two-rasters` likewise** — "on ink" means `main`.

**Arc 46 "Palette" (2026-09-19, branch `tools`, letter Q, `extensions/sketch/PALETTE_PLAN.md` —
COMPLETE + FROZEN 2026-09-19 on the user's Nomad and Manta walks; `tools` and g-paper's
`ink-over`/`ink-true` unmerged, merge on the user's word; post-freeze the same day: the face's page
is remembered on every move, `SketchHostHooks.rememberLastOpened`)**
— the user's decision that, with the pencil and the pen going direct to the panel under a dither
(g-paper 0.1.41–0.1.43), the four-tone limit no longer applies: **all sixteen greys for the
pencil and the gel pen alike** — **Atelier's own sixteen tones** (`SketchPalette.TONES`, darkest
first, 0 black / 15 white; the pencil's default `#505050`), white included on the pen; **one
pencil width, 4 px** (arc 44's twelve leads withdrawn); the shade panel **on each pen button's
re-tap** (the Pencil's for the pencil's shade, the Pen's for the pen's — a Palette button was
opened and withdrawn on the first walk) with Atelier's swatches in a **4 × 4** (fill · gap ·
dotted ring; solid ring on the selected); **both pen glyphs wear their own shade** (`ShadeIcon`,
`ic_ballpen_fill`; `CollapsedChrome.PenKinds.altIcon` + `onReTap(alt, anchor)`,
`PaperToolbar.onPenReTap(alt)` in `:sn-screen`); and **ink flattened over graphite** — "a white
gel pen can write over anything" — g-paper **Phase 30 → 0.1.44** (`SRC_OVER`, ink on top, in the
engine, `SketchRaster` and `SketchCover`; pencil over an ink line is hidden; amends arc 45's "no
top and no bottom"), then **baked ink shown in its true tone on the panel** — g-paper **Phase 31
→ 0.1.45** (`DitherFlatten.coverage`: graphite and a live pen stroke dither, settled ink is its
grey) and **settling at the next non-drawing event rather than at pen-up** — Phase 32 →
**0.1.46** (a tool or shade pick, a rub, an undo, a page load), the host's `settleDisplay()`
before chrome opens (Phase 33 → 0.1.47, called from `PaperScreenActivity.toggleChrome`,
`CollapsedChrome.open` and the sketch face's shade panel), and **the pencil settling in tone
too** — grain in grey, the dither the live picture only (Phase 34 → **0.1.48**; 0.1.49: the rubber settles before it posts; Phase 35 → **0.1.50**: a 2.5 s pause settles on
its own; a page turned to is shown settled from its first frame). **Arc 47 "Side" (2026-09-19/20, same branch): the pencil's flank — g-paper Phase 36 → 0.1.51, no seam change; see `extensions/sketch/docs/sketch.md` § "Arc 47's decisions".** Seam:
`SketchToolSettings` grows a fourth int `penShade` — **a parcel tail, not a method** —
`API_VERSION` 20 → **21** named by `SketchContract.MIN_API_VERSION_FOR_SKETCH_PEN_SHADE`; the
action floor stays 20; `size` is a dead wire slot written 0. Reference
`extensions/sketch/docs/sketch.md` § "Arc 46's decisions" + § "Tools (arcs 44–46)". Branch `tools`
is expected to carry further tool arcs, each a fresh user decision. No ELEVENTH extension point,
no next Sketch phase (eraser sizes, per-notebook tool memory, an `inkLift` fraction, quantizing
graphite alpha to the 16-grey ladder, a pencil library of Atelier's grades) and no other new arc
without a fresh user decision; no re-raising of any waived / declined review finding.

**Maintenance protocol (replaces the per-arc phase protocol):**

1. **Read the matching `docs/` file first** (table below) — each is the authoritative as-built
   reference for its feature, including its failure table and traps.
2. **The plan files are history, not instructions.** `RATTA_PLAN.md` (arcs 1–24 ledger + the
   still-binding decisions of the whole effort, § "Standing traps" and the model recipe) and the
   standalone `<ARC>_PLAN.md` files (arcs 25–36), and each of arcs 37–45's own plan files
   (`extensions/bible/*_PLAN.md`, `extensions/sketch/SKETCH_PLAN.md` + `PENCILS_PLAN.md` +
   `INK_PLAN.md`), hold
   every phase record and judgment call. Consult them to learn *why* something is the way it is;
   never "resume" a phase from them.
3. **Fixes to shared screen logic go in `:sn-screen`, engine gaps in `~/git/g-paper`** — never
   worked around in a consumer or the host (see Standing rules).
4. Model recipe still applies: Fable plans and writes the seams / crypto / engine-facing code,
   Opus substantial features, Sonnet scaffolding / docs / device walks (≤5 background agents).
   Walk on the Nomad by hand where adb cannot drive it (multi-finger gestures, lasso, text entry).
5. Version bumps: every module's `versionName` moves in lockstep with `:app`.

**Subsystem docs (`docs/`) — read the matching one before working in that area:**
`docs/library.md` (library screen, naming schemes, **search** — arc 20's fuzzy name search and the
shared `core/FuzzyRank` matcher, grown by arc 21 / W4 into one query over names **and tags** with
tagged pages as their own cards) · `docs/notebook.md` (the notebook screen:
tools, selection, **snap to guides**, headings, Contents, **Recents**, gestures, **the page
template picker — the whole library since arc 13**, arc 21's tag button and the lasso's Tag, undo,
frame-silence ledger) ·
`docs/links.md` (arc 6: link rows/payload, render, picker + create-in-picker, follow + trail) ·
`docs/templates.md` (arc 13: **the paper library** — the two kinds and no third, the sentinels that
are not rows, the reserved **Default** folder, the one browser its three hosts share, SAF import and
export, the Pinned/Recents/Search shelves, the `.soil` **token** and reuse-before-mint, the failure
table, and the abandoned generator idea) ·
`docs/clipboard.md` (arcs 7–8: the clipboard — one index row, one envelope, two kinds; the page
half's long-press sheet and the object half's Copy/Cut, tap-to-place and lasso popup, both
within and **across notebooks**, where a copied link's own-notebook target is re-pointed at the
notebook it came from) ·
`docs/extensions.md` (the **seam**: the eight extension points — the recognizer, arc 11's
screen-owning scratch pad, arc 15's generic exporter point, arc 16's generic importer point,
arc 19's screen-owning document editor with its host-callback binder, arc 21's tag manager,
arc 23's screen-owning calendar and arc 25's store-taking bind-per-call cloud-storage point —
**the extension store, rebuilt on real SQLite tables behind gated SQL in arc 22**, the tier-2
recipe for an extension-owned screen, and **the boundary audit**) ·
`docs/export.md` (arc 15, grown arc 18: notebook export as a feature — the library sheet's Export…
row, the `ExportActivity` screen with its now-real two-exporter chooser, the keying trio and its
host-side transforms, `SoilOpenFiles`, the conditional-deletion rule, **`NSE · PDF Export`** — the
host-renders/extension-assembles source-kind split, its page-template and password-protect
options, the passwordless-PDF silence call — and the failure table for both exporters) ·
`docs/import.md` (arc 16: notebook import as a feature — the library's Import button, the SAF
picker and extension match, the always-re-key-to-global pipeline, the untrusted manifest, the
three questions, the remap, the staged-rename Garden write, the failure table) ·
`docs/backup.md` (arc 17: **compaction + local backup** — the seal-time `.soil` purge and index
purge, sidecar hygiene and the reopen-waits-on-the-claim rule, the Backup screen, the engine's
index-last ordering and stamp map, the WAL-alongside rule for every file kind, the `.part`/`.old`
destination discipline, the exclude toggle, the failure table; grown by **arc 21 / W5** — every
`Garden/<pkg>.db` is in the backup set, and the manual copy-back that stands in for a restore) ·
`docs/document.md` (arc 19, per-device state on rows arc 22 / X4: **Documents** as a feature —
the page is the draft, the document is the result: the data model and flags-as-watermark, the
extension editor and its two-process autosave/teardown table, the `prefs` / `word` / `caret`
tables, seeding and Bring in, the notebook document, text documents, the export half, Proofread,
the failure table) ·
`docs/tags.md` (arc 21, rebuilt on rows arc 22 / X3: **Tags** as a feature — tags on notebooks
and pages, the identity and lifecycle rules, the `tag` / `assignment` tables where every assignment
names its notebook, the two-query search merge, the tag screen's three modes, the four doors
(library sheet, notebook bar, lasso, search), and the failure table) ·
`docs/scratchpad.md` (arc 11, rebuilt on rows arc 22 / X2, its ink helpers moved to `:ext-ink`
arc 23 / Y1: the Scratch Pad as a feature — screen,
tools, pages, the `page` / `stroke` / `state` tables and the op-log flush with no page ceiling,
both transfers, failure table) ·
`docs/calendar.md` (arcs 23–24: the calendar as a feature — the three pages Month/Week/Day, the
`period` / `page` / `stroke` / `state` tables with rows minted on the first stroke, navigation and
the bookmark, both transfers, the failure table, plus arc 24's **Events** — the day list and
editor, recurrence, the handwriting-or-text note, and the grid glyphs) ·
`docs/cloud.md` (arc 25: **the cloud** — `ACTION_CLOUD_STORAGE` as a generic seam, `NSE · Google
Drive` owning OAuth and the only `INTERNET` in the app, the Drive tree under its own root, the
Backup screen's Cloud section and the inline Connect offer, the host-drawn browser, the three
consumers — export destination, backup leg, import source — the measured `CloudTimeouts` table
and the failure table; no extension is aware of the cloud; arc 27 added the restore source as a
fourth consumer, documented in `docs/restore.md`) ·
`docs/encryption.md` (arc 26 "Keys": **encryption as a feature** — the key model (one global
recovery key or typed passphrase, per-notebook passphrases, derived raw keys), the Encryption screen
behind the library's lock button (Reveal / Change passphrase / Forget), `SoilRekey` as the only
key-changer on disk and its interrupted-commit recovery, the journaled `GlobalRotation` with its
three resume paths and quarantine, `KeyScope` + the pure `KeyResolver` + the one
`NotebookPassphrasePrompt` and the open-site table, the four scope doors over `ScopeChange`, the
import chooser, `NotebookRecovery`, the failure table, the measured Nomad numbers and the traps) ·
`docs/restore.md` (arc 27 "Restore": **whole-library restore as a feature** — the one rule (a
restore installs what the backup's index names and the proven key opens, never "the folder"), the
manifest rules for both legs, staging on the library volume, the rename-only commit with the
installed index as the marker and its four outcomes, the key proof before any commit, the orphan
prune, **the destination rule** (this device's backup destination is parked and re-applied; the
backup's is always discarded), per-item interrupted-commit recovery, both sources, the screen, the
failure table, the measured Nomad numbers, the fault seam and the traps) ·
`docs/objects.md` (arc 28 "Objects": **sticky notes, text objects and six shapes as a feature** —
the three additive rows and their bit packing on the 18 universal columns, the D8 draw order, the
Insert bar, text insert / convert / edit, the g-paper transform mode and **the host contract as
built**, the sticky editor with its transfer singleton and EPD handoff chain, selection modes /
undo kinds / clipboard arms / whole-object erase, PDF endnotes over `PageBundle` v2 +
`bundleVersion`, the failure table, the design calls, the traps, the Nomad walks and the tests) ·
`docs/sn-screen.md` (arc 11 / J1: the shared `:sn-screen` paper-screen library — what may live
there, what may not depend on it, and the `nonTransitiveRClass` flag that holds it together) ·
`extensions/bible/docs/bible.md` (arc 37 "Bible": **NSE · Bible** as a feature — the slim BSB
SQLite build, the paginated print-look reader, single-finger swipe and chapter/book flow, the
Contents side panel (book rows → chapter grid; swipe down opens it), the Recents side panel (the
chapters picked by name; clock button + two-finger swipe down), the Search side panel (B8: a
reference goes there, words are ranked hits — FTS4 + BM25), the stored position and the
`recent` table, the `extensions/` monorepo-root pattern; grown by **arc 38 "Reference"**:
notebook-linked Bible reference objects — the reference parser/codec, the passage view, Full
chapter, and the Recents' `recent_ref` table of followed passages; grown by **arc 40 "Verses"**:
the verses themselves as a linked text object on the page) ·
`extensions/sketch/docs/sketch.md` (arcs 43–44: **NSE · Sketch** as a feature — the raster
pencil and rubbing eraser, the store-less seam (`ISketch`/`ISketchHost`), the data model
(`SoilSchema.TYPE_SKETCH`, `NotebookFlags.SKETCH`, `NotebookKind`), the screen (`SketchActivity :
PaperScreenActivity`), pen-idle-debounced saves and the parked-save recovery, the 64 px raster-undo
tiles, page insert/delete and their own undo/redo from the face (K5b), export as the ink page's own
bundle-page follower, the failure table and the Nomad numbers; `extensions/sketch/SKETCH_PLAN.md`
is history once frozen, this doc is the reference; grown by **arc 44 "Pencils"**: six pencil
shades, twelve sizes, a 5 px gel pen, the `PencilBar`, the filled Pencil glyph, and the two API-19
tool-memory tails (`extensions/sketch/PENCILS_PLAN.md` is history once frozen), and by **arc 45
"Ink"** (two rasters, `extensions/sketch/INK_PLAN.md` history once frozen)).

## Standing rules

All root `CLAUDE.md` rules apply (Kotlin/17, kotlinx-serialization only, no new Gradle
deps without discussion, no Material Components, no `runBlocking` on main, `Slog.d` not
`Log.d`, e-ink design system, Tabler icons only). Plus, for this app:

- **The one `Slog` exception, written down (arc 34 / L20):** `Slog` lives in `:sn-screen`, so it is
  on the classpath of `:app`, `:sn-screen`, `:ext-ink` and every extension that depends on
  `:sn-screen` — and those must use it. A module that depends on **`:extension-api` only**
  (`:ext-image`, `:ext-pdf`, `:ext-mlkit`, `:ext-soil`) has no `Slog` to call and writes
  `if (BuildConfig.DEBUG) Log.d(tag, …)` by hand instead — its own module's `BuildConfig`, the same
  gate `Slog.d` compiles to, and the same zero cost in release. It is not a `Slog`-rule violation
  and does not want "fixing": pulling `:sn-screen` into an exporter for a log line would put a
  paper-screen library inside a module that draws no paper.

- **Sixteen modules, own Gradle root — two live outside it.** Fourteen sit under this
  app's own Gradle root; the fifteenth, `:ext-bible` (arc 37, **NSE · Bible**), and the sixteenth,
  `:ext-sketch` (arc 43, **NSE · Sketch**), live at the monorepo root (`extensions/bible/` and
  `extensions/sketch/`) and are pulled in by `settings.gradle.kts`'s `projectDir` include — the
  pattern recorded for every future extension. `:app` (the
  host) · `:markdown` (arc 19 / M1 — the shared markdown engine: parser, renderer, formatter,
  reflow, search, draft, paginator; stdlib only, depends on **nothing** in this project and
  nothing beyond the android SDK its spans use — `:app` and `:ext-document` consume it, one
  engine, no drift; `:app`'s arc-3 `core/markdown` twin was repointed and deleted at M8) ·
  `:sn-screen` (the shared paper-screen
  library — depends on g-paper (`api`) + androidx only, **never** on `:app` or `:extension-api`;
  **a fix to shared screen logic goes there, never in a consumer** — breaking that recreates the
  `RattaNotebookView` sibling-copy trap one file at a time) · `:extension-api` (the contract
  library — stdlib only) · `:ext-ink` (arc 23 / Y1 — Android library, `…notesproutsn.ink`, `api`
  on BOTH `:extension-api` and `:sn-screen`, never `:app`, no manifest components: the pad's and
  the calendar's shared ink-on-rows helpers — `InkWire` / `StrokeRows` + `StrokeBlob` /
  `StoreBatches` / `StrokeReadPlan` / `InkDocument` / `InkAction` / the abstract `InkStore` base —
  and, since Y4's review, the shared stroke SQL/DDL (`InkSql`), the `InkPage` contract, the
  transfer session (`InkTransferSession<P, R>` — the two service stubs' bodies, one monitor, the
  placement bound by the first chunk) and the abstract tier-2 ink screen (`InkScreenActivity`:
  page-op lock, undo/redo replay, the bounded debounce vs unbounded leave flush, the EPD handoff
  order); one copy, no drift; the pad was repointed at it) · `:ext-mlkit` (**NSE · ML Kit**) ·
  `:ext-scratchpad` (**NSE · Scratch
  Pad** — `:extension-api` + `:sn-screen` + `:ext-ink` since arc 23 / Y1, never `:app`; no
  `tools:replace`, no libc++
  `pickFirsts` — Paper's Onyx tax, SN has no Onyx; its ink/store helpers moved to `:ext-ink` and
  `ScratchAction` is now `sealed { Ink(InkAction) · Page }`) · `:ext-soil` (**NSE · Soil Export** —
  `:extension-api` only; one package, TWO services: `SoilExporterService` + `SoilImporterService`,
  label unchanged on the user's call) · `:ext-pdf` (**NSE · PDF Export** — `:extension-api` only +
  module-local `com.tom-roush:pdfbox-android:2.0.27.0`, which never leaks into another module) ·
  `:ext-document` (**NSE · Document**, arc 19 / M3, grown M8 — `:extension-api` + `:sn-screen` +
  `:markdown`, never `:app`; one package, TWO services + a screen: `DocumentEditorService` +
  the editor Activity, and `TextImporterService` on the importer point (declares API version 3
  for its `ImporterInfo.resultKind` tail — per-service meta-data; the editor service declares
  **6** since arc 22 / X4, because it takes a store); no
  Application class, no drawing engine; module-local `com.darkrockstudios:symspellkt:3.4.0`
  (arc 19 / M10 — the pdfbox precedent, never leaks into another module) with the bundled
  dictionary asset `assets/proofread/en_82765.dict` — gzip content behind an opaque extension
  on purpose: AAPT gunzips any `.gz` asset and strips the extension) ·
  `:ext-tags` (**NSE · Tags**, arc 21 / W1 — `:extension-api` + `:sn-screen`, never `:app`; one
  service + a screen: `TagManagerService` and `TagsActivity`, API version **6** (W1 declared 4;
  W4's reshaped `TagShowing` moved it to 5; arc 22 / X3 moved it to 6 with the store rewrite). The
  FIRST tier-2 screen carrying **no paper** — no `PaperView`, no g-paper call and therefore **no EPD
  handoff** (M3's measured answer covers it); the tag index is **rows in the host's extension store
  since arc 22 / X3** — `TagSchema.V1` = `tag` / `assignment`, every SQL string in `TagSql`, the
  identity a stored `UNIQUE` column, a notebook tag's `pageId` `''` and never NULL, deleting a tag
  one `DELETE` under the declared `ON DELETE CASCADE`, and **the transaction is the lock** (arc 21's
  process-local `TagWrites` monitor is gone, with `TagCodec` / `CompactId` and the whole one-blob
  layout)) · `:ext-calendar` (**NSE · Calendar**, arc 23 / Y1 — `:extension-api` + `:sn-screen` +
  `:ext-ink`, never `:app`; one service + a screen: `CalendarService` + `CalendarActivity`; API
  version **10** since arc 35 / HA1 (9 from arc 31 / HV4, 7 from Y1 to HV3); the fourth tier-2 screen and the second with paper; store `CalendarSchema.V1` =
  `period` / `page` / `stroke` / `state`, every SQL string in `CalendarSql`, rows minted on the
  first stroke never on open, NEVER `INSERT OR REPLACE` into `period`/`page` (the cascade takes
  the ink), nothing deletes a period. **Grown in place by arc 24 "Events" (Z1–Z5, 2026-09-02 —
  not a point, no API bump, still 7):** two more in-process screens, `EventsActivity` +
  `EventEditorActivity`, both `exported="false"`, launched only in-process with an
  `ActivityResultLauncher` (the list by `CalendarActivity`, the editor by the list); `CalendarSchema.V2` = V1's step untouched + one events step (`event` /
  `event_weekday` / `event_exception` / `event_reminder` / `note_stroke`), every SQL string in
  `EventSql` / `NoteSql` / `CalendarSql`, `event` NEVER `INSERT OR REPLACE`d (it has children —
  the cascade would take them), the one hard delete in the arc (`EventSql.deleteEvent`), ISO text
  dates throughout. The recurrence engine's WEEKLY interval counts weeks from **Sunday**, not
  og's ISO Monday — a deliberate divergence from og's own calendar, recorded so no reviewer "fixes"
  it. Event text (title, note) is user content: never logged (counts/ids/durations only), never
  outside the calendar's own process. The editor's note is a second g-paper surface
  (`NoteSurface`) in the same process — the calendar hands nothing over before the list; the
  editor's surface reclaims in `onResume` and releases before every `finish()`) ·
  `:ext-cloud` (**NSE · Cloud Storage** — renamed from `:ext-drive` / `NSE · Google Drive` post-freeze
  on 2026-09-05, the user's call: **a second cloud provider is baked in HERE beside Google Drive,
  never as a second extension**, so the module, package/applicationId (`…ext.cloud`), label and the
  point's service (`DriveService` → `CloudService`) are generic while the `Drive*` classes stay
  honestly named as Google Drive's OAuth flow and REST v3 client — `docs/cloud.md` decision 15.
  Arc 25 / V1 — `:extension-api` + `:sn-screen`, never `:app`;
  the ONLY module with `INTERNET`; one service + a screen: `CloudService` on the cloud point and
  `ConnectActivity` behind `HostCallerCheck.enforceActivity` (V2: the WebView PKCE sign-in — the
  connect showing is the ONE held bind on this point, `beginConnect`/`endConnect`, the tag manager's
  bracket; file ops stay bind-per-call; the access token lives in memory only, the refresh token in
  the store; `DriveApi` over `HttpURLConnection` + kotlinx.serialization, the only extension module
  with it); API version **8**; store
  `DriveSchema.V1` = `account(key, value)` (refresh token, account label, cached folder ids), every
  SQL string in `DriveSql`; `DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET` compiled from the shell env into
  this APK only, `ROOT_FOLDER_NAME` "Notesprout SN" / "Notesprout SN Dev" by build type. **V3 (2026-09-04):
  the Export screen's Destination row + host-drawn `cloud/CloudBrowserDialog` over `Exports/` — the
  exporter writes to `cacheDir/export/out.<ext>`, the host uploads; the browser only lists, creates
  only from New folder, never deletes remotely. **V4 (2026-09-04): the backup run's second leg** —
  `Backups/<device folder>/` with its own stamp map (`BackupConfig.cloudStamps`), every upload a
  `SelfContainedSnapshot` (WAL absorbed in a cache copy — **the cloud never holds a sidecar**), one
  `list` per leg, replace-by-name, corroborated never deleted; `CloudBackupLeg` beside `BackupEngine`. **V5 (2026-09-05): the library's Import button asks
  *Import from* when a provider is installed** — the browser in `PICK_FILE` over the provider's root,
  the importer matched before any download, `download` into `cacheDir/import/cloud/` and the matched
  importer streams it into the unchanged pipeline; nothing remote deleted.** **V6 (2026-09-05):
  docs + freeze — arc 25 is complete and frozen; `docs/cloud.md` is the reference.**
  **Read `DRIVE_PLAN.md`, not `RATTA_PLAN.md`, for any work on it.**) ·
  `:ext-image` (**NSE · Image Export**, arc 31 / HV1 — the fourteenth module, `:extension-api`
  only, manifest API **9**, `…ext.image`, the puzzle icon byte-identical; ONE service on the
  existing exporter action — a new *package* on an old point, so discovery needs no `<queries>`
  change: `ImageDescriptor` (PNG image · `png` · `image/png` · one template toggle · `SOURCE_PAGES`
  · bundle v1 · **`DELIVERY_PER_PAGE`**), `ImageExportSpec` (unknown ids and any secret refused),
  `ImageAssembly` (`requireOnePage` — a multi-page bundle is an `IllegalStateException`, never its
  first page; RGB_565 decode, dimension check against the declaration, PNG 100 through a counting
  stream + the `S_ISREG` fsync rule). It never sees a notebook, a `.soil`, or more than one page —
  the host bakes once and splits (`BundleSplit`) and calls it once per page.) ·
  `:ext-bible` (**NSE · Bible**, arc 37 / B0, grown by arc 38 / R1–R2 and by arc 42 / N0–N1 — the
  fifteenth module, and the first living at the monorepo root, `extensions/bible/`, included by
  `projectDir`):
  `:extension-api` + `:sn-screen` only, **never** `:app`, no Room / SQLCipher / serialization.
  Declares `API_VERSION` **16** since arc 42 "Notes" (12 at arc 38 / R1, 13 at B9, 15 at arc 40,
  16 at arc 42; `MIN_API_VERSION_FOR_BIBLE` = 11 from its first
  phase still gates the point's own existence; `MIN_API_VERSION_FOR_BIBLE_REFERENCE` = 12, a
  method floor, gates `resolve`/`beginAt` and the notebook's two reference doors —
  `MIN_API_VERSION_FOR_BIBLE_NOTES` = 16 gates the seven notes tails and the Notes button; an
  11-only reader still serves the plain door). Store `BibleSchema.V4` = `state` (B0) + `recent`
  (B7) + `recent_ref` (arc 38 / R2, the passages followed from a notebook) + `note_ref` (arc 42,
  the notes index, one row per verse range of a pushed reference, two indexes — the span and the
  target), `INSERT OR REPLACE` safe on all four (no children). `ContentInstaller` copying its
  bundled
  `assets/bible/bsb.bible` to its own `noBackupFilesDir` is **the one sanctioned "extension writes
  to disk" exception** in the whole family — re-derivable APK content, never user data, ML Kit's
  own model's class. Its door is a deliberate exception to "bottom bars are pager-only": `btnBible`
  sits on both the library's and the notebook's bottom bars (arc 37 / B0, decision 3), and arc 42's
  `btnNotes` is a second such exception, on the reader's own bottom bar.
  `gradle.properties` sets `android.nonTransitiveRClass=false` — undoing it breaks every
  `:sn-screen` resource reference from `:app`. ·
  `:ext-sketch` (**NSE · Sketch**, arc 43 / K5, grown by K5b, by arc 44 / T3 and by arc 45 / G3 —
  the sixteenth
  module, and the
  second at the monorepo root, `extensions/sketch/`, included by `projectDir`):
  `:extension-api` + `:sn-screen` + `:ext-ink`, **never** `:app`. Declares `API_VERSION` **21**
  since arc 46 / Q1 (20 at arc 45 / G3, 19 at arc 44 / T2–T3, 18 at K5b, 17 at K2, the point's
  birth floor) — 21 = `SketchContract.MIN_API_VERSION_FOR_SKETCH_PEN_SHADE`, the parcel tail
  carrying the gel pen's remembered shade.
  `SketchContract.MIN_API_VERSION_FOR_SKETCH` **moved from its birth 17 to 20 at arc 45 / G2** —
  the point's own chunk calls changed shape in place, so a pre-20 sketch extension or host never
  binds a 20 one on the other side; `MIN_API_VERSION_FOR_SKETCH_PAGES` = 18 and
  `MIN_API_VERSION_FOR_SKETCH_TOOLS` = 19, the two method floors born under the old action floor,
  are now inert history (both ≤ 20). **No store** (`ISketch.begin(host)` takes none — the seam's
  held bind runs
  backwards, an `ISketchHost` the host mints and lends, `IDocumentHost`'s host-side-stub recipe a
  third time). **Two `SoilSchema.TYPE_SKETCH_GRAPHITE`/`TYPE_SKETCH_INK` rows per page** since arc
  45 (order −1, the dead `TYPE_SKETCH` name excluded only), each a page-sized lossless WebP with
  alpha, minted independently on first save (blank means absent, per row);
  `NotebookFlags.SKETCH = 8` + `notebook_meta.sketch` mirrored at every meta site,
  `NotebookKind` (TEXT wins over SKETCH on a foreign conflict), `SoilDao.childrenOf` excluding
  both live names and the dead one, `liveDescendantIds` carrying both (deleted with the page),
  `liveErasableIds` = descendants minus both (Erase page is ink-only, decision 11 of arc 43).
  **`SketchContract.MAX_BYTES` = 6 MiB is the
  one written-down hard refusal in the whole family**, **per row** — the SQLCipher cursor window
  means an image
  above it could never be read back, so a save above the line is refused before a byte is written
  rather than the family's usual "never refuse." `SketchApplication` (`RattaEngine.register()`),
  `SketchService`/`SketchSession` (the `ISketch` stub), `SketchActivity : PaperScreenActivity`
  (`:ext-ink`'s new store-agnostic chrome/handoff base, K2 — pencil (six shades, twelve leads) + a
  5 px black gel pen (arc 44 / T3, bakes to the ink raster since arc 45) + the rubbing eraser
  (graphite only, ink never erased); no lasso, no smart lasso, no scribble
  erase), `SketchPalette`/`SketchToolState`/`PencilBar`/`PencilIcon` (arc 44 / T3 — the pure
  fifteen-built/six-shipped shade + size lists, the tool state derived style/width/colour, the
  `AnchoredBar` over the re-tapped Pencil, the filled-glyph icon), `SketchSaver`/`SketchSaveGovernor`/`PendingImagePark`
  (renamed from `PendingPngPark` at G3; pen-idle-debounced WebP push, per raster, and the
  parked-save recovery), `RasterImage` (WebP lossless encode/decode behind `ImageHeader`,
  `WEBP_EFFORT` = 100), `SketchLayers` (the `SketchContract.LAYER_*` ↔ `RasterLayer` translation),
  `RasterTiles`/`RasterEditBuilder`/
  `SketchEdit` (the Paintsprout Onyx raster-undo port, 64 px tiles, a 48 MB `UndoRedoStack`
  budget, each entry carrying a layer since arc 45), `InkBake` ("Bring in ink" — bare strokes only,
  link-wrapped and sticky ink excluded, lands in the ink raster since arc 45).
  Its door is a further granted exception to "bottom bars are pager-only": `btnSketch` sits on the
  notebook's bottom strip, left of Bible (decision 9) — the third such exception after `btnBible`
  and `btnNotes`.
- **Arcs 26–35 are each COMPLETE + FROZEN.** Each has ONE reference doc and ONE standalone plan
  file whose ledger holds every phase record and judgment call — **read that plan file, not
  `RATTA_PLAN.md`, for any work on the arc.** None added a point, changed the `.soil` schema, or
  had a code review (arc 35 bumped `API_VERSION` 9 → 10 as a compatible method-floor tail) (every waiver was the user's call — **do not
  re-raise any of them**); the version was `0.1.0-ratta` throughout (now `0.1.0-sn`). What binds beyond the plan files:
  - **Arc 26 "Keys"** (U1–U7, 2026-09-05; `docs/encryption.md`; `ENCRYPTION_PLAN.md`) — full
    encryption, `PARITY_BACKLOG.md` item 1. `SoilRekey` is the only key-changer on disk and
    `ScopeChange` its only one-notebook caller; every `.soil` open goes `SoilDatabase.resolve` →
    `open`; `KeySession.get()` is the GLOBAL passphrase only; every raw-key user goes through
    `KeyMaterial.peekVerified`; a caller that just prompted passes `Passphrases(typed)` into its
    read (the raw-key warm is ~9 s on the Nomad). **`RawKeyDerivation.deriveKey` stays on the
    platform PBKDF2 and `KeyOpener.warm` stays serialized — the hand HMAC loop churned ~80 MB of
    native memory per derive and a burst of cold opens after a rotation OOM-killed the process.**
    The Nomad's typed passphrase is in the memory file, never in a doc.
  - **Arc 27 "Restore"** (L1–L6, 2026-09-05/06; `docs/restore.md`; `RESTORE_PLAN.md`) —
    whole-library restore, item 2; L5 was a failure-injection pass instead of a review. The four
    binding decisions: both legs (SAF + cloud, no contract change); replace-all by aside-swap with
    the installed index as the commit marker, no undo; the staged index must open under a key the
    user can supply BEFORE anything live is touched; **the backup destination is device-local
    state a restore never rewrites** (the user's own overwritten-folder incident). Refused while a
    rotation marker stands; walked by hand on the Nomad against a `GlobalRotation`-made foreign
    backup, never the Manta.
  - **Arc 28 "Objects"** (H1–H7, 2026-09-06; `docs/objects.md`; `OBJECTS_PLAN.md`) — sticky
    notes, text objects, six shapes, item 3: three additive row types on the heading pattern (no
    `SOIL_VERSION` bump), a g-paper transform mode (**0.1.27**), the host `StickyEditorActivity`,
    PDF endnotes over `PageBundle` v2; no line objects, no shape recognizer, no extension
    transfers. **Call `armLassoForLanding()` before `setSelection` from any non-lasso context.**
  - **Arc 29 "Loop"** (LE1–LE4, 2026-09-06/07; `docs/notebook.md`; `LOOP_PLAN.md`) — the lasso
    eraser, item 4: `Tool.LASSO_ERASER` in g-paper 0.1.28 (re-pinned to **0.1.39** since
    2026-09-18 — see the pin note below), armed on all four
    paper surfaces from a second tap on the armed eraser → the Point · Lasso `EraserBar`
    (`:sn-screen`), **never a fourth bar button** (a twelfth 62 dp button falls off the Nomad's
    749 dp). The host never repaints from `onLassoErased`. Onyx's side of the engine change is
    untested.
  - **Arc 30 "Page"** (PE1–PE3, 2026-09-08; `docs/notebook.md` § Erase page / § Export page /
    § Undo + `docs/export.md` § Scope; `PAGE_PLAN.md`) — Erase page (one soft-delete transaction,
    `Action.PageErased` replayed by id) and Export page (close → `ExportActivity` at page scope →
    reopen; a host-side page-id filter, every exporter and the seam untouched), item 5.
  - **Arc 31 "Harvest"** (HV1–HV6, 2026-09-08/09; `docs/export.md` § Images / § Presets /
    § Calendar mode, `docs/calendar.md` § Export / § Calendar → notebook, `docs/templates.md`
    § Save as template, `docs/notebook.md` § The received page, `docs/extensions.md`;
    `HARVEST_PLAN.md`) — item 6: `API_VERSION` **8 → 9** as two compatible tails
    (`ExporterInfo.delivery`, `ICalendar.render` + `outgoingTarget()` — method floors only, **no
    module floor moved**), the `:ext-image` per-page PNG exporter (the fourteenth module), Save
    as template, `EXPORT_PRESET` index rows, calendar file export drawn at the screen's bar insets,
    a papered page on a whole-page Send. The calendar bar's one out-door button is an action
    sheet — an eleventh 62 dp button fills the Nomad bar, a twelfth overflows.
  - **Arc 32 "Resume"** (RS1–RS3, 2026-09-09; `docs/library.md` § Launch restore +
    `docs/notebook.md` § Cold-launch restore; `RESUME_PLAN.md`) — launch restore, item 7, **the
    last item: `PARITY_BACKLOG.md` is closed.** The surface stack in prefs is device-local (never
    backed up or restored); host screens maintain it from lifecycle and extension entries from
    `open` / `onResult`, never `onDestroy`; a missing target drops that entry and everything above
    it. **Walk trap:** `am force-stop` the HOST FIRST, then the extensions, in one shell command.
  - **Arc 33 "Focus"** (F1–F5, 2026-09-09; `docs/notebook.md` § Layout / § Gestures,
    `docs/calendar.md`, `docs/scratchpad.md`, `docs/objects.md` § Sticky notes, `docs/sn-screen.md`,
    `docs/extensions.md` row 50; `FOCUS_PLAN.md`) — a fresh user decision: a single-finger
    double-tap hides / shows all chrome on the four paper screens, floating bars over full-bleed
    paper, full-page calendar grids (`CalendarBars` deleted, old calendar ink one bar higher —
    accepted), one global flag crossing as the compatible `EXTRA_CHROME_HIDDEN` tail. A GONE view
    keeps its last size; the flip rides frame-silence exception 6, never `whenPenIdle`.
  - **Arc 34 "Prune"** (P1–P4, 2026-09-09/10; the `docs/*.md` each fix updated; `PRUNE_PLAN.md`)
    — a fresh user decision: the 32 confirmed findings of the 2026-09-09 `/code-review` of arcs
    24–33 fixed with JVM tests where the code is pure; **four candidates refuted and listed in the
    plan — do not re-raise**; the H1 and M7 hand-walks waived. **Post-freeze pruning 2026-09-10:
    L17 had spliced the raw key into `ATTACH … KEY` as a bare `x'…'` blob, so every passphrase
    change failed at the index — a key literal handed to SQLCipher in SQL must be TEXT
    (`ExportKeying.sqlLiteral` around `rawKeyLiteral`), and a change to any such literal's shape
    gets a device walk, never a JVM spelling test alone.** Final counts: 1654 `:app` /
    3078 JVM tests.
  - **Arc 35 "Halves"** (HA1–HA2, 2026-09-10; `HALVES_PLAN.md`; references `docs/calendar.md` §
    Both transfers + `docs/extensions.md` § `advanceOutgoing` + `docs/notebook.md`) — a fresh user
    decision made during the og2sn migration walk on the Manta: a Day's whole-page Send carries
    **both halves, AM then PM**, as two papered pages after the displayed one, one undo step, an
    empty half as its paper; `ICalendar.advanceOutgoing()` appended under **`API_VERSION` 10**
    (method floor `MIN_API_VERSION_FOR_CALENDAR_DAY_SEND`, no action floor moved, only
    `:ext-calendar` redeclares); `InkScreenActivity.parkCompanionPages` hook, `CalendarSession`'s
    outbound FIFO, `ExtensionScreenEntry.drainFurtherPages` + `onDrained(List)`,
    `Action.PagesReceived`; **a "Receiving from …" box over the caller for every send** (calendar
    and pad). No code review (the user's call); walked by the user on the **Manta** (release, the
    migrated library). Final counts: 1654 `:app` / 3079 JVM tests.
  - **Arc 36 "Corner"** (C1–C3, 2026-09-11; `CORNER_PLAN.md`; references `docs/notebook.md` §
    Layout / § Gestures, `docs/sn-screen.md`, `docs/scratchpad.md`, `docs/calendar.md`,
    `docs/objects.md` § Sticky notes) — a fresh user decision: arc 33's "hidden" chrome now
    **collapses to a corner tool button** (top|end, wearing the armed tool's glyph) whose tap opens
    a **mini toolbar** (Pen · Point eraser · Lasso eraser · Lasso · the notebook's Insert · `…`) and
    an **overflow row** of Back + the screen's doors, every entry **mirroring** its bar button
    (visibility / selected / glyph read at each open, `performClick()` of the bar's own button);
    one or two overflow entries sit on the mini toolbar itself (`CollapsedTools.overflowInline`,
    `INLINE_MAX` 2 — the pad's Back · Send, the sticky editor's Back), three or more go behind
    `…` (notebook, calendar). One `:sn-screen` `CollapsedChrome` + pure `CollapsedTools` for all
    four screens; `ChromeToggle(whileHidden, beforeShow)`, `AnchoredBar.show(anchor)`; the
    notebook's Insert / Tags hang their sub-bars under the mini toolbar's / overflow's own buttons
    (a GONE bar's button keeps stale edges). No point, no API bump, no schema change, the one
    global flag unchanged in meaning. **`/code-review high` at the freeze (the user's call) — its
    findings fixed in C3; do not re-raise.** Walked over adb and by the user on the Nomad.
    **Standing trap:** the Nomad carries the release AND `.dev` build of every extension; the
    `.dev` host binds the release scratch pad / calendar first (`caller is not the host`) — the two
    release packages were `pm disable-user`'d there for the walk (re-enable on request). Final
    counts: 1662 `:app` / 3097 JVM tests. **Arcs 1–36 are all frozen; no next arc without a user
    decision.**
- **Every extension APK wears the same icon — the Tabler "puzzle", byte-identical, no exception**
  (the user's call, 2026-09-05, which reversed the three per-subject glyphs granted along the way:
  `:ext-tags`' `tag`, `:ext-calendar`'s `calendar`, `:ext-cloud`'s `cloud`). A package is found by
  its **label** in Settings → Apps; the glyph only says which family it belongs to. A new extension
  copies `ext-soil/src/main/res/drawable/ic_launcher_foreground.xml` and does not ask.
- **SN has TEN extension points** — each added on its own explicit user decision, and
  **no ELEVENTH may be added without another** (arc 21's `ACTION_TAG_MANAGER` was the sixth's,
  granted 2026-08-31; the SEVENTH, `ACTION_CALENDAR`, was granted 2026-09-01 for arc 23 and
  landed at Y1 with the `:ext-ink` + `:ext-calendar` modules, `RATTA_PLAN.md` § "Phases —
  Arc 23"; **the EIGHTH, `CloudContract.ACTION_CLOUD_STORAGE`, was granted 2026-09-04 for arc 25
  "Drive" and landed at V1** with `:ext-cloud` — its plan is the standalone `DRIVE_PLAN.md`; **the
  NINTH, `ACTION_BIBLE`, was granted 2026-09-13 for arc 37 "Bible" and landed at B0** with
  `:ext-bible` at the monorepo root — its plan is the standalone `extensions/bible/BIBLE_PLAN.md`;
  **the TENTH, `ACTION_SKETCH`, was granted 2026-09-15 for arc 43 "Sketch" and landed at K2** with
  `:ext-sketch` at the monorepo root — its plan is the standalone `extensions/sketch/SKETCH_PLAN.md`).
  The full seam — contracts, caps, trust, the
  boundary audit — is `docs/extensions.md`; the rules that bind every point:
  - `ACTION_HANDWRITING_RECOGNIZER` (headings + the markdown engine are core, the engine is
    swappable). **Only `prepare()` may start a model download** (host consent dialog first;
    notebook open only warms an already-present model). Recognized text is never logged on
    either side — counts + durations only.
  - `ACTION_SCRATCH_PAD` + `_SCREEN` — the screen-owning (tier-2) point. Its screen refuses any
    caller that is not a `startActivityForResult` from the host (`HostCallerCheck.enforceActivity`),
    so the host **must** launch it with an `ActivityResultLauncher`. `ExtensionBinder.hold` is
    SN's **only** bind held across more than one call (the operation is the showing).
  - `ACTION_NOTEBOOK_EXPORTER` — generic, plural (`exporters()`), declarative descriptors the
    host renders with its own widgets. **The host keys, the extension delivers via two fds**;
    the spec carries no id, no path, no secret; `OPTION_KEYING` (and any passphrase-kind option)
    is host-executed — only the choice id crosses. Served by `:ext-soil` and `:ext-pdf`
    (`sourceKind` tail: `SOURCE_SOIL` absent-means-today / `SOURCE_PAGES` host-rendered page
    bundle; `ExportSpec.exportSecret` is the ONE deliberate secret that crosses any seam —
    user-typed, export-scoped, opens no Notesprout data). Detail: `docs/export.md` +
    `docs/extensions.md` §§ source-kind tail / export secret.
  - `ACTION_NOTEBOOK_IMPORTER` — the exporter's mirror (plural, two fds, bounded spec, no
    secret/id/path). Probe, unlock (`AttemptLimiter` `"IMPORT"`), the unconditional re-key to
    the device global key, `SafeImportId`, placement, remap and both writes are all host-side;
    the extension only streams bytes. `ImporterInfo.resultKind` (arc 19 / M8, compatible tail —
    absent = `RESULT_NOTEBOOK`) says what the delivered bytes ARE: `RESULT_TEXT_DOCUMENT` forks
    the host after delivery into strict-UTF-8 validation + text-document create instead of the
    `.soil` probe. Detail: `docs/import.md`.
  - `ACTION_DOCUMENT_EDITOR` + `_SCREEN` (arc 19 / M3) — the second screen-owning point, served
    by `:ext-document`. **The host owns every `.soil` read and write** (og's invariant, enforced
    by the process boundary): the seam's new piece is `IDocumentHost`, the first **host-side**
    stub on any SN seam — minted per showing, uid-bound and revoked with the unbind (the store
    binder's recipe). Document text is the only user content that crosses, **chunked** by the
    shared `TextChunks` rule under `DocumentContract.MAX_DOCUMENT_CHARS`, and every save names
    its target `pageKey` (the mode-routing guard, structural). Ink never crosses this seam, and
    document text is never logged on either side. Nothing rides the screen's Intent — no extras
    at all. The editor's per-device state is **rows in its extension store since arc 22 / X4**:
    `EditorSchema.V1` = `prefs` (`key`/`value` — the ONE extension table the host reads, its shape
    pinned in `DocumentContract`) / `word` (the word is the primary key) / `caret` (`pageKey`,
    `offset`, `updatedAt`); every SQL string in `EditorSql`, run only by `EditorStore` (schema
    applied on every call — the binder is fetched per call), behind the `EditorPrefs` facade
    where every exception is the default. `rememberCaret` is one two-statement batch (upsert +
    the LRU trim at 100); the dictionary's add/remove are single statements — no
    read-modify-write, no lock.
  - `ACTION_TAG_MANAGER` + `_SCREEN` (arc 21 / W1) — the third screen-owning point, served by
    `:ext-tags`, and the first whose screen carries no paper. **One interface, two call shapes:**
    a showing is a HELD bind (`begin` → `configureShowing` → launch → result → `end`) and the store
    is lent once; `tags` / `assignmentsOf` / `assign` are bind-per-call and the store rides the call.
    Tag text and target labels are the user's own words — they cross on the bind as a `TagShowing`,
    **never** in the screen's Intent, and are never logged on either side. The extension owns the tag
    index (rows in its extension store since arc 22 / X3); the host owns every entry point, the
    recognizer and the search merge. **The search merge is TWO paged queries** (X3, replacing W4's
    whole-index ashmem `snapshot`): `tags(store, offset)` in pages of `TAGS_PAGE` for the host's own
    `FuzzyRank`, then `assignmentsOf(store, matchedIds, offset)` in pages of `ASSIGNMENTS_PAGE` for
    only the rows the ranking needs — a reply is an ordinary parcel, which is why both page.
  - `ACTION_CALENDAR` + `_SCREEN` (arc 23 / Y1) — the fourth screen-owning point, served by
    `:ext-calendar`. **Scratch-Pad-shaped whole**: an extension writes nothing to disk itself, ever;
    both transfers are copies through the held bind — never the Intent, never a file — carry no
    ids, and keep coordinates 1:1; the tools are the notebook's, fixed; the EPD handoff order is
    g-paper's to keep. `ICalendar` is `IScratchPad`'s four methods with the placement made a real
    type: `receiveInk` carries a `CalendarTarget` on every chunk, as the pad's placement is.
    `CalendarTarget` (kind/date/half) is unmarshal-validated (`requireValid` in its constructor) on
    every chunk. `CalendarDates` (Sunday weeks, hand lists never a formatter, ISO dates only) is
    shared by both sides so the host never guesses the week rule. The notebook → calendar Send
    asks first, host-side, via `CalendarTarget.of`: Today morning / Today afternoon / This week /
    This month. The bookmark (`state`) is written on every show. **Since arc 23 / Y4 (the user's
    checklist call) the calendar also has its own door out, to the pad** — the Scratch Pad is the
    LAST button on every bar, so the calendar carries a third boolean
    (`EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE`) and a fourth result code
    (`RESULT_CALENDAR_OPEN_SCRATCH_PAD`) that ask the host, not the calendar itself, to open the pad
    and bring the calendar back at its bookmark once the pad closes without sending — a compatible
    addition, the calendar keeps declaring 7.
  - `ACTION_CLOUD_STORAGE` + `_SCREEN` (arc 25 / V1, `CloudContract`) — the eighth point and the
    first **generic over a provider**: folders, files and bytes, never a provider's terms; served by
    `:ext-cloud`. **Store-taking, bind-per-call** (the tag manager's second call shape): the store
    rides every call, no held bind, every operation one Binder call under a `CloudTimeouts` row
    sized by on-device measurement. The extension owns the network, the OAuth flow, the client
    credentials and the refresh token (rows in its store); the host has no INTERNET permission and
    sees a `CloudStatus` (connected? configured? account label) and file ops by name under a root
    the provider owns. **No secret, no device path, no URL crosses** in either direction; the
    account label is user content — never logged on either side. Two `IllegalStateException`
    messages are compared verbatim by the host: `NOT_CONNECTED` (offer Connect) and `NETWORK`
    (nothing changed, try again). **No other extension is aware of the cloud** — exporters and
    importers only ever see fds, so a cloud destination changes only where the host's fd points.
    Consumers this arc: export destination, backup destination, import source; no restore.
  - `ACTION_BIBLE` + `_SCREEN` (arc 37 / B0, granted 2026-09-13) — the fifth screen-owning point
    and the second with **no paper** (the tag manager's shape): `IBible` is a held bind,
    `begin(store)` / `end()` and nothing else, the store lent for the showing exactly as the tag
    manager's is. Nothing rides the screen's Intent — no extras at all, the editor's and the cloud
    connect screen's precedent. The store holds one `state` row (`position` →
    `USFM:chapter:verse`); scripture text never crosses the seam and is never logged. Served by
    **`NSE · Bible`** (`:ext-bible`), the ONE module living outside this app's own Gradle root
    (`extensions/bible/`, included by `projectDir`), and the source of the family's one sanctioned
    disk exception: `ContentInstaller` copies the bundled `.bible` file to its own
    `noBackupFilesDir` — re-derivable APK content, never user data, ML Kit's own model's class.
    **Grown by arc 38 "Reference" (R1, 2026-09-13, a fresh user decision) with two compatible
    method tails after `end()`**: `resolve(text): ResolvedReference?` (bind-per-call, no store —
    the notebook's reference dialog asks whether the user's words are a reference this Bible
    knows) and `beginAt(store, reference)` (`begin`'s second opening, onto the passage view).
    Both behind the **method** floor `MIN_API_VERSION_FOR_BIBLE_REFERENCE` = 12
    (`ExtensionContract.API_VERSION` 11 → 12 to express it); the **action** floor
    `MIN_API_VERSION_FOR_BIBLE` stays 11, so an 11-only reader still serves the plain door and
    only loses the notebook's two reference doors. Not a tenth point.
  - `ACTION_SKETCH` + `_SCREEN` (arc 43 / K2, granted 2026-09-15) — the sixth screen-owning point,
    and the second **with paper** after the pad and the calendar (an extension writes nothing to
    disk itself; the host owns every `.soil` read and write). **The held bind runs backwards**:
    `ISketch` is `begin(host)` / `end()` and nothing else — the extension takes no store at all
    (there is nothing of its own to persist), so the one argument on `begin` is an `ISketchHost`
    binder the **host** mints and lends, `IDocumentHost`'s host-side-stub recipe a third time.
    Six `ISketchHost` methods at K2 move a page's PNG and its bare-ink strokes chunked both ways
    (`current`/`requestPage`/`readSketchChunk`/`saveSketchChunk`/`requestInk`/`readInkChunk`);
    five more at K5b (`insertPage`/`deletePage`/`pageContent`/`undoPage`/`redoPage`) let the face
    insert and delete pages and undo/redo one such edit itself, mirrored onto the **notebook's**
    own undo stack so the two histories stay one. **Since arc 45 / G2 a page is two rasters, not
    one PNG**: `readSketchChunk`/`saveSketchChunk` take a raster **layer** and each raster crosses
    as its own lossless **WebP** with alpha, chunked independently of the other. Nothing rides the
    screen's Intent but
    `EXTRA_CHROME_HIDDEN`. Served by **`NSE · Sketch`** (`:ext-sketch`) at the monorepo root
    (`extensions/sketch/`, the Bible `projectDir` pattern repeated); its plan is the standalone
    `extensions/sketch/SKETCH_PLAN.md` (history) + `extensions/sketch/INK_PLAN.md` (history once
    frozen), its reference `extensions/sketch/docs/sketch.md`.
    `SketchContract.MAX_BYTES` = 6 MiB is **the one written-down hard refusal in the whole
    family**, **per raster row** since arc 45 — the SQLCipher cursor window means an image above
    it could never be read back, so a
    save above the line is refused before a byte is written. Not an eleventh point.

  All of them get the **extension store** (`IExtensionStore` — per-package,
  encrypted under the global key at `Garden/<pkg>.db`, minted per bind, uid-bound, revoked with
  the unbind, **and copied by every backup run** — arc 21 / W5) because **an extension writes
  nothing to disk itself, ever**. **Since arc 22 / X1 the store is real SQLite tables behind gated
  parameterized SQL, not key/value:** the extension declares versioned DDL once (`StoreSchema` —
  the host applies the missing steps and refuses a downgrade), then sends `SELECT`/`WITH` through
  `query` and `INSERT`/`REPLACE`/`UPDATE`/`DELETE`/`WITH` batches through `exec` (one transaction,
  all-or-nothing, never held open across Binder calls) as `StoreCodec` payloads, and reads
  `StoreCodec` rows back in ≤ 4 MiB chunks (`StoreReads.all` is the loop). The host validates every
  statement (`StoreSql`: one statement, the head keyword decides the kind, a denylist —
  `PRAGMA`/`ATTACH`/DDL/transaction control — anywhere in the token stream, positional binds only,
  and **every identifier in a reserved space `host_*` / `sqlite_*` / `room_*` / `android_*`
  refused**, quoted or not), runs it on the one connection it owns (WAL, `foreign_keys` ON — a
  declared `ON DELETE CASCADE` cascades), and maps every SQLite failure — a constraint violation
  included — to `IllegalStateException`. Room left the store file: it is a `SupportSQLiteOpenHelper`
  over the same `SoilCrypto`/`KeyOpener` factories, its **format** rides `PRAGMA user_version`
  (`StoreFormat`: 2 = tables; 1 or a `kv` table = the arc-11 store, **wiped on open, no migration**
  — the user's call; above 2 = refuse, file left as found). `host_schema` is the host's one table;
  the editor's `prefs` table is the ONE extension table the host reads (`DocumentContract` pins its
  shape; Document-PDF export's text size, only if file and table exist). The debug menu's
  "Extension store self-test" is the only on-device proof — SQLCipher, ashmem and a real Binder
  cannot run on the JVM; the gate runs there over an injected `StoreExecutor`.
  Action strings are
  SN-namespaced so Paper's extensions are never discovered; trust is same-signature both ways
  (discovery + bind-time re-check host-side, `HostCallerCheck` first thing in every stub method);
  `ExtensionContract.API_VERSION` = **20** and the host accepts `minApiVersion(action)..20` — **the
  floor is per action since arc 23 / Y1** (`minApiVersion` is a map, not a single set): 20 for
  `ACTION_SKETCH` (`SketchContract.MIN_API_VERSION_FOR_SKETCH`, moved from its birth 17 at arc 45 /
  G2), 11 for
  `ACTION_BIBLE` (`MIN_API_VERSION_FOR_BIBLE`, arc 37 / B0), 8 for
  `ACTION_CLOUD_STORAGE` (`CloudContract.MIN_API_VERSION_FOR_CLOUD`, arc 25 / V1), 7 for
  `ACTION_CALENDAR` (`MIN_API_VERSION_FOR_CALENDAR` — a point born at 7 has no older shape to
  accept), `MIN_API_VERSION_FOR_STORE` 6 for the three arc-22 store-taking points, 1 for every
  stateless point — nothing about an existing interface changed, so no existing door vanished with
  this bump (the declared number is still what the extension *requires* of the host). The ledger:
  2 = arc 18's
  `sourceKind` tail · 3 = arc 19 / M8's `resultKind` tail · 4 = arc 21 / W1, the tag point itself ·
  **5 = arc 21 / W4, the first bump that is NOT a compatible tail** — `TagShowing`'s wire form
  changed, so a W1-shaped tag extension against a W4 host unmarshals wrongly; it fails loudly (the
  constructor `require`s reject it as an `IllegalArgumentException`) and the declaration keeps it
  from being reached · **6 = arc 22 / X1, the second break and the first with a FLOOR** —
  `IExtensionStore` was *replaced*, which also breaks the old-extension/new-host direction (a v5
  pad calling transaction code 1 lands on a different method), so the three **store-taking** points
  (scratch pad, document editor, tag manager) are accepted only at
  `MIN_API_VERSION_FOR_STORE` 6 and above; the stateless points keep floor 1. **Consequence, live
  on the Nomad between X1 and each extension's phase: that extension's doors were GONE** —
  deliberate, and the X1 walk verified it (the pad redeclared 6 in X2 and its button came back; the
  tag manager in X3, so every tag door and the search merge came back; the editor service in X4, so
  the Document button came back — its text importer and document exporter services stay at 3,
  since neither takes a store) · **7 = arc 23 / Y1, the calendar point — the first PER-ACTION
  floor**: `ACTION_CALENDAR` is listed only at `MIN_API_VERSION_FOR_CALENDAR`, every other point's
  declared floor is unchanged, so no consequence like X1's — a point born at 7 was never reachable
  at any lower number to begin with. · **8 = arc 25 / V1, the cloud point** — the calendar's shape again:
  a compatible addition, floored at 8, no existing door moved · **9 = arc 31 / HV1 + HV4, two
  compatible tails and NO floor moved**: `ExporterInfo.delivery` (the exporter descriptor's third
  tail — `MIN_API_VERSION_FOR_DELIVERY` 9 says which services the host *reads* it from) and two
  `ICalendar` methods appended after `end()` (`render` + `outgoingTarget`, gated by the **method**
  floor `MIN_API_VERSION_FOR_CALENDAR_RENDER` 9 — `MIN_API_VERSIONS` untouched, a calendar declaring
  7 still binds; `CloudContractTest` had pinned the cloud floor to *the current* version and was
  re-pinned to 8). Not a ninth point. · **10 = arc 35 / HA1, one compatible tail and NO floor
  moved**: `ICalendar.advanceOutgoing()` appended after `outgoingTarget` (method floor
  `MIN_API_VERSION_FOR_CALENDAR_DAY_SEND` 10 — a Day's whole-page Send parks both halves and the
  host drains them in turn; only `:ext-calendar` redeclares) · **11 = arc 37 / B0, the BIBLE
  point** — compatible addition floored at 11, no floor moved: `ACTION_BIBLE` is listed only at
  `MIN_API_VERSION_FOR_BIBLE`, every other point's declared floor is unchanged; only `:ext-bible`
  declares 11. · **12 = arc 38 / R1, two compatible tails and NO floor moved**: `IBible.resolve` +
  `IBible.beginAt` appended after `end()`, gated by the **method** floor
  `MIN_API_VERSION_FOR_BIBLE_REFERENCE` 12 — `MIN_API_VERSIONS` untouched,
  `MIN_API_VERSION_FOR_BIBLE` still 11 and a reader declaring only 11 still binds for the plain
  door. Not a tenth point; only `:ext-bible` declares 12. · **13 = B9 "Send"**:
  `IBible.takeOutgoingReference`, method floor `MIN_API_VERSION_FOR_BIBLE_SEND` 13. · **14 = arc
  39 "Lookup"**: `IDocumentHost.openReference`, method floor
  `DocumentContract.MIN_API_VERSION_FOR_DOCUMENT_LOOKUP` 14 (`:ext-document` declares 14). ·
  **15 = arc 40 "Verses"**: `IBible.passageText`, method floor `MIN_API_VERSION_FOR_BIBLE_TEXT`
  15 (`:ext-bible` declares 15). · **16 = arc 42 "Notes"**: seven `IBible` tails (six
  store-taking/bind-per-call pushes into the reader's own notes index, one held-bind read), method
  floor `MIN_API_VERSION_FOR_BIBLE_NOTES` 16 (`:ext-bible` declares 16). · **17 = arc 43 / K2
  (2026-09-15), the SKETCH point** — the calendar's/Bible's shape: a compatible addition floored at
  17 (`SketchContract.MIN_API_VERSION_FOR_SKETCH`, a birth floor), no existing floor moved, only
  `:ext-sketch` declares 17. This is the **tenth** point, not an eleventh. · **18 = arc 43 / K5b
  (2026-09-15), five `ISketchHost` tails** — `insertPage`/`deletePage`/`pageContent`/`undoPage`/
  `redoPage` appended after `readInkChunk` on the **host-side** stub, gated by the method floor
  `MIN_API_VERSION_FOR_SKETCH_PAGES` 18; `MIN_API_VERSION_FOR_SKETCH` stays 17 untouched, so a
  17-only sketch extension still gets the plain view-only face. Not an eleventh point; only
  `:ext-sketch` declares 18. · **19 = arc 44 / T2 (2026-09-17), two `ISketchHost` tails** —
  `toolSettings()`/`putToolSettings(in SketchToolSettings)` appended after `redoPage` (codes
  12–13) on the host-side stub, behind the method floor `MIN_API_VERSION_FOR_SKETCH_TOOLS` 19;
  `MIN_API_VERSION_FOR_SKETCH` 17 and `MIN_API_VERSION_FOR_SKETCH_PAGES` 18 untouched. Not an
  eleventh point; only `:ext-sketch` declares 19. · **20 = arc 45 "Ink" / G2 (2026-09-17) — the
  THIRD non-tail break** (after arc 21 / W4's `TagShowing` and arc 22 / X1's store) **and the
  first to move a single point's ACTION floor after its birth** (X1 moved three at once, to 6,
  when floors were introduced): a sketch is two rasters now (graphite + ink,
  `extensions/sketch/INK_PLAN.md`), so `ISketchHost.readSketchChunk`/`saveSketchChunk` (codes 3–4)
  take a raster layer **in place** and the image guard becomes WebP (`ImageHeader`, `PngHeader`
  gone) — neither side can read the other's parcel, so this is not a tail a floor can sit below.
  `SketchContract.MIN_API_VERSION_FOR_SKETCH` moves **17 → 20**, granted by the user's decision 4
  (no legacy, no shipped library on the old shape); `MIN_API_VERSION_FOR_SKETCH_PAGES` 18 and
  `MIN_API_VERSION_FOR_SKETCH_TOOLS` 19 stay where they were minted, both now inert below the
  action floor. `:ext-sketch` redeclares 20 as of arc 45 / G3 (2026-09-18) — its manifest still
  said 19 between G2 and G3, a build not installed. Not an eleventh point. · **21 = arc 46
  "Palette" / Q1 (2026-09-19)**: a compatible tail on a **parcel**, not a method —
  `SketchToolSettings` grows a fourth `int`, `penShade` (the gel pen's own remembered shade), read
  with the exhausted-parcel rule; `SketchContract.MIN_API_VERSION_FOR_SKETCH_PEN_SHADE` 21 names
  it, `MIN_API_VERSIONS` untouched, the action floor still 20, only `:ext-sketch` redeclares. No
  action floor moved at any of the other eight bumps
  from 12 on; the pin
  lives in
  `ExtensionContractTest`, which **must be run** (`:extension-api:testDebugUnitTest`) at every
  bump — it went red across three bumps unnoticed once.
  Meta-data is **per service**.
- **The Scratch Pad is not ours to change from here** (arc 11, `docs/scratchpad.md`). It is the
  `:ext-scratchpad` APK: its own process, its own g-paper surface, its own undo stack, and it
  **writes nothing to disk itself** — its pages live in the host store, lent for the showing and
  revoked with the unbind (**rows since arc 22 / X2**: `ScratchSchema.V1` = `page` / `stroke` /
  `state`, every SQL string in `ScratchSql`, writes as an idempotent op log split into ≤ 4 MiB
  `exec` batches, reads planned by `LENGTH(blob)` into `BETWEEN` ranges — **no page ceiling**, and a
  page row is never `INSERT OR REPLACE`d because REPLACE's delete cascades its strokes). It opens
  **no `.soil`**, and the notebook behind it is **not sealed** —
  what the notebook gives up is the EPD pipeline, not its data. Both transfers are **copies** that
  cross only through the held service (never the Intent, never a file), carry **no ids**, and keep
  coordinates 1:1. The pad's tools are the notebook's, fixed: a pad that lassoed differently one tap
  from the notebook would read as a bug, so a change to the notebook's ink feel is a change to both.
  Touching either paper surface's handoff means re-reading the ordering rule in
  `docs/extensions.md` § the tier-2 recipe first; a failure there is fixed in **g-paper**. The
  calendar (arc 23, `docs/calendar.md`) shares the pad's ink/store helpers through `:ext-ink` and
  its screen chrome (`FloatingSelectionBar`) through `:sn-screen` — the same rule applies there.

- **Paper is identified by a TOKEN, not a kind** (arc 13, `docs/templates.md`). A `.soil` `template`
  row's `text` is `""` (blank — no row at all), `LINED`/`DOTTED`/`GRID` byte-for-byte as every build
  in this family has written them, or `IMG#<8 hex>` for an imported picture — whose digest covers the
  **fit mode** as well as the bytes. Reuse is `token + page size`, **reuse before mint**, and nothing
  ever soft-deletes a template row. The library's **sentinels are not rows** (Blank, the reserved
  **Default** folder, the three built-in papers): hardcoded ids, nothing seeded, nothing repairable
  — so any prune against "alive rows" must exempt them by name. The browser **never opens a `.soil`**
  and never returns pixels; it returns a `TemplatePick` and the caller does the read and the write.
  Paper that will not **draw** is not paper that is **absent** and neither is blank: a failed render
  leaves the page exactly as it was. **No adjustable generators** — built, shown, abandoned
  (arc 13 / G2); import is how a user gets different paper, and re-raising it needs a fresh user
  decision.
- **Data model is Paper's, byte-for-byte format-compatible** — `notesprout.db` `objects`
  table (user_version 1) + `Garden/<uuid>.soil` universal `notebook` table v1 +
  `notebook_meta`, StrokeCodec format B, encrypt-by-default global key, SQLCipher stock
  defaults. Any schema/codec/crypto change must keep a Paper-created file openable and
  vice versa. References: `apps/notesprout_paper/docs/data.md` + `docs/crypto.md`.
- **`data/SoilFile.kt` is the only path constructor** — `extensionStoreFile` included
  (arc-11 / J2 amendment: the function exists now, and it is the only way to derive an
  extension store's `Garden/<pkg>.db`) **and `extensionStoreFiles` too** (arc 21 / W5: the one
  path authority also owns the one listing of that directory — the library's structure is still
  index-only, but a store has no index row to be listed from, so the backup run reads the
  file system, and only there) **and `rekeyLeftovers` since arc 34 / L18** (the re-key recovery's
  `Garden/` walk moved out of `SoilRekey` for the same reason: one `listFiles()` of that directory,
  or the beginning of a second answer to what is in it; the naming rule stays pure in
  `RekeyNames.leftoverOriginals`).
- **Every SQLCipher open routes through `crypto/SoilCrypto`.** Passphrases never logged,
  never in Intent extras, never in the index. Never delete a DB on corruption.
- **Encryption standing rules (arc 26, `docs/encryption.md`):** every `.soil` open resolves through
  `SoilDatabase.resolve` → `KeyResolver` and opens with the `Resolved` it got — `KeySession.get()` is
  the GLOBAL passphrase only; a key change on disk is `SoilRekey` or nothing (`ScopeChange` for one
  notebook, `GlobalRotation` for the library — never `PRAGMA rekey`, never a hand copy); every
  raw-key read on an open path is `KeyMaterial.peekVerified`, never `peekOrLoad`; a prompt other
  than the notebook screen's never reads `PassphraseCache`; `IndexRepository.setEncryptionState` is
  the only scope writer and never bumps `updatedAt` (it clears the backup stamps instead);
  `RawKeyDerivation` stays on the platform PBKDF2 with `KeyOpener.warm` serialized; a silent reader
  never prompts and a locked notebook answers null to it; the failure of a wrong key is reported,
  never repaired by deletion.
- **`IndexGuard.ready(this)` first thing in every index-touching `onCreate`**;
  `BootstrapActivity` is the only index opener and is `noHistory`.
- **g-paper 0.1.40 (the pin since 2026-09-18 — Phase 27, the white lead's LIGHT_GRAY preview,
  ratta only; **0.1.43 since 2026-09-19 (Phase 28 "Graphite on the panel" + maintenance + Phase 29 — the Supernote pencil, gel pen and rubber all go direct on `/dev/ebc`, the on-screen page dithered, the bake is the live layer; SN maintenance re-pins, not arcs)**; 0.1.39 since arc 45 / G1, 2026-09-17, re-pinned into `:ext-sketch` at G3,
  2026-09-18 — Phase 26 "Two rasters: graphite and ink":** `RasterLayer { GRAPHITE, INK }`;
  `CanvasPaperView` holds both, each allocated lazily on its own first mark; the committed-layer
  draw flattens them with one `PorterDuff.Mode.DARKEN` blit (`renderToBitmap()` is the flatten);
  `eraseRasterAlong` names the graphite raster only — ink is never read, allocated or announced by
  an erase; layered `get`/`load`/`copy`/`swap`/`readPageRaster` and layered
  `onRasterWillChange`/`onRasterChanged`, with the un-layered forms kept as interface defaults
  meaning `GRAPHITE` so a legacy (pre-0.1.39) host or listener still compiles and runs, **silent
  for ink** on purpose (its `readPageRaster(rect)` reads graphite, so forwarding an ink change
  would hand it the wrong before-image). `gpaper-core` only — `RattaPaperView` needs no override, and
  `OnyxPaperView`'s two existing overrides simply moved to the layered forms; walked on the Nomad (pen over pencil then
  rub — graphite lifts, ink stays). **0.1.38 (since 2026-09-17, post-arc-44 maintenance — Phase 25
  "Graphite on paper":**
  the 96 px pencil lead baked as a comb of bars across the mark (the travel direction's wobble
  times a 48 px lever arm); `GraphiteGrain` loosens rim flecks along the stroke, streaks the skate
  along it, and mottles by a page-space tooth field. `gpaper-core` only, no API change, walked on
  the Manta. **0.1.37 since arc 44 / T3 the same day — Phase 24 "The lead goes wide on Ratta":**
  `RattaEmr.EMR_MAX` 1200 → 9600, so a lead up to 96 px previews at the width it bakes; the old
  ceiling had never been reached by anything and its "the daemon lags" was never a measurement.
  **0.1.36 since T1 the same day — Phase 23 "The pencil previews its tone on Ratta":**
  `RattaInkMap.pencilPreviewFor`, the pencil's own ladder — 0–2 BLACK / 3–6 DARK_GRAY / 7–14 GRAY,
  thresholds settled by the hand, never LIGHT_GRAY for a grey (**0.1.40 / Phase 27, 2026-09-18:
  a white lead alone reaches LIGHT_GRAY**, luma > 246.5); `PENCIL_PREVIEW_GREY` gone. Before them,
  **0.1.35 (since the Manta sketch walk, 2026-09-17 — Phase 22 "The pencil bakes upright
  on Ratta":** a leaned `PENCIL` baked 10–15× wider than the firmware's live line, which cannot
  widen with tilt; the `bakeTilt` seam bakes it at tilt 0 on Ratta. Walked on Manta + Nomad; see
  `extensions/sketch/docs/sketch.md` § Traps.) Before it, **0.1.34 (arc 43 / K8, 2026-09-16 —
  Phase 21 "The door closes":** `RattaTuning`
  removed; the four Nomad-measured values it held as a "measurement door, not host API" — cadence
  16 ms, the PENCIL EMR hairline floor 120, the pencil baked at a constant pressure 0.5 under a
  DARK_GRAY needle preview — are now plain constants in `gpaper-ratta`, Ratta-only, no behaviour
  change. **0.1.33 since K6, 2026-09-15 — Phase 20 "A mark says where it landed":**
  a composited raster mark announces itself as runs of ≤ 256 px span (`RasterDirty.along`), not
  one bounding box, and `loadPageRaster` is silent like `swapPageRaster` — the sketch face's
  `loadingRaster` guard went with it. **0.1.32 since K1 the same day — Phase 19 "Ratta and the
  raster page":** the raster-erase cadence seam, `RattaEmr` with the PENCIL hairline EMR floor 120,
  the pencil baked at a constant pressure 0.5 under a DARK_GRAY needle preview, cadence 16 ms, all
  measured on the Nomad. 0.1.31 since K0 the same
  day; 0.1.28 since arc 29 / LE1, 2026-09-06 — `Tool.LASSO_ERASER`; 0.1.27 = arc 28's transform
  mode, 0.1.23 before). 0.1.29 = a raster undo swap, 0.1.30 = the rubbing eraser, 0.1.31 = the
  alpha-3 fix — all three are Paintsprout Onyx raster work; **zero `gpaper-ratta` lines changed
  between 0.1.28 and 0.1.31**. `gpaper-core` + `gpaper-ratta` only** (mavenLocal). No `gpaper-onyx`,
  no BOOX repo, no jetifier, no jniLibs pickFirsts, no `tools:replace` label. Engine gaps
  are fixed in `~/git/g-paper` (bump version, `publishToMavenLocal`, re-pin) — never
  worked around in the host.
- **Host does only the documented host responsibilities**
  (`~/git/g-paper/docs/host-responsibilities.md`): page swap = `clearForContentSwap` →
  `setPageSize`/`setTemplate` → `loadStrokes`; undo/redo via `addStrokes`/`removeStrokes`;
  chrome via `setExclusionRects`; lifecycle `resumeDrawing`/`releaseForHandoff`/`release`.
- **An attached Ratta paper view keeps the pen claimed whatever its visibility** (arc 24 / Z3's
  on-device finding, `:ext-calendar`'s `NoteSurface`) — `View.INVISIBLE` alone does not release it,
  so hiding one behind another control needs `INVISIBLE` **and** a whole-view exclusion rect. Also
  why the app never hides the IME itself on Ratta: the person dismisses the keyboard with its own
  key, never a call from the app.
- **Frame-silence rule:** never present an app frame while `paper.isPenActive` — route chrome
  text/updates through a pen-idle gate. The recorded exceptions (each one chrome frame at a
  deliberate act or a boundary, never under live ink) are **ledgered with their justifications
  in `docs/notebook.md` § frame-silence** — any new exception needs the same written
  justification there. Remember `isPenActive` counts **hover** — never idle-gate a hide/show
  that must answer a deliberate act.
- **Toast vs. dialog:** a toast only confirms something that already happened; anything
  explaining why a tap *didn't* work is a problem dialog. On e-ink a missed toast reads as
  "broken". **Three recorded exceptions** use `Dialogs.confirm` (post-arc-17 toast review,
  2026-08-30 — a successful result that still shouldn't ride a toast): export-done (the screen
  finishes under the toast — `finish()` runs on the dialog's dismiss), backup-done (the counts
  are the screen's whole point), import-done (names a non-current destination folder). The
  frequent/reversible/in-context toasts (copy/cut/paste, clipboard clear, template
  import/export, backup-exclude) stayed toasts on purpose — do not promote them.
- Portrait-locked everywhere · one layout per screen · no colour in chrome (ink is fixed
  black — P1 removed the tool panels) · TopGuard is 0 on Ratta — chrome sits flush at the top
  edge · notebook writes go through the session's single serial `SoilWriter` · undo/redo
  replays through the store then reloads the page (DB is the source of truth) · no file
  over ~800 lines without a written reason.
- **Supernote swallows `adb shell input text`** — scripted device tests tap the on-screen
  keyboard or avoid text entry. EPD live ink is invisible to screencap; only committed
  strokes screenshot-verify.
- **Bottom bars are pager-only, with three recorded exceptions, each its own grant** — `btnBible`
  (arc 37 / B0, decision 3), the first non-pager control ever placed on a bottom bar, on both the
  library's and the notebook's; `btnNotes` (arc 42), a second such exception on the Bible reader's
  own bottom bar; and `btnSketch` (arc 43, decision 9), a third, on the notebook's bottom strip left
  of Bible. Each is a deliberate, one-time grant, not a precedent: a future door still goes on a top
  bar or a long-press sheet unless the user grants another one explicitly.

## Build & install

See `RATTA_PLAN.md` appendix and the root `device-build-install` skill. From `apps/notesprout_sn/`:
debug `./gradlew assembleDebug` → `adb -s SN078D10012852 install -r` (host + every `ext-*` APK).
Release is unsigned + hand-signed with the debug keystore; the release ext packages are re-enabled
on the Nomad. JVM tests: `./gradlew test` (**1844** in `:app`, **3631** across the modules —
including `extensions/bible`'s 155 and `extensions/sketch`'s **97** — at arc 45's G5).
Java 17 comes from `org.gradle.java.home` (Temurin-17).
