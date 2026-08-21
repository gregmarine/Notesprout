# Paper — Extensions arc 7: Link objects (`NSE · Links`)

> **This file is the project's memory across sessions for arc 7.** Context is cleared between
> phases. Everything a fresh session needs — decisions, non-goals, the contract, per-phase tasks,
> tests, status — is here or in the files this document points at. If it isn't written down here
> (or in the repo / project memory), it doesn't exist. **Read this file top to bottom at the start
> of every session**, after `PAPER_PLAN.md` (v0), the six earlier arc plans
> (`PAPER_EXTENSIONS_PLAN.md`, `PAPER_NAMING_PLAN.md`, `PAPER_RECOGNITION_PLAN.md`,
> `PAPER_OBJECTS_PLAN.md`, `PAPER_CONTENTS_PLAN.md`, `PAPER_SCRATCHPAD_PLAN.md`) and both
> `CLAUDE.md` files. `docs/extensions.md` is the subsystem reference all arcs write into;
> `docs/notebook.md`, `docs/library.md` and `docs/data.md` gain sections in this arc; a new
> `docs/links.md` (Paper's own, under `apps/notesprout_paper/docs/`) is the extension's reference.
> The **original** Notesprout implementation this arc draws on is `docs/links.md` at the monorepo
> root — inspiration, not a spec; every deviation is recorded here.
>
> **Status: L0 ✅ (df2de82) · L1 ✅ (bc944bc) · L2 ✅ (a86214c) · L3 ✅ (6822e44) · L4 ✅ (b2e71dd + b9b107f) · L5 🧪**

## Why

The original Notesprout has **link objects** (root `docs/links.md`): a lasso selection of any mix
of strokes and content objects becomes one tappable unit that navigates — to another page of the
open notebook, to another notebook (its last-open page), or to a specific page of another notebook;
a one-finger swipe up walks back along the trail of follows. Arc 7 gives Paper the same behaviour
with the semantics owned by an extension:

- **The core owns the structure.** A link is a core row type in the `.soil` (`type = "link"`) whose
  wrapped content is child rows — the core wraps and unwraps selections, renders the composite,
  gives it full lasso / move / delete / undo parity, detects the finger tap and the swipe-up, and
  performs **all navigation** itself.
- **`NSE · Links` owns the meaning.** The link row's payload (target + chrome, `text` column) is
  **opaque to the core** — written by the extension's target-picker screen (an extension-owned
  screen, the arc-6 tier-2 pattern) and interpreted only by the extension: at follow time the core
  asks it to *resolve* the payload into a typed destination description (the arc-5 "a description,
  not a parse" pattern), at render time to *describe* the chrome, and the back-trail lives in the
  extension's host-owned store behind the point's own trail methods.
- Consequences accepted in planning: with the extension **uninstalled**, existing links still
  render (content, no chrome), still move / delete / **unlink** (structural, core-owned) — but a
  follow tap gets an honest dialog and Link / Edit never appear. The picker needs library data, so
  a **catalog callback binder** (host-implemented, per-showing, uid-gated — the `ExtensionStoreBinder`
  shape grown to a read + create lens over the index) is the arc's recorded boundary widening.

That is one new extension APK (`:ext-links`), one new point (`LINK_PROVIDER` + its picker screen
action), one new AIDL callback (`ILinkCatalog`), four new parcelables, one new `.soil` row type
(**no version bump** — additive; a pre-arc-7 build ignores unknown types and never loads a link's
children because their `parentId` is the link, not a page), a new selection shape (`OneLink`),
three core toolbar actions, one new finger gesture (tap) plus the reserved swipe-up, and the
first notebook → notebook navigation in Paper.

---

## Working protocol

Identical to `PAPER_SCRATCHPAD_PLAN.md` §"Working protocol" — each phase in a **fresh session**:
phase-start no-assumption QA (read this file fully + the earlier plans' Locked decisions /
Architecture / Appendices + `docs/extensions.md`, `docs/notebook.md`, `docs/library.md`,
`docs/data.md`, `docs/crypto.md`, both `CLAUDE.md`s, and the **root** `docs/links.md`), confirm the
next `⬜` phase, flip to `🔄`, ask the phase's start questions **one at a time** in the wizard
format; code; `./gradlew testDebugUnitTest` + build + install on the three test devices; the
numbered user checklist; docs / memory / status + Outcome; commit & push on `paper`; user runs
`/clear`. Status markers `⬜ 🔄 🧪 ✅` updated the moment the state changes.

**Test devices** (unchanged, always `-s <serial>`, never a device the user didn't ask for):
SNN Supernote Nomad `SN078D10012852` (ratta) · NA5C BOOX NoteAir5C `92c16533` (onyx) ·
MIP11 Wacom Movink Pad 11 `5HL21V5007384` (core). All seven extensions installed from L0 on.
Device traps carried forward verbatim from `PAPER_SCRATCHPAD_PLAN.md` §"Working protocol"
(BOOX freeze / `pm enable`, SNN input quirks, MIP11 `log.tag`, no adb multi-finger, dump before
blind taps). **New in this arc:** cross-notebook navigation seals one `.soil` and opens another —
adb can drive it end to end (finger taps are injectable), so the per-device agents can cover more
of the checklist than in arc 6.

**Model note (user decision, 2026-08-19 — new for this arc):** any work **Opus or Sonnet can do
in a phase runs as a spawned agent instead of Fable**; Fable orchestrates and keeps the heavy
lifting where Fable genuinely earns it. In practice, per phase:

- **Sonnet agents:** read-only exploration/surveys, JVM test authoring against signatures this plan
  fixes, doc-table edits, the automated per-device verification runs (one agent per device, ≤ 2 at
  a time, checklist verbatim + traps).
- **Opus agents:** implementation of well-specified units — files whose names, signatures,
  constants and failure texts this plan (or the phase-start answers) fully pin down: the extension's
  picker UI, stores, codecs, the catalog binder's read methods, mechanical parity sweeps. Fable
  reviews every agent diff before it lands.
- **Fable inline:** contract / AIDL shape, `SelectionActions` / merge / shape changes, wrap-unwrap
  + undo semantics, `NotebookActivity` / close / navigation surgery, anything g-paper-adjacent or
  EPD-handoff-adjacent, review-finding fixes, and all boundary-audit walking.

Every phase below names files, signatures, constants and failure texts so an Opus agent can build
from it; anything genuinely undecided is a phase-start question, not an inference.

---

## Locked decisions (planning Q&A, 2026-08-19)

| Area | Decision |
|---|---|
| Scope (user brief) | Any selectable stroke or object can be wrapped in a link **except another link**. Targets: a page of the current notebook · another notebook (opens on its last-open page) · a specific page of another notebook. Chrome: **underline or none only** (the original's dashed box + icon is not built). Follow = tap; back = one-finger swipe up; a notebook opened via a link returns to the origin notebook from its Back button. Links live in the notebook's `.soil`, never the extension store. |
| Edit link (Q1) | **In scope.** A selected link offers **Edit** — the picker reopens pre-populated (target + chrome), on OK only the payload changes (wrapped content untouched), one undoable step. |
| Architecture (Q2) | **The extension owns link semantics.** Payload (`text` column) opaque to the core; the core calls `resolve(payload)` at follow time for a typed destination, `chromeOf(payloads)` at load time for the underline flag, and never parses either. The core owns rows, wrap/unwrap, composite render, gestures, navigation, undo. Declined: tier-2-picker-only (extension as a rented UI over core-typed targets) and fully-core (no extension). Recorded consequence: uninstalled extension → follows dead (honest dialog), create/edit absent; **unlink + delete + move still work** (structural). |
| Chrome default (Q3) | **Underline** for new links (the original's default). `LINK_CHROME_NONE = 0` / `LINK_CHROME_UNDERLINE = 1`; the flag crosses inside the payload and is described back via `chromeOf`. |
| Follow gesture (Q4) | **Finger-only, never stylus** — the pen always writes, even over a link. New `PageGestures` tap callback, pen-gated like every finger gesture, inert while a selection is active or over chrome. |
| Back-trail (Q5) | **Persisted in the extension's store** (`Garden/<ext-pkg>.db`) — user's own call, replacing the original's SharedPreferences. Push/pop/clear are point methods taking `IExtensionStore` as in-parameter (rule 6); entries are `{notebookId, pageId}` (a recorded outward widening); cap `MAX_TRAIL_ENTRIES = 50`; cleared on any fresh notebook open (no `EXTRA_VIA_LINK`). Swipe-up with an empty trail (or no extension) is silent, like the Contents swipe-down. |
| Create targets in the picker (Q6) | **In v1, as its own phase (L3):** new page (this or the other notebook, before/after an anchor), new notebook, new folder — over the catalog binder's create methods; host validates exactly as the library's own UI would. Picker-created things are **not undoable** (matches the original and the library); the link itself stays undoable. |
| Names + glyphs (Q7) | Extension **`NSE · Links`** (debug ` Dev`), module **`:ext-links`**, package `com.symmetricalpalmtree.notesprout.ext.links` (debug `.dev`), APK icon = the standard Tabler `puzzle`. Toolbar: **"Link"** (Tabler `link`) on linkable selections · **"Edit"** (Tabler `link-plus` or `edit` — pick at L1 against the catalog) + **"Unlink"** (Tabler `link-off`) + Delete on a selected link. Plan file `PAPER_LINKS_PLAN.md`; Paper doc `docs/links.md`. |
| Phases (Q8) | **Six**: L0 contract + skeleton · L1 core link rows · L2 the picker (existing targets) · L3 create-in-picker · L4 follow + trail · L5 review + freeze. Each a fresh session, verified on SNN + NA5C + MIP11. |
| Trust / artifacts / version | Unchanged: same-signature both directions (the picker Activity checks its caller), debug-only APKs, no app version bump, `ExtensionContract.API_VERSION` stays **1** (a new point is a compatible addition), `SOIL_VERSION` stays **1** (`TYPE_LINK` is additive), g-paper stays **0.1.3** unless a phase finds a gap (recorded there — the tap callback is host-side `PageGestures`, not g-paper). |

## Deferred (recorded 2026-08-19, not built in this arc)

- **Dashed-box + icon chrome** (`DOTTED_CHEVRON`) — user excluded; underline or none only.
- **File / website / URI targets** — the original also excluded them from its phase 1.
- **Page thumbnails or covers in the picker** — labels only; nothing rendered crosses the boundary.
- **Outline labels for *other* notebooks' pages** in the picker (would mean opening + outlining
  every browsed notebook); the current notebook's pages DO show outline labels (L2 Q2 — accepted).
- **A search/filter field in the picker's notebook list** (L2 Q3 — deferred; the library itself has
  no search yet, and it would bring the IME onto the picker before L3 needs it).
- ~~**Eraser parity with the original** (hardware/scribble eraser deleting a whole link)~~ —
  **reversed at L1 start (Q2)**: the eraser erases whole links *and* whole objects (headings) via
  g-paper 0.1.4 `onContentErased`; only scribble-erase stays content-immune (Paper has it off).
- **Trail entries surviving the extension's uninstall** — the store `.db` survives, so they come
  back with a reinstall; nothing migrates them out.
- **A "links on this page/notebook" index or search** — nothing lists links anywhere.
- **Warm-bind of the links extension at notebook open** — decided at L4 if tap latency needs it.
- **The Extensions-UI-arc items** (consent, per-extension store deletion, `HOST_PACKAGE` meta-data) —
  unchanged.

## Non-goals for this arc (do not build, do not scaffold "for later")

No `.soil` version bump · no colour · no chrome beyond the 1 dp underline · no stylus follow ·
no link-inside-link (guarded at creation) · no export treatment of links (they export as their
rendered content wherever pages already export) · no launch-restore of a mid-trail state beyond
what the persisted trail gives · no release signing · no `kotlinx.serialization` in the extension
(payload is its own tiny hand format, JVM-tested) · no new Gradle dependency beyond Appendix B ·
no g-paper change unless a phase records one.

---

## Architecture

### Module layout (delta)

New module **`:ext-links`** (the seventh extension APK). Dependencies: `:extension-api` **+
`:paper-screen`** (L0 wizard Q1 — accepted like the pad: the picker is a full e-ink screen and
wants the design system — theme, dimens tiers, `Dialogs`, `ActionSheetDialog`, Tabler drawables;
`:paper-screen` carries g-paper (`api`) and therefore the Onyx SDK, ~25 MB debug APK, and the
module's g-paper surface classes simply go unused; the `:paper-style` resources-only split stays
the recorded escape hatch if the size ever matters). Gradle enforcement grows the same way as
arc 6: `:ext-links → :extension-api` + `:paper-screen`, never `:app`.

### Contract additions (`:extension-api`) — exact

Constants (`ExtensionContract`, names final — values follow the existing action-string pattern):

- `ACTION_LINK_PROVIDER` (service intent action) · `ACTION_LINK_PICKER_SCREEN` (activity action,
  resolved with `setPackage`, `<category DEFAULT>`).
- `MAX_LINK_PAYLOAD_CHARS = 2_000` (both ways; the host truncates nothing — an over-cap payload is
  a refused result, `requireValid`).
- `LINK_CHROME_NONE = 0` · `LINK_CHROME_UNDERLINE = 1`.
- `DEST_PAGE = 0` · `DEST_NOTEBOOK = 1` · `DEST_NOTEBOOK_PAGE = 2`.
- `CATALOG_FOLDER = 0` · `CATALOG_NOTEBOOK = 1` · `CATALOG_PAGE = 2`.
- `MAX_CATALOG_ENTRIES = 2_000` (per reply) · `MAX_CATALOG_LABEL_CHARS = 200`.
- `MAX_TRAIL_ENTRIES = 50`.
- `RESULT_LINK_PICKED = 1` (activity result; anything else = cancelled).
- `EXTRA_LINK_EDIT = "editMode"` (Boolean — the only extra; data never rides the Intent).
- `IconNames.LINK`, `IconNames.LINK_OFF` (+ `LINK_PLUS`/`EDIT` per the L1 glyph pick) join the
  catalog names (core toolbar uses the drawables directly; the names exist for symmetry and hints).

Parcelables (each with `requireValid` enforced at unmarshal; a malformed parcelable rejects the
whole reply, the row-21/26 rule):

- `LinkDestination(kind: Int, notebookId: String?, pageId: String?)` — kind ∈ {0,1,2};
  `DEST_PAGE` → pageId only; `DEST_NOTEBOOK` → notebookId only; `DEST_NOTEBOOK_PAGE` → both.
  Ids non-blank, ≤ 64 chars.
- `LinkChoice(payload: String, chrome: Int)` — payload non-blank ≤ `MAX_LINK_PAYLOAD_CHARS`,
  chrome ∈ {0,1}. (Chrome also lives inside the payload; it rides `LinkChoice` so the core can
  draw the underline immediately without a `chromeOf` round trip at creation.)
- `CatalogEntry(id: String, kind: Int, label: String)` — id non-blank ≤ 64, kind ∈ {0,1,2},
  label ≤ `MAX_CATALOG_LABEL_CHARS` (blank allowed for a page with no name — the picker shows
  "Page n" from position).
- `TrailEntry(notebookId: String, pageId: String)` — both non-blank ≤ 64.

**`ILinkProvider.aidl`** (transaction order fixed forever; declared in full at L0):

```
1  void beginPick(IExtensionStore store, ILinkCatalog catalog,
                  String currentNotebookId, String editPayload)   // editPayload null = create
2  LinkChoice takeResult()                                        // null = cancelled
3  void endPick()
4  LinkDestination resolve(String payload)                        // null = payload unusable
5  int[] chromeOf(in List<String> payloads)                       // same order/length; 0|1
6  void pushTrail(IExtensionStore store, in TrailEntry entry)
7  TrailEntry popTrail(IExtensionStore store)                     // null = empty
8  void clearTrail(IExtensionStore store)
```

**`ILinkCatalog.aidl`** (host-implemented callback, handed only in `beginPick`, dead outside the
showing; declared in full at L0, create methods `UnsupportedOperationException` until L3):

```
1  List<CatalogEntry> listFolder(String folderId)      // "" = root; alive folders + notebooks, library order
2  List<CatalogEntry> listPages(String notebookId)     // page rows in order, id + label
3  String createPage(String notebookId, String anchorPageId, boolean before)  // → new page id (L3)
4  String createFolder(String parentFolderId, String name)                    // → new folder id (L3)
5  String createNotebook(String parentFolderId, String name)                  // → new notebook id (L3)
```

Create methods throw `IllegalArgumentException(message)` on refusal (invalid name, duplicate,
dead parent) — the message is user-honest, shown by the picker in a `Dialogs.problem`.

Timeouts (host-enforced): bind ≤ 3 s · `beginPick`/`takeResult`/`endPick`/`resolve`/`chromeOf`/
trail methods ≤ 2 s each. Catalog calls run ext→host on Binder threads (host side `runBlocking`
off Main, the proxy precedent); the extension shows its own progress while it waits — the host
sets no timeout on its own catalog work but keeps `listPages` of a cold encrypted notebook honest
(risk register 4).

### The point's shape

Two usage modes on one service (both bind-per-operation in the recorded sense):

- **The pick showing** (create + edit): the arc-6 held-bind recipe verbatim — store pre-opened on
  IO, `LinkCatalogBinder` + `ExtensionStoreBinder` minted per showing, `ExtensionBinder.hold` →
  `beginPick` ≤ 2 s → screen launched with an `ActivityResultLauncher` only (caller-checked
  `HostCallerCheck.enforceActivity`; `am start` refused) → on any result `takeResult` ≤ 2 s on the
  still-held bind → `endPick` → unbind → **revoke both binders** in one `finally` (result callback
  and caller `onDestroy` both funnel there). The picker is a plain screen (no paper surface), so
  **no `releaseForHandoff()` is needed** — but L2 verifies pen-idle chrome rules on return anyway.
- **The one-shot calls** (`resolve`, `chromeOf`, trail): `ExtensionBinder.call` — bind, one call,
  unbind in `finally`, exactly like `RecognizerClient`. Trail calls pre-open the store on IO first
  (cold KDF outside the 2 s window).

### Extension side — `:ext-links` (`NSE · Links`)

- `LinksApplication` (nothing to register — no paper surface).
- `LinkProviderService` (`ILinkProvider.Stub`): `HostCallerCheck.enforce` first line of every
  method; a single `PickSession` singleton (catalog + store + edit prefill + result slot) bracketed
  by `beginPick`/`endPick`; `resolve`/`chromeOf` pure over the payload codec; trail methods over
  `TrailStore`.
- `LinkPayload` — the payload codec, a tiny hand format (no serialization dep):
  `"L1|<chrome>|<kind>|<notebookId>|<pageId>"` (exact grammar + escaping fixed at L0; versioned
  leading tag so a future arc can extend it). JVM tests: round trip, malformed → null, over-cap
  refused, unknown version → `resolve` null (the core shows the dead-link dialog — honest, not a
  crash).
- `TrailStore` — trail entries in the host store under key `trail` (one value, newest-last binary
  list, cap 50 drops oldest; `requireValid` on the way out). JVM-tested with a fake store.
- `LinkPickerActivity` — the picker screen (L2): caller check first in `onCreate`; three-mode
  target chooser + chrome toggle (Underline / None, default per `EXTRA_LINK_EDIT` prefill or
  UNDERLINE); browse via `PickSession.catalog`; OK composes the payload via `LinkPayload`, parks
  the `LinkChoice` in the session, `setResult(RESULT_LINK_PICKED)`, `finish()`; Cancel finishes
  plain. L3 adds the create buttons.

### Host side (`:app`)

- `ExtensionRegistry.linkProvider` (first trusted service for the action; `<queries>` entry).
- `extension/LinkClient` — `openPick(editPayload: String?): Intent?` · `takeChoice(): LinkChoice?`
  (drain + endPick + close/revoke in `finally`) · `resolve(payload): LinkDestination?` ·
  `chromeOf(payloads): IntArray?` · `pushTrail(entry)` / `popTrail(): TrailEntry?` / `clearTrail()`.
- `extension/LinkCatalogBinder` (`ILinkCatalog.Stub`) — uid-gated + revocable (the
  `ExtensionStoreGate` shape): `listFolder` / `listPages` read the index / the target `.soil`
  (names, ids, labels — **never** keys, paths, covers, blobs); the current notebook's pages come
  from the live session, other notebooks are opened read-only via `SoilCrypto` + `KeySession` on
  IO and closed in `finally`; create methods (L3) route through the same validation the library
  uses. Never touches the origin notebook's open `.soil` from a second connection.
- `notebook/LinkStore` — the `.soil` structural side over `SoilWriter` (see Data model).
- `notebook/LinkFlow` — the screen collaborator (`NotebookActivity` is at its line cap): toolbar
  actions + guards, the pick flow (launcher, result → create/edit), unlink, follow (tap → resolve
  → validate → trail → navigate), swipe-up back, trail clear on fresh open, every failure dialog
  (core `links_*` strings).
- `notebook/LinkRenderer` work folds into `ObjectRenderer`'s pass (one `ContentRenderer` — links
  join `hitTargets` and the cached-bitmap draw; composite built core-side).
- `SelectionActions`: new `Shape.OneLink`; core actions `CORE_LINK_ID = "link"` (`appliesTo = ALL`,
  present only while the extension is installed **and** the selection contains no link),
  `CORE_LINK_EDIT_ID = "link_edit"` (OneLink, extension-gated), `CORE_LINK_UNLINK_ID = "link_unlink"`
  (OneLink, **not** extension-gated). Mixed keeps Delete (+ Link when eligible).

### Data model — the link row and its children

- `SoilSchema.TYPE_LINK = "link"`. Link row: `parentId` = page id · `text` = opaque payload ·
  `style` = the providing extension's identity (`<pkg>:link`, provenance only — the core never
  routes on it) · `x/y/width/height` = union bounds of the wrapped content + 2 dp bottom clearance
  for the underline · `"order"` = z-order (`MAX+1` like objects) · `flags` = **null** (L0 wizard
  Q4 — the heading precedent: nothing extension-derived is persisted; the chrome comes from
  `chromeOf` at load, session-cached, and a link with the extension missing renders its content
  with no underline).
- **Wrap = re-parent, not copy** (recommended; L1 question 1): the selected stroke + object rows'
  `parentId` flips page → link id in one transaction; coordinates stay page-absolute; unlink flips
  them back. No id churn (the original's UNIQUE-collision family can't happen), and undo of
  create/unlink is the same flip. Copy/paste of a link (if/when links join the clipboard) must
  deep-copy with fresh ids — recorded, not built (Paper has no clipboard yet).
- A page's load path (`liveChildIds` / `objectsOf`) keeps ignoring link children automatically
  (their `parentId` is the link). **Page delete / undo must cascade one level deeper**: the
  `Structural` snapshot's `childIds` grows the links' children (grandchildren of the page) —
  `LinkStore.deepChildIds(pageId)`; JVM-tested.
- Move: dragging a selected link translates the row bounds **and** its children rows (stroke blobs
  re-encoded via the existing translate path; objects' x/y shifted) in one step; `Action.Moved`
  replay covers it.
- Render: the composite bitmap = children strokes rasterized core-side (the cover-snapshot raster
  path — exact helper confirmed at L1 start) + child **objects'** rendered bitmaps (the normal
  provider render pass, run for link children too, composited at their page-absolute offsets) +
  the 1 dp inkBlack underline across the bounds' bottom when chrome = underline. Cached in
  `ObjectRenderCache` keyed like objects; a link with an absent object provider inside shows that
  child as the standard dashed placeholder within the composite.
- New undo actions (`NotebookUndo.Action`): `LinkCreated(pageId, linkRow, childIds)` ·
  `LinkUnlinked(pageId, linkRow, childIds)` · `LinkEdited(pageId, id, beforePayload, afterPayload,
  beforeChrome, afterChrome)`; links additionally ride `Moved` (ids in `objectIds`),
  `ObjectsDeleted` (a `links` list), and `Page`.

### Navigation (core-owned, L4)

- **Tap**: `PageGestures.Listener` gains `onFingerTap(x: Float, y: Float)` (default no-op) —
  single finger, sub-slop, under the long-press timeout, pen-gated, selection inactive, not over
  chrome. `LinkFlow.followAt(x, y)`: topmost link row whose bounds contain the point (by z-order),
  else ignore.
- **Resolve → validate → trail → go**: `LinkClient.resolve(payload)`; null / dead target (index
  `alive` / page-row check) → `Dialogs.problem(links_target_gone)`; no extension →
  `Dialogs.problem(links_required)`. On success `pushTrail(TrailEntry(origin notebook, origin
  page))`, then:
  - `DEST_PAGE` (same notebook): `navigateTo(index of pageId)`.
  - `DEST_NOTEBOOK` / `DEST_NOTEBOOK_PAGE`: the close seal sequence (cover → lastOpened → meta →
    seal), then `startActivity(NotebookActivity.intent(...) + EXTRA_VIA_LINK = true
    [+ EXTRA_INITIAL_PAGE_ID])`, then `finish()` — the stack stays Library → Notebook, one live
    session at a time. The "Opening…" popup on the target covers the transition; the origin shows
    the standard close immediately.
- **`EXTRA_VIA_LINK`** (Boolean) + **`EXTRA_INITIAL_PAGE_ID`** (String) on `NotebookActivity`:
  via-link present → the trail survives and the Back button walks it; absent (fresh open from
  library / recents) → fire-and-forget `clearTrail()` when the extension is installed.
  `EXTRA_INITIAL_PAGE_ID` overrides the notebook's own last-open `refId` for this open only.
- **Swipe-up** (`PageGestures` gains `onSwipeUp()`, the mirror of `onSwipeDown`'s thresholds):
  `popTrail()` → null = silent; same notebook → `navigateTo`; other notebook → seal + relaunch
  with `EXTRA_VIA_LINK` (walking back **also** keeps the flag — only a fresh open clears). A dead
  popped entry is skipped and the next is popped (L4 question 2 confirms), until the trail is
  empty.
- **Back button / system back** in a via-link notebook: pop + walk exactly like swipe-up; when the
  trail is empty (or the extension is gone) the normal close-to-library runs.

### Rules followed and added

Rules 1–27 all apply (this arc exercises: the store rules 6–9 for the trail, the screen rules
25–26 for the picker — 27 is n/a, no second paper surface). **New rules recorded in L5**
(§"Adding a navigation point (arc 7 pattern)", numbering continues):

- **28 — A destination is a description.** The extension names index ids in a typed parcelable;
  the core validates them against the index / the `.soil` and performs all navigation itself.
  No Intent, component, path or URI ever crosses the boundary in either direction.
- **29 — A catalog binder is a per-showing lens, not a door.** Host-implemented, uid-gated,
  revoked with the showing; outward = names + ids + labels of alive rows only (never keys, paths,
  covers, blobs); its mutation methods enforce exactly the validation the host's own UI enforces,
  and exist only while a pick showing is open.
- **30 — The payload is the extension's; the structure is the core's.** The core stores, wraps,
  renders, moves and deletes the link and its children without ever parsing the payload;
  everything semantic is asked of the extension as a description (`resolve`, `chromeOf`) and is
  untrusted + validated inward. Structure keeps working when the extension is gone.
- **31 — A trail is extension data.** It lives in the host-owned store, is touched only through
  point methods that take the store as an in-parameter, is capped, and every entry is validated
  on the way back before the core navigates anywhere.

Boundary-audit rows **33+** (L5): the catalog binder (outward names/ids/labels; create validation),
`resolve`/`chromeOf` inward validation, the trail store round trip, the picker showing's
held-bind + two-binder revoke, `EXTRA_VIA_LINK`/`EXTRA_INITIAL_PAGE_ID` staying host-internal
(they ride the core's own Intent to its own Activity — never an extension's), plus the rows-1/6/7
re-walk for `LinkClient`.

---

## Phases

### Phase L0 — Contract · `:ext-links` skeleton · client (discovered, held, probed)
**Status:** ✅ Complete (commit df2de82; user checklist all green on SNN/NA5C/MIP11 2026-08-19)

**Outcome (2026-08-19).** **The L5 review base is `0f91ed5`** (the commit before L0's first).
Everything delivered as specified; deviations and findings:
- **Wizard answers** recorded above (Q1 `:paper-screen` accepted · Q2 grammar confirmed · Q3 probe
  yes · **Q4 no persisted chrome cache — the heading precedent**, which also rewired L1 deliverable 3
  to a pure session cache and made "extension missing → content renders with no underline" the
  recorded behaviour, matching the Why section's original consequence).
- **Model protocol worked as planned:** Fable inline = contract/AIDL, the four parcelables +
  constants, `LinkClient`, `LinkCatalogBinder`/`LinkCatalogGate`/`LinkCatalogSource`,
  `ExtensionRegistry.linkProvider` + `<queries>`, `IconCatalog` + the two Tabler drawables (copied
  into `:paper-screen` from the original app — they were **not** already there), the debug probe +
  `NotebookActivity` wiring (4 lines; the file is at 799 — L1 must move logic out before adding).
  One Opus agent built `:ext-links` (module, manifest, icon, `LinkProviderService`, `LinkPayload`,
  `TrailStore`, stub `LinkPickerActivity`, 9 JVM tests); one Sonnet agent wrote the four parcelable
  test files; Fable reviewed both diffs (no findings). 43 new JVM tests total; all ten modules green;
  debug + release compile; ext-links debug APK ≈ 23.6 MiB.
- **Small calls recorded:** the picker stub uses `TopGuard.applyInsetPadding` (an ordinary screen
  with system bars — `applyRootPadding` is for immersive screens; flip it if L2 goes immersive).
  The extension-side `listFolder("")` probe half lives in `beginPick` under the ext module's own
  `if (BuildConfig.DEBUG)` (count only) — remove with the probes in L5. `LinkCatalogGate` is its own
  small gate class (the `ExtensionStoreGate` shape) rather than a `ProxyGate` reuse, so the catalog
  binder's caps (`entry` label truncation, `cap` at `MAX_CATALOG_ENTRIES`) sit beside the uid check
  and are JVM-tested together (`LinkCatalogGateTest`).
- **Claude-side device runs (one Sonnet agent per device): all 9 checks PASS on SNN, NA5C and
  MIP11** — probe sequence exact (`root has 1/1/2 entries`; cold `beginPick` 3.0 s SNN / 1.0 s NA5C /
  1.1 s MIP11 — the extension process start; warm one-shots 11–14 ms); binds = unbinds, no leaked
  connections; `Garden/com.symmetricalpalmtree.notesprout.ext.links.dev.db` created encrypted
  (header verified non-plaintext) on all three; `am start` of the picker refused
  (`refused caller (none)`, nothing resident); probe re-run after `am force-stop` of the extension
  identical from a fresh PID (the trail's data is host-owned — the strict push→kill→pop split isn't
  drivable with the L0 probe shape; L4's device items cover mid-chain force-stop for real); page
  flip + crash-buffer regression clean. The BOOX had re-disabled `ext.scratchpad.dev` at install
  time (the known trap) — re-enabled; `ext.links.dev` stayed enabled through both agent runs.
- **Docs:** `docs/extensions.md` header + constants table + AIDL + parcelables +
  §"LinkProvider (contract)" (with the L0 state paragraph); paper `CLAUDE.md` ten modules +
  `:ext-links` edges + the Links bullet; the `device-build-install` skill's install lines.
  **User checklist: all four items green on all three devices (2026-08-19).**

**Goal:** the point exists end to end with nothing user-visible: contract compiled into
`:extension-api`, `NSE · Links` installs and is discovered, `LinkClient` can hold a pick showing
(`beginPick` with a live catalog binder → `takeResult` null → `endPick`) and one-shot `resolve` /
`chromeOf` / trail calls round-trip, all proven by a debug ⋯ probe.

**Questions resolved at phase start (wizard, 2026-08-19):**
1. **`:ext-links` depends on `:paper-screen`** (accepted like the pad — one visual vocabulary,
   ~25 MB debug APK; the `:paper-style` resources-only split stays the recorded escape hatch).
2. **Payload grammar confirmed:** `"L1|<chrome>|<kind>|<notebookId>|<pageId>"` — versioned leading
   tag, `|` separator (forbidden in ids — they are UUIDs), chrome `0|1`, kind `0|1|2`, unused id
   slots empty. Unknown version → `resolve` null → the core's dead-link dialog.
3. **Debug ⋯ "Probe links": yes** — hold → `beginPick` (store + catalog + null edit; the extension
   logs the root count via `listFolder("")`) → `takeResult` (null) → `endPick`; then one-shot
   `resolve` + `chromeOf` of a fixed payload; then a trail push/pop/clear round trip. Logs only,
   removed in L5.
4. **No `flags` chrome cache — the heading precedent** (user's call: "handle this the same way the
   heading extension does"): nothing extension-derived is persisted; the link row stays 100 %
   opaque (`flags` null). `chromeOf` is asked at page load (batched, **session cache** — the
   `ObjectRenderCache` shape) and with the extension missing a link renders its content with **no
   underline** (the consequence already recorded under Why). `LinkChoice.chrome` still lets the
   core draw the underline immediately at creation — transient, never persisted.

**Deliverables**
1. `:extension-api`: `ILinkProvider.aidl`, `ILinkCatalog.aidl` (all five methods; create methods
   documented as L3-live), `LinkDestination` / `LinkChoice` / `CatalogEntry` / `TrailEntry`
   (`.aidl` + `.kt` + `requireValid` + JVM tests: round trip, each malformed shape rejected),
   the Appendix A constants, `IconNames.LINK` / `LINK_OFF`.
2. `:ext-links` skeleton: manifest (service + picker activity declared, caller-checked stub screen
   showing only a title + Back; `tools:replace` + `pickFirsts` as `:ext-scratchpad` needed if
   `:paper-screen` rides along), puzzle icon + `NSE · Links` label, `LinksApplication`,
   `LinkProviderService` (`beginPick`/`takeResult`/`endPick`/`resolve`/`chromeOf`/trail real over
   `LinkPayload` + `TrailStore`; picker UI itself is L2), `LinkPayload` (+ JVM tests), `TrailStore`
   (+ JVM tests over a fake store: order, cap-50 drop-oldest, malformed value → empty).
3. `:app`: `ExtensionRegistry.linkProvider` + `<queries>`; `LinkClient` (all methods; pick showing
   via `ExtensionBinder.hold`); `LinkCatalogBinder` with `listFolder`/`listPages` **real** (index
   reads; other-notebook `listPages` opens read-only and closes in `finally`; current-notebook
   pages from the live session), create methods `UnsupportedOperationException`; uid-gate +
   revoke JVM test (the `ExtensionStoreGateTest` shape); debug ⋯ "Probe links".
4. Docs: `docs/extensions.md` gains the contract section §"LinkProvider (contract)" (AIDL, caps,
   timeouts, the catalog binder); `CLAUDE.md` module list (ten modules) + a Links bullet; the
   `device-build-install` skill's install lines gain `ext-links`.

**Tests**
- JVM green (ten modules); debug + release compile.
- Claude-side per device (Sonnet agents): install + `pm enable` dance; probe sequence exact in
  `logcat -s LinkClient LinkProviderService`; binds = unbinds (`dumpsys activity services` clean);
  store `.db` `Garden/com.symmetricalpalmtree.notesprout.ext.links.dev.db` created encrypted;
  `am start` of the picker refused; trail survives a force-stop of the extension (persisted).
- **User device checklist:** (1) Settings → Apps shows "NSE · Links Dev" on all three; (2) probe
  runs clean on all three (Claude reads the logs, user just triggers ⋯ → Probe links); (3) 60-second
  v0 + arcs regression (open / write / flip / undo / heading / Contents / Scratch Pad round trip)
  unchanged; (4) no new button appears anywhere yet.

**Close-out:** status + Outcome (record the **L5 review base** = the commit before L0's first);
docs; memory; commit + push.

---

### Phase L1 — Core link rows: wrap, unwrap, render, parity (no picker, no follow)
**Status:** ✅ Complete (commit bc944bc; user checklist all green on SNN/NA5C/MIP11 2026-08-19)

**Outcome (2026-08-19).** Everything delivered; the wizard answers, the g-paper 0.1.4 record and
the recorded deviations are in the sections above (Build notes (a)–(j) — most notably: underline
drawn live from the session chrome map rather than baked; `Moved` carries `linkIds` separately;
wrap/unwrap reload the page instead of mirror surgery; Link/Edit toolbar taps inert until L2; no
dao-level `LinkStoreTest` — no Room JVM harness exists, pure halves in `PageLinkTest`, dao halves
device-verified). **Model protocol:** one Opus agent built `LinkStore`/`PageLink`/`LinkRows` +
`PageLinkTest` (Fable review found one fix: id-list chunking at 500 inside the transactions);
one Sonnet agent wrote the `SelectionActionsTest`/`NotebookUndoTest` additions; three Sonnet
device agents ran the drivable checks (all PASS ×3); Fable inline did g-paper 0.1.4, the
schema/DAO/session/undo/SelectionActions changes, `RenderFlow`/`LinkFlow`/`LinkComposite`/
`ObjectRenderer`, the `NotebookActivity` surgery (closes at exactly 800) and the debug item.
28 new JVM tests; ten modules green; debug + release compile. **User checklist: all eight items
green on all three devices (2026-08-19)** — incl. the disable/enable round trip (8a/8b run per
device over adb: bare content + no underline + structural ops alive while disabled; underline and
Link/Edit back on re-enable). **L2 (the picker) next, in a fresh session with its phase-start
wizard.**

**Claude-side device runs (one Sonnet agent per device, 2026-08-19): all drivable checks PASS on
SNN, NA5C and MIP11** — packages installed + enabled (BOOX freeze list clean before and after);
the L1 page-load path live (the `"… N links …"` load log line on all three); the debug ⋯ sheet
carries "Create test link", whose no-selection guard dialog fires correctly; the L0 "Probe links"
regression exact on all three (`resolve kind=0`, `chromeOf [1,0,0]`, trail round trip, PROBE DONE
— warm one-shots 16–22 ms on MIP11); flip / insert-past-last regression clean; crash buffers
empty. **adb cannot inject stylus/lasso input on any of the three**, so wrap / render / move /
erase / undo are the user checklist's — the agents verified everything reachable without hands.
(Agent note for later phases: the probe logs live under tag `NotebookDebugMenu` — include it in
capture filters.)

**Build notes (recorded during the phase; the Outcome finalises them):**
- **g-paper 0.1.4** (b3ab42b, published to mavenLocal, Paper pins it): `StrokeRasterizer` (public
  offline door to the internal `StrokeRenderer` — pixel-identical composite ink) and
  `PaperListener.onContentErased` (eraser hit-tests `ContentRenderer.hitTargets`, whole-object,
  per-gesture dedup; `EraseHitTest.hitContentIds` + `Geometry.polylineIntersectsRect`, JVM-tested;
  scribble-erase stays content-immune). Both L1 needs from wizard Q6.
- **Recorded deviations from the plan text** (all equivalent-or-better, none semantic):
  (a) the **underline is drawn live** by `ObjectRenderer` from the session chrome map instead of
  baked into the composite — a chrome change repaints without a rebuild, and the composite cache
  key stays content-derived (constant payload key `""`, translation-invariant, explicitly
  invalidated when a wrapped child's render lands);
  (b) `LinkStore.create` takes no chrome argument (nothing chrome-shaped is persisted — L0 Q4);
  the creation seeds `LinkFlow`'s session map directly;
  (c) `Moved` carries links in their own `linkIds` list (not inside `objectIds`) — the replay must
  route them to `LinkStore.move`, which translates children too;
  (d) wrap/unwrap **reload the page** (store → drain → `refreshToPage`, the undo-replay
  discipline) instead of hand-surgery on the live mirrors — simpler and ordering-correct;
  (e) `LinkStoreTest` as a dao-level JVM test does not exist (no Room JVM harness in the project —
  `ObjectStore` has none either): the pure halves are `PageLinkTest` (union bounds, translate,
  mapper round trip, caps — 14 tests) and the dao halves are device-verified;
  (f) a wrapped child object's rendered bitmap never resizes its row (bounds frozen by the wrap);
  (g) the render trio (`scheduleRenderPass` / `applyRenderResults` / `renderNow`) moved out of
  `NotebookActivity` into the new `RenderFlow` (the line-cap surgery — the Activity closes at
  exactly 800), which is also where the link-composite maintenance lives;
  (h) the debug "Create test link" composes its fixed payload in **debug source only**
  (`NotebookDebugMenu`) — the core never builds a payload, even for a probe; with no eligible
  selection it shows an honest dialog;
  (i) strokes and content erased by one eraser sweep are **separate undo steps** (`Erased` +
  `ObjectsDeleted` — g-paper reports them separately; accepted);
  (j) Link / Edit toolbar actions are present per the gating but **inert until L2** (a `Slog`
  no-op — the picker flow is L2's deliverable; Unlink and Delete are fully live).
- `LinkStore` chunks id lists at 500 **inside** its transactions (SQLite's 999-variable cap —
  found in Fable's review of the store agent's diff).

**Goal:** a link is a first-class page citizen created from any eligible selection (via a debug
item with a fixed payload until L2), rendered as its wrapped content + underline, atomic under
lasso / move / delete, fully undoable, and unlink restores the content — all with the extension
only consulted for `chromeOf`.

**Questions resolved at phase start (wizard, 2026-08-19):**
1. **Wrap = re-parent** children rows (the recommendation): `parentId` flips page → link id in one
   transaction, ids and coordinates untouched; unlink flips back; no id churn.
2. **Eraser: erase-whole, and for headings too** (user's call — beyond both recommendations): the
   eraser tool erases a whole link **and a whole object** on contact, links and headings behaving
   the same. g-paper 0.1.3 never touches host content and the BOOX raw pen path is invisible to the
   host, so this is a **g-paper 0.1.4 feature** (below). Scribble-erase stays content-immune
   (Paper has it off).
3. Tap inside a **selected** link = **no-op** (follow is for unselected links, L4; Edit lives on
   the toolbar).
4. The Edit glyph: **`ic_edit`** (already in `:paper-screen`; `link-plus` would be a new download
   and reads as "add").
5. Debug ⋯ **"Create test link": yes** — wraps the current eligible selection with a fixed
   `DEST_PAGE` payload to page 1 via the real grammar (`"L1|1|0||<page-1-id>"`), so `chromeOf`
   round-trips for real; removed in L5.
6. **(Added — the raster gap)** The plan's "cover-snapshot raster path" does not exist offline:
   `CoverSnapshot` uses the live view's `renderToBitmap()` and g-paper's `StrokeRenderer` is
   `internal`. **g-paper bumps to 0.1.4** with both L1 needs: (a) a **public stroke-raster
   helper** (the same internal code path live ink uses — exact fidelity, per risk 3's "never a
   host workaround"); (b) the **eraser tool hit-testing `ContentRenderer` hitTargets** with a new
   `onContentErased(ids)` listener callback (default no-op; whole-object semantics like
   whole-stroke; one shared implementation across the three engines). Recorded here per the
   "g-paper stays 0.1.3 unless a phase finds a gap" rule.

**Deliverables**
1. `data`: `SoilSchema.TYPE_LINK`; `LinkStore` over `SoilWriter` (`create(pageId, payload, chrome,
   memberStrokeIds, memberObjectIds): LinkRow` — union bounds + 2 dp underline clearance, re-parent
   in one transaction; `unlink(linkId)`; `move`; `deepChildIds(pageId)`; `payloadsOf(pageId)`),
   JVM tests incl. the page-delete cascade and move-translates-children.
2. `notebook`: `Shape.OneLink` + the three core actions (gating per Architecture; `SelectionActions`
   + tests); `LinkFlow` (create-from-selection guard: no link inside the selection, page object cap;
   unlink; the failure dialogs); composite render folded into `ObjectRenderer` + `ObjectRenderPass`
   (children strokes rasterized — helper confirmed here; child objects via the normal pass;
   underline; cache; dashed placeholder inside the composite for an absent child provider);
   `NotebookUndo` gains `LinkCreated` / `LinkUnlinked` (+ `LinkEdited` shape, exercised in L2);
   links join `Moved` / `ObjectsDeleted` / `Page` replay. All new screen logic in collaborators —
   `NotebookActivity` stays under its cap.
3. `chromeOf` on page load for link rows (batched, session cache — **no persisted mirror**, L0 Q4).
4. Docs: `docs/notebook.md` §"Link objects (arc 7)" started; `docs/data.md` link-row paragraph.

**Tests**
- JVM: `LinkStoreTest` (wrap/unwrap round trip, cascade, move), `SelectionActionsTest` additions
  (OneLink, link-gating, Mixed+Link), `NotebookUndoTest` additions.
- Claude-side per device: create test link over ink+heading → composite renders (underline on),
  lasso the link → Delete-only-plus-link-actions toolbar, drag it, undo/redo chain (create → move
  → unlink → undo ×3 → redo ×3), page delete + undo with a link on the page, reopen the notebook
  (render from rows), disable the extension → link still renders content (underline per Q4 cache),
  Unlink still works, Link/Edit absent.
- **User device checklist** (numbered, ~8 items: the above by eye on all three devices, incl.
  writing over a link with the pen — ink lands, nothing follows).

**User checklist as issued (2026-08-19 — run on SNN, NA5C and MIP11; adb can't drive the lasso, so
these are the stylus-dependent halves the agents couldn't reach):**
1. Write a few strokes + create a heading (lasso ink → H), then lasso both together → debug ⋯ →
   **Create test link** → the selection becomes one unit rendered exactly as it looked, with a
   **1 dp underline** along its bottom.
2. Lasso the link → the toolbar shows **Delete · Edit · Unlink** only (no H, no Pad; Link absent).
   Lasso the link *plus* extra ink → **Delete only** (the selection contains a link, so Link is
   gated off — no link-inside-link). Delete of that mixed selection works as one step.
3. Drag the selected link somewhere else → the whole composite moves as one; undo (2-finger
   double-tap) puts it back; redo (3-finger) re-moves it.
4. The chain: create link → move it → **Unlink** → undo ×3 (content re-wraps, move reverts,
   link dissolves back to loose ink + heading) → redo ×3 — everything lands where it was.
5. **Eraser over a link** (your L1 Q2 call): one eraser pass erases the whole link; same for a
   bare heading — whole-object. Undo brings each back.
6. Write **over** a link with the pen — ink lands on the page, nothing else happens.
7. Delete the page holding a link (long-press → Delete) → undo → the link (and its underline)
   comes back intact. Then close + reopen the notebook → the link still renders from its rows.
8. Disable / uninstall `NSE · Links Dev` (Settings → Apps) → reopen the notebook: the link renders
   its **content with no underline**, still moves / deletes / **Unlinks**; Link and Edit are gone
   from every toolbar. Re-enable → underline and buttons return (resume or reopen).

*(Note: the toolbar's Link and Edit buttons are present but inert in L1 — their taps do nothing
until the L2 picker. Unlink and Delete are fully live.)*

**Close-out:** as L0.

---

### Phase L2 — The picker: choose an existing target, create + edit end to end
**Status:** ✅ Complete (commits 0961d04 + a86214c; user checklist all green on SNN/NA5C/MIP11 2026-08-19)

**Outcome (2026-08-19).** Everything delivered; the wizard answers (Q1–Q4), the recorded deviations
and the review/fix trail are in the Build notes above. **Model protocol:** one Opus agent built the
picker (`LinkPickerActivity` + pure `PickerModel`, 27 JVM tests) and, as a follow-up, the `pathTo`
prefill navigation (8 more tests); one Sonnet agent wrote `LinkPickerLabelsTest` (6 tests); three
Sonnet device agents ran the drivable half (all PASS ×3 — probe on the real-picker build, `am
start` refused, binds clean); Fable inline did the host pick flow (`LinkFlow`), the
`LinkCatalogSource` reshape + `LinkPickerLabels`, the `PasteFlow` line-cap extraction, the
**`ILinkCatalog.pathTo` append** (contract + `LinkCatalogBinder`), the doc drafts, and reviewed
every agent diff (two review fixes: "Chrome"→"Style"; edit-prefill pager jump). **User checklist:
all nine items green on all three devices (2026-08-19)** — item 5 surfaced the one real defect
(`DEST_NOTEBOOK` prefill invisible at the root), fixed in-phase via `pathTo`; item 8 verified live
on SNN (clean disconnect → silent cancel). 41 new JVM tests total; ten modules green; debug +
release compile. **L3 (create-in-picker) next, in a fresh session with its phase-start wizard.**

**Claude-side device runs (one Sonnet agent per device, 2026-08-19): all drivable checks PASS on
SNN, NA5C and MIP11** — app + ext-links installed and enabled (BOOX freeze list clean before and
after); the page-load regression live ("… 1 links …" on all three); the L0/L1 "Probe links"
sequence exact on the real-picker build (`beginPick ok` 144 ms SNN / 98 ms NA5C / 46 ms MIP11 warm;
one-shots 7–23 ms; `chromeOf [1,0,0]`; trail round trip; PROBE DONE); **`am start` of the now-real
`LinkPickerActivity` refused** (`refused caller (none)`, nothing resident) on all three; binds =
unbinds after the probe; flip regression clean (MIP11's swipe also exercised insert-past-last,
1/1 → 2/2); crash buffers empty. The picker's own open is lasso-gated, so the create / edit /
cancel / kill-mid-showing items are the user checklist's (Build notes, drivable-half correction).

**Goal:** "Link" on an eligible selection opens the extension's picker; choosing a page of this
notebook / another notebook / a page of another notebook + chrome creates the link (one undo
step); "Edit" reopens it pre-populated and patches payload + chrome only.

**Questions resolved at phase start (wizard, 2026-08-19):**
1. **Modes "This notebook" · "Notebook" · "Notebook page"** (three-way toggle, that order) — and
   the **current page is excluded** from "This notebook"'s grid (a self-link is a no-op trap).
2. **Current notebook pages additionally show outline labels** where the Heading outline has them
   ("Page n — <label>"); other notebooks stay "Page n" (Deferred item unchanged). The label crosses
   outward through the catalog (`LinkCatalogSource` grows a labels callback; capped/truncated by
   `LinkCatalogGate.entry` like every label) — a **recorded outward widening** for the L5 audit:
   heading-derived text of the *current notebook only*, during a pick showing only.
3. **No search field — deferred** (recorded under Deferred; keeps L2 IME-free).
4. **Paged card grid** — the shape Paper's `FolderPickerActivity` already has (measured columns +
   first/prev/next/last pager). *(Plan correction: the library's folder picker is a card grid, not
   rows — the original Q4 wording misremembered it.)*

**Build notes (recorded during the phase; the Outcome finalises them):**
- **`LinkCatalogSource` reshaped** (`currentPageIds` → `currentPages: () -> List<Pair<String,
  String>>?`): the current notebook's page **labels are composed host-side** ("Page n" /
  "Page n — heading" via the new `notebook/LinkPickerLabels`, headings from the same
  `ContentsSource.gather` the Contents uses, best-effort) and the **current page is excluded
  host-side** — the exclusion must live beside the numbering (`mapIndexedNotNull` over the full
  page list) or "Page n" would drift from true position. Foreign notebooks keep blank labels
  (picker shows "Page n" from position). The debug probe/test-link source passes plain unexcluded
  labels (debug behaviour unchanged from L1).
- The outline gather for the labels runs in `LinkFlow.buildSource()` **before** `openPick` — its
  provider binds never sit inside the held bind's 2 s `beginPick` window.
- **The pick launcher lives in `LinkFlow`** (registered at construction, the `ScratchPadFlow`
  precedent) rather than in `NotebookActivity` — the Activity gains only the two `onAction` cases,
  the two `LinkFlow` constructor args and `linkFlow.close()` in `onDestroy`.
- **`NotebookActivity` line cap**: the arc-6 scratch-pad paste block moved out to the new
  `notebook/PasteFlow` (with a shared `presentSelection` helper the object-select path reuses) —
  the Activity closes at **779** lines, headroom for L4's extras + gestures.
- **The current notebook is hidden in both "Notebook" modes** picker-side (a self-target is the
  same no-op trap as Q1b's self-page; the original's mode was literally named "Other Notebook").
- Strings beyond Appendix A: `links_result_lost` (host recreated mid-showing — the scratch
  `scratch_result_lost` precedent) and `links_page_full` (the pre-flight cap refusal is a dialog
  in the real UI; `createFromSelection` keeps its log-only race-window re-check).
- Edit with an unchanged payload is a **no-op** (no store write, no undo step) — chrome rides
  inside the payload, so payload equality covers both.
- **Picker (Opus agent) build calls, Fable-reviewed:** pure decisions in `PickerModel` (mode↔kind,
  prefill, hide-filter, label fallback, OK composition — 27 JVM tests); the hide-current-notebook
  filter is **kind-scoped** (only `CATALOG_NOTEBOOK` rows match, a folder can never be hidden by an
  id collision); the Up control is a **text button** (the only left-arrow glyph is already the top
  bar's Back — two identical arrows would read as one control); only folder cards carry a glyph
  (`ic_folder` — no notebook/page glyph exists and no new asset is added); selection/toggle
  inversion reuses `btn_elevated_background` vs `shape_bordered` with padding saved around
  `setBackgroundResource`; problem-dialog titles use the live screen title ("Edit link" in edit
  mode); leaving a drilled notebook clears the selection (a page id is meaningless outside its
  notebook) while popping a folder keeps a chosen notebook; a `loadToken` drops stale catalog
  replies (risk 4 — slow cold foreign `listPages`); a blank `PickSession.currentNotebookId` yields
  an empty "This notebook" grid rather than `listPages("")`; `setResult(RESULT_CANCELED)` set in
  `onCreate` covers every non-OK exit; grid geometry = the folder picker's (3 cols ≥ 480 dp else 2,
  card height ×1.4).
- **Fable review fixes on the agent diff:** the chrome row's label "Chrome" → **"Style"** (user
  wording, not developer jargon); an Edit's prefilled target may sit pages into the grid — `accept`
  now jumps the pager to the page holding the selected id, or the prefill highlight would read as
  "nothing selected".
- **Flagged for L5** (found in the picker agent's report, left as built): the host's
  `LinkCatalogBinder.io` wrapper rethrows unexpected failures as `IllegalStateException("catalog:
  <Class>: <msg>")` and that prefix can reach the picker's dialog verbatim; a **host**-process death
  mid-showing surfaces ext-side as `DeadObjectException` → generic dialog + stay (only a revoke is
  a plain finish) — both honest, neither pretty.
- **Drivable-half correction (the L1 trap applies to L2's entry point):** the picker opens only
  from Link / Edit on a *lasso selection*, which adb cannot make — so the planned Claude-side
  "create each kind / edit / cancel / kill mid-showing" items move to the **user checklist**; the
  device agents cover install, the probe regression on the real picker build, the `am start`
  caller-check, binds=unbinds, flip + crash-buffer regressions. "Revoked binder refuses a late
  call" is JVM-covered (`LinkCatalogGateTest`) — accepted without a device twin.
- **APK-size non-finding:** a working-tree `ext-links-debug.apk` measured 35.7 MB against L0's
  ≈23.6 MiB — zipflinger *incremental-packaging holes* from repeated in-place debug builds, not
  real growth (identical entry CRCs, central directory ~24.6 MB; a clean package is 24.85 MB ≈
  HEAD's 24.79 MB + the picker's ~60 KB).
- **User-checklist finding (item 5, SNN) → fixed in-phase:** an Edit of a `DEST_NOTEBOOK` link
  pre-selected the notebook but the browse opened at the library root, so a target inside a folder
  never showed its highlight ("Notebook page" worked — it drills by id). Fix: **`ILinkCatalog`
  gained an appended sixth method `pathTo(notebookId)`** (the arc-5 append-LAST recipe, recorded in
  the AIDL + §LinkProvider) answering the alive folder chain root-first + the notebook itself last
  (label = its name); `LinkCatalogBinder.pathTo` = `IndexRepository.ancestry` behind the same gate
  caps; the picker seeds its folder stack from it on both notebook-kind prefills — `DEST_NOTEBOOK`
  opens in the target's own folder with the card inverted, and `DEST_NOTEBOOK_PAGE` gains the real
  notebook name in its browse header + an Up that lands in the notebook's folder instead of the
  root. Empty/failed `pathTo` → the old root fallback, no dialog (prefill is cosmetic).
- **Checklist item 8 (kill mid-showing) verified live on SNN** during the run: force-stop took the
  picker down with its process; the host saw result 0 → `takeChoice` `service disconnected` → null
  → silent cancel, clean unbind + revoke, crash buffer empty. Cosmetic L5 tidy: `LinkClient.finish`
  logs "endPick ok" even when a dead bind made it skip the call.
- JVM totals this phase: 27 (`PickerModelTest`) + 6 (`LinkPickerLabelsTest`) + the pathTo prefill
  tests = **33+ new tests**; all ten modules green; debug + release compile.

**Deliverables**
1. `:ext-links` `LinkPickerActivity` real: three modes over `PickSession.catalog` (folder
   navigation with the library's ordering; drill-in for "Notebook page"), chrome toggle
   (default UNDERLINE, prefill on edit), OK/Cancel per Architecture, progress while a catalog call
   runs, `Dialogs.problem` on a catalog failure (message from the host's `IllegalArgumentException`
   where typed).
2. `:app` `LinkFlow`: the pick flow (launcher registered in `NotebookActivity`, guards before any
   bind, `takeChoice` → `LinkStore.create` → render → `LinkCreated`, selected on completion like
   objects); Edit (OneLink → `openPick(editPayload)` → patch via `LinkStore.updatePayload` →
   `LinkEdited`); every failure dialog (`links_picker_gone`, `links_choice_invalid` — exact
   strings in Appendix A).
3. Chrome changes re-render the composite (underline on/off) immediately.
4. Docs: `docs/extensions.md` §"The Links extension" + §"LinkProvider — host behaviour" drafts.

**Tests**
- JVM: payload/choice validation paths; `LinkFlow` guard tests where typeable.
- Claude-side per device: full create flow to each of the three target kinds (adb-driveable —
  finger taps), edit flip target + chrome, cancel changes nothing, kill the extension mid-showing
  → honest dialog + notebook intact, binds = unbinds, revoked catalog binder refuses a late call
  (log probe).
- **User device checklist** (~8 items incl. e-ink look of the picker on all three widths, IME
  behaviour n/a — no text input until L3).

**User checklist as issued (2026-08-19 — run on SNN, NA5C and MIP11; the picker only opens from a
lasso selection, so all of this is yours):**
1. Lasso some ink → **Link** → the picker opens: top bar Back · "Link to" · OK; the mode toggle
   (This notebook · Notebook · Notebook page), the Style toggle (Underline · None, Underline
   pre-selected), and a card grid — everything on-screen at the device's width, e-ink-clean.
2. **This notebook** shows "Page n" cards with the **current page missing** and the numbering still
   true to position; a page that has a heading shows "Page n — <heading>". Pick a page → OK → the
   selection becomes a link with an underline. Undo removes it; redo brings it back.
3. **Notebook** mode: the browse shows your folders + notebooks (library order), the **current
   notebook absent**; drill into a folder (header names it, Up returns), pick a notebook (card
   inverts) → OK → a second link lands (tap does nothing yet — follow is L4).
4. **Notebook page** mode: a notebook card drills into its pages ("Page n"); pick one → OK.
5. **Edit**: lasso a link → **Edit** → the picker reopens pre-populated (mode + Style + the target
   card inverted, the grid opened on the page that shows it; title "Edit link"). Change the target
   and flip the Style → OK → the underline follows immediately; **one undo** reverts the edit
   (underline flips back); redo re-applies.
6. **Edit → OK without changing anything** and **Cancel/Back**: both leave the link exactly as it
   was (no undo step is added — undo still targets the previous action).
7. **OK with nothing selected** → an honest "Choose a target first." dialog, picker stays.
8. With the picker open, kill the extension (Settings → Apps → NSE · Links Dev → Force stop, or ask
   Claude to `am force-stop` it): the picker vanishes with its process; the notebook underneath is
   intact, no crash, and the selection still lassoed. (A cancelled-looking return is correct.)
9. 60-second regression: write over a link (ink lands), eraser takes a whole link, drag / unlink /
   delete still work, Scratch Pad round trip, Contents swipe-down — unchanged. *(On SNN also give
   the forward-swipe-past-last-page insert one glance — the device agent's injected swipe didn't
   visibly insert there while NA5C's did; almost certainly an injection artifact, nothing in L2
   touched that path.)*

**Close-out:** as L0.

---

### Phase L3 — Creating targets in the picker (new page · new notebook · new folder)
**Status:** ✅ Complete (commit 6822e44; user checklist all 8 items green on SNN/NA5C/MIP11 2026-08-20)

**Outcome (2026-08-20).** Everything delivered; the wizard answers (Q1–Q4), the settled Q2
mechanics (the host's real New-notebook screen over `prepareNewNotebook` / `LinkCreateRelay` /
`ACTION_LINK_NEW_NOTEBOOK_SCREEN` / `takeCreatedNotebook` — nothing on the Intent in either
direction), the undo-stack-clear consequence and the L5 flags are in the Build notes above.
**Model protocol:** one Opus agent built the picker half (`PickerModel.createButtons` +
`CreateDialogs` + the three flows; Fable review — no blocking findings, its extra `loadToken` and
`performClick` guards kept); one Sonnet agent wrote `LinkCreateRelayTest` (7 tests); three Sonnet
device agents ran the drivable half (7/7 PASS ×3 — the new `am start` refusal of the exported
screen, the library's own +Notebook through the caller gate, probe/flip regressions); Fable inline
did the contract appends, the catalog create half, relay + caller gate, `NewNotebookActivity`
relay mode, `insertPageAt`, the binder→page-op bridge, and all docs. 12 new JVM tests; ten modules
green; debug + release compile. **User checklist: all eight items green on all three devices
(2026-08-20)** — create page (anchored + appended, both notebooks), New folder incl. refusal
paths, New notebook through the real screen in both browse modes, cancel paths, and the L2/library
regressions. **L4 (follow + trail) next, in a fresh session with its phase-start wizard.**

**Claude-side device runs (one Sonnet agent per device, 2026-08-20): all 7 checks PASS on SNN,
NA5C and MIP11** — packages installed + enabled (no BOOX re-disable through the run); **the
library's own +Notebook flow works through the new caller gate** on all three (screen appears with
the prefilled default — the NA5C/SNN folders' "Test N" scheme names prove the namer prefill path
too — create + open + delete clean); **the NEW `am start` refusal of the exported
`ACTION_LINK_NEW_NOTEBOOK_SCREEN`** holds on all three (no `callingPackage` → the screen never
becomes resumed); the L2 picker `am start` refusal intact (`refused caller (none)`); the L0/L1/L2
"Probe links" sequence exact on the L3 build (beginPick ok 141 ms SNN / 94 ms NA5C / 75 ms MIP11;
one-shots 8–22 ms; `chromeOf [1,0,0]`; trail round trip; PROBE DONE); binds = unbinds
(`dumpsys activity services` clean); page-load "… N links …" line + flip regression clean; crash
buffers empty. The create flows themselves are lasso-gated (the picker only opens from Link/Edit),
so they are the user checklist's.

**Goal:** the original's create-in-picker parity: from the picker, a new page (this or the other
notebook, before/after an anchor or appended), a new notebook, or a new folder can be created and
immediately picked, without leaving the flow; the host validates everything.

**Questions resolved at phase start (wizard, 2026-08-19):**
1. **Anchor rule** (the original's exact rule): a page card selected → ActionSheet "Insert before /
   Insert after"; nothing selected → append. Template inherited from the anchor (append → the last
   page; a Paper notebook always has ≥ 1 page, so the "empty notebook" branch can't occur).
2. **Full new-notebook screen** (user's call — beyond the recommendation): "New notebook" routes
   through the host's real `NewNotebookActivity` (Template section included). Mechanics settled in
   session: the picker can't launch a host screen, so `ILinkCatalog` gains two **appended** methods
   (the arc-5 append-LAST recipe, after L2's `pathTo`) — `requestNewNotebook(parentFolderId)` (the
   host launches its own screen, on top of the picker, in a host-internal **create-only** mode:
   create, don't open; naming-scheme prefill resolved for the browsed folder like the library does)
   and `takeCreatedNotebook(): CatalogEntry?` (the picker drains it on resume; null = cancelled).
   No id ever rides an Intent; everything crosses through the gated catalog lens. The original
   `createNotebook(parentFolderId, name)` AIDL slot stays `UnsupportedOperationException` forever,
   documented as superseded by this answer.
3. **Library rules exactly** for the picker's New-folder name dialog: charset `[a-zA-Z0-9_\-. ]`,
   not `.`/`..`, non-empty, duplicate-sibling check — validated host-side in `createFolder`;
   refusals are typed `IllegalArgumentException`, shown verbatim by the picker. (The library
   dialog's naming-scheme field is a library nicety — absent in the picker, recorded.)
4. **Reload on any result** (the original's rule): the notebook screen re-reads its page list from
   the `.soil` on every picker return — OK or Cancel — recomputing `currentIndex` from the stable
   current page id.

**Design settled at phase start (the Q2 mechanics + consequences — build notes grow below):**
- **The new-notebook round trip** (Q2): the picker launches the host's real `NewNotebookActivity`
  itself via a new contract activity action `ACTION_LINK_NEW_NOTEBOOK_SCREEN` (+ `setPackage
  (HOST_PACKAGE)` — the mirror of `ACTION_LINK_PICKER_SCREEN`), with an `ActivityResultLauncher`.
  The screen is exported with a new host-side caller gate (`extension/ExtensionCallerCheck` — the
  `HostCallerCheck.enforceActivity` mirror: `callingPackage` non-null + `SIGNATURE_MATCH`).
  **Nothing rides the Intent in either direction:** before launching, the picker calls the appended
  catalog method `prepareNewNotebook(parentFolderId)` — the host validates the folder, resolves the
  naming-scheme default for it exactly like the library (`NamerClient.defaultName`, best-effort)
  and parks `(folderId, defaultName)` in a host-process relay (`LinkCreateRelay`, cleared with the
  showing's revoke); `NewNotebookActivity` in relay mode (detected by the action) reads the relay,
  creates without opening (it never opened — the caller does), parks `(id, name)` back and returns
  a bare `RESULT_OK`; the picker drains it through the appended `takeCreatedNotebook():
  CatalogEntry?` (null = cancelled). Both methods are appended LAST after `pathTo` (the arc-5
  recipe). `createNotebook(parentFolderId, name)` (AIDL slot 5) stays
  `UnsupportedOperationException` forever — superseded by this answer, documented in the AIDL.
- **`createPage` (slot 3) goes live**, two branches: the **current** notebook through the live
  session (`LinkCatalogSource` gains a `createPage(anchorPageId?, before)` callback → `LinkFlow`
  bridges the binder thread to the host's page-op lock with a `CompletableDeferred` → new
  `NotebookSession.insertPageAt` — inserts + renumbers + mirrors pageCount **without navigating**,
  `currentIndex` re-anchored by id); a **foreign** notebook by an open → insert → renumber →
  index `setPageCount`/`touch` → seal-in-`finally` helper. Anchor `""` = append; template
  inherited from the anchor / last page (Q1).
- **`createFolder` (slot 4) goes live**: `NewNotebookActivity.validateName` + `MAX_NAME_CHARS` +
  `nameTaken(parent, FOLDER)` → `IndexRepository.createFolder`; refusals are typed
  `IllegalArgumentException` with the library's own user-honest texts (Q3).
- **Undo consequence of a non-undoable page insert (recorded):** the notebook-level undo stack
  holds `Page(Structural)` snapshots whose page-id lists predate a picker-created page — replaying
  one would soft-delete the new page (`reconcile` makes the live set exactly `targetAlive`). So a
  picker page-create in the **current** notebook **clears the undo stack** on picker return
  (honest: structural truth changed outside the stack; creation is an explicit act). The link
  created after it is recorded normally.
- **Q4** lands as: the current-notebook insert mutates the live session directly, and on any picker
  result `LinkFlow` (when its `pagesChanged` flag is set) clears the undo stack and refreshes the
  page indicator + Contents availability.

**Build notes (recorded during the phase; the Outcome finalises them):**
- **Host half landed first (Fable inline), compiles + `:app`/`:extension-api` JVM green:** the two
  appended AIDL methods + `ACTION_LINK_NEW_NOTEBOOK_SCREEN`; `ic_page_add` + `ic_new_notebook`
  copied from the original app into `:paper-screen` (the L0 icon precedent — both already
  `@color/inkBlack`); `ExtensionCallerCheck` + `LinkCreateRelay` (pure — 7 JVM tests, Sonnet agent);
  `LinkCatalogBinder` create half (`createPage` live-vs-foreign split, `createFolder` with the
  library's texts, `prepareNewNotebook` resolving the namer default on the binder thread — the
  proxy precedent for the nested host→ext bind, `takeCreatedNotebook`; `revoke()` also clears the
  relay); `NewNotebookActivity` relay mode + exported behind the caller gate (every launch — the
  host itself passes trivially; a plain `startActivity` has no `callingPackage` and is refused);
  `NotebookSession.insertPageAt` (no navigation, `currentIndex` re-anchored by id);
  `LinkFlow.createPageBlocking` (`CompletableDeferred` bridge into `runPageOp`, 10 s timeout —
  `runPageOp` silently drops ops while closing, and a dropped op must be an honest failure, not a
  hung picker) + `pagesChanged` → undo clear + `onPagesChanged` (indicator + Contents refresh) on
  any picker result; the debug-menu `LinkCatalogSource` call named its lambda (the new trailing
  `createPage` param would have captured it).
- The ext-links manifest gained a `<queries>` for the new action (the held bind already grants
  interaction-based visibility; explicit is self-documenting).
- **Edge accepted (recorded):** if the host process dies mid-showing and the user then taps "New
  notebook", the cold host start bounces through `IndexGuard` (`NEW_TASK|CLEAR_TASK`) and tears the
  task down — the same half-dead-showing family as the L2 `DeadObjectException` flag; L5 reviews
  both together.
- **Picker half (Opus agent), Fable-reviewed — no blocking findings:** `PickerModel.createButtons`
  (pure; PickerModelTest 35 → 40, module 49) + the three flows in `LinkPickerActivity` (690 lines)
  + the new `CreateDialogs` collaborator (the insert-position sheet + the name prompt — the one IME
  in this extension, the `ObjectEditDialog` pattern verbatim; a refused name keeps the prompt and
  the typed text; Create disarmed while the call is out, and the IME-Done path re-checks
  `isClickable` because `performClick` fires even on a disarmed button). Good agent calls kept:
  `createPage` bumps + checks `loadToken` (a mode switch mid-create would otherwise select the new
  page id under the wrong destination kind); `takeCreatedNotebook` failures route through the
  existing `failed(e)`; the three buttons are `ic_folder_plus` / `ic_new_notebook` / `ic_page_add`
  left of OK, absent-never-disabled, with the extra Activity-level hide when the showing has no
  current notebook.
- **Flagged for L5 (agent findings, left as built):** the relay's `prepared` slot is deliberately
  sticky (recreation must re-find it) and empties only on re-prepare/revoke; a mode switch during
  `prepareNewNotebook`'s IO creates the notebook in the previously browsed folder without showing
  it (benign — nothing invalid composes); a stale Edit-prefill anchor id passed to `createPage`
  surfaces as the host's honest "unknown page" dialog (accepted, no pre-check).
- **Root New notebook from the picker has no scheme prefill** — mirrors the library exactly (the
  library resolves the namer only inside a folder); the timestamp default fills the name field.

**Deliverables**
1. Contract: `ILinkCatalog` + `prepareNewNotebook` / `takeCreatedNotebook` (appended);
   `ACTION_LINK_NEW_NOTEBOOK_SCREEN`; `createPage` / `createFolder` real per above,
   `createNotebook` documented-superseded.
2. Host: `LinkCatalogBinder` create half + `LinkCreateRelay` + `ExtensionCallerCheck` +
   `NewNotebookActivity` relay mode (exported + intent-filter); `NotebookSession.insertPageAt` +
   the foreign-insert helper; `LinkFlow` createPage bridge + `pagesChanged` → undo clear +
   indicator/Contents refresh on return.
3. `LinkPickerActivity`: the mode-dependent create buttons (This notebook → New page; Notebook
   browse → New folder + New notebook; drilled-in pages → New page — glyphs `ic_page_add` /
   `ic_folder_plus` / `ic_new_notebook`, the first + last copied from the original app, the L0
   precedent), the New-folder name dialog (design-system IME pattern), the anchor ActionSheet
   ("Insert before / Insert after" when a page card is selected, append otherwise — Q1),
   auto-select the created target (page → selected; folder → navigate into it; notebook →
   Notebook mode auto-picks / Page mode drills in).
4. Not undoable (matches the original + the library) + the undo-stack-clear consequence —
   recorded in `docs/links.md` (L5) and `docs/extensions.md`.

**Tests**
- JVM: validation refusal paths.
- Claude-side per device: create page before/after/append in both notebooks (verify order + template
  inheritance), new folder + new notebook appear in the library afterwards with correct placement,
  duplicate/invalid names refused with honest messages, cancel-after-create keeps the created page
  (and the notebook screen shows the reloaded list).
  *(Drivable-half correction, the L1/L2 trap again: every create flow sits behind the lasso-gated
  picker, so the in-picker items above move to the user checklist. The device agents cover: install
  + enabled, the library's own +Notebook regression — the screen gained the caller gate and must
  still open for the host, prefilled default so no typing (SNN swallows `input text`) — the NEW
  `am start` refusal of the exported `ACTION_LINK_NEW_NOTEBOOK_SCREEN` (no `callingPackage` →
  finish; key L3 security check), the L2 picker `am start` refusal, the "Probe links" regression,
  page-load/flip regression, binds = unbinds, crash buffers.)*
- **User device checklist as issued (2026-08-20 — run on SNN, NA5C and MIP11; every create flow is
  lasso-gated, so all of this is yours):**
  1. Lasso ink → **Link** → "This notebook": a **New page** button sits in the top bar left of OK.
     Tap it with **no page card chosen** → a page appends (the grid grows by one, "Page n"
     numbering still true to position) and the new card comes back **selected**. OK → the link
     lands on it.
  2. Choose a page card first → **New page** → the sheet asks **Insert before / Insert after** →
     pick one → the page lands on the right side of the anchor, numbering follows, new card
     selected. Back in the notebook (even after **Cancel**): the pager count includes the created
     page, flipping reaches it, its template matches the anchor's — and **undo does NOT remove it**
     (creation is explicit; the undo history is fresh after a picker page-create).
  3. **Notebook** mode → **New folder** → the name dialog (the picker's first IME — the Ratta IME
     quirks apply on SNN: field visible above the keyboard, typing works). An invalid name (`..`)
     and a duplicate sibling both → an honest dialog **with the prompt and your text still there**.
     A valid name → the browse walks into the new folder.
  4. **Notebook** mode → **New notebook** → the **real New-notebook screen** opens (Template
     section with your templates; in a folder with a naming scheme the scheme name is prefilled) →
     CREATE → back in the picker with the new notebook's card **selected in its folder** → OK →
     link created. The notebook also shows in the library afterwards, right folder, right template.
  5. **Notebook page** mode → drill into another notebook → **New page** there (before / after /
     append) → the new page selectable → OK. Open that notebook afterwards: the inserted page is
     there, in position, template inherited.
  6. **Notebook page** mode → **New notebook** (while browsing, not drilled) → CREATE → the picker
     drills straight into the fresh notebook's one-page grid → pick Page 1 → OK.
  7. **Cancel paths**: Back out of the New-notebook screen → picker unchanged, nothing created.
     Cancel the name dialog → nothing created. Cancel the whole picker after a New page → the page
     stays, no link is created.
  8. 60-second regression: the library's own +Folder / +Notebook / rename flows unchanged; a quick
     L2 pass (link to existing targets, Edit prefill, Unlink, eraser-takes-a-link) unchanged.

**Close-out:** as L0.

---

### Phase L4 — Follow, the trail, and the way back
**Status:** ✅ Complete (commits b2e71dd + b9b107f; user checklist all 10 items green on SNN/NA5C/MIP11 2026-08-20)

**Outcome (2026-08-20).** Everything delivered; the wizard answers (Q1–Q4, all the
recommendations), the recorded deviations and the two in-phase catches are in the Build notes
above — most notably: **both Backs funnel `backPressed()`** (the top-bar Back walking the trail
was the scope's own rule — the toolbar callback initially still closed straight to the library),
and the **user-checklist finding on SNN** (a created link presented as a dashed outline until the
pen left hover — not a crash; fixed by `RenderFlow.buildCompositesNow()` before every page-load's
at-once frame, commit b9b107f). **Model protocol:** one Sonnet agent wrote `LinkNavTest`
(17 tests, green first try — no review findings); three Sonnet device agents ran the drivable half
(**10/10 PASS ×3 — real follows driven on every device** via screenshot-aimed taps on the L1–L3
test links: cross-notebook seal→relaunch + swipe-up back to the exact origin page on NA5C + SNN,
same-notebook on MIP11); Fable inline did `PageGestures`, `LinkNav`, the `LinkFlow` follow/trail
half, the `NotebookActivity` navigation surgery, the finding's diagnosis + fix, and all docs.
**Q4 settled by data: warm-bind stays unbuilt** — warm tap→resolve 14 ms NA5C / 76 ms SNN /
19–35 ms MIP11, seal→target ≈ 246/420 ms (the chrome refresh at page load keeps the extension
process warm in practice). 17 new JVM tests; ten modules green; debug + release compile.
**User checklist: all ten items green on all three devices (2026-08-20)** — the chain story,
force-stop persistence, both dead-target dialogs, dead-trail-entry silent skip, pen-vs-finger,
no-extension honesty, and the full regression pass. **L5 (review + freeze) next, in a fresh
session with its phase-start wizard; the review base is `0f91ed5` (L0 Outcome).**

**Claude-side device runs (one Sonnet agent per device, 2026-08-20): all 10 checks PASS on SNN,
NA5C and MIP11** — nothing disabled; fresh-open `clearTrail ok` on all three; empty-trail swipe-up
silent (`popTrail: empty`); bare tap on empty paper inert; **a real follow driven on every
device** (the user's L1–L3 test links, located by screenshot): NA5C and SNN each followed a
`DEST_NOTEBOOK` link cross-notebook — seal → relaunch → target loaded, then **swipe-up walked
back to the exact origin page** (`popTrail: entry`); MIP11 followed a same-notebook `DEST_PAGE`
link + walk-back. **Q4 timings (all warm — the chrome refresh at open had started the ext
process): tap→resolve 14 ms NA5C · 76 ms SNN · 19–35 ms MIP11; seal→target-loaded ≈ 246 ms NA5C ·
≈ 420 ms SNN. Warm-bind stays deferred — nothing here needs it.** Probe links regression exact
(beginPick 49/24/16 ms); both `am start` refusals hold; binds = unbinds; crash buffers empty;
flip regression clean. Notes: the agents' past-last-page swipes appended blank pages (SNN
"Test 07" 1→3, NA5C "Test 03" 4→5 — the by-design insert, user may delete); the MIP11 agent
missed the `LinkFlow` log line — the **recorded MIP11 `log.tag` trap** (resets to `I`;
`setprop log.tag.LinkFlow DEBUG` re-verified the line live, 19 ms, on the final build). The
Back-funnel fix build was installed on all three AFTER the agent runs; its follow was
re-smoke-tested live on MIP11. The trail-persistence force-stop, dead-target dialogs,
pen-vs-finger and no-extension items are the user checklist's (lasso-gated setup).

**Goal:** a finger tap on a link follows it (same page-set navigation, or seal + relaunch into the
other notebook); swipe-up walks the trail backward; a via-link notebook's Back button walks it
too; the trail persists in the extension's store and clears on any fresh open.

**Questions resolved at phase start (wizard, 2026-08-20 — all four the recommendation):**
1. **Target popup only** — no origin-side overlay; the tap starts the close and the target's
   existing "Opening…" popup covers from its first frame. The seal-time gap on SNN is accepted
   (re-taps are harmless — the follow is busy-guarded).
2. **Skip dead trail entries silently** — pop until a live entry navigates or the trail is empty
   (then swipe-up is silent / Back closes to the library); capped at `MAX_TRAIL_ENTRIES` pops.
3. **Every follow pushes the trail, same-notebook included** (the original's rule) — the trail is
   a true history of follows.
4. **Warm-bind deferred — measure first**: build follow without it, record tap→resolve per device
   in the L4 runs; the chrome refresh at page load already starts the extension process in most
   real sessions.

**Build notes (recorded during the phase; the Outcome finalises them):**
- **`PageGestures`** (`:paper-screen`): `onFingerTap(x, y)` + `onSwipeUp()` added to the Listener
  (default no-ops — the Scratch Pad ignores them). The swipe-up is the Contents rule mirrored
  (`evaluateVerticalSwipe` routes on the `dy` sign — one vertical evaluation, exclusive with the
  flip). The tap recogniser is the **inverse** of every other one (sub-slop, ≤ long-press timeout,
  single finger — a second finger / movement / duration disarms), so it can never co-fire; it
  commits through the same pen-tail escrow as undo/redo, and reports the **down** point.
- **`LinkNav`** (new, pure, no Android imports — JVM-tested): `planFollow` (SamePage / OtherNotebook
  / Dead / NoOp) + `planBack` (SamePage / OtherNotebook / Skip). A `DEST_NOTEBOOK_PAGE` naming the
  **current** notebook is treated as an in-notebook hop; a self-`DEST_NOTEBOOK` is a silent no-op
  (untrusted payload — the picker never composes one). `Plan.SamePage` carries the page **id**, not
  an index — the index is re-looked-up under the page-op lock at navigation time.
- **`LinkFlow`**: `followAt` (topmost link by z-order — `liveLinks` is insertion-ordered, iterate
  reversed), `walkBack(onEmpty)` (pop loop capped at `MAX_TRAIL_ENTRIES`; dead entries skipped
  silently per Q2), `pushTrail` best-effort (a failed push logs and never blocks the follow),
  `requestTrailClear` (flag consumed by the next successful discovery in `refresh()`; kept pending
  on failure so resume retries), and `foreignPageAlive` — the honest **pre-check** of a
  `DEST_NOTEBOOK_PAGE` target before leaving (a read-only `SoilDatabase.open` sealed in `finally`,
  the `LinkCatalogBinder.foreignPageIds` shape; also validates cross-notebook trail entries so a
  dead page **skips** rather than landing on the wrong page). Arrival-side: a dead
  `EXTRA_INITIAL_PAGE_ID` falls back to `refId` **silently** (covers races and keeps one arrival
  semantic; the pre-checks carry the honesty). Failure texts: no extension `links_required`;
  unresolvable / dead target `links_target_gone`; extension not answering reuses
  `links_picker_gone` (generic "didn't respond" wording — recorded).
- **`NotebookActivity`**: `EXTRA_VIA_LINK` + `EXTRA_INITIAL_PAGE_ID` (host-internal; `intent()`
  gains default params), the initial-page override applied between `open()` and the first page
  load, **both Backs funnel through one `backPressed()`** — the top-bar Back button AND the system
  back walk the trail in a via-link notebook (the scope's "returns from its Back button" is the
  visible top-bar button; caught in-phase — the toolbar callback initially still called `close()`
  directly), the two gesture delegates, and
  `close(andThen)` — the follow-out's `startActivity` runs **strictly after the seal** inside the
  existing NonCancellable close coroutine (risk 2 handled by ordering; no new race surface).
  **Line cap: the file closes at 815 (written reason):** L2's PasteFlow extraction left 779;
  L4's additions are exactly the plan's "launcher, extras, delegation lines" (risk 5) — every
  behaviour lives in `LinkFlow`/`LinkNav`/`PageGestures`; the next extraction candidate if a
  later phase needs room is the delete-sheet pair or a shared immersive helper.
- **Recreation edge (recorded, accepted):** Android redelivers the original Intent, so a process
  death + recreation in a via-link notebook re-applies `EXTRA_INITIAL_PAGE_ID` (jumping back to
  the followed page even if the user had flipped away before the kill). Same family as the L2/L3
  half-dead-showing edges; L5 reviews together.
- **User-checklist finding (SNN) → fixed in-phase — "created link showed only a dashed outline;
  lassoing brought it back."** Not a crash: the SNN's crash buffer was empty and the events buffer
  showed no extension kill all session (and the composite never involves `NSE · Links` anyway —
  chrome flag only). Mechanism: the post-create page reload presents its frame **at once** with
  the composite not yet built (dashed cache-miss placeholder), and the composite-built repaint is
  `whenPenIdle`-gated — a pen *hovering* over the fresh link (EMR hover counts as active) held
  that repaint back for as long as the user examined it; the lasso "fixing" it was the pen finally
  lifting. The H5 failure family (hover held the toolbar back), same fix shape:
  **`RenderFlow.buildCompositesNow()`** — build missing composites from what is cached (Main,
  cheap blits) — called by both page-load paths (`openSession` + `navigateTo`) before their
  at-once frame. A just-created link (children cached by `retainCurrent`) now renders complete in
  the very first frame; on a first-open page, wrapped ink rasters immediately and only a
  still-unrendered child object shows an inner placeholder until its render lands (invalidate +
  rebuild unchanged). Reinstalled mid-checklist (SNN immediately; NA5C/MIP11 when reattached).
1. `:paper-screen` `PageGestures`: `onFingerTap(x, y)` + `onSwipeUp()` (mirror thresholds of
   `onSwipeDown`; both default no-ops — the Scratch Pad ignores them), + JVM-testable threshold
   logic where the existing tests allow.
2. `LinkFlow.followAt` (hit-test by z-order; resolve → validate → push → navigate);
   `links_required` / `links_target_gone` dialogs; `EXTRA_VIA_LINK` + `EXTRA_INITIAL_PAGE_ID`
   handling in `NotebookActivity.onCreate` (via-link keeps the trail; fresh open fire-and-forget
   `clearTrail`; initial page overrides `refId` once); the seal + relaunch path (close sequence
   reused; `startActivity` then `finish`; one live session at a time — risk register 2).
3. Swipe-up + Back-button walking (shared `LinkFlow.walkBack` — pop, skip dead, navigate or
   seal + relaunch with the flag; empty trail → swipe silent / Back closes to library).
4. Docs: `docs/notebook.md` §Link follow + trail; `docs/library.md` untouched (no library entry
   point in this arc).

**Tests**
- JVM: trail walk logic over a fake client (skip-dead, empty, same-vs-cross notebook branches).
- Claude-side per device (adb can drive taps + swipes): follow each destination kind; A→B→C chain
  then swipe-up ×3 lands back in A on the origin pages; Back button in C walks to B; trail
  survives a force-stop mid-chain (persisted store); fresh library open clears it (swipe-up
  silent); link to a deleted page → honest dialog; uninstall the extension → tap shows
  `links_required`, swipe-up silent, Back → library; write-over-a-link never follows (pen);
  timings tap→resolve and seal→target-opened recorded per device.
- **User device checklist** (~9 items: the chain story by eye on all three, incl. EPD cleanliness
  across the two seal+relaunch hops and the pen-vs-finger rule).

**User checklist as issued (2026-08-20 — run on SNN, NA5C and MIP11; link creation is lasso-gated,
so the chain story is yours):**
1. **Pen vs finger** (Q4 of the arc): lasso ink → Link → **This notebook** → pick a page → OK.
   Write **over** the link with the pen — ink lands, nothing follows. Then a bare **finger tap** on
   it → the notebook flips to the target page. One-finger **swipe up** → back where you started.
2. **Cross-notebook follow**: create a link in **Notebook** mode → finger-tap it → the origin
   closes and the target notebook opens on its last-open page. The only feedback during the seal is
   the target's "Opening…" (L4 Q1 — the tap feels quiet on SNN for a moment; an impatient second
   tap is harmless).
3. **The chain**: A → B (Notebook link) → C (**Notebook page** link from B into a third notebook) —
   then **swipe up twice**: C → B on the exact page you followed from, then B → A likewise. EPD
   clean across both seal+relaunch hops (no ghosting, "Opening…" each hop).
4. **Force-stop mid-chain** (the persisted trail): follow A → B again, then ask Claude to
   `am force-stop` the extension (or Settings → Apps → Force stop) — **swipe up still walks back
   to A** (the trail lives in the host-owned store; the pop restarts the extension's process).
5. **Back button**: follow A → B → the **top-bar Back** returns to A (walks the trail, same as
   swipe-up). In A — trail now empty — Back goes to the **library** (normal close). System back
   behaves identically.
6. **Fresh open clears**: after any chain, reopen a notebook **from the library** → swipe up is
   **silent** (the trail was cleared by the fresh open).
7. **Dead target**: link to a page of another notebook, then delete that page (open the target
   notebook, delete it, come back), tap the link → an honest "This link's target no longer
   exists." dialog, no navigation, no trail entry. Undo in the target brings the page back → the
   tap follows again.
8. **Dead trail entry is skipped silently** (L4 Q2, same-notebook variant): on page 1 create a
   link to page 3 → tap it (lands on 3, trail holds page 1) → flip back to page 1 and **delete the
   page** → swipe up → **nothing happens** (the dead entry was popped and skipped, trail empty —
   no dialog).
9. **No extension**: disable `NSE · Links Dev` → tap a link → "This link needs the NSE · Links
   extension."; swipe up silent; Back in a via-link notebook → straight to the library. Re-enable →
   follows work again (resume or reopen).
10. **60-second regression**: L2/L3 picker flows (Link, Edit prefill, create-in-picker page), the
    eraser takes a whole link, Contents **swipe-down** still opens (the vertical swipe now splits
    on direction), multi-finger undo/redo taps and the long-press page delete unaffected by the
    new tap recogniser, Scratch Pad round trip.

**Close-out:** as L0.

---

### Phase L5 — Review, boundary audit, docs freeze
**Status:** 🧪 Built + device-verified (2026-08-20) — the condensed user eye pass pending

**Goal:** the arc is reviewed, audited, documented, probe-free and frozen.

**Questions resolved at phase start (wizard, 2026-08-20):**
1. **Freeze as built** — no user follow-ups beyond the flagged items; the review pass judges the
   L1–L4 flagged list and fixes or explicitly accepts each, recorded in the Outcome.
2. **Remove both probes** ("Probe links" + "Create test link") and the extension-side `beginPick`
   `listFolder("")` count log — the arc-6 (S3) precedent.
3. **No `/code-review` this phase** (user's call — "Let's skip the code-review on this one"):
   L5 runs Fable's own pass over the flagged items + the boundary audit instead of the
   multi-agent review; the review base `0f91ed5` stays recorded should a later review want the
   arc's range.

**Build notes (recorded during the phase; the Outcome finalises them):**
- **Probes removed** (Opus agent, Fable-reviewed): the debug ⋯ "Probe links" + "Create test link"
  blocks, `probeLinks` and the three-lambda plumbing gone from both `NotebookDebugMenu` twins and
  the `NotebookActivity` call; the three debug strings; the ext-side `beginPick` `listFolder("")`
  count block (+ its now-orphaned `Log` import); two stale "removed in L5" comment references in
  `LinkFlow` cleaned. Grep sweep: zero probe references left in code (the one remaining hit is
  the debug menu's own header KDoc naming the removed items, intentional).
- **Fable's review pass over the L1–L4 flagged items** (walked against the code — verdicts):
  - **FIXED — the L2 `catalog:` prefix**: `LinkPickerActivity.failed()` showed
    `IllegalStateException` messages verbatim, so the host's `catalog: <Class>: <msg>` rethrow
    could reach a user dialog. Per the contract only `IllegalArgumentException` refusals carry
    user-honest text — the picker now shows IAE verbatim and everything else generic
    (`links_catalog_failed`).
  - **FIXED — the L2 cosmetic log**: `LinkClient.finish` logged "endPick ok" even when a dead
    bind skipped the call; now logs "endPick skipped (dead bind)".
  - **ACCEPTED** (each honest and by design, recorded in `docs/links.md` §Traps where user-facing):
    host-process death mid-showing → ext-side `DeadObjectException` → generic dialog + stay (L2);
    the relay's sticky `prepared` slot (L3 — recreation must re-find it); a mode switch during
    `prepareNewNotebook`'s IO creates in the previously browsed folder unshown (L3 — benign);
    a stale Edit-prefill anchor → the honest "unknown page" dialog (L3); host death mid-showing +
    "New notebook" → IndexGuard bounce tears the task down (L3 — the same half-dead-showing
    family); Intent redelivery re-applies `EXTRA_INITIAL_PAGE_ID` after a process death in a
    via-link notebook (L4).
- **Docs**: `docs/extensions.md` — §LinkProvider / §The Links extension / host behaviour
  finalised (+ the L4 follow & walk-back paragraphs), rules 28–31 under the new §"Adding a
  navigation point (arc 7 pattern)", boundary-audit rows 33–37 + the L5 re-walk of rows 1/6/7
  for `LinkClient`'s two modes and the catalog's nested calls, "Writing an extension" item 12
  (providing link meaning). **`docs/links.md` written** (ownership split, user surface, failure
  matrix, deviations-from-original table, traps). `docs/notebook.md`: stale debug-item reference
  cleaned, undo table gains the three link actions + `linkIds`/`links` on `Moved`/`ObjectsDeleted`.
  Paper `CLAUDE.md`: all eight plans complete + frozen, no active arc, Links bullet final.
- JVM green (ten modules) + `:app` debug/release + `:ext-links` debug compile after every change;
  freeze build installed on SNN / NA5C / MIP11 (both packages re-enabled on the BOOX).

**Claude-side device runs (one Sonnet agent per device, 2026-08-20): all 9 checks PASS ×3 on the
freeze build** — packages all enabled (BOOX freeze list clean start AND end); the page-load
"… N links …" line + fresh-open `clearTrail ok` on all three; **the debug ⋯ sheet shows only
"Recognize page (ML Kit)" — both probe items gone** (screenshot-verified per device); empty-trail
swipe-up silent (`popTrail: empty`); a **real follow + swipe-up walk-back on every device**
(SNN: Test 06 → Test 07 cross-notebook and back to the exact origin page, tap→resolve 64 ms ·
NA5C: Test 03 cross-notebook round trip, 18 ms · MIP11: 23 ms — its only surviving test link is a
same-page self-link, so the full resolve→push→navigate→pop round trip ran without a visible flip;
test-data gap, not a regression); both `am start` refusals hold ×3 (picker `refused caller
(none)`; the new-notebook screen never reaches RESUMED); binds = unbinds; crash buffers empty;
flip regression clean. Residual test-data drift noted (SNN Test 07's L4 blank pages; the MIP11
self-link) — user may tidy or ignore.

**Deliverables**
1. Review findings fixed or explicitly accepted (each recorded here in the Outcome).
2. `docs/extensions.md`: rules 28–31 (§"Adding a navigation point (arc 7 pattern)"), boundary-audit
   rows 33+ + the rows-1/6/7 re-walk for `LinkClient`, §"LinkProvider (contract)" / §"The Links
   extension" / host-behaviour sections final, "Writing an extension" gains the links paragraph.
3. `apps/notesprout_paper/docs/links.md` written and frozen (data model, contract, picker, follow,
   trail, failure matrix, deviations from the original: no dashed chrome, eraser parity choice,
   re-parent model, trail in the store).
4. Probes removed; `CLAUDE.md` (paper) Links bullet final; root `docs/links.md` untouched (it
   documents the original app).
5. Memory file `project_paper_links.md` updated to ARC COMPLETE + FROZEN; `MEMORY.md` line.

**Tests:** JVM green; the L1–L4 user checklists' condensed regression subset re-run per device
(Claude agents + user's eye pass); no probe remnants (`grep` sweep).

**User checklist as issued (2026-08-20 — the condensed eye pass on SNN, NA5C and MIP11; the
lasso-gated flows the agents can't reach):**
1. **Probes gone:** notebook ⋯ shows only "Recognize page (ML Kit)" — no "Probe links", no
   "Create test link".
2. **The lifecycle in one pass:** lasso some ink → **Link** → pick a page of this notebook → OK
   (underlined link) → bare **finger tap** follows it → **swipe up** returns → lasso the link →
   **Edit** → flip Style to None → OK (underline gone, one undo reverts) → **Unlink** dissolves it.
3. **Cross-notebook + Back:** create a Notebook-mode link → tap → the target opens → **top-bar
   Back** walks back to the origin page → Back again → the library.
4. **Eraser** takes a whole link in one pass; undo brings it back.
5. **Create-in-picker refusal:** picker → Notebook mode → New folder → name `..` → the honest
   library refusal text, prompt + typed text kept (no `catalog:` developer prefix — the L5 fix).
6. **60-second regression:** write / flip / multi-finger undo-redo, Contents swipe-down, Scratch
   Pad round trip, long-press page delete — unchanged.

**Close-out:** status ✅ ARC COMPLETE; commit + push; this file frozen.

---

## Appendix A — Constants + strings (this arc)

Contract constants: see Architecture §"Contract additions" (single source of truth).

Core strings (`:app` `strings.xml`, exact texts settled at their phase, names fixed now):
`links_required` ("This link needs the NSE · Links extension.") · `links_target_gone` ("This
link's target no longer exists.") · `links_picker_gone` ("The Links extension didn't respond.") ·
`links_choice_invalid` (refused result) · toolbar labels `Link` / `Edit` / `Unlink` (core-drawn,
≤ 6 chars) · long-press hints ("Create link" / "Edit link" / "Remove link").

Extension strings (`:ext-links`): picker title "Link to", mode labels (L2 Q1), chrome labels
"Underline" / "None", create-button hints (L3), its own failure dialog texts.

Drawables (`:paper-screen`): `ic_link`, `ic_link_off` (+ the Edit glyph per L1 Q4) — Tabler
outline, 24 dp, inkBlack stroke 2. Look before you download.

## Appendix B — Allowed dependencies

`:ext-links`: `:extension-api` (+ `:paper-screen` per L0 Q1) + androidx (core-ktx, appcompat,
activity-ktx) + kotlinx-coroutines. **No** Room, SQLCipher, serialization, Material, g-paper
direct. `:app` / `:extension-api` / `:paper-screen`: no new dependencies at all. No new Gradle
dependency anywhere without explicit discussion (standing rule).

## Appendix C — Build & install (this arc)

Via the `device-build-install` skill (Paper section). New line after L0:

```sh
adb -s <serial> install -r ext-links/build/outputs/apk/debug/ext-links-debug.apk
```

BOOX: the freeze / `pm enable` dance applies to `com.symmetricalpalmtree.notesprout.ext.links.dev`
like every extension.

## Appendix D — Reference map + risk register

**Read before the matching phase:** root `docs/links.md` (the original — L1/L2/L4);
`PAPER_SCRATCHPAD_PLAN.md` §Architecture (held bind + screen recipe — L0/L2);
`PAPER_OBJECTS_PLAN.md` (object rows, render pass, undo shapes — L1);
`PAPER_CONTENTS_PLAN.md` (appended-method probe + batched describe — L0);
`docs/crypto.md` (read-only other-notebook opens — L0/L3); g-paper `docs/api.md` (selection,
`addStrokes`/`removeStrokes`, content renderers — L1).

**Risk register:**
1. **Finger-tap discrimination** — tap vs flip-swipe vs long-press on EPD digitizers; thresholds
   live in `PageGestures` with the existing slop/timeout constants; MIP11 is the reference,
   BOOX/Ratta verified by hand. Mitigation: sub-slop + sub-long-press window, selection-inactive
   gate; L4 device items first.
2. **Seal / reopen races** — walking back to a notebook whose `NonCancellable` seal from the
   follow-out may still be running (fast A→B→swipe-up). `NotebookSession.open` is exists-guarded
   but a WAL checkpoint in flight is not a normal open. Mitigation: the close sequence completes
   before `startActivity` runs (it already awaits the seal in `close()`); verify the ordering on
   the slowest device (SNN) with a same-file A→B→A hammer test in L4.
3. **Composite raster fidelity** — core-rasterized wrapped strokes must look like the live ink
   (width, pressure taper). The cover-snapshot path is the starting point; if it visibly diverges
   at 1:1, L1 records the gap and the fix (never a g-paper workaround host-side).
4. **Catalog `listPages` latency** — a cold encrypted notebook costs a KDF (~2 s SNN). The picker
   shows progress; the host caches nothing. If drill-in feels broken on SNN, L2 records it and the
   fix candidate (KeySession's cached raw key covers the common case already).
5. **`NotebookActivity` line cap** — all new screen logic lands in `LinkFlow` / `LinkStore` /
   collaborators; the Activity gains only the launcher, the two extras, and delegation lines.
6. **Page-delete cascade** — link grandchildren must ride `Structural` snapshots or undo corrupts
   the page; `deepChildIds` is JVM-tested before any device run.
7. **First host-implemented multi-method callback binder** — `LinkCatalogBinder` is a bigger lens
   than the store; the uid-gate + revoke discipline is copied from `ExtensionStoreBinder`, its
   mutation half only live during a showing (rule 29), and row-33+ audits it explicitly.
