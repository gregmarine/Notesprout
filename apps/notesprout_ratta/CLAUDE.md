# Notesprout SN — Claude Code instructions (apps/notesprout_ratta)

**Branch `ratta` · Package `com.symmetricalpalmtree.notesproutsn` · Label "Notesprout SN"
("Notesprout SN Dev" in debug) · Plan/status: `RATTA_PLAN.md` — read it whole at every
phase start; it holds the working protocol, model recipe, locked decisions, and phase
statuses.**

A from-scratch, **Supernote-only** rebuild of Notesprout (the "ratta paper" experiment).
Paper v0 (`git show 87277da:apps/notesprout_paper/...`) and the original app are reading
references — **no app code is copied from either**. Devices: **Nomad only by default**
(SNN `SN078D10012852`); Manta (SNM `SN100C10023972`) only when the user explicitly asks.
The Manta identifies as a Nomad — target by serial.

**Subsystem docs (`docs/`) — read the matching one before working in that area:**
`docs/library.md` (library screen, naming schemes, **search** — arc 20's fuzzy name search and the
shared `core/FuzzyRank` matcher, grown by arc 21 / W4 into one query over names **and tags** with
tagged pages as their own cards) · `docs/notebook.md` (the notebook screen:
tools, selection, **snap to guides**, headings, Contents, **Recents**, gestures, **the page
template picker — the whole library since arc 13**, arc 21's tag button and the lasso's Tag, undo,
frame-silence ledger) ·
`docs/links.md` (arc 6: link rows/payload, render, picker + create-in-picker, follow + trail) ·
`docs/templates.md` (arc 13: **the paper library** — the two kinds and no third, the sentinels that
are not rows, the reserved **Default** folder, the one browser its three hosts share, SAF import and
export, the Pinned/Recents/Search shelves, the `.soil` **token** and reuse-before-mint, the failure
table, and the abandoned generator idea) ·
`docs/clipboard.md` (arcs 7–8: the clipboard — one index row, one envelope, two kinds; the page
half's long-press sheet and the object half's Copy/Cut, tap-to-place and lasso popup, both
within and **across notebooks**, where a copied link's own-notebook target is re-pointed at the
notebook it came from) ·
`docs/extensions.md` (the **seam**: the six extension points — the recognizer, arc 11's
screen-owning scratch pad, arc 15's generic exporter point, arc 16's generic importer point,
arc 19's screen-owning document editor with its host-callback binder and arc 21's tag manager —
**the extension store, rebuilt on real SQLite tables behind gated SQL in arc 22**, the tier-2
recipe for an extension-owned screen, and **the boundary audit**) ·
`docs/export.md` (arc 15, grown arc 18: notebook export as a feature — the library sheet's Export…
row, the `ExportActivity` screen with its now-real two-exporter chooser, the keying trio and its
host-side transforms, `SoilOpenFiles`, the conditional-deletion rule, **`NSE · PDF Export`** — the
host-renders/extension-assembles source-kind split, its page-template and password-protect
options, the passwordless-PDF silence call — and the failure table for both exporters) ·
`docs/import.md` (arc 16: notebook import as a feature — the library's Import button, the SAF
picker and extension match, the always-re-key-to-global pipeline, the untrusted manifest, the
three questions, the remap, the staged-rename Garden write, the failure table) ·
`docs/backup.md` (arc 17: **compaction + local backup** — the seal-time `.soil` purge and index
purge, sidecar hygiene and the reopen-waits-on-the-claim rule, the Backup screen, the engine's
index-last ordering and stamp map, the WAL-alongside rule for every file kind, the `.part`/`.old`
destination discipline, the exclude toggle, the failure table; grown by **arc 21 / W5** — every
`Garden/<pkg>.db` is in the backup set, and the manual copy-back that stands in for a restore) ·
`docs/document.md` (arc 19, per-device state on rows arc 22 / X4: **Documents** as a feature —
the page is the draft, the document is the result: the data model and flags-as-watermark, the
extension editor and its two-process autosave/teardown table, the `prefs` / `word` / `caret`
tables, seeding and Bring in, the notebook document, text documents, the export half, Proofread,
the failure table) ·
`docs/tags.md` (arc 21, rebuilt on rows arc 22 / X3: **Tags** as a feature — tags on notebooks
and pages, the identity and lifecycle rules, the `tag` / `assignment` tables where every assignment
names its notebook, the two-query search merge, the tag screen's three modes, the four doors
(library sheet, notebook bar, lasso, search), and the failure table) ·
`docs/scratchpad.md` (arc 11, rebuilt on rows arc 22 / X2: the Scratch Pad as a feature — screen,
tools, pages, the `page` / `stroke` / `state` tables and the op-log flush with no page ceiling,
both transfers, failure table) ·
`docs/sn-screen.md` (arc 11 / J1: the shared `:sn-screen` paper-screen library — what may live
there, what may not depend on it, and the `nonTransitiveRClass` flag that holds it together).

## Standing rules

All root `CLAUDE.md` rules apply (Kotlin/17, kotlinx-serialization only, no new Gradle
deps without discussion, no Material Components, no `runBlocking` on main, `Slog.d` not
`Log.d`, e-ink design system, Tabler icons only). Plus, for this app:

- **Ten modules, own Gradle root**: `:app` (the
  host) · `:markdown` (arc 19 / M1 — the shared markdown engine: parser, renderer, formatter,
  reflow, search, draft, paginator; stdlib only, depends on **nothing** in this project and
  nothing beyond the android SDK its spans use — `:app` and `:ext-document` consume it, one
  engine, no drift; `:app`'s arc-3 `core/markdown` twin was repointed and deleted at M8) ·
  `:sn-screen` (the shared paper-screen
  library — depends on g-paper (`api`) + androidx only, **never** on `:app` or `:extension-api`;
  **a fix to shared screen logic goes there, never in a consumer** — breaking that recreates the
  `RattaNotebookView` sibling-copy trap one file at a time) · `:extension-api` (the contract
  library — stdlib only) · `:ext-mlkit` (**NSE · ML Kit**) · `:ext-scratchpad` (**NSE · Scratch
  Pad** — `:extension-api` + `:sn-screen`, never `:app`; no `tools:replace`, no libc++
  `pickFirsts` — Paper's Onyx tax, SN has no Onyx) · `:ext-soil` (**NSE · Soil Export** —
  `:extension-api` only; one package, TWO services: `SoilExporterService` + `SoilImporterService`,
  label unchanged on the user's call) · `:ext-pdf` (**NSE · PDF Export** — `:extension-api` only +
  module-local `com.tom-roush:pdfbox-android:2.0.27.0`, which never leaks into another module) ·
  `:ext-document` (**NSE · Document**, arc 19 / M3, grown M8 — `:extension-api` + `:sn-screen` +
  `:markdown`, never `:app`; one package, TWO services + a screen: `DocumentEditorService` +
  the editor Activity, and `TextImporterService` on the importer point (declares API version 3
  for its `ImporterInfo.resultKind` tail — per-service meta-data; the editor service declares
  **6** since arc 22 / X4, because it takes a store); no
  Application class, no drawing engine; module-local `com.darkrockstudios:symspellkt:3.4.0`
  (arc 19 / M10 — the pdfbox precedent, never leaks into another module) with the bundled
  dictionary asset `assets/proofread/en_82765.dict` — gzip content behind an opaque extension
  on purpose: AAPT gunzips any `.gz` asset and strips the extension) ·
  `:ext-tags` (**NSE · Tags**, arc 21 / W1 — `:extension-api` + `:sn-screen`, never `:app`; one
  service + a screen: `TagManagerService` and `TagsActivity`, API version **6** (W1 declared 4;
  W4's reshaped `TagShowing` moved it to 5; arc 22 / X3 moved it to 6 with the store rewrite). The
  FIRST tier-2 screen carrying **no paper** — no `PaperView`, no g-paper call and therefore **no EPD
  handoff** (M3's measured answer covers it); the tag index is **rows in the host's extension store
  since arc 22 / X3** — `TagSchema.V1` = `tag` / `assignment`, every SQL string in `TagSql`, the
  identity a stored `UNIQUE` column, a notebook tag's `pageId` `''` and never NULL, deleting a tag
  one `DELETE` under the declared `ON DELETE CASCADE`, and **the transaction is the lock** (arc 21's
  process-local `TagWrites` monitor is gone, with `TagCodec` / `CompactId` and the whole one-blob
  layout)).
  `gradle.properties` sets `android.nonTransitiveRClass=false` — undoing it breaks every
  `:sn-screen` resource reference from `:app`.
- **SN has SIX extension points** — each added on its own explicit user decision, and
  **no SEVENTH may be added without another** (arc 21's `ACTION_TAG_MANAGER` was the sixth's,
  granted 2026-08-31). **The SEVENTH, `ACTION_CALENDAR`, was granted 2026-09-01 for arc 23**
  (`RATTA_PLAN.md` § "Phases — Arc 23"; lands at Y1 with the `:ext-ink` + `:ext-calendar` modules) —
  no EIGHTH without another decision. The full seam — contracts, caps, trust, the
  boundary audit — is `docs/extensions.md`; the rules that bind every point:
  - `ACTION_HANDWRITING_RECOGNIZER` (headings + the markdown engine are core, the engine is
    swappable). **Only `prepare()` may start a model download** (host consent dialog first;
    notebook open only warms an already-present model). Recognized text is never logged on
    either side — counts + durations only.
  - `ACTION_SCRATCH_PAD` + `_SCREEN` — the screen-owning (tier-2) point. Its screen refuses any
    caller that is not a `startActivityForResult` from the host (`HostCallerCheck.enforceActivity`),
    so the host **must** launch it with an `ActivityResultLauncher`. `ExtensionBinder.hold` is
    SN's **only** bind held across more than one call (the operation is the showing).
  - `ACTION_NOTEBOOK_EXPORTER` — generic, plural (`exporters()`), declarative descriptors the
    host renders with its own widgets. **The host keys, the extension delivers via two fds**;
    the spec carries no id, no path, no secret; `OPTION_KEYING` (and any passphrase-kind option)
    is host-executed — only the choice id crosses. Served by `:ext-soil` and `:ext-pdf`
    (`sourceKind` tail: `SOURCE_SOIL` absent-means-today / `SOURCE_PAGES` host-rendered page
    bundle; `ExportSpec.exportSecret` is the ONE deliberate secret that crosses any seam —
    user-typed, export-scoped, opens no Notesprout data). Detail: `docs/export.md` +
    `docs/extensions.md` §§ source-kind tail / export secret.
  - `ACTION_NOTEBOOK_IMPORTER` — the exporter's mirror (plural, two fds, bounded spec, no
    secret/id/path). Probe, unlock (`AttemptLimiter` `"IMPORT"`), the unconditional re-key to
    the device global key, `SafeImportId`, placement, remap and both writes are all host-side;
    the extension only streams bytes. `ImporterInfo.resultKind` (arc 19 / M8, compatible tail —
    absent = `RESULT_NOTEBOOK`) says what the delivered bytes ARE: `RESULT_TEXT_DOCUMENT` forks
    the host after delivery into strict-UTF-8 validation + text-document create instead of the
    `.soil` probe. Detail: `docs/import.md`.
  - `ACTION_DOCUMENT_EDITOR` + `_SCREEN` (arc 19 / M3) — the second screen-owning point, served
    by `:ext-document`. **The host owns every `.soil` read and write** (og's invariant, enforced
    by the process boundary): the seam's new piece is `IDocumentHost`, the first **host-side**
    stub on any SN seam — minted per showing, uid-bound and revoked with the unbind (the store
    binder's recipe). Document text is the only user content that crosses, **chunked** by the
    shared `TextChunks` rule under `DocumentContract.MAX_DOCUMENT_CHARS`, and every save names
    its target `pageKey` (the mode-routing guard, structural). Ink never crosses this seam, and
    document text is never logged on either side. Nothing rides the screen's Intent — no extras
    at all. The editor's per-device state is **rows in its extension store since arc 22 / X4**:
    `EditorSchema.V1` = `prefs` (`key`/`value` — the ONE extension table the host reads, its shape
    pinned in `DocumentContract`) / `word` (the word is the primary key) / `caret` (`pageKey`,
    `offset`, `updatedAt`); every SQL string in `EditorSql`, run only by `EditorStore` (schema
    applied on every call — the binder is fetched per call), behind the `EditorPrefs` facade
    where every exception is the default. `rememberCaret` is one two-statement batch (upsert +
    the LRU trim at 100); the dictionary's add/remove are single statements — no
    read-modify-write, no lock.
  - `ACTION_TAG_MANAGER` + `_SCREEN` (arc 21 / W1) — the third screen-owning point, served by
    `:ext-tags`, and the first whose screen carries no paper. **One interface, two call shapes:**
    a showing is a HELD bind (`begin` → `configureShowing` → launch → result → `end`) and the store
    is lent once; `tags` / `assignmentsOf` / `assign` are bind-per-call and the store rides the call.
    Tag text and target labels are the user's own words — they cross on the bind as a `TagShowing`,
    **never** in the screen's Intent, and are never logged on either side. The extension owns the tag
    index (rows in its extension store since arc 22 / X3); the host owns every entry point, the
    recognizer and the search merge. **The search merge is TWO paged queries** (X3, replacing W4's
    whole-index ashmem `snapshot`): `tags(store, offset)` in pages of `TAGS_PAGE` for the host's own
    `FuzzyRank`, then `assignmentsOf(store, matchedIds, offset)` in pages of `ASSIGNMENTS_PAGE` for
    only the rows the ranking needs — a reply is an ordinary parcel, which is why both page.

  All of them get the **extension store** (`IExtensionStore` — per-package,
  encrypted under the global key at `Garden/<pkg>.db`, minted per bind, uid-bound, revoked with
  the unbind, **and copied by every backup run** — arc 21 / W5) because **an extension writes
  nothing to disk itself, ever**. **Since arc 22 / X1 the store is real SQLite tables behind gated
  parameterized SQL, not key/value:** the extension declares versioned DDL once (`StoreSchema` —
  the host applies the missing steps and refuses a downgrade), then sends `SELECT`/`WITH` through
  `query` and `INSERT`/`REPLACE`/`UPDATE`/`DELETE`/`WITH` batches through `exec` (one transaction,
  all-or-nothing, never held open across Binder calls) as `StoreCodec` payloads, and reads
  `StoreCodec` rows back in ≤ 4 MiB chunks (`StoreReads.all` is the loop). The host validates every
  statement (`StoreSql`: one statement, the head keyword decides the kind, a denylist —
  `PRAGMA`/`ATTACH`/DDL/transaction control — anywhere in the token stream, positional binds only,
  and **every identifier in a reserved space `host_*` / `sqlite_*` / `room_*` / `android_*`
  refused**, quoted or not), runs it on the one connection it owns (WAL, `foreign_keys` ON — a
  declared `ON DELETE CASCADE` cascades), and maps every SQLite failure — a constraint violation
  included — to `IllegalStateException`. Room left the store file: it is a `SupportSQLiteOpenHelper`
  over the same `SoilCrypto`/`KeyOpener` factories, its **format** rides `PRAGMA user_version`
  (`StoreFormat`: 2 = tables; 1 or a `kv` table = the arc-11 store, **wiped on open, no migration**
  — the user's call; above 2 = refuse, file left as found). `host_schema` is the host's one table;
  the editor's `prefs` table is the ONE extension table the host reads (`DocumentContract` pins its
  shape; Document-PDF export's text size, only if file and table exist). The debug menu's
  "Extension store self-test" is the only on-device proof — SQLCipher, ashmem and a real Binder
  cannot run on the JVM; the gate runs there over an injected `StoreExecutor`.
  Action strings are
  SN-namespaced so Paper's extensions are never discovered; trust is same-signature both ways
  (discovery + bind-time re-check host-side, `HostCallerCheck` first thing in every stub method);
  `ExtensionContract.API_VERSION` = **6** and the host accepts `minApiVersion(action)..6` (the
  declared number is what the extension *requires* of the host). The ledger: 2 = arc 18's
  `sourceKind` tail · 3 = arc 19 / M8's `resultKind` tail · 4 = arc 21 / W1, the tag point itself ·
  **5 = arc 21 / W4, the first bump that is NOT a compatible tail** — `TagShowing`'s wire form
  changed, so a W1-shaped tag extension against a W4 host unmarshals wrongly; it fails loudly (the
  constructor `require`s reject it as an `IllegalArgumentException`) and the declaration keeps it
  from being reached · **6 = arc 22 / X1, the second break and the first with a FLOOR** —
  `IExtensionStore` was *replaced*, which also breaks the old-extension/new-host direction (a v5
  pad calling transaction code 1 lands on a different method), so the three **store-taking** points
  (scratch pad, document editor, tag manager) are accepted only at
  `MIN_API_VERSION_FOR_STORE` 6 and above; the stateless points keep floor 1. **Consequence, live
  on the Nomad between X1 and each extension's phase: that extension's doors were GONE** —
  deliberate, and the X1 walk verified it (the pad redeclared 6 in X2 and its button came back; the
  tag manager in X3, so every tag door and the search merge came back; the editor service in X4, so
  the Document button came back — its text importer and document exporter services stay at 3,
  since neither takes a store).
  Meta-data is **per service**.
- **The Scratch Pad is not ours to change from here** (arc 11, `docs/scratchpad.md`). It is the
  `:ext-scratchpad` APK: its own process, its own g-paper surface, its own undo stack, and it
  **writes nothing to disk itself** — its pages live in the host store, lent for the showing and
  revoked with the unbind (**rows since arc 22 / X2**: `ScratchSchema.V1` = `page` / `stroke` /
  `state`, every SQL string in `ScratchSql`, writes as an idempotent op log split into ≤ 4 MiB
  `exec` batches, reads planned by `LENGTH(blob)` into `BETWEEN` ranges — **no page ceiling**, and a
  page row is never `INSERT OR REPLACE`d because REPLACE's delete cascades its strokes). It opens
  **no `.soil`**, and the notebook behind it is **not sealed** —
  what the notebook gives up is the EPD pipeline, not its data. Both transfers are **copies** that
  cross only through the held service (never the Intent, never a file), carry **no ids**, and keep
  coordinates 1:1. The pad's tools are the notebook's, fixed: a pad that lassoed differently one tap
  from the notebook would read as a bug, so a change to the notebook's ink feel is a change to both.
  Touching either paper surface's handoff means re-reading the ordering rule in
  `docs/extensions.md` § the tier-2 recipe first; a failure there is fixed in **g-paper**.

- **Paper is identified by a TOKEN, not a kind** (arc 13, `docs/templates.md`). A `.soil` `template`
  row's `text` is `""` (blank — no row at all), `LINED`/`DOTTED`/`GRID` byte-for-byte as every build
  in this family has written them, or `IMG#<8 hex>` for an imported picture — whose digest covers the
  **fit mode** as well as the bytes. Reuse is `token + page size`, **reuse before mint**, and nothing
  ever soft-deletes a template row. The library's **sentinels are not rows** (Blank, the reserved
  **Default** folder, the three built-in papers): hardcoded ids, nothing seeded, nothing repairable
  — so any prune against "alive rows" must exempt them by name. The browser **never opens a `.soil`**
  and never returns pixels; it returns a `TemplatePick` and the caller does the read and the write.
  Paper that will not **draw** is not paper that is **absent** and neither is blank: a failed render
  leaves the page exactly as it was. **No adjustable generators** — built, shown, abandoned
  (arc 13 / G2); import is how a user gets different paper, and re-raising it needs a fresh user
  decision.
- **Data model is Paper's, byte-for-byte format-compatible** — `notesprout.db` `objects`
  table (user_version 1) + `Garden/<uuid>.soil` universal `notebook` table v1 +
  `notebook_meta`, StrokeCodec format B, encrypt-by-default global key, SQLCipher stock
  defaults. Any schema/codec/crypto change must keep a Paper-created file openable and
  vice versa. References: `apps/notesprout_paper/docs/data.md` + `docs/crypto.md`.
- **`data/SoilFile.kt` is the only path constructor** — `extensionStoreFile` included
  (arc-11 / J2 amendment: the function exists now, and it is the only way to derive an
  extension store's `Garden/<pkg>.db`) **and `extensionStoreFiles` too** (arc 21 / W5: the one
  path authority also owns the one listing of that directory — the library's structure is still
  index-only, but a store has no index row to be listed from, so the backup run reads the
  file system, and only there).
- **Every SQLCipher open routes through `crypto/SoilCrypto`.** Passphrases never logged,
  never in Intent extras, never in the index. Never delete a DB on corruption.
- **`IndexGuard.ready(this)` first thing in every index-touching `onCreate`**;
  `BootstrapActivity` is the only index opener and is `noHistory`.
- **g-paper 0.1.23, `gpaper-core` + `gpaper-ratta` only** (mavenLocal). No `gpaper-onyx`,
  no BOOX repo, no jetifier, no jniLibs pickFirsts, no `tools:replace` label. Engine gaps
  are fixed in `~/git/g-paper` (bump version, `publishToMavenLocal`, re-pin) — never
  worked around in the host.
- **Host does only the documented host responsibilities**
  (`~/git/g-paper/docs/host-responsibilities.md`): page swap = `clearForContentSwap` →
  `setPageSize`/`setTemplate` → `loadStrokes`; undo/redo via `addStrokes`/`removeStrokes`;
  chrome via `setExclusionRects`; lifecycle `resumeDrawing`/`releaseForHandoff`/`release`.
- **Frame-silence rule:** never present an app frame while `paper.isPenActive` — route chrome
  text/updates through a pen-idle gate. The recorded exceptions (each one chrome frame at a
  deliberate act or a boundary, never under live ink) are **ledgered with their justifications
  in `docs/notebook.md` § frame-silence** — any new exception needs the same written
  justification there. Remember `isPenActive` counts **hover** — never idle-gate a hide/show
  that must answer a deliberate act.
- **Toast vs. dialog:** a toast only confirms something that already happened; anything
  explaining why a tap *didn't* work is a problem dialog. On e-ink a missed toast reads as
  "broken". **Three recorded exceptions** use `Dialogs.confirm` (post-arc-17 toast review,
  2026-08-30 — a successful result that still shouldn't ride a toast): export-done (the screen
  finishes under the toast — `finish()` runs on the dialog's dismiss), backup-done (the counts
  are the screen's whole point), import-done (names a non-current destination folder). The
  frequent/reversible/in-context toasts (copy/cut/paste, clipboard clear, template
  import/export, backup-exclude) stayed toasts on purpose — do not promote them.
- Portrait-locked everywhere · one layout per screen · no colour in chrome (ink is fixed
  black — P1 removed the tool panels) · TopGuard is 0 on Ratta — chrome sits flush at the top
  edge · notebook writes go through the session's single serial `SoilWriter` · undo/redo
  replays through the store then reloads the page (DB is the source of truth) · no file
  over ~800 lines without a written reason.
- **Supernote swallows `adb shell input text`** — scripted device tests tap the on-screen
  keyboard or avoid text entry. EPD live ink is invisible to screencap; only committed
  strokes screenshot-verify.

## Build & install

See `RATTA_PLAN.md` appendix. Debug: `./gradlew assembleDebug` → `adb -s SN078D10012852
install -r`. Release is unsigned + hand-signed with the debug keystore. JVM tests:
`./gradlew test`. Java 17 comes from `org.gradle.java.home` (Temurin-17).
