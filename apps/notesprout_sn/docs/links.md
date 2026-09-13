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
| `notebook/PageReads` | one read-only page gather over any `SoilDao`: loose strokes/headings + links with their children. Since arc 34 / L16 the read is **one query per level** over a new `SoilDao.childrenOf(parentId)` (five private per-type extensions split the one level's rows) rather than six per-type queries at every level. JVM-tested over `FakeSoilDao` |
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

**Arc 38 "Reference"** (2026-09-13) added a fourth kind, [`KIND_BIBLE`](#the-bible-kind-arc-38) —
a link whose target is not a row of ours at all, but a passage of scripture resolved by **NSE ·
Bible**. `notebook/BibleRefFlow` (§ below) is its collaborator, alongside the ones above; the
reader's own half of the seam is [`extensions/bible/docs/bible.md`](../../../extensions/bible/docs/bible.md)
§ "Bible references".

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

Because wrapped children belong to the link, a page's "loose" content queries no longer see them.
Page delete/reconcile therefore cascades **grandchildren** (`SoilDao.liveDescendantIds` — the page's
own children *and* the links' children).

**But a wrapped heading is still a heading the user wrote on that page**, and the two places that
answer "what is written here" both reach through the link. It was the other way at K1 — one rule for
"whose heading is it", everywhere — and that lost real writing: a heading turned into a link's title
vanished from the table of contents and stopped naming its page, with no way back short of
unlinking. Reversed on **2026-08-26**, on the user's call:

- **The Contents outline** lists it. The gather hops link → page (`SoilDao.liveLinkPages`), so the
  entry sits on the page its link is on, in document order, and the tap navigates by the resolved
  page id. See [`notebook.md`](notebook.md) § Contents.
- **The picker's page label** takes it. `PageLabels.titleOf(PageContent)` reads the loose headings
  and the links' wrapped ones together, and topmost by `(y, x)` still wins.

Both are free because a wrap moves **parentage, not coordinates** — the child keeps its
page-absolute `(x, y)`, so ordering, level and label need nothing the row didn't already carry.
A link on a dead page, or one soft-deleted by an erase (a link erases whole, children and all),
resolves to nothing and is dropped by the rule that has always dropped an unresolvable row.

**The payload** lives in the link row's `text` column — **Paper's v1 grammar, byte-for-byte**
(verified against `PAPER_LINKS_PLAN.md` and pinned by JVM fixtures), so link rows stay
family-compatible in both directions; a cross-app id simply resolves dead (the dead-target rule):

```
"L1|<chrome>|<kind>|<notebookId>|<pageId>"
```

`chrome` `0|1` (none/underline) · `kind` `0` page-of-this-notebook (no notebookId) · `1` whole
notebook (no pageId) · `2` page of another notebook (both) · **`3` a passage of scripture (arc 38 —
no pageId; see below)**. Versioned tag, `|` separator (ids are UUIDs), empty slot for each id the
kind doesn't carry, 2000-char cap **in both directions** (a file is untrusted input). `encode`
throws — only our own flows compose payloads; `decode` returns null for anything unusable (unknown
version, bad kind, forbidden/oversized ids) and **never throws**: chrome falls back to
`CHROME_NONE` (content still renders) and a follow lands in the dead-target dialog, never a crash.

| kind | payload | carries |
|---|---|---|
| `KIND_PAGE` (0) | `"L1\|1\|0\|\|<pageId>"` | a page of the link's own notebook — no `notebookId` |
| `KIND_NOTEBOOK` (1) | `"L1\|0\|1\|<notebookId>\|"` | another notebook — no `pageId` |
| `KIND_NOTEBOOK_PAGE` (2) | `"L1\|1\|2\|<notebookId>\|<pageId>"` | a page of another notebook |
| `KIND_BIBLE` (3) | `"L1\|1\|3\|<wire>\|"` | a passage of scripture — no `pageId`, and no `notebookId` either once decoded (below) |

### The Bible kind (arc 38)

`KIND_BIBLE` is the one kind whose target is not a row of ours: the **notebookId slot** carries a
resolved reference's opaque wire form (`JHN:3:14-3:18,PRO:3:5-3:6` — grammar and examples in
[`extensions/bible/docs/bible.md`](../../../extensions/bible/docs/bible.md) § "Bible references");
the extension's `ReferenceCodec` is its only reader, and `ResolvedReference.isWire` is the whole of
what `LinkPayload` itself checks (a wire is longer than a UUID, is not one, and carries its own
comma/colon grammar, but neither it nor an id may hold the payload's `|` separator).

**`decode()` reports `Decoded.notebookId = null` for a Bible payload** and hands the wire back as
the new `Decoded.reference` field instead — deliberately, so nothing that re-points a notebook id
(`NotebookRemap`, `ObjectClip`, `PageClip`) can ever mistake a reference for one; each of those
three was checked against the new kind at R3 and needed no change, because none of them reaches a
`reference` field. `LinkPayload.referenceOf(payload)` is the one predicate the notebook screen asks
to tell a Bible link from any other.

**Paper and og decode kind 3 as unusable** — a dead link there, accepted at the arc-38 wizard: the
family format gained a fourth kind neither of those apps knows, and a payload they cannot read
already degrades to `CHROME_NONE` + the dead-target dialog by the family's own `decode`-never-
throws rule, so nothing new had to be built for that direction.

`LinkNav.planFollow` answers a fourth `Follow` case, `Follow.Bible(reference)` — **not** a place in
the library at all: `LinkFollowFlow.follow`'s handler for it neither pushes the trail nor seals the
notebook, because the Bible reader owns its own screen and returns by result onto this same page.
It asks the host's `BibleEntry.open(reference)` — **true** only when a trusted reader declaring
`MIN_API_VERSION_FOR_BIBLE_REFERENCE` (12) is installed and the showing was actually asked for;
**false** (no reader, or one too old) is the dead-target dialog, with its own wording
(`link_target_bible_body`, "This link opens a Bible passage, but NSE · Bible is not installed or
needs updating") rather than the page-gone or notebook-gone text every other kind's refusal uses.
The `busy` door is released **before** the async `openBible` call (unlike a same-notebook or
cross-notebook hop, which holds it until the screen actually leaves) — the notebook is never left,
so there is nothing for a second tap to collide with once the reader is asked for.

**Edit** on a lone Bible link routes to `BibleRefFlow.edit`, never the page picker (K1/K2's Edit
path checks the payload's kind first): the reference dialog opens prefilled with the wrapped text
object's own words, Save re-resolves through `BibleEntry.resolve` and rewrites both the text and
the payload in one `Action.BibleRefEdited` undo entry. Because editing can change the wrapped
text's words while its measured box very often does not ("John 3:16" → "John 3:17" is the same
width), the ordinary link-composite cache rule — "same padded size ⇒ same picture" — is wrong for
this one path, so `LinkRenderer.invalidate(id)` drops the cached bitmap and the edit's own reload
rebuilds it; every other link mutation still relies on the cache rule unchanged. `LinkStore.
updateBounds` is the matching write: the **only** rewrite of a link's own box that is not a move,
because the wrapped text's re-measured box changes the link's box (and with it the hit target and
the underline band), and nothing else about a Bible link's geometry does.

Two locked family deltas from Paper's rows, both in `LinkRows`: `style` is **written null**
(Paper put its provider identity there; SN has no provider) and **read leniently** — a
Paper-created row decodes fine; and chrome is **never cached in `flags`** — parsed out of the
payload at load (`chromeOf`), held on the `PageLink`, so payload equality covers target *and*
style (which is what makes an unchanged-payload Edit a clean no-op).

**No nesting**: a link never wraps a link. Enforced at the chrome (`SelectionMode.LINK` /
`MIXED_WITH_LINK` hide the Link action — see below) and re-checked at use time in
`createLinkFromSelection`.

### Wrapping the new kinds (arc 28)

A link may wrap any of arc 28's three additive kinds — text, shape and sticky rows — exactly as it
wraps ink and headings: `PageLink` grew `texts: List<PageText>` / `shapes: List<PageShape>` /
`stickies: List<PageSticky>` alongside `strokes`/`headings`, all page-absolute, each in z-order;
`LinkRows.toLink` takes them as trailing defaults so a three-argument call (a test, a foreign-file
read) still compiles; `LinkStore.loadPage`/`create`/`unlink`/`relink` reparent and read all six
kinds through the widened `PageLink.childIds`.

**A wrapped sticky's content stays under the sticky, not the link.** `childIds` deliberately
excludes a note's content strokes — they hang off the sticky row (`StickyStore`), the same
grandchild relationship a loose sticky has to its page — so `page → link → sticky → stroke` is
**three levels deep** (the exact phrase `ObjectClip`'s own comment uses), one level deeper than
anything arc 6 had to reach. A wrapped sticky still **never draws its content on the page or in the
composite** ([Rendering](#rendering) below; `LinkComposite.build` draws the sticky's icon only, in
D8's order — headings · texts · shapes · strokes · icons, the same order `PagePreview.drawContent`
uses).

Reads reach the extra level on purpose:

- `LinkStore.loadPage` reads a wrapped sticky **icon-only** — `StickyStore`'s own page-load rule,
  since a page load never needs a note's content; a capture or a delete snapshot that does goes
  through `StickyStore.withContent` explicitly.
- `SoilDao.liveDescendantIds` reaches a sticky's content whether the sticky sits loose on the page
  or inside one of the page's links — a third branch besides the page's own children and the links'
  children ([`docs/notebook.md`](notebook.md) § Undo / redo has the full query).
- `ObjectClip.capture`/`plan` treat a link's wrapped set as anything parented to it that is not
  another link (no nesting, unchanged) — a wrapped sticky's own children are then assembled the
  same way a loose one's are, wired onto the sticky's **new** id before the link is rebuilt around
  it.

**Deleting a link that holds a sticky reads before it writes.** `LinkStore.remove` reads every
wrapped sticky's content strokes inside its own transaction and takes them down with the link — no
snapshot needed for a plain delete. **Undo needs one**: a link snapshot holding stickies must carry
their content (`StickyStore.withContent` on each, taken **before** the delete), because there is no
DAO call that reads an already-soft-deleted child back — `LinkStore.restore`'s own doc comment says
so. `NotebookActivity.recordWithStickies` is what makes that read possible from a delete/scribble
gesture at all: g-paper's callback is synchronous, so with a sticky in the act (loose or wrapped)
the read, the deletes and the one undo entry all move into a single page op — still one gesture, one
entry, just recorded a beat later ([`docs/notebook.md`](notebook.md) § Undo / redo).

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

Its card grids flip on a **one-finger horizontal swipe** like every other paginated view in the app
(F3, `core/ListSwipe` — `docs/library.md` § The flip), armed on `gridContainer` so the mode buttons
and the style toggle above it are not page turns.

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
`(y, x)`, prefix-stripped, across the page's loose headings **and** the ones its links wrap),
plain "Page n" otherwise.

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
- **A Bible passage** (`KIND_BIBLE`, arc 38): not a hop to anywhere in the library, so neither the
  trail nor the page-op lock is touched — `openBible(reference)` opens the reader over this same
  screen and returns by result. Refused (no reader ≥ 12, or none at all) is the dead-target dialog
  with its own wording, never the notebook/page-gone text. § [The Bible kind](#the-bible-kind-arc-38)
  above has the payload's own detail; this is only the follow's branch of it.
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
- The via-link flag records how the notebook was opened, so a cold launch restore reopens a
  via-link notebook **as** via-link: the trail survives a mid-chain force-stop, and without the
  flag the restore would read as a fresh open and clear it. **Since arc 32 / RS1** the flag rides
  the surface stack's `SurfaceEntry.viaLink` (`data/prefs/SurfaceStack.kt`, prefs `sn_view_state`
  key `surfaceStack`) rather than the retired `BrowseState.lastOpenViaLink` — the replay
  (`LibraryActivity.replayStack` → `openNotebook(id, name, viaLink, …)`) restores the same value
  the same way; only the storage moved. See [`docs/library.md`](library.md) § Launch restore.

## Encryption (arc 26)

A `NOTEBOOK`-scope target is never read silently — a follow and a walk-back are both deliberate
acts, so `LinkFollowFlow` prompts (`NotebookPassphrasePrompt.ask`) before `foreignPageAlive`'s
`SoilDatabase.readOnce`, on **both** paths. On walk-back a cancelled prompt ends the walk and
**pushes the trail entry back** rather than skipping it as dead — "not now" must not cost the
user their way home. Either path parks the typed passphrase (`PassphraseCache.storeOnce`) before
`leaveFor`, so the hop into the target notebook costs exactly one prompt: the notebook screen's
own open takes the parked value instead of asking again.

The link picker shows a foreign notebook or its pages even when locked — a **lock row** in place
of the cover, tapping it raises the same prompt; on success `ForeignPageSource(passphrase)` holds
the typed value for the source's whole lifetime (surviving every `sealAsync`/reopen cycle across a
mode switch), so only the first drill into a locked notebook prompts. `PickMode.NOTEBOOK` (link-to-
notebook, no page drill) never prompts — nothing is opened to check. Full model, the resolver and
the failure table: [`docs/encryption.md`](encryption.md).

## JVM tests

`LinkPayloadTest` (round-trips, Paper-grammar fixtures, decode rejections, caps — **19 tests** since
arc 38 / R3 added the `KIND_BIBLE` round-trip, the `reference`-not-`notebookId` decode rule, the
`isWire` boundary and the Paper/og dead-kind fixture),
`LinkRowsTest` (row mapping, lenient `style`, `unionBounds` + `bandBottom` + `withUnderlineBand`), `LinkStoreTest`
(wrap/unlink/relink/remove/restore/move transactions over `FakeSoilDao`, chunking, K5's
revive-in-place order preservation, plus arc 38's `updateBounds` write — **18 tests**),
`LinkCompositeTest` (pad/size math), `LinkPickerModelTest` (modes, prefills,
exclusion-beside-numbering, `gridPageOf`, K3 placement/inherit/buttons, `composeOk` incl. the
self-target refusal), `PageLabelsTest`, `PageReadsTest`, `PreviewMathTest`, `SchemePrefillTest`,
`LinkNavTest` (all plan shapes incl. the current-notebook reroutes and, since R3, `Follow.Bible` —
**12 tests**), `TrailCodecTest` (cap, LIFO,
untrusted decode). The stores test against the injected-`transact` seam — no Room in JVM tests.
`NotebookRemapTest`, `ObjectClipTest` and `PageClipTest` each grew one fixture confirming a Bible
payload passes through untouched (no `notebookId` to remap).

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
  and the via-link flag survives process death (`SurfaceEntry.viaLink` on the surface stack, since
  arc 32 / RS1 — `BrowseState.lastOpenViaLink` before it) — Paper's restore forgot the story.
- **`syncLinkRenderer` exists only since K2** (the Edit path needs a repaint without a reload);
  every K1 mutation shares its frame with a reload — recorded in the code.
- Trail cap 50 with **silent** dead-entry skips (Paper matched); the trail lives in
  `SharedPreferences`, not an extension store — SN has none.
