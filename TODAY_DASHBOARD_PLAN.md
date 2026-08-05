# "Today" — the dashboard surface

A full-screen focus view of **today**, and the jump point for the rest of Notesprout. No drawing
surface, no editing: it shows what today asks of you, and takes you where the work lives.

> **Status:** planned, not started. Branch `sapling` (v1.2).

---

## What it is

```
┌───────────────────────────────────────────────────────┐
│ ←  Today             Tuesday 4 August       📅  ☑  ✏️  │
├────────────────────────────┬──────────────────────────┤
│ Tasks                   +  │ Events                +  │
│ Overdue                    │ 09:00    Standup         │
│ ☐ Change filter    2d ago  │ All day  Mum's birthday  │
│ Today                      │                          │
│ ☐ Pay water bill           │                          │
│ ☐ Bins   Weekly reset      │                          │
│ ☑ Water the ferns          │                          │
│         ‹   1/2   ›        │                          │
├────────────────────────────┴──────────────────────────┤
│ Notebooks                                          +  │
│ Today                                                 │
│ Journal                             created · edited  │
│ Notebooks › Ideas › Sketches                 opened   │
│ Recent                                                │
│ Meeting notes                              2 days ago │
│                      ‹   1/2   ›                      │
└───────────────────────────────────────────────────────┘
```

On a device narrower than `sw600dp` the same three sections become three tabs
(**Tasks · Events · Notebooks**, landing on Tasks).

---

## Decisions

Everything below was decided up front; each is a real fork, so the reasoning is recorded with it.

| Decision | Choice | Why |
|---|---|---|
| **Entry point** | Sibling surface, reached from the library bottom bar. A launch preference is **deferred**. | Matches Calendar / Scratch Pad / Tasks exactly, and keeps `BootstrapActivity` forwarding and `MainActivity.reset`'s "library is the implicit bottom of the stack" invariant untouched. Making it home is a later, separable change. |
| **Name** | **Today** | Says what the screen is, not what it's built from. Collides mildly with the Tasks screen's *Today* view — accepted; the dashboard's own task group is also called Today, which reads as agreement rather than conflict. |
| **Task scope** | Open tasks **overdue or due today**, plus tasks **resolved today** (shown ticked). | The tightest honest reading of "today". Upcoming / undated tasks stay in the Tasks screen, one tap away. |
| **Routine steps** | **Steps surface individually**, each labelled with its routine's name. The parent routine row is **not** shown. | The user asked for steps to be visible outside their routine. Showing the routine row as well would put the same work on the screen twice, on a screen with no room to spare. |
| **Event scope** | **Today only** — no reminder look-ahead. | Strict "today". The look-ahead stays where it already lives, in the day window's Events view. |
| **Notebooks** | Today's activity (`created · opened · edited`) **then** up to **10 recents**, deduped against today's rows. | One band that is never empty and never repeats itself. Today's work first, then the jump-point list. |
| **Wide layout** | Tasks \| Events side by side (~55% height), Notebooks full-width beneath. | Tasks and events are narrow rows and pair well in columns; notebook rows need the full width for name + breadcrumb + flags. |
| **Breakpoint** | `sw600dp` | Splits Tier 1 cleanly — G102 (992dp) and MAX single-screen; G6 (571dp) and P2P (439dp) tabbed. Conventional, and the app already has `values-sw600dp` / `layout-sw600dp` buckets. |
| **Pagination** | Per section, **prev / next only** with an `n/m` indicator. No first/last. | As asked. "Today" is inherently small; jumping to the end of a 2-page list is chrome that earns nothing. |
| **Row taps** | Task → Tasks screen (a routine step → its routine). Event → today's day window. Notebook → opens it. | Every row jumps to the surface that owns the thing. Nothing is edited here. |
| **The one edit** | The task state box: check off, and un-check a task resolved today. | A resolved-today row **stays in place, ticked**, until midnight — so an accidental tap on e-ink is undone where it happened, not three screens away. |
| **Routine roll-forward** | Allowed, with the same toast `RoutineActivity` shows. | A step is a step wherever it's ticked. Refusing would make one checkbox behave differently for a reason invisible in advance. |
| **New notebook** | Folder picker first, via the existing `MainActivity.EXTRA_START_NEW_NOTEBOOK` flow. | That flow already exists and the calendar's New Notebook button already uses it. Zero new machinery. |
| **Icon** | Tabler **`layout-dashboard`** (`ic_layout_dashboard`) — one new download. | None of the ~100 existing icons fit. Reads instantly at 24dp and collides with nothing. |

### Assumptions (say if any are wrong)

- **Top bar mirrors `TasksActivity` exactly:** back arrow · divider · title "Today" · date label · trailing
  jumps **Calendar · Tasks · Scratch Pad**. **No separate Library button** — the library is the only
  place the dashboard launches from, so Back already goes there.
- **No schema change and no migration.** Everything comes from new `TaskDao` queries over the existing
  `tasks` table plus the existing events / activity-log / recents stores.
- Pagination controls are **hidden** (not disabled) when a section fits on one page — a disabled
  button is visually silent on e-ink.
- The screen keeps the system bars visible and uses `TopGuard.applyInsetPadding`, like `TasksActivity`
  (no canvas wants to be full-bleed).
- `onResume` refresh + an `ACTION_DATE_CHANGED` / `TIME_CHANGED` / `TIMEZONE_CHANGED` receiver, exactly
  as `TasksActivity` does — everything on screen is relative to the real today.
- Rows are inflated into `LinearLayout`s and rebuilt wholesale on refresh, matching every other list
  in the app. **No `RecyclerView`** (deliberately not a dependency). Pagination is what bounds the cost.
- Lists rebuild on rotation/resize (recomputing rows-per-page); the activity may recreate freely since
  there is no canvas to preserve.

---

## Phases

Each phase stops for on-device review; on a pass it is committed and pushed before the next starts.

### Phase 1 — Surface and shell

The screen exists, is reachable, and is laid out correctly on every Tier-1 device. **No data yet** —
sections render their headers, their `+`, and an empty state.

- `TodayActivity.kt` — chrome, view mode (single-screen vs tabs), `SurfaceStack` registration.
- `res/layout/activity_today.xml` (tabbed) + `res/layout-sw600dp/activity_today.xml` (single screen).
  **Identical id sets in both** — the tab row exists in the wide variant too, just `gone` — so the
  view-binding fields stay non-null and the Kotlin needs no per-variant branch.
- `res/drawable/ic_layout_dashboard.xml` — new Tabler icon.
- `AndroidManifest.xml` — `exported="false"`, `configChanges`, `windowSoftInputMode="stateHidden"`.
- `state/SurfaceStack.kt` — `AppSurface.TODAY` (no payload; the screen reads fresh every time) +
  `MainActivity.restoreSurfaces` mapping.
- Library bottom bar: `btnToday` **first** in `surfaceButtonsGroup`, in **all three**
  `activity_main.xml` variants. On `layout-sw360dp` the bar is already at 428dp of P2P's 439dp, so
  there — as with `btnTasks` — it lives in `overflowToolbar` instead. Id declared in all three either
  way.
- Top-bar jumps wired (Calendar / Tasks / Scratch Pad), long-press hints on every icon button.

**Look at:** does the single screen fit G102 and MAX; do the tabs read right on G6 and P2P; did the
bottom bar survive on P2P; Back and cold-launch restore.

### Phase 2 — Tasks section (and the shared paginated list)

The section most likely to overflow, so it builds the paging scaffolding the other two reuse.

- `data/TodayRepository.kt` + new `TaskDao` queries:
  - open rows `type='TASK' AND state='NOT_DONE' AND dueEpochDay <= :today` — this deliberately
    catches **both** standalone tasks and routine steps, which is the one place the dashboard steps
    outside `TaskDao.MAIN_LIST`. Routines themselves are excluded by the `type` filter.
  - rows resolved today, restricted to `dueEpochDay <= :today` so a task completed early elsewhere
    doesn't appear on a screen it was never on.
  - routine id → title, one query, for the step meta line.
- Grouped **Overdue** then **Today**, sorted by due day then title. **State is not a sort key** —
  ticking a task changes its checkbox and nothing else. (First built with resolved rows sorted to the
  bottom of their group; that let a ticked row sink onto another page, which defeats the point of
  keeping it visible. Paper doesn't reflow when you tick it either.)
- Row rendering reuses `item_task.xml`. Meta line carries the routine name for a step.
- Check off / un-check; routine last-step completion → roll-forward toast; `ReopenOutcome.LOCKED`
  toast for a finished routine's step.
- Tap → `TasksActivity`, or `RoutineActivity` for a step.
- Shared pagination helper: measures a prototype row against the measured host to get rows-per-page,
  renders `‹ n/m ›`, hides itself at one page.

**Look at:** what's listed and what isn't, the routine labelling, check-off and un-check, paging.

### Phase 3 — Events section

- `EventsRepository.eventsForDay(today)`, reused unchanged.
- Read-only rows from `item_event.xml` with the delete button hidden.
- Tap → `DayDetailActivity` for today (Events view). `+` → `EventEditorDialog` defaulted to today.
- Paginated by the phase-2 helper.

**Look at:** ordering (all-day first, then by time), the day-window round trip, create.

### Phase 4 — Notebooks section

- `DayHistoryRepository.notebooksForDay(today)` for the flagged rows, then
  `RecentsManager.resolve(context)` minus anything already listed, capped at 10.
- Two group headers, **Today** and **Recent**; a header renders on whichever page its rows land on.
- New `item_today_notebook.xml` — name, folder breadcrumb, and the flags (today) or relative time
  (recent).
- Tap → opens the notebook through the normal `NotebookActivity` path (encrypted → its unlock path).
- `+` → the existing folder-picker → template + name → create flow.

**Look at:** dedup between the two groups, an encrypted notebook, paging across the group boundary.

### Phase 5 — Polish and device pass

Empty states, content descriptions and long-press hints on everything, contrast and spacing on e-ink,
EPD repaint behaviour on check-off, top guard, and a run across all four Tier-1 devices in both
orientations.

### Phase 6 — Documentation and review

- New `docs/today-dashboard.md`; a row in the CLAUDE.md doc table; cross-links from `tasks.md`,
  `calendar.md`, and `mainactivity-and-recents.md`.
- A thorough review of the built feature for lessons learned and follow-up opportunities; anything
  deferred goes to `BACKLOG.md`, and this plan file is retired to git history.

---

## Deferred

- **"Open on launch: Library / Today"** preference — the path to making this the home screen.
- No day-note / scratch-pad content preview; those remain jump buttons only.
- No weather, no greeting, no counts-and-streaks. It is a focus view, not a metrics screen.
