# Heading Levels (H1 / H2 / H3) — Implementation Plan

> Goal: extend the existing single-style heading object to support **H1, H2, H3**.
> Each session is implemented by a **sonnet subagent**, one at a time. After each session the
> subagent does a **clean build + install on G10 (`34E517F9`)**, then the user runs the listed
> test steps. Fixes (if any) go through another sonnet subagent → clean build → install. Once the
> user reports pass, update the status table here and commit (no push).

Branch: `seed`. Primary module: `apps/notesprout_android`.

---

## Architecture Decisions (apply across all sessions)

1. **Authoritative level field.** Add `level: Int = 1` to **`HeadingObject`** (DB-serialized) and
   **`HeadingStroke`** (render-time). `level` is the single source of truth for heading type —
   it works even for **unrecognized** headings (`recognizedText == null`, rendered as strokes),
   which have no markdown prefix to encode the level.

2. **Stored text keeps the markdown prefix.** When `recognizedText != null` it is stored **with**
   the matching prefix: `# ` for H1, `## ` for H2, `### ` for H3. This makes canvas rendering
   "just work": `drawHeadingText` already pipes `recognizedText` through `TextObjectRenderer` →
   `MarkdownParser`/`MarkdownRenderer`, which already scale headings (h1 ×2.0, h2 ×1.75, h3 ×1.5
   — see `MarkdownRenderer.headingSizeMultiplier`). `TextObjectRenderer.measure(...)` also parses
   markdown, so the bounding box auto-sizes per level. **`level` and the stored prefix must always
   agree** — every write path that sets one sets the other.

3. **Backward compatibility.** `level` has default `1`. Old `.soil` rows (no `level` key) decode as
   H1, and their `# ` prefix already matches. No migration needed (`kotlinx.serialization` defaults).

4. **Prefix helpers** (add to `HeadingObject.kt` companion, reuse everywhere — never hardcode `"# "`):
   - `fun headingPrefix(level: Int): String` → `"#".repeat(level.coerceIn(1,3)) + " "`
   - `fun stripHeadingPrefix(text: String): String` → removes a leading run of 1–3 `#` + following spaces
   - `fun levelFromText(text: String?): Int` → counts leading `#` (1–3), default 1
   - `fun applyLevel(text: String?, level: Int): String?` → `text?.let { headingPrefix(level) + stripHeadingPrefix(it) }`

5. **Page-name rule (per user decision): top-left-most H1, else topmost heading of any level, else `Page {N}`.**
   "Top-left-most" = smallest `boundingBox.top`, ties by smallest `left`. Applies in
   `data/PageHeadingNames.kt` (page index grid + link picker). The old TOC "one heading per page"
   rule in `TocRepository` is replaced wholesale by the hierarchy in S5.

---

## Sessions Overview & Status

| # | Session | Status |
|---|---------|--------|
| S1 | Data model: `level` field + plumbing + prefix helpers | ✅ Done |
| S2 | Heading edit dialog: hash-free editing (strip/restore, level-aware) | ✅ Done |
| S3 | Selection menu: make-heading submenu (strokes → H1/H2/H3) + icons | ✅ Done |
| S4 | Selection menu: selected-heading submenu (change type + un-heading) + undo | ⬜ Not started |
| S5 | Page-name rule update + TOC hierarchy data (TocRepository) | ⬜ Not started |
| S6 | TOC collapsible cascading UI (TocDialog) | ⬜ Not started |
| S7 | Wrap-up: docs, polish, dead-code prune, full-device QA | ⬜ Not started |

Status legend: ⬜ Not started · 🟡 In progress · 🧪 Awaiting test · ✅ Done

---

## Session 1 — Data model: `level` field, plumbing, prefix helpers

**Outcome:** `level` exists end-to-end and is preserved across move/copy/paste/erase/undo. No visible
behavior change — all headings are still created/edited as H1, render identically.

### Files & changes

1. **`app/src/main/kotlin/com/notesprout/android/data/HeadingObject.kt`**
   - Add `val level: Int = 1` to `HeadingObject` (after `recognizedText`).
   - Add the 4 prefix helpers from Architecture Decision #4 to the `companion object`.

2. **`app/src/main/kotlin/com/notesprout/android/data/HeadingStroke.kt`**
   - Add `val level: Int = 1` to `HeadingStroke` (after `recognizedText`).

3. **`app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt`** — `loadHeadingsFromDb` (~line 3104):
   build `HeadingStroke(..., level = headingObj.level)`.

4. **Propagate `level` at every `HeadingStroke(...)` and `HeadingObject(...)` construction that copies
   an existing heading.** Grep both: `grep -rn "HeadingStroke(" app/src/main/kotlin` (~27 sites) and
   `grep -rn "HeadingObject(" app/src/main/kotlin` (~7 sites). For each site that copies from an
   existing heading/render model, add `level = <source>.level`. Critical sites (do not miss — these
   carry undo/redo correctness):
   - Move: `onStrokesMoved` heading branch (~1426) + `HeadingObject(heading.strokes, heading.recognizedText)` (~1429).
   - Lasso-eraser capture (~1264) and scribble-erase capture (~1329); single-heading erase capture (~954).
   - Copy (`performLassoCopy` ~4044) and paste (`pasteFromClipboard` ~4079, and DB insert `HeadingObject` ~4156).
   - Link wrap/unwrap heading copies (~4291, ~4396, ~4939, ~7382) and `data/LinkRender.kt` (~44).
   - `NotebookExporter.kt` (~307), `OnyxNotebookView.kt` (~1128, ~1225), `GenericNotebookView.kt` (~307, ~394).
   - `TocRepository.kt` (~32) — pass `level = headingObject.level` (will be reworked in S5, but keep correct now).
   - All undo/redo `HeadingStroke(...)`/`HeadingObject(...)` rebuilds in NotebookActivity (the `is UndoRedoAction.*` blocks ~6025, 6233, 6480, 6580, 6655, 7671, 7741, 7800, 7928, 7960, 8027 etc.). For these, level comes from the relevant undo action field — see next item.

5. **Undo actions carry level** — `app/src/main/kotlin/com/notesprout/android/history/UndoRedoAction.kt`:
   - `HeadingCreated`: add `val level: Int = 1`.
   - `HeadingRemoved`: add `val level: Int = 1`.
   - (Lasso `LassoCut`/`LassoDeleted`/`LassoErased`/`StrokesMoved`/`LassoPasted` already carry full
     `HeadingStroke` objects, so level rides along automatically once #2 + #4 are done — verify, no field add needed.)
   - Update the `push(...)` call sites: `createHeadingFromStrokes` pushes `HeadingCreated(... level = level)`;
     `removeHeading` pushes `HeadingRemoved(... level = heading.level)`.
   - Update the undo/redo handlers that rebuild a heading from a `HeadingCreated`/`HeadingRemoved`
     action (~6580, ~6655, ~7928, ~7960) to pass `level = action.level`, and the DB re-insert of the
     heading row to use `HeadingObject(..., level = action.level)`.

6. **`createHeadingFromStrokes`** (~4467): add a `level: Int = 1` parameter (used in S3). Replace the
   hardcoded `"# $recognizedText"` with `headingPrefix(level) + recognizedText`. Build
   `HeadingObject(strokes, storedText, level)` and `HeadingStroke(... level = level)`. The existing
   single caller (`btnMakeHeading`, ~1531) passes no level → defaults to 1 (unchanged behavior).

### Verify / build
- Clean build (`./gradlew clean assembleDebug`) + install on G10.

### Test steps (S1)
1. Open a notebook. Lasso some strokes → tap Make-Heading → confirm a heading is created and renders
   the same as before (H1 text or grey stroke background).
2. Move the heading (lasso drag) → undo → redo. Heading stays intact.
3. Copy the heading, paste on another page. Pasted heading renders correctly.
4. Lasso-erase the heading → undo. Heading restored.
5. Un-heading (existing button) → undo → redo. Works as before.
6. Close & reopen the notebook — heading persists and renders unchanged.

---

## Session 2 — Heading edit dialog: hash-free editing

**Outcome:** Editing a heading's text shows **only the words** (no `#`), and Save re-applies the
correct prefix for the heading's current level.

### Files & changes

1. **`app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt`** — `showHeadingTextEditDialog` (~5051):
   - Prefill with `HeadingObject.stripHeadingPrefix(heading.recognizedText ?: "")` (no hashes shown).
   - Capture the heading's level (`heading.level`) for use on Save.

2. **`updateHeadingText`** (~5088):
   - Change signature/behavior so the stored text is `HeadingObject.applyLevel(newText, heading.level)`
     (i.e. `headingPrefix(level) + newText`). Measure the box using the **prefixed** text so sizing
     stays correct for the level. Persist `HeadingObject(..., level = heading.level)`.
   - `HeadingTextEdited` undo action stores `previousText`/`newText` — keep both **prefixed** (so the
     existing undo/redo handlers at ~6683 / ~8061, which write `recognizedText = targetText` directly,
     remain correct). I.e. the dialog strips for display only; everything persisted/undo-tracked stays
     prefixed.

3. Confirm `dialog_edit_heading_text.xml` needs no change (single-line text field is fine).

### Test steps (S2)
1. Create an H1 heading with recognized text (e.g. "Chapter One").
2. Tap it (single selection) to edit → the field shows **`Chapter One`** with **no `#`**.
3. Change to "Chapter Two", Save → renders correctly, still H1 size.
4. Reopen the edit dialog → shows `Chapter Two` (no hashes).
5. Undo → text reverts to "Chapter One". Redo → "Chapter Two".
6. Page name in the page index grid still reads "Chapter Two" (no leading `#`).

---

## Session 3 — Selection menu: make-heading submenu (strokes → H1 / H2 / H3)

**Outcome:** With a pure-stroke selection, the heading button uses the Tabler **`heading`** icon;
tapping it opens a small submenu of **H1 / H2 / H3**; tapping one converts the strokes into a heading
of that level.

### Icons (download + convert to Android vector drawables)
Fetch Tabler **outline** SVGs and hand-translate to vector drawables matching the existing
`res/drawable/ic_h_1.xml` style (24dp, `viewport 24`, `strokeColor="@color/inkBlack"`,
`strokeWidth="1.5"`, round cap/join, transparent fill). `ic_h_1.xml` already exists.
- `https://raw.githubusercontent.com/tabler/tabler-icons/main/icons/outline/heading.svg` → `res/drawable/ic_heading.xml`
- `https://raw.githubusercontent.com/tabler/tabler-icons/main/icons/outline/h-2.svg` → `res/drawable/ic_h_2.xml`
- `https://raw.githubusercontent.com/tabler/tabler-icons/main/icons/outline/h-3.svg` → `res/drawable/ic_h_3.xml`

### Files & changes

1. **`res/layout/activity_notebook.xml`** —
   - Change `btnMakeHeading` `android:src` to `@drawable/ic_heading`.
   - Add a new **heading-type submenu** `LinearLayout` `@+id/headingTypeSubmenu` (sibling of
     `floatingSelectionToolbar`, same `shape_bordered` styling, `visibility="gone"`), containing 4
     `AppCompatImageButton`s reused for both S3 and S4:
     `@+id/btnHeadingH1` (`ic_h_1`), `@+id/btnHeadingH2` (`ic_h_2`), `@+id/btnHeadingH3` (`ic_h_3`),
     `@+id/btnHeadingUnheading` (`ic_heading_off`, `visibility="gone"` by default — shown only in S4).

2. **`NotebookActivity.kt`** —
   - `btnMakeHeading.setOnClickListener` (~1522): instead of converting directly, **toggle**
     `headingTypeSubmenu` visible, positioned under the selection box (reuse the
     `updateFloatingSelectionToolbar` placement math; factor a `positionPopover(view, selectionBox)`
     helper). Hide the un-heading button (`btnHeadingUnheading.visibility = GONE`) in this mode.
   - Wire `btnHeadingH1/H2/H3` → launch `createHeadingFromStrokes(selectedStrokes, box, level = 1|2|3)`
     then hide the submenu. (`createHeadingFromStrokes` already takes `level` from S1.)
   - Dismiss `headingTypeSubmenu` whenever the selection changes / floating toolbar hides / a canvas
     tap occurs (add to existing hide paths: `hideFloatingSelectionToolbar`, lasso clear).

3. **Bounding box for H2/H3:** confirmed automatic — `createHeadingFromStrokes` measures the
   prefixed `storedText`, and `measure` parses markdown so H2/H3 sizes differ correctly. No extra work.

### Test steps (S3)
1. Lasso strokes that ML Kit can recognize → tap the **heading** icon → submenu (H1/H2/H3) appears.
2. Tap **H2** → strokes become an H2 heading; text renders smaller than H1 but larger than body.
3. Repeat with **H3** on another selection → renders smaller still.
4. Tap **H1** → same size as before this feature.
5. Lasso strokes ML Kit can't recognize → tap heading → H2 → grey-background stroke heading is created
   (no text). (Level is stored even though it's not visually obvious yet — TOC proves it in S5/S6.)
6. Undo each conversion → strokes return. Redo → heading returns at the same level.
7. Reopen notebook → all three heading sizes persist.

---

## Session 4 — Selection menu: selected-heading submenu (change type + un-heading) + undo

**Outcome:** Selecting a single heading shows the **`heading`** icon. Tapping it opens a submenu with
**H1 / H2 / H3** (the heading's current level shown with a border highlight) plus a 4th **un-heading**
button. Tapping a different level changes the heading's type (full undo/redo). Tapping un-heading
converts back to strokes (existing behavior).

### Files & changes

1. **`res/layout/activity_notebook.xml`** — add `@+id/btnHeadingMenu` (`ic_heading`) to
   `floatingSelectionToolbar` next to `btnUnheading`. (Keep `btnUnheading` defined but it will be
   superseded by the submenu's un-heading button — simplest is to **hide** `btnUnheading` and surface
   un-heading inside the submenu only. Decide during impl; prefer the single-submenu approach for a
   consistent UX with S3.)
   - Add a 1dp inkBlack border selected-state drawable `res/drawable/bg_heading_type_selected.xml`
     (rectangle, `stroke 1dp @color/inkBlack`, `corners 4dp`, transparent fill) for the current-level
     highlight.

2. **`updateFloatingSelectionToolbar`** (~5164):
   - When `selectionIsSingleHeading`: show `btnHeadingMenu` (and hide the old `btnMakeHeading`/
     `btnUnheading` for this case). Keep `headingDivider` logic.

3. **`NotebookActivity.kt`** —
   - `btnHeadingMenu.setOnClickListener`: open `headingTypeSubmenu` positioned under the selection;
     set `btnHeadingUnheading.visibility = VISIBLE`; apply `bg_heading_type_selected` background to the
     button matching `selectedHeading.level` and clear it on the others.
   - `btnHeadingH1/H2/H3` in this mode → call new `changeHeadingLevel(heading, newLevel)` (skip if
     `newLevel == heading.level`), then hide submenu.
   - `btnHeadingUnheading` → existing `removeHeading(heading)`, hide submenu.
   - The H1/H2/H3 buttons are shared between S3 (convert) and S4 (change). Disambiguate by current
     selection state at tap time (`selectionIsPureStrokes` → convert; `selectionIsSingleHeading` →
     change). Store the active mode in a field when opening the submenu to avoid races.

4. **New `changeHeadingLevel(heading: HeadingStroke, newLevel: Int)`** (model on `updateHeadingText`):
   - Compute `newText = HeadingObject.applyLevel(heading.recognizedText, newLevel)` (null stays null).
   - Recompute bounding box: if `newText != null`, `measure(newText, ...)` (markdown → correct size),
     anchored top-left like `updateHeadingText`; if null (stroke heading), keep the existing box.
   - Persist `HeadingObject(heading.strokes, newText, newLevel)` + new bbox via `updateHeadingData`.
   - Update in-memory heading (`level`, `recognizedText`, `boundingBox`), rebuild bitmap, refresh
     selection box + floating toolbar.
   - Push new undo action (below).

5. **`UndoRedoAction.kt`** — add:
   ```
   data class HeadingLevelChanged(
       val headingId: String,
       val pageId: String,
       val previousLevel: Int,
       val newLevel: Int,
       val previousText: String?,   // prefixed
       val newText: String?,        // prefixed
       @Serializable(with = RectFSerializer::class) val previousBox: RectF,
       @Serializable(with = RectFSerializer::class) val newBox: RectF,
   ) : UndoRedoAction()
   ```
   Add handlers mirroring `HeadingTextEdited` at all three dispatch tiers in `NotebookActivity.kt`:
   - `pageIdFor(action)` map (~5865).
   - Same-page in-memory branch (~8061-style): apply `level`+`text`+`box` for the chosen direction.
   - Cross-page DB branch (~6683-style): update the DB row's `data` (`HeadingObject` with target
     level/text) and `boundingBox`, then rebuild.
   - `isUndo` → previous*, else new*.

### Test steps (S4)
1. Select a single H1 heading → tap the **heading** icon → submenu shows H1/H2/H3 + un-heading, with
   **H1 bordered/highlighted**.
2. Tap **H3** → heading shrinks to H3 size; reopen submenu → **H3** now highlighted.
3. Tap **H2** → becomes H2 size; highlight follows.
4. Undo → back to H3. Undo → back to H1. Redo twice → returns to H2.
5. Change level on a **stroke-only** (unrecognized) heading → no visible size change, but reopening the
   submenu shows the new level highlighted; undo/redo restores prior level.
6. Tap **un-heading** in the submenu → converts back to strokes (same as today). Undo restores the
   heading at its last level.
7. Reopen notebook → levels persist.

---

## Session 5 — Page-name rule update + TOC hierarchy data

**Outcome:** Page names follow the new rule; `TocRepository` produces a **hierarchical** H1→H2→H3
structure (data only — the dialog UI lands in S6, so S5 keeps the dialog compiling against a
temporary flattened adapter or ships together — see note).

> **Build note:** `TocRepository.buildTocEntries()` feeds `TocDialog` (NotebookActivity ~848). Changing
> its return type breaks `TocDialog`. To keep each session shippable, S5 introduces the new hierarchy
> model **and** a minimal `TocDialog` adaptation that renders the tree *flat* (all nodes, indented by
> level, no collapse yet); S6 then adds collapse/expand chrome. Alternatively S5+S6 may be done as one
> larger session if the subagent prefers — note the choice in status.

### Files & changes

1. **`data/PageHeadingNames.kt`** — `topHeadingNamesByPageId`:
   - Parse `level` from the `HeadingObject` JSON per heading.
   - New selection: per page, prefer the top-left-most heading with `level == 1`; if none, the
     top-left-most heading of any level. Strip the prefix with `HeadingObject.stripHeadingPrefix`
     (handles `#`/`##`/`###`), not `removePrefix("# ")`.
   - Update the KDoc to describe the new rule.

2. **`toc/TocEntry.kt`** → replace/augment with a hierarchy model, e.g.:
   ```
   data class TocNode(
       val pageNumber: Int,
       val pageIndex: Int,
       val pageId: String,
       val level: Int,            // 1, 2, or 3
       val title: String,         // prefix-stripped; "" if unrecognized
       val heading: HeadingStroke,
       val children: MutableList<TocNode> = mutableListOf(),
   )
   ```
   (Keep `TocEntry` if S6 wants a flattened view-model; or derive flattening in the dialog.)

3. **`toc/TocRepository.kt`** — rewrite `buildTocEntries()` → `buildTocTree(): List<TocNode>`:
   - Gather all non-deleted headings, resolve each to its page via layer→page map (existing logic).
   - **Document order:** sort by `pageIndex` asc, then `boundingBox.top` asc, then `left` asc.
   - Single pass building the tree:
     - `level == 1` → new top-level node; set `currentH1 = node`, `currentH2 = null`.
     - `level == 2` → if `currentH1 != null` append as its child + set `currentH2`; else **skip**.
     - `level == 3` → if `currentH2 != null` append as its child; else **skip**.
   - `currentH1`/`currentH2` persist across pages ("most recent" may be from a previous page).
   - `title` = `stripHeadingPrefix(recognizedText)`; for unrecognized (null text) use a thumbnail in
     the UI (S6) — keep `heading` on the node so the dialog can render the thumbnail as today.

4. **`NotebookActivity.kt`** (~848) — call `buildTocTree()`; pass to `TocDialog`.

5. **`TocDialog.kt`** — minimal change for S5: accept `List<TocNode>`, flatten (pre-order) for display,
   indent rows by level (e.g. `paddingStart += level * 16dp`). No collapse yet.

### Test steps (S5)
1. Page with H1 "Intro", then H2 "Background", then H3 "Detail" → open TOC → all three appear,
   indented H1 > H2 > H3.
2. Page-2 H2 with **no** preceding H1 anywhere → that H2 is **absent** from the TOC.
3. Page-3 H3 with a preceding H2 (from page 2 which itself follows a page-1 H1) → H3 appears nested.
4. Page with two H1s → **both** show in the TOC.
5. Page index grid / link picker: a page whose first heading is H1 "Title" shows **Title**; a page with
   only an H2 at top (no H1) shows that H2's text; a page with H1 below an H2 shows the **H1** text.
6. Tapping any TOC row still navigates to its page.

---

## Session 6 — TOC collapsible cascading UI

**Outcome:** The TOC is a collapsible cascading menu. Default **collapsed** (only H1 rows visible).
A **+** before a row reveals its children; **−** collapses. State is **in-memory only** (not
persisted). Tapping a row's text/thumbnail navigates to its page.

### Files & changes

1. **`res/layout/item_toc_entry.xml`** (or a new `item_toc_node.xml`): add a leading
   **expand/collapse toggle** `AppCompatImageButton` (`ic_plus` / `ic_minus` — download Tabler
   `plus`/`minus` outline if not present; check `res/drawable` first). Toggle is `INVISIBLE` (keeps
   alignment) for nodes with no children. Indent content by `level`.

2. **`toc/TocDialog.kt`** —
   - Hold `List<TocNode>` + an in-memory `expanded: MutableSet<String>` (node id = heading id),
     **empty by default** (collapsed).
   - Build the **visible list** = pre-order walk that descends into a node's children only when the
     node is in `expanded`. Recompute on every toggle.
   - Pagination: keep the measured `itemsPerPage` logic but paginate over the **visible** list;
     recompute current page bounds after expand/collapse (clamp).
   - Toggle button: add/remove node id in `expanded`, re-render current page; do **not** dismiss.
   - Row text/thumbnail click: dismiss + `onPageSelected(node.pageId)` (unchanged).
   - Active-page highlight: highlight the visible node(s) on the current page (keep existing
     `bg_toc_active_entry`); when collapsed, highlight the ancestor H1 row if the active page's heading
     is hidden.

3. Confirm thumbnails (`HeadingThumbnailView`) still render for unrecognized nodes; recognized nodes
   use the stripped `title`.

### Test steps (S6)
1. Open TOC → only **H1** rows show; rows with children have a **+**.
2. Tap **+** on an H1 with children → H2 children appear with their own **+** where applicable; the
   glyph becomes **−**.
3. Expand an H2 → its H3 children appear. Tap **−** on the H1 → everything under it collapses.
4. Rows with no children show no toggle (and no stray +/−).
5. Tap a row's text → navigates to that page and closes the TOC.
6. Close & reopen the TOC → it's **collapsed again** (state not persisted).
7. Pagination: a long expanded tree paginates; expanding/collapsing updates the page count and the
   first/prev/next/last buttons behave.
8. Active-page highlight: open TOC on a page that has an H3 → the relevant row (or its visible H1
   ancestor when collapsed) is highlighted.

---

## Session 7 — Wrap-up: docs, polish, dead-code prune, full-device QA

**Outcome:** The feature is finished, documented, and clean. No new behavior — this session tightens
everything S1–S6 introduced and confirms it across the device fleet.

### Files & changes

1. **Docs** (bring fully current — these were touched incrementally; this is the final reconciliation):
   - `docs/content-objects.md` — Heading Objects section: `level` field, prefix-stays-stored rule,
     the 4 `HeadingObject` prefix helpers, hash-free edit dialog, the convert submenu (strokes →
     H1/H2/H3), `changeHeadingLevel` + `HeadingLevelChanged` undo, and the H1/H2/H3 size multipliers.
   - `docs/mainactivity-and-recents.md` — TOC hierarchy (H1→H2→H3, cross-page "most recent" parenting,
     orphan-skip rules), collapsible UI (default collapsed, in-memory state), and the updated
     page-name rule.
   - `docs/data-architecture.md` — note `HeadingObject.level` in the heading schema description.
   - Confirm the **two synced page-name sites** note in `docs/content-objects.md` reflects that
     `TocRepository` no longer carries the page-name rule (it's now hierarchy), and `PageHeadingNames`
     owns the rule for the page index grid + link picker.

2. **Dead-code / consistency prune:**
   - Remove any now-unused `btnMakeHeading` / `btnUnheading` paths if S3/S4 superseded them with the
     submenu (and their layout entries + string/contentDescription resources).
   - Grep for remaining hardcoded `"# "` / `removePrefix("# ")` and route them through the
     `HeadingObject` helpers (`headingPrefix` / `stripHeadingPrefix`).
   - Confirm `TocEntry` is either still used or deleted (S5 may have replaced it with `TocNode`).
   - Verify no orphaned drawables; every downloaded icon (`ic_heading`, `ic_h_2`, `ic_h_3`, any
     `ic_plus`/`ic_minus`) is referenced.
   - Add any new findings to `CODE_REVIEW_PRUNING.md` rather than leaving TODOs.

3. **Polish:**
   - Submenu placement: verify both the make-heading (S3) and change-level (S4) submenus position
     correctly for all four toolbar placements (TOP/BOTTOM/LEFT/RIGHT) and don't clip off-screen.
   - Highlight drawable (`bg_heading_type_selected`) renders as a visible 1dp inkBlack border on
     e-ink (per the memory note: `borderGray` is invisible on BOOX — must be inkBlack).
   - Confirm headings render identically on the **Generic** engine path (GenericNotebookView) and in
     **PDF export** (`NotebookExporter.renderPage`) at all three levels.

### Test steps (S7)
1. **Full lifecycle on G10:** create H1/H2/H3 (recognized + unrecognized), edit text, change levels
   both directions, un-heading, move/copy/paste/erase, undo/redo each — all correct.
2. **TOC** end-to-end: hierarchy, collapse/expand, orphan-skip, multi-H1, navigation, page names.
3. **PDF export** a page with all three heading levels → sizes render correctly in the PDF.
4. **Cross-device QA** — practical default for this feature: **G10 (`34E517F9`)** and
   **Palma2 Pro (P2P `287d2364`)**. Optionally widen to the rest of Tier-1 (Go 10.3 Gen 2 `b7a46e13`,
   Note Max `6325773d`, Go 7 `17845014`) and a Generic-engine device (Wacom Movink Pad 11
   `5HL21V5007384`, non-Onyx render path) if time allows.
5. Reopen notebooks created in earlier sessions — everything persists and renders.

---

## Per-session ritual (every session)

1. Subagent implements the session's changes.
2. **Clean build + install on G10:**
   ```sh
   cd apps/notesprout_android && ./gradlew clean assembleDebug
   adb -s 34E517F9 install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. User runs the test steps and reports back.
4. If fixes needed → sonnet subagent fixes → clean build → reinstall on G10 → user re-tests.
5. On pass: flip the session's row to ✅ in the status table, update any affected `docs/` notes
   (esp. `docs/content-objects.md` heading section + `docs/mainactivity-and-recents.md` TOC), and
   **commit** (no push). Use the `🌱` Growth-Log commit prefix convention.

## Docs to update as sessions land
- `docs/content-objects.md` — Heading Objects section (level field, prefix rule, edit dialog,
  conversion submenu, change-level + undo).
- `docs/mainactivity-and-recents.md` — TOC hierarchy + collapse behavior + page-name rule.
- `docs/data-architecture.md` — `HeadingObject.level` field if heading schema is documented there.
