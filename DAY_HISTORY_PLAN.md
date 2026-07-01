# Day Detail — Notebooks & History Enhancement Plan

Status: **in progress** · Branch: `sprout` · Target test device: **G102** (`b7a46e13`)

Enhance `DayDetailActivity` from a single writing canvas into a three-view "day window":

1. **Note** (default) — the existing editable day-detail canvas. Tools toolbar visible.
2. **Notebooks** — notebooks **Opened / Edited / Created** on this calendar day (card list, paginated,
   tap-to-open). Tools toolbar hidden.
3. **History** — same idea, but for **this month/day in a chosen past year**: a year selector plus a
   first **Notes** option (the read-only day-note from that year) followed by **Opened / Edited /
   Created**. Tools toolbar hidden.

Navigation between the three is a toggle group in the top toolbar, placed **after the Back arrow and
before the drawing tools, separated by dividers** (mirrors the calendar's Month/Week/Day toggles).

---

## Confirmed design decisions (from kickoff Q&A)

- **Tracking model:** new table in `notesprout.db` (plaintext index). **Named `notebook_activity`**,
  not "events" — the word *events* is reserved for the future calendar events system (birthdays,
  anniversaries, appointments). Column `activityType` ∈ {`OPENED`, `EDITED`}.
- **Created is derived, not logged:** the Created list comes straight from `ObjectEntity.createdAt`
  on the index (notebooks whose `createdAt` falls within the day). Inherently retroactive — this *is*
  the "backfill Created" behavior, for free. No `CREATED` rows are written.
- **Backfill:** Created retroactive (above). Opened/Edited are **forward-only** — past-year Opened/Edited
  lists are empty until the log accumulates; past-year **Notes** + **Created** still populate.
- **Year picker:** lists **only years that have data** for this month/day (a day-note OR any
  notebook_activity OR a notebook created that day). **Default = current year − 1.**
- **List dedup:** **one card per notebook per list**. A notebook may appear in all three lists, but at
  most once per list; the card shows that day's most-recent time for that activity type.
- **Past note is read-only:** rendered as a static bitmap (template + content) in an `ImageView` — no
  live drawing engine, EPD-friendly, inherently non-editable. Tools toolbar hidden.

---

## Edit-detection (how `EDITED` gets logged)

`updatedAt` on the index is bumped on **every** notebook close (`indexRepo.touchNotebook` in
`sealNotebook`), so it cannot mean "edited." Instead:

- At seal, query the notebook's `.soil` for **content** rows (stroke/heading/text/line/link/
  sticky_note/shape — *excluding* page/layer/template/notebook_meta) whose `updatedAt >= sessionStart`.
  `> 0` → write one `EDITED` row stamped now. `sealNotebook` already receives `sessionStart`.
- `OPENED` is written in `NotebookActivity.onCreate` next to the existing `RecentsManager.recordOpen`.

---

## Data model

```sql
CREATE TABLE notebook_activity (
    id           TEXT    NOT NULL PRIMARY KEY,   -- UUID
    notebookId   TEXT    NOT NULL,
    activityType TEXT    NOT NULL,               -- OPENED | EDITED
    timestamp    INTEGER NOT NULL
);
CREATE INDEX idx_activity_type_time ON notebook_activity(activityType, timestamp);
CREATE INDEX idx_activity_notebook  ON notebook_activity(notebookId);
```

Room: `NotesproutDatabase` `version 3 → 4`, add `NotebookActivityEntity` to `@Database entities`,
register `MIGRATION_3_4`. The notebook is resolved for display the same way `RecentsManager.resolve`
does (skip/prune missing, soft-deleted, or non-NOTEBOOK rows).

**Query API** (new repository, e.g. `DayHistoryRepository`):
- `notebooksFor(date: LocalDate, type: {OPENED,EDITED,CREATED}): List<ResolvedRecent>` — CREATED routes
  to an index `createdAt` range query; OPENED/EDITED group `notebook_activity` rows by `notebookId`
  within `[dayStart, dayEnd)`, keep newest per notebook, resolve + dedup + prune.
- `yearsWithData(monthDay): List<Int>` — union of years from notebook_activity, index createdAt, and
  existing `cal-daynote-…` pages for that month/day. Descending; used to build the picker.
- Day boundaries use the device default zone; `[startOfDay, startOfNextDay)` epoch-millis.

---

## UI structure (`activity_day_detail.xml`)

```
LinearLayout (vertical)
 ├─ dayToolbar (56dp)
 │    btnDayBack │ ─ │ [Note][Notebooks][History] (view toggles) │ ─ │
 │    dayToolsGroup{ pen eraser lassoEraser lasso ─ undo redo ─ sticky template insertShape scratchpad } │
 │    tvDayDate (right)
 │      • dayToolsGroup VISIBLE only in Note mode
 ├─ 1dp divider
 ├─ daySubBar (GONE in Note mode)
 │    • Notebooks mode: [Opened][Edited][Created]
 │    • History mode:   [◀ year ▶ / tap-to-pick]  [Notes][Opened][Edited][Created]
 └─ dayContent (FrameLayout)
       ├─ drawingView            (Note mode — existing editable canvas)
       ├─ dayListContainer       (Notebooks / History card list — paginated; GONE otherwise)
       ├─ historyNoteImage       (History▸Notes read-only bitmap; GONE otherwise)
       ├─ floatingSelectionToolbar / dayShapeInsertToolbar (unchanged)
```

Card list + pagination reuse the recents/pinned card builder pattern (notebook name, folder path,
date/time, snapshot). Toggle/sub-toggle buttons follow the calendar view-toggle styling. Layout must
also fit `layout-sw…` variants if present and the narrow **Go 7**.

---

## Sessions

Each session ends with: **clean build → install on G102 → stop for user testing → commit after sign-off.**

### Session 1 — Toolbar restructure + view-switching skeleton
- Add Note/Notebooks/History toggle group (after Back, before tools, dividers) and the `daySubBar`.
- Implement mode switching: show/hide `dayToolsGroup`, swap `dayContent` children. Note mode = exactly
  today's behavior. Notebooks/History show placeholders ("Coming soon"). Sub-bar shows the correct
  sub-toggles per mode (non-functional yet). Back-button + EPD repaint behavior across modes.
- **Goal:** de-risk the toolbar layout on G102 (and verify nothing in the existing canvas regressed).

### Session 2 — Activity data layer + logging
- `NotebookActivityEntity` + DAO + `MIGRATION_3_4` (version 4). `DayHistoryRepository` query API.
- Log `OPENED` (NotebookActivity.onCreate) and `EDITED` (sealNotebook, content-modified-since check).
- Wire live counts into the Session-1 placeholders to verify data flows end-to-end.

### Session 3 — Notebooks view (full)
- Card list for Opened/Edited/Created with sub-toggle, pagination, empty states.
- Tap a card → open that notebook (`NotebookActivity` via a result launcher); on close, return to this
  view on the same sub-toggle/page. Encrypted notebooks unlock through the normal NotebookActivity flow.

### Session 4 — History view (full)
- Year selector (years-with-data, default current−1). `Notes` sub-mode renders the chosen year's
  `cal-daynote-…` page as a read-only bitmap (template + content); empty state if none.
- Opened/Edited/Created reuse the Session-3 list, keyed to the chosen year's month/day.

### Session 5 — Polish & multi-device QA
- Narrow-screen toolbar (Go 7), empty states, deleted/encrypted-notebook handling in lists, EPD
  repaint, back-stack across modes, last-mode persistence (optional — confirm with user).
- Install + sanity-check on Tier 1 (G10, G102, Note Max, Go 7, Palma2 Pro).

### Session 6 — Wrap-up
- Update `docs/calendar.md` (Day-detail section) + `CLAUDE.md` calendar row + memory index.
- Final commit + push.

---

## Open items to confirm during the build
- Should the active view (Note/Notebooks/History) persist across reopen of the day, or always reset to
  **Note**? (Default assumption: always reset to Note.)
- Year-picker UI: inline `◀ year ▶` stepper vs. tap-to-open list dialog. (Default: stepper across
  years-with-data; tap label opens a full list.)
