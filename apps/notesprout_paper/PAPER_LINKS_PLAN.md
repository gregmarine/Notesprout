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
> **Status: L0 ⬜ · L1 ⬜ · L2 ⬜ · L3 ⬜ · L4 ⬜ · L5 ⬜**

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
  every browsed notebook); the current notebook's labels may use the outline (L2 question).
- **Eraser parity with the original** (hardware/scribble eraser deleting a whole link) — Paper's
  eraser doesn't erase objects today; links behave like objects (L1 question confirms).
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

New module **`:ext-links`** (the seventh extension APK). Dependencies: `:extension-api` +
`:paper-screen`? — **L0 phase-start question 1**: the picker is a full e-ink screen and wants the
design system (theme, dimens tiers, `Dialogs`, `ActionSheetDialog`, Tabler drawables), which lives
in `:paper-screen`; but `:paper-screen` carries g-paper (`api`) and therefore the Onyx SDK
(~25 MB APK, as `:ext-scratchpad` proved). Recommended: **accept `:paper-screen`** (one visual
vocabulary, no sibling copies — the module's g-paper surface classes simply go unused); the
alternative (splitting a `:paper-style` resources-only module out of `:paper-screen`) is recorded
as the escape hatch if the size ever matters. Gradle enforcement grows the same way as arc 6:
`:ext-links → :extension-api` (+ `:paper-screen` if Q1 says so), never `:app`.

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
  for the underline · `"order"` = z-order (`MAX+1` like objects) · `flags` = chrome cache
  (`0/1` mirror of the last `chromeOf` answer so a page renders correctly before/without the
  extension; **L1 question 4** confirms this one non-opaque crumb or drops it).
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
**Status:** ⬜ Not started

**Goal:** the point exists end to end with nothing user-visible: contract compiled into
`:extension-api`, `NSE · Links` installs and is discovered, `LinkClient` can hold a pick showing
(`beginPick` with a live catalog binder → `takeResult` null → `endPick`) and one-shot `resolve` /
`chromeOf` / trail calls round-trip, all proven by a debug ⋯ probe.

**Questions to resolve at phase start:**
1. `:ext-links` depends on `:paper-screen` (rec. — design system + `Dialogs`; ~25 MB debug APK
   accepted like the pad) / `:extension-api` only + a minimal own style copy?
2. The payload grammar (`"L1|chrome|kind|notebookId|pageId"` with `|` forbidden in ids — rec.) —
   confirm, or a different shape?
3. Debug probe: notebook ⋯ → **"Probe links"** = hold → `beginPick` (store + catalog + null edit)
   → the extension logs the root folder count via `listFolder("")` → `takeResult` (null) →
   `endPick`; then `resolve` + `chromeOf` of a fixed payload; then a trail push/pop/clear round
   trip — logs only, removed in L5 (rec. yes).
4. `flags` column as the chrome cache on the link row (rec. yes — one int, lets a page render
   its underlines with the extension missing) / pure `chromeOf` every load?

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
**Status:** ⬜ Not started

**Goal:** a link is a first-class page citizen created from any eligible selection (via a debug
item with a fixed payload until L2), rendered as its wrapped content + underline, atomic under
lasso / move / delete, fully undoable, and unlink restores the content — all with the extension
only consulted for `chromeOf`.

**Questions to resolve at phase start:**
1. Wrap = **re-parent** children rows (rec.) / deep-copy + soft-delete like the original?
2. Eraser: links immune like objects (rec.) / the original's erase-whole-link?
3. Tap inside a **selected** link (`onSelectionTapped`) = no-op (rec. — follow is for unselected
   links, L4; edit lives on the toolbar) / opens Edit?
4. The Edit glyph: Tabler `link-plus` / `edit` (look at the catalog + the toolbar row width first).
5. Debug ⋯ **"Create test link"**: wraps the current selection with a fixed `DEST_PAGE` payload to
   page 1 (rec. yes; removed in L5).

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
3. `chromeOf` on page load for link rows (batched, session cache, `flags` mirror per L0 Q4).
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

**Close-out:** as L0.

---

### Phase L2 — The picker: choose an existing target, create + edit end to end
**Status:** ⬜ Not started

**Goal:** "Link" on an eligible selection opens the extension's picker; choosing a page of this
notebook / another notebook / a page of another notebook + chrome creates the link (one undo
step); "Edit" reopens it pre-populated and patches payload + chrome only.

**Questions to resolve at phase start:**
1. Mode wording + order (rec.: "This notebook" · "Notebook" · "Notebook page" as a three-way
   toggle, the original's trio) — and the current page is excluded from "This notebook"'s grid?
   (rec. yes — a link to its own page is a no-op trap).
2. Page labels: "Page n" everywhere (rec.) / current notebook additionally shows outline labels
   when the Contents has them?
3. A filter/search field in the notebook list (rec. defer — recorded under Deferred) / include?
4. Picker list visuals: rows like the library's folder picker (rec.) / grid?

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

**Close-out:** as L0.

---

### Phase L3 — Creating targets in the picker (new page · new notebook · new folder)
**Status:** ⬜ Not started

**Goal:** the original's create-in-picker parity: from the picker, a new page (this or the other
notebook, before/after an anchor or appended), a new notebook, or a new folder can be created and
immediately picked, without leaving the flow; the host validates everything.

**Questions to resolve at phase start:**
1. New page placement: anchor selected → ActionSheet "Insert before / Insert after"; none →
   append; template inherited from the anchor (or last page; blank in an empty notebook) —
   the original's exact rule (rec.) / always append?
2. New notebook: name prompt only, created blank + unencrypted-scope under the global key like
   the library's own create path, **no template section** (rec. — the template flow needs the
   Templates extension UI; recorded under Deferred if declined) / route through the full
   new-notebook screen?
3. Name validation: exactly the library's rules incl. duplicate-sibling checks (rec.) — confirm.
4. Do picker-created pages in the **current** notebook require the notebook screen to reload its
   page list on return even on Cancel (the original's rule — rec. yes)?

**Deliverables**
1. `LinkCatalogBinder.createPage` / `createFolder` / `createNotebook` real: same code paths /
   validation as the library (`IndexRepository`, the page-insert path over the target `.soil`,
   current notebook via the live session + `writer.drain()`); refusals as typed
   `IllegalArgumentException` messages.
2. `LinkPickerActivity`: the mode-dependent create buttons (This notebook → New page; Notebook
   browse → New folder + New notebook; drilled-in pages → New page), name dialogs (design-system
   IME pattern — `:paper-screen` `Dialogs`), auto-select the created target per the original's
   rules (notebook mode auto-picks; page mode drills in).
3. `NotebookActivity`/`LinkFlow`: page-list reload on picker return (any result) preserving the
   current page id.
4. Not undoable (matches the original + the library) — recorded in `docs/links.md`.

**Tests**
- JVM: validation refusal paths.
- Claude-side per device: create page before/after/append in both notebooks (verify order + template
  inheritance), new folder + new notebook appear in the library afterwards with correct placement,
  duplicate/invalid names refused with honest messages, cancel-after-create keeps the created page
  (and the notebook screen shows the reloaded list).
- **User device checklist** (~7 items incl. the IME dance on SNN — hardware-keyboard rule n/a,
  but the Ratta IME quirks apply to the name dialogs).

**Close-out:** as L0.

---

### Phase L4 — Follow, the trail, and the way back
**Status:** ⬜ Not started

**Goal:** a finger tap on a link follows it (same page-set navigation, or seal + relaunch into the
other notebook); swipe-up walks the trail backward; a via-link notebook's Back button walks it
too; the trail persists in the extension's store and clears on any fresh open.

**Questions to resolve at phase start:**
1. Tap feedback before a cross-notebook open (the seal takes real time on SNN): rely on the
   target's "Opening…" popup only (rec.) / an origin-side overlay at tap time?
2. A popped trail entry whose notebook/page is gone: skip silently to the next (rec.) / dialog?
3. Same-notebook follows also push the trail (rec. — the original's rule) — confirm.
4. Warm-bind `NSE · Links` at notebook open when links exist on the opened page (rec. defer —
   measure tap→resolve first; the recognizer warm-up precedent if needed)?

**Deliverables**
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

**Close-out:** as L0.

---

### Phase L5 — Review, boundary audit, docs freeze
**Status:** ⬜ Not started

**Goal:** the arc is reviewed, audited, documented, probe-free and frozen.

**Questions to resolve at phase start:**
1. Freeze as built, or is there a follow-up list from L1–L4 to land first?
2. Remove both debug probes ("Probe links", "Create test link") — confirm.
3. `/code-review high <L5 review base>...HEAD` — the RANGE (the base hash is in L0's Outcome);
   confirm scope. (Trap from arc 6: the coordinator's report can fail to surface — read the finder
   agents via `ListAgents` + `TaskOutput` if so.)

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
