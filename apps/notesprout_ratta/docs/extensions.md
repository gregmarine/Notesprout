# Extensions — Notesprout SN subsystem doc

Arc 3 (N0–N2), landed as arc 1's one deliberate amendment: SN ships with **no extension system**
through R0–R6, and picks up exactly **one** extension point in arc 3 — handwriting recognition —
so a different HWR engine can slot in later without touching the host. Headings and the markdown
engine that consume it are core, not extension surface.

**Arc 11 is the fresh user decision that rule required.** The user asked for the scratch pad as an
extension, so SN gains a second capability point — `SCRATCH_PAD`, and the first **screen-owning**
one — and, with it, the **extension store** documented below. J2 landed the store and the contract
half; J3 the point itself — the AIDL, the wire parcelables, the held bind, the host's client and
the `:ext-scratchpad` APK; J4 the real screen, both entry buttons and **the EPD handoff between two
paper surfaces in two processes**; J5 the two ink transfers. The arc is complete and frozen.

**Arc 15 is the second fresh user decision, on 2026-08-27.** The user asked for notebook export,
with the export *implementations* as extensions, so SN gains a **third** capability point —
`ACTION_NOTEBOOK_EXPORTER`, generic and plural (any number of trusted exporters may register) — and
its first shipped exporter, **`NSE · Soil Export`** (`:ext-soil`). E1 landed the point itself, the
extension, and the host's `ExportActivity` on the Keep path; E2 landed the two keying transforms.
The arc is complete and frozen.

**Arc 16 is the third fresh user decision, on 2026-08-28.** The user asked for notebook import
riding the same seam, so SN gains a **fourth** capability point — `ACTION_NOTEBOOK_IMPORTER`, the
exporter's mirror: generic, plural, and served by the **same** `:ext-soil` APK under the **same**
label (`NSE · Soil Export` — the user declined a rename; one package, two directions of one
format). I1 landed the point, the second service and the host's whole pipeline; I2 the review
fixes, this doc's rows 9–11 and the freeze. The arc is complete and frozen.

**Arc 19 is the fourth fresh user decision, on 2026-08-30.** The user asked for og's Documents
feature with the editor as an extension, so SN gains a **fifth** capability point —
`ACTION_DOCUMENT_EDITOR`, the second screen-owning one — served by **`NSE · Document`**
(`:ext-document`), one APK carrying **three registrations**: the editor point plus a document
exporter on arc 15's exporter point and a text importer on arc 16's importer point (the
`:ext-soil` one-APK-many-services precedent). The seam's new piece is `IDocumentHost` — the
first **host-side** stub on any SN extension seam, minted per showing so the editor's autosave
pushes text back to the host live. M3 landed the point; M9 the `SOURCE_DOCUMENT` source kind;
M8 the `resultKind` descriptor tail. The rule survived one word wider through arc 19 — **no
*sixth* capability point without another user decision** — and arc 21 is that decision.

**Arc 21 is the fifth fresh user decision, on 2026-08-31.** The user asked for tags on notebooks
and pages, so SN gains a **sixth** capability point — `ACTION_TAG_MANAGER`, the third
screen-owning one, served by **`NSE · Tags`** (`:ext-tags`), the **tenth** module. The shape is
the scratch pad's: the extension owns the tag screen and the tag index, in its own extension
store; the host owns every entry point (the library's long-press row, the notebook's three tag
doors, the lasso's silent and recognized flows), the recognizer call, and the library's search
merge. It is the first screen-owning point whose screen carries **no paper at all** — no
`PaperView`, no g-paper, and therefore no EPD handoff — and the first interface to serve two call
shapes at once, a held bind for the showing and bind-per-call for `tags`/`assignmentsOf`/`assign`
(`snapshot`, W1's original bind-per-call read, was replaced at arc 22 / X3 — see below). The rule
survives once more, another word wider: **no *seventh* capability point without another user
decision** (`apps/notesprout_ratta/CLAUDE.md`).

**Arc 22 "Tables" is not a seventh decision — the rule above still stands — but it is a fresh
decision all the same, self-granted 2026-09-01: a rebuild of the *service* every store-taking
point already receives, not a new point.** `IExtensionStore` stopped being a per-package
key/value blob store and became real SQLite tables behind gated parameterized SQL: an extension
declares its schema once (`StoreSchema`), then sends `SELECT`/`INSERT`/`UPDATE`/`DELETE` through a
validator (`StoreSql`) and reads rows back (`StoreCodec`) — full SQLite expressiveness (joins,
aggregates, indexes) where arc 11 offered six methods and a byte array. X1 rebuilt the seam and
the host's store, with every store-taking extension GONE from the host until it redeclared the new
floor; X2 moved the scratch pad onto rows (the 4 MiB page ceiling is gone with the blob it
bounded); X3 moved the tag manager onto rows (`ITagManager`'s `snapshot` replaced by two paged
reads, `tags`/`assignmentsOf`, and the caps' size arithmetic deleted with the one-value layout it
protected); X4 moved the document editor onto rows (`prefs`/`word`/`caret`, no more
read-modify-write). `API_VERSION` 6 is the ledger's second break that is not a compatible tail, and
the first that carries a floor — see the ledger below.

**Arc 23 "Calendar" is the sixth fresh user decision, on 2026-09-01.** The user asked for a basic
writable calendar — Month, Week and Day pages, the way a physical organizer is one — so SN gains a
**seventh** capability point, `ACTION_CALENDAR` + `ACTION_CALENDAR_SCREEN`: the fourth
screen-owning point and the second with paper (after the pad), served by **`NSE · Calendar`**
(`:ext-calendar`), the **twelfth** module, alongside a new library module, **`:ext-ink`**, that
carries the pad's ink-on-rows helpers out from under `:ext-scratchpad` so the calendar is not a
sibling copy of it. Y1 landed the seam, both modules and the Month page; Y2 grew Week and Day and
the navigation; Y3 landed the notebook door and both transfers; Y4 is this doc. **Arc 24
"Events" (2026-09-02) is not a fresh decision on a point** — it grew the calendar point in place
(two more in-process screens, five more store tables, the point still declaring 7) rather than
opening one, so it needed none. `API_VERSION` 7 is
the ledger's third bump — a compatible *addition*, not a break, since no existing interface
changed shape — and the first with a **per-action** floor, replacing the single
`MIN_API_VERSION_FOR_STORE` set with a map. The rule survives once more, another word wider: **no
*eighth* capability point without another user decision** (`apps/notesprout_ratta/CLAUDE.md`).

The pad as a **feature** has its own reference — [`docs/scratchpad.md`](scratchpad.md); export has
its own — [`docs/export.md`](export.md); import has its own too — [`docs/import.md`](import.md);
documents likewise — [`docs/document.md`](document.md); tags likewise —
[`docs/tags.md`](tags.md); the calendar likewise — [`docs/calendar.md`](calendar.md). This doc is
the seam for all seven points, and the store rebuild that underlies three of them.

Fresh code. Paper's own extension arcs (`PAPER_EXTENSIONS_PLAN.md`, `PAPER_RECOGNITION_PLAN.md`,
`PAPER_SCRATCHPAD_PLAN.md`, its `:extension-api` / `:ext-mlkit` / `:ext-scratchpad`) are the shape
reference — nothing is copied, and SN's AIDL is scoped to its **seven** points rather than Paper's
broader capability set. Paper never built export, import, documents, tags or a calendar, so it has
nothing to say about the third through seventh; og's `docs/full-notebook-export.md` § Import was
the fourth's reading reference, og's `docs/documents.md` the fifth's, and og's own
`docs/calendar.md` (`CalendarActivity` / `CalendarTemplateRenderer`, at the monorepo root) the
seventh's — the three layouts and the navigation, not the seam; nothing is copied. Tags have no og
reading reference — the feature is new to this family.

---

## Module layout

Twelve modules, SN's own Gradle root:

| Module | Type | Depends on | Holds |
|---|---|---|---|
| `:sn-screen` | Android library | g-paper (`api`) + androidx; **never** `:app`, **never** `:extension-api` | the design resources and the screen helpers every paper surface needs — including `FloatingSelectionBar` (arc 23 / Y1, moved here from the pad so the calendar's floating bar is not a sibling copy), `ic_calendar`, and (arc 23 / Y4) `PenIdle` (the shared frame-silence gate) and `InkSelectionBar` (the ONE Send-then-Delete floating bar over `FloatingSelectionBar`, replacing `ScratchSelectionToolbar` and `CalendarSelectionToolbar`) — see [`sn-screen.md`](sn-screen.md) |
| `:markdown` | Android library | nothing in this project — stdlib + the android SDK its spans use (arc 19 / M1) | the shared pure markdown engine `:app` and `:ext-document` both consume — parser, renderer, `HeadingTypography`, `MarkdownDraw`, `MarkdownFormatter`, `TextBuffer`, `MarkdownReflow`, `TextSearch`, `DocumentDraft`, `MarkdownText`, `MarkdownPaginator`. One engine, no drift: the host renders text-document covers and the PDF preview, the extension renders the editor's Preview |
| `:extension-api` | Android library | nothing in `:app`, no library beyond the Kotlin stdlib (`build.gradle.kts` says so explicitly) | the AIDL (`IHandwritingRecognizer`, `InkStroke.aidl`; `IExtensionStore`, `StorePayload.aidl`, `StoreResult.aidl`, `StoreSchema.aidl`; `IScratchPad`, `WireStroke.aidl`, `InkBundle.aidl`; `INotebookExporter`, `ExporterInfo.aidl`, `ExportSpec.aidl`, `ExportResult.aidl`; `INotebookImporter`, `ImporterInfo.aidl`, `ImportSpec.aidl`, `ImportResult.aidl`; `IDocumentEditor`, `IDocumentHost`, `DocumentPageState.aidl`; `ITagManager`, `TagShowing.aidl`, `TagRecord.aidl`, `AssignmentRecord.aidl`; `ICalendar`, `CalendarTarget.aidl`), the hand-written `InkStroke` / `StorePayload` / `StoreResult` / `StoreSchema` / `WireStroke` / `InkBundle` / `ExporterInfo` / `OptionDescriptor` / `ExportSpec` / `ExportResult` / `ImporterInfo` / `ImportSpec` / `ImportResult` / `DocumentPageState` / `TagShowing` / `TagRecord` / `AssignmentRecord` / `CalendarTarget` parcelables, `PageBundle` (the arc-18 page-bundle container — pure `java.io`, no Android types), `SharedBytes`, `InkChunks`, `TextChunks`, `RecognizerStatus`, `ExtensionContract`, `ExporterContract`, `ImporterContract`, `DocumentContract`, `HostCallerCheck`, `CalendarDates` (arc 23 / Y1, pure `java.time` date arithmetic — Sunday weeks, hand-list titles, `step`/`periodDate`/`isNormalized`), and — the store seam rebuilt at arc 22 / X1 — `StoreCodec` (`Cell`, `Statement`, `Row`, `StoreRows`, `StoreChunker`), `StoreSql` / `StoreNames` (the validator), `StoreReads` (the extension-side `query`/`exec` loop), `TagRules` (arc 21, `isId` case-insensitive since X3) and `TagPages` (arc 22 / X3, the one paging loop both sides run). Arc 21's `TagIndex` / `TagCodec` / `CompactId` are **deleted** with the one-blob layout they served; `TagIndex` survives as `:ext-tags`' own in-memory query model, not a file shared by both sides |
| `:ext-mlkit` | Android application (its own installable APK) | `:extension-api` + `com.google.mlkit:digital-ink-recognition:19.0.0` | `HandwritingRecognizerService`, `ModelManager`, `MlKitEngine`, `PageText`, `StrokeSegmenter`, `Dots`, `Box` |
| `:ext-ink` | Android library | `:extension-api` (`api`) + `:sn-screen` (`api` — g-paper's `Stroke` reaches it that way, plus `StrokeCodec` and `Slog`) + coroutines + (since Y4) `api(appcompat)` — `InkScreenActivity` is an `AppCompatActivity` a consumer extends, the same version both consumers already declared, no new library on the graph — + `implementation(lifecycle-runtime-ktx)`; **never** `:app`, no manifest components, no resources | the ink-on-rows library the scratch pad and the calendar share since arc 23 / Y1, so neither is a sibling copy of the other: `InkWire` (wire ⇄ paper, the extension-side twin of the host's `TransferCaps` — the twin stays deliberate), `StrokeRows` + `StrokeBlob` (row ⇄ stroke, `StrokeCodec` format B, a bad row a dropped stroke and never a lost page), `StoreBatches` (splitting a write into `exec` batches at the store's byte/statement caps), `StrokeReadPlan` (planning a page's stroke read into `BETWEEN` ranges so a page of any size comes back without meeting `STORE_RESULT_LARGE`), `InkDocument` (the `TreeMap<order, Stroke>` + op log + `flushUntilClean`, taking its two stroke statements through a small `StrokeSql` interface so each consumer's SQL stays its own), `InkAction` (`Drew`/`Erased`/`Moved`/`Pasted`, the stroke-level replay), and the abstract `InkStore` base (`StoreUnavailable`, `PageInk`, `execAll`/`run`/`compensated`/`guard`/`readStrokes`) the pad's and the calendar's own stores extend. Moved out of `:ext-scratchpad` at Y1 under neutral names, tests included. **Since arc 23 / Y4** (the code-review fix that closed a second sibling copy the Y1 move left below the store line): `InkSql` (the shared `stroke` DDL and its six statements, byte-identical to what each consumer used to spell out), `InkPage` (the ink half of a consumer's document as a contract — `pageId`/`strokes`/`pageWidth`/`pageHeight`/`erase`/`move`/`flushUntilClean`, `ScratchDocument` and `CalendarDocument` implement it), `InkTransferSession<P, R>` (the process-wide showing state and the two transfer stubs' bodies, `receiveChunk`/`outgoing`, under one monitor, the placement bound by the first chunk), and `InkScreenActivity<A>` (the abstract tier-2 screen skeleton: the page-op lock, undo/redo replay with the `followReplay` hook, the bounded-debounce-vs-unbounded-leave flush, the EPD handoff order) |
| `:ext-scratchpad` | Android application (its own installable APK) | `:extension-api` + `:sn-screen` (g-paper arrives through its `api`) + `:ext-ink` + androidx; **never** `:app`, no Room / SQLCipher / serialization | `ScratchPadApplication`, `ScratchPadService` (thin on `:ext-ink`'s `InkTransferSession` since Y4 — supplies only the placement int's own check, the page-list read at `begin`, and its own log wording), `ScratchPadActivity` (thin on `:ext-ink`'s `InkScreenActivity` since Y4 — keeps the page list, the pager, inserts, delete confirm, its own `consumeReceived` head), `ScratchSession` (an `InkTransferSession<Int, ScratchStore.Received>(recordInboundPageSize = true)`), `ScratchSchema` (arc 22 / X2, schema v1: `page`/`stroke`/`state` — the `stroke` half is `:ext-ink`'s `InkSql` since Y4), `ScratchSql` (`: InkDocument.StrokeSql by InkSql` since Y4), `ScratchStore` (extends `:ext-ink`'s `InkStore`), `ScratchDocument` (thin over `:ext-ink`'s `InkDocument` — the pad's own layer keeps only the page list and its structural edits — and implements `:ext-ink`'s `InkPage` since Y4), `ScratchUndo` (`ScratchAction` sealed: `Ink(InkAction)` · `Page`, the pad's own page-level action), `ScratchPages`, `ScratchToolbar` — `ScratchInk`, `ScratchBatches`, `ScratchReadPlan` and `StrokeRows` **moved to `:ext-ink`** (arc 23 / Y1) under neutral names, and `ScratchSelectionToolbar` **deleted** (arc 23 / Y4) in favour of `:sn-screen`'s `InkSelectionBar` |
| `:ext-soil` | Android application (its own installable APK) | `:extension-api` only | `SoilExporterService`, `SoilExportSpec` — see [`export.md`](export.md); and, arc 16, `SoilImporterService` — see [`import.md`](import.md). One package, two services, one label |
| `:ext-pdf` | Android application (its own installable APK) | `:extension-api` + `com.tom-roush:pdfbox-android:2.0.27.0` (module-local — approved 2026-08-30, used only on the protect path) | `PdfExporterService`, `PdfDescriptor`, `PdfExportSpec`, `PdfAssembly`, `CountingOutputStream` — arc 18's second exporter on the same point; see [`export.md`](export.md) |
| `:ext-document` | Android application (its own installable APK) | `:extension-api` + `:sn-screen` + `:markdown` + `com.darkrockstudios:symspellkt:3.4.0` (module-local — approved 2026-08-30, the pdfbox precedent); **never** `:app`, no Application class, no drawing engine | one package, TWO services + a screen: `DocumentEditorService` + `DocumentEditorActivity` (the editor — arc 19 / M3–M7, with `EditorSession`, `DocumentSaver`, `AutosaveGovernor`, `ChunkPush`, `PendingPark`, `EditorSchema` (arc 22 / X4, schema v1: `prefs`/`word`/`caret`), `EditorSql`, `EditorStore`, `EditorPrefs` (the thin facade callers keep using), the format bar, find & replace, and the `proofread/` engine over the bundled `assets/proofread/en_82765.dict`), `TextImporterService` (M8, on the importer point) and `DocumentExporterService` (M9, on the exporter point) — see [`document.md`](document.md) |
| `:ext-tags` | Android application (its own installable APK) | `:extension-api` + `:sn-screen` (g-paper arrives through its `api` and is deliberately never touched); **never** `:app`, no Application class, no drawing engine | the TENTH module (arc 21 / W1–W4, grown onto rows at arc 22 / X3, **NSE · Tags**, Tabler `tag` icon): one service + a screen, `TagManagerService` + `TagsActivity`, over `TagSession` (the `ScratchSession` shape — the two share a process), `TagSchema` (schema v1: `tag`/`assignment`), `TagSql`, `TagStore`, `TagIndex` (moved here from `:extension-api` at X3 — the screen's query-only in-memory model, built from two reads), `TagManage`, `TagPaging`, `TagRowView` — see [`tags.md`](tags.md) |
| `:ext-calendar` | Android application (its own installable APK) | `:extension-api` + `:sn-screen` (g-paper arrives through its `api`) + `:ext-ink` + androidx; **never** `:app`, no Room / SQLCipher / serialization | the TWELFTH module (arc 23 / Y1–Y3, **NSE · Calendar**, Tabler `calendar` icon): `CalendarApplication` (registers `RattaEngine` — its own process), `CalendarService` (the `ICalendar` stub, thin on `:ext-ink`'s `InkTransferSession` since Y4 — supplies only the target's own null check and the log wording), `CalendarSession` (an `InkTransferSession<CalendarTarget, CalendarStore.Received>(recordInboundPageSize = false)`), `CalendarSchema` (schema v1: `period`/`page`/`stroke`/`state` — the `stroke` half is `:ext-ink`'s `InkSql` since Y4), `CalendarSql` (`: InkDocument.StrokeSql by InkSql` since Y4), `CalendarStore` (on `:ext-ink`'s `InkStore`), `CalendarDocument` (thin over `:ext-ink`'s `InkDocument` — the calendar's own layer keeps which period/page is showing, whether its rows exist yet, and its size — and implements `:ext-ink`'s `InkPage` since Y4), `CalendarGeometry`, `CalendarTemplate`, `CalendarNavigation`, `DayPickerModel`, `DayPickerDialog`, `CalendarToolbar`, `CalendarActivity` (thin on `:ext-ink`'s `InkScreenActivity` since Y4 — keeps navigation, template bake, the picker, double-tap, `followReplay()`) — `CalendarSelectionToolbar` **deleted** (arc 23 / Y4) in favour of `:sn-screen`'s `InkSelectionBar`; **grown in place by arc 24 "Events" (Z1–Z5, not a point, no API bump — still declares 7)**: two more in-process screens, `EventsActivity` (the day's list) and `EventEditorActivity` (one event), both `exported="false"` and launched only in-process with an `ActivityResultLauncher` (the list by `CalendarActivity`, the editor by the list) — same process, so neither needs a `HostCallerCheck`; `CalendarSchema.V2` (V1's step untouched + one events step: `event` / `event_weekday` / `event_exception` / `event_reminder` / `note_stroke`); `EventStore : InkStore, MarkSource`, `EventSql`, `NoteSql : InkDocument.StrokeSql`, `Recurrence`, `EventRules`, `EventWrites`, `EventRows`, `Upcoming`, `EventWording`, `EventDraft`, `GridMarks`, `DayRows`; and `NoteSurface`, a second g-paper surface in the same process (the calendar hands nothing over before the list; the editor's surface releases before every `finish()`) — see [`docs/calendar.md`](calendar.md) |
| `:app` (`extension/` package) | part of the host APK | `:extension-api` | `ExtensionRegistry`, `ExtensionBinder`, `ExtensionCallException`, `InkCaps`, `RecognizerClient`, `RecognizerReadiness`, `HeldInkClient` (arc 23 / Y4 — the pad's and the calendar's held-bind lifecycle written once: `HeldInkPoint` is the per-point names/budgets interface, `DrainedInk` the one drained-result class), `ExtensionScreenEntry` (Y4 — the pad's and the calendar's entry-button door written once: `InkSend` the one outbound-ink class, `EntryWording` the four strings), `TransferSelection` (Y4 — the pure ink-only/writing-order rule both lasso sends obey), `ScratchPadClient` / `CalendarClient` / `ScratchPadEntry` / `CalendarEntry` (since Y4, thin points on the two classes above — a point's companion is a `HeldInkPoint`, its constructor a set of `ExtensionScreenEntry` wiring), `TransferCaps`, `ExporterClient`, `ImporterClient`, `DocumentEditorClient`, `DocumentEditorEntry`, `DocumentHostBinder`, `DocumentHostSession`, `TagClient`, `TagManagerEntry`; and in `data/extstore/`, the extension store — rebuilt on `SupportSQLiteOpenHelper` at arc 22 / X1, Room's `KvEntity`/`KvDao` deleted with it (`ExtensionStores`, `ExtensionStoreDatabase`, `StoreFormat`, `StoreExecutor` / `SupportStoreExecutor`, `ExtensionStoreGate`, `ExtensionStoreBinder`) — plus, in `export/` and `crypto/`, export's own host-side half (`ExportActivity`, `ExportPanel`, `ExportOptions`, `ExportArtifact`, `ExportNaming`, `ExportKeying`, `SoilOpenFiles`, and arc 19's `ExportText`, `ExportDocumentRules`, `DocumentPdfRender`, `DocumentPdfMetrics`), in `importing/` and `crypto/`, import's (`ImportFlow`, `NotebookImport`, `ImporterMatch`, `ImportNames`, `AncestryPlan`, `SafeImportId`, `ImportDialogs`, `ImportOverlay`, `ImportKeying`, `NotebookRemap` in `data/soil/`, and arc 19's `TextImport`), in `notebook/`, tags' own host-side half (`TagsPopup`, `TagTargets`, `TagSelection`), and in `notebook/`, the calendar's own host-side half (`CalendarTargets`, arc 23 / Y3 — the four Send-to-Calendar choices, every one through `CalendarTarget.of`) |

`:sn-screen` is deliberately **not** in that dependency chain: it never sees `:extension-api`, so a
shared screen helper can never quietly become part of the wire contract. **`:ext-ink` is the one
module that depends on both, separately** (arc 23 / Y1, `api` on each) — and that seam is exactly
why the host's `TransferCaps` and `:ext-ink`'s `InkWire` are deliberate **twins** of the same wire
⇄ paper mapping rather than one shared class. `:ext-scratchpad` and `:ext-calendar` each still
declare all three (`:extension-api`, `:sn-screen`, `:ext-ink`) directly in their own
`build.gradle.kts` — `:ext-ink`'s `api` dependencies would reach them transitively, but each names
its own AIDL dependency explicitly rather than relying on that.

`:ext-scratchpad` needs **no** `tools:replace` and **no** libc++ `pickFirsts`: both exist in Paper
only because the Onyx SDK arrives through its shared screen module. SN has no Onyx, and the release
APK is **6.7 MB** against Paper's ≈ 25 MB.

`:extension-api` is the contract both sides compile against — a host that never binds an
extension still needs its constants and parcelable, and an extension that never sees `:app` still
needs the same AIDL. The ML Kit dependency lives in `:ext-mlkit` **only**: it is the one new
Gradle dependency the arc-3 wizard approved, and it must never leak into `:app` or the contract
library. `:extension-api`'s `build.gradle.kts` turns on `aidl = true`; nothing else in the build
graph touches AIDL.

The host's `AndroidManifest.xml` declares package-visibility for the point (API 30+ filtering):

```xml
<queries>
    <intent>
        <action android:name="…extension.HANDWRITING_RECOGNIZER" />
    </intent>
    <intent>
        <action android:name="…extension.SCRATCH_PAD" />
    </intent>
    <intent>
        <action android:name="…extension.SCRATCH_PAD_SCREEN" />
    </intent>
    <intent>
        <action android:name="…extension.NOTEBOOK_EXPORTER" />
    </intent>
    <intent>
        <action android:name="…extension.NOTEBOOK_IMPORTER" />
    </intent>
    <intent>
        <action android:name="…extension.DOCUMENT_EDITOR" />
    </intent>
    <intent>
        <action android:name="…extension.DOCUMENT_EDITOR_SCREEN" />
    </intent>
    <intent>
        <action android:name="…extension.TAG_MANAGER" />
    </intent>
    <intent>
        <action android:name="…extension.TAG_MANAGER_SCREEN" />
    </intent>
    <intent>
        <action android:name="…extension.CALENDAR" />
    </intent>
    <intent>
        <action android:name="…extension.CALENDAR_SCREEN" />
    </intent>
</queries>
```

The four screen-owning points (scratch pad, document editor, tag manager, calendar) need **both**
of their actions listed: one to discover and bind the service, one to resolve and launch the
screen. The exporter and importer points need only one each — `describe()` and the delivery call
both ride the same bind-per-call service. Plus `ACCESS_NETWORK_STATE`, for the readiness flow's
offline pre-check (below).

**Missing either of a screen-owning point's two actions from this block is a silent-zero trap, not
a mismatch.** Arc 21 / W1 cost an hour to it: with the service's own action present but the
screen's absent (or the reverse), `queryIntentServices` answers `0 provider(s) of 0 candidate(s)`
for a service that is installed, exported, correctly signed and at the right API version — which
reads exactly like a signature or version skew and is neither. Both actions went in together, the
scratch-pad / document-editor precedent.

---

## The AIDL contract

`IHandwritingRecognizer` (`extension-api/src/main/aidl/…/IHandwritingRecognizer.aidl`) is
engine-neutral, stateless, and three calls wide:

```
int    status()
void   prepare()
String recognizeInk(in List<InkStroke> strokes, float areaWidth, float areaHeight, String preContext)
String recognizePage(in List<InkStroke> strokes, float pageWidth, float pageHeight)
```

`status()` never blocks on the engine — it is the fast, always-answerable read the host polls.
`prepare()` is the **only** call that may start acquiring what the engine needs (a model
download) and returns immediately; the recognize calls wait for an acquisition already in flight
within the caller's timeout, but never start one themselves. `recognizeInk` is one writing area
with no layout analysis — the caller has already decided what the ink is (a heading's lassoed
selection); `recognizePage` hands the extension a whole page and lets it segment lines and
paragraphs itself, chaining each line's recognized text as the next line's `preContext`.

**`InkStroke`** (`extension-api/…/InkStroke.kt`, plain hand-written `Parcelable` — no AIDL
`parcelable` codegen beyond the one-line `InkStroke.aidl` declaring it exists) carries bare
geometry and nothing else: parallel `x`/`y` `FloatArray`s in the caller's px space. No id, time,
pressure, colour or width crosses the boundary. The wire form is `int n · float[] x · float[] y`
— documented as a compatible tail (e.g. a future time channel could be appended after `y`; a
reader of this version simply stops reading after `y`). `MAX_INK_POINTS` is capped at 60,000,
sized in the constant's own comment as "≈480 KB of floats" — comfortably under the point past
which a single Binder transaction risks the ~1 MB transaction-buffer ceiling, which is also why
ink crosses as one bounded list rather than, say, a stream.

**`RecognizerStatus`** is four plain `Int` constants (no enum, no parcelable — AIDL carries `int`
natively): `READY` (0), `NEEDS_DOWNLOAD` (1), `DOWNLOADING` (2, covers checking/downloading/
loading as one busy state), `UNAVAILABLE` (3). The host treats anything outside `0..3` as
`UNAVAILABLE` (`InkCaps.status`).

**`ExtensionContract`** is the constants object both sides compile against:

| Constant | Value | Purpose |
|---|---|---|
| `API_VERSION` | 7 (arc 23 / Y1) | the host accepts a service whose `<meta-data>` is in `minApiVersion(action)..API_VERSION` (`ExtensionContract.accepts`) — the declared number is what the extension *requires* of the host. `minApiVersion` answers **`MIN_API_VERSION_FOR_STORE`** (6) for the three arc-22 **store-taking** points (scratch pad, document editor, tag manager), **`MIN_API_VERSION_FOR_CALENDAR`** (7) for the calendar, and 1 for every stateless point — a service below its floor is skipped even though the ceiling alone would admit it. Meta-data is **per service**: the PDF exporter declares 2 (the `sourceKind` tail), `:ext-document`'s text importer and document exporter declare 3 (the `resultKind` tail / `SOURCE_DOCUMENT`), its editor service declares **6** (arc 22 / X4, since it takes a store), `:ext-tags`' one service declares **6** (arc 22 / X3), `:ext-scratchpad`'s one service declares **6** (arc 22 / X2), `:ext-calendar`'s one service declares **7** (arc 23 / Y1 — the point was born at 7, so there is no lower number to consider), everything else (`:ext-mlkit`, `:ext-soil`) at 1. **The ledger:** 2 = arc 18's `sourceKind` tail · 3 = arc 19 / M8's `resultKind` tail · 4 = arc 21 / W1, the tag point itself · 5 = arc 21 / W4, the first bump that is not a compatible tail · 6 = arc 22 / X1, the second break and the first that carries a floor · **7 = arc 23 / Y1, the calendar point — a compatible *addition* (no existing interface changes shape) and the first bump with a *per-action* floor** — see the version note below. **Arc 24 "Events" (2026-09-02) did NOT bump it** — the first arc since 21 to leave the ledger alone: it grew the calendar's own screen in place, touched no existing interface and opened no new point, so `:ext-calendar`'s service keeps declaring the same 7 it declared at Y1 |
| `MIN_API_VERSION_FOR_STORE` | 6 (arc 22 / X1) | the floor `minApiVersion` answers for a service on one of the three arc-22 store-taking points |
| `MIN_API_VERSION_FOR_CALENDAR` | 7 (arc 23 / Y1) | the floor `minApiVersion` answers for `ACTION_CALENDAR` — the point was born at API version 7, so there is no older calendar shape for the host to accept; every other point without a row here keeps a floor of 1 |
| `ACTION_HANDWRITING_RECOGNIZER` | `…notesproutsn.extension.HANDWRITING_RECOGNIZER` | SN-namespaced action string |
| `META_API_VERSION` | `…notesproutsn.extension.API_VERSION` | the `<service>` meta-data name |
| `MAX_INK_STROKES` | 2,000 | most strokes in one recognize call |
| `MAX_INK_POINTS` | 60,000 | most points summed over all strokes |
| `MAX_PRECONTEXT_CHARS` | 20 | the host truncates `preContext` to this tail before the call |
| `MAX_RECOGNIZED_CHARS` | 20,000 | host-side cap on the returned text; the rest is dropped |
| `RECOGNIZER_NOT_READY` | `"recognizer not ready"` | the exact `IllegalStateException` message an extension throws when it cannot become READY within the call |

The action and meta-data strings are **SN-namespaced**
(`com.symmetricalpalmtree.notesproutsn.extension.*`) precisely so that Paper's own extensions —
which can be installed on the very same Nomad — are never discovered by SN's query, and vice
versa; N0 pinned this with a test rather than leaving it to convention. `RECOGNIZER_NOT_READY` is
compared **by exact message string**, not substring: it is the one case the host types as "still
downloading," and every other `IllegalStateException` is treated as a generic engine failure.

**A version bump means one of two different things, and `API_VERSION`'s history through arc 23 is
where both shapes are on the record.** Versions 2 and 3 were **compatible tails**: `ExporterInfo`
grew `sourceKind` and `ImporterInfo` grew `resultKind`, and an old host reading either tail simply
finds nothing there — `dataAvail()` runs out and the absent-tail default is exactly the old
meaning, so an old-host/new-extension pairing that predates the field still runs correctly on real
wire (proven at the D1 and M8 walks). Declaring the version that introduced the tail is what keeps
an *old* host from mis-reading it as absent when the extension needs it read — the extension
requires the newer host, not the other way around. Version 5 is not that shape. W4 turned a tag
target from a single id into a **pair** (a notebook, and optionally a page of it), which reshaped
`TagShowing`'s wire form and `TagCodec`'s stored records — there is no absent-tail reading of a
parcel whose fields changed underneath it. A W1-shaped `:ext-tags` bound to a W4 host would
unmarshal a `TagShowing` **wrongly**, not blankly, so this bump relies on the version guard alone
rather than on a graceful old-reading: it fails loudly (the constructor `require`s reject the
malformed result, and the exception crosses as `IllegalArgumentException`) precisely because the
declaration is what keeps a mismatched pairing from being reached at all. Only the tag service's
own declaration moved for it; every other extension's declaration, and its meaning, is untouched —
meta-data is still per service, and the host still accepted `1..API_VERSION` at that point.

**Version 6 (arc 22 / X1) is a third shape, and the first to break *both* directions at once.**
`IExtensionStore` was not merely grown, it was **replaced**: the version-1..5 interface's six
methods (`get`/`put`/`delete`/`keys`/`putLarge`/`getLarge`) are gone, and the version-6 interface's
six methods (`schemaVersion`/`applySchema`/`exec`/`query`/`next`/`close`) now answer those same
transaction codes. The usual range rule (`1..API_VERSION`) still protects the
old-extension/new-host direction it always has — an old extension is simply skipped — but this
time the *other* direction breaks too: a version-5 extension calling transaction code 1 against a
version-6 host would not fail loudly, it would land on `schemaVersion`, or worse `applySchema`
with a parcel shaped like the old `get`'s `String` key, which is not reliably loud the way a
rejected `TagShowing` is. So version 6 is the first bump to carry a **floor** as well as a
ceiling: `ExtensionContract.minApiVersion(action)` answers `MIN_API_VERSION_FOR_STORE` (6) for the
three points whose service is lent a store (`ACTION_SCRATCH_PAD`,
`DocumentContract.ACTION_DOCUMENT_EDITOR`, `ACTION_TAG_MANAGER`) and 1 for every other point, and
`accepts(action, apiVersion)` is the range check (`minApiVersion(action)..API_VERSION`) the
registry runs — both pure and JVM-tested (`ExtensionContractTest`).

**The live consequence, verified on the Nomad at every phase from X1 through X4:** the instant X1
shipped, every store-taking service still declared its pre-X1 number, so `ExtensionRegistry`
skipped all three of them at once — the scratch pad's notebook-toolbar button, every Document
entry and all three tag doors were **gone from the host**, deliberately, until each extension's
own phase redeclared 6 (the pad at X2, the tag manager at X3, the editor at X4). `ITagManager`
itself was reshaped again in the same bump (arc 22 / X3): `snapshot` — one ashmem blob of the
whole index — was replaced by the paged `tags`/`assignmentsOf` reads the search merge now runs, a
change only the tag service's own declaration needed to survive, since nothing about it is an
absent-tail reading (the method is gone, not a field on it).

**Version 7 (arc 23 / Y1) is a fourth shape: a compatible *addition* that still needed a version
bump, because the thing it adds is a whole new interface rather than a field on an old one.**
`ICalendar` did not exist before Y1, so there is no absent-tail reading for a host to fall back to
and no older shape for it to misread — a version-6 host simply has never heard of `ACTION_CALENDAR`
and never queries for it. Nothing about `IExtensionStore`, `ITagManager`, `IDocumentEditor` or any
other existing interface changed shape, so **every extension that already declared a version keeps
declaring exactly what it declared before** and no door this arc did not touch closes — unlike
version 6, arc 23 costs no existing extension a redeclaration. What does change is the shape of the
floor itself: `MIN_API_VERSION_FOR_STORE` was, through X4, the one floor above 1 that existed, so a
single constant sufficed; a calendar service born at version 7 needs a **different** floor
(`MIN_API_VERSION_FOR_CALENDAR`, also 7 — there being no lower number a calendar could sensibly
declare), so `minApiVersion(action)` became a small map (`ACTION_SCRATCH_PAD`,
`DocumentContract.ACTION_DOCUMENT_EDITOR` and `ACTION_TAG_MANAGER` still answering 6,
`ACTION_CALENDAR` answering 7, everything absent from the map answering 1) rather than one constant
compared against a fixed set of actions. `accepts` is unchanged in shape — still the range check
`minApiVersion(action)..API_VERSION` — because the map is exactly what `minApiVersion` was always
free to be.

**The live consequence, verified on the Nomad through Y1:** `:ext-calendar`'s service declares 7 —
the point's own birth version — from the moment it exists, so there was never a phase where the
calendar's doors were live but skipped the way the pad's, the tag manager's and the editor's were
between X1 and their own phase. Bootstrapping a point at the version that requires it, rather than
growing an old one past a floor it predates, is the version-7 shape's whole point.

---

## Trust — same signature, both directions

Nothing here is bearer-token or permission-based; trust is the certificate the two APKs were
signed with.

- **Discovery** (`ExtensionRegistry.discover`): `queryIntentServices` for the action, then a
  candidate survives only if its `<service>` is `exported`, its API-version meta-data is in
  `1..ExtensionContract.API_VERSION` (the D3 skew guard — the declared number is the version the
  extension requires of the host, reasoned at the constant), and
  `PackageManager.checkSignatures(host, candidate) ==
  SIGNATURE_MATCH`. A disabled package or component never appears in the query at all, so `pm
  disable` reads as "uninstalled" from the host's point of view. When more than one candidate
  survives, the first by `(label, package)` is kept and the rest are dropped with a log line —
  choosing among engines is a future arc's problem, not this one's.
- **Bind-time re-check** (`ExtensionBinder.call`): the signature is checked **again** immediately
  before `bindService`, because the package could have been replaced under the same name with a
  different key while a screen holding the stale `ProviderRef` was still open. A mismatch throws
  `ExtensionCallException` before any bind is attempted.
- **Extension-side gate** (`HostCallerCheck.enforce`, in `:extension-api` so every extension gets
  it for free): every AIDL stub method calls it first. It resolves the calling uid's packages via
  `Binder.getCallingUid()` + `getPackagesForUid`, requires the host's package to be among them,
  and requires `checkSignatures(callerUid, myUid) == SIGNATURE_MATCH`. Anything else is a
  `SecurityException` — belt-and-braces against the host's own checks, enforced independently on
  the side that has the most to lose from a spoofed caller.
- **Extension-side gate for a screen** (`HostCallerCheck.enforceActivity`, arc 11 / J3): an exported
  Activity the host launches for a result calls it **first thing in `onCreate`, before anything is
  inflated**, and returns at once if it answers false (the Activity has already finished itself).
  It compares `callingPackage` — which Android sets **only** for a `startActivityForResult`-style
  launch — against the host package, then checks the signature. A plain `startActivity`, an
  `am start` from a shell included, leaves `callingPackage` null and is refused: verified on the
  Nomad, where a shell `am start` of the pad screen logs `refused caller (none)` and shows nothing.
  This is why the host must launch the screen through an `ActivityResultLauncher` — a design
  constraint, not a bug.

**Marshalable exceptions only.** `HandwritingRecognizerService`'s stub methods may only let
`SecurityException`, `IllegalArgumentException`, or `IllegalStateException` leave — anything else
(an NPE, an ML Kit internal exception, an OOM) kills the Binder transaction silently, and the host
sees a dead call with no reason at all rather than a catchable failure. `engine { }` in the
service is the one place that funnels every other exception type down to a marshalable
`IllegalStateException`, distinguishing a timeout/interrupt (the engine is slow but alive — must
never look like it vanished) from a real engine failure (which also tells `ModelManager` a
remembered model may need re-verifying).

---

## Host side

- **`ExtensionRegistry.handwritingRecognizer(context)`** — the one entry point, IO-dispatched,
  re-run at every tap that needs the recognizer (N2's convert flow re-discovers rather than
  caching a stale `ProviderRef` across a screen's lifetime).
- **`ExtensionBinder.call`** — the one bind-per-operation path every call takes: explicit
  `ComponentName`, `BIND_AUTO_CREATE` on the **application** context (never an Activity context —
  the bind must not die with the screen), the connection awaited up to `BIND_TIMEOUT_MS` (3 s), the
  actual call run on IO inside a supervisor scope under a caller-supplied `callTimeoutMs`, and
  **unbind in `finally`** unconditionally. Every failure — no connection, timeout,
  `RemoteException`, `SecurityException`, a bad payload — collapses to one `ExtensionCallException`
  so callers have a single shape to handle; a `CancellationException` (the caller's own scope
  died) is re-thrown as-is rather than swallowed. SN holds no long-lived binding anywhere — the
  recognizer is call-shaped, not session-shaped.
- **`RecognizerClient`** wraps that binder with the recognizer's own method signatures and its own
  timeouts: `STATUS_TIMEOUT_MS` 2 s, `INK_TIMEOUT_MS` 10 s, `PAGE_TIMEOUT_MS` 30 s (one ML Kit call
  per line, and the first call after the extension's process starts also loads the model — hence
  the long page ceiling). **Outward caps run in `InkCaps` before any bind**: over
  `MAX_INK_STROKES`/`MAX_INK_POINTS`, a malformed stroke, or a negative/NaN area throws
  `InkTooLargeException` without ever touching the extension — but a **zero** dimension is floored
  to 1 px first (N3): a single dot committed as a one-point stroke has zero-size tight bounds, and
  that is degenerate-but-real ink, not a caller bug; `preContext` is truncated to its last
  `MAX_PRECONTEXT_CHARS` on the way out. **Inward is untrusted**: a status outside `0..3`
  becomes `UNAVAILABLE`, returned text is `?: ""` and capped at `MAX_RECOGNIZED_CHARS`, and the
  extension's own `RECOGNIZER_NOT_READY` message is recognized and re-thrown as the typed
  `RecognizerNotReadyException`.
- **`RecognizerReadiness.ensureReady`** — the model-consent flow, driven once per caller: `status()`
  READY → run the caller's `onReady` immediately; NEEDS_DOWNLOAD → a one-time "Recognition model
  needed" dialog, but only after `Connectivity.isOnline` is checked (ML Kit's downloader *hangs*
  rather than fails outright with no network, so offering Download while offline would look
  broken — the dialog shows an offline notice with only OK instead); a Download tap calls
  `prepare()` and opens a progress dialog that polls `status()` every `POLL_MS` (2 s) — an
  **elapsed-seconds counter**, not a spinner, because an e-ink refresh every couple of seconds
  reads as "still working" for the price of one full-screen animation loop; DOWNLOADING (someone
  else already consented) goes straight to that same progress dialog; UNAVAILABLE is a plain
  problem dialog. `status()` is polled **every iteration, offline included** (N3) — it is a purely
  local bind, so a model that finished downloading just as connectivity dropped is still seen as
  READY rather than falsely failed on the offline clock. Give-up rules: `MAX_POLL_FAILURES` (5)
  consecutive failed polls, `OFFLINE_GIVE_UP_MS` (30 s) offline-and-not-ready mid-download rather
  than waiting out the full `DOWNLOAD_CAP_S` (300 s) cap, or the cap itself. Cancel only hides the dialog — the download
  keeps running inside the extension's own process, so the next attempt finds it further along or
  already finished. Every dialog belongs to the host; the extension shows nothing of its own.
- **`InkPayload.fromStrokes`** (`notebook/InkPayload.kt`) — the one place a page's ink becomes the
  bare `InkStroke` list a recognizer receives: per stroke, its point arrays in page px, nothing
  else. **The order of the returned list is load-bearing** — ML Kit reads ink as a sequence in
  writing order, so the caller must already hand this function the strokes in commit order (a
  `LinkedHashMap`'s iteration, never a `Set`'s hashed order — the exact Paper H4 "Meeting Notes"
  trap this arc inherited). A stroke with no points is skipped, since an `InkStroke` cannot be
  empty.

---

## Extension side

`HandwritingRecognizerService` has no launcher Activity and is never opened by a person — it
exists only to be bound by the host. `onCreate` calls `ModelManager.init` then `ModelManager.warmUp`:
with the model already remembered present, the client is built immediately (READY with **no** ML
Kit network check at all); otherwise nothing happens until `prepare()`. Every stub method calls
`HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)` first (`HOST_PACKAGE` is a per-build-type
`buildConfigField` — the dev extension only trusts the dev host package, the release extension
only the release one).

**`ModelManager`** owns the `en-US` model and the ML Kit client for the process's lifetime:

- `status()` never waits on ML Kit — a cold `isModelDownloaded` call takes tens of seconds on a
  Nomad, so a synchronous check would misreport READY-in-a-few-seconds as UNAVAILABLE. It reads
  only in-memory state (`recognizer != null` / the in-flight `chain`).
- **Only `prepare()` may start the ensure-ready chain** (`isModelDownloaded` → `download` only if
  needed → build the client) — the host's consent dialog always precedes it. `awaitReady(timeoutMs)`
  is what the recognize calls use instead: it waits for a chain **already in flight**, but never
  starts one itself.
- **Model-present memory**: once the chain has seen the model on disk, that fact is kept as a
  `SharedPreferences` flag in the extension's own sandbox. A fresh process with the flag set
  builds the client directly in `warmUp()` (`onCreate`) — this is the fast path a cold
  `isModelDownloaded` would otherwise force every process restart to pay, since ML Kit's own check
  is what's slow, not the client construction.
- **`onEngineFailure`** — a real (non-timeout) failure on a client built via the flag shortcut
  triggers one asynchronous `isModelDownloaded` re-check; only a **confirmed** absence clears the
  flag and drops the client (a merely slow first inference is not "gone").
- **`prime`** — a throwaway inference on a synthetic two-point stroke, on a daemon thread right
  after the client is built, so the session's first real recognition doesn't pay ML Kit's lazy
  model-load cost (observed 1.5–4 s on the fleet).

**`MlKitEngine`** runs the actual calls synchronously on the Binder thread while ML Kit's executor
does the work, under an **absolute deadline** derived from the service's whole-call budgets —
`INK_BUDGET_MS` 9,500 ms and `PAGE_BUDGET_MS` 28,000 ms, each sized just under the host's own 10 s
/ 30 s timeouts so the extension always gives up before the host has already stopped waiting.
`recognizePage` tolerates a single line's failure (that line contributes nothing, the page carries
on) but rethrows if *every* attempted line failed, and never tolerates a deadline timeout that way
— a page that runs out of time aborts whole, because a warm retry (model now loaded) is far
cheaper than serving a silent partial result.

**`StrokeSegmenter`** (pure geometry, no ML, no Android types) turns a flat page of strokes into
reading-order lines and paragraphs for `recognizePage`, using a vertical coverage-histogram
projection rather than sorting-and-merging by centre (which interleaves adjacent lines the moment
a descender dips past the next line's ascender). **`Dots`** compensates for ML Kit's tendency to
skip or misread a handwritten period as a comma — replacing tiny strokes with an unambiguous
circle before recognition, and fixing a line's trailing punctuation afterward from pure geometry
(size, position in the line band) rather than reading the recognized text. Its tiny-stroke
threshold scales from a **line** height, never a multi-line area's: `recognizePage` passes each
line's height, and the direct `recognizeInk` stub derives one from the ink via the segmenter (N3 —
a selection spanning two written lines would otherwise double the threshold and swallow real
punctuation into dot circles). **`Box`** is the shared
immutable rectangle both use, so the segmenter stays pure Kotlin end to end.

Language is **hardcoded `en-US`** — a setting could add others later with no format impact, but
nothing in this arc reads a locale.

---

## The extension store (arc 11 / J2 — rebuilt on tables, arc 22)

`IExtensionStore` is **not a capability point** — it is the service the host offers an extension it
has already bound: a per-package, host-owned, encrypted store, handed in as a *parameter* of the
calls that need it and revoked when the bind ends. The rule it exists to enforce has not moved:
**an extension writes nothing to disk itself, ever.** Its data is the host's, under the host's key,
in the host's directory, and it survives the extension being uninstalled.

Arc 11 / J2 shipped this as a key/value store — `get`/`put`/`delete`/`keys`, plus an
ashmem-backed `putLarge`/`getLarge` pair above the inline cap. **Arc 22 "Tables" rebuilt it
whole**, because every store user's awkward shape turned out to be downstream of hiding SQLite
behind six blob methods: the scratch pad's 4 MiB page ceiling and whole-page re-encode on every
save, the tag index's one 4 MiB value with a bespoke codec and a whole-index decode per search, the
document editor's line-codec blobs for a caret map and a dictionary. The file underneath was
always SQLite. `IExtensionStore` v6 **replaced** the interface (`API_VERSION` 6 — the ledger
above): an extension now declares its tables once and sends parameterized SQL, and the host
validates, runs and encodes the reply, instead of moving opaque bytes under a key. X1 rebuilt the
seam and the host's store; X2, X3 and X4 moved the scratch pad, the tag manager and the document
editor onto rows in their own sections below — this section is the seam under all three.

### The contract — six methods

```
int          schemaVersion();
void         applySchema(in StoreSchema schema);
long[]       exec(in StorePayload batch);
StoreResult  query(in StorePayload statement);
StoreResult  next(int handle);
void         close(int handle);
```

`schemaVersion` answers the version already applied to this store (0 = nothing declared yet).
`applySchema` is idempotent — it runs the steps `applied + 1 .. schema.version`, each its own
transaction with the version bump (crash-resumable: a step that has landed is never re-run), and
throws `IllegalStateException(STORE_SCHEMA_NEWER)` on a downgrade. `exec` runs N statements as
**one** transaction, all-or-nothing, and answers `changes()` per statement in order — a failure
anywhere rolls the whole batch back. `query` runs exactly one `SELECT`/`WITH` to completion,
encodes the rows and hands back the first chunk, naming a `handle` when more follow; `next` drains
the following chunk (`IllegalStateException` on an unknown or already-drained handle); `close`
drops an unfinished result early (a no-op on an unknown handle — every parked result is dropped on
revoke regardless).

**`exec` / `query` refuse with `STORE_SCHEMA_UNAPPLIED` until `applySchema` has run on this
binder** — structural, not a courtesy check: a query cannot precede the declaration of what it
queries, so there is no way to read or write a table this binder has not yet declared (a no-op
`applySchema` call, when the versions already match, is one `SELECT` against `host_schema`).
`ExtensionStoreGate.declared` is the flag, per binder, flipped by the first successful
`applySchema` on it; `schemaVersion` itself needs no declaration.

### The caps

`ExtensionContract.STORE_*`, enforced by the host and pinned by test:

| Cap | Value | Why |
|---|---|---|
| `STORE_MAX_INLINE_BYTES` | 512 KiB | the `byte[]` path's ceiling — the Binder transaction budget is ~1 MB |
| `STORE_MAX_VALUE_BYTES` | 4 MiB | one payload in either direction: one statement batch, or one chunk of a query result — above it a payload rides ashmem |
| `STORE_MAX_RESULT_BYTES` | 32 MiB | the whole **materialized** query result; past it the host refuses with `STORE_RESULT_LARGE` and the extension pages with `LIMIT` |
| `STORE_MAX_ROW_BYTES` | = `STORE_MAX_VALUE_BYTES` | a row is never split across chunks, so one encoded row must fit one chunk; above it, `STORE_ROW_LARGE` |
| `STORE_MAX_BATCH_STATEMENTS` | 10 000 | most statements in one `exec` (one transaction) |
| `STORE_MAX_SQL_CHARS` | 8 192 | longest SQL text of one statement |
| `STORE_MAX_ARGS` | 999 | most bound arguments per statement — SQLite's own default bind limit |
| `STORE_MAX_TABLES` | 64 | most tables one schema may create, counted over every step |
| `STORE_MAX_SCHEMA_STEPS` | 256 | most versions (steps) a `StoreSchema` may declare |
| `STORE_MAX_STEP_STATEMENTS` | 64 | most statements per step |
| `STORE_MAX_OPEN_RESULTS` | 4 | most unfinished query results one binder may hold open at once |

Typed refusals, `IllegalStateException` messages compared **verbatim**: `STORE_RESULT_LARGE`,
`STORE_ROW_LARGE`, `STORE_SCHEMA_NEWER` (a downgrade — an extension never sees a store at a schema
newer than it knows, and the host never rolls one back), `STORE_SCHEMA_UNAPPLIED`,
`STORE_RESULTS_OPEN` (a fifth query wanting a handle on a binder that already holds four). The
key/value era's caps are **deleted**: `STORE_MAX_KEY_CHARS`, `STORE_MAX_KEYS`, `STORE_VALUE_LARGE`.

### The validator (`StoreSql`)

Pure and shared (`:extension-api`), so an extension can pre-check what the host will refuse. A
tiny tokenizer honest about all four SQL quote forms — `'…'` (string), `"…"` / `` `…` `` / `[…]`
(identifier) — and both comment forms (`-- …` to end of line, `/* … */`) feeds a handful of rules;
nothing here parses SQL, it only refuses shapes the seam does not carry:

- **One statement.** No `;` outside a literal or comment; **one trailing `;` is tolerated** (an X1
  implementer call, not in the original spec — the X2–X4 schemas are all written `;`-terminated,
  and refusing that one case would have been a paper cut with no safety behind it). Any other `;`
  is the one-statement refusal.
- **The head keyword decides the kind.** `SELECT` / `WITH` for `checkQuery`; `INSERT` / `REPLACE` /
  `UPDATE` / `DELETE` / `WITH` for `checkExec`; `CREATE` / `ALTER` / `DROP` for `checkDdl`.
- **A query cannot smuggle a write under `WITH`.** `INSERT` / `UPDATE` / `DELETE` anywhere in the
  token stream of a query is refused, and so is `REPLACE` immediately followed by `INTO` — a
  `rawQuery` of `WITH … DELETE …` would actually run it. The `replace(x, y, z)` **function** still
  passes: the refusal is `REPLACE INTO` specifically, not the bare word `REPLACE`.
- **The denylist**, anywhere in the token stream for `query`/`exec`: `ATTACH DETACH PRAGMA VACUUM
  CREATE DROP ALTER BEGIN COMMIT ROLLBACK SAVEPOINT RELEASE REINDEX ANALYZE load_extension`. DDL
  keeps its own head word and refuses a second one anywhere (`… DROP` inside a `CREATE`), plus
  `VIEW TRIGGER VIRTUAL TEMP TEMPORARY` — no views, triggers, virtual tables or temp objects in v6
  (each is an additive later tail if ever wanted).
- **Reserved names, checked on every identifier token — bare or quoted, in every statement kind.**
  `StoreNames.isReserved` refuses anything starting with `host_`, `sqlite_`, `room_` or `android_`
  (case-folded) — the prefixes that protect what the file holds besides an extension's own tables:
  the host's own `host_schema`, SQLite's catalog, and the names the Room era and the platform
  mint. The file is per-package, so those are the only things a validator here needs to protect,
  and the check applies to every WORD and quoted identifier in every statement kind — so
  `sqlite_version()` is refused too, a harmless loss.
- **Positional binds only** (`?`, `?NNN`); `:name` / `@name` / `$name` are refused as
  `IllegalArgumentException("named binds are not supported (…) — use ?")` — a name is one more
  parser to trust.
- **DDL shape** (schema steps only): `CREATE TABLE`, `CREATE [UNIQUE] INDEX … ON`, `ALTER TABLE …
  ADD [COLUMN] | RENAME TO | RENAME [COLUMN] … TO`, `DROP TABLE|INDEX`, each with its `IF [NOT]
  EXISTS` form, plus `WITHOUT ROWID` and `REFERENCES … ON DELETE …` (foreign keys are ON for the
  store connection — see Room below — so a declared cascade cascades). The object a statement
  creates, alters or drops (and an index's `ON` table) must be bare and `StoreNames.isValid`
  (`^[a-z][a-z0-9_]{0,62}$`, not reserved); column names are the extension's own business, so
  `"order"` and `pageId` both pass.

Every refusal is an `IllegalArgumentException` naming which rule fired.

### The codec (`StoreCodec`)

Two wire documents, pure and shared, big-endian `DataOutputStream`, the arc-11 page-codec idiom:
**statements** — magic `NSST` · u8 version 1 · u16 count · per statement u32 sqlLen + UTF-8 sql ·
u16 argc · args as cells; **rows** — magic `NSRW` · u8 version 1 · u16 columnCount · per column a
u16 nameLen + UTF-8 name · u32 rowCount · per row per column a **cell**: u8 tag (`0 NULL · 1
INTEGER i64 · 2 REAL f64 · 3 TEXT u32+UTF-8 · 4 BLOB u32+bytes`). Unknown magic or version, a
truncated document, a bad tag or a length past the end all throw `IllegalArgumentException` —
**unreadable is never empty** (the arc-11 rule that keeps a half-read value from being written
over). A rows document needs at least one column; a zero-column document is corrupt, not empty.

`Cell` is a sealed class over SQLite's five storage classes (`Null` / `Integer` / `Real` / `Text` /
`Blob`, `Blob` comparing by content); `Cell.of` converts `null` / `Long` / `Int` / `Boolean` /
`Double` / `Float` / `String` / `ByteArray` and refuses anything else. `Row`'s typed accessors
(`long` / `real` / `text` / `blob`, each with an `OrNull` twin) throw `IllegalArgumentException` on
a wrong storage class — the "bad row = dropped row" contract an extension can catch — except that
an `INTEGER` also answers `real()` (SQLite's own column affinity, not a bug). `StoreReads.all(store,
statement)` is the extension-facing loop over `query`/`next` that stitches chunks into one
`StoreRows`, closing the parked remainder on any failure so a failed read never leaves a handle
behind; `StoreReads.exec` is its `exec` counterpart.

`StoreChunker` splits a query's rows into chunks **as the host reads them**, living in
`:extension-api` beside `StoreCodec` rather than in the host, because it is codec arithmetic:
`StoreCodec.rowsHeaderBytes` / `rowBytes` / `statementBytes` are the exact bytes the writers
produce, pinned by test, and the caps are raised **at the row that crosses** — `add` throws
`IllegalStateException(STORE_ROW_LARGE)` for a single row that will not fit one chunk (with its
header) and `STORE_RESULT_LARGE` for a result whose chunks would sum past the result cap — so the
host stops reading there instead of materializing the rest. **A row is never divided between two
chunks.** `finish()`'s one asymmetry: a result that ends exactly on a chunk boundary gets no empty
trailing chunk, but an empty result still gets its one (an empty rows document, not nothing).

### The chunk protocol

A payload at or under `STORE_MAX_INLINE_BYTES` (512 KiB) rides inline as a `byte[]` in a
`StorePayload`; anything above it, up to `STORE_MAX_VALUE_BYTES` (4 MiB), rides a `LargeValue` over
ashmem — the arc-11 / J2 `putLarge` handshake, unchanged: the sender creates the region, writes,
protects it read-only, hands it over and closes its own handle once the transaction is marshalled;
the receiver copies out exactly `byteCount` bytes and closes (`StorePayload.of` / `readAndClose`).
Ashmem is now purely the **chunk carrier** for the two payload types (`StorePayload` /
`StoreResult`), not a value type of its own — the key/value era's `putLarge`/`getLarge` methods
are gone with the interface they belonged to.

A query result that needs more than one chunk is **parked as bytes**, not as a live region, behind
a `handle` on `ExtensionStoreGate` (`HashMap<Int, ArrayDeque<ByteArray>>`, at most
`STORE_MAX_OPEN_RESULTS` per binder — a fifth throws `STORE_RESULTS_OPEN`). The **binder**
(`ExtensionStoreBinder`) mints the actual ashmem region only when a chunk is about to leave: an
outgoing chunk above the inline cap is wrapped in a region created fresh at `query`/`next` time,
parked in a per-Binder-thread `ThreadLocal<SharedMemory>`, and closed in `onTransact`'s `finally`
— **after** `super.onTransact` has written the reply holding a dup of the descriptor, the ordering
this app has relied on since the scratch pad's own `getLarge`. Every parked chunk, at every
handle, is dropped when the gate is `revoke()`d.

### The schema lifecycle (`StoreSchema`)

`StoreSchema(version, steps)` is ordered DDL: `steps[i]` is the DDL that takes a store from version
`i` to `i + 1`, and `version == steps.size` always. **Construction *is* the DDL validator run** —
every statement in every step is `StoreSql.checkDdl`'d at construction, so a bad schema fails on
the extension's side, at the declaration, never at bind, and again at unmarshal on the host's; the
table cap (`STORE_MAX_TABLES`) is counted statically over every `CREATE TABLE` across all steps in
the same pass. Version is `1..STORE_MAX_SCHEMA_STEPS` (a zero-step schema is a bug, not a
declaration); each step holds `1..STORE_MAX_STEP_STATEMENTS` statements.

The host keeps the version it has applied to each store in its own table (`host_schema (id INTEGER
PRIMARY KEY CHECK (id = 0), version INTEGER NOT NULL)`, one row) and runs only the missing steps on
`applySchema` — **each step its own transaction with the version bump inside it**, so a crash
between steps resumes at the next one rather than re-running a step that already landed. A step
that fails rolls back *that step only* and leaves the version where it was; the binder stays
`declared` if an earlier `applySchema` on it already succeeded. A downgrade — `applySchema` with a
version below the one already applied — is refused with `IllegalStateException(STORE_SCHEMA_NEWER)`:
an extension never sees a store at a schema newer than it knows, and the host never rolls one back.

### The floor

`IExtensionStore` v6 did not just add methods, it **replaced** the interface: the version-1..5
methods (`get`/`put`/`delete`/`keys`/`putLarge`/`getLarge`) are gone, and the six methods above now
answer those same transaction codes. That breaks the *old-extension/new-host* direction the usual
range rule protects for free, but also the *new-host/old-extension* one: a version-5 extension
calling transaction code 1 on a version-6 host would land on `schemaVersion`, or worse
`applySchema` with a parcel shaped like a `String` key — not reliably loud. So this bump carries a
**floor** as well as the usual ceiling: `ExtensionContract.MIN_API_VERSION_FOR_STORE` = 6, and
`minApiVersion(action)` answers it for the three points whose service is lent a store
(`ACTION_SCRATCH_PAD`, `DocumentContract.ACTION_DOCUMENT_EDITOR`, `ACTION_TAG_MANAGER`) and 1 for
every stateless point; `accepts(action, apiVersion)` is the range check
(`minApiVersion(action)..API_VERSION`) the registry runs, both pure and JVM-tested. (Arc 23 / Y1
later added a fourth floor, `MIN_API_VERSION_FOR_CALENDAR` = 7, for the calendar point — see § The
calendar point below; `minApiVersion` became a small map at that point rather than one constant
compared against a fixed set of actions, but the shape this section describes is otherwise
unchanged.)

**The live consequence, X1 through X4, verified on the Nomad each time:** the moment X1 shipped,
every store-taking service still declared its pre-X1 number, so `ExtensionRegistry` skipped all
three at once — the scratch pad's notebook-toolbar button, every Document entry and all three tag
doors were **gone from the host**, deliberately, until each extension's own phase redeclared 6 (the
pad at X2, the tag manager at X3, the editor at X4). The stateless points — the recognizer, both
exporters, both importers — never moved, because nothing about *their* interfaces changed.

### The `user_version` ladder (`StoreFormat`)

The store **file's** format rides `PRAGMA user_version`, decided by the pure table
`StoreFormat.decide(userVersion, hasLegacyTables)` and acted on by the `SupportSQLiteOpenHelper`
callback whose own version parameter *is* `StoreFormat.VERSION` (2 — the open helper's own version
machinery gives this ladder a transaction for free; Room is not involved):

- `0` on an empty file, no legacy tables → `FRESH` — `onCreate` runs, `host_schema` is created.
- `1` (the Room-era key/value format) or **any** `kv` / `room_master_table` present in
  `sqlite_master` at any version → `WIPE` — `onUpgrade` drops those two tables and creates
  `host_schema`, all inside the helper's own version transaction. **No migration** — the arc-22
  wizard's call: `0.1.0-ratta` is unreleased and the Nomad's arc-11 data was test data. The wipe is
  logged as a row count (`"wiped legacy store for <pkg> (format 1, N kv row(s) dropped)"`), never a
  key or value.
- `2` = `VERSION`, already the table store → `OPEN` — nothing runs.
- above `VERSION` → `REFUSE` — `onDowngrade` **throws**; a newer host wrote this file, and
  never-delete-on-corruption applies: the file is left exactly as found and the extension reads as
  "unavailable," never wiped or repaired.

A restored backup carrying a legacy-shaped store is wiped on its next open exactly the same way —
there is no separate restore-time path, since the ladder runs on `user_version` and
`sqlite_master`, not on how the file arrived (see Backup below).

### Room left the store

Extension tables are unknown at compile time, so Room's entity machinery bought nothing once the
schema became the extension's to declare: `ExtensionStoreDatabase` is a thin wrapper over a
`SupportSQLiteOpenHelper` built from the **same** `SoilCrypto` / `KeyOpener` factories every other
SQLCipher open in this app takes — still `NonDestructiveOpenHelperFactory`-wrapped (a wrong key
reports corruption without deleting), still the create-door / open-door split `ExtensionStores`
always had, still cached for the process's lifetime and closed only by `closeAll()` (tests/debug),
still WAL with `wal_autocheckpoint = 100` and `busy_timeout = 5000` set in `onOpen`. `KvDao` /
`KvEntity` and Room's dependency on the store file are **deleted** outright.

**Foreign keys are ON as a pool setting, not a per-connection PRAGMA**: `onConfigure` calls
`db.setForeignKeyConstraintsEnabled(true)`, which the connection pool applies to every connection it
opens — a `PRAGMA foreign_keys = ON` issued once in `onOpen` would only have reached the one
connection that ran it, and WAL readers are separate connections. This is documented as a promise
of the seam: a declared `REFERENCES … ON DELETE CASCADE` actually cascades, which is what lets
`ScratchSchema` drop a page's strokes with one `DELETE FROM page` and `TagSchema` drop a tag's
assignments with one `DELETE FROM tag`.

### The executor split

`StoreExecutor` (`transaction` / `ddl` / `exec` / `query`) carries **no Android type in its
signature**, precisely so `ExtensionStoreGate` — everything worth unit-testing about the seam —
runs on the JVM; `SupportStoreExecutor` is the one implementation that actually touches
`SupportSQLiteDatabase`, and tests inject a fake. `query`'s `RowSink` callback answers column
names once, then rows one at a time until it returns `false` — the hook `StoreChunker` uses to
stop the underlying cursor read at the exact row that would cross a cap, rather than materializing
a refused result first and throwing after.

### The gate

`ExtensionStoreGate` requires the caller's uid to be the bound `extUid` and the gate not `revoked`
before anything else, on every method — `SecurityException` otherwise. Every executor failure —
SQLite full, locked, an I/O error, **a constraint violation included** — is mapped to
`IllegalStateException` inside `io {}`; the message crosses, and the extension reads it, but the
host never parses it back. `exec` is `@Synchronized` — one writer per store at a time; reads run
concurrently under WAL. **No transaction is ever held open across a Binder call**: `exec`'s
transaction begins and ends inside one call, and `query` reads its statement to completion before
answering, so nothing here can leave the connection mid-transaction between two calls the way a
long-held write lock would.

### Only three exceptions cross a Binder — restated

`SecurityException`, `IllegalArgumentException`, `IllegalStateException` remain the whole set this
contract uses. `ExtensionStoreBinder.region {}` still wraps the ashmem step **outside** the gate's
`io {}`, because an `ErrnoException` from a mapping or creation failure is checked and outside
Binder's marshalable set — left alone it would kill the transaction silently and the extension
would read the empty reply as success, the exact Paper-era failure this app has never allowed back
in. An extension treats all three exception types, and a `RemoteException` from a dead bind, the
same way: *store unavailable*.

### The one extension table the host reads

`prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL)` — the document editor's own schema — is the
**one** extension table any host code reads: Document-PDF export takes the editor's saved text
size (arc 19 / M9, rewired at X1) straight from it, through the host's own `StoreExecutor`,
**never a binder**, and only if `ExtensionStoreDatabase.hasTable` says the table already exists —
the same never-mint rule the rest of the app applies to a `.soil` it does not own. The table's own
name and both column names are pinned in `DocumentContract`
(`PREFS_TABLE`/`PREFS_KEY_COLUMN`/`PREFS_VALUE_COLUMN`/`PREF_TEXT_SIZE`) rather than spelled twice,
so the two sides cannot drift on what they mean by "the prefs table." Every failure on this path —
no editor installed, no store file, no table yet, an unparseable value, a locked library — lands on
`DocumentPdfMetrics.DEFAULT_TEXT_SIZE_SP`: a text size is comfort, never something an export may
refuse over.

### The self-test

SQLCipher, `SharedMemory` and a real `Binder` cannot run on the JVM, so the debug library's
⋯ → **"Extension store self-test"** is the only on-device proof, driven through a **real**
`ExtensionStoreBinder` (called in-process, so `Binder.getCallingUid()` is the app's own uid and the
gate's check passes) over `Garden/probe.test.db`, a fake package recreated fresh on every run: an
encrypted-header check → `exec` refused with `STORE_SCHEMA_UNAPPLIED` before any `applySchema` →
`applySchema` v1 (idempotent — applying it twice is a no-op) → 5 000 stroke-shaped 1 KiB-blob rows
across two `exec` batches (each rides ashmem) → a `query` streaming them back in more than one
chunk, byte-exact → a batch with a duplicate primary key mid-list leaves **zero** new rows behind →
`applySchema` v2 (`ALTER TABLE … ADD COLUMN`) then v1 again refused with `STORE_SCHEMA_NEWER` →
`PRAGMA`, a two-statement string, a query that writes under `WITH`, and a schema declaring `CREATE
VIEW` all refused as `IllegalArgumentException`, and `host_schema` itself is unreachable from SQL
(a reserved name) → wrong uid, then a revoked binder, both `SecurityException` → a **legacy-shaped
file** built by the probe itself (`Garden/probe.legacy.db`, `kv` + `room_master_table`,
`user_version 1`) opens as a wipe to format 2. Nomad timings, kept in the summary string: **open
≈ 2.0 s** (cold KDF), **5 000 rows in ≈ 2.4 s**, **read back 2 chunks in ≈ 0.9 s**, **legacy wipe
≈ 2.0 s** (also one cold KDF). The probe recreates both its own files every run —
`ExtensionStores.closeAll()` first, since a cached store is never closed otherwise — so the create
door and the wipe door are both proved every time, not just the first.

### Backup (arc 21 / W5)

Unchanged in shape by the rebuild: **every `Garden/<pkg>.db` is in the backup set**, copied on
every pass unconditionally, ordered after the notebooks and before the index, WAL-checkpointed
first when this process has the store open (`ExtensionStores.checkpointIfOpen`). One sentence
added by X1: **a legacy-shaped store restored from an old backup is wiped on its next open like
any other** — the `StoreFormat` ladder runs on `user_version` and `sqlite_master`, not on how the
file arrived, so a copied-back arc-11 `.db` is indistinguishable from one that was simply never
upgraded, and gets the identical `WIPE` decision, logged the identical way.

There is still **no restore this arc** — the manual copy-back (`<pkg>.db` plus its `-wal` if the
backup has one, `-shm` never, app closed, ciphertext keyed to the device that wrote it) is
documented in [`docs/backup.md`](backup.md), and a whole-library restore screen is a `BACKLOG.md`
item, the same answer arc 17 gave for the library itself.

### Verification

SQLCipher, `SharedMemory` and a real `Binder` cannot run on the JVM, so the store is checked
from two sides:

- **JVM** — `StoreCodecTest` round-trips every cell kind, an empty result and the zero-column
  guard; `StoreSqlTest` drives the tokenizer (all four quote forms, both comment forms, a `;`
  inside a string, the one-trailing-`;` tolerance), every denylist word, the reserved-name refusal
  on bare and quoted identifiers, the `WITH … DELETE` smuggling refusal, and the DDL shape checks;
  `StoreChunkerTest` pins exact chunk counts, `STORE_ROW_LARGE`, `STORE_RESULT_LARGE` and the
  boundary-exact no-empty-trailing-chunk rule; `StoreSchemaTest` pins construction-time DDL
  validation and the table-count cap; `StoreWireRulesTest` pins `StorePayload` / `StoreResult`
  unmarshal validation; `StoreReadsTest` pins the chunk-stitching loop and its close-on-failure
  rule; `ExtensionStoreGateTest` drives every check and cap over a fake `StoreExecutor` with an
  injectable calling uid — uid/revoked on every method, the unapplied-schema refusal, batch
  rollback, the open-result handle lifecycle including the fifth-result refusal and
  revoke-drops-all; `StoreFormatTest` pins the ladder as a pure decision table;
  `ExtensionContractTest` pins every `STORE_*` cap and the exact typed-refusal strings.
- **Device** — the self-test above is the only proof that SQLCipher, ashmem and a real `Binder`
  agree with the JVM's picture of the gate: OK / FAIL as a toast, the full trace and timings in
  `Slog`.

---

## The scratch-pad point (arc 11)

> The pad **as a feature** — its screen, tools, pages, store layout, transfers and failure table —
> is [`docs/scratchpad.md`](scratchpad.md). What follows is the **seam**: the point, the held bind,
> the wire types, and what each side is allowed to know.

`ACTION_SCRATCH_PAD` is SN's **second** capability point and its first **screen-owning** one: the
extension owns an off-paper Activity (`ACTION_SCRATCH_PAD_SCREEN`) that the core launches for a
result and returns from. The core grows no second drawing surface — the pad's canvas lives in the
extension's own process, which is also why the extension registers g-paper itself
(`RattaEngine.register()` in `ScratchPadApplication`; **no Onyx** — SN has none).

### The held bind

This is the one place SN keeps a binding across more than a single call, and the reason is that the
*operation* is longer, not that the rule changed: the operation **is** the showing of the screen.
`ExtensionBinder.hold` is `call`'s bind half without the unbind — same explicit component, same
signature re-check immediately before `bindService`, same `BIND_AUTO_CREATE` on the application
context, same `BIND_TIMEOUT_MS`. It returns a `HeldBinding`, which runs any number of timed calls
over that one connection with `call`'s exception mapping, reports `isDead` once the connection dies
(`onBindingDied` / `onServiceDisconnected`) or it is closed, and unbinds on an **idempotent**
`close()`. On any failure during the hold itself the attempted bind is released before the
`ExtensionCallException` is thrown.

The bracket is: `begin(store)` → launch the screen → the result → `end()` → unbind → revoke the
store binder — the last three in one `finally`, on every path.

```
interface IScratchPad {
    void begin(IExtensionStore store);                                // hold the store for this showing
    void receiveInk(in InkBundle chunk, int placement, boolean last); // notebook → pad (J5)
    InkBundle takeOutgoing(int chunkIndex);                           // pad → notebook (J5)
    void end();                                                       // drop the store, clear pending ink
}
```

Every byte of ink crosses through these methods. **Nothing rides the Intent** but two booleans:
`EXTRA_SCRATCH_SEND_ENABLED` (opened from a notebook, so the pad shows Send) and
`EXTRA_SCRATCH_OPEN_RECEIVED` (opened right after a `receiveInk`, so the pad opens on the received
page with the strokes selected).

### The wire types

`WireStroke` — deliberately **not** Paper's name `PaperStroke`; "Paper" names the other app and a
wire type is on the wire forever. It is a whole paper stroke minus its id and time: parallel
`x` / `y` / `pressure` / `tilt` arrays in the authoring page's px space, plus `width`, `colorArgb`
and the g-paper `StrokeStyle` **name**. No id (both sides mint fresh ones), no time, no page id, no
notebook id.

`InkBundle` is one chunk of a transfer: its strokes plus the page px geometry they were authored in
(`0 × 0` = unknown → the reader uses its own page). An **empty** bundle is legal — it is how
`takeOutgoing` says "done".

Both run `requireValid` **in the constructor**, which is also where unmarshalling lands, so a
malformed stroke rejects the whole bundle it rides in rather than being quietly dropped. `InkChunks`
holds the chunking rule once, for both sides: greedy at the per-call caps, and **a single stroke over
the point chunk cap is its own chunk** — never split, still bounded by the whole-transfer cap.

| Constant | Value | What it bounds |
|---|---|---|
| `MAX_TRANSFER_STROKES` | 10 000 | one whole transfer, either direction |
| `MAX_TRANSFER_POINTS` | 400 000 | ditto, summed over its strokes |
| `TRANSFER_CHUNK_STROKES` | 300 | one Binder call |
| `TRANSFER_CHUNK_POINTS` | 20 000 | ditto (≈ 320 KB of floats — under the ~1 MB transaction budget) |
| `TRANSFER_MAX_CHUNKS` | 74 | how many chunks the host drains — a safe **upper bound** on what `chunk()` can produce |

The first four are Paper's **shipped** values (its S2 outcome), not the pre-S2 table in its plan
appendix, and a JVM test pins each one.

`TRANSFER_MAX_CHUNKS` is SN's own, and it is the arc's one **deviation from Paper** — J6's review
found the inherited 34 was derived from the stroke cap alone (`ceil(10 000 / 300)`), which is not an
upper bound on what the chunker produces: a chunk also closes when the **next** stroke would cross
the point cap, so 39 strokes of 10 001 points — inside both whole-transfer caps — chunks into 39,
and the drain would have called a legal transfer truncated. The bound now counts both reasons a
chunk closes (stroke-driven, at most `MAX_TRANSFER_STROKES / TRANSFER_CHUNK_STROKES`; point-driven,
fewer than `2 * MAX_TRANSFER_POINTS / TRANSFER_CHUNK_POINTS`, because summing those pairs counts
each point at most twice; plus the last chunk) and is **computed from the other four** rather than
written down. It is loose on purpose — a runaway guard, not a target: the drain normally stops at
the first empty bundle, one call after the ink. Three tests pin it, one per chunking shape.

### Host side — `ScratchPadClient`

**Since arc 23 / Y4 the whole lifecycle below is `HeldInkClient`'s, not `ScratchPadClient`'s own —
`ScratchPadClient` is a thin `HeldInkClient<IScratchPad, Int>` whose companion `Point` is the
`HeldInkPoint` supplying the pad's two actions, two extras, four calls and three budgets.** Through
Y3 the pad and the calendar (below) each carried their own copy of this bind-open-send-drain-finish
sequence, and the copies had already drifted once — the settle rule (a timed-out last chunk is
waited on again rather than believed, § below) landed on the calendar's copy alone at Y3 and the
pad did not gain it until this unification — which is the `RattaNotebookView` sibling-copy trap
playing out in a second seam. One instance of `HeldInkClient` per calling screen. `open(sendEnabled,
openReceived)`:

1. `ExtensionStores.open` **on IO, before the bind** — the pre-open rule. Measured on the Nomad:
   a cold create is **3 123 ms** end to end and a warm one **114 ms**. A 27× difference is what a
   cold SQLCipher KDF costs, and it must never sit inside a call's timeout window.
2. Mint one `ExtensionStoreBinder` bound to the extension's uid.
3. `ExtensionBinder.hold` (signature re-checked at bind).
4. `point.begin(iface, store)` under `HeldInkPoint.callTimeoutMs` (2 s for both points). Measured:
   **47–57 ms**, first run included — it creates the pad's first blank page.
5. Return the screen `Intent` (or **null** on any failure, reason logged, everything opened so far
   released — the caller shows the core's own dialog). Returning the Intent rather than a boolean
   plus an accessor keeps it one call: the caller launches exactly what it got.

`finish()` — settle any call a timeout orphaned (`HeldBinding.settle(settleTimeoutMs)`) so the store
is never revoked under a placement still writing, then `end()` best-effort under the call timeout,
then `close()` + `revoke()` in `finally`. Idempotent, and the caller runs it from its **result
callback and** from `onDestroy` while still open, because a bind must not outlive the screen that
opened it even when the result never comes.

`HeldInkClient.send` / `.drainOutgoing` (J5, the settle rule added Y4) are the two transfers' host
half. Rules built into them:

- The **last** `receiveInk` chunk carries the whole placement — a read, decode, re-encode and write
  of up to 4 MiB on an e-ink CPU — so it takes `PLACE_TIMEOUT_MS` (10 s), not the 2 s of every other
  call. A Binder call cannot be cancelled: a budget that is too short reports failure for ink that
  then lands anyway — which is why, since Y4, a timeout on that last chunk is **settled, not
  believed**: `send` waits `placeTimeoutMs` again for the orphaned call, and a late `Settled.OK` is
  treated as the success it is, ink already on the page.
- `drainOutgoing` probes **one chunk past** `TRANSFER_MAX_CHUNKS`, so a non-empty chunk there tells
  the caller something was left behind rather than letting a silent truncation read as success.

`TransferCaps` is the host's pure, JVM-tested half. Outward: `withinLimits` is checked **before any
bind**, `toWireStrokes` is the one reduction site from a g-paper `Stroke` (id and time never leave;
point-less strokes are skipped), `chunk` delegates to `InkChunks`. Inward is untrusted: `Drain`
accumulates `takeOutgoing` chunks under the summed caps and says whether it truncated, `sanitize`
forces what SN can draw (unknown style → PEN, width clamped to 0.5–50 px, colour forced opaque
black), and `toStrokes` mints **fresh ids** here. No id ever crosses in either direction.

### Extension side

`ScratchPadService` holds what the host lent for this showing in `ScratchSession` — the store
binder, the inbound chunks, the outbound chunks and the one-shot "open selected" record. `end()`
clears all of it. `begin` reads the page list on the Binder thread; the first run creates one blank
page. **Since arc 23 / Y4 the stub bodies are `:ext-ink`'s `InkTransferSession`'s, not
`ScratchPadService`'s own**: `receiveInk` calls `ScratchSession.receiveChunk`, which accumulates
chunks under **one monitor** (`begin` and `end` take the same one, so a host that restarts
mid-transfer can never interleave with a placement), re-checks the running totals against the
transfer caps as it goes — the untrusted-input half of the host's own before-any-bind check — refuses
a placement that changes mid-transfer, and on `last` mints fresh ids and calls back into
`ScratchStore.receive` on the Binder thread; `takeOutgoing` is `ScratchSession.outgoing`. What
`ScratchPadService` still supplies itself is the placement int's own validity check and the log
wording. An index past the end is an **empty bundle, not an error**, because "done" is exactly what
the host is asking about and it probes one chunk past the budget on purpose.

Only exceptions that survive Binder marshalling are ever thrown from a stub method — anything else
kills the transaction silently and the caller reads an empty reply as success. Through J3 the two
transfer methods threw `UnsupportedOperationException` for that reason (`EX_UNSUPPORTED_OPERATION`
crosses intact); J5 replaced them with the real implementations, whose refusals are
`IllegalArgumentException` (over the transfer caps) and `IllegalStateException` (the store
unavailable, or — since arc 22 / X2 — a multi-batch failure the store's own compensation could not
fully undo).

**Since arc 22 / X2 the pad's storage is rows, not a key layout** — `ScratchSchema.V1` declares
three tables in the host's extension store:

```sql
page   (id, position, width, height, createdAt, updatedAt)          -- 0 × 0 = size not learned yet
stroke (id, pageId → page.id ON DELETE CASCADE, "order", color, width, style, blob)
state  (key, value)                                                 -- 'current' → the current page id
```

`stroke.blob` is `StrokeCodec` format B (x/y/pressure/tilt) — the `.soil`'s own stroke encoding,
unchanged; `stroke."order"` is the writing order within its page, what makes the page's ink stable
across an undo/redo cycle. `ScratchSql` is every statement as a pure builder, and **two write ops,
both idempotent**, because a batch that failed part-way is retried by whatever caller owns it and
the retry has to converge: a stroke row is `INSERT OR REPLACE` (a stroke has no children, so
REPLACE is safe) or `DELETE … WHERE id = ?` (a row that is not there is not an error); a page row
is `INSERT OR IGNORE` then `UPDATE`d — **never `INSERT OR REPLACE INTO page`**, because REPLACE
deletes the conflicting row first and, with foreign keys ON, that delete **cascades** — it would
take the page's strokes with it.

**Since arc 23 / Y1 the stroke-level half of this lives in `:ext-ink`'s `InkDocument`**, shared with
the calendar so the two never drift: a `TreeMap<order, Stroke>` plus an **op log** (`Put`/`Drop`
per stroke id), replacing arc 11's whole-page re-encode on every save — a flush snapshots and
clears the log, then writes it as one or more `exec` batches (`:ext-ink`'s `StoreBatches`, moved
from the pad's own `ScratchBatches` at Y1, split at ≤ 4 MiB / ≤ 10 000 statements over
`StoreCodec`'s own arithmetic — one batch is one transaction and therefore atomic; past it,
`receive` **compensates** a multi-batch failure — a cascading `DELETE FROM page` for a new page,
one `dropStroke` per minted id for the current page, never an `IN (…)` list — before throwing
`StoreUnavailable`). `ScratchDocument` itself now keeps only the pad's own layer over `InkDocument`
— the page list and its structural edits (insert/delete/renumber) and the page's size. Orders are a
**high-water mark**, never the map's last key, so erasing the tail stroke can never hand its order
to the next stroke drawn.

Reads are **planned, never refused**: `readPage` reads the size row, then `SELECT "order",
LENGTH(blob)` (small), then packs consecutive strokes into `BETWEEN` ranges under the 4 MiB chunk
budget (`:ext-ink`'s `StrokeReadPlan`, moved from the pad's own `ScratchReadPlan` at Y1) — a page
of any size comes back without ever meeting `STORE_RESULT_LARGE`. `:ext-ink`'s `StrokeRows.decode`
(moved from the pad's own `StrokeRows`) drops a bad row rather than losing the page (counted,
logged) — arc 11's "unreadable page" state, and the blob it protected, are both gone. **The 4 MiB
page ceiling, `PageFullException`, `SCRATCH_PAGE_FULL` and the "page full" dialog are deleted**: a
scratch page is unbounded, the same as a notebook page, structurally (no cap anywhere in the write
path, keyset reads throughout) — proven by the JVM split/plan tests and not, as of the X2 walk, by
an on-device page stressed past 4 MiB (the user's checklist skipped that one item; see
[`docs/scratchpad.md`](scratchpad.md)).

### What J3 proved on the Nomad

Discovery finds the extension and the debug row appears; `pm disable-user` makes it vanish and
`pm enable` brings it back, because discovery re-runs on every open. The whole sequence runs clean:
`hold` → `begin: pages=1` → screen `opened` → Back → `end` → `finish: end ok` → `unbind`, with
`dumpsys activity services` showing nothing left bound and the crash buffer empty. The store's
`.db` is created encrypted (`head -c 16 | xxd` — not `SQLite format 3`), and a shell `am start` of
the screen is refused with `refused caller (none)`.

One question this phase existed to answer: does a 4 MiB `putLarge` / `getLarge` round trip survive a
**real** Binder — ashmem crossing two processes — inside `begin`'s 2 s budget? A once-per-process
debug probe answered **916 ms on the Nomad** (Paper measured 917 ms), and was removed in the same
phase, as Paper removed its own: left in, it would sit inside the first pad open of every session
and muddy J4's timings.

### An extension-owned screen — the tier-2 recipe (arc 11 / J4)

A **tier-2** point does not answer a question; it takes the screen. The recipe, in the order it has
to happen — and, **since arc 23 / Y4, a base class the paper-hosting points share**
(`:ext-ink`'s `InkScreenActivity`) rather than a shape two files each followed by hand:

1. **The Activity is exported, with a custom action and no launcher filter.** `<category DEFAULT>`
   is required or implicit resolution never matches it.
2. **`HostCallerCheck.enforceActivity` is the first statement in `onCreate`**, before anything is
   inflated: `callingPackage` must be the host **and** share this extension's signature. A plain
   `startActivity` — `am start` from a shell included — leaves `callingPackage` null and is refused.
   Which means the host **must** launch it with an `ActivityResultLauncher`; that is what sets it.
3. **The core launches it only after `begin(store)` has succeeded** on the held bind, and only
   through the Intent the client returned. Two booleans ride it and nothing else.
4. **The result comes back on the bind that is still held** — drain first, `finish()` after.
5. **The caller's `onDestroy` calls `finish()` too**, as the backstop for a caller destroyed while
   the screen is up: a bind must not outlive the screen that opened it even when the result never
   comes.

**Two paper surfaces, one EPD pipeline.** The screen-owning point's real cost is not the Activity,
it is the firmware ink session. The caller releases (`releaseForHandoff()`) immediately before the
launch; the extension reclaims in `onResume`; **every** exit on the extension side releases before
`finish()`. The ordering that matters is at the end: the caller's reclaim lands *before* the
departing window's close, because g-paper's ownership guards are process-local statics and a close
landing after the reclaim tears the caller's live session down. This worked on the Nomad first try,
in both directions and on both exits, with **no g-paper change** — the pin stayed 0.1.6. An
extension screen that draws must follow the same order, and a failure in it is fixed in g-paper.

**The extension registers the engine itself** (`RattaEngine.register()` in its own `Application`) —
it is a different process, so the host's registration means nothing to it.

### The transfers as a seam (arc 11 / J5)

Both directions are copies that cross **only through the held service** — never the Intent, never a
file — carry **no ids**, and keep coordinates 1:1. The feature-level walk-through is in
[`docs/scratchpad.md`](scratchpad.md); what belongs here is which side is allowed to trust what.
**Since arc 23 / Y4** the host-side calls this table names — `open`/`send`/`drainOutgoing`/`finish`
— are `HeldInkClient`'s (via the thin `ScratchPadClient`), the caller that checks `withinLimits`
before any bind is `NotebookActivity.sendSelectionToExtension`, and what feeds it a selection to
check is `TransferSelection.sendable` (the ink-only, writing-order rule, Y4) — none of which changes
what crosses or who sanitizes it, only which class the reader finds it in:

| | Notebook → pad (`receiveInk`) | Pad → notebook (`takeOutgoing`) |
|---|---|---|
| Capped **before any bind** | `TransferCaps.withinLimits` in the host | — (the reply is bounded by the drain) |
| Capped **on receipt** | the service re-checks the running totals across chunks | `TransferCaps.Drain` — summed caps, chunk budget, one probe past it |
| Validated at unmarshal | `WireStroke` / `InkBundle` `requireValid` (a malformed stroke rejects the whole bundle) | the same, host-side |
| Sanitized | `:ext-ink`'s `InkWire.toStrokes` (moved from `ScratchInk` at Y1) — unknown style → PEN, width clamped | `TransferCaps.sanitize` — the same, **plus colour forced opaque black** |
| Ids | minted by the extension | minted by the host |
| Failure | a multi-batch write failure is **compensated** (the new page or the minted strokes are undone) before `StoreUnavailable`, never a partial placement left standing — the 4 MiB page ceiling and `SCRATCH_PAGE_FULL` this row named through X1 are deleted at X2 | a cut drain is reported, never silently truncated |

The two mappings are deliberate **twins** (`TransferCaps` host-side, `:ext-ink`'s `InkWire`
extension-side, shared with the calendar since arc 23 / Y1) rather than one shared class:
`:sn-screen` never sees `:extension-api`, and keeping the twin is what keeps that seam honest.

---

## The exporter point (arc 15)

> The exporter **as a feature** — the screen, the flow, the keying table, the failure table — is
> [`docs/export.md`](export.md). What follows is the **seam**: the point, the descriptor contract,
> and what each side is allowed to know.

`ACTION_NOTEBOOK_EXPORTER` is SN's **third** capability point, and its first **plural, call-shaped**
one: unlike the recognizer and the pad, any number of trusted exporters may register at once — since
arc 18 two actually do (`NSE · Soil Export` and `NSE · PDF Export`), which is when the Export
screen's chooser first showed two radios — the
Export screen lists whatever is installed (`ExtensionRegistry.exporters` returns a `List<ProviderRef>`,
ordered by `(label, package)` — every candidate is kept, none dropped as "additional"), and each
export is one `ExtensionBinder.call` — there is no held bind, because the operation is a single
`describe()` or a single `export()`, never a showing. `ExporterClient` is the whole host-side client:
same bind-per-operation shape as `RecognizerClient`, `DESCRIBE_TIMEOUT_MS` (3 s) for the fast call,
`EXPORT_TIMEOUT_MS` (120 s, measured — see below) for the slow one.

### The declarative descriptor seam

An exporter never draws its own UI. `describe()` returns an `ExporterInfo` — format label, file
extension, MIME, and a **bounded** `OptionDescriptor` list (`ExporterContract.MAX_OPTIONS` = 8 per
exporter, `MAX_CHOICES` = 8 per single-choice option, `MAX_ID_CHARS` = 32, `MAX_LABEL_CHARS` = 80) —
and the host renders every option with its own e-ink widgets (`ExportPanel`: caption, fixed value,
radio choice, tick toggle). Both parcelables' constructors `require` every cap, so **unmarshal is
the validation**, same as every other point in this file: a descriptor over the caps, or one
declaring an option kind this build cannot draw (`ExportOptions.isRenderable` — the one unrenderable
kind is a free-standing `KIND_PASSPHRASE`), or declaring the reserved keying option with a choice id
outside the trio the host can execute (keying is host-*executed*, not just host-drawn — an unknown
value would otherwise surface only at export time, explained as the wrong failure), **drops that
exporter with a log line, never a crash** (`ExportActivity.loadCandidates`). Inward is untrusted the same way it is for `describe()`'s
siblings on the other two points.

### The reserved keying option — declared like any other, executed by the host

`ExporterContract.OPTION_KEYING` is a normal single-choice `OptionDescriptor` by shape — an exporter
declares it exactly like any other option, with its own choice ids and labels — but the host
recognizes the id and treats it specially: the transform it names (`ExportKeying`, beside
`SoilCrypto`) runs **host-side**, before the fds are ever opened, and **a passphrase-kind option's
value never enters the spec at all**. Only the chosen *choice id* crosses in `ExportSpec.values`;
the typed secret behind `KEYING_REKEY` is collected in host-owned, `saveEnabled="false"` masked
fields and consumed by `ExportKeying` in the same process — it is never marshalled, never logged,
never in an Intent. This is the same rule the contract states for `KIND_PASSPHRASE` generally: a
passphrase-kind option exists to *ask the host* for a host-executed step, never to receive the
secret, and the reserved keying option is the one such step this arc implements.

### The two-fd seam

`INotebookExporter.export(source, destination, spec)` is the whole data path: a read fd (what the
descriptor's source kind asked for — the host-prepared, already-keyed artifact, or since arc 18 a
host-rendered page bundle) and a write fd (the SAF destination the host opened), plus
the bounded `ExportSpec`. **The extension writes only through the granted write fd** — the
writes-nothing-to-disk rule every extension in this app keeps, applied to the one point that is
fundamentally about writing a file. `SoilExporterService.export()` is the reference implementation:
read the whole source fd in 64 KiB chunks, write each to the destination fd, `fsync` before closing,
return the byte count the host will verify against what it actually streamed.

### Call-shaped, not held

No `begin`/`end` bracket, no `HeldBinding` — `ExporterClient.describe()` and `.export()` are each
their own `ExtensionBinder.call`: explicit `ComponentName`, signature re-checked immediately before
`bindService`, the call run on IO under its own timeout, unbind in `finally` unconditionally. An
export that fails partway through leaves nothing bound; a second export is a second bind.

### fd lifecycle, and the E1 trap

The fd handshake is the ashmem handshake's shape in `ParcelFileDescriptor` clothes, same as the
store's `LargeValue`: the client hands over both descriptors and closes them in `finally` once the
transaction is marshalled; the extension closes its own dups in its own `finally`. **The caller
check must run *inside* that `try`** — `SoilExporterService.export()` calls `enforce()` as the first
statement inside the try whose `finally` closes both descriptors, not before it. Outside it, a
refused caller would still have handed the extension two live dups with nothing left to close them
— the E1 trap, found and fixed before the walk.

Only three exception shapes may leave a stub method — `SecurityException` (the caller check),
`IllegalArgumentException` (a spec this exporter cannot serve), `IllegalStateException` (a delivery
failure) — the same marshalable set as every other point. Anything else kills the transaction
silently and the host would read the empty reply as success; `SoilExporterService` funnels every
other `Throwable`, `IOException` and `OutOfMemoryError` included, down to an `IllegalStateException`
whose message names only the exception's class, never a path.

### Timeouts, measured

A Binder call cannot be cancelled, so `EXPORT_TIMEOUT_MS` could not be guessed the way a first pass
might guess it (the J5 `PLACE_TIMEOUT_MS` lesson, repeated): it is 120 s, sized against a 100 MB
flash copy measured on the Nomad at ~0.45 s (~525 MB/s `dd`, ~230 MB/s `cp`) on 2026-08-27 — two
minutes comfortably covers a 1 GB artifact even through a slow DocumentsProvider at 10 MB/s.
`DESCRIBE_TIMEOUT_MS` stays short (3 s): a descriptor is a small in-memory answer by construction.
Arc 18 re-asked the question rather than assuming the answer transferred — a `SOURCE_PAGES` export
is a transform, not a copy — and the measurement kept the one value: PDF assembly of a 13-page
bundle took 3.5 s on the Nomad at D1 (~270 ms a page) and 2.6 s (~200 ms a page) after D3 moved
the assembly onto pdfbox, so 120 s covers a ~400-page
notebook; the host's render runs *before* the call starts and never counts against it.

### The source-kind tail — the host renders, the extension assembles (arc 18)

An exporter that turns a notebook into a *document* — the PDF exporter — can never receive the
`.soil` itself: no key crosses an extension seam, and an encrypted artifact without its key is
noise. So `ExporterInfo` grew a **compatible parcel tail**, `sourceKind`, declaring what the read
fd should carry: `SOURCE_SOIL` (the prepared artifact, arc 15's original) or `SOURCE_PAGES` (a
host-rendered page bundle). The tail is read by `dataAvail()` — an old-shape descriptor simply runs
out and means `SOURCE_SOIL`, so every pre-arc-18 exporter kept its meaning on real wire (proven
both directions at the D1 walk); an unknown kind fails `ExporterInfo`'s unmarshal and drops that
exporter like any other bad descriptor. The same tail pattern grew `ExportSpec` its own trailing
field (the export secret, below) — both hold only because the descriptor is the reply's trailing
payload and the spec is `export()`'s trailing argument, which both wire docs now state as a rule.

For `SOURCE_PAGES` the host does the reading with the one process that can: `ExportRender` re-runs
`ExportArtifact.prepare`'s guard order one for one (`SoilOpenFiles` held → IN_USE, missing key,
missing or unopenable file), opens the notebook **read-only** through the one `SoilDatabase.open`
door — nothing is stamped, not even `exportedAt`, because a PDF is not the notebook — and bakes
every page full-fidelity at its **own** pixel size (template under headings, links' wrapped
children, then ink — `PagePreview.drawContent`, the one layering function) into a `PageBundle` in
`cacheDir/export/`, which the screen's one `finally` wipes along with the soil artifact. The
container (`extension-api`, pure `java.io`) is `"NSPB"` magic · version · page count · per-page
width/height/length/WEBP bytes, with a streaming Writer/Reader that hold **one page at a time** —
the OOM rule on a 3 GB device is a rule, not an optimisation, and both sides keep it — and caps
refused before allocation on both sides (`MAX_PAGES` 4096, `MAX_DIMENSION_PX` 32768,
`MAX_PAGE_BYTES` 32 MiB). The extension re-checks each decode against the bundle's declaration: a
page that will not decode, or decodes at an undeclared size, is a **delivery failure, never a page
to skip** — a PDF quietly short of a page would be reported as a success.

**Verification is per source kind** (`ExportVerification`, pure). The `bytesWritten ==
streamBytes` equality is a *verbatim-streaming contract* — it holds for `SOURCE_SOIL` and nothing
else (E3's refutation of a "transforming exporter" finding was scoped to soil's verbatim stream;
the PDF exporter is that exporter arrived for real). A `SOURCE_PAGES` export is corroborated
against the destination's own answers only, zero bytes is never a document, `SHORT` (failed — the
flow may delete wreckage under its usual rules) stays distinct from `UNCONFIRMED` (stream complete,
provider metadata disagrees — a check-the-file dialog, never a delete), and an unknown kind is
`SHORT`: verification never defaults to trust. "What a notebook is" stays a host question for good
— a future page kind renders differently host-side and no extension changes.

### The export secret — the one deliberate secret that crosses (arc 18 / D2)

Arc 18's two reserved toggle ids follow `OPTION_KEYING`'s pattern — declared and labeled by the
exporter, recognized by id, because each names something the *host* must do about it.
`OPTION_PAGE_TEMPLATE` is **host-executed**: its value threads into `ExportRender` (off = white
ground, the template decode skipped, never decoded-and-discarded), and the value still crosses in
the spec map so the extension knows what was asked. `OPTION_PROTECT` is **host-collected,
extension-executed**: arming it reveals the host's one dual masked block (re-worded "Password"),
and the typed secret crosses on `ExportSpec.exportSecret` — the **one deliberate secret that ever
crosses an extension seam**. Its scope is the whole justification: it is user-typed for exactly
this export, it protects the *output* file, and it opens no Notesprout data — never the global
passphrase, never derived from it, never the device key. `KIND_PASSPHRASE` keeps its never-crosses
meaning untouched, and the secret is never in the spec's value map.

Both sides keep the typed-passphrase lifecycle rules verbatim. Host: `typedExportSecret` mirrors
`typedPassphrase` to the letter — XML-static `saveEnabled="false"` fields, held from the Export tap
to the end of the flow, cleared at the picker's cancel and in the flow's `finally`, never in
instance state, an Intent, or a log line; over `MAX_EXPORT_SECRET_CHARS` (128) is refused at the
tap, where the dialog can still explain; a screen rebuilt behind the picker (the fields gone with
it) refuses with the honest password-lost body rather than silently exporting unprotected.
`ExportOptions.isRenderable` drops a descriptor declaring both a rekey choice and the protect
toggle — one block, one tenant, one secret lifecycle. Extension: `PdfExportSpec` refuses an
inconsistent delivery in **both** directions (protect armed with no secret would write an
unprotected file the user believes is locked; a secret nothing asked for would lock a file the user
never asked to lock), no refusal message ever names, quotes, or measures the secret; `PdfAssembly`
holds it only for the pdfbox call and drops its reference in `finally` (a `String` cannot be
zeroed — releasing the reference is the whole of what that side can do, and it is done whichever
way the assembly ended).

---

## The importer point (arc 16)

> Import **as a feature** — the button, the pipeline, the keying table, the three questions, the
> remap, the failure table — is [`docs/import.md`](import.md). What follows is the **seam**: the
> point, and what each side is allowed to know.

`ACTION_NOTEBOOK_IMPORTER` is SN's **fourth** capability point and the exporter's mirror in every
structural respect: plural (`ExtensionRegistry.importers()` returns every trusted candidate,
ordered by `(label, package)`), call-shaped (each `describe()` or `importDocument()` is its own
`ExtensionBinder.call` — no held bind, no store), and served by the same `:ext-soil` APK
(`SoilImporterService` beside `SoilExporterService` — one package, two directions, one label).
`ImporterClient` mirrors `ExporterClient`; `ImporterContract` shares the exporter's caps and
timeouts **by reference** (`DESCRIBE_TIMEOUT_MS` = 3 s, `IMPORT_TIMEOUT_MS` = `EXPORT_TIMEOUT_MS`
= 120 s — the stream is the same copy in the other direction, so the arc-15 Nomad measurement
transfers directly).

### The descriptor, reversed

`describe()` returns an `ImporterInfo` — format label plus the bounded **file-extension** and
**MIME** lists (`MAX_FILE_EXTENSIONS` 8, `MAX_MIME_TYPES` 8, extensions `[a-z0-9]{1..12}`, MIME
shape-checked). Constructor `require`s make unmarshal the validation, the family rule; a
descriptor over the caps drops that importer with a log line, never a crash
(`ImportFlow.loadCandidates`). The two lists do two different jobs, and the split is deliberate:
the **MIME union seeds the `OPEN_DOCUMENT` filter** (plus `*/*` — og's rule: providers mislabel a
`.soil` routinely, and a filter that hid the file the user came for would be a dead end), while
the **extensions are what actually choose an importer** for the picked document
(`ImporterMatch`, on the display name's extension — a MIME match would drop the one importer that
can read the file). One match is no question; several is a chooser; none is a dialog.

### The AIDL is one delivery call

```
ImporterInfo describe()
ImportResult importDocument(in ParcelFileDescriptor source, in ParcelFileDescriptor destination, in ImportSpec spec)
```

The method is `importDocument` because `import` is a Java keyword — AIDL codegen would not
compile it. `ImportSpec` crosses **now**, empty, because an AIDL method cannot grow parameters
later: a bounded id → value map (the exporter's caps: ≤ `MAX_OPTIONS` entries, ids by
`OptionDescriptor.requireId`, values ≤ `MAX_SPEC_VALUE_CHARS`) plus the picked document's
**display name** — display only, ≤ `MAX_NAME_CHARS`, its constructor refusing `/` and NUL so it
can never carry a path. `ImportResult` is one non-negative `bytesWritten`.

The extension's whole job is `streamCopy`: read the source fd to the end, write every byte to the
destination fd, `fsync`, return the count — the exporter's stream in the other direction, same
64 KiB buffer. It verifies its own copy against the source's length where the fd will stat (a
proxy fd from a cloud provider answers −1, and the stream is then accepted on its own terms — the
host's corroboration takes over). **It does not probe the bytes, and must not**: recognising a
`.soil` is the host's job, after the copy, behind its own crypto. The caller check runs inside
the `try` whose `finally` closes both descriptors (the E1 trap, kept), and only the three
marshalable exception shapes leave.

### Where the trust boundary actually sits

The delivered file is **still untrusted bytes** after a successful, verified copy — the copy
proves delivery, not content. Everything that decides what the bytes *are* runs host-side, after
the seam: the probe (`SoilCrypto.probe`), the unlock (device key tried first; a foreign
passphrase prompted under the `"IMPORT"` `AttemptLimiter` bucket), the unconditional re-key to
the device's global key (`ImportKeying` — export-and-key, never `PRAGMA rekey`; `user_version`
copied by hand and re-verified; `notebook_meta` restamped `GLOBAL`; nothing accepted without
probing as encrypted + opening + `integrity_check` = ok), the manifest's id validation
(`SafeImportId` — UUID alphabet only before any id becomes a `soilFile()` path component or index
key), the three questions, the in-file remap (`NotebookRemap`), the staged-rename Garden write
and the index-last commit. The extension never learns any of it happened.

---

## The document-editor point (arc 19)

`ACTION_DOCUMENT_EDITOR` + `_SCREEN` — the fifth point, the second screen-owning one, served by
`:ext-document` (**NSE · Document**). The extension owns the full-screen Markdown editor
Activity; **the host owns every `.soil` read and write** (og's invariant 3, now enforced by a
process boundary). The feature is [`document.md`](document.md); this section is the seam.

### The held bind, and the seam's new piece

The shape is the scratch pad's — SN's second **held** bind, because the operation is the showing:
`DocumentEditorClient.open` pre-opens the store on IO, mints one uid-bound `ExtensionStoreBinder`
**and** one uid-bound `DocumentHostBinder`, holds the bind (signature re-checked), calls
`begin(store, host)` and hands back the screen Intent; `finish` calls `end()` best-effort and, in
`finally`, unbinds and revokes both binders on every path — result, cancel, caller death, failed
`begin`.

What is new is the second argument. **`IDocumentHost` is the first host-side stub on any SN
extension seam**: every other binder in the app is one the host *calls*; this one the extension
calls, so it carries the same trust discipline an extension's own service does, mirrored. The
host mints one per showing, bound to the extension's uid; `gate()` is the first statement of
every method (wrong uid, or anything after the revoke, is a `SecurityException` that says nothing
about what is here), and the revoke — in the same `finally` as the unbind — also clears the
session, so the showing's read window and any half-received save never outlive the bind. The
binder is deliberately **thin**: everything worth getting right — the read window, the ordered
save accumulator, the target-key guard, the caps, the parked watermark — lives in
`DocumentHostSession`, pure and JVM-tested. Hooks into the open notebook
(`DocumentHostBinder.Hooks`) run **blocking on Binder threads**, never Main, and every hook
invocation is funnelled: only `SecurityException` / `IllegalArgumentException` /
`IllegalStateException` (plus `UnsupportedOperationException`, the J3 precedent) cross; any other
`Throwable` becomes `IllegalStateException(className)` — the class name and nothing else, because
a message here could carry a path or a fragment of the user's document.

### Text crosses chunked, in both directions — and nothing rides the Intent

Document text is the only user content that crosses this seam, and it crosses **chunked** by the
shared `TextChunks` rule (`TEXT_CHUNK_CHARS` 100k per Binder call under the ~1 MB transaction
budget; a chunk never splits a surrogate pair; **empty text is one empty chunk**, so "save blank"
rides the same shape as everything else) under `MAX_DOCUMENT_CHARS` (10 M — aligned with the text
importer's 10 MB byte cap, so any file import admits stays editable). The read direction is a
**pull**: every state-answering call (`current`, `requestPage`, `requestScope`, `requestSeed`,
`requestMerge`) parks its text in the host's read window atomically with the `DocumentPageState`
it returns, and `readChunk` serves that window. The write direction is a **push**: `saveChunk`
accumulates in order from 0, the host re-checks the running total on receipt, the last chunk
commits, and a refused chunk resets the whole accumulation. **Every save names its target
`pageKey`** — a host-minted opaque token (≤ 64 chars, no `/`, no NUL; never a path, opens
nothing) that must be the *current* target's key, which is what makes the mode-routing guard
structural: notebook-document text can never land on a page row or vice versa, and a save landing
in a flip gap is refused by key rather than written onto the wrong page.

The screen Intent carries **nothing** — not one extra. (The scratch pad's two booleans were the
last thing to ride an Intent on any SN seam; this point starts with none.) Everything moves
through the two binders, which is what makes the whole of it uid-gated and revocable.
`DocumentEditorActivity` is exported under its own action with `<category DEFAULT>` and no
launcher filter, refuses any caller that is not the host's `startActivityForResult`
(`HostCallerCheck.enforceActivity` first thing in `onCreate`), and the host launches it only
through an `ActivityResultLauncher` with `setPackage` — the tier-2 recipe, verbatim.

Three typed refusals cross as exact `IllegalStateException` messages, `==`-matched (the
`RECOGNIZER_NOT_READY` recipe): `SEED_UNAVAILABLE` (recognition is not there to run),
`NO_DRAFT_PENDING` (a drafted commit with no watermark parked — nothing written; the editor
retries the same text as an ordinary save) and `MERGE_CANCELLED` (the editor's own cancel —
nothing written, target and scope untouched).

### Timeouts, and the flush that rides `end()`

`begin` gets `CALL_TIMEOUT_MS` (2 s — a state read and nothing else). `end()` gets its own
`END_TIMEOUT_MS` (15 s), because it is not a question: it is the editor's last chance to push
unsaved text, and the extension's handler flushes synchronously through the host binder before
answering — the live buffer's snapshot plus anything parked, ordered so a park for the same
target (an older copy by construction) is dropped rather than written over the newer text. On
the other side, `begin` arriving while a showing already exists means **the host restarted**
underneath a live screen: the screen re-confirms the target through `current()` before any text
moves, and a parked save whose key no longer matches is **dropped, deliberately** — those words
are another document's, and writing them there would be corruption.

### The store is small per-device state, never the document

Arc 19 kept all of this under four keys of one key/value store — `size`, `carets` (`CaretMemory`'s
line blob, cap 100), and since M10 `proofread` / `dict` (`UserWords`' line blob) — so the shape it
took was the shape a blob can hold: the caret map and the dictionary were each a whole value, read
and rewritten entirely to change one entry. **Since arc 22 / X4 it is three tables**
(`EditorSchema.V1`):

```sql
prefs (key, value)                   -- 'size', 'proofread' (absent = on)
word  (word, addedAt)                -- the user dictionary
caret (pageKey, offset, updatedAt)   -- where the writer left off, per page
```

Each is a different *identity*, which is what removes the read-modify-write: a **pref** is a key
with one value, and its table is the ONE the host itself reads (`prefs` — pinned in
`DocumentContract` rather than spelled twice, since `DocumentPdfRender` reads it too); a **word**
is its own identity — the word *is* the primary key, so vouching for one is `INSERT OR IGNORE` and
removing one is a plain `DELETE`, with no set to decode in between (`insertWord` keeps `addedAt`
on a re-add, since that column is the manage list's order — a re-add must not move it); a
**caret** is per page, and `updatedAt` is what the LRU orders by — the eviction arc 19 did in
Kotlin over a `LinkedHashMap` is now one bound `DELETE` (`CARET_LIMIT` 100) in the **same batch**
as the write that caused it (`rememberCaret` = one two-statement `exec`). `INSERT OR REPLACE` is
safe on all three — unlike the scratch pad's `page` table, none of the three has children (no
`REFERENCES`, no cascade), so a replaced row takes nothing with it.

`EditorStore` is the one place SQL runs, in the `TagStore`/`ScratchStore` shape: blocking, applies
the schema on **every** public call (the binder is fetched per call, never cached, because a
restarted host lends a new one), and lets every exception through; `EditorPrefs` is the thin
facade every caller already used — same names and signatures, every exception answering the
default. **`CaretMemory` and `UserWords` are deleted** along with their line codecs; the
normalization rule that mattered (`SpellEngine.normalizeWord`) already existed and every caller
still applies it. **A draft never lives in the store** — autosave pushes text to the host through
the callback binder; the store holds comfort, not content, and every store failure still degrades
silently to a default.

### The sibling registrations

The same APK registers on the two generic points, one service each — no new point either way:

- **`DocumentExporterService`** (M9) declares `SOURCE_DOCUMENT` (API version 3 on its service
  meta-data): the **host assembles the final UTF-8 text bytes** — the format choice executed
  host-side, a `.txt` stripped through `:markdown` — and the extension is a verbatim streamer,
  held to the same `bytesWritten == streamBytes` equality as `NSE · Soil Export`. Detail:
  [`export.md`](export.md).
- **`TextImporterService`** (M8) declares `resultKind = RESULT_TEXT_DOCUMENT` — `ImporterInfo`'s
  compatible tail (absent = `RESULT_NOTEBOOK`), API version 3 likewise: the extension streams the
  picked file verbatim exactly like the soil importer, and the host forks **after** delivery into
  strict UTF-8 validation + text-document create instead of the `.soil` probe. Detail:
  [`import.md`](import.md).

---

## The tag-manager point (arc 21, rebuilt on rows arc 22 / X3)

> Tags **as a feature** — the identity and lifecycle rules, the tag screen's three modes, the four
> doors (library sheet, notebook bar, lasso, search), the failure table — is
> [`docs/tags.md`](tags.md). What follows is the **seam**.

`ACTION_TAG_MANAGER` + `_SCREEN` — the sixth point, the third screen-owning one, served by
`:ext-tags` (**NSE · Tags**). The extension owns the tag screen and the tag index — **`tag` /
`assignment` rows in its own extension store since arc 22 / X3**, not one key holding the whole
index as arc 21 built it; the host owns every entry point (the library's long-press row, the
notebook's three tag doors, the lasso's silent and recognized flows), the recognizer call, and the
library's search merge.

### One interface, two call shapes

`ITagManager` is the first seam in this app carrying two call patterns on one interface, and the
store argument is what tells them apart:

```
void begin(IExtensionStore store);
void configureShowing(in TagShowing showing);
void end();
List<TagRecord> tags(IExtensionStore store, int offset);
List<AssignmentRecord> assignmentsOf(IExtensionStore store, in List<String> tagIds, int offset);
String assign(IExtensionStore store, String text, String notebookId, String pageId);
```

**Arc 22 / X3 replaced `snapshot`** (one `LargeValue` of the whole index) with the two paged reads
above: `tags` answers a page of `TagRecord`s (`ExtensionContract.TAGS_PAGE` = 500, browse order
`identityKey, display`, a page shorter than 500 ending the loop), `assignmentsOf` answers a page of
`AssignmentRecord`s (`ASSIGNMENTS_PAGE` = 1 000) for at most `ASSIGNMENT_QUERY_TAGS` (500) tag ids
at a time. Neither reply rides ashmem — a `List<Parcelable>` this size is an ordinary Binder
parcel, which is exactly why both are paged rather than sent whole (`TagPages.collect`, in
`:extension-api` so both sides run the identical loop and can never disagree about where a listing
ends).

A **showing** is the scratch pad's bracket, verbatim: `ExtensionBinder.hold` pre-opens the store on
IO (the pre-open rule), mints one uid-bound `ExtensionStoreBinder`, holds the bind, `begin(store)`
lends the store for the screen's whole life, `configureShowing(showing)` says what this showing is
about, the screen launches through an `ActivityResultLauncher`, and `end()` — best-effort under
`TagClient.CALL_TIMEOUT_MS` (2 s) — drops both the store and the parked showing in one `finally`
alongside the unbind and the revoke, on every path: result, cancel, caller `onDestroy`. `tags`,
`assignmentsOf` and `assign` are bind-per-call, the recognizer's shape: `ExtensionBinder.call`, and
the store rides that one call rather than being lent ahead of it — nothing is held once the call
returns.

The two shapes exist on one interface because they answer two different questions. "Show the user
this" is an operation with a lifetime — the store has to still be there when the screen finally
lets go of it, whatever the user does with the screen in between — while "read the whole index" or
"attach this tag" is not: the operation *is* the call. The store's own rule (lent once for a
showing, lent per call otherwise, stated at `IExtensionStore`'s own introduction) is what keeps a
single interface honest about the difference rather than forcing every caller through a bracket it
does not need. Every other seam in this app only ever needed one shape — the recognizer only
calls, the pad and the document editor only hold — and the tag point is the first to need both at
once, because the same tag screen the wizard specified had to sit beside a silent lasso flow that
shows nothing at all.

### The first tier-2 screen with no paper

`:ext-tags` has no Application class and no g-paper call anywhere in its own code — `TagsActivity`
never touches a `PaperView` and never registers an engine, because there is nothing on this screen
to draw with one. That makes it the first screen-owning point with **no EPD handoff anywhere**: arc
11 / J4's ordering rule (the caller releases before the launch, the extension reclaims in
`onResume`, every exit on the extension side releases before `finish()`) exists because two paper
surfaces were trading one firmware ink session, and a screen with no paper has nothing to trade.
Arc 19 / M3 already measured the answer for exactly this case — **stop-behind is enough behind a
non-drawing child screen, cross-process included** — and `releaseForHandoff` is deliberately
**absent** from every call site in `TagClient` and `TagManagerEntry`. Adding one would be solving a
problem this screen does not have.

### Trust, and nothing on the Intent

The tier-2 recipe runs unchanged: `TagsActivity` is exported under `ACTION_TAG_MANAGER_SCREEN`
with `<category DEFAULT>` and no launcher filter, `HostCallerCheck.enforceActivity` is the first
statement in `onCreate` — before anything is inflated — and the host launches it only through an
`ActivityResultLauncher`, only after `begin`/`configureShowing` have both already succeeded.
`TagManagerService`'s stub calls `HostCallerCheck.enforce` first in every method; only the three
marshalable exception shapes ever leave one.

Tag text and target labels are the user's own words. They cross on the bind, as fields of
`TagShowing`, handed over by `configureShowing` — **never** on the screen's Intent, which carries
the action and the package and nothing else (`TagShowing`'s own KDoc states the reason: an Intent
extra is readable in a `dumpsys` and can survive in the recent-tasks description, the arc-19 rule
applied again). Every log line on both sides of the seam — `TagManagerService`, `TagClient`,
`TagManagerEntry`, `TagsActivity` — carries counts, lengths and durations, and never a tag or a
label; `configureShowing`'s own debug line names the mode, the target kind and the page count, on
purpose, and nothing else.

### The two-query search merge

Arc 21 / W4's search merge read one `snapshot` — a `LargeValue` of the whole index, decoded whole
off Main. **Arc 22 / X3 replaced it with two paged reads**, because there is no longer a blob to
decode: `TagClient.search(ctx, ref) { tags -> ids }` does one pre-open and ONE bind — inside it,
`tags(store, offset)` is paged through `TagPages.collect` for the host's own `FuzzyRank` to run
over (small: `MAX_TAGS` records at ~250 parcel bytes apiece, still comfortably under a Binder
transaction, which is *why* `TAGS_PAGE` exists rather than sending it in one call), and the caller's
lambda hands back the matched tag ids; then `assignmentsOf(store, matchedIds, offset)` fetches only
the rows the ranking needs, chunked into groups of `ASSIGNMENT_QUERY_TAGS` (500) and paged again.
`SearchAssembly.rank(folders, notebooks, query, TagMatches?, assignments)` groups exactly as
before (own-tag = `pageId == ""`); an **empty** tag-match selection asks `assignmentsOf` nothing at
all. `SEARCH_TIMEOUT_MS` is **10 s** — a first cut, generous on purpose: the Nomad measured
**52–78 ms** end to end on a 2-tag index (`tags` 15–19 ms, `assignmentsOf` 11–18 ms), but the worst
case — ten tag pages and fifty assignment pages — was never built as test data, so the budget stays
wide rather than tuned to a case nobody has actually run.

### The transaction is the lock

Arc 21's index was a **single store value**, so two writers — the screen's own edits on IO and the
service's call-shaped `assign` on a Binder thread (the lasso's silent heading→tag) — needed
`TagWrites`, a process-local monitor around a read-modify-write of the whole blob: read fresh,
apply, write, hand the new index back, or one writer's edit silently erases the other's. **Arc 22 /
X3 deletes `TagWrites` outright**: there is no blob and no read-modify-write left to serialize.
`assign` is now two small reads and **one two-statement transaction** (`TagStore.assign` /
`TagSql.insertTag` + `insertAssignment`): `selectTagByIdentity` answers "does this tag exist, and
is it already on this target" in one read; if it is, nothing is written and the stored display
comes back unchanged. Otherwise the batch is `insertTag` (`INSERT OR IGNORE … SELECT ?,?,?,? WHERE
(SELECT COUNT(*) FROM tag) < ? AND (SELECT COUNT(*) FROM assignment) < ?` — gated on **both** caps,
so a tag whose attachment the assignment cap is about to refuse is never created as an orphan) only
when the identity was absent, then `insertAssignment`, which **resolves the tag id by identity
inside the statement** (`SELECT id, ?, ?, ? FROM tag WHERE identityKey = ? AND (SELECT COUNT(*)
FROM assignment) < ?`) rather than trusting an id read a moment earlier — so a concurrent creator
of the same tag can never leave this call pointing at a row that was never inserted. A post-write
re-read turns `INSERT OR IGNORE`'s silence back into a typed `TAG_INDEX_FULL` refusal (nothing was
written either way) and answers the **stored** display even when a concurrent writer won the
create with different casing. **The transaction is the lock** — correct across both writers in one
process, and across two host processes, which a monitor never could be.

### The caps — policy now, not size arithmetic

Three caps, unchanged from the arc-21 wizard and each still pinned by test: `MAX_TAG_CHARS` 64,
`MAX_TAGS` 5,000, `MAX_TAG_ASSIGNMENTS` 50,000. What changed at X3 is what enforcing them means:
the index is `tag` / `assignment` **rows** now, so a cap is a `COUNT(*)` check bound *inside* the
insert (`insertTag` / `insertAssignment`, above) — race-free, because the count and the insert are
one statement in one transaction, never a check-then-write two statements apart could race between.
Arc 21's whole size arithmetic — `TagCodec.WORST_CASE_BYTES` (the proof the worst legal index still
fit one 4 MiB store value), `MAX_TAG_ID_CHARS`, `CompactId`'s 22-character base64url id encoding —
is **deleted** with the one-blob layout it existed to protect: there is no longer any relationship
between a cap here and a byte budget anywhere, because there is no single value whose size the caps
had to keep under a ceiling. Tag ids are now plain `UUID.randomUUID().toString()`, and
`identityKey` (`TagRules.identityKey` — trim, collapse whitespace runs, fold case) is a **stored,
uniquely indexed column** rather than a value the codec omitted to save space: on rows, the
uniqueness of a tag identity has to be enforced by *something*, and a `UNIQUE` index is the only
thing that can enforce it across two processes with no lock (arc 21 argued the opposite — a stored
copy could disagree with the question it answers — and that reasoning still holds for what a
**record** carries: `TagRecord.identityKey` is still derived, never itself a wire field).

### `TagIndex` moved to `:ext-tags`, and is query-only

`TagIndex` used to live in `:extension-api`, pure and shared, because both processes decoded the
identical bytes off one `snapshot`. There is no blob to share any more: the host asks the store
directly for `TagRecord`s and `AssignmentRecord`s and ranks them itself, so `TagIndex` moved into
`:ext-tags` at X3 as **the screen's own in-memory query model** — built once per showing from two
reads (`tags()` and `assignmentsOfNotebook`), filtered in memory per keystroke (the arc-21 lock
stands: never a store call per keystroke), and holding **no edits at all** — every edit is a
statement through `TagStore`, and the screen re-reads both after each write, which is also how
another writer's edit arrives. `TagRules` and `TagPages` are the two pieces still shared, in
`:extension-api`, because both sides need the identical identity rule and the identical paging
loop; `TagCodec` and `CompactId` are deleted outright with the layout they served.

### `TagRules.isId` is case-insensitive on purpose

`TagRules.isId` (which replaced `CompactId.isId` at X3, carried over unchanged in behaviour) is
still the one check at every door a target id crosses — `TagShowing`'s constructor, `TagRecord` /
`AssignmentRecord`'s own `require`s — round-tripping a parsed UUID through `toString()` so a
lenient parse like `UUID.fromString("1-2-3-4-5")` is refused, and keeping a path character or a NUL
out of one for free (the canonical UUID alphabet has neither). It is **deliberately
case-insensitive** on the hex: `CompactId` already was, and arc 16's `SafeImportId` admits
upper-case ids out of a stranger's imported `.soil` — tightening the check now would make an
imported notebook's pages untaggable, since `TagShowing`'s `require` would refuse the showing over
a spelling difference that names the identical UUID.

---

## The calendar point (arc 23)

> The calendar **as a feature** — the three pages, the store, navigation, both transfers, the
> failure table — is [`docs/calendar.md`](calendar.md). What follows is the **seam**: the point,
> the held bind, the wire types, and what each side is allowed to know.

`ACTION_CALENDAR` + `ACTION_CALENDAR_SCREEN` — SN's **seventh** point, granted 2026-09-01, the
fourth screen-owning one and the second with paper (after the pad), served by **`NSE · Calendar`**
(`:ext-calendar`). The shape is the pad's, on purpose: the extension owns the calendar screen, its
g-paper surface and every stroke, in its own extension store; the host owns the two entry doors
(the library, and — since Y3 — the notebook) and the held bind. `ICalendar` is `IScratchPad`'s
four methods with the one thing the pad names by an int made a real type: where a placement is
`PLACEMENT_NEW_PAGE` / `PLACEMENT_CURRENT_PAGE`, the calendar's is a `CalendarTarget` — a page is a
*date*, and a date that was not normalized would mint a second row for the same period.

### The held bind

The bracket is the pad's, unchanged: `begin(store)` → launch the screen → the result →
`end()` → unbind → revoke the store binder, the last three in one `finally` on every path.

```
interface ICalendar {
    void       begin(IExtensionStore store);
    void       receiveInk(in InkBundle chunk, in CalendarTarget target, boolean last);
    InkBundle  takeOutgoing(int chunkIndex);
    void       end();
}
```

Every stub method calls `HostCallerCheck.enforce` first, and only the three marshalable exception
shapes ever leave one: `receiveInk` throws `IllegalArgumentException` over the transfer caps or on
a target that changes mid-transfer (every chunk of one transfer must name the same
`CalendarTarget` — the service re-checks it, the untrusted-input half of the host's own
before-any-bind check), and `IllegalStateException("store unavailable")` — the pad's own text — is
the one store failure, on either method that touches it. Nothing rides the screen's Intent but
booleans. Two are the pad's own, mirrored under the calendar's own names: `EXTRA_CALENDAR_SEND_ENABLED` (opened
from the notebook, so the calendar shows its Send buttons) and `EXTRA_CALENDAR_OPEN_RECEIVED`
(opened right after a `receiveInk`, so the calendar opens on the target page with the placed
strokes selected). **Since Y4 there is a third**, `EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE` — a
trusted pad is installed, so the calendar shows its own Scratch Pad button — set by
`ExtensionScreenEntry`'s new `decorateIntent` hook rather than by the calendar itself discovering
the pad (discovery stays the host's, an extension never queries for another). The button's tap
answers with a fourth result code, `RESULT_CALENDAR_OPEN_SCRATCH_PAD`, alongside `RESULT_CALENDAR_SEND`
and `RESULT_CANCELED` — it carries no data of its own, only a request that the host walk a door the
calendar cannot walk itself (an extension screen refuses any caller but the host).

### The wire types

**Reused unchanged.** `WireStroke`, `InkBundle`, `InkChunks` and every transfer cap
(`MAX_TRANSFER_STROKES`/`_POINTS`, `TRANSFER_CHUNK_STROKES`/`_POINTS`, `TRANSFER_MAX_CHUNKS`) are
the pad's own — a calendar page's ink is ink like any other, and the caps were never about what the
ink was *for*. What is new is `CalendarTarget`, which rides on **every** `receiveInk` chunk (not
just the last) exactly as the pad's `placement` int does:

```kotlin
class CalendarTarget(val kind: Int, val date: String, val half: Int) : Parcelable
```

`kind` is `KIND_MONTH` / `KIND_WEEK` / `KIND_DAY`; `date` is the period's ISO day
(`yyyy-MM-dd`), **already normalized** — a month's first day, a week's Sunday, the day itself;
`half` is 0 (AM, and the only legal value for a month or a week) or 1 (PM, `KIND_DAY` only). The
constructor's `require`s **are** the validation — unmarshal is validation, the family rule since
E1 — so a target that fails them crosses as an `IllegalArgumentException` rather than landing ink
on a page nobody asked for. `CalendarTarget.of(kind, day, half)` is the constructor a caller with a
`LocalDate` in hand uses (the host's target sheet); it normalizes through `CalendarDates` rather
than trusting the caller to have done so.

`CalendarDates` (pure, `java.time`, minSdk 29) is the arithmetic both sides and the tests share, so
**nobody guesses the week rule**: weeks start on **Sunday**, never the device locale. Titles come
from `DAY_NAMES` / `MONTH_NAMES` / `MONTH_NAMES_SHORT` **hand lists, indexed, never a formatter** —
arc 5's rule, because CLDR data drifts between devices and a page title is chrome, not locale data.
A date crosses the seam and lives in a row only as `LocalDate.toString()` — ISO, `Locale.ROOT`-safe
(og's Eastern-Arabic-digit lesson) — never a formatted string. `step(kind, date, half, forward)` is
the one place "the next page" is defined: a month by a month, a week by seven days, a day AM → PM →
the next day's AM (and the mirror going back) — the host's `CalendarTargets` (below) and the
extension's own navigation both call through it rather than each re-deriving the rule.

### Host side — `CalendarClient`, `CalendarEntry`, `CalendarTargets`

**Since arc 23 / Y4, `CalendarClient` and `CalendarEntry` are thin points, not shapes of their own
kept in step with the pad's by hand.** `CalendarClient` is a `HeldInkClient<ICalendar,
CalendarTarget>` whose companion `Point` supplies `ICalendar`'s names and budgets; `CalendarEntry`
is an `ExtensionScreenEntry<ICalendar, CalendarTarget>` supplying the calendar's registry lookup,
its four strings and its result code. Through Y3 each was a hand-kept copy of the pad's client and
entry — "`CalendarClient` is `ScratchPadClient`'s shape on `ICalendar`" was true only by discipline
— and the copies had already drifted once before Y4 closed them: the settle rule below landed on
the calendar's copy alone at Y3, and the pad did not gain it (or its `SETTLE_TIMEOUT_MS`) until this
unification, which is the `RattaNotebookView` sibling-copy trap in a second seam.

`open(sendEnabled, openReceived)` pre-opens the store on IO (the pre-open rule), mints a uid-bound
`ExtensionStoreBinder`, holds the bind, calls `begin(store)` under `CALL_TIMEOUT_MS` (2 s) and
returns the screen Intent or null (everything opened so far released on any failure); `send` hands
`receiveInk` its chunks with the `CalendarTarget` on every one, the **last** chunk under
`PLACE_TIMEOUT_MS` (10 s, the pad's number — a Binder call cannot be cancelled, so a budget too
short reports a failure for ink that then lands anyway — and therefore, since Y4, **a timed-out
placement is settled, not abandoned**: `HeldBinding.settle` waits the budget again for the orphaned
transaction, a late return without an exception is a success, and only a call that threw or is
still running past the second budget is a failure); `drainOutgoing` is the pad's `Drain` loop over
`takeOutgoing`; `finish` **settles first** (`SETTLE_TIMEOUT_MS`, so the store is never revoked under
a placement's batches, where the extension's own compensation would be refused by the same gate),
then runs `end()` best-effort, then unbinds and revokes in `finally`, idempotent, called from both
the result callback and `onDestroy`. All of that is `HeldInkClient`'s code, run once for both
points.

`CalendarEntry`'s shape — **one class serving both doors** (the library's, since Y1, and the
notebook's, since Y3) because everything about them is identical except one line: the notebook's
`beforeLaunch` runs `paper.releaseForHandoff()` immediately before the launch, and the library has
no pipeline to hand over — is `ExtensionScreenEntry`'s: it owns visibility (the button is `GONE`
unless `ExtensionRegistry.calendar` finds a trusted service, re-run on every `refresh()`), the busy
guard, the `OpeningOverlay` wait, and — since Y3 — both transfers' host half: an optional `InkSend`
crosses over the held bind **before** the screen is launched, and a `RESULT_CALENDAR_SEND` is
drained on the bind that is **still held**, handed to `onDrained`, before `finish()` runs.

**Since Y4, `ExtensionScreenEntry` also carries two hooks the calendar's own Scratch Pad door
rides, generic enough for either ink screen to use.** `decorateIntent(activity, intent)` runs after
`begin` succeeds and before launch — `CalendarEntry`'s sets
`EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE` from `ExtensionRegistry.scratchPad(ctx) != null`, the
pad's own registry lookup, never the calendar asking the pad directly. `onClosed(resultCode)` fires
once the showing and its bind are both finished — `opening` already released — so the caller may
open a second door from there without racing the first one's teardown. Both host callers wire the
same chain over it: `onCalendarClosed(resultCode)` opens `scratchPad` and sets a
`reopenCalendarAfterPad` latch when the result is `RESULT_CALENDAR_OPEN_SCRATCH_PAD`;
`onPadClosed(resultCode)` reopens `calendar` — landing at its bookmark, since nothing about the
calendar's own state changed — only when that latch is set and the pad's own result was
`RESULT_CANCELED`. A pad that sent ink to the notebook instead stays closed and the latch is simply
cleared: the paste on the page is what the person is looking at, not a reason to cover it with the
calendar again. `LibraryActivity` and `NotebookActivity` each carry their own latch and their own
pair of these two methods — one class could not serve both, since one door leaves ink behind to
paste and the other has no notebook to paste into at all.

`CalendarTargets` (`notebook/CalendarTargets.kt`) is the pure model behind the Send-to-Calendar
sheet's four rows — Today AM · Today PM · This week · This month — and **the host never computes a
period**: every row is `CalendarTarget.of(kind, today, half)`, so the week rule (Sunday-start) is
the contract's and the extension's alone. "Today" is passed in rather than read from the clock, so
`CalendarTargetsTest` can put the sheet on any day.

### Extension side — the store on rows

`CalendarService` parks what the host lent for the showing — the store binder, the accumulating
inbound chunks and their bound `CalendarTarget`, the outbound chunks, and the one-shot "just
received" record — in `CalendarSession`, under one monitor shared by `begin`/`receiveInk`/`end` so
a host that restarts mid-transfer can never interleave with a placement. `begin` also declares the
schema and logs the bookmark and the three row counts — the arc's on-device proof that browsing an
empty month wrote nothing, since `sqlite3` cannot read a SQLCipher file. **Since arc 23 / Y4 the
stub bodies are `:ext-ink`'s `InkTransferSession`'s, not `CalendarService`'s own** — `receiveInk`
calls `CalendarSession.receiveChunk` (the same accumulate-and-place body `ScratchSession` shares),
and `takeOutgoing` is `CalendarSession.outgoing`; what `CalendarService` still supplies itself is
the target's own null check (already through `requireValid` at unmarshal) and the log wording.

`CalendarSchema.V1` declares four tables:

```sql
period (id, kind, date)                                    -- UNIQUE(kind, date); date = ISO day
page   (id, periodId → period.id ON DELETE CASCADE, half, width, height, createdAt, updatedAt)
stroke (id, pageId → page.id ON DELETE CASCADE, "order", color, width, style, blob)
state  (key, value)                                        -- lastView · lastDate · lastHalf
```

A month or a week owns one `page` (`half` 0); a day owns two (0 = AM, 1 = PM). **Rows are minted on
the first stroke, never on open** — `CalendarStore.readPage` answers what is there and writes
nothing, and the flush that carries a page's first `Put` leads with `INSERT OR IGNORE INTO period`
+ `INSERT OR IGNORE INTO page` (`CalendarStore.mintRows`), the page's `periodId` **resolved inside
the `page` insert** from `(kind, date)` so the day's other half joins whatever period row already
exists rather than duplicating it. **Neither `period` nor `page` is ever `INSERT OR REPLACE`d** —
with foreign keys ON, REPLACE deletes the conflicting row first and that delete cascades, taking a
period's pages and their strokes or a page's strokes with it (X2's trap, which `CalendarSql :
InkDocument.StrokeSql` inherits along with the rest of the shape). `stroke` is the pad's row
exactly — `blob` is
`StrokeCodec` format B, `"order"` the writing order within the page. `page.width`/`height` is the
page's minted size — this device's screen — so the template, rendered always at the page's own
size, keeps grid and ink registered on any screen the store is later carried to. **Nothing deletes
a `period` in this arc**, at all.

`CalendarStore` (on `:ext-ink`'s `InkStore` base) and `CalendarDocument` (thin over `:ext-ink`'s
`InkDocument`) split the same way the pad's do: the document owns *which* period/page is showing,
whether its rows exist yet, and its size; what is *on* the page — the strokes, the op log, the
re-flush rule, the four stroke-level undo replays (`Drew`/`Erased`/`Moved`/`Pasted`) — is
`InkDocument`'s, shared with the pad so the two never drift. `CalendarStore.receive` (the Binder
thread, Y3) places a notebook → calendar transfer as one statement list: minting the target's rows
at `0 × 0` if none exist (the page takes the screen's size the first time a screen shows it — the
sender's page size is the sender's), numbering the new strokes after whatever is already there,
and — above the batch cap — **compensating** a part-way failure by deleting each minted stroke by
id (never an `IN (…)` list: the 999-argument cap) before throwing `StoreUnavailable`; a period or
page row a failed placement minted is left as it is, because an empty page is not a placement and
nothing deletes a period.

### The second paper surface and the EPD handoff

The calendar registers g-paper itself (`RattaEngine.register()` in `CalendarApplication` — its own
process, so the host's registration means nothing to it) and follows the pad's tier-2 recipe
verbatim: the caller releases (`releaseForHandoff()`) immediately before the launch — the library
door has no pipeline to hand over, only the notebook's does — the extension reclaims in `onResume`,
and every exit on the extension side releases before `finish()`. `docs/extensions.md`'s own
"Two paper surfaces, one EPD pipeline" note (§ the scratch-pad point) is the rule this point
inherits rather than re-derives; nothing about the ordering changed for a second consumer of it.

### `:ext-ink` — the seam's answer to the sibling-copy trap

The calendar is the second extension to own a paper surface over the host's extension store, and
without `:ext-ink` it would have been `RattaNotebookView` all over again — a hand-maintained copy
of the pad's wire mapping, row codec, batching, read planning, op log and stroke-level undo,
rotting one fix at a time. `:ext-ink` could not live in `:sn-screen`: it is extension-side code
over the *contract's* `Statement` and `WireStroke`, and `:sn-screen` is deliberately barred from
ever seeing `:extension-api` (§ Module layout) so a shared screen helper can never quietly become
part of the wire format. So it is its own library, depending on both — the one module in the whole
app that does — and the pad was repointed onto it at Y1 rather than the calendar being built as a
fresh copy of pre-`:ext-ink` code.

### What Y1–Y3 proved on the Nomad

**Y1** (the seam, the Month page): cold open **2,726 ms** (store creation) / warm **56 ms**;
`begin` **161 ms** cold / **34 ms** warm (the pad's warm `begin`: 23 ms); a shell `am start` of the
screen is `refused caller (none)`; `pm disable-user` makes the library button vanish
(`0 provider(s) of 0 candidate(s)`), `pm enable` brings it back; the store file is ciphertext; a
second month browsed and left is `rows: 0 period(s), 0 page(s), 0 stroke(s)`. **Y3** (the notebook
door, both transfers): `begin` **818 ms** cold-in-process / **28 ms** warm; `send` →
`receiveInk: 19 strokes placed … in 119 ms`; `drainOutgoing: 1 chunks, 19 strokes … in 106 ms`;
`pm disable-user` takes **both** doors — library and notebook — GONE at once, `pm enable` restores
both; `logcat -b crash` empty at every phase. `PLACE_TIMEOUT_MS` stays the pad's 10 s — 119 ms for
19 strokes leaves two orders of magnitude of headroom, and nothing in three phases suggested it
needed tightening.

**Privacy**, the family rule again: ink crosses and nothing else — no stroke id, page id, notebook
id or name has a parameter to ride on, `CalendarService`/`CalendarClient`/`CalendarEntry` log
counts and durations only, and the screen's Intent carries the action, the package and, since Y4,
three booleans — never a date, a target or a page. The third (`EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE`)
only gates whether the calendar's own Scratch Pad button shows; the result code it can send back
(`RESULT_CALENDAR_OPEN_SCRATCH_PAD`) is a request that the host walk a door, not a payload.

### Arc 24 — events, inside the point

**No contract change.** Arc 24 "Events" grew the calendar's own screen in place: no
`API_VERSION` bump, no host change, no new `<queries>` action, no new extras or result codes on
`ICalendar`. `EventsActivity` (the day's list) and `EventEditorActivity` (one event) are reached
only in-process — the list from `CalendarActivity`, the editor from the list — in the calendar's own process — an `exported="false"` Activity
launched in-process by an `ActivityResultLauncher` needs no `HostCallerCheck`, since it is never
reachable from outside the APK that declares it. That covers the list screen by the M3 / tier-2
recipe (a non-drawing child); the editor's own paper surface (`NoteSurface`) was Z3's open
question, settled by an on-device probe **before** it was built: g-paper's process-local
`inkOwner` guard (`RattaPaperView`) covers the whole chain — calendar (paper) → list (no paper) → editor (paper) —
by itself, so the calendar makes no `releaseForHandoff` call before launching the list; only the
editor's own surface reclaims in `onResume` and releases (`releaseForHandoff`) before every `finish()`.

The store grew in place too: the calendar's `Garden/<pkg>.db` picked up five tables
(`event`/`event_weekday`/`event_exception`/`event_reminder`/`note_stroke`) under one
`CalendarSchema.V2` step, so the arc-21 backup rule — every `Garden/<pkg>.db` copied
unconditionally — covers events with no backup change at all. `event` has children, so it is
never `INSERT OR REPLACE`d (X2's rule, restated for a third table family): a delete cascades the
row's children and its note, which is also why `EventSql.deleteEvent` is the one hard delete in
the arc.

The feature itself — the store, the recurrence engine, the two screens, the note, the grid, the
failure table, tests and traps — is [`docs/calendar.md`](calendar.md) § Events.

---

## Boundary audit

What crosses the process boundary, in which direction, and what guards it. **Re-walk this table
whenever a point is added or a contract field changes.** Rows 1–5 are the scratch-pad point, walked
against the code at the arc-11 freeze (2026-08-25) on the shape Paper's rows 28–32 established. Rows
6–8 are the exporter point, walked against the code at the arc-15 freeze (2026-08-27). Rows 9–11 are
the importer point, walked against the code at the arc-16 freeze (2026-08-28). Rows 12–13 are the
exporter point's arc-18 growth — the source-kind seam and the one deliberate secret crossing —
walked against the code at the arc-18 freeze (2026-08-30). Rows 14–18 are the document-editor
point and `:ext-document`'s two sibling registrations, walked against the code at the arc-19
freeze (2026-08-31). Rows 19–23 are the tag-manager point, originally walked against the code at
the arc-21 freeze (2026-09-01). **Rows 1, 5, 14, 16 and 19–23 are re-walked below against arc 22's
rebuilt store** (X1 through X4, 2026-09-01) — every one of them named a key/value detail that
changed underneath it — and rows 24–26 are the SQL gate, the reserved name spaces, and the one
extension table the host itself reads, none of which existed to audit before the store became
tables. Rows 27–33 are the calendar point, walked against the code at the arc-23 freeze
(2026-09-02) — the seam is the pad's, so most of the pad's rows above (1–5) hold for it unchanged
and are not repeated; what follows is what the calendar's own shape adds. Row 34 is arc 24
"Events" (2026-09-02), walked against the code at the arc-24 freeze — it added two more
in-process screens and five more store tables but touched no contract, so it is one row rather
than a run.

| # | The claim | Where it holds |
|---|---|---|
| 1 | **Outward on `begin` is the uid-bound store binder only.** `begin(store)` is the held bind's opening call and its one argument: an `ExtensionStoreBinder` minted in `ScratchPadClient.open` **after** `ExtensionStores.open` on IO (the pre-open rule), bound to `getPackageUid(ref.packageName)`, gated by `ExtensionStoreGate.check()` on every method, held for the showing in `ScratchSession.store` and revoked in the same `finally` as the unbind — on every path: result, cancel, caller `onDestroy`, failed `begin`. **Since arc 22 / X1 `IExtensionStore` is six SQL-shaped methods** (`schemaVersion`/`applySchema`/`exec`/`query`/`next`/`close`), not `get`/`put`/`delete`/`keys`/`putLarge`/`getLarge`, but the claim is unchanged: still no method that could return a key, path or `File`, and every statement that crosses is validated (`StoreSql`) before it runs on the one connection the host owns. Nothing else reaches the extension at open: the Intent is the action + `setPackage` + two booleans — no key, path, name, notebook or page id. | `HeldInkClient.open/finish` (via `ScratchPadClient`, thin since Y4), `ExtensionBinder.hold` / `HeldBinding`, `ExtensionStoreBinder`, `ExtensionStoreGate` (JVM-tested), `ScratchPadService.begin/end`, `ScratchSession`, `:ext-ink`'s `InkTransferSession.begin` (since Y4 — `ScratchSession.store` is this base class's field) |
| 2 | **Outward ink is bare geometry + width + colour + style name + the page px size — capped and chunked before the bind.** `InkBundle(strokes, pageWidth, pageHeight)` with `WireStroke` = four parallel `FloatArray`s + `width` + `colorArgb` + the `StrokeStyle` **name**. `TransferCaps.toWireStrokes` is the one reduction site from a g-paper `Stroke` (id and time never leave; point-less strokes skipped); `placement` is one of two recorded ints. **No stroke id, page id or number, notebook id or name, or selection bounds has a parameter to travel in** — `IScratchPad` has no other argument. Host side, before any bind: `withinLimits` → the "too much to send" dialog, then `InkChunks.chunk`. Extension side: `requireValid` at unmarshal, the running totals re-checked under one monitor, the placement int checked, fresh ids minted, the page written on the Binder thread under the full rule. | `IScratchPad.aidl`, `WireStroke` / `InkBundle` (parcel + `requireValid`, JVM-tested), `TransferCaps.withinLimits/chunk/toWireStrokes`, `InkChunks`, `HeldInkClient.send` (via `ScratchPadClient`), `ScratchPadService.receiveInk`, `:ext-ink`'s `InkTransferSession.receiveChunk` (since Y4 — the running-totals re-check and the placement-bound-by-first-chunk rule are this shared body's, not `ScratchPadService`'s own), `:ext-ink`'s `InkWire.toStrokes` (moved from `ScratchInk`), `ScratchStore.receive` |
| 3 | **Inward ink is validated, capped and fresh-id'd; the paste is one undoable step and nothing else on the page changes.** Every reply is an `InkBundle` → `requireValid` at unmarshal, then `TransferCaps.sanitize` (known style or PEN, width in 0.5–50 px, **colour forced opaque black**) under `Drain`: stop at the first empty bundle, at the summed caps, or at `TRANSFER_MAX_CHUNKS` + one probe past it (a non-empty chunk there = truncated → the "not everything came back" dialog, naming the pasted count). Fresh ids are minted host-side (`toStrokes`, `timeMillis 0`); `NotebookSession.pasteStrokes` writes the rows in **one transaction** with `"order"` rebased inside it, and `NotebookActivity` records **one** `Action.ObjectsPasted` and leaves the strokes selected. No other row, object, page or session state is touched; a failed write → a dialog, nothing pasted, and a drain that fails or brings back nothing gets its own dialog rather than a silent return (J6). The bind is finished **after** the paste callback, never before it. | `IScratchPad.aidl`, `InkBundle.requireValid`, `TransferCaps.sanitize/toStrokes/Drain` (JVM-tested), `HeldInkClient.drainOutgoing` (via `ScratchPadClient`), `ExtensionScreenEntry.onResult` (via `ScratchPadEntry`, holds the `DrainedInk`), `NotebookSession.pasteStrokes`, `NotebookActivity.pasteFromPad` |
| 4 | **The screen is the extension's, launched only by the core, caller-checked both ways; data never rides the Intent.** `ScratchPadActivity` is exported under `ACTION_SCRATCH_PAD_SCREEN` with `<category DEFAULT>` and **no launcher filter**; `HostCallerCheck.enforceActivity` is the first statement in `onCreate` (host package **and** `SIGNATURE_MATCH`, else `finish()` before anything is inflated). The core launches it only through an `ActivityResultLauncher` with `setPackage` from a trusted `ProviderRef`, and only after `begin` succeeded and (on a paper-hosting caller) `releaseForHandoff()`. The Activity reads only the two booleans and returns only `RESULT_SCRATCH_SEND` / `RESULT_CANCELED`; ink goes through the service, pages through the store binder. Every exit runs `releaseForHandoff()` before `finish()`. Verified on the Nomad every phase: a shell `am start` is `refused caller (none)`. | `ScratchPadActivity.onCreate` / `finishWithHandoff` / `onResume`, `HostCallerCheck.enforceActivity`, the `:ext-scratchpad` manifest, `HeldInkClient.open` (via `ScratchPadClient`), `ExtensionScreenEntry` (via `ScratchPadEntry`; `ActivityResultLauncher`, `beforeLaunch`) |
| 5 | **The store caps change no trust rule, and since arc 22 / X2 there is no page ceiling to enforce.** A payload is ≤ `STORE_MAX_VALUE_BYTES` (4 MiB): **inline** up to `STORE_MAX_INLINE_BYTES` (512 KiB); above that as a `LargeValue` — a read-only ashmem region + `byteCount` the receiver copies out of and closes in `finally`, host side through `SharedBytes.readAndClose` **before** the gate sees bytes, so the cap applies to the copy and never to a live mapping. Every statement is still `StoreSql`-validated, every method is still uid-bound and revocable through the same gate, and the DB is still opened only through `SoilCrypto` under the global key, with foreign keys ON so `ON DELETE CASCADE` actually cascades. `receiveInk` writes the page's rows as one or more `exec` batches (`:ext-ink`'s `StoreBatches`, moved from `ScratchBatches` at arc 23 / Y1, split at 4 MiB / 10 000 statements) — **a multi-batch failure is compensated** (the new page's cascade, or a `dropStroke` per minted id) rather than left half-written, so "nothing was sent" is never contradicted by orphaned rows. **The 4 MiB page ceiling, `PageFullException` and `SCRATCH_PAGE_FULL` — this row's claim through X1 — are deleted at X2**: a page is unbounded like a notebook page, and reads are planned into `BETWEEN` ranges (`:ext-ink`'s `StrokeReadPlan`, moved from `ScratchReadPlan` at Y1) rather than refused. The pad has no file, prefs or second store of its own. | `ExtensionContract.STORE_*`, `IExtensionStore.aidl`, `LargeValue`, `SharedBytes`, `ExtensionStoreBinder`, `ExtensionStoreGate` (JVM-tested), `ScratchSchema`, `ScratchSql`, `ScratchStore`, `ScratchDocument`, `:ext-ink`'s `StoreBatches` (moved from `ScratchBatches`), `:ext-ink`'s `StrokeReadPlan` (moved from `ScratchReadPlan`), `ScratchPadActivity` |
| 6 | **Outward on `export` is two fds and a bounded spec with no secret, no id and no path.** The call's only arguments are a read `ParcelFileDescriptor` (the host's own already-keyed cache artifact), a write `ParcelFileDescriptor` (the SAF destination the host opened) and an `ExportSpec` — an id → value map (each value ≤ `MAX_SPEC_VALUE_CHARS`, 64, a choice id or `"0"`/`"1"`, never free text) plus a display-only `notebookName` (≤ `MAX_NAME_CHARS`, 200; its constructor refuses `/` and NUL, so it cannot carry a path). **No notebook id, no file path, no passphrase has anywhere to ride** — the reserved keying option's chosen choice id crosses; the typed secret behind it never does, because `ExportOptions.specValues` never writes an entry for a `KIND_PASSPHRASE` option. | `INotebookExporter.aidl`, `ExportSpec` (constructor `require`s, JVM-tested), `ExportOptions.specValues`, `ExportNaming.specName`, `ExportActivity.runExport`, `ExporterClient.export` |
| 7 | **Inward is bounded descriptors and a byte count verified before success is believed.** `describe()`'s `ExporterInfo` and its `OptionDescriptor` list are capped at unmarshal (`MAX_OPTIONS` 8, `MAX_CHOICES` 8, `MAX_ID_CHARS` 32, `MAX_LABEL_CHARS` 80, `MAX_FILE_EXTENSION_CHARS` 12, `MAX_MIME_CHARS` 128 — every cap pinned by `ExporterContractTest`); a descriptor over any cap, declaring an option kind the host cannot draw, or declaring the reserved keying option with a choice id the host has no transform for, **drops that exporter with a log line, never a crash** (`ExportOptions.isRenderable`, `ExportActivity.loadCandidates`). `export()`'s `ExportResult` carries only a non-negative `bytesWritten`; the host checks it against the length of the file it actually streamed (the keying transform's output, when there was one) and, where the destination provider will answer, against what that provider now reports holding — an exporter that died mid-stream, or under-reported its own copy, cannot read as success on either count. | `ExporterInfo`, `OptionDescriptor`, `ExportResult` (constructor `require`s, JVM-tested), `ExporterContractTest`, `ExportActivity.loadCandidates` / `runExport`, `ExporterClient.describe` / `.export` |
| 8 | **The keying secret's whole lifecycle is host-side.** A typed *New passphrase…* value is entered into `ExportActivity`'s own XML-static, `saveEnabled="false"` masked fields — never saved to instance state, because the system may persist that Bundle to disk and the secret has no business there — held in a private, non-persisted `typedPassphrase` var from the Export tap to the end of the flow, consumed by `ExportKeying.apply` on the local cache artifact, and cleared in the flow's own `finally` — and at the picker's cancel, the other way the flow ends. It is never written into `ExportSpec` (the reserved keying option only ever carries a choice id), never put in an Intent extra, and never logged — failure paths log the transform's exception **class name only** (`Log.w(TAG, "keying transform failed: ${e.javaClass.simpleName}")`), on the recorded principle that a transform's own message text could carry a path. A rekey armed with the fields lost to a screen rebuild is refused with its own honest dialog rather than silently falling back to Keep. | `ExportActivity` (`editPassphrase`/`editPassphraseConfirm` XML `saveEnabled="false"`, `typedPassphrase`, `onExportTap`, `runExport`), `ExportKeying.plan` / `.apply`, `activity_export.xml` |
| 9 | **Outward on `importDocument` is two fds and a bounded spec with no secret, no id and no path.** The call's only arguments are a read `ParcelFileDescriptor` on the user's picked document (opened `"r"` from the SAF URI — the extension never sees the URI itself), a write `ParcelFileDescriptor` on `cacheDir/import/incoming.soil` (a host cache file — never a Garden path), and an `ImportSpec` — an id → value map (empty this arc; capped at unmarshal like the exporter's) plus a display-only `displayName` (≤ `MAX_NAME_CHARS`; constructor refuses `/` and NUL, and `ImportNames.specDisplayName` strips to the leaf and drops both before construction — a name the parcelable cannot express degrades to `""` rather than failing the import). **No notebook id, no path, no passphrase has anywhere to ride**: the unlock prompt does not even exist until after the delivery call has fully returned and the fds are closed. | `INotebookImporter.aidl`, `ImportSpec` (constructor `require`s, JVM-tested), `ImportNames.specDisplayName`, `ImportFlow.deliver`, `ImporterClient.importDocument` (both fds closed in `finally`) |
| 10 | **Inward is a bounded descriptor and a byte count that is corroborated, never believed — and the delivered bytes stay untrusted after both.** `describe()`'s `ImporterInfo` is capped at unmarshal (`MAX_FILE_EXTENSIONS` 8, `MAX_MIME_TYPES` 8, extension charset `[a-z0-9]`, MIME shape, label cap — pinned by `ImporterContractTest` / `ImporterInfoTest`); a failing descriptor drops that importer with a log line, never a crash. `importDocument()`'s `ImportResult.bytesWritten` must equal the length of the file that actually landed, a zero-byte delivery is refused, and the count is checked against every size the source provider will report (`OpenableColumns.SIZE` + fd stat) — **corroboration, not authority**: a provider claiming *more* than landed is a truncated stream and fails; one that says nothing or less (streaming providers report stale/placeholder sizes) never overrules two agreeing first-hand counts. Passing all of that earns the bytes nothing: the probe, the unlock, the re-key with its four-part acceptance (a same-device pass-through still pays a whole-file `integrity_check`), `SafeImportId` on every manifest id, and the create-only `AncestryPlan` all still treat the file as a stranger's — and the acceptance opens ride `SoilCrypto`'s no-op corruption handler, so a hostile file is refused, never deleted. | `ImporterInfo` / `ImportResult` (constructor `require`s, JVM-tested), `ImportFlow.loadCandidates` / `deliver` / `sourceSizes`, `NotebookImport.readManifest`, `ImportKeying.toGlobal`, `SafeImportId`, `AncestryPlan` (all pure parts JVM-tested) |
| 11 | **The unlock passphrase's whole lifecycle is host-side, and shorter than export's.** A foreign file's passphrase is typed into a dialog field built with `isSaveEnabled = false` (never in a saved instance state — a secret that survives a process death is a secret on disk), returned to the flow as a local, verified on IO (`SoilCrypto.verifyPassphrase`) under the `"IMPORT"` `AttemptLimiter` bucket (its own — a wrong guess at a stranger's file never counts against the library's unlock), consumed by `ImportKeying` as an SQL literal on a local connection (`ExportKeying.sqlLiteral`, pure, pinned by test), and out of scope when the flow ends. It is never in the spec (delivery is already over by then), never in an Intent, never logged — every failure path here logs an exception's **class name only**. The device's global key follows the same path one step shorter: fetched from `KeySession` inside the flow, handed only to `SoilCrypto` / `ImportKeying` / `SoilDatabase.open`, never crossing the seam. | `ImportDialogs.passphrase` (`isSaveEnabled = false`, IME kept up — the Ratta rule), `ImportFlow.unlock` (`ATTEMPT_BUCKET`), `AttemptLimiter`, `ImportKeying` (path-free messages), `ExportKeying.sqlLiteral` (JVM-tested) |

| 12 | **A `SOURCE_PAGES` exporter receives baked pixels, never the notebook — and its success is judged per source kind.** `ExporterInfo.sourceKind` is a compatible parcel tail (`dataAvail()` — absent = `SOURCE_SOIL`, so every pre-arc-18 descriptor keeps its meaning, proven on real wire at the D1 walk; an unknown kind fails unmarshal and drops the exporter). The host does the reading with the one process that can: `ExportRender` runs `ExportArtifact.prepare`'s guard order one for one (`SoilOpenFiles` held → IN_USE, missing key, unopenable), opens **read-only** through the one `SoilDatabase.open` door and stamps nothing (not even `exportedAt`), bakes each page at its own size — template, headings, links' children, ink — one page in memory at a time, into a `PageBundle` in `cacheDir/export/` that the screen's one `finally` wipes. The container is capped before allocation on **both** sides (`MAX_PAGES` 4096 / `MAX_DIMENSION_PX` 32768 / `MAX_PAGE_BYTES` 32 MiB; magic + declared count checked), and the extension re-checks each decode against the declaration — a mismatch is a delivery failure, never a page to skip. The device key opens the notebook for reading and never leaves the host. Verification (`ExportVerification`, pure): the verbatim `bytesWritten == streamBytes` equality runs for `SOURCE_SOIL` only; `SOURCE_PAGES` is corroborated against the destination's own answers, zero bytes is never a document, `SHORT` (may delete wreckage) stays distinct from `UNCONFIRMED` (never a delete), and an unknown kind is `SHORT` — verification never defaults to trust. | `ExporterInfo` (tail + `require`s, JVM-tested), `ExporterContract.SOURCE_*`, `PageBundle` (Writer/Reader caps, round-trip JVM-tested), `ExportRender` (+ pure `plan`, JVM-tested), `ExportVerification` (JVM-tested), `ExportActivity.runExport` / `renderedPages`, `PdfAssembly.addPage` |
| 13 | **The export secret is the ONE deliberate secret that crosses any extension seam — user-typed, export-scoped, and it opens no Notesprout data.** It is a password for the *output* file (arc 18 / D2's `OPTION_PROTECT`), never the global passphrase, never derived from it, never the device key; `KIND_PASSPHRASE` keeps its never-crosses meaning and the secret is never in the spec's value map — it rides only `ExportSpec.exportSecret`, a compatible tail holding because the spec stays `export()`'s trailing argument. Host lifecycle = `typedPassphrase`'s to the letter: XML-static `saveEnabled="false"` dual fields, held from the Export tap to the flow's end, cleared at the picker's cancel and in the flow's `finally`, never in instance state / an Intent / a log line; > `MAX_EXPORT_SECRET_CHARS` (128) refused at the tap; a screen rebuilt behind the picker refuses with the honest password-lost body rather than exporting unprotected; `isRenderable` drops a descriptor declaring both rekey and protect (one block, one tenant). Extension side: `PdfExportSpec` refuses an inconsistent delivery in both directions (armed-with-no-secret; secret-nothing-asked-for) with messages that never name, quote or measure the secret; `PdfAssembly` holds it only for the pdfbox call and drops its reference in `finally`, whichever way the assembly ended. | `ExporterContract.OPTION_PROTECT` / `MAX_EXPORT_SECRET_CHARS`, `ExportSpec` (constructor `require` + tail, JVM-tested), `ExportOptions.isRenderable` / `wantsExportSecret` (JVM-tested), `ExportActivity` (`typedExportSecret`, `onExportTap`, `runExport`, `saveLauncher` cancel), `activity_export.xml`, `PdfExportSpec.require` (JVM-tested), `PdfAssembly.assemble`, `PdfExporterService.export` |

| 14 | **Outward on the editor's `begin` is two uid-bound binders and nothing else — and nothing rides the screen's Intent at all.** `begin(store, host)` is the held bind's opening call: an `ExtensionStoreBinder` minted in `DocumentEditorClient.open` **after** `ExtensionStores.open` on IO (the pre-open rule) and a `DocumentHostBinder` over a fresh `DocumentHostSession`, both bound to `getPackageUid(ref.packageName)`, both revoked in the same `finally` as the unbind — on every path: result, cancel, caller `onDestroy`, failed `begin`. Revoking the host binder also **clears its session**: the read window and any half-received save go with the bind, never outlive it. **Since arc 22 / X4 the store binder is `IExtensionStore` v6**, so the editor's own service redeclares `MIN_API_VERSION_FOR_STORE` (6) — before that phase landed the service was still at 2 and `ExtensionRegistry` skipped it, so this `begin` never fired at all. The screen Intent is the action + `setPackage` and **not one extra** — no id, key, text, name or path; the scratch pad's two booleans were the last thing to ride an Intent on any SN seam, and this point starts with none. `begin`/`end` in the extension: `HostCallerCheck.enforce` first, marshalable exceptions only. | `DocumentEditorClient.open/finish`, `ExtensionBinder.hold`, `ExtensionStoreBinder`, `DocumentHostBinder.revoke`, `DocumentHostSession.clear` (JVM-tested), `DocumentEditorService.begin/end`, `EditorSession` |
| 15 | **The host-side stub answers only the bound extension, and text crosses it chunked, capped and target-keyed in both directions.** `IDocumentHost` is the first host-side stub on any SN seam; `gate()` — the bound uid, and not revoked — is the first statement of **every** method, so a stranger never learns which calls exist by the exception it gets back. Reads are a pull: every state-answering call loads the read window **atomically** with the `DocumentPageState` it returns (`setWindow` under one monitor), and `readChunk` refuses an index outside it. Writes are a push: `saveChunk` chunks arrive in order from 0, each ≤ `TEXT_CHUNK_CHARS`, the running total re-checked against `MAX_DOCUMENT_CHARS` on receipt (the untrusted-inward re-check — the receiveInk recipe), and **a save whose `pageKey` is not the current target's never accumulates a single chunk** (the mode-routing guard, structural: notebook text can never land on a page row, a flip-gap save is refused by key). Any refusal resets the whole accumulation. The watermark moves only through a drafted commit consuming a host-parked value — `NO_DRAFT_PENDING` otherwise, typed and `==`-matched; a different-key window swap clears the park (a cross-target draft anchor is unreachable). Hooks run blocking on Binder threads, funnelled to the marshalable set with **class name only** — a message could carry a path or a fragment of the document. | `DocumentHostBinder` (`gate`, `hook`, every override), `DocumentHostSession` (`setWindow` / `readChunk` / `acceptChunk` / `parkWatermark`, JVM-tested), `TextChunks` (JVM-tested both sides), `DocumentContract` caps + typed messages, `ChunkPush` (extension side, JVM-tested) |
| 16 | **The editor screen is the extension's, launched only by the core for a result — and the store holds small per-device state, never the document.** `DocumentEditorActivity` is exported under `ACTION_DOCUMENT_EDITOR_SCREEN` with `<category DEFAULT>` and no launcher filter; `HostCallerCheck.enforceActivity` runs first thing in `onCreate` (host package **and** signature, else `finish()` before anything is inflated), and the host launches only through an `ActivityResultLauncher` with `setPackage` from a trusted `ProviderRef` after `begin` succeeded — the tier-2 recipe, its second use. The extension writes nothing to disk: **since arc 22 / X4 its whole persistent surface is three tables** — `prefs` (`key`/`value` — the ONE table the host itself reads, pinned in `DocumentContract`), `word` (the user dictionary, the word IS the primary key) and `caret` (`pageKey`/`offset`/`updatedAt`, LRU 100 via a bound `DELETE` in the same batch as the write) — comfort, not content, replacing the old four key/value keys and their line codecs (`CaretMemory`, `UserWords`, both deleted). A draft never lives in the store (autosave pushes through the host binder), every store failure degrades to a default silently, and `EditorStore` applies the schema and fetches the store binder on **every** call because a restarted host lends a new one. The `end()` flush is the teardown backstop: the host is asked `current()` first (a host that cannot answer leaves everything **parked**, never lost), the live buffer rides the saver's own push lock so it can never interleave with an in-flight autosave, a same-key park is skipped as the older copy, and a park or buffer whose key is not the host's current target is parked or **dropped by `PendingPark`'s key rule** — writing it elsewhere would be corruption. | `DocumentEditorActivity.onCreate`, `HostCallerCheck.enforceActivity`, the `:ext-document` manifest, `DocumentEditorEntry`, `EditorSchema`, `EditorSql`, `EditorStore`, `EditorPrefs` (JVM-tested), `DocumentEditorService.flushBeforeRevoke` / `pushPendingInBackground`, `DocumentSaver.pushLockedBlocking`, `PendingPark` (JVM-tested) |
| 17 | **A `SOURCE_DOCUMENT` exporter receives final bytes the host assembled — and is held to the verbatim equality.** The host does the assembly *and* the format strip (`ExportText` — read-only open through the one `SoilDatabase.open` door after the `SoilOpenFiles` guard, stamps nothing, not even `exportedAt`; a `.txt` stripped through `:markdown`; **export never recognizes** — no document is an honest refusal, never an empty file), so what crosses the read fd is already the file the user asked for and the extension is a byte-for-byte streamer with no decode and no charset sniff. The reserved `OPTION_TEXT_FORMAT` is host-executed (assembly + destination naming); the spec that crosses carries the choice id and nothing new. Because the output is a copy and not a transform, `ExportVerification` holds this kind to the same `bytesWritten == streamBytes` equality as `SOURCE_SOIL` — the per-kind verification rule doing its job. The exporter is listed only when the notebook has a document (`ExportDocumentRules.listed`), its service meta-data declares API 3, and an unknown source kind still fails unmarshal and drops the exporter. | `ExportText` (guard order in KDoc), `ExportDocumentRules` (JVM-tested), `ExporterContract.SOURCE_DOCUMENT` / `OPTION_TEXT_FORMAT`, `ExportVerification` (JVM-tested), `DocumentExporterService.export` (E1-shaped fd `finally`), `DocumentExporterDescriptor` (JVM-tested), `TextStreams.streamCopy` |
| 18 | **A `RESULT_TEXT_DOCUMENT` importer streams verbatim, and the host decides what the bytes are — after delivery, under its own caps.** `ImporterInfo.resultKind` is a compatible parcel tail (absent = `RESULT_NOTEBOOK`, the arc-18 `sourceKind` recipe mirrored; an unknown kind fails unmarshal and drops the importer), and the service's API-3 meta-data is what keeps a version-2 host — which would read the absent tail as `.soil` and run Markdown through the notebook probe — from ever pairing with it. `TextImporterService` is `SoilImporterService` in shape down to the line: caller check inside the fd `try`, verbatim `streamCopy`, no decode, no cap of its own — recognising the bytes is the job of the side that owns the data. Host side, after delivery: byte cap first (10 MB, first-hand `File.length()`), **strict UTF-8** with `CodingErrorAction.REPORT` (mojibake refused, not landed), NUL = binary wearing a text extension, the char cap re-checked after decode, BOM and CRLF normalized and nothing else touched. What survives becomes an ordinary encrypted text-document create — the delivered bytes never name a path, an id or a destination. | `ImporterInfo` (tail + `require`s, JVM-tested), `ImporterContract.RESULT_*`, `TextImporterService` (API 3 meta-data in the manifest), `TextImport.decode` (JVM-tested), `ImportFlow` (the fork after delivery), `TextStreams.streamCopy` |

| 19 | **Outward on `configureShowing` is a target *pair* and its display label — no tag the library already holds, no store key or path.** `TagShowing(notebookId, pageId?, targetLabel, mode, prefill?, pageIds, pageLabels)` crosses on the held bind, after `begin`, before the launch; the screen's own Intent carries only the action and the package. Both ids are checked as canonical UUIDs in the constructor (`TagRules.isId` since arc 22 / X3 — `CompactId.isId` before it, identical behaviour, now case-insensitive on the hex; the one check that also keeps a path character or a NUL out of either), `targetLabel` is capped at `MAX_TARGET_LABEL_CHARS` (200) and refuses a NUL, `prefill` is capped at `MAX_TAG_CHARS` (64) when present, and MANAGE's parallel `pageIds`/`pageLabels` are capped at `TagShowing.MAX_PAGES` (5,000) and must match in length — the parcel refuses rather than allocates above any of them. The showing says what the screen is *about*; what is *in* the index is the extension's to read for itself. | `TagShowing` (constructor `require`s, JVM-tested), `ITagManager.aidl`, `TagClient.open`, `TagManagerService.configureShowing`, `TagSession.showing` |
| 20 | **`tags`/`assignmentsOf` hand back the index a page at a time, and neither rides ashmem any more.** Arc 22 / X3 replaced `snapshot` (one `LargeValue` of the whole index, unreadable-never-empty over ashmem) with two ordinary paged Binder calls: `tags(store, offset)` answers ≤ `TAGS_PAGE` (500) `TagRecord`s, `assignmentsOf(store, tagIds, offset)` answers ≤ `ASSIGNMENTS_PAGE` (1 000) `AssignmentRecord`s for ≤ `ASSIGNMENT_QUERY_TAGS` (500) ids at a time; both constructors are the unmarshal validation (`TagRules.isId` on every id, `TagRules.isValid` + the normalized-form check on `display`), so a malformed record is dropped and counted rather than corrupting a decode the way an unreadable blob once could. `TagPages.collect` (`:extension-api`) is the one paging loop both sides run — a short page ends it, a runaway peer trips a guard rather than returning a silently truncated list. There is no longer an "unreadable index" state to re-type: a store the extension cannot reach is `StoreUnavailable`, the same as every other store failure. | `ITagManager.tags`/`.assignmentsOf`, `TagManagerService` (thin pass-through to `TagStore`), `TagClient.search`, `TagPages.collect` (JVM-tested), `TagRecord`/`AssignmentRecord` (constructor `require`s, JVM-tested) |
| 21 | **`assign` crosses normalize-ready text and a target pair outward, and the tag's canonical spelling inward — nothing else either way.** Outward: `store`, `text` (untrimmed — normalization is `TagRules`'s job, not the caller's), `notebookId` (always) and `pageId` (nullable — present only for a page tag). Inward: one `String`, the tag's **canonical display form** — the casing whoever created it first used, which may not be the casing this call just sent, and is the whole reason the call answers with a string rather than a boolean: it is what the host's toast says. A cap refusal (`IllegalStateException(TAG_INDEX_FULL)`) or invalid text (`IllegalArgumentException`) leaves the index untouched on both sides — since arc 22 / X3, `TagStore.assign`'s one two-statement transaction simply never runs, or runs and is undone by SQLite's own rollback; there is no `TagWrites.apply`/`store.write` step left to short-circuit. | `ITagManager.assign`, `TagManagerService.assign`, `TagClient.assign` (`TagIndexFullException`), `TagStore.assign` (JVM-tested via `FakeTagStore`), `TagManagerEntry.assign` (the lasso's silent door) |
| 22 | **The store-index layout is `tag` / `assignment` rows — never one key holding the whole blob.** `TagSchema.V1` (`TagStore.load`) declares both tables; every statement lives in `TagSql`, and a read or write is one or two ordinary statements rather than a whole-value round trip. **No read-modify-write remains to serialize**: `assign` is two small reads plus one two-statement transaction (`insertTag` gated on both the tag and assignment `COUNT(*)` caps, `insertAssignment` resolving the tag id by identity inside its own statement), and SQLite's own transaction is what makes it correct under two writers — the screen on IO and the service's call-shaped `assign` on a Binder thread — with no process-local lock standing in for it. `TagWrites` and its monitor (`TagSession.writes`) are **deleted**. | `TagSchema`, `TagSql` (JVM-tested via `TagSqlTest`), `TagStore.assign`/`load` (JVM-tested), `TagSession` |
| 23 | **The host holds decoded `TagRecord`/`AssignmentRecord` pages only at query time, and never writes any of it.** Every door that shows or edits a tag routes through the extension (`TagManagerEntry.open` / `.assign`); the host itself never runs a write statement and never calls into `TagStore`. The one place the host reads the index without opening a screen is the library's search merge (`TagClient.search`, arc 22 / X3's two-query replacement for W4's `snapshot`), and dead assignments never surface there **without a filtering pass**: `SearchAssembly.rank` reads tags only by iterating the notebook list it was already handed — the index's own live listing — so an assignment naming a notebook that is not in it is simply never looked at, and a page's aliveness is answered separately, from the owning notebook's own live page list. Neither `TagRecord` nor `AssignmentRecord` carries an aliveness flag of its own — the merge gets the identical result structurally, for free, from lists it was reading anyway, the same design W1's shipped-but-uncalled filter was removed in favour of at W6. | `TagManagerEntry` (every screen-opening door), `TagClient.search`/`.assign`, `SearchAssembly.rank` |
| 24 | **Every statement that crosses `exec`/`query` is validated before it touches the connection — one statement, a known head keyword, no denylisted word, no reserved name.** `StoreSql.checkExec`/`checkQuery` run inside `ExtensionStoreGate` between decoding a `StoreCodec` batch and running it: `PRAGMA`, `ATTACH`, `VACUUM`, every DDL keyword, and every transaction-control keyword are refused anywhere in the token stream (not just as the head), a query cannot smuggle a write under `WITH`, and only one statement (one optional trailing `;`) is ever accepted per call. The file is per-package, so this is the whole of what a validator here needs to protect: the host's own connection and the file it lives in. A refusal is `IllegalArgumentException`, thrown before the executor is ever called — no partial run, nothing to roll back. | `StoreSql` (JVM-tested via `StoreSqlTest`), `ExtensionStoreGate.exec`/`.query` |
| 25 | **A `host_*`/`sqlite_*`/`room_*`/`android_*` name is refused wherever it appears — bare or quoted, in a query, a write, or a schema step.** `StoreNames.isReserved` runs on every WORD and quoted-identifier token `StoreSql`'s tokenizer produces, in every statement kind, so an extension cannot reach `host_schema` (the host's own applied-version table), SQLite's `sqlite_master`/`sqlite_sequence` catalog, or a name a Room-era or platform convention would mint — `sqlite_version()` is refused along with everything else in that space, a harmless loss the validator does not special-case around. `StoreNames.isValid` additionally requires a table or index name an extension *creates* to be lowercase, bare and `^[a-z][a-z0-9_]{0,62}$` — column names are exempt, since they are the extension's own business and never collide with a host-owned object. | `StoreNames` (JVM-tested via `StoreSqlTest`), `StoreSchema.requireValid` (DDL names), `StoreSql.check` (runtime names) |
| 26 | **The host reads exactly one extension table, through its own executor, never a binder, and only if the table is already there.** `DocumentPdfRender.editorTextSizeSp` is the sole caller: it resolves the installed document editor, checks `extensionStoreFile(...).exists()`, opens the store the normal `ExtensionStores.open` way, calls `ExtensionStoreDatabase.hasTable(DocumentContract.PREFS_TABLE)`, and only then runs a plain `SELECT` through `StoreExecutor` — no `IExtensionStore` binder is ever minted for this read, and nothing is created if the table or the file is absent. The table's name and both column names are pinned in `DocumentContract` rather than duplicated in `:app`, so the two sides cannot drift on what "the prefs table" means; every failure on this path — missing editor, missing file, missing table, an unparseable value — lands on `DocumentPdfMetrics.DEFAULT_TEXT_SIZE_SP` rather than refusing the export. | `DocumentPdfRender.editorTextSizeSp`, `DocumentContract.PREFS_*`, `ExtensionStoreDatabase.hasTable`, `EditorSchema` (the table this reads) |
| 27 | **The `ICalendar` held bind is the pad's bracket verbatim, and every stub method enforces the caller before it does anything else.** `begin(store)` → the screen launch → `receiveInk`* / `takeOutgoing` → `end()` → unbind → revoke, the last three in one `finally` on every path (result, cancel, caller `onDestroy`, failed `begin`) — and, since the Y4 review, **`finish` settles any call a timeout orphaned before `end()`** (`HeldBinding.settle`): a Binder call cannot be cancelled, so the revoke must never land under a placement still writing its batches (the extension's compensation is refused by the same gate that refused the batch). Every one of `CalendarService`'s four stub methods calls `HostCallerCheck.enforce` first; only the three marshalable shapes ever leave one, and the one store failure either side of a transfer can hit is `IllegalStateException("store unavailable")` — the pad's exact text, so a caller that already handles the pad's failure needs no new case for the calendar's. | `ICalendar.aidl`, `CalendarService.begin/receiveInk/takeOutgoing/end`, `HostCallerCheck.enforce`, `HeldInkClient.open/send/drainOutgoing/finish` (via `CalendarClient`, thin since Y4), `CalendarSession`, `:ext-ink`'s `InkTransferSession.begin/receiveChunk/outgoing/clear` (since Y4 — `CalendarService`'s four stub methods call into this shared base rather than holding the bracket themselves) |
| 28 | **`CalendarTarget` rides every `receiveInk` chunk — not just the last — and is validated by construction, never by convention.** `CalendarTarget(kind, date, half)`'s `require`s run in the constructor, so unmarshal *is* the validation (the family rule since E1): an unnormalized date, an out-of-range `kind`, or a `half` illegal for its `kind` (PM on a month or a week) all cross as `IllegalArgumentException` before a single stroke is placed. Every chunk of one transfer must additionally name the identical target — a change mid-transfer drops the whole accumulation — which is the untrusted-input half of the host's own before-any-bind check (`CalendarClient.send` builds one target and reuses it for every chunk it sends). **Since arc 23 / Y4 that refusal is `:ext-ink`'s `InkTransferSession.receiveChunk`'s, shared with the pad, not `CalendarService`'s own** — the message crossing is now the generic `IllegalArgumentException("placement changed mid-transfer")` rather than the calendar's old `"target changed mid-transfer"`, and the same refusal now protects the pad's placement int too, which never checked for a mid-transfer change before this sweep. | `CalendarTarget` (constructor `require`s, JVM-tested via `CalendarTargetTest`), `CalendarDates.isNormalized` (JVM-tested), `ICalendar.receiveInk`, `CalendarService.receiveInk`, `HeldInkClient.send` (via `CalendarClient`), `:ext-ink`'s `InkTransferSession.receiveChunk` (JVM-tested via `InkTransferSessionTest`) |
| 29 | **The floor is per action, and the calendar's is its own constant, not a reused one.** `ExtensionContract.minApiVersion(ACTION_CALENDAR)` answers `MIN_API_VERSION_FOR_CALENDAR` (7), never `MIN_API_VERSION_FOR_STORE` (6) — the two floors happen to differ by one only because the calendar point was born a version later, not because the map conflates them. `accepts(action, apiVersion)` is the same range check every point runs (`minApiVersion(action)..API_VERSION`), so `:ext-calendar`'s declared 7 needs no special-casing in `ExtensionRegistry.discover` — the map is the only thing that changed shape. | `ExtensionContract.minApiVersion`/`.accepts`/`MIN_API_VERSIONS` (JVM-tested via `ExtensionContractTest`), `:ext-calendar`'s manifest `<meta-data>` |
| 30 | **Nothing rides the calendar screen's Intent but three booleans, and no transfer content ever could.** `EXTRA_CALENDAR_SEND_ENABLED` / `EXTRA_CALENDAR_OPEN_RECEIVED` are the pad's two extras under the calendar's own names, and `RESULT_CALENDAR_SEND` is the pad's result code, mirrored; the ink itself never touches the Intent in either direction — outbound ink is sent over the held bind *before* the screen launches, and inbound ink is drained on the bind that is *still held* after the result returns. **Since Y4** `EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE` is a third boolean, set by `ExtensionScreenEntry`'s `decorateIntent` hook from the host's own pad-discovery call rather than the calendar querying for another extension, and `RESULT_CALENDAR_OPEN_SCRATCH_PAD` is a fourth result code — a compatible addition, since the calendar keeps declaring `API_VERSION` 7 — that asks the host to walk a door the calendar cannot walk itself. No date, target, page id or notebook id has anywhere to ride, and neither does this new pair. | `ExtensionContract.EXTRA_CALENDAR_*`/`RESULT_CALENDAR_SEND`/`RESULT_CALENDAR_OPEN_SCRATCH_PAD`, `HeldInkClient.open` (via `CalendarClient`; the Intent it builds), `ExtensionScreenEntry.open`/`decorateIntent`/`onResult`/`onClosed` (via `CalendarEntry`), `LibraryActivity`/`NotebookActivity`'s `onCalendarClosed`/`onPadClosed` |
| 31 | **`:ext-ink` is shared extension-side code, not a wire contract — nothing in it is a parcelable, an AIDL type, or anything either side unmarshals.** `InkWire`, `StrokeRows`/`StrokeBlob`, `StoreBatches`, `StrokeReadPlan`, `InkDocument`, `InkAction` and the `InkStore` base all run **inside** one extension process, over the `IExtensionStore` calls that process already makes — the pad and the calendar each mint their own `IExtensionStore` binder and send their own `StoreCodec` statements through it; `:ext-ink` only supplies the code that decides what those statements say. Moving it out of `:ext-scratchpad` therefore changed no wire shape at all: the pad's `stroke`/`page` rows are byte-for-byte what they were before Y1, and `:ext-calendar`'s are the same shape again by choosing to reuse the same helpers, not by any contract requiring it to. | `:ext-ink`'s whole module (no manifest components, confirming it crosses no process boundary of its own), `ScratchStore`/`CalendarStore` (each still mints its own `IExtensionStore` binder) |
| 32 | **The calendar's schema is `period → page → stroke` + `state`, and the cascade is what keeps a `period`/`page` insert safe to retry.** Foreign keys are ON for the store connection (the family rule), so `stroke.pageId` and `page.periodId` both declare `ON DELETE CASCADE` — which is exactly why `CalendarSql.insertPeriod`/`insertPage` are `INSERT OR IGNORE` and **never** `INSERT OR REPLACE`: REPLACE deletes the conflicting row first, and that delete would cascade, taking a period's pages and their strokes, or a page's strokes, with it (X2's trap, inherited by `CalendarSql : InkDocument.StrokeSql`). Rows are minted on the first stroke only (`CalendarStore.receive`/`CalendarDocument`'s `statementsFor`), never on open — `CalendarStore.readPage` is read-only — and nothing in this arc issues a `DELETE FROM period` at all. | `CalendarSchema.V1`, `CalendarSql.insertPeriod`/`.insertPage`/`.putStroke`/`.dropStroke` (JVM-tested via `CalendarSqlTest`), `CalendarStore.mintRows`/`.receive`, `CalendarDocument.statementsFor` |
| 33 | **The host computes no calendar arithmetic of its own — `CalendarTargets` routes every choice through the contract's `CalendarDates`.** The Send-to-Calendar sheet's four rows (Today AM · Today PM · This week · This month) are each `CalendarTarget.of(kind, today, half)`, which normalizes through `CalendarDates.periodDate` inside `:extension-api` — the host never derives "this week's Sunday" itself. That is deliberate, not merely convenient: the week rule lives in one place, so a host-side guess could never come to disagree with the extension's own and mint a duplicate row for the week the two definitions parted ways. | `notebook/CalendarTargets.kt` (`CalendarTargetsTest`, JVM-tested), `CalendarTarget.of`, `CalendarDates.periodDate` |
| 34 | **Arc 24's two in-process Intents carry a date and an event id — never event text — and the events screens are reachable from nowhere but `CalendarActivity`.** `EventsActivity.EXTRA_DAY` and `EventEditorActivity.EXTRA_DAY`/`EXTRA_EVENT_ID` are the whole of what either Intent holds — an ISO day string and, for the editor, the id of the event being opened (absent on a new one); no title, note text, reminder or recurrence rule has anywhere to ride. Both Activities are `exported="false"`, so no `HostCallerCheck` is needed or possible — Android itself refuses a launch from outside `:ext-calendar`'s own process — and `EventsActivity` hands its result back the same way, `EXTRA_ENDED_ON`, one more ISO day. Event text stays inside the process end to end: `EventStore`/`EventRules`/`EventWording` log counts, ids and durations only, never a title or a note (`EventStore`'s own KDoc states the rule). | `EventsActivity.EXTRA_DAY`/`.EXTRA_ENDED_ON`, `EventEditorActivity.EXTRA_DAY`/`.EXTRA_EVENT_ID`, the `:ext-calendar` manifest (`exported="false"` on both), `CalendarActivity.openEvents`/`eventsLauncher` |

**One recorded asymmetry.** The host forces inbound colour to opaque black; the extension does not
force it on the ink the host sends. That is not an oversight and not a hole: SN's ink is fixed
black, so the host has no other colour to send, and the sender is signature-matched. The *untrusted*
direction — anything coming **into** the core — is the one that clamps.

---

## Privacy

Recognized text is never logged on either side of the boundary — every log line in
`RecognizerClient`, `HandwritingRecognizerService`, `MlKitEngine`, and `ModelManager` carries only
counts, character lengths, and durations. `Dots.describeLine`, the one debug-only line that comes
closest to describing recognized content, explicitly logs geometry and a coarse punctuation class
("period"/"comma"/"other") rather than the text itself.

**Document text is under the same rule, now covering the callback direction too** (arc 19):
`DocumentHostBinder`, `DocumentEditorClient`, `DocumentEditorService`, the editor, `ExportText`
and `TextImport` log counts, chunk counts, lengths and durations — never a character of the
document. Exception funnels on both sides of the seam carry a **class name only**, on the
recorded principle that an exception's own message could hold a path or a slice of the user's
text. The proofread engine extends it further: the user dictionary's words are the writer's own
vocabulary, so `EditorStore` / `EditorPrefs` log nothing at all — not even key names, which name
pages (arc 22 / X4 deleted `UserWords` and `CaretMemory` with the key/value layout they served;
the rule they enforced did not move to the tables that replaced them).

**Tags are the same rule again, applied to the shortest piece of user text this app carries**
(arc 21): a tag is the user's own words whether it came from typing or from a heading's or a
selection's recognized text, and it crosses the seam only on the bind (`TagShowing`, `assign`'s
argument, the `tags`/`assignmentsOf` replies since arc 22 / X3), never on an Intent and never in a
log line. `TagManagerService`, `TagClient`, `TagManagerEntry` and `TagsActivity` all log counts,
lengths, mode and target *kind* — never a tag's text or a target's label. `configureShowing`'s own
debug line names the mode, the target kind and the page count on purpose, and stops there.

**The calendar crosses ink and nothing else** (arc 23): a stroke's geometry, width, colour and
style name are the whole of what either transfer carries, plus the `CalendarTarget` naming the
page it lands on — no stroke id, page id, notebook id or name has a parameter to ride on in either
direction. `CalendarService`, `CalendarClient` and `CalendarEntry` log counts and durations only
(`begin: … rows: N period(s), N page(s), N stroke(s)`, `receiveInk: N strokes placed on
kind/date/half in N ms`) — a target's `kind`/`date`/`half` are logged because they say *where*, not
*what*, the same distinction `configureShowing`'s debug line draws for the tag manager's mode and
target kind.

---

## Identity

All seven extensions share one recipe; only the name and the point differ. (`:ext-soil` serves
**two** points — exporter and importer — under one identity: the user's arc-16 call was no rename,
so the label stays `NSE · Soil Export` even though it imports too. `:ext-pdf` is the second
exporter on the same point — arc 18, no new point. `:ext-document` serves **three** points under
one identity — its own editor point plus one service each on the exporter and importer points.
`:ext-tags` and `:ext-calendar` are the two extensions in the family whose icon is **not** the
shared Tabler "puzzle" — each on its own wizard call, an app icon of Tabler `tag` and Tabler
`calendar` respectively, because each is a point a person is likely to find by name in
Settings → Apps rather than by process of elimination.)

**`:ext-scratchpad`**

| | |
|---|---|
| Label | **"NSE · Scratch Pad"** (`"NSE · Scratch Pad Dev"` in debug — a build-type string override, not a suffix) |
| Package | `com.symmetricalpalmtree.notesproutsn.ext.scratchpad` (`.dev` in debug) |
| Icon | the same Tabler "puzzle" glyph as `:ext-mlkit` — the extension family reads as one thing |
| Launcher activity | **None**; the screen `<activity>` is exported under its own action with `<category DEFAULT>` (without which implicit resolution never matches it) and is refused unless launched for a result by the host |
| versionName | host lockstep: `0.1.0-ratta` (`-dev` in debug) |
| Release APK | 6.8 MB |
| API version | declares **6** (arc 22 / X2 — `MIN_API_VERSION_FOR_STORE`; the pad's button was gone from the host between X1's landing and X2's, when this was still the pre-X1 number) |

**`:ext-mlkit`**

| | |
|---|---|
| Label | **"NSE · ML Kit"** (`"NSE · ML Kit Dev"` in debug — a build-type string override, not a suffix on the shared resource) |
| Package | `com.symmetricalpalmtree.notesproutsn.ext.mlkit` (`.dev` in debug) |
| Icon | Tabler "puzzle," ink-black outline, at the same ×3.1 / 108dp-viewport scale as the host's own launcher glyph — same visual family in Settings → Apps and in any launcher that lists every package |
| Launcher activity | **None** — Supernote's own launcher shows the package anyway; accepted as "Ratta being Ratta" rather than worked around |
| versionName | host lockstep: `0.1.0-ratta` (`-dev` suffixed in debug), bumped together with `:app` at arc freezes |

**`:ext-soil`** (arc 15 / E1 · arc 16 / I1 — `SoilExporterService` + `SoilImporterService`, one APK)

| | |
|---|---|
| Label | **"NSE · Soil Export"** (`"NSE · Soil Export Dev"` in debug — a build-type string override, not a suffix) |
| Package | `com.symmetricalpalmtree.notesproutsn.ext.soil` (`.dev` in debug) |
| Icon | the same Tabler "puzzle" glyph as `:ext-mlkit` and `:ext-scratchpad`, byte-identical vector — ink-black outline, ×3.1 / 108dp-viewport scale, same family in Settings → Apps |
| Launcher activity | **None** — the Supernote launcher shows the package anyway; the family recipe |
| versionName | host lockstep: `0.1.0-ratta` (`-dev` suffixed in debug), bumped together with `:app` at arc freezes |

**`:ext-pdf`** (arc 18 / D1 — `PdfExporterService`, the second exporter on arc 15's one point)

| | |
|---|---|
| Label | **"NSE · PDF Export"** (`"NSE · PDF Export Dev"` in debug — a build-type string override, not a suffix) |
| Package | `com.symmetricalpalmtree.notesproutsn.ext.pdf` (`.dev` in debug) |
| Icon | the same Tabler "puzzle" glyph as the other three, byte-identical vector — the family mark, the user's D1 phase-start call (no PDF-specific glyph) |
| Launcher activity | **None** — the Supernote launcher shows the package anyway; the family recipe |
| versionName | host lockstep: `0.1.0-ratta` (`-dev` suffixed in debug), bumped together with `:app` at arc freezes |
| Release APK | 14 MB — pdfbox-android pulls bouncycastle; module-local, and since the D3 review it assembles every export (the framework's `PdfDocument` held each page's raster until the write — the memory finding) |
| API version | declares **2** (`sourceKind` is load-bearing for it) — an older host skips it at discovery rather than streaming a `.soil` at it; `:ext-mlkit` and `:ext-soil` stay at 1, `:ext-scratchpad` declares 6 (arc 22 / X2 — a store-taking point, not a `sourceKind` matter) |

**`:ext-document`** (arc 19 / M3, grown M8–M10 — `DocumentEditorService` + the editor screen, `TextImporterService`, `DocumentExporterService`, one APK on three points)

| | |
|---|---|
| Label | **"NSE · Document"** (`"NSE · Document Dev"` in debug — a build-type string override, not a suffix; **singular** — the user's M3 call) |
| Package | `com.symmetricalpalmtree.notesproutsn.ext.document` (`.dev` in debug) |
| Icon | the same Tabler "puzzle" glyph as the other four, byte-identical vector — the family mark |
| Launcher activity | **None** — the editor `<activity>` is exported under its own action with `<category DEFAULT>` and is refused unless launched for a result by the host; the Supernote launcher shows the package anyway, the family recipe |
| versionName | host lockstep: `0.1.0-ratta` (`-dev` suffixed in debug), bumped together with `:app` at arc freezes |
| Release APK | 7.8 MB — SymSpellKt is module-local (the pdfbox precedent), and the bundled proofread dictionary asset (`assets/proofread/en_82765.dict` — gzip content behind an opaque extension, because AAPT gunzips any `.gz` asset and strips the extension) rides inside |
| API version | **per service**: the editor declares **6** (arc 22 / X4 — `MIN_API_VERSION_FOR_STORE`; the Document button was gone from the host between X1's landing and X4's, when this was still 2), the text importer and document exporter stay at **3** (the `resultKind` tail / `SOURCE_DOCUMENT` are load-bearing for them, and neither takes a store — an older host skips those two services at discovery and still binds nothing it would misread) |

**`:ext-tags`** (arc 21 / W1–W4, the TENTH module — `TagManagerService` + `TagsActivity`, one APK on one point)

| | |
|---|---|
| Label | **"NSE · Tags"** (`"NSE · Tags Dev"` in debug — a build-type string override, not a suffix) |
| Package | `com.symmetricalpalmtree.notesproutsn.ext.tags` (`.dev` in debug) |
| Icon | **not** the family's puzzle — Tabler `tag`, ink-black outline only, on the wizard's own call: this is the one extension whose subject already has a glyph everyone reads, so it wears its own mark rather than the shared one. Same adaptive-icon geometry as every other extension's (108dp viewport, Tabler's 24-unit grid × 3.1 centred at 54) |
| Launcher activity | **None** — the screen `<activity>` is exported under its own action with `<category DEFAULT>` and is refused unless launched for a result by the host; the Supernote launcher shows the package anyway, the family recipe |
| versionName | host lockstep: `0.1.0-ratta` (`-dev` suffixed in debug), bumped together with `:app` at arc freezes |
| Release APK | ≈ 6.9 MB — no module-local dependency beyond `:extension-api` and `:sn-screen`; no Room, no SQLCipher, no serialization library (the tag index lives in the host's extension store, not in a file this APK owns) |
| API version | declares **6** (the point itself was the version-4 event, W4's target-pair reshape the version-5 one, and arc 22 / X3's store rewrite the version-6 one — see the `API_VERSION` ledger above; every tag door and the search merge were gone from the host between X1's landing and X3's, when this was still 5); every other extension's declaration is untouched |

**`:ext-calendar`** (arc 23 / Y1–Y3, the TWELFTH module — `CalendarService` + `CalendarActivity`, one APK on one point)

| | |
|---|---|
| Label | **"NSE · Calendar"** (`"NSE · Calendar Dev"` in debug — a build-type string override, not a suffix) |
| Package | `com.symmetricalpalmtree.notesproutsn.ext.calendar` (`.dev` in debug) |
| Icon | **not** the family's puzzle — Tabler `calendar`, ink-black outline only, the wizard's own call at Y1: this is the second point (after tags) whose subject already has a glyph everyone reads, so it wears its own mark rather than the shared one. Same adaptive-icon geometry as every other extension's (108dp viewport, Tabler's 24-unit grid × 3.1 centred at 54) |
| Launcher activity | **None** — the screen `<activity>` is exported under its own action with `<category DEFAULT>` and is refused unless launched for a result by the host; the Supernote launcher shows the package anyway, the family recipe |
| versionName | host lockstep: `0.1.0-ratta` (`-dev` suffixed in debug), bumped together with `:app` at arc freezes |
| Release APK | ≈ 6.9 MB signed (the Y4 build; the pad's is 6.9 MB too) — no module-local dependency beyond `:extension-api`, `:sn-screen` and `:ext-ink`; no Room, no SQLCipher, no serialization library (the calendar's rows live in the host's extension store, not in a file this APK owns) — almost exactly the pad's own size, the two sharing `:ext-ink` |
| API version | declares **7** (`MIN_API_VERSION_FOR_CALENDAR`) from its first phase — the point was born at 7, so unlike the pad, the tag manager and the editor, `:ext-calendar` was never live-but-skipped between a store rebuild and its own redeclaration: there was no lower number for it to have declared first |

---

## N3 — the debug entry point is gone

Through N0–N2 a debug-only ⋯ menu on the notebook toolbar carried one row, "Recognize page,"
which ran the whole-page pipeline over every stroke on the page and showed the result in a plain
dialog (text + a timing line) — the fastest way to eyeball recognition quality against real
handwriting while the heading flow didn't exist yet. N3 removes that row entirely: the heading
convert flow (`HeadingConvert`, `docs/notebook.md` § Headings) is now the only consumer of
`recognizeInk`, `recognizePage` is unused in the shipped app, and the debug ⋯ button itself is
gone from `NotebookActivity` along with it (the library screen keeps its own unrelated debug ⋯ —
recovery-key tools — untouched). A future debug need re-adds the same pattern from scratch rather
than reviving the removed menu.
