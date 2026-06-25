# Sticky Note Object — Implementation Plan

> A new first-class content object. A **sticky note** sits on a notebook page (or scratch pad page)
> as a small **icon** (Tabler `sticker-2`). Tapping the icon opens a **content window** — a popup on
> large screens, full-screen on small devices — where the user draws/edits with pen, eraser, lasso,
> and copy/cut/paste (shared clipboard). The note's content does **not** render on the page; only the
> icon does. The icon is a full lasso citizen (move/copy/cut/paste/delete/erase) and participates in
> the notebook's undo/redo exactly like every other object.
>
> Sticky notes **cannot** be converted to/from links, headings, etc. They are their own type.
>
> **Execution model:** each session below is run **one at a time by Sonnet at medium effort**. Steps
> and file lists are written to be self-contained. At the **end of every session**: clean build →
> install on **G102** (`b7a46e13`) → present device test steps → wait for the user's pass/fail report
> → on pass, update this file's **Status Tracker** and **commit** (do **not** push).

---

## Decisions (locked — from planning Q&A)

| # | Decision | Choice |
|---|---|---|
| D1 | Content window pages | **Single page** per sticky note (no page nav/add/delete/swipe) |
| D2 | Tools inside the window | **Pen / eraser / lasso + paste** (shared clipboard, any object type). No text/line insert buttons inside. No nested sticky notes. |
| D3 | Undo for in-window edits | **Yes** — closing the window after changes pushes **one** `StickyNoteContentEdited` action to the notebook undo stack (before/after content snapshot). No live undo button inside the window (matches scratch pad). |
| D4 | Toolbar button | **Visible by default** in the notebook toolbar (and scratch pad toolbar); still hideable via Customize Toolbar. |

---

## Architecture

### Storage model — embed content in the object's `data` (the link precedent)

A sticky note is a `type = "sticky_note"` row in the **`.soil` `notebook` table** (notebook pages) or
the **`scratchpad` table** (scratch pad pages). It follows the **`LinkObject` pattern**: the object's
**embedded content travels inside its `data` JSON** (`StickyNoteObject`), so copy/cut/paste, page
copy, full-notebook export, and clipboard persistence all carry it automatically with zero special
plumbing.

Two independent coordinate spaces:

- **Row `boundingBox`** = the **icon's** fixed-size rectangle **on the page** (where the note sits).
  This is what lasso hit-tests/moves.
- **Embedded content** (`strokes`/`headings`/`textObjects`/`lines`) lives in the **content window's
  own pixel space**, independent of the icon's page position. `contentWidth`/`contentHeight` record
  the window-canvas px size the content was authored at.

> Lines are stored density-independently as `EmbeddedLine` (reuse the existing carrier from
> `LinkObject.kt`) so a note round-trips across devices of differing density. Strokes/headings/text
> reuse the existing `@Serializable` render models, identical to `LinkObject`.

`StickyNoteObject` carries **no links and no nested sticky notes** (only strokes/headings/text/lines),
to keep follow/recursion semantics out of scope.

### Content window — in-memory editor, transfer-singleton round-trip (no cross-Activity `.soil`)

The content window is a **separate Activity** (`StickyNoteEditorActivity`). It must **never open the
`.soil`** — `NotebookActivity` already holds that connection open (it is only paused), and the project
forbids cross-Activity `.soil` access (WAL/sidecar risk; see `TemplateBrowserActivity` note in
`docs/drawing-engine.md`). Instead, content is handed in and out through an in-memory singleton,
exactly like `ScratchpadTransfer`:

```
NotebookActivity.openStickyNote(note):
    StickyNoteEditorTransfer.input  = note's embedded content (+ contentW/H)
    launch StickyNoteEditorActivity (editorLauncher)

StickyNoteEditorActivity:
    load input into the drawing view's in-memory lists; edit fully in memory
    (pen adds; eraser/lasso operate on in-memory lists; paste pulls from NotesproutClipboard)
    on close: StickyNoteEditorTransfer.output = current in-memory content (+ contentW/H); finish()

NotebookActivity editorLauncher callback:
    read StickyNoteEditorTransfer.output; if changed → persist the new data JSON to the .soil row,
    update the in-memory StickyNoteRender, rebuild the page bitmap (icon unchanged),
    push StickyNoteContentEdited(before, after). Clear the transfer.
```

The editor is **fully in-memory** — it owns no DB/repository. This is simpler than the scratch pad
(which persists to its own table). The host (`NotebookActivity` for notebook pages,
`ScratchpadActivity` for scratch pad pages) owns persistence. The editor is host-agnostic.

> **Process-death caveat (deferred):** because the editor holds content only in memory until close,
> an OS kill mid-edit loses unsaved strokes. Acceptable for v1 (short sessions, same process as the
> paused host). Listed in Deferred Items.

### On-page rendering

Both drawing engines gain a `stickyNotes: List<StickyNoteRender>` in-memory list and render **only the
icon** (the `ic_sticker_2` VectorDrawable via `AppCompatResources.getDrawable(...).setBounds(box).draw(canvas)`,
the same mechanism `drawLinkChrome` uses for the link icon). Icon size = `STICKY_NOTE_ICON_SIZE_DP`
(start at **36dp** — "large enough to match the visual size of text objects"; tunable in S1). Drawn in
`redrawCanvas` / `buildRenderBitmap` / snapshot-composite **after links, before top-level strokes**.

### Coordinate space on reopen / cross-device (v1)

v1 renders embedded content **as-authored**, clipped to the window canvas. Proportional rescale when
the window size differs (rotation, cross-device paste) is a **Deferred Item**.

### Tabler `sticker-2` icon (download once in S1)

Source: `https://raw.githubusercontent.com/tabler/tabler-icons/main/icons/outline/sticker-2.svg`
Convert to `res/drawable/ic_sticker_2.xml` matching the project's Tabler VectorDrawable style
(`width/height=24dp`, `viewportWidth/Height=24`, `strokeColor=@color/inkBlack`, `strokeWidth=1.5`,
`strokeLineCap/Join=round`, transparent fill). Two paths (verbatim `d` data):

```
M6 4h12a2 2 0 0 1 2 2v7h-5a2 2 0 0 0 -2 2v5h-7a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2
M20 13v.172a2 2 0 0 1 -.586 1.414l-4.828 4.828a2 2 0 0 1 -1.414 .586h-.172
```

The **same** `ic_sticker_2.xml` is used for the toolbar button (44dp standard) and the on-page icon.

### New type constant & key

- `TYPE_STICKY_NOTE = "sticky_note"` (in `data/NotebookObject.kt`, alongside `TYPE_LINK`).
- Toolbar registry key `"stickyNote"` — **append-only**, added last in `SPECS` (key-stability rule).

---

## Status Tracker

| Session | Title | Status | Commit |
|---|---|---|---|
| S1 | Data model, icon, on-page render, insert + persist + insert-undo | ☑ Complete (tested on G102 + committed) | 84e0762 |
| S2 | Lasso lifecycle: select / move / delete / lasso-erase / scribble / erase-all (notebook) | ☑ Complete (tested on G102 + committed) | e87edaf |
| S3 | Content window editor + transfer + tap-to-open + create-flow + content-edit undo | ☑ Complete (tested on G102 + committed) | — |
| S4 | Copy / cut / paste parity (lasso clipboard + page copy/paste + cross-notebook) | ☐ Not started | — |
| S5 | Scratch pad parity (insert / render / tap-open / lasso on scratch pad pages) | ☐ Not started | — |
| S6 | PDF export — interactive footnote/endnote (icon → GoTo endnote page → back-link) | ☐ Not started | — |
| S7 | Export/import + encryption verification, docs, BACKLOG, polish, retire plan | ☐ Not started | — |

Legend: ☐ Not started · ◐ In progress · ☑ Complete (tested on G102 + committed)

---

## Per-session protocol (every session)

1. Implement the session's steps.
2. **Clean build** debug:
   ```sh
   cd apps/notesprout_android && ./gradlew clean assembleDebug
   ```
3. **Install on G102** (`b7a46e13`) — user says devices are ready, so skip `adb devices`:
   ```sh
   adb -s b7a46e13 install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Present the session's **device test steps** to the user. **Stop and wait** for the pass/fail report.
5. On **pass**: tick the Status Tracker row (◐→☑), fill the commit hash, then **commit (no push)**:
   - Commit on the `sprout` branch.
   - Message style (project nomenclature — new feature = "New Branch"):
     `feat: sticky note SN — <short summary>` … ending with
     `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
6. On **fail**: fix, rebuild/reinstall, re-test. Do not commit until the user confirms pass.

---

## Session 1 — Data model, icon, on-page render, insert + persist + insert-undo

**Goal:** A new toolbar button inserts a sticky-note **icon** at page center. The icon persists across
page navigation and notebook reopen. Insert is undoable/redoable. (No content window yet — tapping the
icon does nothing this session.)

### Files to create
- `app/src/main/res/drawable/ic_sticker_2.xml` — Tabler `sticker-2` VectorDrawable (see Architecture).
- `app/src/main/kotlin/com/notesprout/android/data/StickyNoteObject.kt`
  ```kotlin
  @Serializable
  data class StickyNoteObject(
      val strokes: List<LiveStroke> = emptyList(),
      val headings: List<HeadingStroke> = emptyList(),
      val textObjects: List<TextRender> = emptyList(),
      val lines: List<EmbeddedLine> = emptyList(),
      val contentWidth: Float = 0f,
      val contentHeight: Float = 0f,
  ) { fun toJson(); companion object { fun fromJson(...) } }   // mirror LinkObject.kt
  ```
- `app/src/main/kotlin/com/notesprout/android/data/StickyNoteRender.kt`
  ```kotlin
  @Serializable
  data class StickyNoteRender(
      val id: String,
      @Serializable(with = RectFSerializer::class) val boundingBox: RectF,  // ICON box on page
      val strokes: List<LiveStroke> = emptyList(),
      val headings: List<HeadingStroke> = emptyList(),
      val textObjects: List<TextRender> = emptyList(),
      val lines: List<LineRender> = emptyList(),     // px render-time
      val contentWidth: Float = 0f,
      val contentHeight: Float = 0f,
  )
  // fun StickyNoteRender.translate(dx, dy, newId = id): StickyNoteRender  — offsets ONLY boundingBox
  //   (embedded content lives in its own space; do NOT offset it). Fresh content list copies.
  // fun StickyNoteRender.toStickyNoteObject(density): StickyNoteObject — lines → EmbeddedLine
  ```
  > Model on `LinkRender.kt` / `LinkRender.translate` / `LinkRender.toLinkObject`, but `translate`
  > moves only the icon box.

### Files to edit
- `data/NotebookObject.kt` — add `const val TYPE_STICKY_NOTE = "sticky_note"`.
- `data/NotebookDao.kt`
  - Add `@Query("SELECT * FROM notebook WHERE parentId = :layerId AND type = 'sticky_note' AND deletedAt IS NULL ORDER BY \"order\" ASC") suspend fun getStickyNotesForLayer(layerId: String): List<NotebookObject>`.
  - Include `'sticky_note'` rows in the `getMaxContentUpdatedAt` staleness query (mirror how `text`/`line`/`link` are included).
- `notebook/NotebookConstants.kt` — add `const val STICKY_NOTE_ICON_SIZE_DP = 36f`.
- `notebook/NotebookView.kt` — add to the interface (default no-op/empty like the link methods):
  - `fun loadStickyNotes(stickyNotes: List<StickyNoteRender>) {}`
  - `fun getStickyNotes(): List<StickyNoteRender> = emptyList()`
  - `fun compositeStickyNotes(bitmap: Bitmap) {}`
  - add `stickyNotes: List<StickyNoteRender>? = null` as the **last** param of `buildRenderBitmap(...)`.
- `notebook/OnyxNotebookView.kt` **and** `notebook/GenericNotebookView.kt` (mirror exactly):
  - Add `private var stickyNotes: List<StickyNoteRender> = emptyList()`.
  - Implement `loadStickyNotes` (set + `redrawCanvas`/invalidate), `getStickyNotes`, `compositeStickyNotes`.
  - Add `private fun drawStickyNoteObject(canvas, note)` — draw `ic_sticker_2` via
    `AppCompatResources.getDrawable(context, R.drawable.ic_sticker_2)?.apply { setBounds(note.boundingBox …); draw(canvas) }` (icon box = the row bbox).
  - Call `drawStickyNoteObject` in **`redrawCanvas`** and in **`buildRenderBitmap`** render order
    **after the links loop, before the top-level strokes loop**; add it to the snapshot-composite paths
    (`compositeStickyNotes`) the same way `compositeLinks` is wired.
  - Wire the new `buildRenderBitmap(... stickyNotes ...)` param: when non-null use it, else fall back to
    the stored field (same pattern as `textObjects`/`links`).
- `app/src/main/res/layout/activity_notebook.xml` — add the toolbar button after `btnInsertLines`
  (GROUP_TOOLS), id `btnInsertStickyNote`, `style="@style/Widget.Notesprout.ToolbarButton"`,
  `android:src="@drawable/ic_sticker_2"`, `contentDescription="Insert sticky note"`,
  `layout_marginStart="4dp"`.
- `notebook/ToolbarButtonRegistry.kt` — append to `SPECS` (last entry, append-only):
  `ButtonSpec("stickyNote", R.id.btnInsertStickyNote, R.drawable.ic_sticker_2, "Insert Sticky Note", GROUP_TOOLS)`.
  (`DEFAULT_ORDER` derives from `SPECS`, so it is visible by default; the
  `ToolbarPreferencesManager` append-missing-keys migration adds it for existing users.)
- `NotebookActivity.kt`
  - **PageLoadResult**: add `stickyNotes: List<StickyNoteRender>` field.
  - Add `loadStickyNotesFromDb(db, layerId)` (mirror `loadLinksFromDb`: query `getStickyNotesForLayer`,
    parse bbox + `StickyNoteObject.fromJson`, map `EmbeddedLine`→`LineRender`).
  - `loadCurrentPage(db)` — populate `stickyNotes`.
  - `displayPage(result)` — `drawingView.loadStickyNotes(result.stickyNotes)`; in the `usedSnapshot`
    branch call `drawingView.compositeStickyNotes(bitmap)` alongside the other composites.
  - Wire `binding.btnInsertStickyNote.setOnClickListener { insertStickyNote() }`.
  - Add `insertStickyNote()`:
    1. Compute a square icon box (`STICKY_NOTE_ICON_SIZE_DP * density`) centered on the page
       (`drawingView.asView().width/height / 2`), clamped to page bounds.
    2. Insert a `type = "sticky_note"` row: empty `StickyNoteObject().toJson()`, the icon bbox JSON.
       `invalidatePageSnapshot(db, pageId)`. (Mirror `insertTextObject`'s DB-write shape.)
    3. Append a `StickyNoteRender` (empty content) to `drawingView.getStickyNotes()` →
       `loadStickyNotes(...)`; rebuild bitmap off-thread → `loadStrokesWithBitmap`.
    4. Push `UndoRedoAction.StickyNoteInserted(noteId, pageId, layerId, render)`; `updateUndoRedoButtons()`.
    > This session: do **not** launch the window and do **not** auto-select the icon (selection comes in
    > S2; window + create-flow in S3).
- `history/UndoRedoAction.kt` — add:
  ```kotlin
  @Serializable data class StickyNoteInserted(
      val noteId: String, val pageId: String, val layerId: String, val note: StickyNoteRender,
  ) : UndoRedoAction()
  ```
- `NotebookActivity.kt` undo/redo dispatch — handle `StickyNoteInserted` in all relevant tiers
  (same-page in-memory + DB, cross-page two-phase) mirroring `TextInserted`/`LinesInserted`:
  undo soft-deletes the row + drops it from the in-memory list + rebuilds; redo restores it. After any
  sticky-note undo/redo, re-read via `loadStickyNotesFromDb` + `loadStickyNotes` (mirror the links
  "re-sync after undo/redo" rule).

### G102 test steps (S1)
1. Open a notebook. The toolbar shows a new sticker icon button (may be in overflow `⋯`).
2. Tap it → a sticky-note icon appears at the center of the page, ~ one text-line tall.
3. Flip to the next page and back → icon still there, same spot. (Tune `STICKY_NOTE_ICON_SIZE_DP` if it
   looks too big/small vs a line of text.)
4. Close the notebook and reopen → icon persists.
5. Insert a second icon (also center, overlapping first is fine). Undo twice → both disappear newest-first.
   Redo twice → both reappear.
6. Existing features (pen, text, lines, links, lasso) unaffected.

---

## Session 2 — Lasso lifecycle on the notebook page (no clipboard yet)

**Goal:** The icon is a first-class lasso object for **select, move, delete, lasso-erase,
scribble-erase, and erase-all (page clear)** — all with undo/redo. (Copy/cut/paste is S4.)

### Files to edit
- `notebook/NotebookView.kt` — add `var onStickyNoteErased: ((note: StickyNoteRender) -> Unit)?`
  (default null), mirroring `onLinkErased`. Extend `onStrokesMoved` and `onScribbleEraseComplete`
  signatures with `…StickyNotes` lists (mirror how links were threaded). Update both engine
  implementations to pass sticky notes through these callbacks (hardware/lasso eraser + scribble +
  drag-move), reusing the link code paths as the template.
- `OnyxNotebookView.kt` / `GenericNotebookView.kt`
  - Lasso-eraser hit test & scribble hit test: include `stickyNotes` (icon bbox center-point / bbox
    penetration test, same as headings/links). On hit, remove from in-memory list + fire
    `onStickyNoteErased` / include in `onScribbleEraseComplete` payload.
  - Drag-move: translate selected sticky-note icon boxes; include them in `onStrokesMoved` output.
  - Hardware/barrel eraser over an icon box → `onStickyNoteErased`.
- `NotebookActivity.kt`
  - **Lasso selection** (`onLassoComplete` hit test): add a `stickyNotes` loop (region-intersect the
    icon bbox, mirror the links loop). Selected ids drive the floating toolbar.
  - **`onStrokesMoved`**: persist moved icon boxes (`updateHeadingData`/equivalent bbox+data update),
    include `originalStickyNotes`/`movedStickyNotes` in the `StrokesMoved` undo push.
  - **`performLassoDelete`**: include selected sticky-note ids; soft-delete; `LassoDeleted` carries them.
  - **Lasso-eraser** (`onLassoEraseComplete`) + **`onScribbleEraseComplete`** + **`onStickyNoteErased`**:
    soft-delete row, drop from in-memory list, rebuild, push the matching undo action with sticky-note
    payload (mirror `onLinkErased`).
  - **`eraseAllCurrentPage`** (page clear / `btnEraseAll`): wipe in-memory sticky notes
    (`drawingView.loadStickyNotes(emptyList())`) + DB rows; `PageEraseAll` records them for restore.
  - After every sticky-note-affecting undo/redo, re-sync via `loadStickyNotesFromDb` + `loadStickyNotes`.
  - **S1 follow-up:** now make `insertStickyNote()` **enter lasso mode and select the new icon** (so it
    is immediately movable) — mirror the tail of `insertTextObject`.
- `history/UndoRedoAction.kt` — extend with optional sticky-note fields + handlers:
  - `StrokesMoved`: `originalStickyNotes`/`movedStickyNotes: List<StickyNoteRender> = emptyList()`.
  - `LassoDeleted`, `LassoErased`, `ScribbleErased`: `stickyNoteIds: List<String> = emptyList()` +
    `stickyNotes: List<StickyNoteRender> = emptyList()`.
  - `PageEraseAll`: `stickyNoteIds: List<String> = emptyList()` (restore via `restoreChildrenDeletedSince`/`restoreById`).
  - Wire each handler in `NotebookActivity` exactly like the link equivalents.

### G102 test steps (S2)
1. Insert a sticky note → it is **auto-selected** (dashed box + floating toolbar) and draggable.
2. Drag the icon elsewhere; release → it stays. Undo → returns to prior spot; redo → moves again.
3. Lasso-select the icon (stylus) → Delete → gone. Undo → back. Redo → gone.
4. Lasso-eraser across the icon → gone (+undo restores). Scribble-erase across it → gone (+undo restores).
5. Erase-all on a page containing an icon → cleared. Undo → icon restored.
6. Move/delete survive page flip and reopen; undo/redo stay consistent.

---

## Session 3 — Content window editor + tap-to-open + create-flow + content-edit undo

**Goal:** The core UX. Inserting a note immediately opens the editor for input; closing returns to the
page with the icon selected for placement. Tapping an existing icon (finger) reopens the editor with
its content. In-window edits are persisted and form one undoable action.

### Files to create
- `app/src/main/res/layout/activity_sticky_note_editor.xml` — clone `activity_scratchpad.xml` but:
  - **single page**: remove the chrome page arrows / indicator / add-page / delete-page; keep a simple
    title ("Sticky Note") + a Close affordance (tap-outside-to-close is inherited from the translucent
    theme; keep parity with scratch pad's tap-outside `finish()`).
  - toolbar: `btnStickyPen`, `btnStickyEraser`, `btnStickyLasso` only (no add/delete page, no
    Send-to-Notebook).
  - keep `drawingContainer` + `floatingSelectionToolbar` (Copy / Cut / Delete) identical to scratch pad.
- `app/src/main/kotlin/com/notesprout/android/StickyNoteEditorTransfer.kt`
  ```kotlin
  object StickyNoteEditorTransfer {
      data class Content(
          val strokes: List<LiveStroke>, val headings: List<HeadingStroke>,
          val textObjects: List<TextRender>, val lines: List<LineRender>,
          val contentWidth: Float, val contentHeight: Float,
      )
      var input: Content? = null    // host → editor
      var output: Content? = null   // editor → host (null = no change / cancelled-clean)
  }
  ```
- `app/src/main/kotlin/com/notesprout/android/StickyNoteEditorActivity.kt` — model on
  `ScratchpadActivity`, but **fully in-memory, no repository/DB**:
  - Pick engine via `isBooxDevice()`; large-screen 75%×75% popup / small-screen full-screen (reuse the
    `is_large_screen` logic and `Theme.Notesprout.Scratchpad`).
  - On load: read `StickyNoteEditorTransfer.input`, push its lists into the view
    (`loadHeadings/loadTextObjects/loadLineObjects/loadStrokes…`), build bitmap. Record canvas size into
    `contentWidth/Height` if input's were 0.
  - Pen: strokes accumulate in the view (no DB save — `onPenLifted` is a no-op or just marks dirty).
  - Eraser / lasso-eraser / scribble: operate on the view's **in-memory lists** (filter + rebuild);
    `onStickyNoteErased` is irrelevant here (notes can't nest). **No** `repository.softDelete*` calls.
  - Lasso copy/cut/delete + tap-paste: copy/cut write `NotesproutClipboard` (shared); delete/cut filter
    the in-memory lists; paste pulls from `NotesproutClipboard` into the in-memory lists. (Reuse scratch
    pad's lasso handlers but swap every `repository.*` DB call for pure in-memory list mutation.)
  - On close (`finish()`/tap-outside/`onPause`): read `getStrokes/getHeadings/getTextObjects/getLineObjects`
    into `StickyNoteEditorTransfer.output` with current `contentWidth/Height`.
- `app/src/main/AndroidManifest.xml` — register:
  ```xml
  <activity android:name=".StickyNoteEditorActivity" android:exported="false"
            android:theme="@style/Theme.Notesprout.Scratchpad"
            android:configChanges="orientation|keyboardHidden|screenSize|smallestScreenSize|screenLayout|density"
            android:windowSoftInputMode="stateHidden" />
  ```

### Files to edit
- `NotebookActivity.kt`
  - Add `editorLauncher = registerForActivityResult(...)` (mirror `scratchpadLauncher`). On result:
    read `StickyNoteEditorTransfer.output`; if non-null **and** differs from the note's prior content,
    write the new `StickyNoteObject(...).toJson()` to the row (DAO data update + `invalidatePageSnapshot`),
    update the in-memory `StickyNoteRender`, rebuild bitmap, push
    `UndoRedoAction.StickyNoteContentEdited(noteId, pageId, before, after)`. Clear the transfer
    (`input = null; output = null`).
  - Add `openStickyNote(note: StickyNoteRender, initialCreate: Boolean)`:
    set `StickyNoteEditorTransfer.input` from `note` (lines → `LineRender` already), launch
    `editorLauncher`. Stash `pendingStickyNote = note` + `pendingStickyInitialCreate`.
  - **Create-flow:** `insertStickyNote()` (from S1/S2) now, after inserting the row, calls
    `openStickyNote(render, initialCreate = true)` **instead of** auto-selecting. On the launcher
    result for an `initialCreate` note: after persisting content, **enter lasso mode and select the
    icon** (so the user can immediately drag it to place). For a non-create open: do **not** change
    selection (just persist + rebuild) — "if they simply tap the icon… no need to select."
  - **Tap-to-open gesture:** add `stickyNoteAt(x, y): StickyNoteRender?` (topmost icon bbox containing
    the point). In the finger-tap handler (the same place `handleLinkFollowGesture` runs; gate with
    `isLinkFollowEnabled()` = not text-placement), **hit-test sticky notes first**; if hit, call
    `openStickyNote(note, initialCreate = false)` and consume; else fall through to link follow. Keep
    it finger-only and tap-only (reuse the link tap thresholds: single pointer, no move, ≤ long-press).
- `history/UndoRedoAction.kt` — add:
  ```kotlin
  @Serializable data class StickyNoteContentEdited(
      val noteId: String, val pageId: String,
      val before: StickyNoteRender, val after: StickyNoteRender,   // full content snapshots
  ) : UndoRedoAction()
  ```
  Handlers (all tiers): write `before`/`after` content JSON to the row, update the in-memory render,
  rebuild bitmap, re-sync `loadStickyNotesFromDb`. Icon box is unchanged between before/after.

### G102 test steps (S3)
1. Insert a sticky note → the **editor opens immediately** (popup on G102 large screen).
2. Draw a few strokes; tap outside (or Close) → back on the page; the icon is **selected** and draggable.
   Drag to a corner; release.
3. Tap the icon (finger) → editor reopens showing the strokes you drew. Add more; close.
4. Tap again → all content present. Tapping does **not** select the icon (no dashed box) this time.
5. Inside the editor: eraser removes a stroke; lasso-select + Delete; lasso-copy then tap-paste — all
   work; reopen confirms the result persisted.
6. **Undo** on the page after a content edit → the note's content reverts to the prior snapshot
   (reopen to verify); **Redo** re-applies. Insert-undo still removes the whole note.
7. Large vs small: (if a smaller device is handy later) editor is full-screen there — not required on G102.

---

## Session 4 — Copy / cut / paste parity

**Goal:** Sticky notes ride through the shared clipboard (lasso copy/cut/paste, cross-notebook),
persisted clipboard across restart, and page-level copy/paste — all with undo/redo.

### Files to edit
- `NotesproutClipboard.kt` — `ClipboardContent` += `stickyNotes: List<StickyNoteRender> = emptyList()`.
- `data/ClipboardPayload.kt` / `data/ClipboardMappers.kt` — add `TYPE_STICKY_NOTE` handling in
  `toPayload` (serialize each `StickyNoteRender`) and `toClipboardContent` (deserialize) and the
  empty-check guards. (`TYPE_STICKY_NOTE` already exists from S1.)
- `NotebookActivity.kt`
  - `performLassoCopy` / `performLassoCut`: collect selected sticky notes into `ClipboardContent`
    (`translate(0,0,newId=id)` for copy snapshot; mirror links). Cut also soft-deletes rows + updates
    in-memory list; `LassoCut` carries `stickyNoteIds`/`stickyNotes`.
  - `performLassoPaste`: paste sticky notes with **fresh UUIDs**, icon box translated by the tap offset
    (content unchanged); insert rows; add to in-memory list; select; `LassoPasted` carries them.
  - Page-level `copyCurrentPage` / `pasteCopiedPage` / `copyPageAfter(Raw)`: include `sticky_note` rows
    in the page copy (these copy "all object types" — ensure sticky_note is in the copied set).
  - Cross-notebook page move/copy: `sticky_note` rows travel with the page copy engine (verify the
    generic row-copy path includes them; no type-specific exclusion).
- `history/UndoRedoAction.kt` — `LassoCut`, `LassoPasted` (and `CopyPageAfter`/page-paste actions as
  applicable) get `stickyNoteIds`/`stickyNotes` fields; wire handlers in `NotebookActivity` like links.

### G102 test steps (S4)
1. Lasso-select a sticky note + some strokes → **Copy** → tap elsewhere to **paste**: a new icon (fresh)
   appears; original remains. Open the pasted icon → same content as the source.
2. **Cut** a sticky note → it vanishes + is on the clipboard → paste it back. Undo/redo the cut and paste.
3. Copy a page that contains a sticky note → paste page → the copy has its own working sticky note
   (open it to confirm content copied).
4. Copy a sticky note, **fully close and relaunch the app**, open a notebook, paste → it pastes
   (persisted clipboard). Open it → content intact.
5. (If two notebooks handy) copy in A, open B, paste → works cross-notebook.
6. Undo/redo across every operation above stays consistent.

---

## Session 5 — Scratch pad parity

**Goal:** Sticky notes work on **scratch pad pages** too: insert, render, tap-to-open the same editor,
and full lasso copy/cut/paste/delete/erase. (Scratch pad has no undo stack — consistent with all scratch
pad objects; no undo here.)

### Files to edit
- `data/ScratchpadRepository.kt` + its page-content model — load `sticky_note` rows into a
  `stickyNotes` list (mirror how links are loaded); `insertObjects(...)` writes sticky notes (fresh
  UUIDs); `softDeleteObjects` already operates by id (works for sticky notes). Ensure `loadPage`
  returns sticky notes and `setPageSize`/snapshot paths are unaffected.
- `data/index/ScratchpadDao.kt` — add `getStickyNotesForLayer` equivalent if the repo needs a typed
  query (mirror its text/link queries).
- `app/src/main/res/layout/activity_scratchpad.xml` — add `btnScratchStickyNote` to the toolbar
  (`ic_sticker_2`), placed with the other tools.
- `ScratchpadActivity.kt`
  - Wire `btnScratchStickyNote` → `insertStickyNote()` (scratch pad version): insert a `sticky_note`
    row at page center via `repository`, add `StickyNoteRender` to the view, rebuild, then
    `openStickyNote(render, initialCreate = true)`.
  - Add an `editorLauncher` + `openStickyNote(...)` (set `StickyNoteEditorTransfer.input`, launch).
    On result: persist edited content back to the scratchpad row (`ScratchpadDao.updateData`), update
    in-memory render, rebuild; on `initialCreate` select the icon, else leave unselected.
  - Add `stickyNoteAt(...)` + finger tap-to-open in `dispatchTouchEvent` (scratch pad lasso is
    stylus-only, so finger tap can open without conflict).
  - `onLassoComplete` hit test, `performLassoCopy/Cut/Delete`, `onStrokesMoved`,
    `onScribbleEraseComplete`, `performLassoPaste`, `selectInsertedObjects`: thread `stickyNotes`
    through, exactly as links are (the file already follows this shape — see lines handling links).
  - `onStickyNoteErased` wiring (soft-delete row + rebuild), mirroring `onLinkErased`.

### G102 test steps (S5)
1. Open the **scratch pad** (from MainActivity or from a notebook). New sticker button present.
2. Insert → editor opens → draw → close → icon selected, draggable. Drag it.
3. Tap the icon → reopens with content. Edit → close → persists across scratch-pad page nav and app
   restart.
4. Lasso copy/cut/paste/delete + lasso-erase + scribble-erase on the scratch pad icon all behave like
   notebook pages.
5. Clipboard is shared: copy a sticky note on the scratch pad, paste into a notebook page (and vice
   versa) → content intact.

---

## Session 6 — PDF export: interactive footnote/endnote (icon → endnote page → back-link)

**Goal:** A sticky note exports as a tappable **footnote**: the `sticker-2` icon renders in place on its
page, and tapping it **jumps to an appended "endnote" page** that shows the note's content rendered as an
image; the endnote page has a **back-link** to the source page. This is the most universally-supported
interactive PDF mechanism (internal `GoTo` link annotations) — works on e-ink readers, unlike PDF
text-comment popups (text-string only — can't hold ink) or FileAttachment annotations (poor e-ink
support).

> **Architectural constraint (why a post-process pass):** the page render path uses Android's native
> `android.graphics.pdf.PdfDocument`, which is **Canvas-only and cannot emit annotations or
> destinations**. `pdfbox-android` (already a dependency, used today only by `encryptPdfFile`) **can**.
> So: keep the existing Canvas render for page bitmaps + the on-page icon, then add a **pdfbox
> post-process pass** that appends endnote pages and wires `GoTo` link annotations. No new dependency.

### Part A — draw the on-page icon (Canvas render path)
- `NotebookExporter.kt` — `renderPageBitmap` / `renderPage`: load `dao.getStickyNotesForLayer(layer.id)`,
  parse each into a `StickyNoteRender`, and draw the `ic_sticker_2` icon at each icon `boundingBox`
  (after the links loop, before strokes — mirror the on-screen render order). Embedded **content is
  intentionally not drawn** on the page (hidden, exactly as on screen).
- Have `renderPageBitmap` (and the per-page export loops in `export` / `exportPagesPdf`) **collect**, per
  rendered PDF page, a list of `(pdfPageIndex, iconBox: RectF, note: StickyNoteRender)`. The export loop
  already tracks `pdfPageNumber`; accumulate these into an ordered `stickyExports` list to feed Part B.
  (Icon box is in page **pixels**, top-left origin — same space as the page bitmap, since `PageInfo` is
  built at `bitmap.width/height`.)

### Part B — pdfbox post-process: endnote pages + GoTo links
- New `private fun addStickyEndnotes(input: File, output: File, stickyExports: List<StickyExport>, context)`
  in `NotebookExporter.kt`:
  1. `PDDocument.load(input)`.
  2. For each `StickyExport` (in order), assign an endnote number **N** (1-based, document-wide):
     - **Render the note's content to a bitmap**: reuse a `renderPage`-style helper but for the embedded
       content lists (`note.strokes/headings/textObjects/lines`) at `contentWidth×contentHeight` px
       (fall back to a sensible default, e.g. the source page size, when 0). No template. White
       background. → PNG/JPEG bytes → `PDImageXObject` via `JPEGFactory`/`LosslessFactory`.
     - **Append an endnote page** sized to the content bitmap. Draw the image with a `PDPageContentStream`.
       Add a small caption ("Note N — from page P") and a **back-link region** (e.g. the caption rect).
     - **Source-page icon link:** add a `PDAnnotationLink` on the source `PDPage` (index `pdfPageIndex`)
       covering the icon's rect, with `PDActionGoTo` → `PDPageFitDestination` at the endnote page.
       **Coordinate flip:** PdfBox uses points, bottom-left origin; our boxes are pixels, top-left. Page
       height in points = `pdfPage.mediaBox.height`. `llx = box.left`, `lly = pageH - box.bottom`,
       `urx = box.right`, `ury = pageH - box.top` (1px ≈ 1pt because pages were built at bitmap px size).
     - **Back-link:** add a `PDAnnotationLink` on the endnote page (over the caption) → `PDActionGoTo` →
       `PDPageFitDestination` at the **source** page.
     - Make link borders invisible (`PDBorderStyleDictionary` width 0) — e-ink, no chrome.
  3. `doc.save(output)`; `doc.close()`.
- **Wire into both PDF paths** (`export` and `exportPagesPdf`):
  - Plaintext: write the `PdfDocument` to a temp file → if `stickyExports` non-empty, `addStickyEndnotes(tmp, out, …)` else move temp→out.
  - Encrypted: order is **annotate first, then encrypt** — `PdfDocument`→tmpA; `addStickyEndnotes(tmpA, tmpB)`; `encryptPdfFile(tmpB, out, password)`. Delete intermediates. (When no sticky notes, current behavior is unchanged.)
  - PNG export paths (`exportPagesPng`, `exportPage`) get the **on-page icon only** (Part A); endnotes/links are PDF-only — note this in the PNG branches.
- Guard: if a page has **no** sticky notes, emit no annotations (zero overhead, byte-identical to today).

### G102 test steps (S6)
1. Put 1–2 sticky notes (with distinct drawn content) on a page; export the notebook to PDF.
2. Open the PDF in the device reader: the **icon shows in place**; content is **not** on the page.
3. **Tap the icon** → it jumps to an **endnote page** showing that note's drawn content + a
   "Note N — from page P" caption.
4. **Tap the caption/back-link** on the endnote page → returns to the source page.
5. Two notes → two endnote pages, correct numbering, each links to the right content and back.
6. Export the **same** notebook **with a PDF password** → links + endnotes still work after unlocking.
7. A notebook with **no** sticky notes exports exactly as before (regression).

---

## Session 7 — Export/import + encryption verification, docs, BACKLOG, polish, retire plan

**Goal:** Confirm sticky notes survive full-notebook export/import and encryption with no special code,
write the documentation, file deferred items, do a final regression sweep, and retire this plan.

### Verify (expected: no code, or a one-line allow-list add)
- **Full-notebook export/import** (`NotebookPackager` / `NotebookImporter`): `sticky_note` rows copy raw
  with the `.soil` and content lives in `data`, so this should need **no code** — **verify** a notebook
  with sticky notes round-trips export → import with content intact. If any object-type allow-list
  exists, add `sticky_note`.
- **Encryption:** content lives in the `.soil`, encrypted with the notebook — **no special handling**.
  Verify on an encrypted notebook that content opens/edits correctly. (The existing cross-device
  unencrypted-clipboard warning already covers copy-out.)
- Tune `STICKY_NOTE_ICON_SIZE_DP` if testing across tiers suggests a better value.

### Docs
- New `docs/sticky-notes.md` — data model (embedded-in-`data`, two coordinate spaces), editor
  transfer-singleton model, on-page icon render, tap-to-open, lasso/undo parity, create-flow, scratch
  pad parity, **PDF footnote/endnote export (pdfbox post-process)**, encryption note, deferred items.
- `CLAUDE.md` — add a row to the Detailed Documentation table pointing at `docs/sticky-notes.md`.
- `BACKLOG.md` — add the Deferred Items below.
- Retire this plan file the way `SCRATCHPAD_PLAN.md` was retired (final commit once all sessions ☑).

### G102 test steps (S7)
1. Full-notebook **export** a notebook with sticky notes, then **import** it → open the imported
   notebook → sticky notes present, tap → content intact.
2. Encrypt a notebook containing a sticky note → reopen (unlock) → tap the icon → content opens/edits.
3. Final regression sweep: insert / move / copy / cut / paste / delete / erase-all / undo-redo across
   notebook and scratch pad once more; PDF export footnotes still work.

---

## Deferred Items (record in BACKLOG.md at S6)

- **Cross-size content scaling:** when the editor window size differs from the authored
  `contentWidth/Height` (rotation, cross-device paste), proportionally rescale embedded content instead
  of rendering as-authored + clipping.
- **In-editor autosave / process-death durability:** the editor holds content in memory until close;
  persist incrementally (or on `onPause`) so an OS kill mid-edit doesn't lose strokes.
- **Multi-page sticky notes** (D1 chose single page).
- **Native text/line insertion inside the editor** (D2 chose pen/eraser/lasso + paste).
- **Content affordance on the icon** (e.g. a "has content" mark / mini-preview) — currently one static icon.
- **Sticky-note content in search / TOC** — content is hidden and excluded from ML Kit search and the
  page-name/TOC rules (intentional).
- **Live undo inside the editor** (D3: only one before/after action per window session).
- **Endnote pagination / fit:** S6 renders each note's content onto a single endnote page sized to the
  content; content larger than one page is not split across multiple endnote pages (acceptable for v1).

---

## Cross-cutting invariants (apply in every session)

- Kotlin / `kotlinx.serialization` only (`toJson`/`fromJson`); never `org.json`. No new Gradle deps.
- No Material Components; e-ink palette only; `Slog.d` not `Log.d`; never `runBlocking` on UI thread.
- Icon must come from Tabler (`sticker-2`) and match the project's stroke VectorDrawable style.
- Mirror the **link object** as the reference implementation for every embedded-content/lasso/undo touch
  point — sticky notes are "a link that opens an editor instead of navigating," minus links/headings/
  nested notes in their content and minus a follow target.
- After **any** undo/redo that touches sticky notes, re-read via `loadStickyNotesFromDb` +
  `loadStickyNotes` to keep the in-memory list in sync (the links rule).
- Build + install **debug** on **G102** (`b7a46e13`) at each session end; commit on `sprout`; never push.
```