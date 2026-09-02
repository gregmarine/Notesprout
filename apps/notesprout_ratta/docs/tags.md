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

This is the feature doc. The seam — `ITagManager`, `TagShowing`'s wire form, the store's schema,
the boundary audit — is [`docs/extensions.md`](extensions.md) § the sixth point; this doc only
summarizes it. The notebook's tag button is [`docs/notebook.md`](notebook.md); the library's row
and search merge are [`docs/library.md`](library.md) § Search; the backup set is
[`docs/backup.md`](backup.md).

**Status: arc 21 complete** — W1 the point, the module, the screen's BROWSE/ADD core, the library's
first door · W2 the notebook's three doors + MODE_MANAGE · W3 the lasso's Tag · W4 the search merge
(and the record reshape it forced) · W5 extension stores enter the backup set · W6 review, docs,
freeze. **Arc 22 / X3 (2026-09-01) rebuilt the index's storage** — the one-blob layout under
`TagCodec` is gone; `tag` and `assignment` are rows in the host's extension store now, reached
through gated parameterized SQL (`docs/extensions.md` § the store). This doc describes that shape
as it stands today; a mention of "arc 21" below is a historical note about how a decision came
to be, never a claim about what currently runs.

---

## Why an extension, and what it cost

The scratch pad proved that a screen-owning point can hold real state without a `.soil` in sight;
tags reuse that shape rather than inventing a fourth. The core keeps one thing it would otherwise
have had to grow twice: a query surface over an arbitrary set of user-typed labels, attached to
things that live in two different places (the global index's notebooks, a `.soil`'s pages). Living
in `:ext-tags` means the index is rows in one encrypted per-package store — an extension writes
nothing to disk itself, the arc-11 rule holds unchanged — and the host never has to decide where a
tag table would live in `notesprout.db` or inside every `.soil`.

**What arc 21 paid, and what arc 22 removed.** The arc-11 seam only ever offered one shape: a
key/value store. W1 built the index as one store *value* because that was the only shape there
was — the whole library's tags and assignments, encoded by `TagCodec` into one blob under
`ExtensionContract.STORE_MAX_VALUE_BYTES` (4 MiB), decoded whole on every read and rewritten whole
on every edit. That single constraint is what shaped most of arc 21: an identity key that had to
be dropped rather than stored so the worst-case index would fit the byte budget, ids compacted to
a 22-character base64url form to buy back a few bytes each, a `WORST_CASE_BYTES` arithmetic proof
pinned by test, a process-local write lock (`TagWrites`) because a read-modify-write of one blob
has no other way to stay correct between two writers. Arc 22 / X3 rebuilt the seam under it — the
store is real SQLite tables now — and every one of those costs went with the blob that forced
them: the identity key is a stored, uniquely indexed column; ids are plain UUIDs; there is no
byte-budget arithmetic to prove because there is no single value to bound; and the lock is gone
because a SQL transaction already is one. See The data model, below.

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

- **Tabs and newlines cannot reach a stored row.** `Char.isWhitespace` — what `display` collapses —
  already covers both, so nothing that has been through `TagRules.display` carries either one. There
  is no escape layer anywhere in the seam, because there is nothing left needing one.
- **The identity key is derived on the wire, and stored only as a constraint.** `TagRecord`
  computes `identityKey` from `display` the moment a record is built, the same way on both sides of
  the seam; the store's `tag.identityKey` column holds the same value as a `UNIQUE` index, which is
  what makes "does this tag already exist" enforceable with two writers and no lock (see The data
  model). One function produces the value either side ever sees, so the database's constraint and
  the in-memory answer cannot drift apart.

**Lifecycle.** A tag persists until it is explicitly deleted — removing its last assignment leaves
it sitting in the suggestion list, ready to be reused. `TagStore.assign` is idempotent (re-assigning
an attached tag writes nothing and still answers with the canonical spelling, so a double tap on
e-ink costs one read) and keeps the **first** casing: assigning "Reading List" to a library that
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

`TagSchema.V1` (`:ext-tags`) is the whole library's tags, as two tables the extension declares once
and the host applies. Every statement below is quoted verbatim from the code:

```sql
CREATE TABLE tag (
    id TEXT PRIMARY KEY,
    display TEXT NOT NULL,
    identityKey TEXT NOT NULL UNIQUE,
    createdAt INTEGER NOT NULL);
CREATE TABLE assignment (
    tagId TEXT NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    notebookId TEXT NOT NULL,
    pageId TEXT NOT NULL DEFAULT '',
    createdAt INTEGER NOT NULL,
    PRIMARY KEY (tagId, notebookId, pageId));
CREATE INDEX assignment_target ON assignment(notebookId, pageId);
```

**Every assignment names a notebook.** A page tag names its page as well — `notebookId` alone is a
notebook tag, `notebookId` + `pageId` is a page tag, and `pageId`'s presence is what makes it one:
there is no stored `kind` column, because a stored kind would be a second copy of an answer the
row already gives.

This shape is not what W1 shipped. A W1 assignment named a page and nothing else, and W4's own
phase — building the search merge — ran straight into the fact that **nothing anywhere could say
which notebook a tagged page was in**: the global index holds folders and notebooks only, a page
lives entirely inside its notebook's `.soil`, and there is no way to scan for the owner either,
because `KeyMaterial`'s raw-key cache is per file (the salt is per file) — a first-ever open of an
unopened `.soil` costs a full key derivation. The user's call reshaped the record instead of adding
a lookup: every assignment carries its notebook, so the relationship is stored rather than
inferred. `ExtensionContract.API_VERSION` moved to **5** for it at the time — since superseded by
arc 22 / X3's **6**, below. The shape (notebook always, page only for a page tag) is unchanged by
the move to rows; only the storage under it moved.

**`pageId` is `''`, never `NULL`, and it sits inside the primary key.** In SQL `NULL` is not equal
to `NULL`, so a nullable page column would let the same notebook tag be inserted twice with the
primary key saying nothing about it. `''` is a value, and the `String?` ⇄ `""` mapping happens once,
at `TagStore`'s door; everything above that reads `pageIdOrNull`.

**Ids are UUIDs, everywhere.** `tag.id`, `assignment.tagId`, `notebookId` and (non-empty) `pageId`
are all canonical UUIDs, minted with `UUID.randomUUID().toString()`. `TagRules.isId` is the one
shape check at every door on the seam (`TagRecord`/`AssignmentRecord`'s constructors, `TagStore`,
the service's `assignmentsOf`) — round-tripped through `toString()` so only the canonical `8-4-4-4-12`
form is accepted, but **deliberately case-insensitive** on the hex: `CompactId.isId` already was,
and arc 16's `SafeImportId` admits upper-case ids out of a stranger's `.soil` — tightening the check
would make an imported notebook's pages untaggable.

**`identityKey` is a stored, uniquely indexed column — arc 21 derived it and refused to store it.**
On one blob that was the right call: a second copy of an answer can disagree with the question. On
rows the reasoning inverts, because the uniqueness of a tag identity has to be enforced by
*something*, and a `UNIQUE` index is the only thing that can enforce it across two writers with no
lock between them. `TagRules.identityKey` is still the one function that produces the value —
`TagRecord` re-derives it on the wire, the column just gives the store something to constrain on.

**The caps are policy now, not byte arithmetic.** `MAX_TAGS` (5,000), `MAX_TAG_ASSIGNMENTS`
(50,000) and `MAX_TAG_CHARS` (64, on the display form) are the same numbers the arc-21 wizard set,
but each is now a `COUNT(*)` check bound *inside* the insert it gates, race-free because the count
and the insert are one statement in one transaction. `TAG_INDEX_FULL` keeps its meaning — a cap
refused a write, nothing was written — but there is no `WORST_CASE_BYTES` proof to keep in step
with it any more, because there is no single value whose size that arithmetic was ever protecting.

**Every SQL string the seam sends lives in `TagSql`, pinned to exact text by `TagSqlTest`, and every
one runs through the real validator (`StoreSql`) in both the test and `FakeTagStore` — no validator
refusal was ever met while building this.** The shapes that had to clear it: `EXISTS(…)` in
`selectTagByIdentity`, `SUM(pageId = '')` in `selectUsage`, `COUNT(*)` inside both inserts,
`INSERT … SELECT … WHERE` for the same two, and the `IN (?, …)` list `selectAssignmentsOf` builds
from a chunk of ids.

### `assign` — two reads, one transaction

The whole of `TagStore.assign` is two small reads and one two-statement transaction, and the reason
it can be that small is that **the caps ride inside the inserts** rather than being checked
beforehand:

```sql
-- insertTag — gated on BOTH caps, because a new tag is only ever created to be attached
-- in the same batch.
INSERT OR IGNORE INTO tag (id, display, identityKey, createdAt)
SELECT ?, ?, ?, ? WHERE (SELECT COUNT(*) FROM tag) < ? AND (SELECT COUNT(*) FROM assignment) < ?

-- insertAssignment — resolves the tag id BY IDENTITY inside the statement, never from an id
-- the caller read a moment ago.
INSERT OR IGNORE INTO assignment (tagId, notebookId, pageId, createdAt)
SELECT id, ?, ?, ? FROM tag WHERE identityKey = ? AND (SELECT COUNT(*) FROM assignment) < ?
```

The second guard on `insertTag` is a Fable review finding, fixed before the walk: without it, a new
tag created for an attachment the assignment cap then refused would have been an orphan row behind
a sentence that promised "nothing was written." `insertAssignment` resolving the tag id by identity
rather than by a value read earlier is what makes a concurrent creator harmless — if another writer
created the same tag a moment before, this statement's own `SELECT` finds *that* row, and the
assignment lands on it rather than on an id nobody inserted.

`assign` reads `selectTagByIdentity` once before writing (does the tag exist, and is it already on
the target — an idempotent attach short-circuits here, writing nothing) and once after: the
post-write re-read is what turns `INSERT OR IGNORE`'s silence back into a typed `TAG_INDEX_FULL`
failure, and it is also what answers with the **stored** display — the casing whoever entered the
tag first used, even when this call lost a race to create it.

**The transaction is the lock.** Arc 21's `TagWrites` held a process-local monitor around every
read-modify-write of the one blob, because two writers each applying their change to the version
they happened to be holding is how one silently erases the other. There is no blob and no monitor
now: every write is one statement, or two in one batch, that is correct no matter who else is
writing at the same moment — in this process, after a host restart, or from the other process
entirely. `TagWrites` and `TagSession.writes` are deleted.

**`usageOf` and `deleteTag` are reads and a single statement, not arithmetic over an index in
memory.** `usageOf(tagId)` is `SELECT SUM(pageId = '') AS notebooks, SUM(pageId <> '') AS pages FROM
assignment WHERE tagId = ?` — `SUM` over no rows is `NULL`, read as 0 — and it is what the delete
confirm's blast-radius sentence names. `deleteTag(id)` is `DELETE FROM tag WHERE id = ?`; the
declared `ON DELETE CASCADE` takes every assignment of it in the same statement, so "delete with
blast radius" is one write with no window in which an assignment names a tag that is gone.

**A legacy (arc-21) store is wiped on open, not migrated.** `0.1.0-ratta` is unreleased, so every
tag any Nomad build had written before X3 was test data; the first open of a store still shaped
like the old key/value layout drops it and starts empty, logged as a count and never a tag name —
on the Nomad this read `wiped legacy store for …ext.tags.dev (format 1, 1 kv row(s) dropped)`.
There is no migration path from `TagCodec`'s blob to rows, and none is planned.

---

## The seam, briefly

`ITagManager` is one AIDL interface serving two call shapes, and the store is what tells them
apart:

- **A showing** — `begin(store)` → `configureShowing(TagShowing)` → the host launches the screen for
  a result → `end()` — is a **held bind**, the scratch pad's bracket. The store is lent once for the
  screen's whole life and revoked with the unbind. Unchanged since W1.
- **`tags(store, offset)`, `assignmentsOf(store, tagIds, offset)` and `assign(store, text,
  notebookId, pageId)`** are **bind-per-call**, the recognizer's shape: the store rides the one call
  that needs it, because the operation *is* the call and nothing is ever shown. `tags` +
  `assignmentsOf` are the search merge's door (W4, reshaped onto rows at X3); `assign` is the
  lasso's silent heading→tag (W3), unchanged in signature.

**Arc 22 / X3 replaced `snapshot(store)` with two paged reads**, because a reply is now an ordinary
Binder parcel rather than a `LargeValue` over ashmem, and 5,000 tags at roughly 250 parcel bytes
apiece would not fit one transaction. `tags` answers at most `TAGS_PAGE` (500) records in the browse
order (`identityKey`, then `display` — stable by construction, so `LIMIT`/`OFFSET` paging is safe
rather than merely plausible); `assignmentsOf` answers at most `ASSIGNMENTS_PAGE` (1,000) rows for
up to `ASSIGNMENT_QUERY_TAGS` (500) tag ids per call — one `IN (…)` kept comfortably under SQLite's
999-bind limit with room left for the `LIMIT`/`OFFSET` binds, so a longer selection is the host's to
chunk. `TagPages.collect` (`:extension-api`, because both sides run it) is the **one** paging loop:
a short page ends it, and a runaway guard (one page more than the cap could ever fill) stops a
misbehaving peer from spinning forever rather than answering with a silently truncated list.
`TagRecord` and `AssignmentRecord` are `requireValid` parcelables — the row shape crossing the wire,
nothing more. There is no ashmem anywhere on the tag seam any more; `onTransact`/`pending` are gone
from the service entirely.

`TagShowing` (Parcelable, `:extension-api`) is everything one showing needs, and it crosses **on
the bind**, never the screen's Intent: a tag and a target's display label are the user's own words,
and an Intent extra is readable in a `dumpsys` and lingers in the recent-tasks description. Its
constructor `require`s are the whole validation, both directions (unmarshal is validation, the
family rule since arc 1). It carries the target pair (`notebookId` always, `pageId` only for a
page), the resolved `targetLabel`, `mode` (`MODE_BROWSE` / `MODE_ADD` / `MODE_MANAGE`), an optional
`prefill`, and — MANAGE only — parallel `pageIds`/`pageLabels` arrays capped at `MAX_PAGES` (5,000):
the parcel **refuses** rather than allocates above it.

**The transaction is the lock now**, not a process-local monitor (The data model, above, has the
detail): `assign`'s two reads and one two-statement batch are correct whoever else is writing, in
this process or the other, so the screen's own edits and the service's call-shaped `assign` need no
chokepoint between them.

`ExtensionContract.API_VERSION` is **6** — W1 declared 4, W4's reshaped `TagShowing` moved it to 5
(the first bump that was not a compatible tail), and X3 moves it again because `IExtensionStore` was
*replaced*: a version-5 extension calling the old store interface would land on a different method
on a version-6 host, so the host accepts a **store-taking** service (`TAG_MANAGER` included) only at
`MIN_API_VERSION_FOR_STORE` (6) and above.

The full contract — every method's exceptions, the paging loop both `tags` and `assignmentsOf`
share, the boundary-audit rows (what each side may know) — is `docs/extensions.md` § the sixth
point; this section only orients.

---

## The screen

`TagsActivity` (`:ext-tags`) is the whole tag editor, in one Activity built without `:sn-screen`'s
paper pieces (there is nothing to draw). `HostCallerCheck.enforceActivity` is the first statement
in `onCreate`, before anything is inflated — the screen is exported (it has to be; the host
launches it by action) and a plain `am start` with no `callingPackage` is refused outright.

**`TagIndex` (moved into `:ext-tags` at X3) is the screen's in-memory model** — the library's tags
and the assignments of the one notebook this showing is about, loaded in **two reads per showing**
(`store.tags()` + `store.assignmentsOfNotebook(showing.notebookId)`) and asked the same questions
over and over rather than re-querying. It used to live in `:extension-api` and be shared by both
sides of the seam, because the host decoded the same blob the extension wrote; there is no blob now
— the host asks the store for `TagRecord`s and `AssignmentRecord`s directly and does its own
ranking, so this model belongs to the extension alone. What is left is the **query half**: `find`
answers "does this already exist?" by identity key, `tagsOf(notebookId, pageId?)` lists one target's
tags, `isAssigned` answers one membership question, and `suggest(query)` orders live suggestions
exact-identity first, then prefix, then substring matches, each group in browse order — deliberately
**not** `core/FuzzyRank`, which lives in `:app` and answers a different question (matching what you
are searching *for*, not what you are *typing right now* to avoid creating a duplicate). Nothing
here writes; edits go through `TagStore`. **The filter runs against this, never against the store**
— the arc-21 "never a store call per keystroke" lock stands. There is deliberately no aliveness
filter on it either; see Traps and standing decisions.

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

**Every edit is written before it is shown.** `edit()` sends its statements to `TagStore` on IO
behind a busy latch and, once the write lands, **re-reads both queries** — the same two `readIndex`
makes — before the screen adopts the result and fires its `onDone` (the field clearing, the toast).
That re-read is also how another writer's edit arrives: the glass and the store can never disagree,
because what is on screen is always freshly read, never patched in memory. `RESULT_OK` is set only
when something actually changed — an idempotent attach (the tag was already on the target) is an
honest success the host has no reason to redraw for.

**Deleting a tag reads its blast radius before asking.** `confirmDelete` runs `TagStore.usageOf` on
IO behind the same busy latch, because the count is no longer arithmetic over an index already in
memory — this screen only ever holds one notebook's assignments, and a tag's blast radius is the
whole library's. The confirm dialog is built once the read lands; a tap that opened nothing would
otherwise read as a tap that missed.

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

`TagClient.search(ctx, ref) { tags -> ids }` (arc 22 / X3) is the merge's whole door — one pre-open
and **one bind**, inside which: page `tags` to a short page (the host's own `SearchAssembly.matchTags`
runs *inside* the call block, choosing which ids matter), then page `assignmentsOf` for only the
matched ids, chunked at `ASSIGNMENT_QUERY_TAGS` (500) per `IN (…)`. An empty selection — a query that
only matches names — asks nothing at all. This replaces W4's single whole-index `snapshot()` call;
no extension installed still answers `null` silently, and the shelf is exactly arc 20's name-only
one, with nothing to disable because search has no standing tag control.

`TagClient.SEARCH_TIMEOUT_MS` is **10 s**, a first cut and deliberately generous: the Nomad measured
52–78 ms end to end on a 2-tag index (`tags` 15–19 ms, `assignmentsOf` 11–18 ms), but the worst case
— ten tag pages and fifty assignment pages — was never built as test data, so the budget stays loose
rather than tuned to a case nobody has actually run. `assign`'s budget shrank with the work it now
covers: `ASSIGN_TIMEOUT_MS` is **4 s** (was 8 s), because arc 21's assign decoded the whole index,
edited it, re-encoded it and wrote up to 4 MiB back through the large-value path, where X3's is two
small indexed reads and one two-statement transaction.

When a tag manager *is* installed, the search dialog's hint changes from "Folder or notebook name"
to "Folder, notebook or tag" (`LibraryActivity.onResume` keeps `TagManagerEntry`'s discovery current
for it, since the dialog has no button of its own to refresh from).

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
  looked at (see Traps and standing decisions — this is why nothing on either side of the seam
  filters an index for aliveness).

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
Arc 22 / X3 changed what is inside that file, not the copy itself: it is real SQLite tables now, but
still the same file, the same key, the same WAL/sidecar treatment every store gets. A store restored
from a backup taken before X3 still carries the old key/value shape — it is wiped on its first open
after restore, exactly as a never-backed-up legacy store is (The data model, above), never migrated.
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
| The store cannot be reached at all — any exception reading or writing it | `StoreUnavailable` (the store's own catch-all rule); the screen shows `tags_unavailable`, the host's `TagClient` calls fail as `ExtensionCallException` and the doors behave as if the extension were gone |
| A store the host refuses to open (`PRAGMA user_version` > 2 — a newer host wrote it) | Refused outright, surfaced as `StoreUnavailable`; the file is left exactly as found — never-delete-on-corruption |
| A legacy (arc-21 key/value) store shape is found on open | Wiped silently on that open (dropped tables, logged as a count, never a tag name) — the screen simply opens on an empty library; every tag written before X3 was test data on an unreleased build |
| A cap refused a new tag or assignment (`MAX_TAGS` / `MAX_TAG_ASSIGNMENTS`) | `TAG_INDEX_FULL` — `assign`'s post-write re-read turns `INSERT OR IGNORE`'s silence into this; "This library is holding as many tags as it can. Delete one to add another." on both sides, nothing written |
| Two writers create the same tag at the same moment | The second's `INSERT OR IGNORE` into `tag` is a no-op (the `UNIQUE` index); its assignment still resolves the row **by identity** inside its own statement and lands on it — no error, no dangling id, no user-visible difference |
| Typed text is not a valid tag (blank after normalize, or over 64 chars) | The add field: "A tag needs some text, and no more than 64 characters." A heading over the cap instead falls through to the prefilled correction screen rather than a dialog |
| A write raises an exception that is neither a cap nor an unreachable store | `tags_save_failed`; "That change could not be saved." — the screen keeps showing the index it had before the attempt |
| Recognizer not ready during the lasso's ink→tag | The existing heading-convert "still downloading" dialog; the Tag button itself is unaffected, since its visibility never depends on the recognizer |
| Mixed selection, or a lone link, under the lasso | No button offered at all — nothing to fail |
| A displayed page is not in the live page list at the moment a door is tapped (mid page-op) | The tag screen's title falls back to the notebook's own name rather than naming "Page 0" |
| A notebook has more pages than `TagShowing.MAX_PAGES` (5,000) | MANAGE lists the first 5,000 rather than crashing the tap; logged |
| Deleting a tag that is on nothing | "This tag is not on anything. It will be removed from the list." — no blast-radius sentence, since there is no blast radius |
| A search hit's notebook will not open | Its page cards are dropped for that run; its notebook card, if it also matched, is unaffected |

---

## Traps and standing decisions

- **The transaction is the lock.** Arc 21's `TagWrites` held a process-local monitor around every
  read-modify-write of the one blob; there is nothing to hold now, because a statement (or a batch
  of them in `assign`) is correct no matter who else is writing at the same moment — this process's
  screen and service, or the other process entirely, after a restart included. Reintroducing a lock
  here would be solving a problem rows do not have.
- **`INSERT OR IGNORE`'s silence has to be turned into a refusal by reading again.** A conflicting
  insert simply does nothing and reports zero rows changed — indistinguishable, from the statement's
  own answer, between "already there" and "a cap said no." `assign`'s post-write `selectTagByIdentity`
  is what tells the two apart and is why the whole operation is two reads, not one.
- **The 999-bind cap is what shapes `assignmentsOf`'s chunk size.** `ASSIGNMENT_QUERY_TAGS` (500) is
  not a round number chosen for taste: `selectAssignmentsOf` turns its id list into one `IN (?, …)`,
  and 500 leaves comfortable room under SQLite's per-statement bind limit for the `LIMIT`/`OFFSET`
  binds beside it, without either side having to reason about the exact arithmetic at the boundary.
- **`TagRules.isId` is deliberately case-insensitive on the hex.** `CompactId.isId` already was, and
  arc 16's `SafeImportId` admits upper-case ids out of a stranger's `.soil`; tightening the check now
  would make an imported notebook's pages untaggable rather than closing a real gap.
- **`FakeTagStore` applies the four writes literally, not just records them.** `assign`'s whole shape
  rests on a post-write re-read seeing the write, so a fake that only recorded statements could never
  exercise a cap refusal or a concurrent create. It honours `OR IGNORE`, the identity resolution
  inside `insertAssignment`, and both `COUNT` caps — nothing else — and its `beforeExec` hook is what
  lets a test land a second writer's row between `assign`'s pre-read and its own batch
  (`aConcurrentCreatorOfTheSameTagStillLeavesTheAssignmentAttached`). Real SQL is still proved only
  on the Nomad.
- **The caps are `TagStore` constructor parameters, not just contract constants**, so a cap test
  (`theTagCapRefusesAndNothingIsWritten`, `theAssignmentCapRefusesANewTagWithoutCreatingIt`) exercises
  the real `INSERT … WHERE COUNT(*) < ?` statement with a small number instead of having to build
  thousands of rows to reach `MAX_TAGS`/`MAX_TAG_ASSIGNMENTS`.
- **Matching and ranking run off Main, but no longer in the same place.** `SearchAssembly.matchTags`
  runs *inside* `TagClient.search`'s call block, on the IO thread the bind already occupies — pure
  CPU over at most 5,000 short strings, and running it there is what lets it choose the
  `assignmentsOf` selection without a second bind. `SearchAssembly.rank`, the final grouping over
  folders/notebooks/pages, still runs on `Dispatchers.Default` in `LibrarySearch.cards`, because its
  caller is the listing coroutine and it walks every candidate. There is no whole-index decode to
  worry about any more, and there never can be again.
- **There is no undo for a tag operation.** It is not page content, and the destructive one (delete)
  is guarded by a confirm instead.
- **Aliveness is structural, and nothing on either side of the seam filters an index for it.** W1
  shipped a `filterAlive` on the old `TagIndex`, W6's review found it had no caller, and it was
  removed rather than kept — the search merge reads tags only *through* the library's own live
  notebook listing (see § 4. Library search), so an assignment naming a deleted notebook is simply
  never looked at, and `PageNumbers` answers a page's aliveness the same way against the notebook's
  live page rows. A function that filtered an index nobody filters would be a doc comment asserting
  a role it did not have. Pruning stored rows for dead notebooks is a `BACKLOG.md` note, and is now
  one `DELETE FROM assignment WHERE notebookId NOT IN (…)` inside the store's own transaction — no
  lock to route it through any more, though the live id set still has to be handed in by the host,
  because the extension is not the side that knows which ids are alive.
- **The overview's outside-tap dismissal for `TagsPopup` does not write `tapDismissedPopup`** — that
  latch exists so a contact spent dismissing the clipboard popup is not also spent pasting, and the
  tag bar carries no second meaning for a tap to accidentally trigger.
- **Pre-existing, arc-21 shape — not X3's, but observed during X3's walk:** the search shelf re-runs
  its query **twice** on return from a tag screen (`onChanged` + the resume re-list, about 10 ms
  apart). Cheap, and recorded as a `BACKLOG.md` line rather than chased here.

---

## Related

- [`docs/extensions.md`](extensions.md) — the seam in full: `ACTION_TAG_MANAGER` + `_SCREEN`,
  `ITagManager`, `TagShowing`'s wire form, § the store (the schema/SQL contract every store-taking
  point shares), and the tag rows of the boundary audit.
- [`docs/library.md`](library.md) § Search — the merge's own mechanics (the dialog, the ordering
  rule, `FuzzyRank`, folders/notebooks/pages) that this doc only summarizes from the tag side.
- [`docs/notebook.md`](notebook.md) — the top-bar `ic_tag` button and the selection toolbar's Tag in
  their place among the notebook's other chrome.
- [`docs/backup.md`](backup.md) § Extension stores — the backup set and the manual copy-back.
- `apps/notesprout_ratta/RATTA_PLAN.md` § "Phases — Arc 21 \"Tags\"" — the wizard's locked
  decisions and every phase's outcome record, including the two implementer-level arithmetic
  reshapes (W1's dropped identity key, W4's compact ids) that arc 22 / X3 later deleted along with
  the blob they existed for.
- `apps/notesprout_ratta/RATTA_PLAN.md` § "Phases — Arc 22 \"Tables\"", the X3 phase — the rows
  rewrite this doc describes: the locked decisions, the seam spec, and X3's Outcome record (the
  Fable review finding on `insertTag`'s assignment-cap gate, the Nomad walk's timings and test
  data left behind).
