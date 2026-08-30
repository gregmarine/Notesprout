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
fixes, this doc's rows 9–11 and the freeze. The arc is complete and frozen. The rule survives,
one word wider each time: **no *fifth* capability point without another user decision**
(`apps/notesprout_ratta/CLAUDE.md`).

The pad as a **feature** has its own reference — [`docs/scratchpad.md`](scratchpad.md); export has
its own — [`docs/export.md`](export.md); import has its own too — [`docs/import.md`](import.md).
This doc is the seam for all four points.

Fresh code. Paper's own extension arcs (`PAPER_EXTENSIONS_PLAN.md`, `PAPER_RECOGNITION_PLAN.md`,
`PAPER_SCRATCHPAD_PLAN.md`, its `:extension-api` / `:ext-mlkit` / `:ext-scratchpad`) are the shape
reference — nothing is copied, and SN's AIDL is scoped to its **four** points rather than Paper's
broader capability set. Paper never built export or import, so it has nothing to say about the
third or fourth; og's `docs/full-notebook-export.md` § Import was the fourth's reading reference.

---

## Module layout

Seven modules, SN's own Gradle root:

| Module | Type | Depends on | Holds |
|---|---|---|---|
| `:sn-screen` | Android library | g-paper (`api`) + androidx; **never** `:app`, **never** `:extension-api` | the design resources and the screen helpers both paper surfaces need — see [`sn-screen.md`](sn-screen.md) |
| `:extension-api` | Android library | nothing in `:app`, no library beyond the Kotlin stdlib (`build.gradle.kts` says so explicitly) | the AIDL (`IHandwritingRecognizer`, `InkStroke.aidl`; `IExtensionStore`, `LargeValue.aidl`; `IScratchPad`, `WireStroke.aidl`, `InkBundle.aidl`; `INotebookExporter`, `ExporterInfo.aidl`, `ExportSpec.aidl`, `ExportResult.aidl`), the hand-written `InkStroke` / `LargeValue` / `WireStroke` / `InkBundle` / `ExporterInfo` / `OptionDescriptor` / `ExportSpec` / `ExportResult` parcelables, `PageBundle` (the arc-18 page-bundle container — pure `java.io`, no Android types), `SharedBytes`, `InkChunks`, `RecognizerStatus`, `ExtensionContract`, `ExporterContract`, `HostCallerCheck` |
| `:ext-mlkit` | Android application (its own installable APK) | `:extension-api` + `com.google.mlkit:digital-ink-recognition:19.0.0` | `HandwritingRecognizerService`, `ModelManager`, `MlKitEngine`, `PageText`, `StrokeSegmenter`, `Dots`, `Box` |
| `:ext-scratchpad` | Android application (its own installable APK) | `:extension-api` + `:sn-screen` (g-paper arrives through its `api`) + androidx; **never** `:app`, no Room / SQLCipher / serialization | `ScratchPadApplication`, `ScratchPadService`, `ScratchPadActivity`, `ScratchSession`, `ScratchStore`, `ScratchPageCodec`, `ScratchPages`, `ScratchInk` |
| `:ext-soil` | Android application (its own installable APK) | `:extension-api` only | `SoilExporterService`, `SoilExportSpec` — see [`export.md`](export.md); and, arc 16, `SoilImporterService` — see [`import.md`](import.md). One package, two services, one label |
| `:ext-pdf` | Android application (its own installable APK) | `:extension-api` + `com.tom-roush:pdfbox-android:2.0.27.0` (module-local — approved 2026-08-30, used only on the protect path) | `PdfExporterService`, `PdfDescriptor`, `PdfExportSpec`, `PdfAssembly`, `CountingOutputStream` — arc 18's second exporter on the same point; see [`export.md`](export.md) |
| `:app` (`extension/` package) | part of the host APK | `:extension-api` | `ExtensionRegistry`, `ExtensionBinder`, `ExtensionCallException`, `InkCaps`, `RecognizerClient`, `RecognizerReadiness`, `ScratchPadClient`, `TransferCaps`, `ExporterClient`, `ImporterClient`; and in `data/extstore/`, the extension store (`ExtensionStores`, `ExtensionStoreDatabase`, `KvEntity`, `KvDao`, `ExtensionStoreGate`, `ExtensionStoreBinder`) — plus, in `export/` and `crypto/`, export's own host-side half (`ExportActivity`, `ExportPanel`, `ExportOptions`, `ExportArtifact`, `ExportNaming`, `ExportKeying`, `SoilOpenFiles`), and in `importing/` and `crypto/`, import's (`ImportFlow`, `NotebookImport`, `ImporterMatch`, `ImportNames`, `AncestryPlan`, `SafeImportId`, `ImportDialogs`, `ImportOverlay`, `ImportKeying`, `NotebookRemap` in `data/soil/`) |

`:sn-screen` is deliberately **not** in that dependency chain: it never sees `:extension-api`, so a
shared screen helper can never quietly become part of the wire contract. `:ext-scratchpad` depends on
both, separately — and that seam is exactly why the host's `TransferCaps` and the extension's
`ScratchInk` are deliberate **twins** of the same wire ⇄ paper mapping rather than one shared class.

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
</queries>
```

The scratch pad needs **both** of its actions listed: one to discover and bind the service, one to
resolve and launch the screen. The exporter and importer points need only one each — `describe()`
and the delivery call both ride the same bind-per-call service. Plus `ACCESS_NETWORK_STATE`, for
the readiness flow's offline pre-check (below).

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
| `API_VERSION` | 2 (arc 18 / D3) | the host accepts an extension whose `<meta-data>` is in `1..API_VERSION` — the declared number is what the extension *requires* of the host, so a new-seam extension (the PDF exporter declares 2, for the `sourceKind` tail) is skipped by an older host instead of misread by it |
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

## The extension store (arc 11 / J2)

`IExtensionStore` is **not a capability point** — it is the service the host offers an extension it
has already bound: a per-package, host-owned, encrypted key/value store, handed in as a *parameter*
of the calls that need it and revoked when the bind ends. The rule it exists to enforce is short:
**an extension writes nothing to disk itself, ever.** Its data is the host's, under the host's key,
in the host's directory, and it survives the extension being uninstalled.

Six methods, in this order — the base four first, the large pair **appended**, never reordered, so
the four keep their transaction codes without an `API_VERSION` bump (the family's
compatible-append recipe, kept even though SN ships all six at once; the store extensions still
declare 1 — the arc-18 bump to 2 names the exporter's `sourceKind` seam, which none of them use):

```
byte[]     get(String key)
void       put(String key, in byte[] value)
void       delete(String key)
List<String> keys(String prefix)
void       putLarge(String key, in LargeValue value)
LargeValue getLarge(String key)
```

### Caps

`ExtensionContract.STORE_*`, enforced by the host and pinned by test:

| Cap | Value | Why |
|---|---|---|
| `STORE_MAX_KEY_CHARS` | 512 | the empty key is rejected too |
| `STORE_MAX_INLINE_BYTES` | 512 KiB | the `byte[]` path's ceiling — the Binder transaction budget is ~1 MB |
| `STORE_MAX_VALUE_BYTES` | 4 MiB | the large path's ceiling, sized for one key per scratch page |
| `STORE_MAX_KEYS` | 50 000 | per extension |
| `STORE_VALUE_LARGE` | `"value is large — use getLarge"` | the **exact** message `get` throws for a stored value above the inline cap; extensions compare it verbatim, not by substring |

A `put` above the inline cap is an `IllegalArgumentException`; a `get` of a value that was stored
above it is the `STORE_VALUE_LARGE` `IllegalStateException`, never a truncation. A put of a **new**
key at `STORE_MAX_KEYS` fails; replacing an existing key at the cap is still fine.

### Why the large pair exists — and the ashmem handshake

A 4 MiB `byte[]` cannot cross a Binder. Values above the inline cap travel in an ashmem region
(`LargeValue` = `SharedMemory` + `byteCount`), the same handshake in both directions:

- the **sender** creates a region of exactly `bytes.size`, maps RW, copies in, unmaps,
  `setProtect(PROT_READ)`, hands it over, and closes **its own** handle once the transaction is
  marshalled — a stub in `onTransact`'s `finally`, a client after the call returns;
- the **receiver** maps read-only, copies out exactly `byteCount` bytes, unmaps and closes in its
  own `finally`.

`SharedBytes.write` / `read` / `readAndClose` write that handshake once for both sides, so neither
side re-derives it. Two details are load-bearing:

- **ashmem refuses a zero-size region**, so an empty value rides a **1-byte region with
  `byteCount = 0`**. An empty value is a value, not an absence.
- `LargeValue.requireValid` runs in the constructor, therefore also at **unmarshal** — it is the one
  thing between a malformed parcel and a read past the region's end. `describeContents` returns
  `CONTENTS_FILE_DESCRIPTOR`, or `Bundle.hasFileDescriptors()` lies about it.

### Only three exceptions cross a Binder

`SecurityException`, `IllegalArgumentException`, `IllegalStateException` — that is the whole set
this contract uses. (Binder's own marshalable set is slightly wider — `NullPointerException` and
`UnsupportedOperationException` are in it too, which is why `ScratchPadService`'s not-yet-implemented
J5 methods can throw the latter safely — but nothing in the store path relies on that.) Anything
outside it kills the transaction **silently**, and the caller reads the empty reply as
null / success. In Paper that is exactly how a page came back blank and was then saved over the real
one. So:

- every DAO failure (SQLite full / locked / I/O) becomes an `IllegalStateException` inside
  `ExtensionStoreGate.io {}`;
- every ashmem step is wrapped by `ExtensionStoreBinder.region {}`, which exists **separately** from
  the gate's mapping because `ErrnoException` is checked and outside the set;
- an extension treats all three the same way: *store unavailable*.

### Host side

| Piece | Role |
|---|---|
| `data/SoilFile.kt` → `extensionStoreFile(ctx, pkg)` | **still the only path constructor**, `extensionStoreFile` included: `Garden/<pkg>.db`, beside the `.soil` files. `isValidExtensionPackage` (`[a-zA-Z0-9_.]+`) refuses anything that could become a path segment |
| `ExtensionStores` | open-or-create on IO, process-lifetime cache, one DB per package. SN's **second named create entry point** after `SoilDatabase.create`, and it obeys the same two doors — create only over a missing/empty file, open only through `requireExisting` + the raw-key cache. Raw-key id `ext:<pkg>`, which can never collide with a notebook UUID or the index's id |
| `ExtensionStoreDatabase` / `KvEntity` / `KvDao` | one `kv(key, value, updatedAt)` table, its own version. Nothing here touches the global index or any `.soil`, so neither one's version moves when this one does. `keysWithPrefix` uses `substr`, not `LIKE`: `LIKE` is ASCII-case-insensitive per connection *and* reads `%` / `_` as wildcards |
| `ExtensionStoreGate` | every check and cap, **with no Android types precisely so it is JVM-testable** — the binder is an `android.os.Binder` and cannot be constructed in a unit test |
| `ExtensionStoreBinder` | the `IExtensionStore.Stub` the host mints **per bind**, bound to that extension's uid; the ashmem copy in / out around the gate; `getLarge`'s region parked in a per-Binder-thread slot that `onTransact`'s `finally` closes **after** the reply (holding a dup of the descriptor) is written |

Encryption: `Garden/<pkg>.db` is SQLCipher under the **global** key, opened through `SoilCrypto`
like everything else, so every factory is `NonDestructiveOpenHelperFactory`-wrapped and a wrong key
reports corruption without deleting the file. The `.db`s sit in `Garden/` beside the `.soil`s and
are invisible to the library, whose structure is index-only — nothing enumerates that directory.

**Pre-open rule:** a caller opens the store on IO **before** binding the extension. A cold open runs
the KDF (≈ 0.5–1.5 s on e-ink when the raw key is not cached yet), and that must never land inside a
call's timeout window.

**Lifecycle:** the binder is minted per bind, uid-bound, and `revoke()`d in the same `finally` as
the unbind — after which every method throws `SecurityException`. The store file itself outlives the
extension: uninstalling or disabling one leaves its `.db` in place, because removing an extension's
data is a deliberate act, not a side effect.

### Verification

Room, SQLCipher and `SharedMemory` cannot run on the JVM, so the store is checked from two sides:

- **JVM** — `ExtensionStoreGateTest` drives every check and cap over a fake `KvDao` with an
  injectable calling uid; `LargeValueTest` pins the unmarshal validation; `ExtensionContractTest`
  pins the caps and the exact `STORE_VALUE_LARGE` string; `SoilFileTest` pins the package-name guard.
- **Device** — the debug library's ⋯ → **"Extension store self-test"** opens `probe.test`, checks
  the file header is encrypted, round-trips through a **real** `ExtensionStoreBinder` (called
  in-process, so `Binder.getCallingUid()` is our own uid and the gate passes), drives 4 MiB and
  empty values through real ashmem both ways, and proves the inline cap, the `STORE_VALUE_LARGE`
  refusal, the wrong-uid refusal and the revoked refusal. OK / FAIL as a toast.

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

One instance per calling screen. `open(sendEnabled, openReceived)`:

1. `ExtensionStores.open` **on IO, before the bind** — the pre-open rule. Measured on the Nomad:
   a cold create is **3 123 ms** end to end and a warm one **114 ms**. A 27× difference is what a
   cold SQLCipher KDF costs, and it must never sit inside a call's timeout window.
2. Mint one `ExtensionStoreBinder` bound to the extension's uid.
3. `ExtensionBinder.hold` (signature re-checked at bind).
4. `begin(store)` under `CALL_TIMEOUT_MS` (2 s). Measured: **47–57 ms**, first run included — it
   creates the pad's first blank page.
5. Return the screen `Intent` (or **null** on any failure, reason logged, everything opened so far
   released — the caller shows the core's own dialog). Returning the Intent rather than a boolean
   plus an accessor keeps it one call: the caller launches exactly what it got.

`finish()` — `end()` best-effort under the same 2 s, then `close()` + `revoke()` in `finally`.
Idempotent, and the caller runs it from its **result callback and** from `onDestroy` while still
open, because a bind must not outlive the screen that opened it even when the result never comes.

`send` / `drainOutgoing` (J5) are the two transfers' host half. Two rules are built into them:

- The **last** `receiveInk` chunk carries the whole placement — a read, decode, re-encode and write
  of up to 4 MiB on an e-ink CPU — so it takes `PLACE_TIMEOUT_MS` (10 s), not the 2 s of every other
  call. A Binder call cannot be cancelled: a budget that is too short reports failure for ink that
  then lands anyway.
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
page. `receiveInk` accumulates chunks under **one monitor** (`begin` and `end` take the same one, so
a host that restarts mid-transfer can never interleave with a placement), re-checking the running
totals against the transfer caps as it goes — the untrusted-input half of the host's own
before-any-bind check — and on `last` mints fresh ids and places through `ScratchStore.receive`,
still on the Binder thread. `takeOutgoing` hands back one parked chunk; an index past the end is an
**empty bundle, not an error**, because "done" is exactly what the host is asking about and it
probes one chunk past the budget on purpose.

Only exceptions that survive Binder marshalling are ever thrown from a stub method — anything else
kills the transaction silently and the caller reads an empty reply as success. Through J3 the two
transfer methods threw `UnsupportedOperationException` for that reason (`EX_UNSUPPORTED_OPERATION`
crosses intact); J5 replaced them with the real implementations, whose refusals are
`IllegalArgumentException` (over the caps) and `IllegalStateException` (`SCRATCH_PAGE_FULL`, store
gone).

`ScratchStore` is the pad's key layout over `IExtensionStore`, and the extension's only storage:

| Key | Value |
|---|---|
| `pages` | UTF-8, one page id per line, in order |
| `current` | the current page id |
| `page/<id>` | the page blob (`ScratchPageCodec`) |

It is **blocking** — IO thread or the Binder thread, never Main. Values at or below
`STORE_MAX_INLINE_BYTES` go through `put` / `get`; above that through `putLarge` / `getLarge`.
`readPage` tries `get` first and falls to `getLarge` **only** on the contract's exact
`STORE_VALUE_LARGE` message. A blob over `STORE_MAX_VALUE_BYTES` is `PageFullException` — never
split, never written elsewhere; every other store failure is `StoreUnavailable`.

`ScratchPageCodec` is a page blob ⇄ `(pageWidth, pageHeight, strokes)`: a small header plus the
`.soil`'s own `StrokeCodec` format-B blob per stroke, so a page is that format with a header on it.
Three rules matter and each has a test:

- **`strokeBytes` is exact.** The 4 MiB full rule keeps a *running* encoded size rather than
  re-encoding the page on every stroke, so `HEADER_BYTES + Σ strokeBytes` must equal
  `encode(...).size` to the byte. Geometry is zlib-compressed **per stroke**, so a moved stroke has
  to be re-measured — "floats re-encode to the same size" is false, and a test caught it in Paper.
- **A truncated tail keeps what decoded whole.** The partial stroke is dropped; the page still opens.
- **An unknown version is unreadable, not empty.** It throws, so the caller can say "unreadable" —
  a blank page saved over real ink is the failure this prevents.

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
to happen:

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
[`docs/scratchpad.md`](scratchpad.md); what belongs here is which side is allowed to trust what:

| | Notebook → pad (`receiveInk`) | Pad → notebook (`takeOutgoing`) |
|---|---|---|
| Capped **before any bind** | `TransferCaps.withinLimits` in the host | — (the reply is bounded by the drain) |
| Capped **on receipt** | the service re-checks the running totals across chunks | `TransferCaps.Drain` — summed caps, chunk budget, one probe past it |
| Validated at unmarshal | `WireStroke` / `InkBundle` `requireValid` (a malformed stroke rejects the whole bundle) | the same, host-side |
| Sanitized | `ScratchInk.toStrokes` — unknown style → PEN, width clamped | `TransferCaps.sanitize` — the same, **plus colour forced opaque black** |
| Ids | minted by the extension | minted by the host |
| Failure | `SCRATCH_PAGE_FULL` refuses the **whole** placement — nothing placed, nothing inserted | a cut drain is reported, never silently truncated |

The two mappings are deliberate **twins** (`TransferCaps` host-side, `ScratchInk` extension-side)
rather than one shared class: `:sn-screen` never sees `:extension-api`, and keeping the twin is what
keeps that seam honest.

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

## Boundary audit

What crosses the process boundary, in which direction, and what guards it. **Re-walk this table
whenever a point is added or a contract field changes.** Rows 1–5 are the scratch-pad point, walked
against the code at the arc-11 freeze (2026-08-25) on the shape Paper's rows 28–32 established. Rows
6–8 are the exporter point, walked against the code at the arc-15 freeze (2026-08-27). Rows 9–11 are
the importer point, walked against the code at the arc-16 freeze (2026-08-28). Rows 12–13 are the
exporter point's arc-18 growth — the source-kind seam and the one deliberate secret crossing —
walked against the code at the arc-18 freeze (2026-08-30).

| # | The claim | Where it holds |
|---|---|---|
| 1 | **Outward on `begin` is the uid-bound store binder only.** `begin(store)` is the held bind's opening call and its one argument: an `ExtensionStoreBinder` minted in `ScratchPadClient.open` **after** `ExtensionStores.open` on IO (the pre-open rule), bound to `getPackageUid(ref.packageName)`, gated by `ExtensionStoreGate.check()` on every method, held for the showing in `ScratchSession.store` and revoked in the same `finally` as the unbind — on every path: result, cancel, caller `onDestroy`, failed `begin`. `IExtensionStore` still has no method that could return a key, path or `File`. Nothing else reaches the extension at open: the Intent is the action + `setPackage` + two booleans — no key, path, name, notebook or page id. | `ScratchPadClient.open/finish`, `ExtensionBinder.hold` / `HeldBinding`, `ExtensionStoreBinder`, `ExtensionStoreGate` (JVM-tested), `ScratchPadService.begin/end`, `ScratchSession` |
| 2 | **Outward ink is bare geometry + width + colour + style name + the page px size — capped and chunked before the bind.** `InkBundle(strokes, pageWidth, pageHeight)` with `WireStroke` = four parallel `FloatArray`s + `width` + `colorArgb` + the `StrokeStyle` **name**. `TransferCaps.toWireStrokes` is the one reduction site from a g-paper `Stroke` (id and time never leave; point-less strokes skipped); `placement` is one of two recorded ints. **No stroke id, page id or number, notebook id or name, or selection bounds has a parameter to travel in** — `IScratchPad` has no other argument. Host side, before any bind: `withinLimits` → the "too much to send" dialog, then `InkChunks.chunk`. Extension side: `requireValid` at unmarshal, the running totals re-checked under one monitor, the placement int checked, fresh ids minted, the page written on the Binder thread under the full rule. | `IScratchPad.aidl`, `WireStroke` / `InkBundle` (parcel + `requireValid`, JVM-tested), `TransferCaps.withinLimits/chunk/toWireStrokes`, `InkChunks`, `ScratchPadClient.send`, `ScratchPadService.receiveInk`, `ScratchInk.toStrokes`, `ScratchStore.receive` |
| 3 | **Inward ink is validated, capped and fresh-id'd; the paste is one undoable step and nothing else on the page changes.** Every reply is an `InkBundle` → `requireValid` at unmarshal, then `TransferCaps.sanitize` (known style or PEN, width in 0.5–50 px, **colour forced opaque black**) under `Drain`: stop at the first empty bundle, at the summed caps, or at `TRANSFER_MAX_CHUNKS` + one probe past it (a non-empty chunk there = truncated → the "not everything came back" dialog, naming the pasted count). Fresh ids are minted host-side (`toStrokes`, `timeMillis 0`); `NotebookSession.pasteStrokes` writes the rows in **one transaction** with `"order"` rebased inside it, and `NotebookActivity` records **one** `Action.ObjectsPasted` and leaves the strokes selected. No other row, object, page or session state is touched; a failed write → a dialog, nothing pasted, and a drain that fails or brings back nothing gets its own dialog rather than a silent return (J6). The bind is finished **after** the paste callback, never before it. | `IScratchPad.aidl`, `InkBundle.requireValid`, `TransferCaps.sanitize/toStrokes/Drain` (JVM-tested), `ScratchPadClient.drainOutgoing`, `ScratchPadEntry.onResult`, `NotebookSession.pasteStrokes`, `NotebookActivity.pasteFromPad` |
| 4 | **The screen is the extension's, launched only by the core, caller-checked both ways; data never rides the Intent.** `ScratchPadActivity` is exported under `ACTION_SCRATCH_PAD_SCREEN` with `<category DEFAULT>` and **no launcher filter**; `HostCallerCheck.enforceActivity` is the first statement in `onCreate` (host package **and** `SIGNATURE_MATCH`, else `finish()` before anything is inflated). The core launches it only through an `ActivityResultLauncher` with `setPackage` from a trusted `ProviderRef`, and only after `begin` succeeded and (on a paper-hosting caller) `releaseForHandoff()`. The Activity reads only the two booleans and returns only `RESULT_SCRATCH_SEND` / `RESULT_CANCELED`; ink goes through the service, pages through the store binder. Every exit runs `releaseForHandoff()` before `finish()`. Verified on the Nomad every phase: a shell `am start` is `refused caller (none)`. | `ScratchPadActivity.onCreate` / `finishWithHandoff` / `onResume`, `HostCallerCheck.enforceActivity`, the `:ext-scratchpad` manifest, `ScratchPadClient.open`, `ScratchPadEntry` (`ActivityResultLauncher`, `beforeLaunch`) |
| 5 | **The store caps change no trust rule.** A value is ≤ `STORE_MAX_VALUE_BYTES` (4 MiB): **inline** up to `STORE_MAX_INLINE_BYTES` (512 KiB); above that as a `LargeValue` — a read-only ashmem region + `byteCount` the receiver copies out of and closes in `finally`, host side through `SharedBytes.readAndClose` **before** the gate sees bytes, so the cap applies to the copy and never to a live mapping. Keys are still bounded, every method is still uid-bound and revocable through the same gate, and the DB is still opened only through `SoilCrypto` under the global key. On a **new-page** placement the ink is written before the page list names it and a failed list write takes the orphan blob back out, so "nothing was sent" is never contradicted by a stray blank page (J6). **A page over the cap is refused by the extension, never split, never written elsewhere:** `PageFullException` → `SCRATCH_PAGE_FULL` on `receiveInk` (the host's dialog; nothing placed) or the pad's own dialog once per visit on a stroke the page cannot take. The pad has no file, prefs or second store of its own. | `ExtensionContract.STORE_*`, `IExtensionStore.aidl`, `LargeValue`, `SharedBytes`, `ExtensionStoreBinder`, `ExtensionStoreGate` (JVM-tested), `ScratchStore`, `ScratchDocument`, `ScratchPadActivity` |
| 6 | **Outward on `export` is two fds and a bounded spec with no secret, no id and no path.** The call's only arguments are a read `ParcelFileDescriptor` (the host's own already-keyed cache artifact), a write `ParcelFileDescriptor` (the SAF destination the host opened) and an `ExportSpec` — an id → value map (each value ≤ `MAX_SPEC_VALUE_CHARS`, 64, a choice id or `"0"`/`"1"`, never free text) plus a display-only `notebookName` (≤ `MAX_NAME_CHARS`, 200; its constructor refuses `/` and NUL, so it cannot carry a path). **No notebook id, no file path, no passphrase has anywhere to ride** — the reserved keying option's chosen choice id crosses; the typed secret behind it never does, because `ExportOptions.specValues` never writes an entry for a `KIND_PASSPHRASE` option. | `INotebookExporter.aidl`, `ExportSpec` (constructor `require`s, JVM-tested), `ExportOptions.specValues`, `ExportNaming.specName`, `ExportActivity.runExport`, `ExporterClient.export` |
| 7 | **Inward is bounded descriptors and a byte count verified before success is believed.** `describe()`'s `ExporterInfo` and its `OptionDescriptor` list are capped at unmarshal (`MAX_OPTIONS` 8, `MAX_CHOICES` 8, `MAX_ID_CHARS` 32, `MAX_LABEL_CHARS` 80, `MAX_FILE_EXTENSION_CHARS` 12, `MAX_MIME_CHARS` 128 — every cap pinned by `ExporterContractTest`); a descriptor over any cap, declaring an option kind the host cannot draw, or declaring the reserved keying option with a choice id the host has no transform for, **drops that exporter with a log line, never a crash** (`ExportOptions.isRenderable`, `ExportActivity.loadCandidates`). `export()`'s `ExportResult` carries only a non-negative `bytesWritten`; the host checks it against the length of the file it actually streamed (the keying transform's output, when there was one) and, where the destination provider will answer, against what that provider now reports holding — an exporter that died mid-stream, or under-reported its own copy, cannot read as success on either count. | `ExporterInfo`, `OptionDescriptor`, `ExportResult` (constructor `require`s, JVM-tested), `ExporterContractTest`, `ExportActivity.loadCandidates` / `runExport`, `ExporterClient.describe` / `.export` |
| 8 | **The keying secret's whole lifecycle is host-side.** A typed *New passphrase…* value is entered into `ExportActivity`'s own XML-static, `saveEnabled="false"` masked fields — never saved to instance state, because the system may persist that Bundle to disk and the secret has no business there — held in a private, non-persisted `typedPassphrase` var from the Export tap to the end of the flow, consumed by `ExportKeying.apply` on the local cache artifact, and cleared in the flow's own `finally` — and at the picker's cancel, the other way the flow ends. It is never written into `ExportSpec` (the reserved keying option only ever carries a choice id), never put in an Intent extra, and never logged — failure paths log the transform's exception **class name only** (`Log.w(TAG, "keying transform failed: ${e.javaClass.simpleName}")`), on the recorded principle that a transform's own message text could carry a path. A rekey armed with the fields lost to a screen rebuild is refused with its own honest dialog rather than silently falling back to Keep. | `ExportActivity` (`editPassphrase`/`editPassphraseConfirm` XML `saveEnabled="false"`, `typedPassphrase`, `onExportTap`, `runExport`), `ExportKeying.plan` / `.apply`, `activity_export.xml` |
| 9 | **Outward on `importDocument` is two fds and a bounded spec with no secret, no id and no path.** The call's only arguments are a read `ParcelFileDescriptor` on the user's picked document (opened `"r"` from the SAF URI — the extension never sees the URI itself), a write `ParcelFileDescriptor` on `cacheDir/import/incoming.soil` (a host cache file — never a Garden path), and an `ImportSpec` — an id → value map (empty this arc; capped at unmarshal like the exporter's) plus a display-only `displayName` (≤ `MAX_NAME_CHARS`; constructor refuses `/` and NUL, and `ImportNames.specDisplayName` strips to the leaf and drops both before construction — a name the parcelable cannot express degrades to `""` rather than failing the import). **No notebook id, no path, no passphrase has anywhere to ride**: the unlock prompt does not even exist until after the delivery call has fully returned and the fds are closed. | `INotebookImporter.aidl`, `ImportSpec` (constructor `require`s, JVM-tested), `ImportNames.specDisplayName`, `ImportFlow.deliver`, `ImporterClient.importDocument` (both fds closed in `finally`) |
| 10 | **Inward is a bounded descriptor and a byte count that is corroborated, never believed — and the delivered bytes stay untrusted after both.** `describe()`'s `ImporterInfo` is capped at unmarshal (`MAX_FILE_EXTENSIONS` 8, `MAX_MIME_TYPES` 8, extension charset `[a-z0-9]`, MIME shape, label cap — pinned by `ImporterContractTest` / `ImporterInfoTest`); a failing descriptor drops that importer with a log line, never a crash. `importDocument()`'s `ImportResult.bytesWritten` must equal the length of the file that actually landed, a zero-byte delivery is refused, and the count is checked against every size the source provider will report (`OpenableColumns.SIZE` + fd stat) — **corroboration, not authority**: a provider claiming *more* than landed is a truncated stream and fails; one that says nothing or less (streaming providers report stale/placeholder sizes) never overrules two agreeing first-hand counts. Passing all of that earns the bytes nothing: the probe, the unlock, the re-key with its four-part acceptance (a same-device pass-through still pays a whole-file `integrity_check`), `SafeImportId` on every manifest id, and the create-only `AncestryPlan` all still treat the file as a stranger's — and the acceptance opens ride `SoilCrypto`'s no-op corruption handler, so a hostile file is refused, never deleted. | `ImporterInfo` / `ImportResult` (constructor `require`s, JVM-tested), `ImportFlow.loadCandidates` / `deliver` / `sourceSizes`, `NotebookImport.readManifest`, `ImportKeying.toGlobal`, `SafeImportId`, `AncestryPlan` (all pure parts JVM-tested) |
| 11 | **The unlock passphrase's whole lifecycle is host-side, and shorter than export's.** A foreign file's passphrase is typed into a dialog field built with `isSaveEnabled = false` (never in a saved instance state — a secret that survives a process death is a secret on disk), returned to the flow as a local, verified on IO (`SoilCrypto.verifyPassphrase`) under the `"IMPORT"` `AttemptLimiter` bucket (its own — a wrong guess at a stranger's file never counts against the library's unlock), consumed by `ImportKeying` as an SQL literal on a local connection (`ExportKeying.sqlLiteral`, pure, pinned by test), and out of scope when the flow ends. It is never in the spec (delivery is already over by then), never in an Intent, never logged — every failure path here logs an exception's **class name only**. The device's global key follows the same path one step shorter: fetched from `KeySession` inside the flow, handed only to `SoilCrypto` / `ImportKeying` / `SoilDatabase.open`, never crossing the seam. | `ImportDialogs.passphrase` (`isSaveEnabled = false`, IME kept up — the Ratta rule), `ImportFlow.unlock` (`ATTEMPT_BUCKET`), `AttemptLimiter`, `ImportKeying` (path-free messages), `ExportKeying.sqlLiteral` (JVM-tested) |

| 12 | **A `SOURCE_PAGES` exporter receives baked pixels, never the notebook — and its success is judged per source kind.** `ExporterInfo.sourceKind` is a compatible parcel tail (`dataAvail()` — absent = `SOURCE_SOIL`, so every pre-arc-18 descriptor keeps its meaning, proven on real wire at the D1 walk; an unknown kind fails unmarshal and drops the exporter). The host does the reading with the one process that can: `ExportRender` runs `ExportArtifact.prepare`'s guard order one for one (`SoilOpenFiles` held → IN_USE, missing key, unopenable), opens **read-only** through the one `SoilDatabase.open` door and stamps nothing (not even `exportedAt`), bakes each page at its own size — template, headings, links' children, ink — one page in memory at a time, into a `PageBundle` in `cacheDir/export/` that the screen's one `finally` wipes. The container is capped before allocation on **both** sides (`MAX_PAGES` 4096 / `MAX_DIMENSION_PX` 32768 / `MAX_PAGE_BYTES` 32 MiB; magic + declared count checked), and the extension re-checks each decode against the declaration — a mismatch is a delivery failure, never a page to skip. The device key opens the notebook for reading and never leaves the host. Verification (`ExportVerification`, pure): the verbatim `bytesWritten == streamBytes` equality runs for `SOURCE_SOIL` only; `SOURCE_PAGES` is corroborated against the destination's own answers, zero bytes is never a document, `SHORT` (may delete wreckage) stays distinct from `UNCONFIRMED` (never a delete), and an unknown kind is `SHORT` — verification never defaults to trust. | `ExporterInfo` (tail + `require`s, JVM-tested), `ExporterContract.SOURCE_*`, `PageBundle` (Writer/Reader caps, round-trip JVM-tested), `ExportRender` (+ pure `plan`, JVM-tested), `ExportVerification` (JVM-tested), `ExportActivity.runExport` / `renderedPages`, `PdfAssembly.addPage` |
| 13 | **The export secret is the ONE deliberate secret that crosses any extension seam — user-typed, export-scoped, and it opens no Notesprout data.** It is a password for the *output* file (arc 18 / D2's `OPTION_PROTECT`), never the global passphrase, never derived from it, never the device key; `KIND_PASSPHRASE` keeps its never-crosses meaning and the secret is never in the spec's value map — it rides only `ExportSpec.exportSecret`, a compatible tail holding because the spec stays `export()`'s trailing argument. Host lifecycle = `typedPassphrase`'s to the letter: XML-static `saveEnabled="false"` dual fields, held from the Export tap to the flow's end, cleared at the picker's cancel and in the flow's `finally`, never in instance state / an Intent / a log line; > `MAX_EXPORT_SECRET_CHARS` (128) refused at the tap; a screen rebuilt behind the picker refuses with the honest password-lost body rather than exporting unprotected; `isRenderable` drops a descriptor declaring both rekey and protect (one block, one tenant). Extension side: `PdfExportSpec` refuses an inconsistent delivery in both directions (armed-with-no-secret; secret-nothing-asked-for) with messages that never name, quote or measure the secret; `PdfAssembly` holds it only for the pdfbox call and drops its reference in `finally`, whichever way the assembly ended. | `ExporterContract.OPTION_PROTECT` / `MAX_EXPORT_SECRET_CHARS`, `ExportSpec` (constructor `require` + tail, JVM-tested), `ExportOptions.isRenderable` / `wantsExportSecret` (JVM-tested), `ExportActivity` (`typedExportSecret`, `onExportTap`, `runExport`, `saveLauncher` cancel), `activity_export.xml`, `PdfExportSpec.require` (JVM-tested), `PdfAssembly.assemble`, `PdfExporterService.export` |

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

---

## Identity

All four extensions share one recipe; only the name and the point differ. (`:ext-soil` serves
**two** points — exporter and importer — under one identity: the user's arc-16 call was no rename,
so the label stays `NSE · Soil Export` even though it imports too. `:ext-pdf` is the second
exporter on the same point — arc 18, no new point.)

**`:ext-scratchpad`**

| | |
|---|---|
| Label | **"NSE · Scratch Pad"** (`"NSE · Scratch Pad Dev"` in debug — a build-type string override, not a suffix) |
| Package | `com.symmetricalpalmtree.notesproutsn.ext.scratchpad` (`.dev` in debug) |
| Icon | the same Tabler "puzzle" glyph as `:ext-mlkit` — the extension family reads as one thing |
| Launcher activity | **None**; the screen `<activity>` is exported under its own action with `<category DEFAULT>` (without which implicit resolution never matches it) and is refused unless launched for a result by the host |
| versionName | host lockstep: `0.1.0-ratta` (`-dev` in debug) |
| Release APK | 6.8 MB |

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
| API version | declares **2** (`sourceKind` is load-bearing for it) — an older host skips it at discovery rather than streaming a `.soil` at it; the other three extensions stay at 1 |

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
