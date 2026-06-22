# Global Clipboard + Cross-Notebook Page Transfer — Implementation Plan

> Status: **S4 done** (2026-06-21). Two related features, sequenced into 5 sessions, each
> shippable and encryption-safe on its own. Follows the same session conventions as the encryption /
> full-notebook export / import plans.

---

## Goals

1. **Global object clipboard.** Promote the in-memory `NotesproutClipboard` to an app-level clipboard
   persisted in the global index (`notesprout.db`), so copied/cut objects survive activity death and
   app restart and move freely between notebooks. Existing copy/cut/paste between pages keeps working
   and now shares the same mechanism.
2. **Copy/move pages to another notebook.** Extend the page-index destination workflow so the user can
   target **This Notebook** (today's before/after flow, unchanged default) **or Other Notebook** —
   pick a destination notebook, then place the selected page(s) before/after a page in *that* notebook.
   Cancellable back to the source page index; on confirm, offer **Stay here** vs **Open the other
   notebook**.
3. **Encryption guardrails.** Any action that takes content out of an encrypted notebook's protection
   warns the user first and is cancellable.

### Locked design decisions (from review)

- **Page encryption warning = smart.** Warn only when protection actually *drops*: destination is
  plaintext, **or** destination is encrypted under a *different key* than the source. Copying between
  two notebooks that resolve to the **same key** (e.g. both GLOBAL on this device) does **not** warn.
- **Object clipboard from encrypted source = warn, then persist.** Show the scope warning; on accept,
  write to the (unencrypted) global clipboard. On decline, cancel the copy/cut.
- **Cross-notebook page MOVE keeps source-side undo.** A cross-notebook move pushes an undo entry on
  the *source* notebook that restores the removed source pages. The pages added to the destination are
  not undoable from the source. Cross-notebook **copy** is non-undoable (nothing is removed) and
  confirmed by toast.

---

## Current State (what we build on)

| Concern | Where | Notes |
|---|---|---|
| Object clipboard | `NotesproutClipboard.kt` | In-memory `object` singleton; holds render objects (`LiveStroke`, `HeadingStroke`, `TextRender`, `LineRender`, `LinkRender`) + union `boundingBox`. Already process-global. |
| Object copy / cut / paste | `NotebookActivity.performLassoCopy/Cut/Paste` (~4461–4699, 4705+) | Copy/Cut populate `NotesproutClipboard.content`; Paste re-IDs, translates to tap point, inserts soil rows, pushes `UndoRedoAction.LassoPasted`. |
| Page copy/move engine | `data/PageCopier.kt` | Raw keyed `.soil` ops: `copyPagesRelativeRaw`, `movePagesRelativeRaw`, `deletePageRaw`, `setPagesTemplateRaw`, `insertSoilTemplateRaw`, `readNotebookRowId`. All single-`.soil`. |
| Page-index destination UI | `PageIndexActivity.kt` | `DestMode { NONE, MOVE, PASTE }`; `enterDestMode`, before/after buttons, `pendingDestPageId`, `confirmDestination`, `executeMove`/`executePaste`, `cancelDestMode`. Returns undo extras to `NotebookActivity.pageIndexLauncher`. |
| Notebook picker UI | `MainActivity` `DestinationPickerState` + `enterPickerMode` (~1895), folder browse, `confirmPickerDestination` (~1956) | Reusable folder-browsing destination chooser for notebooks/folders/import. |
| Keying | `crypto/KeyResolver.kt`, `crypto/SoilCrypto.kt`, `crypto/KeySession.kt` | `resolveForOpen(activity, notebookId, info)` obtains a key (prompting/looping as needed). `KeySession` caches the **current** notebook's key only. `SoilCrypto.openRaw(file, passphrase?)`, `verifyPassphrase`, `probe`. |
| Encryption metadata | `data/index/IndexRepository.getEncryptionInfo(id)` → `EncryptionInfo(encrypted, keyScope)`; `KeyScope { GLOBAL, NOTEBOOK }` | `notesprout.db` itself is **unencrypted**. |
| Global index DB | `data/index/NotesproutDatabase.kt` (v1, single `objects` table of `ObjectEntity`) | Singletons (e.g. Pinned list) are stored as fixed-id `ObjectEntity` rows — no migration needed to add the clipboard. |
| Launch a notebook | `Intent(NotebookActivity)` + `EXTRA_NOTEBOOK_ID` / `EXTRA_NOTEBOOK_NAME` | Identity is id+name, never a `File`. |

---

## Session 1 — Global object clipboard (persistent in `notesprout.db`)

**Outcome:** objects copied/cut in one notebook can be pasted in another *after an app restart*; the
existing in-notebook paste path is unchanged for the user. Copy/cut from an encrypted notebook warns
before writing plaintext to the unencrypted global clipboard.

### Storage (no Room migration)

Store the clipboard as **one fixed-id `ObjectEntity`** in the existing `objects` table, mirroring the
Pinned-list singleton pattern (`PINNED_LIST_ID`).

- New constant `CLIPBOARD_ID` (well-known UUID) and `ObjectType.CLIPBOARD = "clipboard"`.
- `data` holds a `@Serializable ClipboardPayload` (kotlinx.serialization, `toJson()`/`fromJson()`):

```kotlin
@Serializable
data class ClipboardPayload(
    val items: List<ClipItem>,           // neutral soil-row form
    val boundingBox: BoundingBoxData,    // union, serializable (not android RectF)
    val sourceNotebookId: String,        // provenance (used for link-target sanity, diagnostics)
    val sourceEncrypted: Boolean,        // provenance only — payload itself is always plaintext here
    val copiedAt: Long,
)

@Serializable
data class ClipItem(val type: String, val boundingBox: BoundingBoxData, val data: String)
// type ∈ {stroke, heading, text, line, link}; data = the same per-object data JSON written to .soil
```

Rationale for the neutral `{type, boundingBox, data}` shape: it is exactly the row form already used by
`copyPagesRelativeRaw`'s `ChildRow`, is fully serializable, and decouples the on-disk clipboard from
in-memory Android render types.

### New: `data/ClipboardStore.kt`

```kotlin
object ClipboardStore {
    suspend fun write(dao: ObjectDao, payload: ClipboardPayload)   // upsert fixed-id row
    suspend fun read(dao: ObjectDao): ClipboardPayload?            // null if absent/empty
    suspend fun clear(dao: ObjectDao)                              // delete the row
}
```

`IndexRepository` gains thin wrappers (`saveClipboard` / `loadClipboard` / `clearClipboard`).

### Bridge to the existing in-memory clipboard

Keep `NotesproutClipboard` as the **hot in-memory cache** (fast paste, no deserialize), but make
`notesprout.db` the **source of truth**:

- Add mappers: `ClipboardPayload ↔ NotesproutClipboard.ClipboardContent`
  (`ClipboardMappers.kt`) — render objects ↔ neutral items, reusing existing `*Object`/`*Render`
  serializers (`StrokeData`, `HeadingObject`, `TextObject`, `LineObject`, `LinkObject`).
- `performLassoCopy` / `performLassoCut` (`NotebookActivity`): after setting
  `NotesproutClipboard.content`, also persist via `ClipboardStore.write` (off the UI thread). For Cut,
  this is in the same `Dispatchers.IO` block that soft-deletes.
- App start / clipboard-button state: rehydrate `NotesproutClipboard.content` from
  `ClipboardStore.read` if the in-memory cache is empty. Cheapest hook:
  `NotesproutApplication` lazy load on first access, or a one-shot load in `NotebookActivity.onCreate`
  before `updateLassoButtonIcon()` reads `hasContent()`. (Pick the Application-level lazy load so both
  Notebook and any future paste surface see it.)
- `NotesproutClipboard.clear()` callers also clear the persisted row (route both through a single
  `clearClipboard()` helper so they never drift).

### Encryption guard (objects)

When `performLassoCopy` / `performLassoCut` runs and the **current notebook is encrypted**
(`getEncryptionInfo(currentNotebookId).encrypted`):

1. Show an AlertDialog (`shape_bordered`, e-ink styled): *"This notebook is encrypted. Copying these
   objects places their contents in the app clipboard, which is stored unencrypted on this device.
   Continue?"* — **Continue** / **Cancel**.
2. On Continue: proceed (in-memory + persist).
3. On Cancel: abort. For **Cut**, abort *before* the soft-delete (no data loss).

The payload is written plaintext (clipboard DB is unencrypted) — `sourceEncrypted=true` is recorded
for provenance only. Paste is unaffected (already in-memory render objects).

### Edge cases / notes
- **Link targets**: a copied `link` whose target is `CurrentNotebookPage` may dangle when pasted into a
  different notebook. Out of scope to remap; document that cross-notebook link paste keeps the raw
  target (existing behavior — unchanged, just now reachable after restart).
- **Single clipboard slot**: copy/cut replaces the previous payload (matches today's single-slot model).
- **Size**: payloads are bounded by what a user lassos; no special limit. Persist on `Dispatchers.IO`.

### Done when
Copy objects in notebook A, force-stop the app, reopen notebook B, paste → objects appear. Cut/Copy
from an encrypted notebook prompts; Cancel on Cut leaves the objects intact.

---

## Session 2 — Cross-notebook page transfer engine (data layer only)

**Outcome:** pure `PageCopier` functions that copy/move page(s) from a **source `.soil`** into a
**destination `.soil`** (different file, possibly different key), before/after a target page in the
destination. **No UI entry point yet** — nothing user-reachable, so the build stays encryption-safe.

### New functions in `data/PageCopier.kt`

```kotlin
suspend fun copyPagesAcrossNotebooks(
    sourcePageIds: List<String>,
    sourcePath: String, sourcePass: String?,
    targetPageId: String?, before: Boolean,     // null target ⇒ append to end
    destPath: String, destPass: String?,
): List<Pair<String, Int>>?                      // (newDestPageId, indexInDest)

suspend fun movePagesAcrossNotebooks(
    sourcePageIds: List<String>,
    sourcePath: String, sourcePass: String?,
    targetPageId: String?, before: Boolean,
    destPath: String, destPass: String?,
): MoveAcrossResult?                              // dest insertions + source-delete undo data
```

### Algorithm (copy)
1. Open **source** raw (`SoilCrypto.openRaw(sourceFile, sourcePass)`); read each page row, its live
   layer, and live children — reuse the `Row`/`ChildRow` readers already in `copyPagesRelativeRaw`.
2. **Bring templates along.** A page's `data.template` is a **`.soil`-local** template-row id that
   lives in the *source* file. For each distinct referenced template id, read the source `template`
   row and insert an equivalent row into the destination (reusing the `insertSoilTemplateRaw` shape),
   building a `sourceTemplateId → destTemplateId` remap. Rewrite each copied page's `data.template`
   to the dest id before insert. (Pages with no template are unaffected.)
3. Open **destination** raw; compute insertion index from `targetPageId`/`before` exactly as
   `copyPagesRelativeRaw` does; shift dest page `order`s; insert new page/layer/children with fresh
   UUIDs, `now` timestamps, `parentId` = dest notebook/layer ids. Preserve `boundingBox` and the rest
   of `data` verbatim (snapshots stay valid — content is identical).
4. `checkpointAndVacuum()` the destination; `cleanStrayJournal` both paths. Close both DBs in
   `finally`.

### Algorithm (move)
Copy (as above) into dest, then `softDelete` the source pages+layers+children in the **source** file
using one shared `deletedAt` (same pattern as `deletePageRaw`), and return that timestamp + the source
page ids so the source notebook can build a restore-on-undo action. `MoveAcrossResult` carries
`destInsertions: List<Pair<String,Int>>`, `sourceDeletedAt: Long`, `sourceDeletedPageIds: List<String>`.

### Keying
Caller supplies both keys. `sourcePass` = `KeySession.getFor(sourceNotebookId)` (the open notebook).
`destPass` is resolved by the UI session (S3) via `KeyResolver.resolveForOpen(activity, destId, info)`
— **never** derived here. Plaintext notebooks pass `null`.

### Edge cases
- **Different page dimensions.** Copied pages keep their **source** dimensions (page `data` is verbatim).
  Acceptable and documented; no resampling.
- **Empty/missing target** in dest (e.g. dest has zero pages, or target id stale) → append to end.
- **Source == dest** (same `.soil`): callers should route to the existing single-file
  `copyPagesRelativeRaw`/`movePagesRelativeRaw` instead; the cross-notebook functions assume distinct
  files. Add a guard that falls back or rejects equal paths.
- **Failure** anywhere → return `null`, both files closed, dest transaction rolled back, no partial
  source delete (source delete only runs after a successful dest copy, in its own transaction).

### Done when
Instrumented/manual call copies pages A→B across two `.soil`s (plaintext and encrypted-with-key),
templates render in the destination, and a move leaves the source short by exactly those pages.

---

## Session 3 — Page destination chooser UI (This vs Other Notebook) + cancel

**Outcome:** from the page index, Copy/Move offers **This Notebook** (unchanged before/after flow) or
**Other Notebook** (pick a notebook → place before/after a page there → confirm). Cancel returns to the
source page index. Wires the S2 engine. **Includes the smart encryption warning gate** so the build
stays encryption-safe.

### Flow

1. User selects page(s) → taps **Copy** or **Move**.
2. **Scope chooser** (`ActionSheetDialog` or a small AlertDialog): **This Notebook** (default) /
   **Other Notebook** / Cancel.
   - **This Notebook** → existing `enterDestMode(MOVE|PASTE)` path. No change.
   - **Other Notebook** → new cross-notebook flow below.
3. **Pick destination notebook.** Launch a notebook-only picker. Reuse the `MainActivity` folder-browse
   chooser by extracting it into a small pickable activity/result contract, **or** add a lightweight
   `NotebookPickerActivity` (folder browse, tap a notebook to return its id+name). Returns
   `destNotebookId` (+ name) or cancel.
   - Picking the **same** notebook is allowed and just behaves like the This-Notebook path.
4. **Load destination page index.** Re-enter `PageIndexActivity` chrome pointed at the destination's
   pages — reuse `loadPagesFromSoil(destPath, destPass)`. The selected source pages are carried as
   `pendingCrossSourceIds` (+ `sourcePath`, `sourceNotebookId`). Title reflects the destination
   (e.g. *"Copy to ‹DestName› — before/after…"*). Before/After + Confirm chrome identical to today.
   - Destination key obtained up front via `KeyResolver.resolveForOpen` (prompt if encrypted); cancel
     here returns to the source index.
5. **Cancel** at any cross-notebook step → restore the **source** page index exactly (selection +
   `currentPageIndex` intact). No file writes have occurred.
6. **Confirm** → encryption gate (below) → run S2 engine → toast → return to source index (navigation
   prompt added in S4; for now stay in the source notebook).

### Smart encryption gate (pages) — runs before the engine

Let `srcInfo = getEncryptionInfo(sourceId)`, `dstInfo = getEncryptionInfo(destId)`, and resolve
`destPass` first (needed to compare keys).

Warn iff `srcInfo.encrypted` **and** *protection drops*:
- destination **plaintext** (`!dstInfo.encrypted`), **or**
- destination encrypted but `destPass != KeySession.getFor(sourceId)` (different effective key).

No warning when both are encrypted under the **same** key (e.g. both GLOBAL on this device →
`destPass == sourcePass`). Dialog text: *"This notebook is encrypted. Copying/Moving these pages to
‹DestName› will store their contents outside this notebook's encryption. Continue?"* —
**Continue** / **Cancel**. Cancel aborts before any write (and before any source delete).

### Engine call & result handling
- **Copy** → `copyPagesAcrossNotebooks`; on success toast *"Copied N pages to ‹DestName›"*. Non-undoable.
- **Move** → `movePagesAcrossNotebooks`; on success, push a **source-side** restore action so the source
  notebook can undo the removal (see below), toast *"Moved N pages to ‹DestName›"*.

### Source-side undo for cross-notebook move
`PageIndexActivity` already accumulates page actions and returns them to
`NotebookActivity.pageIndexLauncher`. Add a new `UndoRedoAction` variant (e.g.
`CrossNotebookPagesRemoved(pageIds, deletedAt)`) whose **undo** restores the soft-deleted source rows
(`restoreChildrenDeletedSince`-style) and whose **redo** re-soft-deletes them. Marshal it through new
result extras (`EXTRA_XNB_REMOVED_*`) parallel to the existing moved/pasted extras. The destination
additions are intentionally **not** in the undo graph.

### Done when
Copy/Move to another (plaintext and encrypted) notebook works end-to-end; Cancel anywhere returns to
the source index untouched; same-key encrypted→encrypted copy shows **no** warning; plaintext or
different-key destination **does**; a cross-notebook move can be undone on the source.

---

## Session 4 — Post-confirm navigation (Stay vs Open other) + polish

**Outcome:** after a successful cross-notebook copy/move, the user is asked whether to stay or jump to
the destination notebook; assorted polish.

1. **Navigation prompt** (AlertDialog): *"N pages ‹copied/moved› to ‹DestName›."* — **Stay here** /
   **Open ‹DestName›**.
   - **Stay** → remain in the source page index (default; dismiss).
   - **Open** → finish `PageIndexActivity` back to the source `NotebookActivity` with a result flag
     `EXTRA_OPEN_NOTEBOOK_ID` (+ name). `NotebookActivity` seals/closes the current notebook
     (`sealNotebook` → checkpoint/vacuum, the standard close path) and launches `NotebookActivity` for
     the destination via the standard `EXTRA_NOTEBOOK_ID`/`EXTRA_NOTEBOOK_NAME` intent. The
     destination opens through the normal `KeyResolver.resolveForOpen` path (re-prompting if its key
     isn't cached). Pending source-side move-undo is flushed/applied before close as usual.
2. **Polish / hardening**
   - Disable the nav prompt when destination == source (no-op jump).
   - Ensure `KeySession` is updated correctly when switching notebooks (clear source, set destination
     on open — already handled by the standard open path; verify no stale key bleed).
   - Confirm the persisted object clipboard is **not** cleared by a notebook switch (it's app-global).
   - Verify checkpoint/vacuum + stray-journal cleanup on the destination `.soil` after a cold
     cross-notebook write (the dest was opened only by our raw connection, then closed).

### Done when
Confirm → prompt → **Open** lands in the destination notebook with the new pages present and the source
notebook properly sealed; **Stay** keeps the user in the source index.

---

## Session 5 — Docs, MEMORY, edge cases, test matrix

1. **Docs.**
   - New `docs/clipboard-and-page-transfer.md`: global clipboard model (fixed-id `ObjectEntity`,
     `ClipboardPayload`, mappers, encrypted-source warning) + cross-notebook page engine (templates
     travel, smart warning rule, source-side move undo, navigation).
   - Update `docs/full-notebook-export.md` cross-refs if the cross-notebook engine reuses any helpers.
   - Update `CLAUDE.md` docs table with the new doc row.
2. **MEMORY.** Add index entries: global object clipboard (persisted, encrypted-source warning) and
   cross-notebook page transfer (smart warning = key comparison; source-side move undo; templates
   remap into dest).
3. **Edge-case sweep.** Dangling cross-notebook links; copy into a zero-page notebook; copy a page with
   a template into a notebook that lacks it; same-name not an issue (pages aren't name-keyed);
   GLOBAL→NOTEBOOK and NOTEBOOK→plaintext warning permutations.
4. **Test matrix.**

| Scenario | Source | Dest | Expect |
|---|---|---|---|
| Object copy → restart → paste | plaintext | plaintext | objects paste after restart |
| Object cut from encrypted, Cancel | encrypted | — | warning; objects intact |
| Object copy from encrypted, Continue | encrypted | — | warning; persisted plaintext; paste works |
| Page copy, This Notebook | any | self | unchanged before/after behavior |
| Page copy, Other (same global key) | GLOBAL | GLOBAL | **no** warning; pages + templates land |
| Page copy, Other (plaintext dest) | encrypted | plaintext | warning; on Continue pages land |
| Page move, Other (different key) | NOTEBOOK | GLOBAL | warning; source short; source undo restores |
| Cancel mid-cross-notebook | any | any | back to source index, no writes |
| Confirm → Open other | any | any | dest opens, pages present, source sealed |
| Page with template → bare dest | any | any | template row copied; page renders |

5. **Device pass.** Tier-1 (Go 10.3 flagship, Go 10.3 Gen 2, Note Max, Go 7, Palma2 Pro). Two-device
   check for the object clipboard restart path and cross-notebook open.

---

## Open assumptions (flag if wrong)

- The notebook picker for "Other Notebook" reuses/extracts the existing `MainActivity` folder-browse
  chooser rather than inventing a new browsing UX.
- Single-slot clipboard (copy/cut replaces prior contents) is retained.
- Cross-notebook **copy** is non-undoable; only cross-notebook **move** gets source-side undo.
- Copied pages keep their source page dimensions (no resampling to the destination's page size).
