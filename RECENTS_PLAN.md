# Recents — Implementation Plan & Session Tracker

**Feature:** "Recent Notebooks" — a device-local list of the most recently opened notebooks,
surfaced in two places with very different UI:

1. **MainActivity Recents view** — a new browse mode (peer of Pinned/Search) showing notebook
   *cards* of recently opened notebooks.
2. **NotebookActivity Recents list** — a TOC-style paginated dialog to quickly switch notebooks
   while one is open.

**Status legend:** ⬜ Not started · 🔄 In progress · ✅ Done (committed) · 🧪 Awaiting user validation

> Commit each session separately with message `Recents Session x/6`. **Do not push** until Session 6.
> Validation: data-only → verified via adb by Claude; UI-visible → step list for the user to run.
> Only commit a session after its validation passes.

---

## Agreed Decisions (locked)

- **Storage:** `SharedPreferences` + `kotlinx.serialization` JSON list — a new `RecentsManager`
  object mirroring `AppStateManager` / `SortPreferencesManager`. Device-local, not in
  `notesprout.db`, not in any `.soil` file.
- **Capacity:** 20 entries. On overflow, drop the oldest (furthest-back timestamp).
- **Identity:** a notebook appears at most once. Opening an already-listed notebook bumps it to top.
- **Timestamp:** a single `timestamp: Long` per entry. Set to *now* on open; **updated** to *now*
  on close. List is ordered by `timestamp` descending (most recent first).
- **Stale entries:** at display time, resolve each entry against the index; **silently skip and
  prune** any notebook that no longer exists or is soft-deleted (self-healing store).
- **NotebookActivity list:** **excludes** the currently-open notebook (it's top of the store but
  hidden from its own switch list).
- **Empty state:** Recents entry points are **always visible** (subject to mode rules); when empty
  they show a centered "No recent notebooks" message (TOC empty-state pattern).
- **Icon:** Tabler `clock` → `res/drawable/ic_clock.xml`, used by **both** buttons.
- **Return-to-folder on close:** opening from either recents path lands the user back in the
  *closed notebook's* folder on close — matching today's search-mode behavior.

---

## Shared vs. View-Specific Work

**Shared (built once, used by both views):**
- `RecentEntry` model + `RecentsManager` store (Session 1).
- Record-on-open / record-on-close hooks in `NotebookActivity` (Session 1).
- `ic_clock.xml` drawable (Session 1).
- `RecentsManager.resolve(context)` — resolves stored entries against the index into display
  models (`notebookId`, `notebookName`, `folderPath`, `timestamp`), skipping + pruning missing
  ones. Introduced in **Session 3** (first consumer), reused by **Session 4**.

**View-specific:**
- MainActivity recents *mode* + card rendering (Sessions 2–3).
- NotebookActivity `RecentsDialog` + switch flow (Sessions 4–5).

---

## Session 1 — Recents Data Core (shared) ✅
**Goal:** the store exists and is correctly maintained on every open/close. No user-facing UI.

**Work:**
1. Create `res/drawable/ic_clock.xml` — Tabler `clock` as a 24dp stroke VectorDrawable,
   `@color/inkBlack`, matching existing `ic_*.xml` style.
2. `data/recents/RecentEntry.kt` — `@Serializable data class RecentEntry(val notebookId: String, val timestamp: Long)`.
3. `data/recents/RecentsManager.kt` — `object` over `SharedPreferences("notesprout_recents")`,
   single key holding a JSON-serialized `List<RecentEntry>`:
   - `load(context): List<RecentEntry>` (ordered newest-first; tolerant of malformed JSON → empty).
   - `recordOpen(context, notebookId)` — remove any existing entry for the id, prepend a fresh
     `RecentEntry(id, now)`, cap to 20, persist.
   - `recordClose(context, notebookId)` — if present, update that entry's `timestamp = now`,
     re-sort newest-first, persist. (No-op if not present.)
   - `remove(context, notebookId)` — used by prune.
   - `MAX_ENTRIES = 20`, `PREFS_NAME`, `KEY_ENTRIES` constants.
4. Wire `RecentsManager.recordOpen(this, notebookId)` into `NotebookActivity.onCreate` right after
   `notebookId` is resolved from `EXTRA_NOTEBOOK_ID` (~line 1228–1231), guarded on non-empty id.
5. Wire `RecentsManager.recordClose(appCtx, nbId)` into the close/seal path so it fires on every
   close. Use `applicationContext` (the Activity may be finishing); call from `closeNotebook()` /
   `sealNotebook()` where `nbId` is in scope (~line 1675–1706).

**Validation (data-only — Claude via adb):**
- Build + install debug on a device (e.g. G10 `34E517F9`).
- Open notebook A, close; open B, close; open A again.
- `adb -s <serial> pull /sdcard/Android/data/com.notesprout.android.dev/files/.../shared_prefs/notesprout_recents.xml`
  (or `run-as` / `exec-out cat`) and confirm: A is first (bumped), single entry per id, timestamps
  update on close, ≤20 entries after opening >20 distinct notebooks.

**Commit:** `Recents Session 1/6`

---

## Session 2 — MainActivity Recents Mode Scaffolding ⬜
**Goal:** the recents *mode* exists with correct chrome and navigation — rendered list can be a
temporary placeholder/empty view (cards come in Session 3).

**Work:**
1. `activity_main.xml` — add `btnRecents` (`ic_clock`) **after** `btnSearch`, **before** `btnSort`.
2. State: `private var isRecentsMode = false`. Mutually exclusive with pinned/search/picker.
3. `enterRecentsMode()` / `exitRecentsMode()` modeled on `enterPinnedMode()`/`exitPinnedMode()`:
   - Top bar: title **"Recent Notebooks"** on the left + **X** close button on the right (reuse the
     `pinnedTitle` + `btnPinnedCancel` chrome, branching the cancel handler by active mode; or add
     dedicated views if cleaner).
   - Bottom bar: **pagination only** — hide `btnNewNotebook` and `btnNewFolder`.
   - Hide `btnPinned`, `btnSearch`, `btnSort`, `btnRecents` while in recents mode (peer of pinned).
4. `btnRecents` only visible when **not** pinned and **not** search (same visibility rule as
   `btnSort` — see `enterSearchMode`/`exitSearchMode` and the visibility blocks ~line 365–383).
5. Back-press priority: handle `isRecentsMode` first (before picker/search/stack), mirroring the
   `isPinnedMode` priority.
6. **Not persisted:** recents mode is never written to `AppStateManager` (same as search mode).
7. Render path: route `isRecentsMode` to a temporary placeholder (empty-state text is fine for now).

**Validation (UI — user step list):** enter/exit recents mode; verify title/X, bottom bar shows
only pagination, new-folder/new-notebook gone, other toolbar buttons hidden, back-press exits
recents (not the app), mode is not restored after app restart, and recents/pinned/search are
mutually exclusive.

**Commit:** `Recents Session 2/6` (after validation passes)

---

## Session 3 — MainActivity Recents Cards (+ shared resolver) ⬜
**Goal:** real recents content as notebook cards, with tap-to-open + return-to-folder.

**Work:**
1. **Shared resolver** in `RecentsManager`: `suspend fun resolve(context): List<ResolvedRecent>`
   on `Dispatchers.IO`. `ResolvedRecent(notebookId, notebookName, folderPath, timestamp)`.
   - For each stored entry: look up the `ObjectEntity` in the index; if missing/soft-deleted →
     collect for pruning and skip. Build `folderPath` by walking the `parentId` chain
     (e.g. `"Notebooks › A › B"`, matching the search/pinned label convention).
   - After resolving, prune skipped ids from the store in one write. Preserve newest-first order.
2. Render recents cards in MainActivity (reuse the existing notebook-card builder used by
   pinned/search). Each card shows, underneath:
   - **Folder name** (immediate parent) — `folderPath.substringAfterLast(" › ")`, same as
     pinned/search card labels.
   - **Notebook name**.
   - **Date/time** last opened/closed — `DateFormat.getMediumDateFormat(...)` +
     `getTimeFormat(...)` of `timestamp` (same formatter the sort labels use).
3. Pagination over the resolved list (reuse existing grid pagination).
4. Empty state: centered "No recent notebooks" when resolved list is empty.
5. Tap → open: reuse the **search-mode** open pattern — `navigateStackToFolder(parentId)` +
   `AppStateManager.save(AppViewState(parentId, false))` + `exitRecentsMode()` +
   `launchNotebookActivity(entity)`, so closing the notebook returns to its folder.

**Validation (UI — user step list):** open several notebooks; verify recents cards ordered newest
-first, correct folder name + notebook name + date/time, paging works, tapping opens the notebook,
closing returns to that notebook's folder, deleted notebooks never appear.

**Commit:** `Recents Session 3/6` (after validation passes)

---

## Session 4 — NotebookActivity RecentsDialog (reuses shared resolver) ⬜
**Goal:** a TOC-style paginated "Recent Notebooks" dialog, openable from a new toolbar button.
Tap behavior is stubbed (no switch yet) — switch flow lands in Session 5.

**Work:**
1. `activity_notebook.xml` — add `btnRecents` (`ic_clock`) **after** `btnClose`, **before** the
   divider that precedes `btnToc` (grouped with the close button).
2. `notebook/RecentsDialog.kt` — modeled on `TocDialog`:
   - Title **"Recent Notebooks"** (no numbering).
   - Each row: **notebook name** first, styled like TOC heading text; **date/time** under it in a
     smaller font; **folder path** under that, same small size as the date/time.
   - Paginated exactly like TOC (measure row height after layout → `itemsPerPage`; first/prev/
     next/last controls + indicator). New layouts: `dialog_recents.xml` / `item_recent_entry.xml`
     (or reuse `activity_toc.xml` structure).
   - Empty state: "No recent notebooks".
3. Data: `RecentsManager.resolve(context)`, then **exclude the currently-open notebook** (filter by
   `notebookId`).
4. Wire `btnRecents` to build entries off-thread then `RecentsDialog(...).show()`.
5. Tap callback: stub for now (e.g. dismiss only) — full switch in Session 5.

**Validation (UI — user step list):** open a notebook, tap the recents button; verify dialog title,
rows show name (TOC style) / date-time (smaller) / folder path (smaller), current notebook is
absent, pagination matches TOC feel, empty state when no other recents.

**Commit:** `Recents Session 4/6` (after validation passes)

---

## Session 5 — NotebookActivity Switch Flow + Return-to-Folder ⬜
**Goal:** tapping a recents row closes the current notebook properly and opens the selected one,
without bouncing through the notebook list; closing the switched notebook returns to *its* folder.

**Work:**
1. `RecentsDialog` tap → `onRecentSelected(notebookId)` in NotebookActivity:
   - Capture the selected notebook's `ObjectEntity` (name + `parentId`) from the index.
   - Run the **current** notebook's full close (`closeNotebook()` — captures snapshot on main
     thread, seals on `appScope`, which also fires `recordClose`), then `finish()`.
   - **Launch the selected notebook** directly (new `NotebookActivity` with its
     `EXTRA_NOTEBOOK_ID`/`EXTRA_NOTEBOOK_NAME`) — does **not** return to MainActivity.
   - Order so the leaving notebook is recorded-closed before the new one is recorded-open (the
     new Activity's `onCreate` `recordOpen` handles the open side).
2. **Return-to-folder:** before launching the switched notebook, write
   `AppStateManager.save(AppViewState(selected.parentId, false))`. Add an `onResume` sync in
   MainActivity: if the persisted browse folder differs from the current folder (and not in
   pinned/search/picker), re-navigate the stack to it. This makes closing the switched notebook
   land in the new notebook's folder even though MainActivity was sitting in the original folder.
   - Verify this does **not** disturb the normal close path (persisted folder == current → no-op)
     or the MainActivity-recents open path (already navigates before launch).

**Validation (UI — user step list):** from notebook A's recents, switch to B; confirm A is sealed
(reopen A and verify last edits saved), B opens directly (no flash of the notebook list), B now
sits atop recents and A's timestamp updated; close B → land in B's folder. Repeat A→B→C chains.

**Commit:** `Recents Session 5/6` (after validation passes)

---

## Session 6 — Full Feature Validation + Docs ⬜
**Goal:** end-to-end sign-off, documentation, ship.

**Work:**
1. Provide a **complete** end-to-end test script covering both views, ordering, 20-cap eviction,
   dedupe/bump, open/close timestamp updates, stale pruning, empty states, and both
   return-to-folder paths. **User validates the whole feature.**
2. Fix anything found.
3. Update `CLAUDE.md` with a new "Recents System" section (store, record points, both UIs, shared
   resolver, return-to-folder mechanism).
4. Commit `Recents Session 6/6`, then **`git push`** everything.

**Commit:** `Recents Session 6/6` → **push**

---

## Open Notes / Risks
- `MainActivity` (1715 lines) has dense mode handling (pinned/search/picker/state-restore). Session 2
  must respect mode exclusivity + back-press priority + the `isStateRestored` deferral.
- `RecentsManager.resolve` walks `parentId` chains via the index — keep it on `Dispatchers.IO`.
- Session 5's `onResume` folder-sync is the one cross-activity coordination point; keep it narrow
  (only re-navigate when persisted ≠ current and no special mode is active).
