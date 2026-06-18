# Link Objects — Iterative Build Plan

> **Status legend:** ⬜ Not started · 🔄 In progress · ✅ Done (tests passed)
> **Branch:** `seed` · **Primary test device:** BOOX Go 10.3 (G10, serial `34E517F9`)
> **End-of-session protocol:** clean `assembleDebug` build → install on G10 → provide test steps →
> wait for user to confirm "tests pass" → update this file's session status → commit with a message
> describing what was accomplished + a `(x/n)` session indicator.

---

## Feature Summary

Add a new first-class **link** content object. A link wraps one or more existing objects (the
"held" objects), renders them as themselves plus an optional visual **chrome**, and points at a
navigation **target**. Tapping a link follows it; a swipe-up returns through a back-stack of follows.

This is **Phase 1**: page links and notebook links only. File/website links are a **future effort**
and are explicitly out of scope here.

### Link targets (Phase 1)

| Target kind | Behavior on follow |
|---|---|
| **Page in current notebook** | Navigate to that page (no notebook close). Push to back-stack. |
| **Other notebook — general** | Close current notebook, open target notebook to its last-opened page (same as Recents). Push to back-stack. |
| **Other notebook — specific page** | Close current notebook, open target notebook, navigate to the linked page **by page ID**. If that page is missing, open the last-opened page and show a Toast: *"Linked page is unavailable."* Push to back-stack. |
| (Target notebook missing entirely) | Toast: *"Linked notebook is unavailable."* No navigation. |

### Chrome (visual indicator) — three options

1. **None** — no visual artifact at all (for dashboards).
2. **Underline** — bottom border of the bbox only.
3. **Dotted box + chevron** — dotted bbox outline with a tiny chevron in the lower-right corner.

All chrome drawn in `inkBlack`, 1dp, per the e-ink design system.

---

## Decisions (confirmed with user)

- **Back-stack clearing:** the swipe-up back-stack is **app-level + persisted** (survives notebook
  close/open and app restart). Opening a notebook directly from MainActivity/Recents (a "fresh"
  navigation) **clears** it. Following links and switching notebooks via a link **preserve/extend** it.
- **Follow gesture:** a **finger single-tap inside a link bbox** follows immediately, in **pen mode**.
  The double-tap-to-hide-toolbar gesture only fires when the tap is **not** on a link bbox. **Stylus
  taps never follow** — you can still write on top of a link with the pen.
- **Wrapping:** an "Add link" on a multi-object selection creates **one** link object holding the
  **entire** selection (heterogeneous: strokes + heading + text + line all in one link). A single
  stroke, or any subset, is linkable. (Mirrors how a heading wraps strokes.)
- **Export (PDF/PNG):** link objects render their **embedded content only — no chrome**.

## Assumptions (flagged for review — change before/at Session 1 if wrong)

- **A1.** The chrome selector in the dialog is a 3-option segmented control (None / Underline /
  Dotted+chevron) shown at the top, above the Current/Other toggle.
- **A2.** Editing a link reopens the same full-screen dialog with the current chrome **and** target
  pre-selected; the user may change either or both.
- **A3.** Following a **same-notebook** page link also pushes a back-stack entry (consistent web-like
  behavior). A swipe-up with an empty back-stack is a no-op.
- **A4.** Tap-to-follow is active in **pen mode only** — not lasso, lasso-eraser, or text-placement
  modes (in lasso mode a finger tap on a link is part of selection, used to edit/remove it).
- **A5.** Links are **first-class lasso participants** for move and copy/cut/paste (parity with
  heading/text/line), in addition to the required delete/erase behavior. If you'd rather links NOT be
  copyable/movable, say so and we'll trim Session 5.
- **A6.** A link **cannot** wrap a link (no nesting). A selection that contains a link **plus** other
  objects shows **none** of the link buttons. A selection of exactly **one** link shows edit + remove.

---

## Data Model (target shape — finalized in Session 1)

A link is a `type = "link"` row in the `.soil` notebook table, mirroring the heading pattern
(`HeadingObject` serialized to `data`, `HeadingStroke` as the in-memory render model).

```kotlin
// Serialized to the `data` column (kotlinx.serialization only).
@Serializable
data class LinkObject(
    val target: LinkTarget,
    val chrome: LinkChrome,
    // The wrapped objects, by type. Fresh-UUID copies captured at link-creation time.
    val strokes: List<LiveStroke> = emptyList(),
    val headings: List<HeadingObject> = emptyList(),   // + their bboxes (see note)
    val textObjects: List<EmbeddedText> = emptyList(),
    val lineObjects: List<LineObject> = emptyList(),
)

@Serializable
sealed class LinkTarget {
    @Serializable data class CurrentNotebookPage(val pageId: String) : LinkTarget()
    @Serializable data class OtherNotebook(val notebookId: String) : LinkTarget()
    @Serializable data class OtherNotebookPage(val notebookId: String, val pageId: String) : LinkTarget()
}

enum class LinkChrome { NONE, UNDERLINE, DOTTED_CHEVRON }
```

- **In-memory render model:** `LinkRender(id, boundingBox, target, chrome, <embedded render models>)`,
  built at page load from `type = "link"` rows. Embedded objects reuse existing render models
  (`LiveStroke`, `HeadingStroke`, `TextRender`, `LineRender`) so the existing draw helpers paint them.
- **Bbox note:** each embedded object keeps its own bbox; the link's `boundingBox` is the union
  (the chrome is drawn around this union). Since `HeadingObject`/`LineObject`/`TextObject` don't all
  store a bbox in their serialized form, Session 1 defines small `Embedded*` carriers (id + bbox +
  payload) so the link can reconstruct each held object's render model on load.
- **`@Serializable`** so links can ride in undo/redo actions (like `HeadingStroke`/`TextRender`).
- **`soilFile(context, notebookId)`** is the only way to resolve another notebook's `.soil` path
  (for the Other-notebook page list and cross-notebook follow).

---

## Sessions

### ✅ Session 1 — Object model, rendering, chrome, create/remove + undo/redo (1/5)

> **Done (tests passed on G10).** Implementation notes / deviations from the plan above:
> - Embedded objects reuse the existing `@Serializable` render models (`HeadingStroke`, `TextRender`)
>   directly; lines use a density-independent `EmbeddedLine` (dp + bbox) carrier. No separate
>   `Embedded*` carriers were needed for headings/text.
> - Link create/remove **undo/redo use the full-reload path** (no optimised same-page or two-phase
>   cross-page handlers like headings have). The `'link'` type was added to `getMaxContentUpdatedAt`
>   so the snapshot is correctly invalidated; selection is cleared before the reload. A future pass
>   can add optimised handlers if redraw latency matters.
> - **Drag-layer** link rendering and lasso **move/copy/cut/delete** parity are deferred to Session 5
>   (links are lasso-*selectable* now — basic box hit-test — but not yet movable/copyable/deletable).
> - Only `btnLink` (add) + `btnUnlink` (remove) were added this session; the `link` (edit) button
>   arrives with the dialog in Session 2.


Foundation. Ends with a **temporary** creation hook so the object is testable before the real dialog
exists: the `link-plus` button wraps the selection into a link pointing at the **current notebook's
first page** with a default chrome (cycle chrome on repeated taps for visual testing).

Steps:
1. Add Tabler icons: `ic_link_plus.xml`, `ic_link.xml`, `ic_link_off.xml` (24dp, `@color/inkBlack`,
   stroke style).
2. `data/LinkObject.kt`, `data/LinkRender.kt`, `data/LinkTarget.kt`, `LinkChrome`, `Embedded*`
   carriers. Round-trip `toJson()`/`fromJson()`.
3. `NotebookDao.getLinkObjectsForLayer(layerId)` (`WHERE type = 'link'`); include in
   `getMaxContentUpdatedAt` staleness check; parse into `LinkRender` at page load (two-phase load).
4. `drawLinkObject(canvas, linkRender)` in `OnyxNotebookView` + `GenericNotebookView`: paint embedded
   objects via existing helpers (strokes, `drawHeading`, `drawTextObject`, `drawLineObject`), then
   draw chrome (none / underline / dotted box + chevron). Wire into `redrawCanvas`,
   `buildRenderBitmap`, snapshot composite, and the drag layer. Render order: after lines, before/at
   the layer the embedded type would normally draw (document the chosen order).
5. Floating selection toolbar: add `btnLink` (link-plus) + divider **before** `btnSnapToggle` in
   `activity_notebook.xml`; visibility logic in `updateFloatingSelectionToolbar` per A6 (show add when
   selection has no link; show edit+remove when selection is exactly one link; show nothing when a
   link is mixed with other objects).
6. **Create** (temporary target): `createLinkFromSelection(...)` mirroring `createHeadingFromStrokes`
   — wrap the whole selection into one link (fresh-UUID embedded copies), soft-delete originals in one
   transaction, invalidate snapshot, select the new link.
7. **Remove** (`link-off`): `removeLink(...)` mirroring `removeHeading` — reinsert embedded objects
   with fresh UUIDs, soft-delete the link row, select restored objects.
8. Undo/redo: `UndoRedoAction.LinkCreated` + `LinkRemoved` (model on `HeadingCreated`/`HeadingRemoved`).

Test on G10: select strokes / a heading / a text object → tap link-plus → they collapse into one link
that renders with chrome; reload notebook → link persists & renders; cycle chrome → all 3 styles draw
correctly; select the link → link-off restores the original objects with fresh IDs; undo/redo both
create and remove.

---

### ✅ Session 2 — Full-screen link dialog: chrome selector + Current-notebook page index (2/5)

> **Done (tests passed on G10).** Implementation notes / deviations:
> - Built as a full-screen `LinkTargetPickerActivity` (+ `activity_link_target_picker.xml`),
>   reusing `PageIndexActivity`'s grid machinery (trimmed: no action mode / long-press). Returns
>   `(chrome, pageId)` via an Activity result; the Other toggle is present but disabled (Session 3).
> - Edit uses a new **`btnLinkEdit`** (`ic_link`) shown beside remove for a single-link selection;
>   the picker pre-selects the link's chrome and highlights its target page.
> - Edit is committed by `updateLink`, an **in-place** data-column rewrite + in-memory `LinkRender`
>   swap (chrome redraws without a full reload). Undo/redo uses a new `UndoRedoAction.LinkEdited`
>   (full-reload path, mirroring LinkCreated/LinkRemoved). `createLinkFromSelection` now takes
>   `chrome` + `target` params (the Session-1 temp chrome-cycle hook was removed).

Replace the temporary creation hook with the real dialog for the **Current notebook** path.

Steps:
1. New full-screen dialog/activity modeled on `PageIndexActivity` (reuse its page-thumbnail grid
   rendering). E-ink design system throughout (`shape_bordered`, no elevation/animation).
2. Header: chrome segmented control (None / Underline / Dotted+chevron) [A1]; Current/Other toggle
   (Other stubbed/disabled this session).
3. Current notebook: render the page-thumbnail grid; tapping a page returns
   `(chrome, CurrentNotebookPage(pageId))`.
4. Wire `btnLink` (add) to open the dialog (replacing the Session-1 stub); on result, create the link
   with the chosen chrome + target.
5. Wire the **edit** (`link`) button: when exactly one link is selected, show edit + remove; edit
   opens the dialog pre-selected with the link's current chrome + target [A2]; on confirm, update the
   link's chrome/target in place (undo/redo: reuse a `LinkEdited` action or remove+create pair —
   decide in-session).

Test on G10: add a link by picking chrome + a current-notebook page; verify all three chromes; edit an
existing link's chrome and target; cancel leaves the link unchanged; undo/redo.

---

### ⬜ Session 3 — Other-notebook browsing: folders/notebooks, Notebook/Page sub-toggle, search, breadcrumb (3/5)

Build the **Other notebook** half of the dialog.

Steps:
1. Other-notebook view: folder/notebook browser reusing `IndexRepository` (folders + notebooks like
   MainActivity), with **breadcrumb navigation + back button** to walk the folder hierarchy.
2. **Notebook/Page** sub-toggle (shown only in Other mode):
   - **Notebook:** tap a folder → open it; tap a notebook → create
     `(chrome, OtherNotebook(notebookId))`.
   - **Page:** tap a folder → open it; tap a notebook → open that notebook's **page list** (read its
     `.soil` via a transient read-write Room instance per `soilFile()`, mirroring
     `NotebookExporter.exportPage` / `CoverLoader`); tap a page → create
     `(chrome, OtherNotebookPage(notebookId, pageId))`.
3. Basic notebook **name search** (reuse `SearchEngine` fuzzy matching where it fits).
4. Edit path [A2] pre-navigates the browser to the linked notebook/page when editing an
   other-notebook link.

Test on G10: link to another notebook (general); link to a specific page in another notebook; folder
breadcrumb in/out; search finds notebooks; Notebook vs Page toggle behaves; edit an other-notebook link.

---

### ⬜ Session 4 — Following links + swipe-up back-stack (4/5)

Make links navigable.

Steps:
1. **Tap-to-follow:** finger single-tap inside a link bbox, **pen mode only** [A4]. Hit-test the tap
   against `LinkRender` bboxes in the finger-gesture path. Resolve target:
   - `CurrentNotebookPage` → `navigateToPage(pageIndexOf(pageId))`.
   - `OtherNotebook` → app-level switch (mirror `switchToRecentNotebook`): save browse state, close
     current, open target to last page.
   - `OtherNotebookPage` → open target; navigate to `pageId`; if missing → last page + Toast.
   - Missing notebook → Toast, no nav.
   Every successful follow **pushes** the *origin* `{notebookId, pageId}` onto the back-stack.
2. **App-level back-stack:** `data/links/LinkBackStack.kt` — `object` over SharedPreferences (mirror
   `RecentsManager`), `List<BackEntry(notebookId, pageId)>`, kotlinx.serialization. Push on follow;
   **clear** when a notebook is opened from MainActivity/Recents (the "fresh navigation" reset point).
3. **Swipe-up gesture:** add a one-finger vertical (upward) swipe detector in the
   `handlePageSwipe`/`dispatchTouchEvent` pipeline (distance + velocity + vertical-dominance guards,
   mirroring the horizontal page-swipe). On fire: pop the back-stack and navigate — same notebook →
   `navigateToPage`; cross-notebook → close + open target page. Empty stack → no-op [A3].
4. **Double-tap-hide adjustment:** `handleToolbarToggleGesture` must ignore taps whose down-point is
   inside a link bbox (so a link follow isn't also a toolbar toggle) — confirmed by the
   finger-tap-immediate decision.

Test on G10: follow each link kind; chain several follows then swipe-up repeatedly to walk back;
missing page → toast + last page; missing notebook → toast; open a notebook from Main and confirm the
back-stack was reset; swipe-up on empty stack does nothing; verify pen writing over a link still works.

---

### ⬜ Session 5 — Delete/erase parity, lasso participation, export, edge cases (5/5)

Bring links to full first-class parity and finish.

Steps:
1. **Delete / erase removes the whole link** (link row + embedded objects), distinct from "remove
   link": lasso delete, scribble-erase, and the eraser tool on a link delete it entirely. Extend
   `LassoDeleted` / `LassoErased` / `ScribbleErased` to carry link IDs + `LinkRender` data (like
   headings/text/lines).
2. **Move / copy / cut / paste parity** [A5]: extend `StrokesMoved`, `LassoCut`, `LassoPasted`, and
   `NotesproutClipboard.ClipboardContent` to carry links (new UUIDs + translated bbox on paste).
   Lasso hit-test (`runLassoHitTest` / `scribbleHitTest`) treats links as center-point participants.
3. **Export:** `NotebookExporter.renderPage()` renders link **embedded content only, no chrome**
   (PDF + PNG paths).
4. **Edge cases / hardening:** confirm A6 visibility rules end-to-end; verify snapshot fast-path
   composites links; verify a link's embedded heading recognized-text still renders; verify
   cross-page undo/redo for all new actions.
5. Final clean build + full regression pass on G10.

Test on G10: lasso-delete a link (gone entirely, undo restores); scribble-erase a link; eraser over a
link; move a link by lasso drag; copy/cut/paste a link (incl. cross-page); export a page with links to
PDF and PNG (content shows, no chrome); undo/redo across pages for each.

---

## Notes / Cross-References

- Heading "holds objects" pattern: `createHeadingFromStrokes` / `removeHeading` in
  `NotebookActivity.kt`; `HeadingObject` / `HeadingStroke`. Links mirror this with heterogeneous
  payloads.
- Floating selection toolbar visibility: `updateFloatingSelectionToolbar` (NotebookActivity ~L4283).
- Notebook switch flow: `switchToRecentNotebook` / `closeNotebook` / `sealNotebook`;
  `NotebookMetadata.lastOpenedPage`.
- Finger gestures: `handlePageSwipe`, `handleToolbarToggleGesture`, `dispatchTouchEvent`.
- Page index UI to mirror: `PageIndexActivity.renderGridPage`.
- Cross-notebook `.soil` read pattern: `NotebookExporter.exportPage`, `CoverLoader`,
  `soilFile(context, notebookId)`.
- App-level prefs stores to mirror for the back-stack: `RecentsManager`, `AppStateManager`.
- Undo/redo: `history/UndoRedoAction.kt` (sealed), `history/UndoRedoManager.kt`.
