# Paper — Extensions arc 5: the Contents (table of contents from the Heading extension)

> **This file is the project's memory across sessions for arc 5.** Context is cleared between
> phases. Everything a fresh session needs — decisions, non-goals, the contract change, per-phase
> tasks, tests, status — is here or in the files this document points at. If it isn't written down
> here (or in the repo / project memory), it doesn't exist. **Read this file top to bottom at the
> start of every session**, after `PAPER_PLAN.md` (v0), `PAPER_EXTENSIONS_PLAN.md` (arc 1),
> `PAPER_NAMING_PLAN.md` (arc 2), `PAPER_RECOGNITION_PLAN.md` (arc 3), `PAPER_OBJECTS_PLAN.md`
> (arc 4 — the arc this one extends; its Locked decisions, Architecture and rules 18–23 all still
> hold) and both `CLAUDE.md` files. `docs/extensions.md` is the subsystem reference all arcs write
> into; `docs/notebook.md` gains a section in this arc.
>
> **Status: C0 ⬜ · C1 ⬜ · C2 ⬜ — planned 2026-08-18, not started.**

## Why

Arc 4 put the first non-ink object on the page — the heading — and settled how an extension
contributes UI without drawing over the paper. The original Notesprout builds a **table of
contents** from a notebook's headings (`apps/notesprout_android`, `toc/TocDialog` +
`toc/TocRepository`, `docs/mainactivity-and-recents.md` §"Table of Contents (TOC)"): a collapsible
H1→H2→H3 tree of every heading in the notebook, page numbers beside each, the current page's entry
highlighted, tap = go to that page. Arc 5 gives Paper the same behaviour **as an enhancement of the
Heading extension** — without breaking the arc-4 boundary: the core stores heading rows but never
parses their payload (rule 19), so the entries have to come from the provider. The provider point
gains one appended, batched, pure method — `describeOutline` — the Heading extension answers it with
the stripped words + level, and the core gathers, sorts, builds the tree and draws the **Contents**
screen itself (rule 20). The point stays generic: a future Text / Link object can contribute outline
entries the same way, or answer "not an outline item".

That is **one compatible contract change** (`OutlineEntry` + `IObjectProvider.describeOutline`,
`API_VERSION` stays 1 — the first time the versioning rule "a method appended at the end keeps the
version; the host tolerates old extensions" is actually exercised), **one Heading-extension change**,
and on the host a **Contents dialog** (sidebar / full-screen by width, paginated, tree with +/−
toggles, current entry highlighted), a **top-bar button** and a **one-finger swipe-down gesture** to
open it, plus the row read + tree builder behind them. No new extension, no schema change, no
g-paper change.

---

## Working protocol

Identical to `PAPER_OBJECTS_PLAN.md` §"Working protocol" — each phase in a **fresh session**:

1. **Phase start (no-assumption QA):** read this file (all of it), the five earlier plans' Locked
   decisions + Architecture + Appendices, `docs/extensions.md`, `docs/notebook.md`, `docs/data.md`,
   the root `CLAUDE.md`, and `apps/notesprout_paper/CLAUDE.md`. Confirm the next `⬜` phase with the
   user, flip it to `🔄`, then **ask the phase's "Questions to resolve at phase start"** one at a time
   in the wizard (option-select) format before writing code — recommended default first, plus
   "Other". Do not assume answers. If a new ambiguity surfaces mid-phase that would materially change
   the work, stop and ask. **Wizard trap:** when the user asks a clarifying question inside the
   wizard, answer it in plain text first (the dialog hides the answer), then re-ask.
2. **Code** — auto mode; inline; **frugal with agents** (Model note); no Gradle dependency beyond
   Appendix B; deliverables **exactly as written** — no added scope, no "improving" adjacent code, no
   scaffolding for later phases (the one debug probe of C0 is named there and removed in C2).
3. **Test** — `./gradlew testDebugUnitTest` (all modules), then build + install the debug APKs on
   the requested **test devices** and hand the user the phase's numbered on-device checklist (copy
   it, don't invent). EPD overlays are invisible to screencap — the user verifies by eye and reports;
   the automated per-device agent run covers what adb + uiautomator can reach.
4. **Fix → test again** until every test passes (JVM + device checklist).
5. **Docs / memory / CLAUDE.md** — `apps/notesprout_paper/CLAUDE.md` (standing rules + build facts
   only), `docs/extensions.md` (+ `docs/notebook.md` where named), this file's status marker +
   **Outcome** note, and the project memory
   (`~/.claude/projects/-Users-gregmarine-git-Notesprout/memory/project_paper_contents.md` + its
   `MEMORY.md` index line).
6. **Commit & push** on `paper`. Then the user runs `/clear`.

**Status markers:** `⬜ Not started` · `🔄 In progress` · `🧪 Awaiting device verification` ·
`✅ Complete (commit <hash>)`. Update the marker **the moment the state changes**.

**Test devices** (user verifies by eye; always `-s <serial>`; never install on a device the user didn't
ask for; offline → say so and wait). All five extensions installed; the ML Kit model present.

| Nickname | Device | Serial | Engine | Portrait width |
|---|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` | ~749 dp (1404 px @ 300) |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` | ~749 dp (1404 px @ 300) |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` | ~914 dp (1600 px @ 280) |

**Every test device is ≥ 480 dp wide** → all three get the *sidebar* form (Q3). The full-screen
form is verified by its pure width rule (JVM) and once by eye on MIP11 under a temporary
`adb shell wm size` override in C1's Claude-side run (`wm size reset` afterwards) — recorded in C1.

**Device traps carried forward (arc 4):** NA5C — re-enable the app **and** every extension after
install, confirm `pm list packages -d`; lasso / ink unreachable by adb (Onyx raw callback), finger
gestures reachable. SNN — `input stylus` reads as a finger (Android 11), `input text` swallowed, IME
invisible to uiautomator. MIP11 — `log.tag` resets to `I` by itself: per-tag `setprop
log.tag.<TAG> DEBUG` before every log read; adb stylus lassos need a dense 25-point loop in one
shell command. **adb cannot do multi-finger gestures on any device** (undo / redo / two-finger insert
are always user items); the one-finger swipe-down **is** adb-reachable (`input swipe`, finger).

**Model note (user decision, unchanged from arc 4): Fable 5 runs every phase.** Within a phase the
work is inline; agents only for (a) read-only exploration when a survey spans many files (`Explore`,
Sonnet) and (b) the automated per-device verification run — **one Sonnet agent per device, at most
two at a time**, given the phase's checklist verbatim plus the traps above. Never an agent for code
that changes a verified path. **Written for Opus too:** every phase names its files, signatures,
constants and failure texts; anything genuinely undecided is a phase-start question, not an inference.

---

## Locked decisions (planning Q&A, 2026-08-18)

| Area | Decision |
|---|---|
| Who produces the entries (Q1) | **`describeOutline` appended to `IObjectProvider`** — a *compatible* AIDL change (method appended at the end; `API_VERSION` stays 1; a provider that doesn't implement it contributes nothing and nothing breaks). Batched per type: the core hands a provider all its objects' payloads of one `typeId` and gets back one `OutlineEntry(label, level)` per payload (`level 0` = not an outline item). The Heading extension returns the stripped words + level 1–6. Generic: any object type can contribute. **The core builds and draws the list** (rule 20). The alternatives — a separate `OUTLINE_PROVIDER` point, or an extension-owned Contents screen through the off-paper escape hatch — were declined; and the core **still never parses a payload** (the "core counts the `#`s itself" shortcut was explained and not taken). |
| Entry point (Q2) | **Both:** a **top-bar button** (Tabler `list`, the original's `ic_toc` glyph, hint "Contents") **present only while an installed object provider answers `describeOutline`** (like the Naming entry points, absent without a namer) **and** the original's **one-finger swipe-down on the paper** (vertical-dominant, ≥ 30 % of the height + fling, or ≥ 50 %; pen-activity-gated like every finger gesture; stands down while a selection is active). The gesture does nothing — silently — when no provider contributes. |
| Presentation (Q3) | **Exact parity with the original's width rule:** below **480 dp** of window width the Contents is **full screen** (white, back arrow top-left); at ≥ 480 dp it is a **left sidebar 60 % wide** over a transparent scrim that dismisses on tap, no back arrow. **One layout XML** — the width branch is code (`ContentsDialog.fullScreen`), so Paper's one-layout-per-screen rule holds. Header "Contents" (20 sp bold) + 1 dp divider; body = rows; footer = the library's pager (`|<` `<` `n / N` `>` `>|`, `INVISIBLE` with one page); immersive + `TopGuard.applyRootPadding`. Empty → "No headings yet" centred in the body. |
| Structure (Q4) | **Collapsible tree, six levels, orphans attached — not skipped.** The original's tree (opens collapsed to the roots; +/− toggle per row with children, `INVISIBLE` on leaves so columns align; expansion state in memory only, never persisted) extended to H1–H6 with **one deviation:** a heading with no parent level before it attaches under the nearest *shallower* heading before it (or becomes a root) instead of vanishing — nothing the user wrote is hidden. The current page's entry is highlighted, its ancestors pre-expanded, and the list opens on the page that holds it (the original's `resolveHighlightNodeId` rule: the last entry whose page ≤ the current page; if collapsed away, its nearest visible ancestor). |
| Tap (Q5) | **Navigate to the page only** (parity): dismiss → `navigateTo(pageIndex)` (no-op when already there); nothing is selected, nothing highlighted on the page. (Navigate + select the heading was declined; recorded under Deferred.) |
| Gathering (Q6) | **Rebuilt on every open, one bind per provider, capped.** One blob-free DAO read of every live object row → grouped by provider identity → **one `describeOutline` bind per outline-capable provider** (payloads batched per type, chunked at `MAX_OUTLINE_BATCH` / `MAX_OUTLINE_BATCH_CHARS` per call) → items sorted (page, then y, then x) → hard cap `MAX_OUTLINE_ENTRIES` (2 000; the first 2 000 in document order are shown and the footer says so) → tree. No cache, nothing to invalidate. Objects whose provider is absent / disabled / not outline-capable are simply not listed. |
| Rows (Q7) | **The original's row as-is:** `[+/− toggle] [page number, 52 dp, 20 sp bold] [1 dp divider] [label 20 sp, single line, ellipsize END]`, whole row indented `(level − 1) × 16 dp`, row height 68 dp (`ROW_HEIGHT_DP`), 1 dp separator, current entry = **5 dp inkBlack bar at the row's right edge** (`bg_contents_active_entry`); text size does **not** vary by level (indent only). Toggle tap targets take `toolbar_button_size`. |
| Phases (Q8) | **Three phases C0 · C1 · C2** (below), each a fresh session, verified on SNN + NA5C + MIP11. |
| Names + models (Q9) | UI word **"Contents"**; this file `PAPER_CONTENTS_PLAN.md` (arc 5); parcelable **`OutlineEntry(label, level)`**; method **`describeOutline`**; caps `MAX_OUTLINE_ENTRIES` 2 000 / `MAX_OUTLINE_LABEL_CHARS` 200 (+ `MAX_OUTLINE_LEVEL` 6, `MAX_OUTLINE_BATCH` 200, `MAX_OUTLINE_BATCH_CHARS` 100 000 — Appendix A); icon Tabler `list` (`IconNames.LIST` joins the catalog); core classes `ContentsDialog` + pure `OutlineTree` (+ `ContentsFlow`, `ContentsSource`, `OutlineCaps` named below). **Fable 5 every phase, agents ≤ 2** (Sonnet exploration / device runs). |
| Trust / artifacts / version | Unchanged: same-signature only, debug-only APKs, no app version bump, `ExtensionContract.API_VERSION` stays **1**, no `.soil` change (`SOIL_VERSION` 1), g-paper stays **0.1.1**. |

## Deferred (recorded 2026-08-18, not built in this arc)

- **Navigate + select the heading** from a Contents tap (`paper.setSelection` after the page loads so
  the toolbar shows H with the level marked) — declined for parity; one line to add if wanted.
- **Persisted expansion state** (per notebook, which nodes were open) — the original keeps it in
  memory only; so does this arc.
- **Outline entries from other object types** (Text / Link) — the point supports it (`level 0` = not
  listed); nothing else contributes until those extensions exist.
- **A page-name rule** (the original names Page-Index / link-picker cards from the page's top-left H1
  — `PageHeadingNames`) — Paper has no Page Index; when it does, that rule is a *separate* consumer of
  `describeOutline`, never a copy of the tree (the original merged and then un-merged the two).
- **Export / PDF bookmarks** from the outline — no export in Paper v0.
- **Live update while the Contents is open** — a modal snapshot, like the original.
- **A cache** of outline entries across opens — only if a real notebook makes the rebuild slow (Q6).
- **A "supports outline" flag in a manifest / describe call** instead of the probe — the probe (one
  blank payload → a one-element reply) is enough while every provider is first-party; revisit with
  third-party trust.

## Non-goals for this arc (do not build, do not scaffold "for later")

No new extension · no new extension point · no `.soil` change · no g-paper change · no Page Index ·
no export · no persisted state of any kind (no prefs, no rows, no store) · no core parsing of any
payload · no change to how headings are created / rendered / edited · no thumbnails for
"unrecognized headings" (every Paper heading has text) · no colour · no release signing · no version
bump · no `kotlin-parcelize` · no new Gradle dependency anywhere (Appendix B).

---

## Architecture

### Module layout (delta)

```
apps/notesprout_paper/
├── PAPER_CONTENTS_PLAN.md          this file
├── docs/extensions.md              gains: OutlineEntry + describeOutline in the contract table / AIDL / parcelables,
│                                   §"ObjectProvider" + §"The Heading extension" + host behaviour paragraphs,
│                                   the versioning-rule note ("first compatible change, how the host tolerates
│                                   an old provider"), audit rows 25–27, rule 24 under "Adding an object point"
├── docs/notebook.md                gains: §"Contents (arc 5)" — flow, dialog, gesture, failure table
├── extension-api/src/main/
│   ├── aidl/…/extension/OutlineEntry.aidl            (new)  +  IObjectProvider.aidl (describeOutline appended)
│   └── kotlin/…/extension/OutlineEntry.kt            (new)  +  ExtensionContract (+ 5 constants), IconNames (+ LIST)
├── ext-heading/…/ext/heading/
│   ├── ObjectProviderService.kt    describeOutline
│   └── HeadingText.kt              outlineOf(payload)
├── app/…/notesprout/
│   ├── core/IconCatalog.kt         + LIST → ic_list ; res/drawable/ic_list.xml, ic_minus.xml (Tabler, stroke 2)
│   ├── data/soil/SoilDao.kt        + liveObjectsAll()
│   ├── extension/OutlineCaps.kt    (new, pure)  +  ObjectProviderClient (describeOutline / supportsOutline)
│   └── notebook/
│       ├── OutlineTree.kt          (new, pure)  — items → tree, visible rows, highlight, paging math
│       ├── ContentsSource.kt       (new, IO)    — rows → providers → items → OutlineTree
│       ├── ContentsFlow.kt         (new)        — busy guard, drain, gather, dialogs, dismiss → navigate
│       ├── ContentsDialog.kt       (new)        — the Dialog (width rule, header, rows, pager, toggles)
│       ├── ObjectProviders.kt      Contribution.outline + hasOutline (probe at load)
│       ├── PageGestures.kt         Listener.onSwipeDown + the vertical evaluator
│       └── NotebookActivity.kt     btnContents wiring, gesture wiring (≤ ~20 lines)
└── res/layout/dialog_contents.xml, item_contents_entry.xml ; res/drawable/bg_contents_active_entry.xml
```

Dependency direction unchanged. `:ext-heading` still depends on `:extension-api` only.

### Contract additions (`:extension-api`) — exact

`ExtensionContract` gains:

| Constant | Value | Meaning |
|---|---|---|
| `MAX_OUTLINE_LABEL_CHARS` | `200` | longest outline label a provider may return (host truncates inward; provider re-checks) |
| `MAX_OUTLINE_LEVEL` | `6` | `OutlineEntry.level` is `0` (not listed) or `1..MAX_OUTLINE_LEVEL` |
| `MAX_OUTLINE_BATCH` | `200` | most payloads in one `describeOutline` call |
| `MAX_OUTLINE_BATCH_CHARS` | `100_000` | most payload chars, summed, in one call (Binder transaction budget — a payload may be up to `MAX_OBJECT_TEXT_CHARS`, so the host chunks by both) |
| `MAX_OUTLINE_ENTRIES` | `2_000` | host cap on the whole notebook's outline (document order; the rest is dropped and the footer says so) |

`IconNames` gains `LIST = "list"` (added to `ALL`; the core's Contents button uses the same drawable —
listed so an extension may reuse the glyph, like `TRASH`).

AIDL — **appended at the very end** of `IObjectProvider` (never reordered; the eight arc-4 methods
keep their transaction codes):

```aidl
// OutlineEntry.aidl (new)
package com.symmetricalpalmtree.notesprout.extension;
parcelable OutlineEntry;

// IObjectProvider.aidl — arc 5 / C0, appended after render():
    /** Outline (table-of-contents) entries for [payloads] of one of this provider's types — one
     *  OutlineEntry per payload, same order, same length: level 1..MAX_OUTLINE_LEVEL with a label
     *  ≤ MAX_OUTLINE_LABEL_CHARS, or level 0 (label ignored) for "not an outline item". Pure, ≤ 2 s;
     *  the host chunks at MAX_OUTLINE_BATCH / MAX_OUTLINE_BATCH_CHARS per call. A provider built
     *  before this method existed simply never receives it (the host tolerates the failure). */
    List<OutlineEntry> describeOutline(String typeId, in List<String> payloads);
```

Parcelable (hand-written, `@JvmField CREATOR`, write order fixed forever, tails may be appended):

- **`OutlineEntry(label: String, level: Int)`** — `writeString(label); writeInt(level)`.
  `requireValid()`: `level in 0..MAX_OUTLINE_LEVEL`, `label.length ≤ MAX_OUTLINE_LABEL_CHARS`
  (thrown at unmarshal → the whole reply is rejected by the host, see `OutlineCaps`). Convenience
  `OutlineEntry.NONE = OutlineEntry("", 0)`.

**Semantics the host enforces inward (`OutlineCaps`, pure, JVM-tested):** the reply must have
**exactly** the input's length — any other length, any exception (incl. the `RemoteException` /
malformed empty reply an **old provider** produces for an unknown transaction) means *this provider
does not answer the outline* for this call; labels are trimmed and cut to the cap, blank label with
level ≥ 1 → treated as level 0; level outside `0..MAX_OUTLINE_LEVEL` → 0. Nothing else is trusted.

**Old-provider tolerance (the versioning rule, exercised for the first time):** an `IObjectProvider`
built from the arc-4 AIDL has no transaction for `describeOutline`. The core never assumes; it
**probes** at provider load — `describeOutline(firstType, [""])` — and treats **only** a reply of
exactly one entry as "outline-capable"; an exception, an empty reply, or a reply of another length is
"not capable" (`Contribution.outline = false`, no button, gesture silent, no further outline calls).
C0 verifies this probe against the *pre-C0* Heading APK on one device (install the old
`ext-heading-debug.apk` from a build of the arc-4 head, run the probe, expect `outline=false`, no
crash), then the new one (`outline=true`).

### Extension side — `:ext-heading` (`NSE · Heading`)

- **`HeadingText.outlineOf(payload): OutlineEntry`** (pure, JVM-tested): `strip(payload)` folded and
  trimmed → blank → `OutlineEntry.NONE`; else `OutlineEntry(words.take(MAX_OUTLINE_LABEL_CHARS),
  levelOf(payload))`. Note `levelOf` already maps a malformed payload to 1 (a heading is never level 0
  unless its words are blank).
- **`ObjectProviderService.describeOutline(typeId, payloads)`**: `HostCallerCheck.enforce` first;
  `typeId != "heading"` → `IllegalArgumentException`; `payloads.size > MAX_OUTLINE_BATCH` or summed
  length `> MAX_OUTLINE_BATCH_CHARS` → `IllegalArgumentException`; else `payloads.map(HeadingText::outlineOf)`.
  Debug log: **count + duration only** — never a label.

### Host side (`:app`)

**`extension/`**

- **`OutlineCaps`** (pure, JVM-tested): `chunk(payloads: List<String>): List<List<String>>` (greedy:
  a chunk closes at `MAX_OUTLINE_BATCH` items or when adding the next payload would pass
  `MAX_OUTLINE_BATCH_CHARS`; a single over-long payload — impossible after `MAX_OBJECT_TEXT_CHARS`,
  but checked — becomes its own chunk and is truncated to the batch cap on the way out);
  `sanitize(reply: List<OutlineEntry>?, expected: Int): List<OutlineEntry>?` (null / wrong length →
  null; per entry as above); `isCapableReply(reply, expected = 1)`.
- **`ObjectProviderClient`** gains:
  - `describeOutline(typeId: String, payloads: List<String>): List<OutlineEntry>?` — **one bind**,
    the chunks called in sequence inside it (`call(CALL_TIMEOUT_MS × chunks.size)`), each chunk's
    reply through `OutlineCaps.sanitize`; any chunk failing → the whole result is `null` (the
    provider's objects are absent from the Contents this time). Outward payloads pass through the
    existing `outPayload` cap. Log tag `ObjectProviderClient` — counts + durations, never a payload
    or label.
  - `describeOutlineAll(byType: Map<String, List<String>>): Map<String, List<OutlineEntry>>?` — the
    same in **one bind across all of the provider's types** (this is what `ContentsSource` calls);
    null if any type failed.
  - `supportsOutline(firstType: String): Boolean` — the probe above (own bind, `CALL_TIMEOUT_MS`;
    every failure → false, logged at debug as `outline probe: unsupported`).

**`notebook/`**

- **`ObjectProviders`**: `Contribution` gains **`outline: Boolean`**; `load` runs the probe after
  `describeActions` for every provider that described ≥ 1 type (a provider that failed its describe
  calls — "known but undescribed" — is `outline = false` until the resume retry); **`hasOutline`** =
  any contribution's `outline`; **`outlineProviders`** = the keys with `outline = true`. The resume
  `signature` is unchanged (the extension set decides it, not the probe).
- **`SoilDao.liveObjectsAll(): List<SoilObjectEntity>`** — `SELECT * FROM notebook WHERE type =
  'object' AND deletedAt IS NULL` (objects carry no blob; a page delete soft-deletes its children, so
  this is already the live set — `ContentsSource` still drops rows whose `parentId` is not a live
  page as a guard).
- **`OutlineTree`** (pure, JVM-tested — `OutlineTreeTest`):
  - `data class Item(objectId, pageIndex, x, y, label, level)`; `class Node(id, pageIndex, label,
    level, children: MutableList<Node>, parent: Node?)`.
  - `build(items): List<Node>` — items **sorted (pageIndex, y, x)** first (the caller passes them
    unsorted; sorting lives here so it is tested), then one pass with a per-level "last node" stack:
    a node of level *L* attaches to the deepest open node whose level < *L* (levels between are
    skipped — the orphan rule of Q4), else it is a root; opening a node clears every deeper slot.
    Parents persist across page boundaries (the original's behaviour).
  - `visible(roots, expanded: Set<String>): List<Node>` — pre-order, descending only into expanded
    nodes.
  - `highlight(all: List<Node> in document order, currentPageIndex, expanded): String?` — the last
    node with `pageIndex ≤ currentPageIndex` (none → null); if not visible under `expanded`, its
    nearest **visible** ancestor.
  - `ancestorsOf(node): List<String>` (ids, root first) — what the dialog pre-expands.
  - `pageOf(indexInVisible, itemsPerPage)` = `index / itemsPerPage`; `pageCount(n, itemsPerPage)` =
    `max(1, ceil(n / itemsPerPage))`.
- **`ContentsSource`** (IO): `gather(context, session, providers): Result` — `writer.drain()` first
  (a heading created a moment ago must be in its row) → `dao.liveObjectsAll()` → keep rows whose
  `parentId` is one of `session.pages` and whose `style` parses (`parseIdentity`) → group by
  provider key (package) — **only keys in `providers.outlineProviders`** — then by `typeId` →
  per provider `ObjectProviderClient.describeOutlineAll(byType)` (providers in the order
  `providers.contributions` lists them) → `null` from a provider → `Result.failed(providerLabel)`
  (**stop; the flow shows the failure dialog — nothing opens**) → entries with `level ≥ 1` become
  `OutlineTree.Item`s (`pageIndex` from `session.pages`, `x`/`y` from the row) → sort + cap at
  `MAX_OUTLINE_ENTRIES` (**`truncated = true`**) → `Result.ok(roots = OutlineTree.build(items),
  count, truncated)`. Empty rows / no outline providers → `Result.ok(empty)`. Log: object count,
  provider count, entry count, duration — never a label.
- **`ContentsFlow`** (`activity`, `session`, `providersSupplier`, `currentPageIndex`, `navigate:
  suspend (Int) -> Unit`): `open()` — `busy` guard (a second tap / swipe while gathering is
  dropped) → `paper.releaseRender()` → `lifecycleScope.launch { IO gather }` → on Main: activity
  finishing / `closing` → return; `Result.failed(label)` → `Dialogs.problem(contents_title,
  objects_provider_failed(label))`; `Result.ok` → `ContentsDialog(activity, result, currentPageIndex)
  { pageIndex -> if (pageIndex != current) launch { navigate(pageIndex) } }.show()`. **While the
  dialog is up the whole paper is one exclusion rect** (as the "Opening…" popup does — the Onyx raw
  pen path bypasses the window stack; C1 phase-start Q1 confirms) and the chrome rects come back on
  dismiss (`pushExclusions()`).
- **`ContentsDialog`** (`res/layout/dialog_contents.xml` + `item_contents_entry.xml`): an
  `android.app.Dialog` (`Theme.Notesprout`-derived, `FEATURE_NO_TITLE`, `MATCH_PARENT` × `MATCH_PARENT`,
  transparent window background, `windowAnimationStyle = @null`), immersive flags applied **before and
  after `show()`** (a Dialog's own window resets bar visibility on show — the original's note), root
  `TopGuard.applyRootPadding`.
  - **Width rule (pure, JVM-tested in `OutlineTreeTest` or its own `ContentsLayoutTest`):**
    `fullScreen = windowWidthDp < CONTENTS_SIDEBAR_MIN_DP (480)`. Full screen: panel `MATCH_PARENT`,
    white, back arrow (`ic_arrow_left`, `Widget.Notesprout.ToolbarButton`, hint "Back") visible.
    Sidebar: panel width `round(0.60 × windowWidthPx)`, gravity start, white with a 1 dp inkBlack
    right border, the rest of the root a **transparent scrim** whose tap dismisses; back arrow
    `GONE`.
  - **Header** row (`toolbar_bar_thickness`, 12 dp padding): [← full-screen only] "Contents"
    (`contents_title`, 20 sp bold inkBlack); 1 dp divider.
  - **Body**: a vertical `LinearLayout` of rows; `itemsPerPage` = `max(1, floor(bodyHeight /
    (ROW_HEIGHT_DP + ROW_SEPARATOR_DP)))` measured once via `OnGlobalLayoutListener` after the first
    layout (device-independent, no estimate); the rows re-rendered on every page / toggle change.
    Empty result → `contents_empty` "No headings yet" centred (15 sp inkBlack), pager `INVISIBLE`.
  - **Row** (`item_contents_entry.xml`): `btnToggle` (`AppCompatImageButton`, ToolbarButton style,
    `ic_plus` collapsed / `ic_minus` expanded, hint `cd_expand` / `cd_collapse`; **`INVISIBLE` on a
    leaf**) · `pageNumber` (52 dp, 20 sp bold, `"${pageIndex + 1}"`) · 1 dp inkBlack vertical
    divider · `label` (20 sp inkBlack, single line, `ellipsize=end`); the row's `paddingStart` +=
    `(level − 1) × 16 dp`; `minHeight = 68 dp`; a 1 dp separator view under every row; highlighted
    row background `bg_contents_active_entry` (layer-list: 5 dp inkBlack bar, `gravity=right`).
    Row tap → `dismiss()` → `onPageSelected(pageIndex)`. Toggle tap → flip `expanded` → re-render
    **the same list page** (clamped to the new page count) — the dialog stays.
  - **Opening state:** `expanded = ancestorsOf(highlightNode)`; `listPage = pageOf(index of the
    highlighted row in `visible`, itemsPerPage)` (0 when nothing is highlighted).
  - **Footer** = the library's pager (`ic_page_first` / `ic_page_prev` / label `n / N` / `ic_page_next` /
    `ic_page_last`, `INVISIBLE` with one page; a tap at a bound is a no-op — never a disabled look, the
    e-ink rule) + a small `contents_truncated` line ("Showing the first %1$d headings") **only when
    truncated**.
  - Every button tap → `paper.releaseRender()` is **not** needed inside the dialog (its window is not
    the paper); the flow released once before `show()`.
- **Entry points (`NotebookActivity`)**:
  - `btnContents` (`AppCompatImageButton`, ToolbarButton style, `ic_list`, `cd_contents` "Contents")
    in `topBarRow` after the lasso button behind a 12 dp spacer (C1 phase-start Q2 confirms the
    place); **`GONE` until `providers.hasOutline`**, re-evaluated at the end of every `loadProviders`
    (so an enable / disable seen on resume shows / hides it). Inside `topBar` → the existing exclusion
    rect and chrome `releaseRender()` cover it. Tap → `contentsFlow.open()`.
  - **`PageGestures.Listener.onSwipeDown()`** — a **one-finger** swipe evaluated at `ACTION_UP` beside
    the flip: `verticalQualifies(dx, dy)` = `|dy| > |dx| && |dy| ≥ PAGE_SWIPE_MIN_DISTANCE_FRAC ×
    height`, `qualifiesVerticalFling(vy, dx, dy)` = the flip's rule on the vertical axis
    (`|vy| ≥ minFlingVel` or `|dy| ≥ PAGE_SWIPE_LONG_DISTANCE_FRAC × height`), **`dy > 0`** only (a
    swipe *up* is reserved and does nothing); the same `gateOpen()` (pen idle, no selection), never
    on a stylus, never from a down over chrome. Host: `onSwipeDown` → `if (providers.hasOutline)
    contentsFlow.open()` else nothing (Q2 — silent).
  - Navigation from a tap = the existing `navigateTo(index)` (under `pageOps`, hides the selection
    toolbar, reloads strokes + objects, schedules the render pass) — no new path.
- **`NotebookActivity` line cap:** the wiring is ≤ ~20 lines. If the file crosses 800, C1 moves
  `pushExclusions` + `overChrome` + the rect helpers into a `notebook/NotebookChrome.kt` (a pure
  move, no behaviour change) rather than writing a reason.

### Rules followed and added

Rules 1–5 (point), 18–23 (object point) apply. Added to `docs/extensions.md` §"Adding an object
point" in C2:

24. **An outline is a description, not a parse.** The core never derives structure from a payload;
    a provider *describes* each object's outline entry (label + level, or none) and the core sorts,
    nests, pages and draws under its own rules. A provider that predates the method, is absent, or
    fails, contributes nothing and nothing else changes (rows 25–27).

---

## Phases

### Phase C0 — Contract, the Heading extension, client, rows, tree (no UI)
**Status:** ⬜ Not started

**Goal:** `OutlineEntry` + `describeOutline` exist in the contract; `NSE · Heading` answers them;
the host can ask (`ObjectProviderClient.describeOutline` / `describeOutlineAll` / `supportsOutline`),
validates the reply (`OutlineCaps`), knows which providers are outline-capable
(`Contribution.outline` / `hasOutline` from the load probe), reads every live object row
(`SoilDao.liveObjectsAll`) and builds the tree (`OutlineTree`) — all JVM-tested; on-device the two-hop
call is proven by a **debug ⋯ "Probe contents"** item (counts + durations only; **removed in C2**)
that runs the C1 gather path (`ContentsSource`) and logs entry count / provider count / duration and
the tree's root count. Old-provider tolerance verified against the pre-C0 Heading APK. Nothing
user-visible in release.

**Questions to resolve at phase start** (one at a time; recommended default first):
1. `level 0` = "not an outline item" inside a same-length reply (rec.; no null elements in the
   typed list, no second parcelable) — confirm.
2. Batch caps `MAX_OUTLINE_BATCH 200` / `MAX_OUTLINE_BATCH_CHARS 100 000` per call, one bind per
   provider looping the chunks (rec.) — confirm; and label cap 200 (rec.) — confirm.
3. The capability probe = `describeOutline(firstType, [""])` at provider load, "capable" ⇔ a reply
   of exactly one entry (rec.) — confirm; and its result does **not** join the resume signature
   (rec.) — confirm.
4. On a provider whose outline call fails mid-gather: the Contents does **not** open and the
   `objects_provider_failed` dialog names the extension (rec. — honest, "failure changes nothing") /
   open with that provider's entries missing?

**Deliverables**
1. `:extension-api`: `OutlineEntry.aidl` + `OutlineEntry.kt` (`requireValid`, `NONE`, JVM test
   `OutlineEntryTest`: valid, level 7 rejected, over-long label rejected, round trip);
   `IObjectProvider.aidl` `describeOutline` **appended last**; `ExtensionContract` five constants;
   `IconNames.LIST` (+ `ALL`).
2. `:ext-heading`: `HeadingText.outlineOf` (+ `HeadingTextTest` cases: `## Meeting notes` → ("Meeting
   notes", 2); `#` only → NONE; malformed → level 1; label cut at 200; folded newlines);
   `ObjectProviderService.describeOutline` (caps, `IllegalArgumentException`, log count + duration).
3. `:app` `extension/`: `OutlineCaps` (+ `OutlineCapsTest`: chunking by count and by chars, wrong-length
   reply → null, blank label → level 0, level clamp, label trim/cut); `ObjectProviderClient.describeOutline`
   / `describeOutlineAll` / `supportsOutline`; `core/IconCatalog` `LIST → ic_list` + `res/drawable/ic_list.xml`
   and `ic_minus.xml` (Tabler `list`, `minus`, 24 dp, stroke 2, round caps — fetch from tabler-icons
   `main` like H2).
4. `:app` `data/soil/SoilDao.liveObjectsAll()`; `notebook/OutlineTree` (+ `OutlineTreeTest`: sort
   (page, y, x); H1→H2→H3 nesting; orphan H3 before any H2 attaches to the H1; orphan H2 with no H1 is
   a root; parent persists across pages; a new H1 clears deeper slots; `visible` collapsed = roots;
   `highlight` last-≤-page rule + nearest visible ancestor + null when the first entry is after the
   current page; `ancestorsOf`; `pageOf` / `pageCount`); `notebook/ContentsSource` (IO gather, as
   specified — built here because the probe exercises it; C1 adds nothing to it beyond wiring);
   `ObjectProviders` `Contribution.outline` + `hasOutline` + `outlineProviders` (probe in `load`).
5. Debug ⋯ **"Probe contents"** (`NotebookDebugMenu`, present in debug builds only): runs
   `ContentsSource.gather` and logs `objects=<n> providers=<n> entries=<n> roots=<n> truncated=<b> in
   <ms>` + a "Probe done" toast; on `Result.failed` logs the label. Removed in C2.
6. Docs: `docs/extensions.md` contract table / AIDL / parcelables / §"ObjectProvider" (`describeOutline`
   semantics + the old-provider tolerance paragraph under §"Versioning rules") / §"The Heading
   extension"; `CLAUDE.md` build facts unchanged (no new module).

**Tests**
- JVM green (all seven modules); `assembleDebug`; `:app:assembleRelease` compiles.
- Claude-side per device (app + all five extensions; NA5C enable dance): install; ⋯ → Probe contents
  on a notebook with a few headings across pages → `logcat -s ObjectProviderClient ObjectProviderService
  ContentsSource NotebookDebugMenu` shows one `describeOutline` bind per open (chunks = 1), the
  provider's `describeOutline n=<count> in <ms>` line nested inside, `entries` = the heading count,
  `roots` as expected, no label / payload text in any line, binds = unbinds; **old-provider check on
  MIP11:** install `ext-heading-debug.apk` built from the arc-4 head (`git worktree` at `b1d9b37`,
  `./gradlew :ext-heading:assembleDebug`) → reopen the notebook → `logcat -s ObjectProviders` shows
  `outline probe: unsupported` for the Heading, Probe logs `providers=0 entries=0`, **no crash**;
  reinstall the new APK → resume → `outline=true`, entries back.
- **User device checklist:** (1) Settings → Apps shows "NSE · Heading Dev" still (reinstalled);
  (2) the arc-4 heading story unchanged on each device: lasso → H → H2 → heading; re-size; edit;
  undo — a 60-second regression; (3) Templates / Naming / Recognize page unchanged.

**Close-out:** status ✅ + Outcome (**C2 review base = the commit before C0's first commit** — write
its hash here); docs; memory; commit + push.

---

### Phase C1 — The Contents screen, the button, the gesture, navigation — devices
**Status:** ⬜ Not started

**Goal:** the user story works on all three devices: tap the top-bar **list** button (or swipe one
finger down the paper) → the Contents opens (60 % sidebar on every test device; full screen below
480 dp) with the notebook's headings as a collapsed H1–H6 tree, page numbers, the current page's
entry highlighted with its ancestors open and the list on that page; +/− expands / collapses in place;
the pager turns pages of rows; tapping an entry closes the Contents and turns to that page; with no
outline-capable provider there is no button and the swipe does nothing; failures are honest dialogs.

**Questions to resolve at phase start:**
1. While the Contents is showing, exclude the whole paper (one full-paper exclusion rect, like the
   "Opening…" popup; chrome rects restored on dismiss) — rec. yes (the Onyx raw pen path bypasses the
   window stack) — confirm.
2. Button placement: after the lasso button behind a 12 dp spacer (rec.) / at the far end of the row
   (before the debug ⋯)?
3. Highlight when the current page precedes every heading: no highlight, list opens on page 1 (rec.,
   the original) — confirm; and when the current page *is* a heading's page with several headings on
   it: the last of them (rec., the original's rule) — confirm.
4. Swipe-down gates: `≥ 0.30 × height` + fling, or `≥ 0.50 × height`, vertical-dominant, one finger,
   `dy > 0` (rec. — the original's `evaluateSwipeDownToc` mirrored onto Paper's flip constants) —
   confirm; and should the swipe be refused while the Contents is already open / gathering (rec.
   yes — the `busy` guard) — confirm.

**Deliverables**
1. `notebook/ContentsDialog` + `res/layout/dialog_contents.xml` + `res/layout/item_contents_entry.xml`
   + `res/drawable/bg_contents_active_entry.xml`; the width rule as a pure function (JVM-tested);
   strings `contents_title`, `contents_empty`, `contents_truncated`, `cd_contents`, `cd_expand`,
   `cd_collapse`, `cd_back` (exists) — Appendix A.
2. `notebook/ContentsFlow` (busy guard, release, gather on IO, failure dialog, dialog, exclusion
   swap, dismiss → `navigateTo`).
3. `NotebookActivity`: `btnContents` in `activity_notebook.xml` + visibility from `providers.hasOutline`
   after every `loadProviders`; `PageGestures.Listener.onSwipeDown` + the vertical evaluator in
   `PageGestures` (constants reuse `PAGE_SWIPE_MIN_DISTANCE_FRAC` / `PAGE_SWIPE_LONG_DISTANCE_FRAC` on
   the height); the two entry points call `contentsFlow.open()`; `NotebookChrome` extraction only if
   the 800-line cap is crossed.
4. Docs: `docs/notebook.md` §"Contents (arc 5)" (flow, dialog, gesture, failure table, the
   full-paper exclusion); `docs/extensions.md` host-behaviour paragraph.

**Tests**
- JVM green; builds.
- Claude-side per device: **finger swipe-down by adb** (`input swipe x y1 y2 <ms>` on the paper, one
  finger) opens the Contents (uiautomator dump shows "Contents" + rows); the button appears in the
  top-bar dump when the Heading is installed and is gone after `pm disable-user` of the Heading +
  resume (and the swipe then does nothing); row / toggle / pager taps by coordinates from the dump;
  a row tap → the bottom strip's `n / N` changes; `logcat` — one `describeOutline` bind per open, no
  label text, binds = unbinds, no `SecurityException`; **MIP11 only:** `adb shell wm size 900x1600`
  (→ < 480 dp) → open → full-screen form with the back arrow (screencap) → `wm size reset`. Log
  hygiene on SNN.
- **User device checklist** (app + all five extensions):
  1. A notebook with headings on several pages (write / convert a few: an H1 on page 1, H2s on pages
     1–2, an H3 on page 3, an H1 on page 4): the top bar shows a **list** button after the lasso;
     tap it → the Contents slides in as a left sidebar (60 % wide, paper visible to its right); header
     "Contents"; rows collapsed to the H1s with `+` toggles, page numbers at the left of each label.
  2. Tap `+` on the first H1 → its H2s appear indented under it, `−` on the H1; tap `+` on an H2 with
     an H3 → deeper indent; `−` collapses in place; the Contents never closes on a toggle.
  3. Being on page 2 when opening: the H2 on page 2 (or the last heading before it) is highlighted
     with a black bar at its right edge, its H1 already open, and the list is on the page that shows
     it. On a page after all headings: the last heading is highlighted. On page 1 with the H1 on it:
     that H1.
  4. Tap a row → the Contents closes and the notebook is on that page (`n / N` in the strip); tap the
     row of the current page → closes, nothing moves.
  5. Tap outside the sidebar → closes, nothing moves. Back button → same.
  6. Enough headings to overflow the body (write ~15 H1s across pages, or expand everything) → the
     pager appears (`|<` `<` `n / N` `>` `>|`); it turns pages of rows; at a bound a tap does nothing.
  7. **One-finger swipe down** on the paper (pen away) → the Contents opens; a swipe up does nothing;
     a swipe down with a lasso selection active does nothing; a swipe down while the pen is hovering
     is refused (pen gate — try after lifting the pen away).
  8. An orphan: write an H3 on a page with no H2 before it → it appears under the nearest H1 (or as
     a root if there is none) — never missing.
  9. A notebook with no headings → the Contents opens with "No headings yet"; no pager.
  10. `pm disable-user` **NSE · Heading** → reopen the notebook → **no list button**; swipe down does
      nothing; re-enable → resume → the button is back, the Contents works.
  11. `pm disable-user` **NSE · Markdown** (Heading present) → the Contents still lists every heading
      (the outline needs no renderer); re-enable.
  12. Long labels ellipsize with "…" at the row's end; page numbers stay aligned across indent levels.
  13. Regression: page flip / two-finger insert / undo / redo / long-press delete still work while no
      Contents is showing; a stylus never inks through the Contents (write across it on NA5C / SNN);
      the arc-4 heading story unchanged; Templates / Naming / Recognize page unchanged.

**Close-out:** status ✅ + Outcome (per-device open timings for a ~30-heading notebook: rows read +
bind + build; sidebar widths); docs; memory; commit + push.

---

### Phase C2 — Review, boundary audit, docs freeze
**Status:** ⬜ Not started

**Goal:** the appended method, its host handling and the Contents screen are trustworthy and
recorded as the pattern a second contributing object type follows.

**Questions to resolve at phase start:**
1. Anything observed in C1 the user wants changed before freezing (wording, sizes, gates)?
   (rec.: no — freeze as built)
2. Confirm scope freeze: fixes only; remove the debug "Probe contents" item (rec.: remove).

**Deliverables**
1. `/code-review high <C0 base>...HEAD` (**the range** — passing a bare hash reviews that commit;
   the base is in C0's Outcome); fix confirmed findings.
2. **Boundary audit** rows added to `docs/extensions.md` and walked:
   - **25 — Outward payload of `describeOutline` is the provider's own payloads, grouped by type,
     chunked** — never object ids, page ids / numbers, positions, names, keys, paths (the core keeps
     the geometry and the page index; the provider sees text it produced).
   - **26 — Inward outline replies are validated** (`OutlineCaps`: exact length, label trim/cut,
     level clamp, blank → none; `OutlineEntry.requireValid` at unmarshal; the load probe; a
     pre-method provider is "not capable", never an error).
   - **27 — The Contents is core-drawn from descriptions; absent / failed provider = its objects are
     not listed / the screen does not open with an honest dialog; nothing on the page changes.**
   - Re-walk rows 1, 6, 7 for the new client calls (`describeOutline`, `supportsOutline`).
3. `docs/extensions.md` final: rule 24 under §"Adding an object point"; §"Versioning rules" gains the
   "first exercised compatible change" note (the probe + tolerance recipe); "Writing an extension"
   item 10 (object provider) gains the outline paragraph; `README.md`; `CLAUDE.md` standing rules
   (a Contents bullet: the appended method, the probe, the button/gesture, the dialog's exclusion rule).
4. Remove the debug scaffolding (per Q2). This file frozen; memory updated (arc complete).

**Tests:** full C1 checklist on all three devices + C0 item 2 + the H4 checklist's items 1–5 + H1 3–8
+ v0 regression subset (create / open / write / flip, library create / rename / move / delete,
cold-launch reopen).

**Close-out:** status ✅ + Outcome; commit + push `paper`.

---

## Appendix A — Constants + strings (this arc)

| Name | Value |
|---|---|
| `MAX_OUTLINE_LABEL_CHARS` / `MAX_OUTLINE_LEVEL` | 200 / 6 |
| `MAX_OUTLINE_BATCH` / `MAX_OUTLINE_BATCH_CHARS` | 200 / 100 000 |
| `MAX_OUTLINE_ENTRIES` | 2 000 |
| `IconNames.LIST` | `"list"` → `ic_list` |
| Timeouts | bind 3 s · `describeOutline` `CALL_TIMEOUT_MS` (2 s) × chunks in one bind · probe 2 s |
| `ContentsDialog` | `CONTENTS_SIDEBAR_MIN_DP 480` · sidebar `0.60 × width` · `ROW_HEIGHT_DP 68` · `ROW_SEPARATOR_DP 1` · indent `16 dp × (level − 1)` · page-number column 52 dp · text 20 sp (number bold) · empty text 15 sp · highlight bar 5 dp inkBlack right edge |
| Gesture | one finger, vertical-dominant, `|dy| ≥ 0.30 × height` + `|vy| ≥ minFlingVel` **or** `|dy| ≥ 0.50 × height`, `dy > 0`; pen-idle + no-selection gate; never stylus / over chrome |
| Strings (main) | `contents_title` "Contents" · `cd_contents` "Contents" · `contents_empty` "No headings yet" · `contents_truncated` "Showing the first %1$d headings" · `cd_expand` "Expand" · `cd_collapse` "Collapse" · failure = existing `objects_provider_failed` "The %1$s extension didn't respond — try again." under the title `contents_title` |
| `API_VERSION` / `SOIL_VERSION` / g-paper | 1 / 1 / 0.1.1 — all unchanged |

## Appendix B — Allowed dependencies

None new. `:extension-api` unchanged (none); `:ext-heading` — `project(":extension-api")` + junit;
`:app` — unchanged. No list / recycler library (rows are plain views in a `LinearLayout`, paginated).

## Appendix C — Build & install (this arc)

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug && ./gradlew testDebugUnitTest      # seven modules
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-heading/build/outputs/apk/debug/ext-heading-debug.apk
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.heading.dev   # BOOX: re-run after a few s
adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.heading.dev
adb -s <serial> shell input swipe 700 600 700 1500 300      # C1: one-finger swipe down (finger) — opens the Contents
adb -s 5HL21V5007384 shell wm size 900x1600 ; … ; adb -s 5HL21V5007384 shell wm size reset   # C1: full-screen form
adb -s <serial> logcat -s NotebookActivity ContentsFlow ContentsSource ObjectProviderClient ObjectProviderService ObjectProviders
# C0 old-provider check: git worktree add /tmp/paper-arc4 b1d9b37 && (cd /tmp/paper-arc4/apps/notesprout_paper && ./gradlew :ext-heading:assembleDebug)
```

## Appendix D — Reference map

| Concern | Where |
|---|---|
| Original TOC (behaviour to mirror) | `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/toc/{TocDialog,TocRepository,TocNode}.kt`, `res/layout/{activity_toc,item_toc_entry}.xml`, `res/drawable/{bg_toc_active_entry,shape_toc_panel_border,ic_toc}.xml`; `NotebookActivity.evaluateSwipeDownToc` (~L3829) + `openToc()` (~L3848); `docs/mainactivity-and-recents.md` §"Table of Contents (TOC)" (~L388), `docs/content-objects.md` §"Heading-as-page-name rule" (why TOC ≠ page names) |
| Arc-4 provider point + Heading ext | `PAPER_OBJECTS_PLAN.md` §Contract additions / §Extension side; `extension-api/src/main/aidl/…/IObjectProvider.aidl`; `ext-heading/…/{ObjectProviderService,HeadingText,HeadingActions}.kt`; `docs/extensions.md` §"ObjectProvider (contract)", §"The Heading extension", §"Versioning rules" |
| Host client / providers / render pass (shapes to copy) | `app/…/extension/ObjectProviderClient.kt` (`renderAll` = one bind, N calls; `call(timeout)`), `notebook/ObjectProviders.kt` (`load`, `Contribution`, `signature`), `notebook/ObjectRenderPass.kt` (group by provider → one bind) |
| Gestures | `notebook/PageGestures.kt` (`handleSwipe`, `qualifiesFling`, `gateOpen`, `escrow`, constants) |
| Notebook screen chrome / exclusion / opening popup | `notebook/NotebookActivity.kt` (`pushExclusions`, `overChrome`, `openingOverlay`, `navigateTo`, `loadProviders`), `res/layout/activity_notebook.xml`, `docs/notebook.md` §Open (the whole-paper exclusion rect) |
| Pager to reuse | `res/layout/activity_library.xml` (`btnFirst` … `btnLast`, `pageLabel`), `library/LibraryActivity.renderPager` |
| Design system for the dialog | root `docs/design-system.md` (AlertDialog / Dialog styling, immersive), `docs/toolbar.md` (dimen-driven buttons), `res/values/dimens.xml`, `Widget.Notesprout.ToolbarButton`, `core/TopGuard.kt`, `core/Dialogs.kt` |
| Debug menu (probe home) | `app/src/debug/…/notebook/NotebookDebugMenu.kt` |
