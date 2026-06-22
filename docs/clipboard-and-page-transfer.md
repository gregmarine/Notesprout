# Global Clipboard + Cross-Notebook Page Transfer

> Implemented in sessions S1–S4 (2026-06-21). S5 adds docs and the edge-case sweep.

---

## 1 — Global Object Clipboard

### Storage model

The clipboard is a single fixed-id `ObjectEntity` row in `notesprout.db` (the global, unencrypted
index). It uses a well-known UUID so no Room migration is needed:

```
CLIPBOARD_ID = "00000000-0000-0000-0000-636c69706264"   // "clipbd" in hex
ObjectType.CLIPBOARD = "clipboard"
```

The row's `data` column holds a `ClipboardPayload` serialized to JSON
(`kotlinx.serialization`, zero reflection):

```kotlin
@Serializable data class ClipboardPayload(
    val items: List<ClipItem>,        // neutral soil-row form
    val boundingBox: BoundingBoxData, // serializable RectF wrapper
    val sourceNotebookId: String,     // provenance only
    val sourceEncrypted: Boolean,     // provenance only — payload itself is always plaintext
    val copiedAt: Long,
)

@Serializable data class ClipItem(val type: String, val boundingBox: BoundingBoxData, val data: String)
// type ∈ {stroke, heading, text, line, link}
```

The payload is always written **plaintext** — the global index is never encrypted. `sourceEncrypted`
records that the user accepted the unencryption warning; it is not used at paste time.

### Relevant files

| File | Role |
|---|---|
| `data/ClipboardPayload.kt` | `BoundingBoxData`, `ClipItem`, `ClipboardPayload` |
| `data/ClipboardStore.kt` | `write / read / clear` against `ObjectDao` |
| `data/ClipboardMappers.kt` | `ClipboardContent ↔ ClipboardPayload` (toPayload / toClipboardContent) |
| `data/index/NotesproutDatabase.kt` | global index holding the clipboard row |
| `NotebookActivity` | `performLassoCopy`, `performLassyCut`, `performLassoPaste`, `clearClipboard` |
| `NotesproutApplication.onCreate` | rehydrates `NotesproutClipboard.content` at app start |

### Hot cache / source of truth split

`NotesproutClipboard` (in-memory singleton) remains the **fast path** for paste within a session.
`notesprout.db` is the **source of truth** — it survives force-stop and app restart.

- Copy/Cut → sets `NotesproutClipboard.content` immediately, then persists via
  `indexRepo.saveClipboard(payload)` on `Dispatchers.IO`.
- App start → `NotesproutApplication.onCreate` rehydrates `NotesproutClipboard.content` from
  `ClipboardStore.read` (off-thread) so the clipboard button shows correctly on the first open.
- Clear → `clearClipboard()` in `NotebookActivity` clears both (`NotesproutClipboard.clear()` +
  `indexRepo.clearClipboard()` on `appScope`). Never call `NotesproutClipboard.clear()` alone.

### Encryption guard (objects)

When `performLassoCopy` / `performLassyCut` runs and the current notebook is encrypted:

1. Show `awaitEncryptionClipboardConfirm()` (AlertDialog, `shape_bordered`): *"This notebook is
   encrypted. Copying these objects places their contents in the app clipboard, which is stored
   unencrypted on this device. Continue?"* — **Continue / Cancel**.
2. Continue → proceed (in-memory + persist).
3. Cancel → abort. For **Cut**, abort *before* the soft-delete (no data loss).

### Single-slot model

Copy/Cut always replaces the previous payload. There is no clipboard history. This matches the
existing in-session behaviour.

### Link objects in the clipboard

A copied `LinkRender` whose target is `CurrentNotebookPage` will dangle when pasted into a different
notebook — the target page id references the source notebook. The paste path inserts the link
verbatim; link-follow handles missing targets gracefully (no crash). Cross-notebook link remapping
is out of scope.

---

## 2 — Cross-Notebook Page Transfer Engine

### Entry point

`data/PageCopier.kt` — two public top-level suspend functions:

```kotlin
suspend fun copyPagesAcrossNotebooks(
    sourcePageIds: List<String>,
    sourcePath: String, sourcePass: String?,
    targetPageId: String?, before: Boolean,   // null → append to end
    destPath: String, destPass: String?,
): List<Pair<String, Int>>?                   // (newPageId, 0-based index in dest) or null

suspend fun movePagesAcrossNotebooks(
    sourcePageIds: List<String>,
    sourcePath: String, sourcePass: String?,
    targetPageId: String?, before: Boolean,
    destPath: String, destPass: String?,
): MoveAcrossResult?
```

`MoveAcrossResult`:

```kotlin
data class MoveAcrossResult(
    val destInsertions: List<Pair<String, Int>>,
    val sourceDeletedAt: Long,
    val sourceDeletedPageIds: List<String>,
)
```

Both functions **assert sourcePath ≠ destPath** and return null if equal (callers must use
`copyPagesRelativeRaw` / `movePagesRelativeRaw` for same-file operations).

### Copy algorithm

1. Open source raw (`SoilCrypto.openRaw`). Read each page row, its live layer, and all live
   children. Soft-deleted objects are skipped.
2. **Template remap.** Collect distinct `data.template` ids from the source pages. For each, read
   the source `template` row (`boundingBox` + `data`). After closing source, open dest and insert
   fresh template rows, building `sourceTemplateId → destTemplateId`. Each copied page's
   `data.template` is rewritten before insertion.
3. Open dest. Compute insertion index from `targetPageId` / `before`. Shift existing dest page
   orders to make room. Insert page / layer / children with fresh UUIDs, `now` timestamps, and
   `destParentId` (the dest `type="notebook"` metadata row id).
4. `checkpointAndVacuum()` on dest; `cleanStrayJournal` on both paths. Both DBs closed in
   `finally`.

### Move algorithm

Copy (as above), then call `softDeleteSourcePages` on success. Source is never touched if copy
fails. If the soft-delete fails after a successful copy, both return null — pages will exist in
both notebooks (the user can manually remove the duplicate).

### Edge cases

| Case | Behaviour |
|---|---|
| `targetPageId` null or not found in dest | append to end |
| dest has zero pages | append to start (insertionIndex = 0) |
| page has template, dest lacks it | template row is copied into dest; page renders correctly |
| same-key encrypted→encrypted (GLOBAL→GLOBAL) | no warning; both keys are identical strings |
| sourcePath == destPath | returns null immediately; caller routes to single-file functions |
| copy fails mid-write | dest transaction rolls back; source untouched |
| page dimensions differ from dest | source page dimensions are kept verbatim; no resampling |

---

## 3 — UI Entry Point (PageIndexActivity)

### Scope chooser

When the user selects pages and taps **Copy** or **Move**, an `ActionSheetDialog` offers:

- **This Notebook** → existing before/after flow (`enterDestMode`), unchanged.
- **Other Notebook** → cross-notebook flow.

### Cross-notebook flow

1. **`NotebookPickerActivity`** launches: folder browse, tap a notebook to return
   `RESULT_NOTEBOOK_ID` + `RESULT_NOTEBOOK_NAME`. Excludes the source notebook via
   `EXTRA_EXCLUDE_NOTEBOOK_ID`.
2. **`enterCrossNotebookDestMode`** resolves the dest key (`KeyResolver.resolveForOpen`), loads
   dest pages (`loadPagesFromSoil(destPath, destPass)`), and replaces the visible page list with
   the dest pages. Stores a `CrossNotebookInfo` with both paths, passphrase, and source page ids.
3. User taps Before / After on a dest page → **`executeCrossNotebookOp`**.
4. **Encryption gate** fires before any write (see §4).
5. Engine is called. On success, source page list is restored (or reloaded for move).

### Cancel

Cancel at any step restores the source pages from `savedSourcePages`. No file writes until Confirm.

### Undo (move only)

`movePagesAcrossNotebooks` returns `MoveAcrossResult` with `sourceDeletedAt` and
`sourceDeletedPageIds`. `PageIndexActivity` stores these in `xnbRemovedPageIds` / `xnbRemovedDeletedAt`
and returns them via `EXTRA_XNB_REMOVED_PAGE_IDS` + `EXTRA_XNB_REMOVED_DELETED_AT` to
`NotebookActivity.pageIndexLauncher`, which pushes `UndoRedoAction.CrossNotebookPagesRemoved`.

- **Undo**: restores soft-deleted source pages + layers + children.
- **Redo**: re-soft-deletes them.

Cross-notebook **copy** is intentionally non-undoable (nothing removed from source).

### Post-confirm navigation

After a successful operation, an AlertDialog asks:

- **Stay here** (positive/default) — dismisses; user stays in source page index.
- **Open ‹DestName›** (negative) — sets `pendingOpenNotebookId` / `pendingOpenNotebookName`,
  calls `finishWithResult(null)`. `NotebookActivity.pageIndexLauncher` reads these extras, calls
  `closeNotebook()` on the source, and starts `NotebookActivity` for the dest.

`KeySession` is cleared by `sealNotebook` on close; the dest notebook resolves its own key via
`KeyResolver.resolveForOpen` in its `onCreate`.

---

## 4 — Smart Encryption Warning (Pages)

Runs in `executeCrossNotebookOp`, before any write:

```
protectionDrops = srcInfo.encrypted &&
    (!dstInfo.encrypted || cross.destPass != cross.sourcePass)
```

| Source → Dest | Warning? | Reason |
|---|---|---|
| plaintext → anything | No | source not encrypted |
| GLOBAL → GLOBAL (same key) | No | `destPass == sourcePass` |
| GLOBAL → NOTEBOOK (different key) | Yes | protection changes key |
| NOTEBOOK → GLOBAL (different key) | Yes | protection changes key |
| encrypted → plaintext | Yes | no encryption at dest |

Dialog text: *"This notebook is encrypted. Copying/Moving these pages to ‹DestName› will store their
contents outside this notebook's encryption. Continue?"* — **Continue / Cancel**. Cancel aborts
before any write.

---

## 5 — Test Matrix

| Scenario | Source | Dest | Expect |
|---|---|---|---|
| Object copy → restart → paste | plaintext | plaintext | objects paste after restart |
| Object cut from encrypted, Cancel | encrypted | — | warning; objects intact |
| Object copy from encrypted, Continue | encrypted | — | warning; persisted plaintext; paste works |
| Page copy, This Notebook | any | self | unchanged before/after behaviour |
| Page copy, Other (same global key) | GLOBAL | GLOBAL | no warning; pages + templates land |
| Page copy, Other (plaintext dest) | encrypted | plaintext | warning; on Continue pages land |
| Page move, Other (different key) | NOTEBOOK | GLOBAL | warning; source short; source undo restores |
| Cancel mid-cross-notebook | any | any | back to source index, no writes |
| Confirm → Open other | any | any | dest opens, pages present, source sealed |
| Page with template → bare dest | any | any | template row copied; page renders |
| Copy to zero-page notebook | any | empty | pages land at index 0 |
