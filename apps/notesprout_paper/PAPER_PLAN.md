# Paper — Notesprout Rebuilt From Scratch (v0 "paper")

> **This file is the project's memory across sessions.** Context is cleared between phases. Everything
> a fresh session needs — decisions, non-goals, architecture, per-phase tasks, tests, status — is here or
> in the files this document points at. If it isn't written down here (or in the repo / project memory),
> it doesn't exist. Read this file top to bottom at the start of every session.

Paper is an experimental from-scratch rebuild of Notesprout that may one day replace it. It keeps the
lessons, the `.soil` container family, the global-index model, the global encryption model, and the
e-ink design philosophy — and drops everything else. v0 is **"a set of paper notebooks"**: a library of
folders and notebooks, each notebook a stack of pages you write on with a pen and flip through.

- **Branch:** `paper` (cut from `main` at `2ce08c9`, 2026-08-15). All work lands here; `main` is untouched.
- **Location:** `apps/notesprout_paper/` (its own Gradle project, sibling of `apps/notesprout_android/`).
- **Package / applicationId:** `com.symmetricalpalmtree.notesprout` (debug: `.dev` suffix).
- **Launcher label:** **"Paper"** while experimental (so it is distinguishable from the installed
  Notesprout on the same device). Debug label "Paper dev".
- **Drawing surface:** the **g-paper** library (`~/git/g-paper`), consumed from **mavenLocal**
  (`com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.0`, all three modules).
- **Reference codebase (read, never copy blindly):** `apps/notesprout_android/` + `docs/`. Especially:
  `docs/soil-file-format.md`, `docs/global-index-format.md`, `docs/encryption.md`,
  `docs/design-system.md`, `docs/drawing-engine.md`, `docs/mainactivity-and-recents.md`.
- **g-paper docs (read before touching the notebook screen):** `~/git/g-paper/docs/api.md`,
  `host-responsibilities.md`, `integration-guide.md`, and `~/git/g-paper/CLAUDE.md`.

---

## Working protocol

Each phase runs in a **fresh session** and follows the same ritual:

1. **Phase start:** read this file (all of it), the root `CLAUDE.md`, and `apps/notesprout_paper/CLAUDE.md`
   (exists from Phase 0 on). Confirm the next `⬜` phase with the user, flip it to `🔄`, then **ask the
   phase's "Questions to resolve at phase start"** before writing code. Do not assume answers. If a new
   ambiguity surfaces mid-phase that would materially change the work, stop and ask.
2. **Code** — coding runs in auto mode. Work inline; be frugal with background agents (none unless a
   sweep genuinely needs one). No new Gradle dependency beyond the list in Appendix B without asking.
3. **Test** — JVM unit tests for pure code (`./gradlew :app:testDebugUnitTest`), then build + install
   the debug APK on the **test devices** (below) and hand the user a short, numbered on-device
   checklist. EPD pen overlays are invisible to screencap — the user verifies ink by eye and reports.
4. **Fix → test again** as needed.
5. **Docs / memory / CLAUDE.md** — update `apps/notesprout_paper/CLAUDE.md` (standing rules + build
   facts only) and `apps/notesprout_paper/docs/*.md` (subsystem detail), and this file's status +
   **Outcome** note. Save/refresh a project memory (`~/.claude/projects/-Users-gregmarine-git-Notesprout/memory/`)
   pointing at this plan with the current phase status.
6. **Commit & push** on `paper` (`git push -u origin paper` the first time). Then the user runs `/clear`.

**Status markers:** `⬜ Not started` · `🔄 In progress` · `🧪 Awaiting device verification` ·
`✅ Complete (commit <hash>)`. Update the marker the moment the state changes.

**Test devices for Paper** (user verifies by eye; always pass `-s <serial>`):

| Nickname | Device | Serial | Engine exercised |
|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` (firmware ink) |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` (raw pipeline) |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` generic engine |

Never install on a device the user didn't ask for. If a device is offline, say so and wait.

**Model note (from planning):** this plan is written so that Opus 4.6 can execute a phase without
inventing decisions. Recommendation: use Fable 5 for the two phases with the most hidden failure modes —
**Phase 1** (encryption + non-destructive open + bootstrap; the data-loss bug family lives here) and
**Phase 3** (first g-paper integration on three engines) — and Opus 4.6 for the rest. Either model
must follow the phase-start question ritual.

**g-paper gaps:** if a phase finds something g-paper should do but doesn't, the phase may edit
`~/git/g-paper` — with its own tests + docs (`docs/api.md` must track any public-surface change), bump
`GPAPER_VERSION` (0.1.x), `./gradlew publishToMavenLocal`, commit + push there — then pin the new
version in Paper's `build.gradle.kts` and record it in the phase Outcome. Never work around a real
component gap in the host.

---

## Locked decisions (from the planning Q&A, 2026-08-15)

| Area | Decision |
|---|---|
| Scope | Library of folders + notebooks; a notebook is 1..N pages of **pen strokes only**. Nothing else (see Non-goals). |
| `.soil` | **Same container family, greenfield shape.** All twelve invariants of `docs/soil-file-format.md` Part I hold (one SQLite file, universal row, `notebook_meta`, format-B stroke codec, stock SQLCipher). **No `layer` rows** — strokes are parented directly to the page. **No legacy `data` / `boundingBox` columns.** Existing Notesprout files are not openable in Paper (a converter is future work). |
| Object-table name | `notebook` (Paper is the successor; its files are notebooks). |
| Global index | `notesprout.db`, `objects` table with the universal row shape (see Architecture), lean: no legacy `data` column, no app-content tables. Types: `folder`, `notebook`, `list`, `list_item`. |
| Encryption | **Encrypt-by-default, global key only.** Auto-minted `NSPT-` recovery key shown once at first launch; Keystore-backed passphrase + raw-key caches; unlock screen after reinstall/restore; non-destructive open helper on every path; attempt rate-limiter. **No per-notebook keys, no rotation / change-passphrase UI in v0.** |
| Templates | Built-in set only — **Blank / Lined / Dotted / Grid**, drawn procedurally at page size, **chosen once per notebook at creation**, copied into the `.soil` as one `template` row (WEBP q100 blob); every page's `refId` points at it. No library, no image import, not changeable after creation. |
| Page geometry | Page = the device's **full portrait screen in px** at creation, stored on the page row. **Full-bleed paper; chrome overlays it** and is registered via `setExclusionRects`. `setPageSize(w,h)` on load so template/ink registration survives other screens. |
| Orientation | **Portrait-locked** on every screen (`android:screenOrientation="portrait"`). |
| Tools | Toolbar: **Pen · Eraser · Lasso**, fixed defaults: pen black / one width / `StrokeStyle.PEN`; eraser one radius, whole-stroke; lasso select + drag-move only. Smart-lasso and scribble-erase recognizers **off**. No panels. |
| Undo / redo | **Yes, in-memory per page** (cleared on page turn and close). Two-finger stationary double-tap = undo, three-finger = redo (Notesprout's detector), pen-activity gated. Covers stroke add, erase, lasso move. |
| Pages | Single-finger horizontal swipe flips (left = next, right = previous). Two-finger horizontal swipe inserts (left = **after** current, right = **before**) and navigates to the new page. **Delete:** finger long-press on the paper → action sheet "Delete page" → confirm dialog; one page at a time; deleting the only page creates a fresh blank one so a notebook always has ≥ 1 page. Last-open page is restored on reopen. |
| Library | Paginated card grid (no scrolling); breadcrumb folder navigation; **Create / Rename / Move / Delete** for notebooks and folders (no copy); **Pin / Unpin** notebooks with a Pinned mode; **Recents** mode (max 20, ids-only prefs store); **Sort** by Name or Last-modified (`updatedAt`), asc/desc; card cover = last-open page snapshot. No search. |
| Cold launch | Lands in the library at the last browse folder/mode; **if a notebook was open when the app was last closed or killed, it is reopened automatically on top of the library** (single `last_open_notebook_id` in the browse-state prefs, cleared on normal notebook close; dropped silently if the notebook no longer exists). No deeper stack. |
| New notebook | Full-screen "New notebook" screen: name pre-filled `YYYYMMDD_HHmmss` (editable; whitelist `[a-zA-Z0-9_\-. ]`, reject `.`/`..`, non-empty, unique in target folder), template radio, CREATE → creates in the current folder and opens it. |
| DB access | Room + KSP with the SQLCipher support factory, wrapped in the non-destructive open-helper factory — same stack as today. |
| UI/UX | The existing e-ink design system, verbatim: inkBlack/paperWhite/inkLight/borderGray palette (no colour anywhere in v0 — the ink itself is black), Tabler outline icons, dimen-driven tap targets, TopGuard, no ripple/animation/elevation. |
| Test devices | Nomad, NA5C, MIP11 (table above). |
| g-paper changes | Allowed from Paper phases: fix there, bump 0.1.x, republish to mavenLocal, pin. |

## Non-goals for v0 (do not build, do not scaffold "for later")

No search · no handwriting recognition · no export of any kind · no backup/restore · no import
(`.soil` open-with, share-to) · no scratch pad · no calendar · no tasks / routines · no Today screen ·
no page index / TOC · no headings, text, lines, shapes, links, sticky notes, documents, proofread ·
no clipboard · no per-notebook encryption · no passphrase rotation UI · no template library ·
no pen colour / width / style panels · no toolbar customisation · no landscape · no launch-restore of
a whole surface stack (v0 restores the last browse folder + mode, and **reopens the last-open notebook if
one was open when the app died/closed** — a single id, not a stack) · no Drive · no telemetry (`notebook_activity`) · no compactor · no
`undo_redo_state` persistence.

## Deliberately NOT carried over

**The governing idea:** Notesprout's original design threw in a lot of "people would want this" — features
the user did not personally want. Paper starts from bare paper and adds **only what the user wants**;
the Non-goals list above is that principle in action, and it is not a backlog. Nothing gets added to Paper
because it exists in Notesprout. Below that, the *technical* mistakes not to repeat, seeded from
`docs/soil-file-format.md` Part XI and `~/git/g-paper/PLAN.md`:

1. **Layers.** The page owns its content. No `layer` type, no locked base layer.
2. **Legacy JSON on the object path** (`data`, `boundingBox` columns, JSON payload readers, lazy
   conversion). Columnar + `blob` from day one; there is nothing to convert.
3. **Sibling-copy engines** (`RattaNotebookView` vs `GenericNotebookView`). g-paper owns the canvas;
   the host has exactly one notebook view class.
4. **Nullable-lambda listeners.** Interfaces with default no-ops (as g-paper does).
5. **Per-sample stroke timestamps in storage.** Format B geometry only (+ optional pressure/tilt channels).
6. **A `NotebookActivity` god-object.** Split responsibilities from the start (see Architecture →
   Notebook screen collaborators). Hard cap: no file over ~800 lines without a written reason.
7. **Denormalised geometry.** Stroke bounds recomputed at load; never persisted.
8. **Default-FQCN polymorphic serialization.** Any `@Serializable` sealed hierarchy gets explicit
   `@SerialName`s.
9. **Hardcoded button sizes / colours / borders.** Dimens + colours + styles are the only source.
10. **Plaintext names anywhere outside the index.** Prefs hold ids and settings only.
11. **`runBlocking` on the UI thread. `org.json`. `Log.d` direct.** Same rules as CLAUDE.md.
12. **Multiple `activity_main.xml` width variants.** One layout that fits the narrowest test device
    (MIP11 at its dp width — measure in Phase 2); if it can't, ask before adding a variant.

(Add to this list only when a concrete technical trap is identified; feature-level "don't want it" belongs in Non-goals.)

---

## Architecture

### Project layout

```
apps/notesprout_paper/
├── settings.gradle.kts            rootProject "notesprout_paper", include(":app"); repos: google, mavenCentral,
│                                  mavenLocal(), BOOX maven (insecure http, for gpaper-onyx's transitive SDK)
├── build.gradle.kts               plugin versions (AGP 8.11.1, Kotlin 2.2.20, KSP 2.2.20-2.0.4, serialization)
├── gradle.properties              org.gradle.java.home=Temurin-17, android.enableJetifier=true, jvmargs
├── gradle/ gradlew gradlew.bat    copied from apps/notesprout_android (Gradle 8.14 wrapper)
├── PAPER_PLAN.md                  this file
├── CLAUDE.md                      Paper's project intelligence (standing rules, build facts) — Phase 0 creates it
├── docs/                          subsystem docs, one per area as they are built (data.md, library.md, notebook.md, crypto.md)
└── app/
    ├── build.gradle.kts           namespace + applicationId com.symmetricalpalmtree.notesprout, minSdk 29,
    │                              compileSdk/targetSdk 35, arm64-v8a only, viewBinding + buildConfig,
    │                              debug applicationIdSuffix ".dev" + versionNameSuffix "-dev", jniLibs pickFirsts (libc++_shared.so)
    ├── proguard-rules.pro
    └── src/
        ├── main/AndroidManifest.xml
        ├── main/kotlin/com/symmetricalpalmtree/notesprout/
        │   ├── PaperApplication.kt          registers OnyxEngine + RattaEngine (g-paper) — nothing else at startup
        │   ├── core/                        Slog, TopGuard, IndexGuard, dimens helpers, ActionSheetDialog, gestures/
        │   ├── crypto/                      GlobalKey, SecurePrefs, PassphraseStore, DerivedKeyStore, RawKeyDerivation,
        │   │                                SoilCrypto (probe/verify/open factories), AttemptLimiter, KeySession
        │   ├── data/                        SoilFile, soil/ (SoilDatabase, SoilObjectEntity, SoilDao, SoilSchema,
        │   │                                NotebookMeta, StrokeCodec, NonDestructiveOpenHelperFactory), index/
        │   │                                (IndexDatabase, ObjectEntity, ObjectDao, IndexRepository, ListIds), recents/,
        │   │                                prefs/ (BrowseState, SortPrefs), template/ (BuiltInTemplates renderer)
        │   ├── bootstrap/                   BootstrapActivity, RecoveryKeyActivity, UnlockActivity
        │   ├── library/                     LibraryActivity (+ grid adapter, breadcrumb, pagination, modes), NewNotebookActivity,
        │   │                                FolderPicker (move destination), dialogs
        │   └── notebook/                    NotebookActivity + collaborators (see below)
        ├── main/res/                        values/{colors,dimens,styles,themes,strings}.xml, values-sw720dp/dimens.xml,
        │                                    drawable/ic_*.xml (Tabler, copied from apps/notesprout_android as needed),
        │                                    layout/*.xml
        └── test/kotlin/...                  JVM tests (codec, sentinel ids, sort, recents, name validation, template renderer geometry)
```

The Kotlin source root is `src/main/kotlin` (as in the reference app).

### Data model

**Global index `notesprout.db`** (`getExternalFilesDir(null)/notesprout.db`, SQLCipher under the global key
from the first byte; Room, `user_version` = 1):

```sql
CREATE TABLE objects (
    id        TEXT    NOT NULL PRIMARY KEY,   -- UUIDv4 or a sentinel id
    type      TEXT    NOT NULL,               -- 'folder' | 'notebook' | 'list' | 'list_item'
    name      TEXT    NOT NULL,               -- display name ('' for list_item)
    parentId  TEXT,                           -- NULL = root
    createdAt INTEGER NOT NULL,               -- epoch ms
    updatedAt INTEGER NOT NULL,               -- epoch ms; bumped ONLY by real edits (name, ink, page insert/delete, move)
    deletedAt INTEGER,                        -- NULL = alive
    pageCount INTEGER,                        -- notebook
    flags     INTEGER,                        -- notebook: bit0 = encrypted (always 1 in v0)
    keyScope  TEXT,                           -- notebook: 'GLOBAL' (always, v0)
    templateKind TEXT,                        -- notebook: 'BLANK'|'LINED'|'DOTTED'|'GRID' (mirror for the card; source of truth is the .soil)
    blob      BLOB,                           -- notebook: cover snapshot, WEBP q100
    refId     TEXT,                           -- list_item → member id
    sortOrder INTEGER                         -- list_item → position in its list
);
CREATE INDEX index_objects_parentId_type_deletedAt ON objects(parentId, type, deletedAt);
```

- Sentinel: `PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564"` (`pinned`), created on demand by an
  idempotent `ensurePinnedListExists()` at launch — never by a migration.
- Pin toggle = insert/hard-delete a `list_item` (membership edges are the one routine hard delete).
  Deleting a notebook scrubs its edges first (`DELETE FROM objects WHERE type='list_item' AND refId=?`).
- Cover rule: v0 has only GLOBAL scope, so covers are always stored (same key protects both). Never
  store any content other than the cover.
- Every read for a listing/card must be answerable from this row alone (never open a `.soil` to draw a card).

**Notebook `.soil`** (`getExternalFilesDir(null)/Garden/<uuid>.soil`; **`soilFile(context, id)` in
`data/SoilFile.kt` is the only path constructor**; SQLCipher under the global key; Room; `user_version` = 1;
`journal_mode=WAL`, `auto_vacuum=INCREMENTAL`):

```sql
CREATE TABLE notebook (
    id          TEXT    NOT NULL PRIMARY KEY,
    parentId    TEXT    NOT NULL,             -- '' for the notebook meta row
    type        TEXT    NOT NULL,             -- 'notebook' | 'page' | 'template' | 'stroke'
    "order"     INTEGER NOT NULL DEFAULT 0,   -- ALWAYS double-quote in SQL, backtick in ContentValues
    createdAt   INTEGER NOT NULL,
    updatedAt   INTEGER NOT NULL,
    deletedAt   INTEGER,
    -- columnar payload, wide + sparse, all nullable
    text        TEXT,       -- notebook: title · template: name
    refId       TEXT,       -- notebook: lastOpenedPage id · page: template row id
    width       REAL,       -- page / template: px
    height      REAL,       -- page / template: px
    color       TEXT,       -- stroke: '#RRGGBB' or '#AARRGGBB'
    strokeWidth REAL,       -- stroke: px
    style       TEXT,       -- stroke: g-paper StrokeStyle name ('PEN' in v0; unknown → PEN)
    flags       INTEGER,    -- reserved (NULL in v0)
    blob        BLOB        -- stroke: format-B geometry · template: WEBP q100
);
CREATE INDEX idx_notebook_parent_order ON notebook(parentId, "order", deletedAt);

CREATE TABLE notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL);
```

Hierarchy: `notebook` row (`parentId=''`) → `page` rows (`parentId` = notebook row id, ordered by
`"order"`) → `stroke` rows (`parentId` = page id). One `template` row (`parentId` = notebook row id).
Page queries: `WHERE parentId = :pageId AND type='stroke' AND deletedAt IS NULL ORDER BY "order"`.

`notebook_meta.json` (kotlinx.serialization, lenient decode, `ignoreUnknownKeys = true`), refreshed on
create / open / close / rename / move — same field set as the reference `NotebookMeta` so the file
stays in the family: `formatVersion=1, notebookId, name, createdAt, updatedAt, encrypted=true,
keyScope="GLOBAL", cover=null, folderPath: List<FolderRef(id,name,parentId)>, exportedAt=null,
appVersionCode, textDocument=false`.

**Stroke codec** — format B exactly as `docs/soil-file-format.md` Part V: `byte0 = version 1`
(plaintext), then `zlib{ flags:u8 | (x:f32,y:f32[,pressure:f32][,tilt:f32]) × N }` little-endian,
`flags` bit0 = pressure present, bit1 = tilt present. Paper **writes flags = 0x03 when g-paper reports
pressure/tilt** (it always does; values default 1f/0f), reads any combination, derives stride from
flags, skips unknown channels, and **bails the inflate loop on a zero-progress round**. Every blob
decode is guarded per row (a bad stroke is dropped, the page still renders). Pure Kotlin
(`java.util.zip` only), JVM-tested with round-trip + corrupt-input tests. Port from
`apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/core/StrokeCodec.kt` (+ its test).

**Mapping to g-paper:** `Stroke(id, points=[StrokePoint(x,y,pressure,tilt,timeMillis=0)], color=ARGB Int,
width=strokeWidth, style=StrokeStyle.valueOf(style) ?: PEN)`. Colour column ↔ ARGB int via a single
`InkColorCodec` (`#RRGGBB`/`#AARRGGBB` ⇄ Int). Ids: the g-paper stroke id **is** the row id.

**Recents** — `SharedPreferences("paper_recents")`, key `entries`, JSON `List<RecentEntry(notebookId,
timestamp)>`, max 20, most-recent first; names resolved against the index at read time; missing/deleted
ids pruned on read. **Browse state** — `SharedPreferences("paper_view_state")`: `folderId`, `mode`
(NORMAL/PINNED/RECENTS), `lastOpenNotebookId` (set in `NotebookActivity.onCreate`, cleared on normal close;
read once by `LibraryActivity` on cold launch — `savedInstanceState == null` — to relaunch that notebook). **Sort** — `SharedPreferences("paper_sort")`: `field` (NAME/MODIFIED),
`order` (ASC/DESC). Ids and enums only — never names.

### Key lifecycle (v0 subset of `docs/encryption.md`)

- **Global key**: minted at first launch (`GlobalKey.mint()` — 160-bit, Crockford base32, `NSPT-` +
  8 groups of 4, dashes part of the string), stored in `SecurePrefs` (androidx `security-crypto`
  `EncryptedSharedPreferences`, one lock + one cached instance per file, one retry for the boot-time
  Keystore transient). The Keystore protects the **cache** only; the key is the passphrase (UTF-8 bytes).
- **Raw-key cache** (`DerivedKeyStore`): per file id (`__notesprout_index__` for the index, the notebook
  UUID for a `.soil`), 32-byte PBKDF2-HMAC-SHA512 ×256,000 over the file's first-16-byte salt; open via
  `PRAGMA key = "x'<hex>'"`. Miss → open with the passphrase this once, derive in the background, store.
  Invalidate on delete. **Never format with the default locale** (`String.format(Locale.ROOT, …)`).
- **`SoilCrypto`**: `probe(file)` → `Invalid | Plaintext | Encrypted` (read header; missing/empty =
  Invalid); `verify(file, passphrase|rawKey)` → false for missing/empty; `openFactory(...)` for Room and
  `openRaw(...)` — **every** one wrapped by `NonDestructiveOpenHelperFactory` / a non-deleting
  `DatabaseErrorHandler`; opens **require the file to exist and be non-empty**; creation has its own
  explicitly named entry points used only by the new-notebook and new-index bootstrap.
- **`AttemptLimiter`**: escalating lockout on failed unlock attempts (counter in SecurePrefs; port the
  thresholds from the reference `crypto/AttemptLimiter.kt`).
- **Open state machine (index):** `BootstrapActivity` (not on the back stack, `noHistory`) →
  probe: `Invalid` (no file) → mint key (if none) → create encrypted → derive+cache → show
  `RecoveryKeyActivity` (first launch only) → `LibraryActivity`; `Encrypted` → cached passphrase? →
  raw key (verify!) → open → `LibraryActivity`; verify fails or no cache → `UnlockActivity` (passphrase
  entry, rate-limited) → cache → open. `Plaintext` is impossible for Paper (there is no plaintext mode)
  → treat as an error screen, never open it. **`IndexGuard.ready(activity)`** first thing in every
  Activity that touches the index (bounce to `BootstrapActivity` if the index isn't open — a
  process-death rebuild lands here). No repair-before-probe swap sweep is needed in v0 (no in-place
  migrations exist), but structure `Bootstrap` so one can be inserted first later.
- **Leak hygiene:** passphrases never logged / never in Intents / never in prefs other than SecurePrefs;
  covers only in the encrypted index; recents/browse/sort prefs are ids only.

### Screens

| Screen | Class | Notes |
|---|---|---|
| Bootstrap | `bootstrap/BootstrapActivity` | Launcher activity; opens the index (state machine above); forwards; `noHistory`; the ONLY thing that opens the index. |
| Recovery key | `bootstrap/RecoveryKeyActivity` | First launch only: shows the `NSPT-…` key, "I have written this down" checkbox → Continue. Copy-to-clipboard button. Never shown again (SecurePrefs flag). |
| Unlock | `bootstrap/UnlockActivity` | Passphrase field + Unlock; error text on failure; lockout countdown from `AttemptLimiter`; no "forgot" path in v0 (the recovery key IS the passphrase). |
| Library | `library/LibraryActivity` | Top bar swaps by mode (breadcrumb / "Pinned" / "Recent"); bottom bar constant: `[Pinned] [Recents]  |< < n/n > >|  [Sort] [+Folder] [+Notebook]`. Paginated grid of folder cards + notebook cards. Long-press → context action sheet. Picker mode for Move. |
| New notebook | `library/NewNotebookActivity` | Name + template radio + CREATE. |
| Notebook | `notebook/NotebookActivity` | Full-bleed `PaperView` in a `FrameLayout`; top toolbar `[← back] [pen] [eraser] [lasso]`; bottom strip `"<name>   n / N"`. Immersive (hide system bars) — but see BOOX status-bar note in TopGuard. |

### Notebook screen collaborators (no god-object)

- `NotebookActivity` — lifecycle, wiring, chrome, exclusion rects, `IndexGuard`.
- `notebook/NotebookSession` — owns the open `SoilDatabase`, page list (`List<PageRef(id, order, width, height)>`),
  current index, template bitmap; suspend functions `open()`, `loadPage(i)`, `insertPage(after: Boolean)`,
  `deletePage(i)`, `saveLastOpened(pageId)`, `refreshMeta()`, `seal()`. All DB on `Dispatchers.IO`.
- `notebook/StrokeStore` — mirrors g-paper callbacks to rows: `commit(stroke)`, `erase(ids)` (soft delete),
  `move(ids, dx, dy)` (rewrite blobs via `Stroke.translated`), `loadPage(pageId): List<Stroke>`. Bumps the
  index row's `updatedAt` on the first edit of a session (debounced) — the `updatedAt` discipline.
- `notebook/UndoRedoStack` — in-memory, per page: `Drew(stroke)`, `Erased(strokes)`, `Moved(ids, dx, dy)`;
  replays through `paper.addStrokes/removeStrokes` **and** `StrokeStore`.
- `notebook/PageGestures` — a `View.OnTouchListener` on the paper view: single-finger swipe (page flip),
  two-finger horizontal swipe (insert), multi-finger stationary double-tap (undo/redo), finger long-press
  (delete sheet). Every recogniser: refuse to start while `paper.isPenActive`, re-check at finger-up,
  escrow tap-actions by `PEN_ACTIVE_TAIL_MS`; **stand down entirely while a lasso selection is active**
  (g-paper claims finger input then). Consumes nothing it doesn't act on.
- `notebook/CoverSnapshot` — `paper.renderToBitmap()` → scale to a card-friendly max edge (512 px long
  side) → WEBP q100 → `IndexRepository.setCover(id, bytes)`; called on close/seal and on `onStop`.
- `notebook/NotebookToolbar` — the three tool buttons + back; `onToolChanged` sync; reports its rect
  (+ the bottom strip's) for `setExclusionRects`.

### Gestures (exact thresholds — port, don't reinvent)

- **Page flip** (single finger, from `NotebookActivity.evaluatePageFling` in the reference): horizontal
  dominant (`|dx| > |dy|`); `|dx| ≥ 0.30 × screenWidth` required; qualifies if `|vx| ≥
  scaledMinimumFlingVelocity × 1.0` **or** `|dx| ≥ 0.50 × screenWidth`; direction from `dx` sign only.
  `dx < 0` → next, `dx > 0` → previous. (Phase 4 question: swipe-left on the last page — insert a page
  like the reference, or do nothing?)
- **Insert** (two fingers, same gates on the two-finger centroid): `dx < 0` → insert **after** current
  and navigate to it; `dx > 0` → insert **before**.
- **Undo / redo** (from `CalendarActivity.handleMultiFingerDoubleTap` in the reference): arm on
  `ACTION_POINTER_DOWN` (2 or 3 fingers; ≥ 4 disarms), stationary = centroid moved ≤ `touchSlop`, tap
  duration ≤ `ViewConfiguration.getLongPressTimeout()`, second tap within `getDoubleTapTimeout()` and
  ≤ `doubleTapSlop` of the first. 2 fingers → undo, 3 → redo. **BOOX sends `ACTION_CANCEL` instead of
  `ACTION_UP` for 3-finger touches** — a cancel on an armed, stationary 3-finger gesture counts as the
  tap. Call `paper.releaseRender()` before applying.
- **Delete page** (single finger long-press ≥ `getLongPressTimeout()`, stationary ≤ `touchSlop`, gated at
  down and at fire): `ActionSheetDialog` with one row "Delete page" → `AlertDialog` "Delete this page?
  Its ink cannot be recovered." [Delete] [Cancel].
- **Two-finger swipe down / link back-swipe / toolbar-toggle taps:** not in v0.

### Design-system rules that apply here (summary; full text in root CLAUDE.md + `docs/design-system.md`)

Palette from `res/values/colors.xml` only; Tabler outline icons 24dp inkBlack stroke 2 (copy the
existing `ic_*.xml` files from `apps/notesprout_android/app/src/main/res/drawable/` — do not download
new ones without checking there first); every icon button `Widget.Notesprout.ToolbarButton` sized by
`@dimen/toolbar_button_size` (44dp / 62dp on sw720dp) with a long-press hint = content description;
1dp inkBlack borders, 4dp radius; no ripple / elevation / animation
(`android:windowAnimationStyle="@null"`); `AppCompatButton` with explicit backgrounds; TopGuard on every
tappable top row (`applyInsetPadding` where system bars show, `applyRootPadding` on immersive screens; 0
on Ratta); disabled controls are hidden (GONE), never greyed; borderGray is invisible on e-ink — use
inkBlack for anything that must be seen. AlertDialogs use the reference `ThemeOverlay` pattern
(`docs/design-system.md`).

---

## Phases

### Phase 0 — Foundation & skeleton
**Status:** ✅ Complete

**Outcome:** Gradle project scaffolded, all Appendix B deps wired, g-paper 0.1.0 from mavenLocal,
theme/colors/dimens/styles/drawables ported, core utilities (Slog, TopGuard, IndexGuard stub,
ActionSheetDialog, Device), placeholder LibraryActivity, launcher icon (paper + green sprout),
CLAUDE.md + skill updated. Verified on SNN + NA5C + MIP11 — all three launch, portrait-locked,
no crashes. Labels: "Notesprout Paper" / "Notesprout Paper Dev".

**Goal:** an installable "Paper" app that builds against g-paper from mavenLocal, launches on all three
test devices, and shows a placeholder — plus the project scaffolding every later phase relies on.

**Deliverables**
1. `apps/notesprout_paper/` Gradle project as laid out in Architecture → Project layout (copy the
   wrapper + `gradle.properties` from `apps/notesprout_android`; add `mavenLocal()`; keep the BOOX
   repo + jetifier because `gpaper-onyx` needs them; `tools:replace="android:label"` in the manifest
   for the Onyx AAR label clash — see g-paper `docs/integration-guide.md`; **`~/git/g-paper/consumer-smoke/`
   is a committed, working consumer project — mirror its `settings.gradle.kts` / `app/build.gradle.kts`**).
2. Dependencies exactly per Appendix B. Verify `~/.m2/repository/com/symmetricalpalmtree/gpaper/*/0.1.0`
   exists; if not, run `./gradlew publishToMavenLocal` in `~/git/g-paper` first.
3. `PaperApplication` registering `OnyxEngine.register(this)` and `RattaEngine.register()`.
4. Theme (`Theme.Notesprout` on `Theme.AppCompat.Light.NoActionBar`, no window animations),
   `colors.xml`, `dimens.xml` (+ `values-sw720dp`), `styles.xml` (ToolbarButton etc.), `strings.xml` —
   ported from the reference app, trimmed to what exists.
5. `core/Slog.kt`, `core/TopGuard.kt` (with the Ratta = 0 rule), `core/IndexGuard.kt` (stub that is
   wired for real in Phase 1), `core/ActionSheetDialog.kt`.
6. A placeholder `LibraryActivity` (launcher for now) showing "Paper" centred, portrait-locked.
7. `apps/notesprout_paper/CLAUDE.md` — Paper's project intelligence: purpose, pointer to this plan,
   standing rules (the Standard Constraints + design rules restated briefly or linked to root
   CLAUDE.md), build + install commands, the three test devices with serials, g-paper version pin,
   the "read g-paper docs first" rule. Also add a short **"Paper rebuild"** section to the root
   `CLAUDE.md` pointing at `apps/notesprout_paper/PAPER_PLAN.md` and `CLAUDE.md`, and extend
   `.claude/skills/device-build-install/SKILL.md` with a "Paper" subsection (build path, applicationId,
   the three devices).
8. `.gitignore` entries for the new project (`build/`, `.gradle/`, `local.properties`, `*.iml`).

**Questions to resolve at phase start**
- Confirm the debug launcher label ("Paper dev") and the icon (reuse Notesprout's launcher icon, or a
  plain placeholder?).
- Confirm `versionCode 1` / `versionName "0.1.0-paper"`.

**Tests**
- `./gradlew :app:assembleDebug` green; `./gradlew :app:testDebugUnitTest` green (one trivial test so
  the harness is proven).
- Install on SNN, NA5C, MIP11; app launches, portrait, placeholder visible, no crash in
  `logcat -b crash`. Logcat shows g-paper engine registration lines (`Log.i`).

**Close-out:** status ✅ + Outcome; CLAUDE.md files written; memory updated; commit + push `paper`.

---

### Phase 1 — Container, index, encryption & the launch spine
**Status:** ✅ Complete (commit 6635cfe; user-verified SNN + NA5C + MIP11 2026-08-15)

**Outcome:** Both databases exist encrypted-from-first-byte with the greenfield schemas (`docs/data.md`);
crypto stack + non-destructive open wrapper + `KeyOpener` verify-then-fallback (`docs/crypto.md`);
`PaperIndex` state machine; Bootstrap → RecoveryKey → Library / Unlock; `IndexGuard` real; format-B
`StrokeCodec` (+ pressure/tilt channels) + `InkColorCodec`; library shell (breadcrumb, bottom bar,
pager, empty state, browse-state prefs); debug-only ⋯ menu (Show recovery key / Forget cached key).
29 JVM tests green. MIP11: fresh install → key screen → library; kill+relaunch → library; forget →
Unlock; wrong key → error with the index md5 unchanged; lower-case right key → library; pulled
`notesprout.db` opens in the Mac `sqlcipher` CLI with the passphrase (portability proven).
Phase-start answers: reference wording + required checkbox; AttemptLimiter verbatim; debug tools yes.

**Goal:** the two databases exist encrypted-from-first-byte with the greenfield schemas; the launch
state machine (bootstrap → recovery key → library / unlock) works; the stroke codec is done and tested;
the library shows an empty root ("No notebooks yet") but can't create anything yet.

**Deliverables**
1. `data/SoilFile.kt` (`soilFile(context, id)`), `data/soil/SoilSchema.kt` (DDL constants + `SOIL_VERSION=1`),
   Room `SoilDatabase` (`SoilObjectEntity` = the `notebook` table, `SoilDao`, `notebook_meta` via raw
   SQL in `NotebookMetaStore`), WAL + incremental vacuum PRAGMAs, `seal()` sequence (flush → refresh
   meta → `wal_checkpoint(TRUNCATE)` → close → delete stray `-journal`; no compactor in v0).
2. `data/index/`: Room `IndexDatabase` (`ObjectEntity` for `objects`, `ObjectDao`, `IndexRepository`
   with: root/children listing (folders + notebooks, filtered by `deletedAt IS NULL`), get by id,
   create/rename/move/soft-delete folder & notebook, ancestry walk (cycle-guarded, 50 hops), pinned
   list ensure/add/remove/list, cover set/get (blob-free summary reads for cards: a `NotebookSummary`
   projection without `blob` for lists; cover fetched per card lazily), `pageCount` update,
   `updatedAt` bump helper). `ListIds.PINNED_LIST_ID` sentinel.
3. `crypto/`: `GlobalKey` (mint, Crockford base32), `SecurePrefs`, `PassphraseStore`, `DerivedKeyStore`,
   `RawKeyDerivation`, `SoilCrypto` (probe / verify / open factories, exists-guards, creation entry
   points), `AttemptLimiter`, `KeySession` (process-RAM passphrase). All open helpers wrapped by
   `data/NonDestructiveOpenHelperFactory` (port from the reference) — **build the wrapper first, then
   the crypto**, and test that a wrong key surfaces as an error while the file's bytes are untouched.
4. `bootstrap/BootstrapActivity` (launcher; the state machine in Architecture → Key lifecycle),
   `RecoveryKeyActivity`, `UnlockActivity`; `core/IndexGuard` wired for real (`IndexDatabase` open latch).
5. `core/StrokeCodec.kt` + `InkColorCodec.kt` (pure Kotlin) + JVM tests.
6. `LibraryActivity` becomes a real screen shell behind `IndexGuard`: top breadcrumb bar ("Notebooks"),
   bottom bar with the buttons present but only pagination + an empty-state label working; **no
   creation yet** (Phase 2). Browse-state prefs read/written (folder id + mode).
7. `docs/data.md` + `docs/crypto.md` in `apps/notesprout_paper/docs/` describing exactly what was
   built (schemas, key lifecycle, guards).

**Questions to resolve at phase start**
- Recovery-key screen wording and whether to require the "I have saved this key" checkbox before
  Continue (recommended: yes).
- `AttemptLimiter` thresholds: port the reference values verbatim, or simplify (e.g. 5 attempts →
  30 s, doubling)? Show the reference values when asking.
- Should the debug build expose a "Show recovery key" entry (debug-only) to make reinstall testing
  practical? (Recommended: yes, debug-only, in a debug overflow on the library — removed from release
  by source set.)

**Tests**
- JVM: codec round-trip incl. pressure/tilt flags, empty stroke, truncated blob, zero-progress
  inflate bail; Crockford alphabet + length of minted keys; `InkColorCodec` both directions; sentinel
  id constant; hex formatting is locale-independent.
- Device (SNN, NA5C, MIP11): (1) fresh install → recovery key screen → library empty state;
  (2) kill + relaunch → straight to library (no prompt); (3) `adb shell pm clear` is NOT a valid test
  (it wipes the files too) — instead **uninstall + reinstall** (files under `getExternalFilesDir` are
  removed on uninstall too on these devices; so this test is: note the key, uninstall, reinstall →
  fresh key screen; that proves nothing about unlock). To test unlock: with the debug "Show recovery
  key" entry, note the key, then clear only the Keystore-backed prefs via a debug "Forget cached key"
  action → relaunch → Unlock screen → wrong key shows an error and the file survives → right key opens.
  (Both debug actions are Phase-1 deliverables in the debug source set.) (4) `sqlcipher` CLI on a
  pulled `notesprout.db` opens with the key (portability acceptance test — Mac has `sqlcipher`? if
  not, skip and note).

**Close-out:** as protocol. Update the memory file: Phase 1 done, key facts.

---

### Phase 2 — The library: folders, notebooks, sort, cards
**Status:** ✅ Complete (user-verified SNN + NA5C)

**Goal:** a working library — browse folders, create/rename/move/delete folders and notebooks, sort,
paginated grid, cards with placeholder covers — and notebook creation that writes a real `.soil` (with
its template row and page 1). Opening a notebook launches a stub `NotebookActivity` that just shows the
name (Phase 3 fills it).

**Deliverables**
1. `LibraryActivity` full chrome: breadcrumb top bar (tap a crumb to jump up; back press goes up one
   folder, exits at root); constant bottom bar `[Pinned] [Recents] |< < n/n > >| [Sort] [+Folder] [+Notebook]`
   (Pinned/Recents buttons exist but are wired in Phase 5 — until then they toast "Later"). Pagination
   computes cards-per-page from the real grid size after layout (measure a card, integer-divide), never
   scrolls; page buttons hidden (GONE) when there is one page.
2. Cards: folder card (folder icon + name), notebook card (cover image or template-kind placeholder +
   name + last-modified date/time using `DateFormat.getMediumDateFormat/getTimeFormat`). Tap opens.
   Long-press → `ActionSheetDialog`: notebook = Rename · Move · Delete (Pin arrives Phase 5); folder =
   Rename · Move · Delete.
3. Rename dialog (validation as Locked decisions; duplicate check against the item's own parent,
   excluding itself; no-op if unchanged). Delete confirm dialogs (folder: "Delete "X"? This will
   permanently remove all notebooks and subfolders inside it. This cannot be undone."; notebook: "Delete
   "X"? This cannot be undone."). Delete = index soft-delete (recursive for folders) + remove `.soil`
   files (+ `-wal`/`-shm`/`-journal` sidecars) + scrub pinned edges + invalidate raw-key cache entries.
4. Move = picker mode over the same grid (top bar "Move to…" + Cancel; bottom bar shows only pagination
   + "Move here"; the item being moved and its own subtree are not enterable); collision → "A
   [notebook/folder] named "X" already exists here." and stay in picker.
5. `NewNotebookActivity`: name field (pre-filled timestamp), template radio (Blank / Lined / Dotted /
   Grid, Blank default), CREATE. Creation (on IO): mint UUID → `SoilCrypto.createEncrypted(soilFile)` →
   schema → notebook row (`text=name`) → template row (`data/template/BuiltInTemplates.render(kind, w, h)`
   → WEBP q100 blob) → page 1 (`width/height` = current portrait screen px, `refId` = template row id,
   `"order"=0`) → `notebook_meta` → seal → index row (`pageCount=1`, `flags=1`, `keyScope=GLOBAL`,
   `templateKind`) → open it. Duplicate name in the target folder → toast, stay.
6. `data/template/BuiltInTemplates.kt`: procedural renderers. Lined: horizontal 1px inkBlack rules
   every 8 mm at the device density (`8 * dpi / 25.4` px), starting after a top margin equal to one
   spacing; Dotted: 1.5 px dots on an 8 mm grid; Grid: 1 px lines both axes every 8 mm; Blank: null
   (no template row? — see question). Pure geometry function is JVM-testable; the bitmap painting is
   thin.
7. Sort: `[Sort]` opens an action sheet: Name ↑ / Name ↓ / Modified ↑ / Modified ↓ (current one marked
   with a check icon); folders always listed before notebooks. Persisted.
8. Browse state restore on launch (last folder; if it no longer exists → root).
9. `docs/library.md`.

**Questions resolved**
- Blank template: **no row; page `refId = ""` means blank** — costs nothing, avoids a useless blob.
- Rule spacing: **8 mm** for Lined/Grid/Dotted. Top margin = one spacing for Lined.
- Card grid density: **measured dynamically** from real grid container (min card width 140dp, aspect
  1:1.4). Columns × rows = cardsPerPage.
- Bottom-bar order: **as proposed** — `[Pinned] [Recents]  |< < n/n > >|  [Sort] [+Folder] [+Notebook]`.

**Tests**
- JVM (all pass): name validation (`NameValidationTest`), sort comparators (`SortComparatorTest`),
  template geometry (`TemplateGeometryTest`) — line positions, dot grid, grid X positions, spacing
  calculation, bounds checking.
- Device: create folders (nested), notebooks with each template, rename (incl. duplicate rejection),
  move (incl. collision + can't move into own subtree), delete (files gone from `Garden/`), sort all
  four ways, pagination with > one page of cards, back-press behaviour, kill + relaunch lands in the
  same folder.

**Outcome** (Phase 2)
- New files: `data/prefs/SortPrefs.kt`, `data/prefs/RecentsPrefs.kt`, `data/template/BuiltInTemplates.kt`,
  `library/NewNotebookActivity.kt`, `library/FolderPickerActivity.kt`, `library/LibraryGrid.kt`,
  `notebook/NotebookActivity.kt` (stub).
- New layouts: `activity_new_notebook.xml`, `activity_notebook.xml`, `activity_folder_picker.xml`,
  `card_notebook.xml`, `card_folder.xml`.
- Copied icons from reference app: `ic_folder`, `ic_edit`, `ic_trash`, `ic_check`, `ic_move_page`.
- New tests: `NameValidationTest`, `SortComparatorTest`, `TemplateGeometryTest`.
- `LibraryActivity` fully rewritten: breadcrumb navigation, sort, new-folder dialog, new-notebook
  launch, long-press action sheets, move picker, delete with file cleanup, cold-launch restore.
- `docs/library.md` written.
- g-paper version: unchanged (0.1.0).

---

### Phase 3 — The notebook: g-paper, one page, persistence, cover
**Status:** ✅ Complete (user-verified SNN/NA5C/MIP11 — commit hash below)

**Goal:** open a notebook and write on page 1 with pen / eraser / lasso on all three engines; ink
persists across close/reopen; the library card shows the last-open page as its cover.

**Deliverables**
1. `NotebookActivity` + `NotebookSession` + `StrokeStore` + `NotebookToolbar` + `CoverSnapshot` (see
   collaborators). Layout: `FrameLayout` with `paper.asView()` full-bleed, top toolbar overlay
   (`[← ] [pen] [eraser] [lasso]`, TopGuard applied — on BOOX the status bar overlays the window; the
   reference uses immersive + `applyRootPadding`; do the same), bottom strip overlay ("<name>   1 / 1",
   1dp inkBlack top border, small text). Both chrome rects → `setExclusionRects` (re-pushed after layout
   changes).
2. Open: `IndexGuard` → `OpeningOverlay`-style feedback is NOT needed in v0 (single notebook screen) →
   `NotebookSession.open()` on IO (SoilCrypto raw-key open, verify, load page list, template bitmap
   decode **bounded** (sampled decode, MAX 4096), last-open page) → `paper.setPageSize(w,h)`,
   `setTemplate(bitmap)`, `loadStrokes(store.loadPage(id))`. Missing file / index row → toast + finish
   (never create a ghost file).
3. Tool wiring: pen default; button states drawn as selected/unselected via a bordered background
   (no colour); `onToolChanged` keeps buttons honest. `releaseRender()` on every chrome tap.
4. Persistence: `onStrokeCommitted` → insert row (`"order"` = next); `onStrokesErased` → soft delete;
   `onSelectionMoved` → rewrite the moved strokes' blobs (`Stroke.translated`); `onPenLifted` → no-op
   in v0 (writes are already incremental). All on IO via a per-session `Channel`/serial coroutine so
   writes stay ordered. First edit of a session bumps the index `updatedAt` (debounced 2 s).
5. Lifecycle: `onResume → resumeDrawing()`, `onStop → CoverSnapshot + saveLastOpened`, back button /
   system back → `close()`: `CoverSnapshot`, `saveLastOpened`, `refreshMeta`, `seal()` on an
   application-scoped `NonCancellable` coroutine with an exception handler (each step guarded), then
   `finish()`. `onDestroy → paper.release()`. Recents `recordOpen` in `onCreate` / `recordClose` in
   close (store exists from Phase 1? — no: create `data/recents/RecentsManager` here; the UI mode
   comes in Phase 5).
6. Library card shows the cover (bounded decode of the blob at card size).
7. `docs/notebook.md`.

**Questions resolved (2026-08-15)**
- Pen width **3 px**, eraser radius **15 px** — raw px, not dp (matches the reference
  `OnyxNotebookView` pen and g-paper's `Stroke.DEFAULT_WIDTH` / `DEFAULT_ERASER_RADIUS_PX`).
- Toolbar: **flush at the top edge**, full width, 1dp inkBlack bottom border, TopGuard-padded;
  selected tool = the bordered button state.
- Bottom strip: **always visible**, static; any text change is deferred until the pen gate opens.

**Tests**
- Device (all three — this is the phase where the three engines are proven): write, erase, lasso
  select + drag, tap-away dismiss, chrome exclusion (ink can't land under the toolbar), close and
  reopen (ink back exactly), cover on the card, kill the process mid-session and reopen (no lost ink
  beyond the last stroke), palm on paper while writing (no page flip yet — nothing to gate, but no
  crashes), rotation is locked (verify).
- Ratta-specific: verify no ghosting/lag after ~5 min of writing (frame-silence rule: nothing repaints
  while `isPenActive`).
- Onyx-specific: leave the notebook, then the app; return — pipeline reclaims (`resumeDrawing`).

**Outcome** (Phase 3)
- New files: `notebook/{NotebookSession,StrokeStore,StrokeRows,CoverSnapshot,NotebookToolbar}.kt`,
  `core/Bitmaps.kt` (bounded decode, shared by template + card cover), test `StrokeRowsTest`
  (5 tests). `NotebookActivity` rewritten (full-bleed paper, overlays, exclusion rects,
  immersive, close sequence). `activity_notebook.xml` rewritten; icons `ic_pen/ic_eraser/ic_lasso`
  copied from the reference app. `SoilDao` gained `byIds` + `maxOrder`.
- Recents: `RecentsPrefs` (Phase 2) already covers `record`; no separate `RecentsManager` created —
  `NotebookActivity.onCreate` records the open. Recents UI is Phase 5.
- `updatedAt` discipline implemented as a trailing 2 s debounce on **every** edit (flushed on
  close), not only the first — so the card's "last modified" tracks a long session.
- Fixed a latent Phase 2 bug: `card_notebook.xml` cover `ImageView` had `layout_height=0dp` and no
  weight (never visible). Now `layout_weight=1`.
- g-paper: version unchanged (0.1.0) but the mavenLocal artifact predated g-paper's Phase 9
  commit (`smartLassoEnabled`, `onToolChanged`); republished from committed HEAD
  (`./gradlew publishToMavenLocal`, no source change). `GPaper` lives in `core.engine`.
- Claude smoke: engines `generic` (MIP11) / `ratta` (SNN) / `onyx` (NA5C) selected; open, tool
  taps, back-close, cover on card all verified by screencap. Ink itself awaits the user's hands.
- `docs/notebook.md` written.
- User verification (2026-08-15): every checklist item passed on all three devices. One finding:
  template features were too fine for e-ink (1 px rules, 1.5 px dots ≈ 0.13 mm at 300 ppi read as
  faint grey). Fixed: `BuiltInTemplates` sizes are now authored at mdpi and scaled by density
  (`lineWidthPx` = max(1, dpi/160) px, `dotRadiusPx` = max(1, 2·dpi/160) px) — affects newly
  created notebooks only (the template is baked into the file at creation). Covers are honest
  renders of the page (`renderToBitmap` → 512 px → card), so thin features look softer there by
  design; accepted.
- Phase 3 commits: a2e63b8 (build) + the template-size follow-up.

---

### Phase 4 — Pages: flip, insert, delete, undo/redo
**Status:** ⬜ Not started

**Goal:** the notebook is a stack of pages.

**Deliverables**
1. `PageGestures` (see Gestures) attached to the paper view; page turns via
   `clearForContentSwap()` + `loadStrokes()`; indicator updates ("n / N"); `saveLastOpened` on every
   turn.
2. Insert before/after (new page row with `"order"` renumbered among siblings in one transaction —
   simple approach: renumber all pages 0..N-1 after insert; pageCount mirror in the index; index
   `updatedAt` bump) → navigate to it.
3. Delete via long-press sheet + confirm: soft-delete the page and its strokes; renumber; if it was the
   only page, create a fresh blank page in its place; navigate to previous (or next if first);
   `pageCount` + `updatedAt` mirrors.
4. `UndoRedoStack` (in-memory per page) driven by the multi-finger double-taps; cleared on page turn
   and close. Undo of a move re-translates by −dx/−dy.
5. `docs/notebook.md` updated with the gesture contract.

**Questions to resolve at phase start**
- Swipe left on the last page: insert a new page (reference behaviour) or stop at the last page?
- Should undo/redo also cover page insert/delete? (Recommend: no — pages are structural; the confirm
  dialog is the safeguard.)
- Long-press timing / feedback: a short vibration on fire? (Recommend: none — meditative.)

**Tests**
- Device (all three): flip both ways with the exact thresholds (short swipes don't flip; palm during
  writing doesn't flip — the gate); insert before/after lands on the new page with the right count;
  delete middle/first/last/only page; undo/redo draw + erase + move; 3-finger on BOOX (the CANCEL
  path); everything survives close/reopen; last-open page restored.

---

### Phase 5 — Pin, Recents, browse-state polish
**Status:** ⬜ Not started

**Goal:** the remaining library features from the brief.

**Deliverables**
1. Pin/Unpin in the notebook card's action sheet; a small pin glyph on pinned cards; **Pinned mode**
   (top bar "Pinned" + X; grid of pinned notebooks in `sortOrder`; empty state "No pinned notebooks";
   back exits the mode). Directory stack untouched underneath.
2. **Recents mode** (top bar "Recent" + X; cards newest-first with immediate-parent-folder subtitle;
   empty state; pruning of dead ids on read). Opening from Recents keeps the mode; returning lands
   back in it.
3. Browse-state persistence covers mode (NORMAL/PINNED/RECENTS) and is restored on launch.
4. **Cold-launch reopen:** `lastOpenNotebookId` (Locked decisions → Cold launch) — `NotebookActivity`
   writes it in `onCreate`, clears it on normal close; `LibraryActivity` on cold launch (after the index is
   ready and browse state restored) launches that notebook if its index row is alive and its `.soil`
   exists, else clears the id. Never mint a ghost file here (Phase 1's exists-guards).
5. `docs/library.md` updated.

**Questions to resolve at phase start**
- Pinned order: manual (`sortOrder`, most-recently-pinned first) or follow the current sort? (Recommend:
  follow the current sort; `sortOrder` still recorded.)

**Tests**
- Device (all three): pin/unpin, pinned mode + empty state, recents after opening several notebooks
  (order, cap 20, pruning after delete), mode restore after kill; kill the process while a notebook is
  open → relaunch reopens that notebook on the right page, back lands in the library; close a notebook
  normally then relaunch → library only; delete a notebook that was last-open (from another path) → no crash.

---

### Phase 6 — Hardening, review, docs freeze
**Status:** ⬜ Not started

**Goal:** v0 is trustworthy enough to live in daily.

**Deliverables**
1. `/code-review` (high) over the whole `apps/notesprout_paper` tree; fix confirmed findings.
2. Data-loss audit checklist walked and recorded in `docs/crypto.md`: every open path wrapped;
   no create-capable open outside the two bootstrap entry points; missing file never loops into
   unlock; delete invalidates key cache; no passphrase in logs/intents/prefs; no names in prefs.
3. Perf pass on the slowest test device: library page render < 300 ms with 40 notebooks (blob-free
   listing, lazy covers); notebook open < 1 s warm.
4. Freeze `docs/*.md`, `CLAUDE.md`; write `apps/notesprout_paper/README.md` (short: what Paper is,
   how to build, what it is not).
5. Memory: mark v0 complete; list what the user asked to carry into v0.1.

**Tests:** full regression of every earlier phase's device checklist on all three devices.

---

## Appendix A — Constants

| Name | Value |
|---|---|
| `PINNED_LIST_ID` | `00000000-0000-0000-0000-70696e6e6564` |
| `INDEX_FILE_ID` (raw-key cache key for the index) | `__notesprout_index__` |
| Recovery-key prefix / shape | `NSPT-` + 8 groups of 4 Crockford base32 chars (160 bits) |
| KDF | PBKDF2-HMAC-SHA512, 256,000 iterations, 32-byte output, salt = file bytes 0..15 (stock SQLCipher 4) |
| Recents cap | 20 |
| Cover long edge | 512 px, WEBP q100 |
| Template rule spacing | 8 mm at device dpi (Phase 2 confirms) |
| Page swipe | min 0.30 × width; long 0.50 × width; velocity ≥ 1.0 × `scaledMinimumFlingVelocity`; horizontal-dominant |
| Pen gate tail | `PaperView.PEN_ACTIVE_TAIL_MS` (350 ms, from g-paper) |
| `.soil` `user_version` / index `user_version` | 1 / 1 |

## Appendix B — Allowed dependencies (anything else requires asking)

```
androidx.core:core-ktx:1.13.1
androidx.appcompat:appcompat:1.7.0
androidx.room:room-runtime:2.7.0, androidx.room:room-ktx:2.7.0, ksp androidx.room:room-compiler:2.7.0
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1
androidx.lifecycle:lifecycle-runtime-ktx:2.8.7
org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3
net.zetetic:sqlcipher-android:4.6.1, androidx.sqlite:sqlite:2.4.0
androidx.security:security-crypto:1.1.0-alpha06
com.symmetricalpalmtree.gpaper:gpaper-core:0.1.0, :gpaper-onyx:0.1.0, :gpaper-ratta:0.1.0   (mavenLocal)
testImplementation junit:junit:4.13.2
```
No Material Components. No ML Kit / ONNX / PdfBox / SymSpell / documentfile. The Onyx SDK + hiddenapibypass
arrive transitively through `gpaper-onyx` (its POM); Paper's build must still declare the BOOX maven repo
and enable jetifier.

## Appendix C — Build & install (Paper)

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
adb -s SN078D10012852 install -r app/build/outputs/apk/debug/app-debug.apk   # SNN
adb -s 92c16533       install -r app/build/outputs/apk/debug/app-debug.apk   # NA5C
adb -s 5HL21V5007384  install -r app/build/outputs/apk/debug/app-debug.apk   # MIP11
adb -s <serial> shell am start -n com.symmetricalpalmtree.notesprout.dev/com.symmetricalpalmtree.notesprout.bootstrap.BootstrapActivity
```
BOOX trap: `install -r` + immediate `am start` can leave the package disabled → `pm enable
com.symmetricalpalmtree.notesprout.dev`. BOOX logcat floods — use `adb logcat -G 16M` and a streaming
`logcat -s <TAG>` capture, never `-d` after the fact. Supernote Manta and Nomad share every
`ro.product.*` value — always target by serial.

## Appendix D — Reference map (where to look in the old code)

| Concern | Reference |
|---|---|
| Stroke codec + test | `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/core/StrokeCodec.kt`, `app/src/test/kotlin/.../core/StrokeCodecTest.kt` |
| Non-destructive open helper | `.../data/NonDestructiveOpenHelperFactory.kt` |
| Crypto | `.../crypto/{SoilCrypto,GlobalKey,SecurePrefs,PassphraseStore,DerivedKeyStore,RawKeyDerivation,AttemptLimiter,KeySession,KeyOpener}.kt`, `docs/encryption.md` |
| Bootstrap / onboarding | `.../BootstrapActivity.kt`, `.../OnboardingActivity.kt`, `.../core/IndexGuard.kt` |
| Index repository | `.../data/index/{IndexRepository,ObjectDao,ObjectEntity,ListIds,NotesproutDatabase}.kt` |
| notebook_meta | `.../data/{NotebookMeta,NotebookMetaStore}.kt` |
| Library chrome / cards / sort / recents / browse state | `.../MainActivity.kt`, `.../sort/*`, `.../state/AppStateManager.kt`, `.../data/recents/RecentsManager.kt`, `docs/mainactivity-and-recents.md` |
| Page swipe + insert + multi-finger undo/redo | `.../NotebookActivity.kt` (`evaluatePageFling`, ~L3720; two-finger insert ~L3875), `.../CalendarActivity.kt` (`handleMultiFingerDoubleTap` ~L1983) |
| Design system, TopGuard, dialogs | `docs/design-system.md`, `.../core/TopGuard.kt`, `.../ActionSheetDialog.kt`, `res/values/*.xml`, `res/drawable/ic_*.xml` |
| g-paper API / host duties | `~/git/g-paper/docs/api.md`, `host-responsibilities.md`, `integration-guide.md`, `CLAUDE.md`, `demo/` |
