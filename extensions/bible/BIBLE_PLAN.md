# BIBLE_PLAN.md — arc 37 "Bible": `NSE · Bible` (branch `bible`)

The plan **and the ledger** for the Bible-reader extension, in the shape of the SN arc plans
(`apps/notesprout_sn/<ARC>_PLAN.md`). Phases are appended to the ledger at the bottom as they land;
the reference doc once frozen is `docs/bible.md` beside this file.

## Context

A Bible reader inside Notesprout SN, as a separately installed extension APK on a **ninth**
extension point (granted by the user 2026-09-13 — the eighth fresh user decision on the seam).
Inspired by `~/git/Biblesprout` (its Android port has the data build, the print-look typography
and a working e-ink paginator). First version = **read only**: a button on the library and
notebook bottom bars opens the Bible where it was last left; an index picks book + chapter;
single-finger swipe turns pages and flows across chapter and book boundaries; no tap-to-turn,
no search, no bookmarks, no cross references, no footnote popups. Berean Standard Bible only.

Code lives at monorepo root **`extensions/bible/`**, included into SN's Gradle root as
`:ext-bible` (`projectDir = ../../extensions/bible`) — the first module outside
`apps/notesprout_sn`, recorded as the pattern for future extensions. Host changes go in
`apps/notesprout_sn`. g-paper is not touched (the screen has no paper).

Plan + ledger: this file (phases B0–B5). Reference doc when frozen:
`extensions/bible/docs/bible.md`. **No code review at the freeze** (the user's call); the freeze
is a Nomad walk. Test on the **Nomad** (`SN078D10012852`) in the `.dev` build only.

## Locked decisions (the user's, 2026-09-13)

| # | Decision |
|---|---|
| 1 | Ninth point `ACTION_BIBLE` + `ACTION_BIBLE_SCREEN`; `API_VERSION` 10 → **11**, compatible addition, floor 11 for `ACTION_BIBLE` only; no existing floor moves, no other extension redeclares |
| 2 | Module at `extensions/bible/`, included from `apps/notesprout_sn/settings.gradle.kts` via `projectDir` |
| 3 | Door = **bottom bars** on both screens (first non-pager control on a bottom bar — a deliberate exception to the "bottom bars are pager-only" rule, recorded). Library: in `bottomRight`, **left of Templates**. Notebook: **far right** of the bottom strip. Tabler `bible` icon. Notebook also mirrors it in the arc-36 collapsed overflow |
| 4 | The bundled `.bible` SQLite file is copied once to the extension's own **`noBackupFilesDir`** — the one sanctioned "extension writes to disk" exception: re-derivable APK content, never user data (ML Kit's model class). Recorded in `docs/extensions.md` + SN `CLAUDE.md` |
| 5 | **Slim formatted DB (~14 MB)**: blocks/verse markers/footnotes/xrefs/redletter kept; word/form/morphology + FTS dropped |
| 6 | Bundle **Noto Serif** (regular/bold/italic, OFL) from Biblesprout |
| 7 | Last-read position = **book + chapter + first verse on the page**, in the host store's `state` table, written on every turn; first ever open = **Genesis 1** |
| 8 | Formatting: paragraphs, poetry indents, section headings, superscript verse numbers, chapter number + book title on the first page, **psalm superscriptions (`d`) italic, parallel-passage lines (`r`) small italic (plain, not tappable), footnote callers as superscript `*` (not tappable)**. Red letters NOT rendered |
| 9 | Reader chrome: **top bar** (Back · "Book Chapter" title, tap = index · Index button) **+ bottom pager bar** `[‹] [n / N] [›]`; arrows also turn pages |
| 10 | Swipe past a chapter edge **flows** into the next/previous chapter across books; Gen 1 p1 / Rev 22 last page are no-ops |
| 11 | Index = **bordered dialog, Book grid ↔ Chapter grid** (`DayPickerDialog` shape), current book/chapter filled |
| 12 | Text size **fixed** at 30sp × 1.5 line height (Biblesprout's); no preference this arc |
| 13 | Bible **joins the cold-launch restore stack** as `Surface.BIBLE` |
| 14 | Label `NSE · Bible` / `NSE · Bible Dev`, package `com.symmetricalpalmtree.notesproutsn.ext.bible` (`.dev` debug), the family puzzle icon, `versionName` lockstep `0.1.0-sn` |

## Architecture

```
extensions/bible/                       (:ext-bible — application module, own APK)
  build.gradle.kts                      copy of ext-tags' with names changed; deps :extension-api + :sn-screen only
  BIBLE_PLAN.md · docs/bible.md
  src/main/AndroidManifest.xml          BibleService (ACTION_BIBLE, meta API 11) + BibleActivity (ACTION_BIBLE_SCREEN, DEFAULT category, exported, portrait)
  src/main/assets/bible/bsb.bible       slim build (noCompress "bible")
  src/main/res/font/noto_serif*.ttf     + noto_serif.xml family
  src/main/kotlin/.../ext/bible/
    BibleService.kt      ITagManager-shaped stub: begin(store) / end(), HostCallerCheck.enforce first
    BibleSession.kt      process-wide parked store (TagSession shape)
    BibleSchema.kt       StoreSchema V1: `state(key TEXT PRIMARY KEY, value TEXT NOT NULL)`
    BibleSql.kt / BibleStore.kt   position read/write (INSERT OR REPLACE is safe: childless table)
    ContentInstaller.kt  asset → noBackupFilesDir/bible/bsb.bible with a `lastUpdateTime:size` stamp
    BibleDatabase.kt     read-only SQLiteDatabase.openDatabase(OPEN_READONLY): books(), blocksForChapter(), footnotesForChapter()
    VerseKey.kt · Canon.kt   ported verbatim from Biblesprout (pure)
    reader/Atom.kt · ChapterPaginator.kt   ported (pure; drop WordAtom link fields, keep tokenize/fitCount/trimDanglingOpeners)
    reader/ReaderTypography.kt   ported: build() spans, applyFlow indents, layout(), measureBody, headingLayouts; xref underline + word marks removed
    reader/ReaderView.kt   draws one ReaderPage (title/number on page 1 + body layout) at 44dp/10dp pads
    reader/ChapterCursor.kt   pure: next/prev (book, chapter) over the canon's chapter counts
    BibleActivity.kt     the tier-2 screen (no paper): HostCallerCheck first, TopGuard, top bar, ReaderView, pager row, ListSwipe over the ReaderView
    IndexDialog.kt + IndexModel.kt   Book grid ↔ Chapter grid bordered dialog (DayPickerDialog shape; model pure + tested)
  src/test/kotlin/...   VerseKey/Canon/ChapterCursor/IndexModel/ChapterPaginator(trim+fit with a fake measurer)/BibleSql tests
tools/bible/build_bible_db.py           copy of Biblesprout's builder with a `--slim` flag (skips word layer + FTS); README with the two source URLs
```

Host (`apps/notesprout_sn`) — verified against the code:
- `extension-api/.../ExtensionContract.kt`: `API_VERSION = 11` (line 92) + ledger paragraph ("11 = arc 37, the BIBLE point — store-taking held bind, floored at 11, no existing door moves"); `MIN_API_VERSION_FOR_BIBLE = 11`; `ACTION_BIBLE = "com.symmetricalpalmtree.notesproutsn.extension.BIBLE"`, `ACTION_BIBLE_SCREEN = "…extension.BIBLE_SCREEN"`; `MIN_API_VERSIONS` row (lines 125–133); header "eight points" → nine. New `src/main/aidl/.../IBible.aidl`: `void begin(IExtensionStore store); void end();` only.
- Tests that pin it: `ExtensionContractTest` — `API_VERSION` 11; **line 36 `assertEquals(API_VERSION, MIN_API_VERSION_FOR_CALENDAR_DAY_SEND)` must become `assertEquals(10, …)`** (floors pin to their birth number, the HV1 lesson); pin both new action strings; a bible block in `storeTakingPointsHaveTheFloor` (`minApiVersion(ACTION_BIBLE)==11`, refuses 10, accepts 11, refuses 12; `ACTION_BIBLE_SCREEN` floor 1). `CloudContractTest` untouched.
- `app/.../data/prefs/SurfaceStack.kt:13`: `enum class Surface { …, BIBLE }` (entry = `SurfaceEntry(token, BIBLE)`, no notebookId, the calendar's shape). `ReplayPlan.kt` needs **no code change** (`of()` already yields `LibraryLevel(BIBLE,false)` / `Notebook(id, viaLink, [BIBLE])`; `legalAbove` keeps a lone BIBLE, truncates anything after it) — update its KDoc and pin with `ReplayPlanTest` cases (lone BIBLE; NOTEBOOK+BIBLE; CALENDAR+BIBLE at library level → BIBLE, no latch; `legalAbove([BIBLE,CALENDAR])==[BIBLE]`; `decodeAbove(["BIBLE"])`). `SurfaceStackCodecTest:15` round-trips five surfaces.
- Replay arms: `LibraryActivity.replayLibraryLevel` (line 462, exhaustive `when` — compile error without a `Surface.BIBLE` arm: `if (!bible.discovered()) drop; if (standingForReplay()) bible.open()`); `NotebookActivity.replayAbove` (line 1211 has `else -> Unit`, so add an explicit `Surface.BIBLE` arm mirroring CALENDAR's at 1212–1218). `openIntoEditor` 1287–1291 already drops it above a text document. `docs/library.md` 884 / 941–944 / 965–971 list the surfaces.
- `ExtensionRegistry.bible()` after `calendar()` (line ~120), first-wins; KDoc "eight" → nine. Manifest `<queries>`: both actions after line 60; comment at line 7.
- `extension/BibleClient.kt`: `TagClient.open` (49–77) minus `configureShowing` — `ExtensionStores.lease` on IO → `ExtensionBinder.hold(…, ACTION_BIBLE, asInterface = IBible.Stub::asInterface)` → `begin(store)` under `CALL_TIMEOUT_MS = 2_000` → `Intent(ACTION_BIBLE_SCREEN).setPackage(pkg)`; `finish()` = TagClient 80–96 verbatim (best-effort `end()`, close + revoke in `finally`, idempotent).
- `extension/BibleEntry.kt`: `TagManagerEntry` (48–198) as the skeleton + `ExtensionScreenEntry`'s stack pieces (187–204, 289–291, 346–350, 483–487): launcher registered in the field initializer (construct in `onCreate`, before STARTED); `stack.attach(stackEntry)` only after `ScreenLaunch.attempt` answers `Launched`; `onResult` pops **synchronously first**, then `client.finish()` on `MainScope()`, `opening=false` in `finally`; `close()` pops + finishes. Failure = `Dialogs.problem(bible_failed_title/body)` + `refresh()`. No `EXTRA_CHROME_HIDDEN`, no `releaseForHandoff`.
- Doors. Strings: `cd_bible` "Bible", `bible_failed_title` "Bible unavailable", `bible_failed_body`. Icon: `sn-screen/src/main/res/drawable/ic_bible.xml` on `ic_tag.xml`'s template, Tabler `bible` paths (`M19 4v16h-12a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2h12` · `M19 16h-12a2 2 0 0 0 -2 2` · `M12 7v6` · `M10 9h4`) — nothing named bible exists in the monorepo. Library: `btnBible` inside `bottomRight` **before** `btnTemplates` (`activity_library.xml:263`); `DebugMenu.install` appends after XML children, so `[Bible][Templates][⋯]`; **do not** touch it in `renderChrome` (line 588) — the entry is its one visibility owner. `LibraryActivity`: construct after `tags` (line 243), before `DebugMenu.install`; `onResume` 311 `refresh()`; `onDestroy` 322 `close()`; `::bible.isInitialized` guards (IndexGuard bounce). Notebook: replace the weight-1 spacer `View` (`activity_notebook.xml:219–223`) with a weight-1 `LinearLayout` `gravity="end|center_vertical"` holding `btnBible` (gone) — keep the row's 12 dp padding; `NotebookActivity`: construct after the tag block (line 825), before `collapsed = CollapsedChrome(...)` (900); overflow mirror `Entry.mirroring(R.drawable.ic_bible, binding.btnBible)` **before** `ic_calendar` (pad stays last); click guarded by `opened && !closing`; `onResume` 4230 `refresh()`; `onDestroy` 4374 `close()`. `pushExclusions` reads the whole `bottomStrip` rect (4039) — covered. `TooltipCompat` from the content description at both sites.
- `settings.gradle.kts` after line 31: `include(":ext-bible")` + `project(":ext-bible").projectDir = file("../../extensions/bible")`.
- Grep for every `when (…Surface` before finishing B0 (enum growth = compile error or silent `else` drop).

### The reading model
- One chapter = `atomsForBlocks(blocks, footnotes)` → `paginate(atoms, typo, width, firstH, otherH)` on IO; pages cached per chapter (current + neighbours prefetched lazily, no ceiling needed — a chapter is ≤ ~30 pages).
- Page 1 carries `BOOK NAME` (bold small caps-ish letterSpacing) + big chapter number; `firstPageHeight = readingHeight − headingHeight − safetyPad`.
- Position write on every committed turn: `book usfm`, `chapter`, `verse` = first `NumberAtom` on the page (or the page's carried-in verse when the page opens mid-verse — the paginator's "seed"); reopen = repaginate then pick the page whose verse range contains the saved verse.
- Chapter-edge flow: `ChapterCursor.next/prev`; going back lands on the **last** page of the previous chapter.
- No full-refresh flash: on Ratta the device owns refresh for a non-paper screen (SN rule); nothing in SN flashes.
- Swipe: `ListSwipe(region = { readerView }, onFlipNext, onFlipPrevious)` fed from `dispatchTouchEvent` — the same `SwipeMath` rule the notebook teaches; pen/eraser sequences dropped; no tap zones.

## Phases

**B0 — Seam + scaffold** (host + module skeleton, no reader yet)
1. Branch `bible` from `main`. `tools/bible/` builder (`--slim`), build `bsb.bible`, verify with `sqlite3` that `word/form/morphology/verse_fts*` are absent and `block/verse_marker/footnote/xref/redletter/book/verse/metadata` are present; record the size.
2. `extension-api`: `IBible.aidl`, contract constants, `API_VERSION` 11, ledger, `MIN_API_VERSIONS`; update `ExtensionContractTest` / `CloudContractTest` pins.
3. `settings.gradle.kts` include with `projectDir`; `extensions/bible/build.gradle.kts`, manifest, puzzle icon copied from `ext-soil`, labels; `BibleService`/`BibleSession`/`BibleSchema`/`BibleStore`; a placeholder `BibleActivity` that passes the caller check and shows the title bar.
4. Host: `<queries>`, `ExtensionRegistry.bible()`, `BibleClient`, `BibleEntry`, both bottom-bar buttons (+ collapsed overflow mirror), `Surface.BIBLE` + `ReplayPlan` + replay arms + tests.
5. Gate: `./gradlew assembleDebug test` unpiped; install host + ext-bible on the Nomad; the button appears on both bars; tapping opens the placeholder; `am start` is refused; process-death restore reopens it.

**B1 — Content + reader core**: `ContentInstaller`, `BibleDatabase`, ported `VerseKey`/`Canon`/`Atom`/`ChapterPaginator`/`ReaderTypography`/`ReaderView`, fonts; `BibleActivity` renders Genesis 1 page 1; pager buttons turn pages within the chapter. JVM tests for the pure parts.

**B2 — Swipe + chapter flow + position**: `ListSwipe`, `ChapterCursor`, cross-chapter/book flow, store-backed position (write on every turn, read on open, verse-anchored reopen), "Book Chapter" title.

**B3 — Index**: `IndexModel` (pure, tested) + `IndexDialog` (Book grid ↔ Chapter grid, arrows page books, title flips levels, current filled); title tap + Index button open it.

**B4 — Nomad walk + pruning**: hand walk on the Nomad (swipe cannot be adb-driven reliably — user checklist for swipe feel, pen-over-page no-op, chapter flow both directions, Rev 22 / Gen 1 no-ops, reopen position, process death, launch restore, poetry/heading look in Psalms 23, Genesis 1, Matthew 5, John 1).

**B5 — Docs + freeze**: `extensions/bible/docs/bible.md`, `docs/extensions.md` (ninth point section, module table row, `<queries>` block, identity table, boundary-audit rows, privacy line, the disk exception), SN `CLAUDE.md` (module list, ninth point, bottom-bar exception, `extensions/` pattern), root `CLAUDE.md` (the `extensions/` folder), memory file. Commit + push each phase.

## Boundary / privacy notes for the doc
- Outward on `begin`: the uid-bound store binder only. Nothing on the Intent. Inward: nothing (no result payload; `RESULT_CANCELED`/`RESULT_OK` both mean "closed").
- The store holds one `state` row (`position` → `USFM:chapter:verse`); scripture text never crosses the seam and is never logged (log book/chapter numbers + durations only — "where, not what").
- The `.bible` file is read-only APK content in `noBackupFilesDir`; the backup engine never sees it (not in Garden); uninstall removes it.

## Verification
- `cd apps/notesprout_sn && ./gradlew assembleDebug test` (unpiped, read `$?`); byte-scan changed files for NUL/BEL.
- Nomad `.dev` walk per B4 (the release ext packages need no disabling — no release Bible exists yet).
- `adb shell am start -a …BIBLE_SCREEN` → `refused caller (none)`.

## Ledger

### B0 — Seam + scaffold ✅ 2026-09-13

- `:extension-api`: `IBible.aidl` (`begin(store)` / `end()`), `ACTION_BIBLE` + `ACTION_BIBLE_SCREEN`,
  `MIN_API_VERSION_FOR_BIBLE = 11`, `API_VERSION` 10 → 11 (compatible addition; no other floor moved;
  `ExtensionContractTest` re-pinned — the arc-35 day-send floor now pins to 10, its birth number).
- `settings.gradle.kts`: `include(":ext-bible")` with `projectDir = ../../extensions/bible` — the
  first module outside the app folder.
- Module `extensions/bible/`: ext-tags' build/manifest shape, API 11, puzzle icon byte-identical,
  `BibleService` / `BibleSession` / `BibleSchema` (`state(key, value)`) / `BibleSql` / `BibleStore` /
  placeholder `BibleActivity` (caller check first, top bar + empty band + pager row), 4 JVM tests.
- Data: `tools/bible/build_bible_db.py --slim` (Biblesprout's builder; no word layer, no FTS) →
  `src/main/assets/bible/bsb.bible` **12,210,176 B** (11.6 MB), `integrity_check` ok, 31,086 verses /
  46,360 blocks / 4,854 footnotes / 3,278 xrefs, `metadata.layers = display`. `noCompress "bible"`.
  Noto Serif regular/bold/italic + `noto_serif.xml` under `res/font/` (license note: `FONTS.md` —
  a `README.md` inside `res/font/` breaks the resource merger).
- Host: `<queries>` both actions; `ExtensionRegistry.bible()`; `BibleClient` (TagClient minus
  `configureShowing`); `BibleEntry` (TagManagerEntry + the surface-stack push/pop); `Surface.BIBLE`
  (`ReplayPlan` unchanged in code, its tests grown by five); replay arms in both activities;
  `btnBible` on the library's bottom bar (left of Templates) and the notebook's bottom strip (far
  right, inside a weight-1 container that keeps the pager centred); collapsed-overflow mirror before
  Calendar; `ic_bible.xml` (Tabler `bible`) in `:sn-screen`; three strings.
- Gate: `assembleDebug` + `testDebugUnitTest` green — **1667 `:app` / 232 `:extension-api` / 4
  `:ext-bible`**. Nomad `.dev` walk: discovery `1 provider(s) of 1 candidate(s)`; the button on both
  bars; tap → `begin` → screen (cold open 2.6 s, warm 0.7 s); Back → `end` → unbind; a shell
  `am start` of the screen leaves the notebook focused (refused); force-stop host + extension in ONE
  command, relaunch → `restore: reopening [BIBLE] above the notebook` and the Bible is back.
- **Walk trap (recorded):** `am crash` on the host lets Android relaunch the task, whose library
  `onResume` resets the surface stack to `[]` before a second kill lands — a launch-restore test must
  use `am force-stop <host>; am force-stop <ext>` in one shell command, never `am crash` first.
- Build trap: a stale `extension-api/build/` from the `notesprout_ratta` → `notesprout_sn` rename
  fails `mergeLibDexDebug` ("located outside the root directory") — `rm -rf extension-api/build`.

### B1 — Content + reader core ✅ 2026-09-13

- Ported from Biblesprout (each file says so once, KDoc kept where it still applies):
  `VerseKey.kt` verbatim · `Canon.kt` minus the alias/normalize lookup (no free-text reference
  parsing in this reader — it would have dragged `Reference.kt` in) · `ContentInstaller.kt` with
  the destination moved to **`noBackupFilesDir/bible/`** and the copy made crash-safe
  (stamp deleted → `.part` → `fd.sync()` → rename → stamp written last) · `BibleDatabase.kt`
  (plain `android.database.sqlite`, `OPEN_READONLY`, the SQL copied exactly) + `Rows.kt`
  (`BookRow` / `VerseMark` / `RenderBlock` / `Footnote`) · `reader/Atom.kt` (link, word-layer and
  highlight fields dropped; `minor: Boolean` → `HeadingKind` MAJOR/MINOR/REFERENCE/SUPERSCRIPTION)
  · `reader/ChapterPaginator.kt` (xrefs and the plain-verse `atomsFor` dropped; the typography
  parameter became the `fun interface BodyMeasurer`, which is what makes `paginate`/`fitCount`
  JVM-testable) · `reader/ReaderTypography.kt` (the char-mark bookkeeping left with everything
  that hit-tested it; it now **is** the `BodyMeasurer`) · `reader/ReaderView.kt` (highlights
  dropped; `show(page)` is the one door and an identical page is a no-op — a self-repainting
  surface ghosts).
- `BibleActivity`: head unchanged (caller check first, inflate, `TopGuard`); the `ReaderView` goes
  into `readerBand` as child 0, and the band's **first layout** starts the load — install → open →
  `blocksForChapter`/`footnotesForChapter` → `atomsForBlocks` → `paginate` → every page laid out,
  **all on `Dispatchers.IO`**, Main only draws. Title "Genesis 1"
  (`bible_chapter_title`), indicator "n / N", pager steps within the chapter and **no-ops** at the
  edges (never disabled). `btnIndex` keeps its tooltip and does nothing (B3). A failed open is
  `Dialogs.problem`, never a blank band; "Loading…" appears only past 300 ms (no spinner).
  Logs carry book/chapter, page counts and durations — never text.
- The `.bible` file checks out against the ported SQL: all 16 block kinds it contains are covered
  by `flowFor`/`headingFor`, and its 66 `book` rows match `Canon` usfm-for-usfm, name-for-name.
- Gate: `:ext-bible:assembleDebug` + `:ext-bible:testDebugUnitTest` green, **20 tests** (B0's 4 +
  `VerseKeyTest` 5, `CanonTest` 5, `ChapterPaginatorTest` 6 — the Psalm-23-shaped block list, the
  heading kinds, "a page never ends on a verse number", progress when nothing fits, `fitCount`,
  `firstVerseKey`). Zero Kotlin warnings. Not installed on any device — the look of the page
  (poetry indents, headings, the first page's title + chapter number) and the open duration are
  B4's walk.

### B2 — Swipe + chapter flow + stored position ✅ 2026-09-13

- **`ChapterCursor.kt`** (pure, package root — not `reader/` as the architecture sketch had it:
  it walks the canon, it renders nothing): `ChapterRef(usfm, chapter)` + `next`/`prev` over the
  **source's own** chapter counts (`BibleDatabase.books()`, read once), ordered by `Canon`'s
  ordinal rather than the map's iteration order. A book the map does not carry is **skipped**, a
  count below 1 is no book at all, and `prev(GEN 1)` / `next(REV 22)` are `null` — the reader's
  silent no-ops (decision 10).
- **`Position.kt`** (pure): `GEN:1:1` on the wire, `decode` **total** — wrong arity, a non-int, an
  unknown usfm, a chapter or verse below 1 all yield `null` and the reader falls back to
  `Position.GENESIS_1` (decision 7). A lost bookmark is never a dialog.
- **Page anchors** in `ChapterPaginator`: `anchorVerses(pages)` — the verse **in effect** at each
  page's first word (a number, or a heading then a number, opens the page; otherwise the verse
  carried in from the page before; a heading-only opening page anchors to 1) — and
  `pageContaining(anchors, verse)`, the LAST page at or before the verse. **`firstVerseKey` was
  replaced by these rather than kept beside them** (a second anchor rule is a drift waiting to
  happen); its B1 test became the three anchor tests. A verse spilling over two pages anchors
  both, so a reopen lands on the last of them — recorded, not a bug.
- **`ChapterLoader.kt`** — extracted from `BibleActivity` (which would otherwise have grown past
  its budget): the installed source, the typography, the cursor, `buildChapter`, and a
  **5-chapter `LinkedHashMap` cache** keyed on `ChapterRef`, oldest evicted, emptied whole if the
  band's geometry ever changes. `ChapterPages` moved here and now carries `ref` + `anchors`. Two
  monitors on purpose: the field lock (never held across the 12 MB first-run copy or an open) and
  a **build lock** — one `ReaderTypography` means one shared `TextPaint`, and a `Paint` measured
  from two threads at once is not safe, so a foreground load can wait for one already-running
  prefetch build.
- **`BibleActivity`**: head unchanged (caller check first). Opens on the stored position — read on
  IO at the band's first layout, `pageContaining(anchors, verse)` picks the page — and writes
  `Position(usfm, chapter, anchors[pageIndex])` on every committed turn **and** the first show,
  fire-and-forget on IO, **coalesced** (a write in flight parks the latest one and takes it next,
  so ten fast flips cost two writes) and swallowed with a `Slog.d` on failure. A turn past the
  last page opens the next chapter at page 0, a turn back off page 0 the previous at its **last**
  page, across books; the title follows. **A chapter load latches** further turns away rather than
  queueing them — on e-ink a queued turn arrives long after the hand gave up on it. After each
  show, both neighbours are built on IO into the loader's cache, so the edge is usually one
  `invalidate()`. `ListSwipe(region = { readerView }, …)` built in `onCreate` and fed from
  `dispatchTouchEvent` **before** `super` — observer only, consumes nothing, pager buttons
  untouched, and it drops stylus/eraser sequences itself, so a pen over the page never turns it.
  No tap zones (the user's call).
- **Hardening found on the way:** `onDestroy` ran straight into `lateinit` teardown on a
  caller-check bounce (B1's shape — the shell `am start` in the B0 walk was refusing the launch
  *and* crashing the extension process out of sight). It now returns on an `admitted` flag first,
  the root `IndexGuard.bounced` rule in the extension's shape.
- Gate: `:ext-bible:assembleDebug` + `:ext-bible:testDebugUnitTest` green from a `--rerun-tasks`
  build, **34 tests** (B1's 20 + `ChapterCursorTest` 7 + `PositionTest` 4 + 3 more in
  `ChapterPaginatorTest`, whose `firstVerseKey` test was replaced). Zero Kotlin warnings; every
  touched file byte-scanned clean (no NUL/BEL).
- **Not installed on any device — B4's walk owns all of this:** the swipe's *feel* (how far a
  Nomad finger has to travel before `SwipeMath` calls it, and that a resting pen never turns the
  page), the flow at Genesis 50 → Exodus 1 and back off Exodus 1 into Genesis 50's last page, the
  Revelation 22 / Genesis 1 no-ops being silent rather than stuck-looking, whether the neighbour
  prefetch actually makes an edge crossing feel like a page turn, and the reopen-at-verse after a
  Back (and after process death).

### B3 — The index ✅ 2026-09-13

- **`IndexModel.kt`** (pure): the arithmetic of two grids and nothing else. Books **3 × 6** (a
  book's *name* has to fit), chapters **6 × 6** (a number wants a square) — `bookPages` /
  `chapterPages` return pages → rows → cells with **every row padded to a full row** (a short last
  row would re-centre itself and read as a different grid), plus `bookPageOf` / `chapterPageOf` /
  `clampPage`. Books flow in **canon order**: the OT/NT boundary is *not* a page boundary and no
  testament label is shown — a break there would leave Malachi's page two-thirds empty for a word
  this reader never says. Nonsense is absorbed rather than thrown: an unknown usfm, a chapter below
  1 and an empty page count all answer page 0.
- **`IndexDialog.kt` + `res/layout/dialog_index.xml`** — the day picker's shape, subject for
  subject: the same header (prev · a centred title kept off both arrows by a button-width margin ·
  next), the same code-built grid host, the same `Dialogs.style` + `setNegativeButton(cancel)` +
  **window sized to 0.75 of the screen after `show()`**, and the same **no background on the root**
  (the window's `shape_dialog_bordered` is the border; an opaque root covers it — the trap the Y2–Y4
  picker paid for). Two levels behind one pair of arrows: **Books** (title "Books", **not** a tap
  target there — there is nowhere to flip back to) opening on the current book's page with that book
  **filled black, white bold**; tapping a book opens its **chapters**, titled with the book's name,
  which is also the way back (the day picker's title flip, `cd_bible_index_flip`). The current
  chapter is filled **only in the current book**. Cells are the month cell's recipe —
  `shape_bordered` / `bg_index_selected` (a copy of `bg_month_selected` into the extension's own
  drawables), `maxLines = 2` + ellipsize end for the long names, gravity centre, weight-1 wide and
  **`@dimen/toolbar_button_size` tall**, never a literal — and a null cell is a spacer that holds
  its column. **The arrows are never disabled**: at either end `clampPage` returns the page it was
  already on and nothing repaints at all (a greyed control is invisible on e-ink; a needless repaint
  on e-ink is a flash).
- **`BibleActivity`**: `btnIndex` **and the title** both call `openIndex()` — "Genesis 1" is the
  obvious thing to tap when you want to be somewhere else — and the title gained the same long-press
  hint the buttons have. The books come from `ChapterLoader.booksNow()`: B2's `source()` already
  read the `book` table for the cursor, so the index costs **no second query** and the dialog never
  touches the database. Before the first chapter has shown there are no books and the tap is a
  **silent no-op** — a "not ready" dialog would be noise for the half-second it is true. A picked
  chapter goes down the chapter-edge path (`openChapter(picked) { 0 }`), so the latch, the neighbour
  prefetch and the position write all follow from the show exactly as they do for a turn.
- Gate: `:ext-bible:assembleDebug` + `:ext-bible:testDebugUnitTest` green from a `--rerun-tasks`
  build, **46 tests** (B2's 34 + `IndexModelTest` 12 — the canon's 18/18/18/12, every row three
  slots wide, the padded short row, Malachi and Matthew as neighbours, GEN/PSA/REV's pages, an
  unknown book, Psalms' five chapter pages, the one-chapter book's five spacers, `chapterPageOf`'s
  1/36/37/150 and `clampPage`'s bounds). Zero Kotlin warnings; every touched file byte-scanned clean
  (no NUL/BEL). Not installed on any device.
- **Not verifiable without the Nomad — B4's walk owns it:** the cell sizes at the 62 dp tier and
  0.75 width (three book columns of roughly 340 px each, minus 3 dp margins), and specifically
  whether **"1 Thessalonians"** and **"Song of Solomon"** fit inside two lines at 14sp or hit the
  ellipsis; whether a 6-row grid of 62 dp cells plus the header leaves the dialog inside the screen;
  and whether flipping levels or paging repaints cleanly (no full-screen flash) on e-ink.

### B4 — Nomad walk + pruning ✅ 2026-09-13 (adb half; the hand half is the user's)

Walked over adb on the Nomad `.dev` build after B3:
- **Index**: books page 1 → arrows page to Psalms (page 2) → Psalms' chapter grid → 23 → Psalm 23
  renders as print (italic superscription, hanging poetry indents, verse numbers in the margin,
  footnote callers). Book names fit their cells at 14sp on the 62 dp tier ("Song of Solomon",
  "1 Chronicles" one line each).
- **Pruning (the one fix)**: the last page of a grid was shorter than the others (2 John's one-row
  chapter grid, the canon's 12-book last page), so the bordered dialog shrank and **re-centred under
  the finger** — a mis-tap on e-ink. `IndexModel.grid` now pads every page to the full
  `rows × columns` (spacer rows), so the dialog has one height everywhere; two tests re-pinned.
- **Chapter edges**: Revelation 22 → last page → further swipes are silent no-ops (no chapter load
  logged); Genesis 1 page 1 → a back-swipe is a silent no-op.
- **Long chapter**: Psalm 119 — 30 pages in 1,245 ms (cold, on IO; the reader stays responsive).
- **Prose keep-with-next**: Luke 1 page 1 ends after verse 4 with white space below — the next
  heading + reference + verse 5 did not fit and `trimDanglingOpeners` kept them together on page 2.
  Print behaviour, not a bug.
- Timings: chapter opens 265–480 ms cold, 2 ms from the prefetch cache; open-screen 570–770 ms warm.

**Left to the user's hand walk** (adb cannot judge feel): the swipe's travel/velocity feel against
`SwipeMath`'s 30 %/50 % rule; a resting pen or a pen drag over the page never turning it (the
stylus sequence is dropped by `ListSwipe`); the page-turn ghosting cadence with no forced flash.

**Open for a user decision (not changed):** the title and the running head say "Psalms 23" — the
`book.name` row is "Psalms"; print says "Psalm 23". A one-word special case in the title only, if
wanted.

### B5 — Docs ✅ 2026-09-13 (freeze pending the user's hand walk)

- `extensions/bible/docs/bible.md` — the feature reference (data, reading model, screen, index,
  store, privacy, failure table, walks, traps, tests, recorded futures); links to
  `apps/notesprout_sn/docs/extensions.md` § "The Bible point (arc 37)" for the seam rather than
  repeating it.
- Host docs (landed with B3, `848ea775`): `docs/extensions.md` (the arc-37 paragraph, the module
  table row, `<queries>`, the API ledger at 11, the ninth-point section, boundary-audit rows 52–54,
  privacy, identity), SN `CLAUDE.md` (fifteen modules, NINE points / no TENTH, API 11, the
  bottom-bar exception), root `CLAUDE.md` (`extensions/` folder, docs table, branch), `docs/library.md`
  (`BIBLE` on the restore stack, the bottom bar).
- Final gate: every module assembles; **3,148 JVM tests** green (`:app` 1667 · `:extension-api`
  232 · `:ext-bible` 46 · the rest unchanged).
- **Freeze condition:** the user's hand walk on the Nomad — swipe feel, pen over the page — and the
  "Psalm 23" title decision. Until then the arc is complete but not frozen.

### FREEZE ✅ 2026-09-13

The user walked the Nomad by hand after B5 (swipe feel, the pen over the page, the
force-stop-both-then-relaunch replay landing back on the Bible at Psalm 131): **"it all passes and
looks great for this phase."** Arc 37 "Bible" — B0–B5 — is COMPLETE + FROZEN, no code review (the
user's call). The "Psalm 23" title is left as it is (`book.name` = "Psalms") until asked for.
Everything in `docs/bible.md` § "Not in this arc" needs a fresh user decision; this file's phases
are history from here.

