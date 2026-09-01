# Tags (arc 21)

**Tags appear only where you go looking.** A notebook or a page can carry any number of short
labels — "reading list", "2026 taxes", "wip" — and the only two places that ever show one are the
tag screen itself and a search-result card. Nothing on a library card names a tag, and nothing on
a page does either: tagging is a filing act, not a decoration, and the design system's own rule
(nothing on e-ink that carries no information) extends to a feature whose entire value is filing
something you will look for later, not something you will see every time you open it.

This arc gives SN its **sixth capability point**, `ACTION_TAG_MANAGER` (+ `_SCREEN`), granted by
the user 2026-08-31, and its **third screen-owning one**. The index lives inside a new extension,
`:ext-tags` (**NSE · Tags**) — the tenth module. The shape is the scratch pad's: the extension owns
the tag screen and the whole tag index, in its own encrypted extension store; the host owns every
entry point (four of them), the recognizer call the lasso's ink flow needs, and the library's
search merge.

This is the feature doc. The seam — `ITagManager`, `TagShowing`'s wire form, the store's key
layout, the boundary audit — is [`docs/extensions.md`](extensions.md) § the sixth point; this doc
only summarizes it. The notebook's tag button is [`docs/notebook.md`](notebook.md); the library's
row and search merge are [`docs/library.md`](library.md) § Search; the backup set is
[`docs/backup.md`](backup.md).

**Status: arc 21 complete** — W1 the point, the module, the screen's BROWSE/ADD core, the library's
first door · W2 the notebook's three doors + MODE_MANAGE · W3 the lasso's Tag · W4 the search merge
(and the record reshape it forced) · W5 extension stores enter the backup set · W6 review, docs,
freeze (this doc is part of it).

---

## Why an extension, and what it cost

The scratch pad proved that a screen-owning point can hold real state without a `.soil` in sight;
tags reuse that shape rather than inventing a fourth. The core keeps one thing it would otherwise
have had to grow twice: a query surface over an arbitrary set of user-typed labels, attached to
things that live in two different places (the global index's notebooks, a `.soil`'s pages). Living
in `:ext-tags` means the index is one value in one encrypted per-package store — an extension
writes nothing to disk itself, the arc-11 rule holds unchanged — and the host never has to decide
where a tag table would live in `notesprout.db` or inside every `.soil`.

`:ext-tags` is also SN's **first tier-2 screen carrying no paper at all**: no g-paper dependency,
no `PaperView`, and therefore **no EPD handoff anywhere in the seam**. Arc 19 / M3 already measured
the general question — does a non-drawing child screen need `releaseForHandoff()` around it? — for
the document editor, and the answer (stop-behind is enough, cross-process included) carries over
here with no new code. `docs/extensions.md` records the measurement; this doc just inherits it.

---

## Tag identity and lifecycle

A tag is normalized before it is anything else, by `TagRules` (`:extension-api`, pure, shared by
both sides of the seam and every test that touches it):

- **`display(text)`** trims both ends and collapses every internal run of whitespace to one space.
  Case is kept — a tag wears the casing of whoever typed it first.
- **`identityKey(text)`** is `display(text)` folded to lower case with `Locale.ROOT`, never the
  device locale — a Turkish device must not decide "I" and "ı" are the same tag when the same
  library on another device says they are not. Locale-neutral identity is what keeps a `.soil`
  library portable, this family's oldest rule.
- **`isValid(text)`** is true when something survives normalization and it fits
  `ExtensionContract.MAX_TAG_CHARS` (64), measured on the display form. Nothing else is
  restricted — multi-word tags are the point, punctuation and digits are ordinary text.

Two things follow directly, and both are deliberate rather than incidental:

- **Tabs and newlines are dropped, not escaped.** `Char.isWhitespace` — what `display` collapses —
  already covers both, so nothing that has been through `TagRules.display` can carry either one.
  An escape layer in the codec would be unreachable code pretending to be a guarantee; a record
  that somehow carries a raw tab or newline is simply skipped (`TagCodec.writable`), which costs a
  re-add rather than silently becoming two wrong tags.
- **The identity key is never stored.** It is re-derived from the display form every time an index
  is read, so an index can never disagree with the rule that built it.

**Lifecycle.** A tag persists until it is explicitly deleted — removing its last assignment leaves
it sitting in the suggestion list, ready to be reused. `TagIndex.assign` is idempotent (re-assigning
an attached tag is a no-op that still answers with the canonical spelling, so a double tap on
e-ink costs nothing) and keeps the **first** casing: assigning "Reading List" to a library that
already holds "reading list" attaches the existing tag and hands back "reading list". `unassign`
detaches from one target and never touches the tag itself. `deleteTag` is the **only** thing that
removes a tag, and it removes it — and every assignment of it — everywhere at once; the screen is
the one place that reaches it, behind a confirm that names the blast radius (below).

**Snapshot semantics.** A tag is a **text snapshot** taken at the moment it is created or attached,
never a live pointer to the thing it came from. Renaming the heading a tag was pulled from later
never renames the tag; converting the same heading into a tag again creates or attaches a second,
independent one. This is what lets the lasso's ink→tag flow put the recognized text in front of the
user for correction without worrying about what happens if the ink is edited afterward — nothing
is watching it.

---

## The data model

`TagIndex` (`:extension-api`, pure, immutable) is the whole library's tags as data — shared,
unmodified, by both sides of the seam: the extension edits it and writes it back to its store, and
the host decodes a snapshot of it to feed the search merge. Every edit returns a **new** index,
which is what lets a screen hold the version it would fall back to if a write failed.

```
class Tag(val id: String, val display: String)
class Assignment(val tagId: String, val notebookId: String, val pageId: String? = null)
```

**Every assignment names a notebook.** A page tag names its page as well — `notebookId` alone is a
notebook tag, `notebookId` + `pageId` is a page tag, and `pageId`'s presence is what makes it one:
there is no stored `kind` field, because a stored kind would be a second copy of an answer the
record already gives, and a second copy is a place for the two to disagree.

This shape is not what W1 shipped. A W1 assignment named a page and nothing else, and W4's own
phase — building the search merge — ran straight into the fact that **nothing anywhere could say
which notebook a tagged page was in**: the global index holds folders and notebooks only, a page
lives entirely inside its notebook's `.soil`, and there is no way to scan for the owner either,
because `KeyMaterial`'s raw-key cache is per file (the salt is per file) — a first-ever open of an
unopened `.soil` costs a full key derivation. The user's call reshaped the record instead of adding
a lookup: every assignment carries its notebook, so the relationship is stored rather than
inferred. `ExtensionContract.API_VERSION` moved to **5** for it — the one bump so far that is
**not** a compatible tail (below).

**Queries worth knowing:** `find(text)` answers "does this already exist?" by identity key.
`tagsOf(notebookId, pageId?)` lists one target's tags. `usageOf(tagId)` counts how many notebooks
and pages a tag reaches — what the delete confirm names. `suggest(query)` orders live suggestions
exact-identity first, then prefix, then substring matches, each group in browse order — deliberately
**not** `core/FuzzyRank`, which lives in `:app` and answers a different question (matching what you
are searching *for*, not what you are *typing right now* to avoid creating a duplicate).

**There is deliberately no aliveness filter on `TagIndex`.** W1 shipped a `filterAlive`, W6's review
found it had no caller, and it was removed rather than kept: staleness is answered **structurally**,
not by a pass. The search merge reads tags only *through* the library's own live notebook listing,
so an assignment naming a deleted notebook is never looked at, and `PageNumbers` answers a page's
aliveness the same way against the notebook's live page rows. A function that filtered an index
nobody filters would have been a doc comment asserting a role it did not have. Pruning the *stored*
blob is a `BACKLOG.md` note and would go through `TagWrites`.

### `TagCodec` — the wire form is the storage form

A line codec, not JSON — `:extension-api` carries no serialization dependency and never will (the
`UserWords` precedent). UTF-8, tab-separated, one record per line:

```
NSTAG2                        ← version line; unknown = UNREADABLE, not empty
T <id> <display>               ← one per tag, insertion order
A <tagId> <notebook>            ← a notebook tag
A <tagId> <notebook> <page>     ← a page tag
```

**Ids are written compacted.** `CompactId` (`:extension-api`, pure) turns a canonical UUID's 128
bits into 22 base64url characters instead of 36 — `compact()`/`expand()`, round-tripped through
`UUID.toString()` so only the canonical form is ever accepted (the same leniency guard
`SafeImportId` applies on the import path). In memory an id is **always** a UUID; the compact form
exists only between `TagCodec`'s encode and decode and nowhere else. `CompactId.isId(id)` — "is this
a canonical UUID at all" — is the one shape check every door on the seam makes now:
`ExtensionContract.MAX_TARGET_ID_CHARS` is gone entirely, because a UUID's alphabet has no path
character and no NUL in it either, and W1's hand-rolled character checks were a weaker spelling of
the same guarantee `CompactId.isId` gives for free.

**The arithmetic is load-bearing, not decoration.** The whole index is one store value, capped at
`ExtensionContract.STORE_MAX_VALUE_BYTES` (4 MiB), and `TagCodec.WORST_CASE_BYTES` is the proof —
checked by a test that fails if a cap moves — that the worst legal index still fits:

| Constant | Value |
|---|---|
| `MAX_TAGS` | 5,000 |
| `MAX_TAG_ASSIGNMENTS` | 50,000 |
| `MAX_TAG_CHARS` (display, UTF-16 units) | 64 |
| `TagCodec.MAX_TAG_ID_CHARS` | 4 (base-36 mint counter; 3 within 5,000 tags, 4 is slack good to 1.6M) |
| `CompactId.CHARS` | 22 |
| `TagCodec.WORST_CASE_BYTES` | 3,650,007 |

That number did not close on the first attempt. `id · identityKey · display` under the wizard's
caps came to roughly 6.0 MB — too large for 4 MiB — and rather than shrink a cap the wizard had set,
W1 dropped the identity key (a pure function of `display`, so storing it duplicated an answer that
could disagree with the question) and shrank ids to a base-36 counter; W4's own notebook/page pair
repeated the same move with `CompactId` once ids had to be stored twice per assignment instead of
once.

**Failure has two meanings that are not interchangeable.** An absent or empty stored value is a
**first run** — `TagIndex.EMPTY`, write freely. A stored value whose version line is not one this
build reads is **unreadable**, and `decode` throws `IllegalArgumentException`: the caller must say
so and must never write an empty index over it (the `ScratchPageCodec` rule — losing a whole
library's tags to a blank overwrite is a loss nobody can undo). A **truncated tail** is neither: a
blob that does not end in `\n` has its last, partial line dropped, and everything decoded whole is
kept.

**The v1 → v2 migration.** W1 shipped `NSTAG1`, whose assignment record was
`A <tagId> <kind> <targetId>` with plain, uncompacted UUIDs. A `kind == TARGET_NOTEBOOK` record
migrates whole — a notebook assignment's target *was* its notebook id all along. A
`kind == TARGET_PAGE` record is **dropped**: it names a page and nothing else, and the notebook
that page belongs to is not recoverable from anywhere. `NSTAG1` is still read, never written. On the
Nomad this migration ran live at W4 and dropped exactly the two page-tag test assignments W2 had
left behind, while all five notebook tags survived untouched — exactly what the codec test asserts.

---

## The seam, briefly

`ITagManager` is one AIDL interface serving two call shapes, and the store is what tells them
apart:

- **A showing** — `begin(store)` → `configureShowing(TagShowing)` → the host launches the screen for
  a result → `end()` — is a **held bind**, the scratch pad's bracket. The store is lent once for the
  screen's whole life and revoked with the unbind.
- **`snapshot(store)`** and **`assign(store, text, notebookId, pageId)`** are **bind-per-call**, the
  recognizer's shape: the store rides the one call that needs it, because the operation *is* the
  call and nothing is ever shown. `snapshot` is the search merge's door (W4); `assign` is the
  lasso's silent heading→tag (W3).

`TagShowing` (Parcelable, `:extension-api`) is everything one showing needs, and it crosses **on
the bind**, never the screen's Intent: a tag and a target's display label are the user's own words,
and an Intent extra is readable in a `dumpsys` and lingers in the recent-tasks description. Its
constructor `require`s are the whole validation, both directions (unmarshal is validation, the
family rule since arc 1). It carries the target pair (`notebookId` always, `pageId` only for a
page), the resolved `targetLabel`, `mode` (`MODE_BROWSE` / `MODE_ADD` / `MODE_MANAGE`), an optional
`prefill`, and — MANAGE only — parallel `pageIds`/`pageLabels` arrays capped at `MAX_PAGES` (5,000):
the parcel **refuses** rather than allocates above it.

Both writers in the extension's process — the screen's own edits (on IO) and the service's
call-shaped `assign` (on a Binder thread) — take the **same** lock, `TagSession.writes`, through
`TagWrites.apply`: read the stored index fresh inside the lock (never the one the caller is
showing), run the change, write, and only then hand back the new index. Without that single
chokepoint two writers editing the same store value from two threads would each apply their change
to the version they happened to be holding, and the second would silently erase the first.
`TagWrites.Reason` is the one typed vocabulary for why a cycle did not land
(`STORE_UNAVAILABLE` / `INDEX_UNREADABLE` / `INDEX_FULL` / `NOT_A_TAG` / `SAVE_FAILED`) — the
service turns it into a marshalable `IllegalStateException` message the host compares verbatim, the
screen turns it into a dialog sentence, and neither side reads the other's wording.

The full contract — every method's exceptions, the ashmem handshake `snapshot` uses to answer over
a Binder, the boundary-audit rows (what each side may know) — is `docs/extensions.md` § the sixth
point; this section only orients.

---

## The screen

`TagsActivity` (`:ext-tags`) is the whole tag editor, in one Activity built without `:sn-screen`'s
paper pieces (there is nothing to draw). `HostCallerCheck.enforceActivity` is the first statement
in `onCreate`, before anything is inflated — the screen is exported (it has to be; the host
launches it by action) and a plain `am start` with no `callingPackage` is refused outright.

The chrome is three surfaces, three unambiguous gestures:

| Surface | What it shows | Gesture |
|---|---|---|
| Target section | The current target's own tags | Tap a tag → **detach** it from this target. The tag stays in the library. |
| Add field | Free text, live-filtered against the in-memory index | Type, then ⊕ or the keyboard's Done → normalize, create-if-absent, **attach**. |
| All/matching tags | Every tag while the field is empty, matching ones while it is not; paginated | Tap → **toggle** membership (✓ shows which way). Long-press → **delete the tag everywhere**, behind a blast-radius confirm. |

**Suggestions never cost a store call.** `TagRowView`/the add field's `doAfterTextChanged` filters
`index.suggest(query)` — already in memory — on every keystroke; the list region's repaint is the
accepted EPD cost, not a Binder round trip. **The list is paged against the real band**, not a
guessed row count: `TagPaging.rowsPerPage` measures `bandPx / rowPx`, and the pager is `INVISIBLE`
(never `GONE`) when there is only one page, so the band's height never shifts every row underneath
the reader's finger the moment the count crosses a boundary. Its arrows never disable — a disabled
control is invisible on e-ink; at either end they simply have nothing to do.

**Every edit is written before it is shown.** `edit()` runs `transform` on IO inside
`TagSession.writes`, and only once the write has actually landed does the screen redraw and fire its
`onDone` (the field clearing, the toast). What is on the glass is always what is in the store; a
failed write leaves the screen showing exactly what is still true.

**IME rules follow the Ratta family's, not og's.** The keyboard is asked for with the **explicit**
flag `0`, never `SHOW_IMPLICIT` (which a hardware keyboard skips, stranding the field on Ratta,
where hardware keys type only while the IME is shown), and it is **never hidden** while the field
has focus. `MODE_ADD`'s keyboard is raised from `onWindowFocusChanged`, not `onResume` — a resumed
Activity does not yet have window focus, and `showSoftInput` against an unfocused window is simply
dropped, which read on the Nomad as "the keyboard is broken" rather than "it was asked for too
early." A once-per-showing latch (`pendingAddFocus`) keeps a dialog dismissal from re-raising a
keyboard the user just put away.

### MODE_MANAGE — an overview you drill into

MANAGE opens on an **overview**: the notebook, then every page the host named, each as one row
holding a label and the tags it carries ("No tags" when it carries none). Tapping a row makes that
target current and the screen becomes **exactly** what it is in BROWSE or ADD mode — the same three
surfaces, the same three gestures. The back arrow returns to the overview, and **only from the
overview** does it leave. This is a deliberate rejection of editing many targets on one screen: that
would have needed a second grammar for "which of these am I acting on," where drilling in means
adding and removing have exactly one implementation no matter which of the four doors got you here.

Two things the overview gets right that are easy to get wrong: it remembers its **own** page
(`overviewPage`), kept apart from the tag list's `page`, so drilling into page 20 of a 20-page
notebook and coming back lands where it was left, not at the top; and its text field's watcher is
**silent** while the overview is showing — the field is not even on screen up there, and the two
places it gets cleared (entering/leaving overview) must not be read as "the query changed" and send
the overview's own pager back to page one.

---

## The four doors

Every door is host-side and **`GONE`, never disabled**, when no trusted `ACTION_TAG_MANAGER`
extension is discovered — the standing e-ink rule that a disabled control is invisible and a lie
about what a tap will do is worse than the control simply not being there. `TagManagerEntry` is the
one class behind every door but search (which needs no showing): availability, the busy latch, the
"Opening…" wait, and the bind's teardown are all one implementation, on the `ScratchPadEntry`
pattern — two near-identical doors would have been the sibling-copy trap in miniature.

### 1. Library long-press — Tags…

The action sheet's **Tags…** row (`R.drawable.ic_tag`, `action_tags`), notebooks only — a folder is
a place, not a taggable thing. It sits between **Export…** and **Exclude from backup**, discovered
on the same asynchronous beat as the exporter check (`canTag = tags.discover()`), and is absent
until that check answers. Opens `MODE_BROWSE` on the notebook, identity crossing on the bind as
`TagShowing(notebookId, null, name, MODE_BROWSE)`.

### 2. The notebook top bar

`ic_tag` sits in the top bar's right cluster, between **Document** and **Recents**. A tap opens
`TagsPopup` — a small bordered bar (`AnchoredBar`, the same class the arc-8 lasso popup hangs from,
so the placement math, the measure-before-place rule and the button recipe are written once) with
three icon-only, long-press-hinted buttons:

| Button | Icon | Opens |
|---|---|---|
| Tag notebook | `ic_notebook` | `MODE_ADD` on the notebook, field focused |
| Tag page | `ic_page` | `MODE_ADD` on the page whose ink is on the paper, field focused |
| Manage | `ic_list` | `MODE_MANAGE` — the notebook and every page |

The bar itself is gated on `canvasShown`: two of its three doors are about the page on the paper,
and a text document that has never shown its pages has none to tag — the bar stays absent rather
than opening onto a door that would do nothing. **Page numbers are the host's to resolve, and it
does so at the tap** against the live `session.pages` list (`TagTargets.pageNumber`), never from an
earlier snapshot a page operation could have made stale; a displayed page that is not in that list
(mid page-op) falls back to the notebook's own name rather than naming "Page 0." MANAGE's page
arrays stop at `TagShowing.MAX_PAGES` (`TagTargets.listedPages`) because the parcel refuses above
it rather than allocating — a notebook that actually reached that count is logged, not crashed.

### 3. The selection toolbar's Tag

Between Pad and Delete on the lasso's floating bar, offered for exactly two selection shapes and no
others — `TagSelection.offered`, backed by `TagSelection.flowFor`:

| Selection | Flow | What happens |
|---|---|---|
| Exactly one heading | `SILENT` | Its own text becomes the tag; no screen, a toast |
| Ink only (no content objects) | `RECOGNIZE` | Recognized, then the tag screen opens prefilled to correct |
| Anything else (mixed, a link, links + strokes) | — | **No button at all** |

The rule the user drew here at W3 narrowed the planner's original sketch ("recognize whatever is
selected") because a mixed selection has **two** answers with no way to ask which is meant: a
heading already carries the exact words a tag would be made from, while the ink beside it carries
different words, and re-recognizing the heading's own strokes could disagree with what is actually
on the glass. A button that quietly picked one of those would be worse than a button that is not
there. A lone link is excluded the same way — it is content with a payload, not ink.

**The recognizer's readiness is not gated on** for the button's visibility. Tag stands or falls
with the *tag* extension alone; a missing recognizer is explained by the same "still downloading"
dialog the H (heading-convert) button beside it already shows, because both buttons go out through
the same recognizer and one vanishing while the other stayed would read as a bug rather than a rule.

Both flows are read off the **live** selection at the moment of the tap — `tagSelection()` — never
trusted from whatever offered the button, because a selection can move, change kind, or be dismissed
in the gap between the bar going up and a finger landing. Both are strictly **non-destructive**: the
ink, the heading and the selection are exactly as they were afterward, and a toast (never a silent
success) fires only once the write has actually landed. The lasso **always tags the page on the
paper, never the notebook** (the wizard's call), and the page id is captured at the tap so a page
flip racing a recognize call still lands the tag on the correct page.

A heading whose stripped title is not a valid tag (blank, or over the 64-char cap) does not refuse
the tap — it falls through to the same prefilled correction screen the ink flow uses
(`TagSelection.prefill`, which cuts to the cap without splitting a surrogate pair, the `TextChunks`
backoff), so the act still finishes in one more gesture instead of zero. The silent assign's wait
uses the same `RecognizingOverlay` box the heading convert already shows, with a message parameter
("Tagging…") rather than a second overlay type — the first tag operation of a host process pays
SQLCipher's key derivation, seconds on a Nomad, and needs the same "a tap with no answer for that
long reads as missed" treatment either flow already gets.

### 4. Library search

One query answers over **names and tags together**, through the same `core/FuzzyRank` total order —
the mechanics of the merge (folders → notebooks → pages, ranking, the dialog) belong to
[`docs/library.md`](library.md) § Search; this is what the tag half adds and how the resulting
cards look.

`LibrarySearch.snapshot()` fetches the tag index once per run, bind-per-call — no extension
installed answers `null` silently, and the shelf is exactly arc 20's name-only one, with nothing to
disable because search has no standing tag control. When a tag manager *is* installed, the search
dialog's hint changes from "Folder or notebook name" to "Folder, notebook or tag"
(`LibraryActivity.onResume` keeps `TagManagerEntry`'s discovery current for it, since the dialog has
no button of its own to refresh from).

`SearchAssembly.rank` folds tag matches into the existing name-ranked lists rather than adding a
separate pass:

- A **notebook's** own tags (assignments with no `pageId`) are matched and ranked the same way its
  name is; whichever of the two scores better decides both the row's rank and its label, ties going
  to the name. The row's **subtitle** carries the matched tag only **when the name did not match** —
  `folder · tag` answers "why is this card here," and a matching name has already answered that.
- A **page's** tags surface as their own card, one per page even when several of its tags match —
  the best-scoring tag names it. This is only possible because W4 made every assignment name its
  notebook; before that a page hit could not be traced back to anything.
- Dead assignments never need a filtering pass to be excluded: the merge iterates the library's own
  **live** notebook listing, so an assignment naming a notebook that is not in it is simply never
  looked at (see the data model section above — this is why `TagIndex` carries no aliveness filter).

**Page-hit cards** (`CardItem.Page`, `LibraryGrid.pageCard`) carry:

- **Name line** `<Notebook> · Page N` — `singleLine`, with the notebook name ellipsized to
  whatever room is left after the `· Page N` suffix is measured, so the page number always survives
  rather than a long title eating it.
- **Subtitle** `<folder> · <tag>` (`search_where_and_tag`).
- **Cover** — the notebook's own snapshot, already in hand from the listing; `bindCurrentPage`
  fetches by notebook id and `distinct()`s the set, so two page hits from one notebook cost one
  fetch, and asks for a cover for any non-folder card (a bug caught at the walk — page cards
  originally fell through to the paper placeholder because only `CardItem.Notebook` was asked).
- **No long-press.** The action sheet acts on a notebook (rename, move, delete, tags); firing it
  from a card that names a page would act on something the card itself does not name.

**Resolving a page's number** costs reading the owning notebook's live page list — nothing else
knows it — done through `PageNumbers`, cached per process and keyed on the notebook's own
`updatedAt` (bumped by every page op, so the cache invalidates exactly when it would otherwise lie).
It is viable at search-shelf speed because a notebook holding a tagged page has necessarily been
opened before (that is where the tag was applied from), and `KeyMaterial` persists derived keys, so
reopening it costs no key derivation. **A notebook that will not open contributes no page cards**;
its notebook card is unaffected — the shelf says less rather than saying something wrong.

Opening a page hit reuses the existing "open at a page" mechanism (`NotebookActivity`'s
`initialPageId` extra, already used by link-follow and Contents) rather than adding a second one.

---

## Backup

Every `Garden/<pkg>.db`, tags included, is in the backup set as of arc 21 / W5 — copied
unconditionally on every run, ordered after the notebooks and before the index, with its own line
in the done dialog ("N extension stores copied.") rather than being folded into the notebook count.
There is no restore screen for a store any more than there is for the library itself; getting one
back is the manual copy-back documented in [`docs/backup.md`](backup.md) § Extension stores. This
doc does not repeat that mechanics — it exists once, there.

---

## Failure table

| Failure | What happens |
|---|---|
| No trusted `ACTION_TAG_MANAGER` extension installed | Every door is `GONE` — the library row, the notebook's `ic_tag` button, the selection toolbar's Tag button; search silently becomes names-only |
| The extension was disabled/replaced between discovery and the tap | `open()`/`assign()` fail; "Tags unavailable" / "Tags could not be opened. The extension may have been disabled or removed." (`tags_failed_title` / `tags_failed_body`), and discovery re-runs so the next tap sees the truth |
| The screen launched with no showing parked (host restarted mid-bind, or never launched it) | `TagsActivity` shows "Tags could not be opened just now." (`tags_unavailable`) and finishes immediately |
| A stored index cannot be decoded (bad/future version line) | `IndexUnreadable` — the screen shows "This library's tags could not be read. Nothing has been changed." and closes without writing; the host's `assign`/`snapshot` paths surface the same wording (`tags_unreadable_body`) and change nothing |
| A cap refused a new tag or assignment (`MAX_TAGS` / `MAX_TAG_ASSIGNMENTS`) | `TAG_INDEX_FULL`; "This library is holding as many tags as it can. Delete one to add another." on both sides, nothing written |
| Typed text is not a valid tag (blank after normalize, or over 64 chars) | The add field: "A tag needs some text, and no more than 64 characters." A heading over the cap instead falls through to the prefilled correction screen rather than a dialog |
| A store write reaches the lock but fails to land | `SAVE_FAILED`; "That change could not be saved." — the screen keeps showing the index it had before the attempt |
| Recognizer not ready during the lasso's ink→tag | The existing heading-convert "still downloading" dialog; the Tag button itself is unaffected, since its visibility never depends on the recognizer |
| Mixed selection, or a lone link, under the lasso | No button offered at all — nothing to fail |
| A displayed page is not in the live page list at the moment a door is tapped (mid page-op) | The tag screen's title falls back to the notebook's own name rather than naming "Page 0" |
| A notebook has more pages than `TagShowing.MAX_PAGES` (5,000) | MANAGE lists the first 5,000 rather than crashing the tap; logged |
| Deleting a tag that is on nothing | "This tag is not on anything. It will be removed from the list." — no blast-radius sentence, since there is no blast radius |
| A search hit's notebook will not open | Its page cards are dropped for that run; its notebook card, if it also matched, is unaffected |
| A `.soil` v1 tag blob (`NSTAG1`) is opened by this build | Notebook assignments migrate whole; page assignments are dropped — they name a page with no recoverable owner |

---

## Traps and standing decisions

- **One store key, `index`, holds the whole blob.** Never a key per tag — a per-tag layout would
  turn one logical edit into a fan-out with no transaction around it, and the caps exist precisely
  so the worst legal index still fits one value.
- **Ranking and the blob decode run off Main** (`Dispatchers.Default`), both in `LibrarySearch` and
  in `TagClient.snapshot` — the decode can be megabytes and the rank walks every assignment, and the
  caller in both cases is a listing coroutine that must not stall on it.
- **There is no undo for a tag operation.** It is not page content, and the destructive one (delete)
  is guarded by a confirm instead.
- **`MAX_TARGET_ID_CHARS` is gone, not merely unused** — an id is a canonical UUID or it is not a
  target, checked by `CompactId.isId` at every door (`TagShowing`'s constructor, `TagIndex.assign`,
  `TagIndex.of`'s decode-time filtering). Reintroducing a length-based check would be reintroducing
  a weaker spelling of a guarantee this arc already has for free.
- **In memory an id is always a UUID.** The compact 22-character form exists only inside
  `TagCodec`'s `encode`/`decode` pair; nothing else in the seam should ever hold one.
- **The overview's outside-tap dismissal for `TagsPopup` does not write `tapDismissedPopup`** — that
  latch exists so a contact spent dismissing the clipboard popup is not also spent pasting, and the
  tag bar carries no second meaning for a tap to accidentally trigger.
- **Aliveness is structural, and nothing on `TagIndex` filters for it.** W1's `filterAlive`,
  `targetsOf` and `assignmentsIn`, `Assignment.targetKind`/`targetId`, `TagPaging.pageOf` and
  `PageNumbers.clear()` were all removed by W6's review: each had no production caller while
  carrying a doc comment asserting a role it did not have, which is the kind of thing the next
  reader trusts. If a future arc prunes the *stored* blob (see the monorepo `BACKLOG.md`), it goes
  through `TagWrites` under its lock, with the live id sets handed in by the host — the extension is
  not the side that knows which ids are alive.

---

## Related

- [`docs/extensions.md`](extensions.md) — the seam in full: `ACTION_TAG_MANAGER` + `_SCREEN`,
  `ITagManager`, `TagShowing`'s wire form, the boundary-audit rows for the sixth point.
- [`docs/library.md`](library.md) § Search — the merge's own mechanics (the dialog, the ordering
  rule, `FuzzyRank`, folders/notebooks/pages) that this doc only summarizes from the tag side.
- [`docs/notebook.md`](notebook.md) — the top-bar `ic_tag` button and the selection toolbar's Tag in
  their place among the notebook's other chrome.
- [`docs/backup.md`](backup.md) § Extension stores — the backup set and the manual copy-back.
- `apps/notesprout_ratta/RATTA_PLAN.md` § "Phases — Arc 21 \"Tags\"" — the wizard's locked
  decisions and every phase's outcome record, including the two implementer-level arithmetic
  reshapes (W1's dropped identity key, W4's compact ids) this doc only summarizes.
