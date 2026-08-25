# Extensions — Notesprout SN subsystem doc

Arc 3 (N0–N2), landed as arc 1's one deliberate amendment: SN ships with **no extension system**
through R0–R6, and picks up exactly **one** extension point in arc 3 — handwriting recognition —
so a different HWR engine can slot in later without touching the host. Headings and the markdown
engine that consume it are core, not extension surface; no other capability point may be added
without a fresh user decision (`apps/notesprout_ratta/CLAUDE.md`).

Fresh code. Paper's own extension arcs (`PAPER_EXTENSIONS_PLAN.md`, `PAPER_RECOGNITION_PLAN.md`,
its `:extension-api` / `:ext-mlkit`) are the shape reference — nothing is copied, and SN's AIDL is
scoped to the recognizer alone rather than Paper's broader capability set.

---

## Module layout

Four modules, SN's own Gradle root:

| Module | Type | Depends on | Holds |
|---|---|---|---|
| `:sn-screen` | Android library | g-paper (`api`) + androidx; **never** `:app`, **never** `:extension-api` | the design resources and the screen helpers both paper surfaces need — see [`sn-screen.md`](sn-screen.md) |
| `:extension-api` | Android library | nothing in `:app`, no library beyond the Kotlin stdlib (`build.gradle.kts` says so explicitly) | the AIDL (`IHandwritingRecognizer`, `InkStroke.aidl`), the hand-written `InkStroke` parcelable, `RecognizerStatus`, `ExtensionContract`, `HostCallerCheck` |
| `:ext-mlkit` | Android application (its own installable APK) | `:extension-api` + `com.google.mlkit:digital-ink-recognition:19.0.0` | `HandwritingRecognizerService`, `ModelManager`, `MlKitEngine`, `PageText`, `StrokeSegmenter`, `Dots`, `Box` |
| `:app` (`extension/` package) | part of the host APK | `:extension-api` | `ExtensionRegistry`, `ExtensionBinder`, `ExtensionCallException`, `InkCaps`, `RecognizerClient`, `RecognizerReadiness` |

`:sn-screen` is deliberately **not** in that dependency chain: it never sees `:extension-api`, so a
shared screen helper can never quietly become part of the wire contract. An extension APK depends on
both, separately.

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
        <action android:name="com.symmetricalpalmtree.notesproutsn.extension.HANDWRITING_RECOGNIZER" />
    </intent>
</queries>
```

plus `ACCESS_NETWORK_STATE`, for the readiness flow's offline pre-check (below).

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
| `API_VERSION` | 1 | must match the extension's `<meta-data>` for discovery to keep it |
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
  candidate survives only if its `<service>` is `exported`, its API-version meta-data equals
  `ExtensionContract.API_VERSION`, and `PackageManager.checkSignatures(host, candidate) ==
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

## Privacy

Recognized text is never logged on either side of the boundary — every log line in
`RecognizerClient`, `HandwritingRecognizerService`, `MlKitEngine`, and `ModelManager` carries only
counts, character lengths, and durations. `Dots.describeLine`, the one debug-only line that comes
closest to describing recognized content, explicitly logs geometry and a coarse punctuation class
("period"/"comma"/"other") rather than the text itself.

---

## Identity

| | |
|---|---|
| Label | **"NSE · ML Kit"** (`"NSE · ML Kit Dev"` in debug — a build-type string override, not a suffix on the shared resource) |
| Package | `com.symmetricalpalmtree.notesproutsn.ext.mlkit` (`.dev` in debug) |
| Icon | Tabler "puzzle," ink-black outline, at the same ×3.1 / 108dp-viewport scale as the host's own launcher glyph — same visual family in Settings → Apps and in any launcher that lists every package |
| Launcher activity | **None** — Supernote's own launcher shows the package anyway; accepted as "Ratta being Ratta" rather than worked around |
| versionName | host lockstep: `0.1.0-ratta` (`-dev` suffixed in debug), bumped together with `:app` at arc freezes |

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
