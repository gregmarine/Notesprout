# Bible (arc 37)

**NSE · Bible** is a read-only scripture reader inside Notesprout SN: a book/chapter index, a
paginated print-look page, and single-finger swipe that flows across chapter and book boundaries.
No search, no bookmarks beyond the one remembered position, no cross-reference taps, no footnote
popups. Berean Standard Bible only.

This is the **eighth fresh user decision** on the SN extension seam (granted 2026-09-13), landing
SN's **ninth** extension point (`ACTION_BIBLE` / `ACTION_BIBLE_SCREEN`) and its **fifteenth**
module. It is also the **first module living outside `apps/notesprout_sn`'s own Gradle root** —
code sits at the monorepo root, `extensions/bible/`, pulled into SN's build by
`settings.gradle.kts`'s `projectDir`, on branch `bible`. `extensions/bible/BIBLE_PLAN.md` is both
the plan and the ledger: the fourteen locked decisions, the architecture sketch, and the phase-by-
phase record (B0–B4) that every number and trap below comes from. **There was no code review at
the freeze** — the user's call; the freeze is a Nomad walk instead (B4). The seam itself —
`IBible`, the held bind, the store, the boundary-audit rows — is documented once, in
[`apps/notesprout_sn/docs/extensions.md`](../../../apps/notesprout_sn/docs/extensions.md) § "The
Bible point (arc 37)"; this doc is the feature side and links there rather than repeating it.

---

## The decisions

The user's, locked 2026-09-13 (`BIBLE_PLAN.md`):

1. **Ninth point, `ACTION_BIBLE` + `ACTION_BIBLE_SCREEN`, `API_VERSION` 10 → 11** as a compatible
   addition floored at 11 for this action only — no existing point's floor moves, and no other
   extension redeclares 11.
2. **Module at `extensions/bible/`**, included from `apps/notesprout_sn/settings.gradle.kts` via
   `projectDir` — the pattern recorded for any future extension that does not need to live inside
   the app's own Gradle root.
3. **Door = both bottom bars** — the first non-pager control ever placed on a bottom bar, a
   deliberate, one-time exception to "bottom bars are pager-only." Library: `bottomRight`, left of
   Templates. Notebook: far right of the bottom strip, mirrored in the arc-36 collapsed overflow.
4. **The bundled `.bible` file is copied once to the extension's own `noBackupFilesDir`** — the
   one sanctioned "extension writes to disk" exception in the whole family (ML Kit's model is the
   precedent): re-derivable APK content, never user data.
5. **Slim formatted DB (~12 MB)**: blocks, verse markers, footnotes, cross-references and
   red-letter spans are kept; the word/form/morphology (interlinear) layer and FTS are dropped —
   this reader has no search and no word popup.
6. **Bundle Noto Serif** (regular/bold/italic, OFL) from Biblesprout — a print typeface, not the
   system sans.
7. **Last-read position = book + chapter + first verse on the page**, in the host store's `state`
   table, written on every turn; the first-ever open is Genesis 1.
8. **Formatting**: paragraphs, poetry indents, section headings, superscript verse numbers, chapter
   number + book title on the first page, psalm superscriptions (`d`) italic, parallel-passage
   lines (`r`) small italic (plain, not tappable), footnote callers as a superscript `*` (also not
   tappable). Red letters are stored in the source but **not rendered**.
9. **Reader chrome**: top bar (Back · "Book Chapter" title, tap = index · Index button) + bottom
   pager bar `[‹] [n / N] [›]`; the arrows also turn pages.
10. **Swipe past a chapter edge flows** into the next/previous chapter, across books; Genesis 1
    page 1 and Revelation 22's last page are silent no-ops.
11. **Index = a bordered dialog, Book grid ↔ Chapter grid** — the day picker's shape — current
    book/chapter filled.
12. **Text size is fixed** at 30sp × 1.5 line height (Biblesprout's numbers); no size preference
    this arc.
13. **The Bible joins the cold-launch restore stack** as `Surface.BIBLE` — unlike the tag manager,
    it is a surface worth reopening after a process death.
14. **Label `NSE · Bible` / `NSE · Bible Dev`**, package
    `com.symmetricalpalmtree.notesproutsn.ext.bible` (`.dev` in debug), the family's byte-identical
    puzzle icon, `versionName` in lockstep with `:app` (`0.1.0-sn`).

---

## The data

### The slim build

`tools/bible/build_bible_db.py --slim` is a copy of Biblesprout's builder (same schema version,
same USFM parsing), adapted for CLI paths and given a `--slim` flag that skips two whole layers:
the interlinear **word layer** (`morphology` / `form` / `word` tables and their indexes — original-
language parsing, transliteration, Strong's numbers, concordance) and **FTS** (`verse_fts`, the
plain-text search index). Both exist because Biblesprout's own reader uses them; Notesprout's does
not — no word popup, no search. Everything else is identical to a full build: `metadata`, `book`,
`verse` (clean plain text, unused by this reader but kept for schema parity), `block` (+
`block_chapter` index), `verse_marker`, `redletter`, `footnote`, `xref` (+ `xref_source`), and the
closing `VACUUM`.

Sources, both public domain (Berean Bible):
- USFM: `https://bereanbible.com/bsb_usfm.zip`
- Translation tables (word layer only, unused by the slim build): `https://bereanbible.com/bsb_tables.tsv`

**Attribution.** The text is the Berean Standard Bible, public domain, published by Berean Bible;
the USFM is generated by the BSB-publishing `bsb2usfm` toolchain. `tools/bible/README.md` records
the command used and the exact result:

```
python3 tools/bible/build_bible_db.py --slim \
  --out extensions/bible/src/main/assets/bible/bsb.bible
```

**Size and content, as built and verified (B0):** `bsb.bible` is **12,210,176 bytes** (~11.6 MB),
`PRAGMA integrity_check` returns `ok`, and it carries **31,086 verses / 46,360 blocks / 4,854
footnotes / 3,278 cross-references**. `metadata.layers = "display"` — a full (non-slim) build would
say `"display,words,fts"`; nothing in this reader reads that value, but it is the one place the
file states its own shape.

### Schema

```sql
CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT);

CREATE TABLE book (
  usfm TEXT PRIMARY KEY, ordinal INTEGER NOT NULL, name TEXT NOT NULL,
  testament TEXT NOT NULL, chapter_count INTEGER NOT NULL);

CREATE TABLE verse (
  verse_key INTEGER PRIMARY KEY, usfm TEXT NOT NULL, chapter INTEGER NOT NULL,
  verse INTEGER NOT NULL, text TEXT NOT NULL);

CREATE TABLE block (
  id INTEGER PRIMARY KEY, usfm TEXT NOT NULL, chapter INTEGER NOT NULL,
  kind TEXT NOT NULL, start_key INTEGER, content TEXT NOT NULL DEFAULT '');
CREATE INDEX block_chapter ON block(usfm, chapter, id);

CREATE TABLE verse_marker (
  block_id INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL,
  verse_key INTEGER NOT NULL, number INTEGER NOT NULL);
CREATE INDEX verse_marker_block ON verse_marker(block_id);

CREATE TABLE redletter (block_id INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL);
CREATE INDEX redletter_block ON redletter(block_id);

CREATE TABLE footnote (
  id INTEGER PRIMARY KEY, block_id INTEGER NOT NULL, offset INTEGER NOT NULL,
  verse_key INTEGER, label TEXT, text TEXT NOT NULL);
CREATE INDEX footnote_block ON footnote(block_id);

CREATE TABLE xref (
  id INTEGER PRIMARY KEY, source_kind TEXT NOT NULL, source_id INTEGER NOT NULL,
  start INTEGER NOT NULL, end INTEGER NOT NULL,
  target_start_key INTEGER NOT NULL, target_end_key INTEGER NOT NULL);
CREATE INDEX xref_source ON xref(source_kind, source_id);
```

`verse_key` is a single sortable integer packing (from `VerseKey.kt`, ported verbatim — the reader
and `build_bible_db.py` must agree on this forever): `ordinal * 1_000_000 + chapter * 1_000 +
verse`, canon ordinal 1..66. Keys sort in canonical reading order, and because a chapter's verses
share the same `ordinal*1e6 + chapter*1e3` base, any chapter or verse range is one contiguous
`BETWEEN` on this column. The largest possible key (~66,150,999) fits comfortably in a 32-bit
`Int`.

`block.kind` is the USFM paragraph/heading marker, and the reader's whole vocabulary of it lives in
two small `when`s (`ChapterPaginator.flowFor`/`headingFor`):

| `kind` | Meaning to the reader |
|---|---|
| `p`, `pmo`, `pc`, `pi`, `pm`, `mi`, `nb`, and any other body kind not listed below | Ordinary paragraph flow (first-line indent) |
| `q1` | Poetry, first indent level |
| `q2`, `q3` | Poetry, second indent level |
| `qr` | Poetry refrain (deepest indent) |
| `li1` | List, first indent level |
| `li2` | List, second indent level |
| `b` | Stanza break — a blank separator line, not a heading and not body flow |
| `s1`, `ms`, `ms1` | Major section heading (bold, centered) |
| `s2`, `s3`, `mr`, `qa`, `sr`, `sp` | Minor heading (italic, centered) |
| `r` | Parallel-passage reference line (italic, centered, **80% size** — apparatus, not text) |
| `d` | Psalm superscription (italic, centered) |

A `kind` matching none of the heading rules and none of the flow rules falls through to
`Flow.PARAGRAPH` — the builder's `PARAGRAPH_KINDS` set is wider than what the reader special-cases,
and everything left over reads as ordinary prose rather than being dropped.

### `ContentInstaller` and the disk exception

An asset inside the APK has no real file path, so `ContentInstaller` (`ext/bible/`, ported from
Biblesprout and made crash-safe) copies `assets/bible/bsb.bible` out to
`Context.noBackupFilesDir/bible/bsb.bible` on first use — **the one sanctioned "extension writes to
disk" exception** in the whole SN family (`BIBLE_PLAN.md` decision 4, the precedent being ML Kit's
own model class): what lands there is re-derivable APK content, never user data.
`noBackupFilesDir`, not `filesDir`, is deliberate — the platform backup agent never ships 12 MB of
public-domain scripture anywhere, and an uninstall removes it with the package.

The copy is stamped `lastUpdateTime:size` (the APK's own `PackageInfo.lastUpdateTime` plus the
bundled asset's byte length) and re-copies only when that stamp changes. **Size alone cannot detect
a change**: rebuilding the database can alter its contents without altering its length — SQLite
pads to whole pages, so a fix worth a few dozen characters can land on exactly the same byte
count — and a stale copy would then silently keep serving old content forever. The install time is
the only thing guaranteed to change on every APK update.

The write itself is crash-safe: the stamp file is **deleted before** the copy starts (so a torn
install can never read as complete), the bytes land in `<name>.part`, the output is flushed and
`fd.sync()`ed, `.part` is renamed over the destination, and only **then** is the stamp written. A
power loss mid-copy leaves either the old file or none — never a half one.

### `BibleDatabase` — read-only, two queries

`BibleDatabase` opens the installed file with plain `android.database.sqlite.SQLiteDatabase`,
`OPEN_READONLY` — no SQLCipher, no Room: this is public-domain scripture shipped inside the APK,
not user data, and read-only means nothing here can ever write to it. Three methods, all blocking
(call off Main):

- `books()` — the whole `book` table in ordinal order, into `BookRow`s. Read once by
  `ChapterLoader` and handed to both the chapter cursor and the index dialog, so neither touches the
  database a second time.
- `blocksForChapter(usfm, chapter)` — a chapter's `block` rows in reading order, each carrying its
  own `verse_marker` spans (`RenderBlock` + `VerseMark`), joined in Kotlin rather than SQL so the
  markers arrive pre-grouped by block id.
- `footnotesForChapter(usfm, chapter)` — a chapter's footnotes, ordered by block then caller
  offset.

Nothing else is read: no `xref`, no `redletter`, no `verse`, no `verse_fts` (absent from the slim
build anyway).

---

## The reading model

### Atoms — the pagination quantum

`Atom` (a sealed interface: `NumberAtom`, `WordAtom`, `BreakAtom`, `HeadingAtom`, `FootnoteAtom`) is
the smallest unit the paginator moves — a raised verse number or a single word, never a character
offset. Pages are always cut on atom boundaries, so a word is never split mid-way and no fragile
offset arithmetic is needed to know where a safe cut lives. `HeadingAtom` carries a `HeadingKind`
(`MAJOR` / `MINOR` / `REFERENCE` / `SUPERSCRIPTION`) — Biblesprout's single `minor: Boolean` grew
into four kinds here because decision 8 renders all four differently (bold vs. italic vs. small
italic vs. italic superscription).

`ChapterPaginator.atomsForBlocks(blocks, footnotes)` flattens the rich block layer into one atom
stream: each block contributes either a `HeadingAtom` (via `headingFor`) or a leading `BreakAtom`
styled by `flowFor` (see the `kind` table above) followed by its tokenized content — verse-number
spans lifted out as `NumberAtom`s, footnote callers spliced in at their exact offset as
`FootnoteAtom`s, everything else split on spaces into `WordAtom`s.

### `ReaderTypography` — the measure/draw invariant

`ReaderTypography` is both the font/size table **and** the `BodyMeasurer` the paginator measures
against — the load-bearing trick: the paginator asks it "how tall does `atoms[start, start+count)`
render at this width," and `ReaderView` later draws the **identical** `StaticLayout` configuration.
A page that measures as fitting therefore always renders without overflow, by construction, not by
convention.

Constants (fixed this arc, decision 12): body text **30sp** Noto Serif at **1.5×** line height
(`lineHeightPx = bodySizePx * 1.5`); a book title at 0.85× body size, bold, `letterSpacing = 0.08f`;
a chapter number at 2.1× body size, bold. Poetry/list indent unit 22dp, paragraph first-line indent
18dp. Two named gaps, shared between the paginator's height math and the view's draw math so they
can never drift apart: `GAP1_DP` (4dp, under the book title) and `GAP2_DP` (22dp, under the chapter
number).

The span table `build()` applies, per atom:

| Atom | Spans |
|---|---|
| `HeadingAtom` | `AlignmentSpan.Standard(CENTER)`, `StyleSpan(BOLD)` for MAJOR / `StyleSpan(ITALIC)` for the other three, and `RelativeSizeSpan(0.8f)` **only** for REFERENCE |
| `NumberAtom` | `RelativeSizeSpan(0.62f)` + `StyleSpan(BOLD)` + `SuperscriptSpan()` |
| `FootnoteAtom` | `RelativeSizeSpan(0.7f)` + `StyleSpan(BOLD)` + `SuperscriptSpan()`, rendered as a literal `*`, attached with no leading space |
| The whole buffer | one `LineHeightSpan.Standard(lineHeightPx)` over `[0, length)` |

**The exclusive-paragraph-span landmine**, recorded in the code as a trap: every paragraph-level
span (`AlignmentSpan`, `LeadingMarginSpan`, the per-run styling) must be set with
`SPAN_EXCLUSIVE_EXCLUSIVE`. An `INCLUSIVE` end sitting at the buffer's tail would grow into every
later append — a heading's center alignment would bleed onto the rest of the page, or one line's
poetry indent would spread across lines appended after it. The one span that is deliberately
**inclusive** is the whole-buffer `LineHeightSpan.Standard`, set once at the very end over
`[0, sb.length)` — it must cover everything already written, and nothing is appended after it.

Indent table (`applyFlow`, a `LeadingMarginSpan.Standard(first, rest)` per line):

| `Flow` | First-line / rest margin |
|---|---|
| `PARAGRAPH` | `paraIndent` (18dp) / 0 |
| `POETRY1` | `indentUnit` (22dp) / `indentUnit × 2` — hanging indent, wrapped lines sit deeper |
| `POETRY2` | `indentUnit × 2` / `indentUnit × 3` |
| `POETRY_REFRAIN` | `indentUnit × 3` / `indentUnit × 3` — no hang, the deepest level |
| `LIST1` | `indentUnit` / `indentUnit` |
| `LIST2` | `indentUnit × 2` / `indentUnit × 2` |
| `STANZA` | none — a stanza break is a blank line, not a styled one |

`bodyLayout()`/`measure()` both build the same `StaticLayout.Builder` — `BREAK_STRATEGY_SIMPLE`,
`HYPHENATION_FREQUENCY_NONE`, `setIncludePad(true)`, line spacing `(0, 1f)` — so the measure/draw
invariant holds at the `StaticLayout` construction site, not just at the span level.

### `ChapterPaginator.paginate` — fitting pages

`paginate(atoms, measurer, width, firstPageHeight, otherPageHeight)`: the **first page is shorter**
than the rest by the heading's own height (`headingHeight` = book title + `GAP1` + chapter number +
`GAP2`) plus an 8dp **safety pad** against rounding error ever clipping a line. Each page is built
by `fitCount` — a galloping search (doubling `hi` until it overshoots, or runs out of atoms) followed
by an ordinary binary search inside that bracket — for the most atoms whose measured height fits the
page. At least one atom is always placed, guaranteeing pagination makes progress even when a single
oversized atom does not fit.

`trimDanglingOpeners` is the print-behaviour rule, **keep-with-next**: a fitted page is never left
ending on the atoms that *open* the next verse — a `NumberAtom`, or the `BreakAtom`/`HeadingAtom`s
before it — when the verse's actual text spilled to the next page. The cut backs off to the last
real content atom (a word or a footnote caller). If the fitted span has **no** content atom at all
(a lone oversized heading), it is left as-is rather than trimmed to nothing, so pagination still
advances. The B4 walk saw this land in Luke 1: page 1 ends after verse 4 with visible white space
below, because the next heading + reference line + verse 5 did not fit together and got kept on
page 2 as one unit — recorded as correct print behaviour, not a bug.

`anchorVerses(pages)` / `pageContaining(anchors, verse)` are the position machinery (added B2,
replacing a simpler `firstVerseKey` the ledger explicitly did not keep alongside them — "a second
anchor rule is a drift waiting to happen"). A page's anchor is the verse **in effect** at its first
word: the last verse number standing before the page's first content atom, or — if the page opens
mid-verse — the verse carried in from the page before it; a page with no verse anywhere before or on
it (a lone heading at a chapter's start) anchors to verse 1. Anchors are non-decreasing by
construction, which is what makes `pageContaining` a plain linear scan for the **last** page at or
before a verse, and what makes "write the anchor on every turn, reopen on the page containing it" an
honest round-trip. **A verse spilling over two pages anchors both** — a reopen lands on the *last* of
them, recorded as intended, not a bug.

### `ChapterLoader` — one DB, one typography, per screen

`ChapterLoader` is everything the screen needs off Main, in one place (added B2, pulled out of a
`BibleActivity` that would otherwise have grown past its size budget): the installed `BibleDatabase`,
one `ReaderTypography`, the `ChapterCursor`, `build()`, and a 5-entry `LinkedHashMap` cache
(`CACHE_MAX = 5` — current + two neighbours, with room for the pair behind them), oldest evicted,
cleared whole whenever the band's geometry changes.

Two monitors, deliberately separate: `lock` guards the fields and cache — held only around field
work, never around the first-run 12 MB asset copy or a database open, which Main must never end up
waiting on from `onDestroy`. `buildLock` serializes chapter **building** specifically: one
`ReaderTypography` means one shared `TextPaint`, and a `Paint` measured from two threads at once is
not safe — the cost is that a foreground load can block behind an already-running prefetch build,
while the screen shows "Loading…" regardless.

### `ReaderView` — one page, repaint on change only

`ReaderView` draws one `ReaderPage` (`body` layout, plus `title`/`number` layouts on a chapter's
first page) at **44dp horizontal / 10dp vertical** padding. `show(page)` is the one door, and an
identical page (reference equality) is a no-op: `invalidate()` is skipped entirely. A view that
repaints itself on e-ink ghosts, and every needless frame is a visible flash — this is the same rule
every other paper-adjacent surface in SN follows, restated here for a surface that draws no paper at
all.

---

## The screen

`BibleActivity` is SN's **fifth** screen-owning extension point and, like the tag manager, carries
**no paper**: no `PaperView`, no g-paper call, and therefore no EPD handoff, no
`releaseForHandoff()`, no `EXTRA_CHROME_HIDDEN`.

**The caller check runs first**, before `super.onCreate` does anything and before a single view is
inflated: `HostCallerCheck.enforceActivity` refuses anything but a `startActivityForResult` from the
host package. On a refusal, `onCreate` returns immediately, having set nothing up. **The `admitted`
flag exists because `onDestroy` still runs on a bounced launch** — the B2 finding: a shell `am start`
against the exported screen was being refused correctly, but was *also* crashing the extension
process out of sight, because `onDestroy` ran straight into `lateinit` teardown against fields that
were never built. `admitted` is set only after the check passes, and `onDestroy` returns immediately
when it is false — the root `IndexGuard.bounced` rule, restated in the extension's own shape.

**Chrome**: a top bar (Back · a centred title that is also a tap target for the index · Index
button, `FrameLayout`-centred on the *screen*, not weight-centred between its neighbours, so it
never drifts when one side's content changes width) and a bottom pager bar (`[‹] n / N [›]`). The
first non-pager control on either the library's or the notebook's bottom bar is the Bible button
itself — that lives on the **host** side, not here (see The doors, `extensions.md`).

**The open sequence**: pagination needs the reading band's real pixel size, so nothing starts before
`readerBand.doOnLayout { openWhereWeLeftOff() }` fires. That reads the stored position on IO
(swallowed to `null` on any failure), decodes it (`Position.decode`, falling back to
`Position.GENESIS_1` on anything unreadable), opens that chapter, and picks the page via
`ChapterPaginator.pageContaining(anchors, verse)`.

`ListSwipe` is built once in `onCreate` and fed from `dispatchTouchEvent`, **before** calling
`super` — as a pure observer: it consumes nothing, so every button keeps receiving its own taps, and
it drops stylus/eraser sequences itself, so a pen resting on or dragging across the page never turns
it (decision 9's swipe requirement, finger-only by the user's call — no tap zones anywhere on the
page).

**Chapter flow** (`turnTo`): a turn inside the current chapter is a plain page show; a turn past
either end asks `ChapterCursor` for the neighbouring chapter and opens it at page 0 (forward) or its
**last** page (backward) — so stepping back off a chapter's first page always lands on where a
reader stepping forward would have been. A **load latches** further turns away while it is running,
rather than queueing them — on e-ink a queued turn arrives long after the hand that made it has given
up. After every successful show, both neighbouring chapters are built into the loader's cache on IO,
so crossing an edge is usually one `invalidate()`.

**The position write** (`remember`) is fire-and-forget on IO and **coalesced**: while one write is in
flight, a newer value replaces whatever was waiting rather than queueing behind it, so ten fast flips
cost two writes, not ten. A write failure is swallowed with `Slog.d` — never a dialog; a lost
bookmark is not an error the user has to answer (decision 7).

**"Loading…"** appears only if a chapter build outlasts `LOADING_DELAY_MS` (300 ms) — a word, never a
spinner, and invisible for every ordinary open.

**A failed chapter open** is `Dialogs.problem(bible_unavailable_title, bible_unavailable_body)`,
never a blank reading band.

**Back** (`leave()`) sets `RESULT_OK` and finishes unconditionally — there is no meaningful
cancel/commit distinction for a reader with nothing to save on its own screen (the position write
already happened on every turn).

---

## The index

**`IndexModel`** (pure, no views, JVM-tested) is the day picker's arithmetic one arc over: two grids
behind one pair of arrows. Books are **3 × 6** (18/page) — a book's *name* has to fit, and "1
Thessalonians" at three columns is already tight; chapters are **6 × 6** (36/page) — a number wants a
square, not a column. Both flow in **canon order**, with **no testament boundary shown or paged on**
— a break at the OT/NT line would leave Malachi's page two-thirds empty for a word ("Old Testament" /
"New Testament") this reader never says anywhere.

**Every page is padded to the full `rows × columns` grid**, not just its last row to a full row —
the B4 finding: a short last page (2 John's one-chapter book; the canon's own 12-book last page) was
shrinking the bordered dialog and **re-centring it under the finger mid-tap**. `IndexModel.grid` now
pads with spacer rows so the dialog has exactly one height everywhere it opens; two tests were
re-pinned to the padded shape. Nonsense inputs are absorbed rather than thrown: an unknown book code,
a chapter below 1, or an empty page count all answer page 0.

**`IndexDialog`** is the calendar day picker's shape reused subject for subject: the same header
(prev arrow · centred title kept clear of both arrows by a button-width margin · next arrow), the
same code-built grid host, `Dialogs.style` + `setNegativeButton(cancel)`, and the window sized to
**0.75 of the screen width after `show()`** (before `show()` there is no window to size, and the
weighted cells must measure against this width, not the screen's). **No background on the root
view** — the window's own `shape_dialog_bordered` supplies both the white fill and the border, and an
opaque root would paint over it; this is the same dialog-border trap the day picker itself paid for
first.

Two levels behind one pair of arrows: **Books** (title "Books", not a tap target — there is nowhere
above it to flip back to) opens on the current book's page with that book filled black/white-bold.
Tapping a book opens its **chapters**, titled with the book's own name — which doubles as the way
back out (the day picker's title-flip trick, `cd_bible_index_flip`). The current chapter is filled
only **inside its own book's** grid. Cells are the month-cell recipe: `shape_bordered` /
`bg_index_selected` (copied into the extension's own drawables from `bg_month_selected`), centred,
`maxLines = 2` + ellipsize-end for long names, weight-1 wide, `@dimen/toolbar_button_size` tall —
never a literal number. A `null` cell renders as a bare spacer `View` that holds its column open.
**The arrows never disable**: at either end, `clampPage` returns the page already showing and the tap
repaints nothing at all — a greyed control is invisible on e-ink, and a needless repaint on e-ink is
a flash.

`BibleActivity.openIndex()` is reached from **both** `btnIndex` and the title itself — "Genesis 1" is
the obvious thing to tap when you want to be somewhere else, and the title carries the same
long-press hint the icon buttons do. The books list comes from `ChapterLoader.booksNow()` — B2's
`source()` already read the `book` table once for the cursor, so opening the index costs **no second
database query** and the dialog itself never touches the database. Before the first chapter has ever
shown, `booksNow()` is empty and the tap is a **silent no-op** — a "not ready" dialog would be noise
for the half-second window in which it would ever be true. A picked chapter goes down the ordinary
chapter-edge path (`openChapter(picked) { 0 }`), so the load latch, the neighbour prefetch, and the
position write all follow exactly as they do for any other turn.

---

## The store

One row, one table, the calendar's `state` / the document editor's `prefs` precedent:
`BibleSchema.V1` = `state(key TEXT PRIMARY KEY, value TEXT NOT NULL)`. The one key it ever holds is
`position` (`BibleSql.KEY_POSITION`); `INSERT OR REPLACE` (`BibleSql.UPSERT_STATE`) is safe because
`state` has no children for a replacement to cascade away.

**`Position`**'s wire form is deliberately boring and human-readable: `GEN:1:1` (`encode()`).
`decode` is **total** — wrong field count, a non-integer chapter or verse, an unrecognized book code,
or a chapter/verse below 1 all yield `null` rather than throwing, and `BibleActivity` falls back to
`Position.GENESIS_1` in every one of those cases (decision 7's promise: a lost or malformed bookmark
is never a dialog). `applySchema(BibleSchema.V1)` runs on **every** call into `BibleStore` — read or
write — because it is idempotent (one `SELECT` host-side once applied) and it is the *only* door: the
host's own gate refuses `exec`/`query` on a binder that has never declared its schema.

Every store exception, whatever its real cause, becomes `StoreUnavailable` — the family-wide "treat
every store failure as unreachable" rule; `BibleStore` never distinguishes a missing table from a
revoked binder from a network-adjacent failure, because none of that distinction would change what
the reader does about it (fall back silently).

---

## Privacy / logging

**"Where, not what."** What crosses any log line on either side of the seam: book and chapter
numbers, page counts, durations, and the bare fact of `begin`/`end`. **The position value itself is
never logged** — `remember`'s own failure path (`Slog.d(TAG) { "position not saved" }`) deliberately
omits the value it just failed to write, because that value names exactly where the user has been
reading. Scripture text never crosses the seam at all: `BibleService` hands the extension a store
binder and nothing else; the reader reads what it shows out of its own installed `.bible` file, never
from the host.

---

## Failure table

| Situation | What the user sees | Where |
|---|---|---|
| Store unavailable at open (or no store lent at all) | Opens on Genesis 1 — `openWhereWeLeftOff` treats a read failure and an absent store identically, decoding `null` and falling back | `BibleActivity.openWhereWeLeftOff`, `Position.decode` |
| The bundled asset copy fails (disk full, permission) | `Dialogs.problem(bible_unavailable_title, bible_unavailable_body)` — never a blank reading band | `BibleActivity.openChapter`'s `onFailure`, `ContentInstaller.ensureInstalled` |
| A chapter reference with no `block` rows (a hole in the source) | Same problem dialog — `ChapterLoader.build` throws on an empty block list, caught the same way as any other open failure | `ChapterLoader.build` (`check(blocks.isNotEmpty())`) |
| A swipe or pager tap at Genesis 1 page 1, or off Revelation 22's last page | Nothing — a silent no-op, never a disabled button or a bounce | `BibleActivity.turnTo`, `ChapterCursor.prev`/`next` returning `null` |
| A tap on Index before the first chapter has ever shown | Nothing — `booksNow()` is empty and the dialog never opens | `BibleActivity.openIndex` |
| The host revoked the store mid-showing | Position writes silently fail and are swallowed (`Slog.d`, never surfaced); reading continues normally, just unremembered | `BibleActivity.remember`, `BibleStore.guard` → `StoreUnavailable` |
| A refused caller (a shell `am start`, or any non-host launcher) | `finish()` runs before a single view is inflated — no crash, no visible screen at all | `BibleActivity.onCreate`, `HostCallerCheck.enforceActivity` |

---

## What the walks proved on the Nomad

**B0** (the seam, adb-driven): discovery answered `1 provider(s) of 1 candidate(s)`; the button
appeared on both bars; a tap opened `begin` → the screen — cold open **2.6 s**, warm **0.7 s**; Back
closed it (`end` → unbind); a shell `am start` of the screen was refused, leaving the notebook
focused; force-stopping host and extension **in one shell command**, then relaunching, restored
`[BIBLE]` above the notebook.

**B4** (adb half; the hand-feel half is the user's separately):
- **Index**: books page 1 → arrows paged to Psalms (page 2) → Psalms' chapter grid → 23 → Psalm 23
  rendered as print — italic superscription, hanging poetry indents, verse numbers in the margin,
  footnote callers. Book names fit their cells at 14sp on the 62dp tier ("Song of Solomon", "1
  Chronicles" each stayed one line).
- **Pruning** (the one fix this phase made): the padded-grid fix above, caught by 2 John's one-row
  chapter grid re-centring the dialog under a finger.
- **Chapter edges**: Revelation 22 → last page → further swipes logged nothing (silent no-ops);
  Genesis 1 page 1 → a back-swipe was equally silent.
- **A long chapter**: Psalm 119 — 30 pages built in **1,245 ms** cold, on IO, with the reader staying
  responsive throughout.
- **Prose keep-with-next**: Luke 1 page 1 ended after verse 4 with visible white space below it — the
  next heading, its reference line, and verse 5 didn't fit together and `trimDanglingOpeners` kept
  them on page 2 as one unit. Print behaviour, not a bug.
- **Timings**: chapter opens ranged 265–480 ms cold, ~2 ms from the prefetch cache; screen opens
  570–770 ms warm.

---

## Traps

- **`am crash` vs. `force-stop` for a restore test.** `am crash` on the host lets Android relaunch
  the task, and the library's own `onResume` resets the surface stack to `[]` before a second kill
  can land — a launch-restore test must `am force-stop` the host **and** the extension in one shell
  command, never crash the host first and kill the extension after.
- **A stale `extension-api/build/`** left over from the `notesprout_ratta` → `notesprout_sn` module
  rename fails `mergeLibDexDebug` ("located outside the root directory") until removed by hand
  (`rm -rf extension-api/build`).
- **A `README.md` inside `res/font/` breaks the resource merger** — recorded in `FONTS.md` instead;
  the license note for the bundled Noto Serif faces lives there, not as a resource-directory file.
- **The dialog re-centre trap.** A grid page shorter than its siblings shrinks the bordered dialog and
  visibly re-centres it under whatever finger just tapped — the B4 finding fixed by padding every
  `IndexModel` page to the full grid rather than only its last row to a full row.
- **The `onDestroy` bounce.** A caller-check refusal still runs `onDestroy` (Android calls it
  regardless of what `onCreate` did), so a screen that built nothing must guard its teardown with an
  `admitted` flag rather than assume `onCreate` finished — the root `IndexGuard.bounced` shape,
  reproduced here because the extension has no `IndexGuard` of its own to inherit it from.
- **`noCompress "bible"`.** The bundled `.bible` file must be declared uncompressed in
  `androidResources` — a compressed asset has no meaningful `AssetFileDescriptor.length` (the
  fallback `bundledSize` returns `-1`, degrading the install-detection stamp to copy-if-missing) and
  cannot be opened by SQLite without being extracted first regardless.

---

## Tests

**46 JVM tests** across seven files (`src/test/kotlin/.../ext/bible/`), counted directly from the
source:

| File | Tests | Pins |
|---|---|---|
| `BibleSqlTest.kt` | 4 | The two SQL statement strings verbatim, the `position` key literal, and that the schema is exactly one version of one statement |
| `CanonTest.kt` | 5 | 66 books, ordinals 1–66 in order, the 39/27 OT/NT split, unique three-character USFM codes, case-insensitive/ordinal-addressed lookup |
| `VerseKeyTest.kt` | 5 | The packing formula, encode/decode round-trip, reading-order sort, chapter bounds covering exactly one chapter, a verse range containing only its own span |
| `PositionTest.kt` | 4 | Wire-form round-trip, Genesis 1 as the default, lowercase book codes normalizing, every malformed shape decoding to `null` |
| `ChapterCursorTest.kt` | 7 | Ordinary in-book stepping, book-to-book flow both directions, Genesis 1 having nothing before it, the last book's last chapter having nothing after it, a book the source omits being skipped in both directions, a zero count or unknown code walking nowhere |
| `IndexModelTest.kt` | 12 | The canon's four book pages, every row exactly three (or six) slots wide, a short last page padded to the full grid, canon-order paging across the testament boundary, `bookPageOf`/`chapterPageOf`/`clampPage`'s arithmetic, Psalms' five chapter pages, a one-chapter book as one cell plus thirty-five spacers, an empty book table making no pages |
| `reader/ChapterPaginatorTest.kt` | 9 | Blocks becoming headings/numbers/words with a spliced footnote caller, minor heading kinds mapping to `MINOR`, a page never ending on a bare verse number, forced progress when nothing fits, `fitCount` returning zero when even one atom overflows, a page anchoring to the verse in effect at its first word, a verse-less opening page anchoring to verse 1, `pageContaining` picking the last page at or before a verse, and a real pagination round-tripping every page's anchor back to that same page |

---

## Not in this arc (recorded futures, each needing a user decision)

- **Search** — no free-text or reference lookup; `Canon`'s alias/normalize table was deliberately
  left behind in the Biblesprout port rather than carried forward unused.
- **Bookmarks** beyond the single remembered reading position.
- **Cross references** — the `xref` table is built and shipped but nothing reads it; the `r`
  (parallel-passage) lines and footnote callers render as plain, non-tappable text.
- **Footnote popups** — footnote bodies (`footnote.text`) are stored and joined at chapter load but
  never shown; the caller renders as an inert superscript `*`.
- **Red letters** — `redletter` spans exist in the source and are copied by the slim build but are
  never read by the reader; words of Jesus render identically to surrounding text.
- **The interlinear/word layer** — original-language words, Strong's numbers, morphology and
  concordance all require a **non-slim** rebuild (`build_bible_db.py` without `--slim`) plus new
  reader code; nothing here reads `morphology`/`form`/`word` even when present.
- **A text-size preference** — decision 12 fixed 30sp × 1.5 line height for this arc only.
- **Tap zones** — the reader turns pages by swipe only, on the user's explicit call; a tap-to-turn
  zone was not built.
- **A "Psalm 23" title special case** — `book.name` is "Psalms" (plural, matching the source), so the
  title and running head currently read "Psalms 23" where print convention would say "Psalm 23."
  Left open at the B4 freeze as a one-word special case the user may or may not want.

---

## Related

- [`apps/notesprout_sn/docs/extensions.md`](../../../apps/notesprout_sn/docs/extensions.md) § "The
  Bible point (arc 37)" — the seam in full: the held bind, the second no-paper tier-2 screen, the
  store and its one sanctioned disk exception, the doors, and the boundary-audit row.
- `extensions/bible/BIBLE_PLAN.md` — the plan and the ledger: every locked decision, the
  architecture sketch verified against the code, and the B0–B4 phase records this doc draws every
  number and trap from.
- `apps/notesprout_sn/CLAUDE.md` — the module-table entry, the ninth-point summary, and the
  "bottom bars are pager-only, with one recorded exception" rule.
- `tools/bible/README.md` — the exact build command and result for `bsb.bible`.
