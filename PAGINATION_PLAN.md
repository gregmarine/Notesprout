# Pagination Plan — replacing scrolled interfaces with pages

> **Why.** Core philosophy: *"Pages feel like physical pages. The app should never feel like a web
> app."* Most browsable surfaces already paginate. A handful of content lists still scroll, and each
> one sits next to an already-paginated twin — the Today dashboard paginates task rows while the
> Tasks screen scrolls the same rows. This plan closes that gap.
>
> Status: **inventory complete, scope decided, not started.** Survey done 2026-08-06 over every
> layout in `app/src/main/res/layout*` and every hand-built view in `app/src/main/kotlin`.
>
> Not every answer is a pager. Two surfaces (#7, #11) are in scope and lose their scroller, but by
> changing the control rather than adding pages — see *In scope — agreed, but not by paginating*.
> Scope is now fully decided; nothing is left open.

---

## Already paginated — nothing to do

| Surface | Pager |
|---|---|
| `MainActivity` library grid (+ search / pinned / recents modes) | first·prev·**n/m**·next·last, measured grid |
| `PageIndexActivity` page grid | same |
| `TemplateBrowserActivity` grid | same |
| `LinkTargetPickerActivity` browse grid | same |
| `TocDialog` (`activity_toc.xml`) | same |
| `RecentsDialog` (`dialog_recents.xml`) | same |
| Day window → **Notebooks** and **History** views (`dayListRows` + `tvDayListPageIndicator`) | same |
| `TodayActivity` × 3 sections (`TodaySection.kt`) | prev·**n/m**·next; rows measured against the real band, group headers repeat on continuation pages |

`TodaySection.kt` is the reference implementation and the likely basis for a shared row-pager:
it builds rows once, measures each against the real width/height of the band it must live in, packs
them into pages, and re-emits a group header at the top of a continuation page. It also carries the
two e-ink rules worth preserving — the pager stays `INVISIBLE` (never `GONE`, never merely disabled)
so its 44dp is always reserved and a disabled control is never silently invisible.

---

## Decisions — 2026-08-06

### ✅ In scope — agreed

| # | Surface | Where |
|---|---|---|
| 1 | **`TasksActivity`** — Today / All / Done | `activity_tasks.xml:148` `tasksScroll` / `tasksList`; rows built in `TasksActivity.kt:215,320`, headers via `addSectionHeader` (`:414`) |
| 2 | **Day window → Events view** | `activity_day_detail.xml:631` `dayEventsList`; rows from `EventsController.render` (`:63`) — attached + recurring events *and* the Reminders look-ahead all in one list |
| 3 | **`RoutineActivity`** — step list | `activity_routine.xml:109` `routineList` |
| 4 | **`NotebookPickerActivity`** — folder + notebook browse list | `activity_notebook_picker.xml:50` `scrollView` / `listContainer` |
| 8 | **`TemplateDialog`** and **`DayTemplateDialog`** — template thumbnail grids | `TemplateDialog.kt:144`, `DayTemplateDialog.kt:100` |
| 9 | **`CalendarActivity` insert-position picker** — *plus* a page-label fix, see below | `CalendarActivity.kt:1483` `scroll` / `list` |
| 10 | **`DayNotebooksDialog`** — notebooks touched on a day | `DayNotebooksDialog.kt:50` |

Notes carried from the survey:

- **#1 is the highest-value, lowest-cost item.** It has grouped section headers already, so it maps
  directly onto `TodaySection`'s header-repeating packer. It should also let the Done view's
  `DONE_WINDOW_DAYS` / "Show N earlier" escape hatch (`TasksActivity.kt:345`) be reconsidered — that
  window exists *because* the list grows unbounded.
- **#2 removes an interaction hazard, not just a scroll.** `core/TwoFingerSwipeDown.kt:35` records
  that the day window briefly had to *consume* the gesture to stop this ScrollView following the
  swipe. No scroller, no conflict.
- **#4 is the odd one out** — its three sibling browsers (`MainActivity`, `TemplateBrowserActivity`,
  `LinkTargetPickerActivity`) are all paginated grids.
- **#8 is a direct inconsistency**: the full-screen `TemplateBrowserActivity` paginates the *same*
  templates these two dialogs scroll.
- Extracting `TodaySection`'s measured row-packer into a reusable component should serve 1, 2, 3, 4
  and most of the dialog work.
- **#9 carries a second, independent fix — see below.** It is the only item in this batch that
  changes what the rows *say*, not just how many are shown at once.

#### 9 — also: label pages by their heading, not "Page N"

*What it is:* Send-to-Notebook asks where the page lands. The dialog offers "End of notebook" plus
one tappable row **per page of the destination notebook**, then a Before / After follow-up. Mirrors
the page-index copy/move flow. Unbounded — a 200-page notebook is a 200-row scrolling dialog.

Today every row reads `"Page ${i + 1}"` (`CalendarActivity.kt:1507`), because the picker is fed only
a list of ids. **Every other page-selection surface already shows the page's heading when it has
one**, via a single shared rule:

| Site | Label expression |
|---|---|
| `PageIndexActivity.kt:768` | `entry.headingName ?: "Page ${entry.pageNumber}"` |
| `LinkTargetPickerActivity.kt:1046` | `entry.headingName ?: "Page ${entry.pageNumber}"` |
| `ExportNaming.pageLabel` (`export/ExportNaming.kt:44`) | `page.headingName ?: "Page ${page.number}"` |

The authority is **`loadPageRefs(path, passphrase)`** (`data/PageList.kt:26`), which returns
`PageRef(id, number, headingName)` — `headingName` resolved by `topHeadingNamesByPageId`, null when
the page has no heading with recognized text. `PageIndexActivity.kt:407` is the model call.

**The change:** `CalendarActivity.kt:1458` currently calls `loadNotebookPageIds(destPath, destPass)`,
which returns ids only. Swap it for `loadPageRefs` and label each row
`ref.headingName ?: "Page ${ref.number}"`. No new logic — this picker is simply the one page list
that never got wired to the shared rule.

Three things to get right while doing it:

- ⚠️ **Do not lose the open-failure signal.** `loadNotebookPageIds` returns **`null`** on failure and
  the call site toasts "Couldn't open notebook" (`:1459`). `loadPageRefs` deliberately returns an
  **empty list** instead ("callers treat that as nothing to show rather than an error"). A naive swap
  makes a failed open indistinguishable from an empty notebook — and the empty branch
  (`if (pageIds.isEmpty()) { onChosen(null, false); return }`) would then **silently append into a
  notebook we could not read**. Keep an explicit failure path.
- **The Before/After follow-up must take the label too.** `showBeforeAfterPicker` (`:1523`) hardcodes
  `"Insert before Page $pageNumber"` and titles itself `"Page $pageNumber"`. A heading page should
  read "Insert before Introduction". Pass the resolved label through.
- **`loadNotebookPageIds` (`data/PageCopier.kt:1067`) becomes dead** — `CalendarActivity:1458` is its
  only caller. Remove it with the change, or note why it stays.
- `loadPageRefs` is a plain blocking function, not `suspend`; the call site already wraps in
  `withContext(Dispatchers.IO)`, which is how `PageIndexActivity` uses it too.

### ❌ Out of scope — decided against

| # | Surface | Reason |
|---|---|---|
| 5 | `PageTextViewerActivity` — recognized text + Correct-mode rows (`PageTextViewerActivity.kt:239`) | **Not doing.** Paginating prose needs a text-layout page-splitter, a different mechanism from the row-packer |
| 6 | `DocumentEditorActivity` Preview (`DocumentEditorActivity.kt:372`) | **Not doing.** Same reason — prose, not rows |
| 12 | `CustomizeToolbarDialog` (`dialog_customize_toolbar.xml:218`) | **Superseded.** `BACKLOG.md:175` already plans a full redesign into two stacked *grid* panels ("Showing" / "Hidden"). Don't paginate what's being replaced. Also: its hand-rolled drag-reorder auto-scrolls *using* this ScrollView, so pagination would break dragging a row across a page boundary |
| — | **Tier C — forms** — `activity_backup_settings.xml`, `activity_encryption_settings.xml`, `activity_hwr_settings.xml`, `activity_export.xml`, `activity_onboarding.xml`, `dialog_task_editor.xml`, `dialog_event_editor.xml`, `dialog_insert_lines.xml` | Single vertical forms, not lists. "Page 2 of settings" is worse than a scroll |
| — | **Tier D — horizontal scrollers** — breadcrumb `HorizontalScrollView` (`activity_main` ×3 width buckets, `activity_template_browser` ×3, `activity_link_target_picker`), and `dialog_text_edit.xml:55` (markdown toolbar button row) | Horizontal chrome, not list scrolling |
| — | Debug-only: `HwrLabActivity`, `DebugKeyActivity`, `TodaySeedActivity` | Not shipped surfaces |

### ✅ In scope — agreed, but *not* by paginating

Two surfaces where the discussion concluded pagination is the wrong tool. Both are still in scope;
both remove a scroller. Neither uses a pager.

#### 7 — Export presets → a select control

`presetRows` (`activity_export.xml:120`, rendered by `ExportActivity.renderPresets` `:254`) lives
*inside* the export form's ScrollView, which is Tier C and stays scrolled. So there is no standalone
band to measure a pager against — nesting one inside a scroller would be the worst of both.

**Decision: replace the N growing rows with a single `AppCompatSpinner`.** Tap it, pick, it collapses
to the selection. A user is unlikely to hold more than 3–4 presets, so the popup never scrolls; at
thirty it degrades to a scrolling popup rather than breaking, so **no cap is needed**.

Precedent is established — eight `AppCompatSpinner`s already ship across `dialog_task_editor.xml`,
`dialog_event_editor.xml`, and `dialog_routine_editor.xml`, all using `simple_spinner_item` +
`simple_spinner_dropdown_item` with no custom style.

Why this beats moving presets to their own paginated screen:

- **The active preset becomes always-visible.** Today it is marked with `row.isSelected` and has to
  be scanned for; a collapsed spinner simply shows it.
- **It makes an invisible state change visible.** `clearActivePreset()` fires whenever any option is
  touched, which today silently un-highlights a row. The spinner needs a **position-0 "Custom"**
  entry, and the selection visibly snaps back to it on any deviation.
- **The guard already exists.** `applyingPreset` (`ExportActivity.kt:119`) was written because
  programmatic widget writes fire their own listeners — exactly the hazard `Spinner.setSelection`
  creates. The defense is already in place.

To build:

- Position-0 **"Custom"** entry; `clearActivePreset()` selects it.
- **Long-press-to-delete dies with the rows.** Replace with a small trash button beside the collapsed
  spinner, acting on the current selection, hidden while "Custom" is showing. More discoverable than
  today's hidden long-press.
- **Keep "+ Save current settings…" as its own row.** Choosing and creating are different verbs;
  folding "save" into the selector invites accidental saves.
- ⚠️ **Check on device.** `Spinner`'s dropdown popup is system chrome we don't fully control, and the
  design system forbids elevation/shadow. The eight existing spinners already made this trade, so
  consistency says it is fine — but look at it on the G102, since it is a stated rule.

Net: the section shrinks from N+1 rows to 2.

#### 11 — `ActionSheetDialog` → an icon grid for menus

`ActionSheetDialog.kt:140`. Actions sit in a ScrollView capped at 72% of screen height — a cap added
because a menu clipped on the BOOX Go 6. 23 call sites across 9 files.

**The audit split the component in two.** Of 78 `addAction` calls, **52 pass an icon and 26 pass
`null`** — and the null ones are not an oversight, they are a different kind of sheet entirely:

> "Keep existing passphrase" · "Use this device's global" · "New notebook passphrase" · "Cancel"
> "Replace existing notebook" · "Keep both" · "Cancel"
> "Replace this document" · "Add below the current text" · "This Notebook" · "Other Notebook"

Those are **questions with prose answers**, several carrying an explicit "Cancel" row. The 52
icon-bearing calls are true **menus** — Copy, Move, Rename, Export, Delete, Pin, Insert Before/After.

**Decision: the grid replaces rows for menus outright.** The discriminator is free — icon present or
absent — so the sheet picks its own layout with **no call-site changes**:

- **Every action has an icon → icon grid**, label under each cell.
- **Any action lacks one → keep the current rows.** A grid is actively wrong for prose answers;
  "Keep existing passphrase" will never be a cell.

Sizing (worst case is `MainActivity:1873`, the notebook context menu, ~11 actions, all iconed):
3 columns → 4 rows; cell ≈ 8dp pad + 24dp icon + 6dp gap + two 12sp label lines + 8dp pad ≈ 76dp.
**~304dp + title, against ~600dp today — roughly 40% shorter**, comfortably inside a ~720dp-tall
Go 6. So the grid is expected to **dissolve the pagination need here entirely**; a pager is a
fallback we should not need.

To build:

- **Labels shorten for free.** The title row already names the subject, so `"Copy Notebook"` /
  `"Rename Notebook"` / `"Delete Notebook"` become `Copy` / `Rename` / `Delete`. Only two labels
  genuinely fight a ~100dp cell: **"Change Encryption Scope"** → "Encryption Scope", and
  **"View recognized text"** → "Recognized text".
- ⚠️ **Icon audit is the real cost.** Duplicates that are harmless beside a label become confusing
  when the icon dominates. In the notebook menu alone: `ic_lock` ×3 (Encrypt Notebook, Change
  Passphrase, Change Encryption Scope) and `ic_edit` ×2 (Rename Notebook, Change Passphrase). Tabler
  has `lock-cog` / `lock-password` / `key`, so it is solvable from the existing vocabulary — but it
  is a deliberate pass across the 52 sites. **109 `ic_*.xml` already exist locally — look before
  downloading.**
- ⚠️ **Destructive actions lose their natural position.** A row list gives "danger lives at the
  bottom" for free; a grid flows Delete into an arbitrary cell beside Rename. Reserve a separated
  final row, or a divider before the destructive cells.
- Keep: no Material, no elevation/shadow, 1dp inkBlack dividers, pinned title row with X, the
  `canceledOnTouchOutside` contract.

**Sequencing: this is its own effort, not part of the 1/2/3/4/8/10 batch.** It is a two-mode
component plus an icon audit plus a label pass — a different shape of work from the row-pager.

### 🤔 Needs discussion

*Nothing outstanding — every surveyed surface is now decided.*

---

## Reference — the pagination contract to match

From `view_today_section.xml` and `TodaySection.kt`, both already documented in
[`docs/today-dashboard.md`](docs/today-dashboard.md):

- **prev / next only** for in-band section lists; **first·prev·next·last** for full-screen grids.
- **Arrows never disable** — a disabled control is visually silent on e-ink
  (see the `project_disabled_button_eink` rule). They go quiet instead.
- **The pager keeps its space** (`INVISIBLE`, not `GONE`) at one page — the list is measured against
  the room it can actually keep, so a pager appearing later would clip rows made to fit.
- **Rows are measured against the real band**, not assumed uniform.
- **Group headers repeat** at the top of a continuation page.
- **The current page survives a refresh** (coerced into range) — ticking a task off must not throw
  the reader back to page 1.
- No `RecyclerView` (not a dependency); rows are inflated into a plain vertical `LinearLayout` and
  the list is rebuilt on refresh. Pagination is what bounds that cost.
