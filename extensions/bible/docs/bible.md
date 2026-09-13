# Bible (arc 37)

**NSE · Bible** is a read-only scripture reader inside Notesprout SN: a book/chapter **Contents**
panel, a paginated print-look page, single-finger swipe that flows across chapter and book
boundaries, and — B7 — a **Recents** panel of the chapters picked by name. No search, no bookmarks
beyond the one remembered position and that history, no cross-reference taps, no footnote popups.
Berean Standard Bible only.

**Arc 38 "Reference"** (2026-09-13, a fresh user decision, § [Bible references](#bible-references-arc-38)
below) grew a second door **in**, from the notebook: a lassoed or typed reference ("John 3:16-18,
Proverbs 3:5-6") becomes a text object wrapped in a link, and a finger tap opens this reader on
**just those verses** — a passage view, distinct from the chapter reading this doc otherwise
describes — with a **Full chapter** door back into the ordinary reading model. Nothing about this
is a tenth extension point; the seam grew two compatible tails on the ninth.

This is the **eighth fresh user decision** on the SN extension seam (granted 2026-09-13), landing
SN's **ninth** extension point (`ACTION_BIBLE` / `ACTION_BIBLE_SCREEN`) and its **fifteenth**
module. It is also the **first module living outside `apps/notesprout_sn`'s own Gradle root** —
code sits at the monorepo root, `extensions/bible/`, pulled into SN's build by
`settings.gradle.kts`'s `projectDir`, on branch `bible`. `extensions/bible/BIBLE_PLAN.md` is both
the plan and the ledger: the fourteen locked decisions, the architecture sketch, and the phase-by-
phase record (B0–B4) that every number and trap below comes from. **There was no code review at
the freeze** — the user's call; the freeze is a Nomad walk instead (B4), and **the arc was frozen
2026-09-13 on the user's own hand walk** ("it all passes and looks great for this phase"). The seam itself —
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
   pager bar `[‹] [n / N] [›]`; the arrows also turn pages. **Amended by B7 (2026-09-13, the
   user's call): the bar runs the notebook's order — `[Back] [Contents] · title · [Recents]` —
   "Index" is now "Contents" everywhere the user reads it, and the clock is the Recents door.**
10. **Swipe past a chapter edge flows** into the next/previous chapter, across books; Genesis 1
    page 1 and Revelation 22's last page are silent no-ops.
11. **Index = a bordered dialog, Book grid ↔ Chapter grid** — the day picker's shape — current
    book/chapter filled. **Amended post-freeze (B6, 2026-09-13, the user's call): the index is a
    paginated side panel in the notebook Contents' shape** — every book a root row, an open book
    followed by its chapters as a six-wide grid, opened by the Index button, the title, or a
    **one-finger swipe down** on the page (§ The Contents). **Renamed "Contents" by B7** (the
    notebook's word; the code followed — `ContentsPanel` / `ContentsModel` / `ContentsLayout`).
12. **Text size is fixed** at 30sp × 1.5 line height (Biblesprout's numbers); no size preference
    this arc.
13. **The Bible joins the cold-launch restore stack** as `Surface.BIBLE` — unlike the tag manager,
    it is a surface worth reopening after a process death.
14. **Label `NSE · Bible` / `NSE · Bible Dev`**, package
    `com.symmetricalpalmtree.notesproutsn.ext.bible` (`.dev` in debug), the family's byte-identical
    puzzle icon, `versionName` in lockstep with `:app` (`0.1.0-sn`).

**Arc 38 "Reference"** (2026-09-13) is a separate fresh decision, made after this arc froze — its
own fourteen locked decisions and judgment calls live in `extensions/bible/REFERENCE_PLAN.md`, not
here. What it added to *this reader* — the passage mode, Full chapter, and the Recents' second
table — is § [Bible references](#bible-references-arc-38) below; nothing above this line changed
to accommodate it.

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
  `ChapterLoader` and handed to both the chapter cursor and the Contents panel, so neither touches the
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

**Chrome**: a top bar — `[Back] [Contents]` at the start, a centred title that is also a tap target
for the Contents, `[Recents]` (the clock) at the end; the notebook's own order since B7. The title
is `FrameLayout`-centred on the *screen*, not weight-centred between its neighbours, so it never
drifts when one side's content changes width; its side margins are symmetric and clear two
tablet-tier buttons (2 × 62 dp) — and a bottom pager bar (`[‹] n / N [›]`). The
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

## The Contents

Reshaped post-freeze by **B6** (2026-09-13, the user's decision) and **named by B7** ("Index"
until then — the notebook's word won, in the strings and in the class names alike): B3's bordered
two-grid dialog is gone, and the Contents is **the notebook Contents' side panel, subject for subject** — the same
paginated rows, the same sidebar/full-screen forms, the same pager footer and body swipe, the same
swipe-down door — with one deliberate difference: **a book's second level is a grid of chapter
numbers, not rows** (the B3 grid kept; a number wants a square, not a line of its own).

**`ContentsModel`** (pure, no views, JVM-tested) answers what the list looks like: **one flat list of
uniform-height rows** — every book a `Book` row in canon order, an *expanded* book followed by its
`Chapters` rows of exactly [`CHAPTER_COLUMNS`] = 6 slots, the last row padded with `null`s so a
column never collapses (2 John: one cell, five spacers). Flat and uniform on purpose: it lets the
panel paginate the way the Contents paginates headings — rows per page from one division — and
Psalms' 25 chapter rows simply run on across pages like any long outline. `indexOfBook` /
`indexOfChapter` (the row holding a chapter, only while its book is open) find the opening page;
`pageOf` / `pageCount` / `clampPage` are the paging arithmetic, every nonsense input answering
page 0 or one page rather than throwing. Expansion state is the panel's, handed in as a set of
USFM codes (case-insensitive).

**`ContentsLayout`** (pure, JVM-tested) is the host's `ContentsLayout` in the extension's own copy —
that object lives in `:app`, which an extension never depends on — with **the Contents' numbers on
purpose**: full screen below 480 dp, a 60 % left sidebar at or above (both real devices take the
sidebar: Nomad 749 dp / Manta 1024 dp), 68 dp rows + 1 dp separator, `itemsPerPage` ≥ 1 from the
measured body. A change to one belongs in the other.

**`ContentsPanel`** is `ContentsDialog` reused: a full-window `Dialog` (`Theme_Notesprout`, transparent
background, no dim, no elevation) over the reader; `dialog_contents.xml` is `dialog_contents.xml` with
the extension's ids — header (`Contents`, a back arrow only in the full-screen form) · rows · pager —
branching in code on `ContentsLayout.fullScreen`: the sidebar takes `shape_contents_sidebar` (2 dp
inkBlack right edge) over a transparent scrim whose tap dismisses, the full-screen form is plain
paper with the back arrow. **Rows**: a book is `item_contents_book.xml` —
`[+/− toggle | chapter count 52 dp | 1 dp divider | name 20 sp]`, the Contents' row with the page
number's slot holding the book's length; **the `+`/`−` toggle shows or hides the chapters, and a
tap on the row itself opens the book at chapter 1** — the Contents' own split of tap = go, toggle
= show (the user's call, replacing a first cut where the whole row toggled). A chapter row is built in
code to exactly a book row's slot (`ContentsLayout.rowPx`) so the list stays uniform, indented past
the toggle so it reads as the book's child, its cells the B3 cells (`shape_bordered` /
`bg_contents_selected`, weight-1 wide, `@dimen/toolbar_button_size` tall — never a literal number;
a `null` a bare spacer `View`). A chapter tap dismisses and hands the `ChapterRef` up.

**Opening state**: the book being read is the one open (**expansion is in-memory only** — every
open starts from the current book alone, several may be opened during one showing), its row takes
`bg_contents_active_entry` (the Contents' 5 dp right-edge bar), its current chapter's cell is filled
black/white-bold, and the list opens on the page holding that chapter's row — the panel opens
looking at where you are. `itemsPerPage` is measured once from the real body height after the
first layout (the Nomad: 12 rows a page, 842 px wide). A toggle **re-anchors the page on the
toggled book's row**, so a collapse below the fold never leaves the reader on an emptied page.
**The pager never disables**: a tap at a bound is a no-op (`clampPage` returns the page already
showing, nothing repaints), the footer is `INVISIBLE` at one page. A **one-finger horizontal swipe
over the body** flips pages too — `core/ListSwipe` fed from the *dialog's* `dispatchTouchEvent`,
because a `Dialog` owns its own window: the reader's swipe behind the panel never sees a stroke of
it, and the page underneath cannot turn while the Contents is up.

**Three doors** to `BibleActivity.openContents()`: `btnContents` (beside Back, as the notebook's), the title ("Genesis 1" is the obvious
thing to tap when you want to be somewhere else; it carries the same long-press hint), and — B6 —
a **one-finger swipe down over the page**, the gesture the notebook teaches for its Contents. The
swipe rides the *same* `ListSwipe` that turns the page, through its new optional `onSwipeDown`
(`SwipeMath.vertical`, the flip's rule rotated 90° against the region's height; the two axes are
exclusive by dominance, so one drag is a turn or a call for the Contents, never both; stylus sequences
are dropped as ever). One panel at a time — a second call while one is up is a no-op (the swipe and
the button can land together) — and the activity's `onDestroy` dismisses a showing panel (a Dialog
outliving its finishing Activity is a window leak). The books list comes from
`ChapterLoader.booksNow()` — B2's `source()` already read the `book` table once for the cursor, so
opening the Contents costs **no second database query** and the panel never touches the database.
Before the first chapter has ever shown, `booksNow()` is empty and the call is a **silent no-op**.
A picked chapter is a **pick** (`goTo`, B7): it is recorded as a recent first (§ The Recents) and
then goes down the ordinary chapter-edge path (`openChapter(picked) { 0 }`), so the load latch, the
neighbour prefetch, and the position write all follow exactly as they do for any other turn — and a
pick that lands while a load is running is dropped whole, so a chapter that did not open is never
remembered. There is no BLOCK_ALL / exclusion push here, unlike the Contents: the Bible has no
paper and no ink daemon underneath.

---

## The Recents

Added by **B7** (2026-09-13, the user's decision — "recents in the Bible extension … a paginated
list like in notebook … on the right … a two-finger swipe down … a toolbar button with the clock
icon"): the notebook's Recents panel in a second subject, **the Contents' twin, mirrored to the
right**. It lists the chapters the user has **picked by name** — from the Contents (a book row's
chapter 1, or a cell of the chapter grid) or from this panel itself — newest first, and opens the
one tapped.

**What counts as a recent is a pick, never a turn.** Reading from Genesis 1 through to Genesis 9
by swipe went to Genesis once; the history answers "where did I deliberately go", not "which
chapters have I seen" — the latter would be every chapter, in order, which is the canon. So
`BibleActivity.goTo` is the one recorder: the Contents' `onPicked` and the Recents' `onPicked` both
route through it, `turnTo` (swipe, pager, chapter flow) never does. The first open (the stored
position) is not a pick either.

**The store** (§ The store): `recent(usfm, chapter, at)` with the chapter as the primary key, so a
re-pick **re-stamps** the row it already has rather than duplicating it — the notebook's "opening
is what puts it at the front" rule, on rows. `BibleStore.writeRecent` is one two-statement batch:
the upsert, then `TRIM_RECENTS` keeping the newest [`RecentChapters.KEEP`] = **30** — a history,
not an archive; the two ride one `exec` so the trim can never run against a store the upsert did
not reach. Fire-and-forget on IO from `recordRecent`; a failure is `Slog.d("recent not saved")`,
never a dialog, and the reference itself is never logged.

**`RecentChapters`** (pure, JVM-tested) is the notebook's `RecentRows` in the extension's copy:
`select` keeps **stored order** (a sort would turn the history into the canon), drops the chapter
**being read** (the notebook's "the one you are in is never offered" clause — so right after a
pick the panel shows the pick *before* it), and collapses duplicates to the first; `label` names a
row as the running head does (`Canon.chapterTitleName` — "Psalm 23", "Genesis 1"); `RecentRef.of`
is the lenient row decoder (an unknown book code or a chapter below 1 is a dropped row, never a
dialog — decision 7's rule extended to the history); `SIDEBAR_WIDTH_FRACTION` = **50 %** (narrower
than the Contents' 60 % — a row is a name and a time) and `itemsPerPage` from a **measured** row.

**`RecentsPanel`** is `RecentsDialog` reused: `dialog_recents.xml` is `dialog_contents.xml`
mirrored — panel anchored `end`, the 2 dp inkBlack rule on the **left** edge
(`shape_recents_sidebar`), header running title-then-arrow so the dismissal sits nearest the edge
the panel came from; full screen below 480 dp (`ContentsLayout.fullScreen` — the breakpoint is
decided once), the sidebar over a transparent scrim at or above. Rows (`item_recent_entry.xml`)
are two lines — the chapter at 20 sp and `<medium date>, <time>` at 13 sp, both inkBlack
(secondary text is *smaller*, never grey). One row is inflated and measured at the real panel
width after the first layout and `itemsPerPage` follows (the Nomad: 12 rows a page, 702 px); the
pager footer is `INVISIBLE` at one page; a one-finger horizontal swipe over the body flips pages
(the dialog's own `dispatchTouchEvent`). **"No recent chapters"** is shown in the body when the list
is empty — a real answer, never a reason to hide a door.

**Two doors** to `BibleActivity.openRecents()`, the notebook's own and **neither gated**:
`btnRecents` (Tabler `clock`) at the top bar's right edge — the panel comes in from that side —
and a **two-finger swipe down over the page**. The swipe rides the same `ListSwipe` that turns the
page and opens the Contents, through its new optional `onTwoFingerSwipeDown` (`:sn-screen`, B7):
a second finger landing on a one-finger drag that has **not** yet qualified starts a two-finger
sequence measured at the centroid and judged by `SwipeMath.vertical` when the sequence drops back
to one finger (or a third lands on a qualifying one); only **down** is claimed. A second finger
landing on an *already-qualifying* one-finger drag is still the late arrival it always was — the
flip (or the Contents call) commits and the rest is stood down; so, as on the notebook, land both
fingers together. The open **gathers first**: the rows are read on IO (`readRecents(KEEP)`, a
failure or an absent store an empty list), selected against the chapter being read, then shown —
one showing at a time and one gather at a time, so the button and the swipe landing together
cost one panel. `onDestroy` dismisses a showing panel, as it does the Contents.

**A tap** hands the `ChapterRef` up to `goTo`: the chapter opens at its first page and is
re-stamped at the front — it then disappears from the panel (it is the one being read) and the
chapter it was picked *from* takes its place.

---

## Bible references (arc 38)

A fresh user decision (2026-09-13, `extensions/bible/REFERENCE_PLAN.md`): a lassoed or typed
scripture reference in a notebook becomes a text object wrapped in a link, and a finger tap opens
**this same `BibleActivity`**, one screen further, on **just those verses**. Nothing here is a
tenth extension point — the ninth (`ACTION_BIBLE`) grew two compatible method tails. The seam
itself (`IBible.resolve`/`beginAt`, `ResolvedReference`, `LinkPayload.KIND_BIBLE`, `BibleRefFlow`,
the doors) is the host's half and is documented in
[`apps/notesprout_sn/docs/extensions.md`](../../../apps/notesprout_sn/docs/extensions.md) § "The
Bible point" and [`apps/notesprout_sn/docs/links.md`](../../../apps/notesprout_sn/docs/links.md);
what follows is what this reader itself grew to serve it.

### The seam tails

`IBible` gained two methods after `end()` (transaction codes 3 and 4, the calendar's `render`
precedent), both behind a **method** floor: `MIN_API_VERSION_FOR_BIBLE_REFERENCE` = 12
(`ExtensionContract.API_VERSION` moved 11 → 12 to make 12 expressible). `MIN_API_VERSION_FOR_BIBLE`
— the **action** floor that gates the point's existence at all — stays 11: a reader that only
declares 11 still serves the plain door (open the reader, read where you left off); the host offers
the notebook's two reference doors, and follows a Bible link, only against a reader declaring 12 or
above. This is deliberate and matches the calendar's `render`/`advanceOutgoing` precedent exactly —
a compatible *addition*, not a break, so an 11-only reader is never disabled by the bump, only left
without the newer doors.

- **`resolve(text: String): ResolvedReference?`** — bind-per-call, no store, ≤ 2 s budget host-side
  (`BibleClient.RESOLVE_TIMEOUT_MS` = 8 s, sized for the cold case: a freshly installed reader's
  *first* call also carries `ContentInstaller`'s 11.6 MB asset copy out of the APK). Parses [text]
  and checks it against the installed source; null means "not a reference this Bible knows" **or**
  "could not ask" — the host treats both as one answer, because either way nothing may be created
  (`BIBLE_PLAN.md`'s "a dead Bible link cannot be created" carried into arc 38). Neither the text
  nor the answer is ever logged, on either side of the seam — a count of passages and a duration
  only (`BibleService.resolve`'s own `Slog.d` line).
- **`beginAt(store, reference)`** — `begin`'s second opening: the same store-lending bracket, plus
  a resolved reference's wire form to open the screen on. `BibleSession.reference` is set alongside
  `BibleSession.store` and read **once**, in `BibleActivity.onCreate`, deciding passage mode for
  the life of that showing.

`ResolvedReference(wire, label)` (`:extension-api`) is the one value that crosses back: `wire` is
opaque to the host (only `ReferenceCodec` here reads its grammar) but its constructor `require`s
the host's whole check — ASCII, no `|` (the link payload's separator), no whitespace, ≤
`MAX_WIRE_CHARS` (512) — so a wire that could break a payload never unmarshals; `label` is the
canonical display form ("John 3:16–18; Proverbs 3:5–6"), read once into a confirmation toast and
never stored. Unmarshal is validation, the seam's whole-family rule.

### `Reference.kt` — parser, codec, resolver

Ported from Biblesprout's `data/Reference.kt` and stripped of `rawText` (decision 2 of arc 38: the
user's own words stay on the notebook page, never here) — three pure objects, JVM-tested with no
database:

- **`Passage`** — a `CanonBook` plus an ordered, non-empty list of `VerseRange`s. `format()` is the
  canonical rendering a reader would write by hand: chapter prefixes drop once a chapter is already
  established ("John 3:16–18, 22" not "John 3:16–18, John 3:22"), a whole chapter is the bare
  number ("Genesis 1", "Genesis 1–2").
- **`ReferenceParser`** — `parse(input)` splits book from spec at the first digit (so "1 Cor 13:4"
  and spaceless "Ps23" both work), resolves the book through `Canon.lookup` (case/punctuation/
  abbreviation tolerant), and reads the spec as chapter-level, cross-chapter (`"1:5-2:3"`), or
  verse-level-with-carried-chapter (`"3:14-16,18"`). `parseAll(input)` splits on `,`/`;`, lets a
  bare number continue the previous book ("John 3:16, 18"), and — the locked rule — **any one bad
  chunk empties the whole result**: a half-dead reference can never be linked, because *this* is
  where that rule is enforced, not downstream of it.
- **`ReferenceCodec`** — `encode(passages)` writes the wire grammar (below); `decode(wire)` is
  **total** (a wire this build cannot read is `null`, never a throw — a store row or a link payload
  is untrusted input); adjacent ranges of one book fold back into one `Passage` on decode, so
  `format()` can drop repeated chapter prefixes the same way the writer's own input did.
- **`ReferenceResolver.valid`** — pure over two callbacks (`chapterCount`, `verseExists`) so it is
  JVM-tested without `BibleDatabase`: every named book must be in the canon, every chapter ≤ the
  book's chapter count, and every explicitly named verse must **exist** — a whole-chapter range
  (`v == 0..MAX_VERSE`) needs only its chapters to exist, a verse range needs its two **endpoints**
  to exist (the verses between are the source's to have or not; a hole would still render what is
  there). `John 3:99` is refused; `John 3` whole is fine.

### The wire grammar

What crosses the seam in `ResolvedReference.wire`, what a Bible link's payload stores (riding the
notebookId slot — see `docs/links.md`), and what `beginAt` takes back: one `USFM:c:v-c:v` per
range, ranges joined by `,`, a whole chapter as `USFM:c:0-c:999` (`VerseKey.MAX_VERSE`). ASCII, no
whitespace, no `|`, capped at 512 chars. Examples:

| Written | Wire |
|---|---|
| `John 3:16` | `JHN:3:16-3:16` |
| `John 3:14-18, Proverbs 3:5-6` | `JHN:3:14-3:18,PRO:3:5-3:6` |
| `Genesis 1` (whole chapter) | `GEN:1:0-1:999` |

`ReferenceCodec` is the **only** reader of this grammar on either side — the host treats a stored
wire as opaque and checks nothing but `ResolvedReference.isWire`'s character set.

### The passage view

A second **mode** of the same `BibleActivity` (`docs/bible.md` § The screen, above), not a second
screen: `passage: PassagePages?` is the one flag it branches on, exactly the way `chapter:
ChapterPages?` already did — non-null-and-decodable read of `BibleSession.reference` in `onCreate`
opens the passage; a `landing` (our own Full chapter launch) or the stored position otherwise open
chapter mode as always. `PassagePages` (`PassageLoader`) carries no `ChapterRef` of its own and
writes **no position** — a passage is not a place the reader was left; the bookmark still names
whatever chapter they were last actually reading — and a turn past either end of it is a **silent
no-op**, because the chapter flow belongs to reading, not to a citation.

**Atoms** (`PassageAtoms.atomsFor`, pure, JVM-tested against `VerseRow`s, no database or font):
flattens the `verse` table's plain text, ranges concatenated in the order they were written, into
one atom stream — a `HeadingAtom(HeadingKind.MAJOR)` naming the book and chapter at every (book,
chapter) crossing, a fresh paragraph break under it, then each verse's superscript number and its
words split on spaces. A passage is **not** a chapter: the rich block layer's paragraphs, poetry
indents and section headings describe a whole chapter's typesetting, and half of it read out of
the middle would open on a hanging indent or a heading belonging to verses that are not here — so
the passage view deliberately reads the plain-text `verse` table instead, Biblesprout's own passage
shape.

**Pagination**: `PassageLoader.passage(wire, width, height)` decodes the wire, reads every range's
verses through `ChapterLoader.withSource` (one open source, one `TextPaint`, one measuring thread —
the screen never opens a second database or typography for the passage), and paginates with
`ChapterPaginator.paginate(atoms, typo, width, firstPageHeight = height − safety, otherPageHeight =
height − safety)` — **both page heights equal**, unlike a chapter's shorter first page, because a
passage usually does not open at a chapter's own beginning and has no big chapter number to make
room for. A range the source has nothing for is **skipped** (a reference can outlive a source that
omits a book); a wire that yields **no** verses at all throws, and the screen shows its own problem
dialog (`bible_passage_unavailable_body`) rather than a blank page.

**Chrome**: the notebook's own bar order, `[Back] [Contents] · <canonical label> · [Full chapter]
[Recents]` — the passage's canonical label (`ReferenceCodec.label`) stands in for "Book Chapter" as
the title, and **Full chapter** is a new end-group text button (words read better than glyphs) that
appears only in passage mode (`applyMode`, `GONE` not disabled). The title stays screen-centred
against the bar's two **measured** end groups (`balanceTitle`) — Full chapter widens the end group
by a word, so both margins are re-measured on every layout change rather than assumed.

**Full chapter** launches a **second `BibleActivity` instance, in our own process**, in chapter
mode, at the first range's book/chapter/verse — extras that never cross a process boundary (this
screen launching itself) and therefore carry no contract. This is the one launch that is **not**
the host's, which is why the caller check admits `callingPackage == packageName` **first**, before
`HostCallerCheck.enforceActivity` — load-bearing, because `enforceActivity` finishes the Activity
on a refusal, so the short-circuit is what lets our own launch through at all. The chapter instance
reads, flows and bookmarks like any ordinary reading (it is **not** a pick, and writes position
like any turn); it is *not* told about the passage that opened it.

**The back chain**: the chapter instance's Back finishes back onto the passage (an ordinary Activity
pop — Back always meant this); the passage's own Back finishes with `RESULT_OK` to the **host**, as
it always did. A passage is one hop deep off the notebook, never two.

### The Recents' second table

`BibleSchema.V3` adds `recent_ref(ref TEXT PRIMARY KEY, at INTEGER NOT NULL)` — a **second** table,
never an edit of B7's landed `recent` (the family's "a landed step is never edited" rule). A
passage opened from the notebook (or re-picked from the Recents) is a **pick**, stamped exactly as
a chapter pick is: `BibleStore.writeRecentRef` is one two-statement batch, upsert then
`TRIM_RECENT_REFS` keeping the newest `RecentChapters.KEEP` (30) — the wire is the row's key, so
re-opening the same reference re-stamps rather than duplicates.

`RecentEntry` (sealed: `Chapter(ref, at)` / `Reference(wire, passages, at)`) is what
`RecentChapters.select` now returns — the **union of both tables, merged by `at` descending and
stable** (each table is already stored newest-first, so a plain stamp-order merge is the only rule
that cannot make one subject permanently outrank the other, which is exactly the "never a canon"
rule B7's Recents was built on). The passage **currently showing** is dropped from its own list the
same way the chapter being read always was; each kind dedupes to its newest row. `RecentsPanel`
renders either kind as a name-and-time row (`RecentChapters.label`) and only the tap differs:
`onPicked(ref)` for a chapter, `onPickedPassage(wire)` for a reference — which re-opens the passage
view **in place**, whichever mode the screen was already in, and re-stamps it at the front.

### Privacy

**"Where, not what," carried into this arc without exception.** `resolve`'s `Slog.d` logs a count
of passages and a duration, never the text or the answer; `beginAt` logs the bare fact of the call;
`openPassage`'s failure path logs nothing about the wire, not even on a problem dialog; a passage's
own recording (`recordRecentReference`) fails silently exactly as `recordRecent` always did. A
reference names where the user has read — the same sentence this doc has used since B0, now true
of a citation as well as a chapter.

### Failure table (arc 38 additions)

| Situation | What the user sees | Where |
|---|---|---|
| Typed or recognized text is not a reference this Bible knows | The "Not a Bible reference" dialog, quoting the words back; its **Edit** button reopens the dialog prefilled with them | `BibleRefFlow.notAReference` (host), `BibleService.resolve` returning `null` |
| A verse past its chapter's end, or a chapter past the book's end | Same as above — `ReferenceResolver.valid` refuses before `resolve` ever returns a wire | `BibleService.resolve`, `ReferenceResolver.valid` |
| A follow of a Bible link against a reader declaring only 11 (or no reader at all) | The dead-target dialog, its own wording ("update NSE · Bible") — the link itself is untouched | `LinkFollowFlow.deadTarget` via `link_target_bible_body`, `BibleEntry.supportsReferences` |
| A decodable wire whose ranges yield no verses at all (a source that has dropped every named book) | The problem dialog (`bible_passage_unavailable_body`), never a blank page | `BibleActivity.openPassage`'s `onFailure`, `PassageLoader.passage` (`check(verses.isNotEmpty())`) |
| The store is unavailable (or was never lent) at the moment a passage would be stamped as a recent | The passage opens and reads normally; the pick is silently not recorded (`Slog.d`, never a dialog) | `BibleActivity.recordRecentReference`, `BibleStore.writeRecentRef` → `StoreUnavailable` |

---

## The store

Two tables through B7, a third since arc 38 / R2 — the calendar's `state` / the document editor's
`prefs` precedent, in three schema steps: `BibleSchema.V1` = `state(key TEXT PRIMARY KEY, value
TEXT NOT NULL)` (B0), `BibleSchema.V2` = that step untouched plus `recent(usfm TEXT NOT NULL,
chapter INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (usfm, chapter))` (B7), and
`BibleSchema.V3` = V2 untouched plus `recent_ref(ref TEXT PRIMARY KEY, at INTEGER NOT NULL)` (arc
38 / R2, § [Bible references](#bible-references-arc-38)); `BibleSchema.CURRENT` is what every call
declares, and the host runs only the steps a store has not seen. **A landed step is never edited.**
The one key `state` ever holds is `position` (`BibleSql.KEY_POSITION`); `recent` holds the picked
chapters and `recent_ref` the picked passages (§ The Recents, § Bible references). `INSERT OR
REPLACE` (`UPSERT_STATE`, `UPSERT_RECENT`, `UPSERT_RECENT_REF`) is safe on all three because none
has children for a replacement to cascade away. Every statement is pinned as exact text in
`BibleSql` and run through the host's `StoreSql` query/exec gate in `BibleSqlTest`, so a shape the
host refuses fails on the JVM, never at the seam.

**`Position`**'s wire form is deliberately boring and human-readable: `GEN:1:1` (`encode()`).
`decode` is **total** — wrong field count, a non-integer chapter or verse, an unrecognized book code,
or a chapter/verse below 1 all yield `null` rather than throwing, and `BibleActivity` falls back to
`Position.GENESIS_1` in every one of those cases (decision 7's promise: a lost or malformed bookmark
is never a dialog). `applySchema(BibleSchema.CURRENT)` runs on **every** call into `BibleStore` — read or
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
reading. The same rule covers the recents (B7): `recordRecent`'s failure line names no chapter,
and `openRecents` logs counts and a duration only. Scripture text never crosses the seam at all: `BibleService` hands the extension a store
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
| A tap on Contents before the first chapter has ever shown | Nothing — `booksNow()` is empty and the panel never opens | `BibleActivity.openContents` |
| Recents opened with no store lent, or a store that will not answer | The panel opens with "No recent chapters" — a read failure and an absent store are the same empty list, never a dialog | `BibleActivity.openRecents`, `BibleStore.readRecents` |
| A `recent` row this build cannot read (unknown book code, chapter below 1, wrong cell class) | That row is dropped; the rest show | `BibleStore.readRecents`, `RecentRef.of` |
| A pick (Contents or Recents) while a chapter is still loading | Dropped whole — not opened and **not recorded**, the turn's latch | `BibleActivity.goTo` |
| A recent tapped that cannot be opened (a hole in the source) | The same problem dialog as any other open; the row was already re-stamped | `BibleActivity.goTo` → `openChapter`'s `onFailure` |
| The store revoked mid-showing, then a pick | The chapter opens; the recent is silently not saved (`Slog.d`) | `BibleActivity.recordRecent` |
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
- **Index** (now the Contents): books page 1 → arrows paged to Psalms (page 2) → Psalms' chapter grid → 23 → Psalm 23
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

**R4** (arc 38, 2026-09-13, adb on the Nomad `.dev` builds — the lasso itself left to the user's
hand, adb cannot drive it):
- **Insert → Bible → typed** "jn 3:16-18, prov 3:5-6" resolved **in the extension in 465 ms** (the
  first call against a cold process; **1,136 ms** round trip host-side, the asset-copy cost this
  doc's `resolve` budget is sized for) → the object landed as an underlined link showing the user's
  own words, selected in LINK mode (Edit/Unlink).
- **A finger tap → `beginAt`** → the passage view showed "John 3:16–18; Proverbs 3:5–6" with a
  "John 3" heading, verses 16–18, a "Proverbs 3" heading, verses 5–6, on **one page** (316 ms).
- **Full chapter** opened John 3 on page 3/6 — the page holding verse 16 — in 581 ms.
- **Back** returned to the passage; **Recents**, opened while the passage was showing, listed 3
  chapters and dropped the passage itself ("3 of 3+1"); picking Job 1 then reopening Recents
  listed the reference row "John 3:16–18; Proverbs 3:5–6" **first**; tapping it reopened the
  passage view in place in **55 ms** (cached); Back from the passage returned to the notebook
  (`end` ran, the bind closed).
- **A refusal**: "hezekiah 3" was refused by the extension in **3 ms** → the "Not a Bible
  reference" dialog quoted the words back; its Edit button reopened the dialog prefilled.
- **One post-walk fix**: the refusal dialog's positive button had reused the string "Edit link" —
  wrong, because no link exists yet at that point. It now reads plain "Edit"
  (`bible_reference_edit_action`).
- **Left to the user's hand**: the lasso-bar conversion, Edit on an existing reference, undo/redo
  of both a create and an edit, and the eleven-button lasso bar's width on the Nomad.
- **A trap for the record**: the reference dialog centres at y≈935 px before the IME shows and
  rises to y≈587 once it is up — an adb walk must tap the field at the **pre-IME** position first.

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
- **The dialog re-centre trap** (history — the B3 dialog is gone since B6, but the rule stands for
  any centred bordered dialog). A grid page shorter than its siblings shrinks the dialog and visibly
  re-centres it under whatever finger just tapped — the B4 finding, then fixed by padding every page
  to the full grid. The side panel is immune by shape: it is anchored to the screen edge and its
  rows are uniform, so a short last page leaves white space, not a moved control.
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

**93 JVM tests** across eleven files (`src/test/kotlin/.../ext/bible/`), counted directly from the
source with `grep -c "@Test"` (46 at the arc-37 freeze; +1 the Psalm title, +5 B6's reshape after
its rewrite, +13 B7 → 65; **+11 `ReferenceTest` and +9 `PassageAtomsTest` at arc 38 / R1–R2, +2 into
`BibleSqlTest` and +6 into `RecentChaptersTest`'s rewrite for the union → 93**):

| File | Tests | Pins |
|---|---|---|
| `BibleSqlTest.kt` | 9 | Every SQL statement string verbatim (state, recents and, since R2, recent references), the `position` key literal, every statement passing the host's `StoreSql` query/exec gate, V1/V2/V3 each as the step before it untouched plus one new step, `CURRENT` = V3 |
| `ReferenceTest.kt` | 11 | Arc 38 / R1 — `ReferenceParser.parse`/`.parseAll` (book/spec split, cross-chapter spans, carried-chapter verse lists, any one bad chunk emptying the whole result), `ReferenceCodec.encode`/`.decode` round-tripping including a whole chapter's `c:0-c:999` sentinel and adjacent-range folding, `decode` total over malformed wires, `ReferenceResolver.valid` over fake `chapterCount`/`verseExists` callbacks (a chapter past the book's end, a verse that does not exist, a whole chapter needing only its chapters to exist) |
| `PassageAtomsTest.kt` | 9 | Arc 38 / R2 — a heading + paragraph break at every (book, chapter) crossing, no heading inside one run, verse numbers and words in order, words split on spaces, an empty verse list producing no atoms |
| `RecentChaptersTest.kt` | 16 | Stored order kept against a canon-order and a stamp-order trap, the chapter being read dropped (case-insensitively), a sibling chapter kept, duplicates collapsing to the newest, nothing invented, the row label in the running head's form ("Psalm 23"), the lenient row decoder dropping an unknown code or chapter 0, the 50 % width under the Contents' 60 %, `itemsPerPage` whole rows ≥ 1 and safe on an unmeasured row, `KEEP` = 30, and — arc 38 / R2's rewrite — the two-table union sorted by stamp alone (never canon order), the current passage dropped by wire, a passage row's label via `ReferenceCodec.label`, ties keeping each table's own stored order |
| `CanonTest.kt` | 6 | 66 books, ordinals 1–66 in order, the 39/27 OT/NT split, unique three-character USFM codes, case-insensitive/ordinal-addressed lookup |
| `VerseKeyTest.kt` | 5 | The packing formula, encode/decode round-trip, reading-order sort, chapter bounds covering exactly one chapter, a verse range containing only its own span |
| `PositionTest.kt` | 4 | Wire-form round-trip, Genesis 1 as the default, lowercase book codes normalizing, every malformed shape decoding to `null` |
| `ChapterCursorTest.kt` | 7 | Ordinary in-book stepping, book-to-book flow both directions, Genesis 1 having nothing before it, the last book's last chapter having nothing after it, a book the source omits being skipped in both directions, a zero count or unknown code walking nowhere |
| `ContentsModelTest.kt` | 13 | Six-wide chapter rows with a padded last row, a one-chapter book as one cell and five spacers, the collapsed list as one row per book in canon order, an expanded book followed by its rows then the next book, case-insensitive keys, several books open at once, `indexOfBook` / `indexOfChapter` (only while open; Psalm 119 on the twentieth row), `pageOf` / `pageCount` / `clampPage` arithmetic, an empty source |
| `ContentsLayoutTest.kt` | 4 | The 480 dp sidebar branch (Nomad and Manta both take it), the 60 % width rounding, a row's slot at both densities, `itemsPerPage` flooring to ≥ 1 |
| `reader/ChapterPaginatorTest.kt` | 9 | Blocks becoming headings/numbers/words with a spliced footnote caller, minor heading kinds mapping to `MINOR`, a page never ending on a bare verse number, forced progress when nothing fits, `fitCount` returning zero when even one atom overflows, a page anchoring to the verse in effect at its first word, a verse-less opening page anchoring to verse 1, `pageContaining` picking the last page at or before a verse, and a real pagination round-tripping every page's anchor back to that same page |

The host side of arc 38 (`LinkPayload`, `LinkNav`, `BibleRefFlow`'s pure edges) is tested in
`:app`, not here — `docs/links.md` and `docs/objects.md` carry those counts.

---

## Not in this arc (recorded futures, each needing a user decision)

- **Search** — no free-text or reference lookup; `Canon`'s alias/normalize table was deliberately
  left behind in the Biblesprout port rather than carried forward unused. Arc 38's reference dialog
  is not search either: it accepts only a reference the user already knows how to write, never a
  word or phrase.
- **Bookmarks** beyond the single remembered reading position and the Recents' history of picked
  chapters and, since arc 38, followed passages (nothing is pinned, the list is the newest 30
  picks of the union).
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
- ~~**A "Psalm 23" title special case**~~ — **done at the freeze, the user's call**:
  `Canon.chapterTitleName(usfm)` answers "Psalm" for `PSA` and the book's name otherwise, so the
  page heading and the running head say "Psalm 23" while the Contents still lists the book as
  "Psalms" (`book.name`, matching the source). One helper, one test.

---

## Related

- [`apps/notesprout_sn/docs/extensions.md`](../../../apps/notesprout_sn/docs/extensions.md) § "The
  Bible point (arc 37)" — the seam in full: the held bind, the second no-paper tier-2 screen, the
  store and its one sanctioned disk exception, the doors, the boundary-audit rows, and (arc 38) the
  two method-floored tails.
- [`apps/notesprout_sn/docs/links.md`](../../../apps/notesprout_sn/docs/links.md) — the Bible
  link kind (`KIND_BIBLE`), the payload grammar row, `LinkNav.Follow.Bible`, the follow path.
- [`apps/notesprout_sn/docs/objects.md`](../../../apps/notesprout_sn/docs/objects.md) — the
  selection toolbar's Bible button, the Insert bar's ninth kind, `BibleRefFlow`'s two undo actions.
- `extensions/bible/BIBLE_PLAN.md` — arc 37's plan and ledger: every locked decision, the
  architecture sketch verified against the code, and the B0–B4 phase records this doc draws every
  number and trap from.
- `extensions/bible/REFERENCE_PLAN.md` — arc 38 "Reference"'s plan and ledger: the fourteen locked
  decisions, the judgment calls, and the R1–R5 phase records § "Bible references" draws from.
- `apps/notesprout_sn/CLAUDE.md` — the module-table entry, the ninth-point summary, and the
  "bottom bars are pager-only, with one recorded exception" rule.
- `tools/bible/README.md` — the exact build command and result for `bsb.bible`.
