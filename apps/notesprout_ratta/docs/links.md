# Links — Notesprout SN subsystem doc

Arc **6 "Links"** (K1 core rows/render/ops · K2 picker · K3 create-in-picker · K4 follow + trail,
hardened at K5). A link wraps a lasso selection into one tappable navigation object: the wrapped
ink and headings stay exactly where they were written, render exactly as they did, and a finger
tap goes somewhere — a page of this notebook, another notebook, or a page of another notebook.

Fresh code. Paper's links arc (`docs/links.md` under the og app, `PAPER_LINKS_PLAN.md`) and og
Notesprout are the shape references; the payload format is **Paper's, byte-for-byte** — see
[Data model](#data-model). The one deep family delta: Paper's core treated link payloads as opaque
strings and asked its Links *extension* what they meant; in SN the core owns link meaning outright
(the arc-1 rule that the recognizer point is the only extension surface), so the codec, the
renderer and the follow all live in `:app`. Deliberate differences are listed at the end.

---

## Collaborators

| File | Owns |
|---|---|
| `notebook/LinkPayload` | the payload codec — Paper's v1 grammar; `encode` throws on caller bugs, `decode` never throws (a file is untrusted input); `chromeOf` degrades unusable → `CHROME_NONE`. JVM-tested with Paper-grammar fixtures |
| `notebook/PageLink` | the in-memory link: payload + decoded chrome + bounds + wrapped `strokes`/`headings` (page-absolute, ids unchanged); `unionBounds`/`bandBottom` reserve the underline band (ink gets the heading box's padding); `withUnderlineBand` self-heals a short one at load. JVM-tested via `LinkRows` |
| `notebook/LinkRows` | `PageLink ⇄ SoilObjectEntity` (`SoilSchema.TYPE_LINK`); `style` written null / read leniently, payload capped both directions. JVM-tested |
| `notebook/LinkStore` | `link` rows through the session's shared serial `SoilWriter`; wrap/unlink/relink/remove/restore/move, each multi-row op in **one Room transaction** via an injected `transact` lambda (JVM-testable against `FakeSoilDao`); `deepChildIds` for page delete/reconcile |
| `notebook/LinkComposite` | the wrapped content rendered to one bitmap at 1:1 page px — `padOf`/`sizeOf` add the stroke-overhang margin (eye-check #7), `build` bakes headings then strokes, never the chrome |
| `notebook/LinkRenderer` | the g-paper `ContentRenderer` (`BELOW_STROKES`): composite (or dashed placeholder) + the **live** whole-pixel underline; `update()` reconciles the composite cache (move = free reuse); live-drag pair + whole-link `hitTargets` |
| `notebook/LinkPickFlow` | the notebook screen's side of the picker: launch for create/edit, capture-at-launch, one-door `busy` released at the **top** of the result callback, K3's `createPage` relay arm + `onPagesChanged` |
| `notebook/LinkPickerActivity` | the picker screen — three modes, style toggle, paged card grids, previews, create buttons; chrome and wiring only |
| `notebook/LinkPickerModel` | every picker decision that is not a view: `modeFor`/`chromeFor` prefills, `pageCards` numbering, `gridPageOf`, `insertIndexFor`/`inheritIndexFor`/`createButtons` (K3), `composeOk`. JVM-tested |
| `notebook/PickerPageSource` | what the picker asks a notebook for, + `LinkPickerRelay` — the transfer-singleton hand-off from the live session |
| `notebook/ForeignPageSource` | a browsed notebook's **near-read-only** `.soil` open: lazy open under the global key, `withDb` mutex, `sealAsync` on a process-scoped NonCancellable job — the next instance's open **joins the previous instance's pending seal** (companion `lastSeal`), so a leave-drill → immediate re-drill can never hold two connections to one file (K5); `createPage` is its one sanctioned write (K3) |
| `notebook/PageReads` | one read-only page gather over any `SoilDao`: loose strokes/headings + links with their children. JVM-tested over `FakeSoilDao` |
| `notebook/PagePreview` | one page → one preview bitmap (white paper, headings → wrapped children → loose ink, 1 px border drawn **on** the bitmap); off-Main-safe |
| `notebook/PreviewMath` | preview sizing: real page aspect at grid-cell width, clamped 0.5–3 against untrusted dims, 1024 px edge cap. JVM-tested |
| `notebook/PageLabels` | heading-as-page-name: topmost **loose** heading by `(y, x)`, prefix-stripped; blank → null. JVM-tested |
| `notebook/PageCardGrid` | `LibraryGrid`'s geometry for page cards — deliberately dumb; the picker binds every card |
| `notebook/LinkNav` | the pure follow / walk-back planner — payload + current notebook → `Follow`/`Back` plan, ids only, no database. JVM-tested |
| `notebook/LinkFollowFlow` | whether a planned hop is still possible, and the hop itself: existence checks before navigating, trail pushes, the seal → relaunch hand-off, the dead-target dialog |
| `data/prefs/LinkTrail` + `TrailCodec` | the persisted walk-back stack (`sn_trail` prefs, ids only): cap 50, LIFO, decode-never-throws. Codec JVM-tested |
| `library/SchemePrefill` (K3) | the scheme → suggested-name rules, shared verbatim by the library's +Notebook and the picker's New notebook |
| `library/NewFolderFlow` (K3) | the whole New-folder dialog (name + scheme fields), shared by the library and the picker |

`SelectionToolbar`, `PageGestures`, `UndoRedoStack`, `NotebookSession` and `NotebookActivity` all
grew link duties — described below, detailed in [`notebook.md`](notebook.md).

---

## Data model

A link is one **additive row type** in the universal `notebook` table: `type = "link"`
(`SoilSchema.TYPE_LINK`), parented to its page — no schema version bump, no Room-hash change,
format compat with Paper untouched.

**The wrap model is re-parenting** (Paper L1): wrapping a selection inserts the link row at
`MAX("order")+1` among the page's links and flips the selected strokes'/headings' `parentId`
page → link — **no id churn, no embedded copies**; the children keep their page-absolute
coordinates and stay live rows. Unlink flips `parentId` back and soft-deletes the link row.
Both halves ride one Room transaction (plus the shared serial `SoilWriter`), so a link row and
its children's parentage are never separately visible — and big wraps chunk their id lists at 500
*inside* the transaction (SQLite's 999-variable cap; chunking loses no atomicity).

Because wrapped children belong to the link, a page's "loose" content queries no longer see them:
a wrapped heading **leaves the Contents outline** and **names no page** in the picker — one rule
for "whose heading is it", everywhere. Page delete/reconcile therefore cascades **grandchildren**
(`SoilDao.liveDescendantIds` — the page's own children *and* the links' children).

**The payload** lives in the link row's `text` column — **Paper's v1 grammar, byte-for-byte**
(verified against `PAPER_LINKS_PLAN.md` and pinned by JVM fixtures), so link rows stay
family-compatible in both directions; a cross-app id simply resolves dead (the dead-target rule):

```
"L1|<chrome>|<kind>|<notebookId>|<pageId>"
```

`chrome` `0|1` (none/underline) · `kind` `0` page-of-this-notebook (no notebookId) · `1` whole
notebook (no pageId) · `2` page of another notebook (both). Versioned tag, `|` separator (ids are
UUIDs), empty slot for each id the kind doesn't carry, 2000-char cap **in both directions** (a
file is untrusted input). `encode` throws — only our own flows compose payloads; `decode` returns
null for anything unusable (unknown version, bad kind, forbidden/oversized ids) and **never
throws**: chrome falls back to `CHROME_NONE` (content still renders) and a follow lands in the
dead-target dialog, never a crash.

Two locked family deltas from Paper's rows, both in `LinkRows`: `style` is **written null**
(Paper put its provider identity there; SN has no provider) and **read leniently** — a
Paper-created row decodes fine; and chrome is **never cached in `flags`** — parsed out of the
payload at load (`chromeOf`), held on the `PageLink`, so payload equality covers target *and*
style (which is what makes an unchanged-payload Edit a clean no-op).

**No nesting**: a link never wraps a link. Enforced at the chrome (`SelectionMode.LINK` /
`MIXED_WITH_LINK` hide the Link action — see below) and re-checked at use time in
`createLinkFromSelection`.

## Rendering

`LinkRenderer` is the arc's g-paper `ContentRenderer`, registered alongside `HeadingRenderer` at
`ContentLayer.BELOW_STROKES` (the K1 wizard's og-parity call): fresh ink written over a link stays
visible on top.

Each link draws its **composite** — the wrapped strokes + headings rendered by `LinkComposite`
into one bitmap at 1:1 page px, headings first (the paper's own layering) via the shared
`HeadingRenderer.drawHeading` recipe, strokes through g-paper's `StrokeRasterizer` (the same
renderer live ink bakes with), so the wrap is pixel-identical to what the page showed before it.
Two hard-won rules:

- **The pad (eye-check #7):** g-paper's `Stroke.bounds` is **point-tight** — no stroke width — so
  rendered ink overhangs it by width/2 + the round cap, and a bitmap cut exactly at the union
  bounds shears the outermost strokes. `LinkComposite.padOf` (`maxWidth/2 + 1` AA slop) grows the
  bitmap on every side; `sizeOf` is what the renderer's cache-reuse check compares against (a
  stale unpadded bitmap must never be "reused" at the wrong offset), and `drawLink` draws at
  `(x − pad, y − pad)` so the content lands page-exact. Bounds, hit targets and the underline are
  unchanged by the pad.
- **The hover-repaint trap (Paper L4's field finding, a standing arc rule):** `update()` — the one
  way in — runs on Main **before** the frame that must paint the result (`loadStrokes` /
  `notifyContentChanged` at both page-load sites), never behind a pen-idle gate: EMR hover holds
  `whenPenIdle` back, and a freshly created link's chrome would stay invisible while the pen
  floats over the page. The trap requires composites to *exist* before that frame, not to be
  *built on Main*: both page-load sites call `prebuild(links)` inside their suspend load block
  (in `navigateTo`, inside the buffered-commit window), which rasters the needed bitmaps on
  `Dispatchers.Default` and hands them to `update(links, prebuilt)` — a link-heavy flip or an
  undo replay's refresh never allocates page-sized ARGB bitmaps in the display frame (K5).
  Prebuilding is an optimisation only; `update` still builds anything missing (the wrap flow's
  single-link build stays synchronous on Main, same-frame by design).

The composite is translation-invariant (children ride the bounds), so **a move never rebuilds
it** — `update()` reuses a cached bitmap whose padded size is unchanged and drops departed links.
It changes only when the wrapped content set does (create / unlink / undo-redo), which all hand
the renderer a fresh `PageLink` via a page reload or `syncLinkRenderer` (the Edit path). A
composite that cannot build (degenerate size, OOM — bounds are untrusted input, edge-capped at
4096) leaves the standard dashed placeholder.

The **underline chrome is never baked**: a solid inkBlack line across the bounds' bottom, drawn
live from the link's decoded chrome inside the clearance band `PageLink.bandBottom` reserves at
wrap time (`unionBounds`) — so the chrome never overlaps the writing and a style-only Edit
repaints without a rebuild. Three rules keep it looking like og's:

- **Whole pixels, or it reads grey.** It is a `drawRect` of `round(density)` px (≥ 2) with its
  edges on integers, not a 1 dp `drawLine`. 1 dp is 1.875 px on the Nomad and the line's centre
  landed wherever the bounds' float bottom put it, so Skia's non-antialiased ">50 % of the pixel"
  rule kept two rows for some links and a single hairline row for others — the "faint underline"
  the user reported. A filled rect on integer edges is the same weight every time and every pixel
  of it is fully black.
- **Ink gets the box a heading already has.** The band sits `UNDERLINE_CLEARANCE_DP` (4 dp) below
  the lowest wrapped **box** bottom. A heading's box *is* its bounds — `HeadingTypography.PADDING_DP`
  (8 dp) of breathing room is built in around its line, and that is the gap the user calls right.
  Loose ink has no box, so `bandBottom` gives it the same one: a stroke's box is its ink extent
  (`bounds.bottom + width / 2` — `Stroke.bounds` is point-tight, the trap `LinkComposite.padOf`
  pads for) plus that same 8 dp. Ink and headings then arrive at the line looking alike; measuring
  from the point bounds alone let half the stroke eat the band, which is why only **stroke-only**
  links looked cramped.
- **Old links self-heal.** A link written under an earlier, tighter band would keep its stored
  bounds forever, so `PageLink.withUnderlineBand` (applied by `NotebookActivity.withUnderlineBand`,
  next to the heading remeasure, before `prebuild`) re-applies the wrap-time formula at page load
  and **only ever grows** — a foreign link may wrap children this build cannot decode, so shrinking
  to the union of what we can read would cut it down. In memory only; the row is corrected whenever
  the link is next written.

`hitTargets()` exposes each link's whole bounds: lasso selection
and the follow tap are both whole-link. The live-drag pair (`draw` with exclusions +
`drawObject`) lets a dragged link ride under the pen as its real self.

## Ops — the selection toolbar, eraser, lasso, undo

`SelectionMode` classifies what the lasso caught into five modes; the two link-bearing ones are
what enforces no-nesting. The bar (order: Delete · H · Link · Edit · Unlink):

| Mode | Selection | Offers |
|---|---|---|
| `STROKES` / `HEADING` / `MIXED` | link-free | **Link** (wrap → the picker in create shape) |
| `LINK` | exactly one link, nothing else | **Edit** (picker prefilled) · **Unlink** |
| `MIXED_WITH_LINK` | a link plus anything | neither — Delete only |

- **Wrap** (`createLinkFromSelection`): capture-at-tap discipline (the heading-convert precedent),
  no-nesting re-check at use time, bounds from `unionBounds` + the underline band, one frame. The
  smart-lasso session survives a wrap exactly as it does a heading conversion —
  `pendingSelection` is a select-successor lambda, so the new link comes up selected.
- **Unlink**: store → `Action.LinkUnlinked` → drain → `refreshToPage` (the reload is the sync).
  Undo re-wraps via `LinkStore.relink` — same row id, same geometry, same children.
- **Edit** (`applyLinkEdit`): rewrite the payload (`updatePayload`), patch the working copy,
  `syncLinkRenderer`, record `Action.LinkEdited` (payload before/after), re-select. An
  unchanged payload is a no-op — no write, no undo step.
- **Eraser**: a link erases **whole**, wrapped content and all — the eraser can never reach
  inside one. `onContentErased` splits headings from links: a sweep that took a link records
  **one `Action.Deleted`** covering both kinds; heading-only sweeps keep `HeadingDeleted`.
- **Scribble** (arc 14): a link is **no longer scribble-immune**. It was, on Paper L1's user call
  ("a scribble over wrapped ink must not shred a navigation object"), and the immunity was total
  rather than partial — a wrap re-parents its children off the page, so a scribble over wrapped
  ink found nothing on the stroke list either. **The user reversed it on 2026-08-26**: a scribble
  now erases a link exactly as the eraser tool does, whole. What protects a link is not immunity
  but *reach* — the engine decides content by **penetration** (≥ 14 dp of scribble path inside
  the bounds, `EraseHitTest.scribbleContentIds`), so ink scribbled out beside a link leaves it
  standing. It arrives with any ink the same gesture took, in one `onScribbleErased`, and is
  recorded as one `Action.ScribbleErased` — one gesture, one undo step.
- **Lasso move/delete** are first-class: `Moved` carries `linkIds` (`LinkStore.move` re-encodes
  stroke children's blobs and `moveBy`s heading children + the row, all in one transaction —
  children stay page-absolute), `Deleted` carries link snapshots (`remove`/`restore` soft-delete
  and revive the link **and everything it wraps** in place).
- **Revives are in place** (`LinkStore.reviveOrInsert`, K5): `relink` and `restore` un-delete an
  existing row by id — keeping the geometry and the **store-assigned z-order** the row already
  carries — and upsert the snapshot only when no row exists. The host's snapshot holds
  `order = 0` (the store assigns the real `MAX(order)+1` inside `create`'s transaction), so
  writing it over a live row would sink the link below its overlap-mates and hand the
  topmost-last follow tap to the wrong link.

Undo actions: `LinkCreated` / `LinkUnlinked` / `LinkEdited`, plus `linkIds` on `Moved` and link
snapshots on `Deleted` — all replayed through the store then a page reload, the DB-is-truth rule.

*(K1's temporary debug flask — "Create test link" on the selection toolbar — was removed in K5.)*

## The picker

`LinkPickerActivity` is the one screen behind both Link and Edit: it answers "where does this
link point" and returns a single payload string. In-app (`IndexGuard`, portrait, e-ink chrome,
`exported="false"` — an external `am start` is refused by Android itself), launched by
`LinkPickFlow` via ActivityResult.

**What crosses where (the relay rule):** the current notebook's pages reach the picker through
`LinkPickerRelay` — the family's transfer-singleton shape. The relay's `source` closes over the
**live session** (the notebook screen stays alive underneath), because the current `.soil` is
already open and **one file never has two connections**; the Intent carries only the edit-prefill
payload (ids, never content, never a key); the result carries only the composed payload. A relay
found null in `onCreate` means the process was rebuilt while the picker was up → finish canceled.
Everything the *application* of a result needs — the wrapped selection, the edited link — is
captured in `LinkPickFlow` **at launch**; a host process death loses the capture, and the
redelivered result then explains honestly (`link_result_lost`) rather than applying a payload to
a guess. One door (`busy`), released at the **top** of the result callback — the callback runs
*before* `onResume` (the S2 latch trap). And `begin()` re-checks after its one suspension (the
pre-launch `drain()`): a screen that closed in that gap has already dropped the relay and may be
sealing the session, so the launch bails instead of re-arming the relay over a dead notebook
(K5) — the tap is simply lost with the screen.

**Three modes** (og/Paper's trio): **This notebook** (the open notebook's pages, minus the page
being written on), **Notebook** (the library browsed exactly as the library browses it — folders
navigate, notebooks select), **Notebook page** (the same browse; a notebook drills into its
pages). The current notebook is hidden in both browse modes — and `composeOk` refuses a
self-target anyway, because "hidden from the grid" is a chrome fact and the refusal is the
contract. **Numbering never drifts**: positions are computed over the full page list and only
then is the current page dropped, so the page after the excluded one still reads "Page 4".
Browse position is kept across mode switches; nothing is ever disabled or greyed (invisible on
e-ink) — a button that can't apply is `GONE`, and an OK with nothing chosen explains via a
problem dialog.

**Page previews** (og feature, Paper skipped): every page card shows the page in miniature —
white paper, headings → each link's wrapped children → loose ink (the paper's layering), scaled
undistorted to the grid-cell width at the **real page aspect** (`PreviewMath`, clamped against
untrusted foreign dims) — rendered async per grid page behind placeholder cards. The 1 px card
border is drawn **on the bitmap** (eye-check #7: a border on the ImageView gets overpainted by
the fit-centred paper). The cache is **per-showing** (`(bitmap, title)` per page, dropped whole
past ~3 grid pages' worth — deliberately not an LRU, cleared on drill-exit and gone with the
screen), so a preview is always of the notebook as it is now and there is no staleness machinery
to be wrong. Notebook cards keep their cover snapshots.

**Heading page names**: a page card reads "n · <topmost heading>" (`PageLabels` — topmost by
`(y, x)`, prefix-stripped, **loose headings only**), plain "Page n" otherwise.

**Foreign notebooks** answer from `ForeignPageSource` — a lazy, **near-read-only**
`SoilDatabase.open` under the global key (never creates the file), at most one instance at a
time, every read under a mutexed `withDb`. The picker MUST `sealAsync()` it when the drill is
left (mode switch, another notebook, destroy); the seal runs on a **process-scoped
NonCancellable IO job** because the destroy path's lifecycle scope is already dead — an unsealed
open strands the connection and its WAL sidecar for the process lifetime (the R6 lesson). A
failed open answers empty everywhere; the honest "target is gone" moment belongs to the follow,
not to browsing.

### Create-in-picker (K3)

The target may not exist yet, so whichever grid is on screen carries its own create
(`LinkPickerModel.createButtons` — a page grid offers **New page**; a browse offers **New
notebook** and **New folder**; never both), and the created thing becomes the selection — a
create and a pick are one gesture. **Picker creations are not undoable** (the og rule).

- **New page**: a selected card anchors an "Insert before / Insert after" sheet; nothing selected
  appends — and so does an anchor that vanished underneath the picker (`insertIndexFor` never
  redirects to the old index). Template + authored size inherit from the anchor, else the last
  page (`inheritIndexFor`). In the **current** notebook the create runs host-side through the
  relay's `createPage` arm, under the page-op lock, via `NotebookSession.insertAt` — an insert
  that **never navigates** (`currentIndex` re-anchored by id, no template load, no undo entry).
  A page that landed makes every `Structural` undo snapshot stale, so the return fires
  `onPagesChanged` (host: `undo.clear()` + indicator + Contents refresh) **before** the
  RESULT_OK check — a cancel still clears — and before `applyCreate`, so the new link's
  `LinkCreated` survives the clear. In a **foreign** notebook it is `ForeignPageSource.createPage`
  — that open's one sanctioned write: same anchor rules, upsert + renumber in one transaction
  inside the seal lock, index mirrored after (page count + clock stay honest).
- **New notebook**: the **real** `NewNotebookActivity`, launched from the picker with
  `EXTRA_DEFAULT_NAME` prefilled through `SchemePrefill` on the browse folder's scheme — the
  arc-5 rules shared verbatim with the library's +Notebook (lazy sibling fetch only when the
  scheme holds `{n}`; an expansion the library would refuse falls back to the default — naming
  never blocks the create). On return: auto-selected (Notebook mode) or drilled into (Page
  mode). Latch released at the top of the result callback (S2).
- **New folder**: `NewFolderFlow` — the library's dialog extracted whole (name + scheme fields,
  identical validation order name → scheme → duplicate → create → save scheme, the `accepting`
  re-entry guard), then navigate in.

### Style

The chrome is **per-link, underline by default**, underline/none only (og's dotted-chevron
excluded — a locked decision). The style latch rides the payload, so Edit covers it; a
style-only Edit of a link whose target has died deliberately **keeps the dead target** — the
honest dialog belongs to the follow.

## Follow + trail (K4)

**A follow is a finger tap, never the stylus** (og/Paper) — the pen writes, including *over*
links. The tap comes from `PageGestures.onFingerTap` (an escrowed inverse recogniser: sub-slop,
under the long-press timeout, single-finger, pen-gated like every finger gesture) and hits
whole-link via bounds, **topmost last** (later rows draw over earlier ones — the last match is
what the user sees). A tap on a *selected* link is a no-op (the selection owns the touch); a tap
that hits no link never takes the flow's door.

`LinkNav` plans, `LinkFollowFlow` validates **before navigating**, then hops:

- **Same notebook** (`KIND_PAGE`, or `KIND_NOTEBOOK_PAGE` naming the current notebook): page
  still in the session's list → push the origin, `navigateTo` under the page-op lock. Plans carry
  page **ids**, never indexes — the list can change between tap and hop.
- **Cross-notebook**: the index row must be an alive **notebook** (a payload is untrusted file
  input — a folder id must not launch a notebook screen), and a page target must be a live page
  row of that notebook, checked by `foreignPageAlive` — through **`SoilDatabase.readOnce`**, the
  single owner of the one-shot open → read → always-seal ritual (K5: never hand-roll that shape
  at a call site), never a second connection to the live session's own file. Then: tap-time
  **"Opening…" overlay** (`OpeningOverlay.showThen` — feedback frame first), and
  `close(andThen)` — **seal strictly before launch**, one live session per `.soil` — into a new
  `NotebookActivity` with `EXTRA_VIA_LINK` + `EXTRA_INITIAL_PAGE_ID`. A whole-notebook target
  (null pageId) opens at its own remembered page (`refId`).
- **Self-referential notebook target**: a silent no-op — our picker refuses to compose one, and
  "reopen the notebook you are in" has no honest meaning. A **page targeting itself** (foreign or
  hand-edited payload) is the same silent no-op (K5): pushing would stack self-entries that eat a
  real walk-back hop each and crowd genuine origins off the capped trail.
- **Dead or unusable target**: the **dead-target dialog** — a problem dialog (never a toast),
  with distinct wording for notebook-gone / page-gone / payload-unreadable and a positive
  **"Edit link"** button that opens the picker prefilled to retarget on the spot. The link row is
  never touched: a target gone today may be restored from a backup tomorrow.

One door (`busy`) guards both entry points; a hop that leaves the screen keeps it set forever —
the seal → launch hand-off is asynchronous and a second tap in the gap must stay harmless. No
frame-silence exception is claimed: both entry points are finger gestures behind `PageGestures`'
pen gate.

**The trail** (`LinkTrail`, prefs `sn_trail` — persisted because a cross-notebook follow is a
real Activity hand-off and process death mid-story must not strand the user): **every successful
follow pushes the origin** (notebook + displayed page) before navigating, in-notebook hops
included, so a page → page → page story walks back a page at a time; back-and-repeat can never
stack duplicates because walking back *pops*. Ids only — prefs are plaintext, and a name's only
home is the index. Cap 50, oldest dropped; `TrailCodec.decode` treats the stored JSON as
untrusted (corrupt → empty, over-cap → truncated on read too), and the cap doubles as the
walk-back's loop bound so the two can never disagree.

**Walking back**: **swipe-up** on the paper (the flip's own thresholds, sign-routed at the same
`ACTION_UP` as the Contents swipe-down — mutually exclusive by construction), and in a
**via-link notebook both Backs walk the trail too** (toolbar ← and system Back, funnelled
through `backPressed()` — which walks only while the screen is actually **open**: during the
opening window, and once closing, it falls through to `close()` instead of letting `walkBack`'s
alive/busy door swallow the press, so Back can always cancel a slow open — K5). A dead trail
entry is **skipped silently** — the user asked to go back, not to be told about a page they
deleted; an exhausted trail means "nothing to go back to": the swipe-up ignores it, Back on a
via-link screen closes to the library. A **fresh, non-via-link open of any notebook clears the
trail** — gated on `savedInstanceState == null` like the initial-page consume, because a
recreate or a post-process-death task rebuild is *not* a fresh open, and the trail is persisted
precisely to survive that death (K5): a new story must not walk back into someone else's, but a
rebuilt screen is the same story.

**Restore honesty**, the two K4 fixes over Paper's accepted quirks:

- `EXTRA_INITIAL_PAGE_ID` is **consumed once** — read only when `savedInstanceState == null` — so
  a recreated via-link notebook lands on its *remembered* page, not back on the link target the
  redelivered Intent still names.
- `BrowseState.lastOpenViaLink` records how the notebook was opened, so a cold launch restore
  reopens a via-link notebook **as** via-link: the trail survives a mid-chain force-stop, and
  without the flag the restore would read as a fresh open and clear it.

## JVM tests

`LinkPayloadTest` (round-trips, Paper-grammar fixtures, decode rejections, caps),
`LinkRowsTest` (row mapping, lenient `style`, `unionBounds` + `bandBottom` + `withUnderlineBand`), `LinkStoreTest`
(wrap/unlink/relink/remove/restore/move transactions over `FakeSoilDao`, chunking, K5's
revive-in-place order preservation),
`LinkCompositeTest` (pad/size math), `LinkPickerModelTest` (modes, prefills,
exclusion-beside-numbering, `gridPageOf`, K3 placement/inherit/buttons, `composeOk` incl. the
self-target refusal), `PageLabelsTest`, `PageReadsTest`, `PreviewMathTest`, `SchemePrefillTest`,
`LinkNavTest` (all plan shapes incl. the current-notebook reroutes), `TrailCodecTest` (cap, LIFO,
untrusted decode). The stores test against the injected-`transact` seam — no Room in JVM tests.

## Deliberate differences from Paper / og

- **Core, not an extension.** Paper's links lived behind a provider AIDL; SN's core owns payload
  meaning, render and follow. `style` is written null (no provider identity) and read leniently;
  chrome is never cached in `flags`. The recognizer point stays SN's only extension surface.
- **Page previews and heading page names** are og features Paper skipped — SN has them, and has
  no other page index for them to live in.
- **Naming schemes in the picker** (K3): `SchemePrefill`/`NewFolderFlow` shared with the library
  — Paper's picker had no arc-5 to lean on.
- **Chrome menu**: underline/none only; og's dotted-chevron style excluded (locked).
- **No search in the picker** — deferred exactly as Paper deferred it (`BACKLOG.md`).
- **Paper's accepted Intent-redelivery quirk is fixed** (consumed-once `EXTRA_INITIAL_PAGE_ID`),
  and the via-link flag survives process death (`BrowseState.lastOpenViaLink`) — Paper's restore
  forgot the story.
- **`syncLinkRenderer` exists only since K2** (the Edit path needs a repaint without a reload);
  every K1 mutation shares its frame with a reload — recorded in the code.
- Trail cap 50 with **silent** dead-entry skips (Paper matched); the trail lives in
  `SharedPreferences`, not an extension store — SN has none.
