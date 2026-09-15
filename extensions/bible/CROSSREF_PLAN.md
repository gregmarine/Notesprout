# Arc 41 "Cross references" — tappable references and footnotes (branch `crossref`, 2026-09-14)

**The user's decision (2026-09-14):** "In the Bible extension, let's implement cross references.
There are many cross references throughout the Berean Standard Bible. Biblesprout implemented
this. There was a bug in Biblesprout with one of the cross references … In 1 Peter 3, there is a
cross reference to Song of Solomon. However, the link just goes to 1 Peter 1. In light of that,
make sure all cross references link to the correct passages."

The BSB carries 3,278 cross references, all as `\ref Display|TARGET\ref*` pairs (no `\x` notes):
2,028 in the italic parallel-passage `r` lines under section headings and 1,250 inside footnote
bodies ("Cited in Matthew 19:4"). The slim build shipped them in `xref` since B0, unread. This
arc makes both tappable — and fixes the data first.

## The bug, root-caused

The publisher's USFM emits Song of Solomon targets with **no book code** —
`\ref Song of Solomon 1:1–17|1:1-17\ref*` (1PE.usfm:80, EPH.usfm:87). `_resolve_target` in
`tools/bible/build_bible_db.py` treated a code-less target as "the source book", so the row
pointed at 1 Peter 1:1–17 (`60001001–60001017`); Ephesians 5's twin pointed at Ephesians 1.
Biblesprout's reader trusted the stored integers. Auditing every row found three defect classes:

| Class | Rows | Correct result |
|---|---|---|
| Code-less target whose display names a canon book (Song of Solomon ×2) | 2 | Resolve the book from the display text (`SNG`) |
| Code-less target naming a non-canon book (Jasher ×3, 1 Enoch ×2, 1 Esdras ×2 — all in footnotes) | 7 | **No xref row**; plain text |
| Single-chapter books (`JUD 17-23`, `OBA 1-14`, `PHM 1-3`, `2JN 12-13`, `3JN 13-14`) read as whole chapters | 15 | Verses of chapter 1 |

Biblesprout's own builder copy has the same defects — not touched from here.

## The user's locked decisions (2026-09-14 wizard) — do not re-raise

1. **Scope: parallel-passage lines AND footnote popups** — both xref sources reachable.
2. **A tap opens the passage view** (the cited verses, Full chapter available); **Back returns
   to the chapter being read**.
3. **Finger only** — a stylus tap does nothing (the page swipe's rule).
4. **Underlined** references; footnote callers stay a superscript `*`.
5. **Footnote = bordered popup near the caller**; its references underlined and tappable; a tap
   outside dismisses.
6. **Popup shows label + text**: "1 Peter 3:8" heading (book + the note's `label`), then the body.
7. **Following a reference is a Recents pick** (stamped in `recent_ref`).
8. **Branch `crossref`** from `main`, for this arc and the related follow-ups.
9. **Freeze = Nomad hand walk, no code review** (the arc 37–40 waiver).
10. **(Amendment after the first hand walk, 2026-09-14) A one-finger swipe up goes back** after
    a cross-reference link — the notebook's walk-back gesture. Judgment call: it pops every
    instance this process launched itself (a reference's passage, a Full chapter opened from one);
    the host's own instance is the trail's origin and the swipe is silent there. **Widened the
    same day (the user's call): "any link that lands us in the Bible, that swipe takes us back"**
    — the host's instance also leaves on a swipe up when a link opened it (a notebook Bible link,
    the editor's Lookup); only the plain bar-button door stays silent.

## Judgment calls (stated, not asked)

- **"One screen further" = a second in-process `BibleActivity`** in passage mode — the Full-chapter
  pattern in reverse (`EXTRA_PASSAGE_WIRE`, an extra that never crosses a process). Back pops to
  the chapter at its page; both Send codes relay up as `fullChapter`'s do. Nesting is unbounded.
- **The tap lives in `ListSwipe`** (`:sn-screen`): optional `onTap(x, y)` on the UP of a
  one-finger, finger, in-region sequence that never qualified and moved under slop. One detector
  owns the sequence; a swipe and a tap can never both fire. A tap on nothing is a no-op.
- **No taps in passage mode** — it flows the plain `verse` table (no `r` lines, no callers).
- **Hit test = the drawn `StaticLayout`'s offset under the finger**; a reference hits on
  `start until end`, a one-char `*` caller on `start..end` inclusive.
- **Marks are collected only for the drawn layout**, never on the measuring path.
- **Keys → wire** through `ReferenceCodec.encode` over one `Passage`; a `\ref` target is one book.
- **Builder self-check**: every target endpoint must exist in `verse` (a sentinel needs its
  chapter) and a parseable display book must agree with the target's — else the build fails.
- **Popup**: anchored below the caller's line, clamped to the reading band, flipped above when
  there is no room; `Theme_Notesprout` dialog, scrim-less transparent root, 1 dp inkBlack border,
  no animation; a reference tap dismisses then opens the passage instance.
- **Privacy**: a tap logs the mark kind and a duration, never the text or the target.

## Phases

| Phase | What |
|---|---|
| **X0** branch + plan | `crossref` from `main`; this file |
| **X1** data | Builder fix + self-check + `unittest`s; asset rebuilt `--slim`; README numbers |
| **X2** reader model | `Xref` row + `xrefsForChapter`, `XrefLink` on `HeadingAtom`, `atomsForBlocks(…, xrefs)`, `ChapterPages` footnote/link maps, `ReaderTypography.build` → text + marks with underlines, `ReaderPage.marks`, `ReaderView.markAt` |
| **X3** doors | `ListSwipe.onTap`; `BibleActivity` tap → passage instance / `FootnotePopup`; passage landing from intent; Send relay |
| **X4** walk | `.dev` builds on the Nomad (adb, then the user's hand) |
| **X5** docs | `docs/bible.md`, `apps/notesprout_sn/CLAUDE.md`, root `CLAUDE.md`, `tools/bible/README.md`, this ledger |

## Ledger

- **X0 — ✅ 2026-09-14.** Branch `crossref` from `main` (`1c35bef8`); this plan.
- **X1 data — ✅ 2026-09-14.** `_resolve_target(target, disp, cur_ordinal)`: the display
  text's book (`display_book`, longest canon name first, "Song of Songs"/"Psalm" aliases) when the
  target has no code; a non-canon display is a warning and no row; `ONE_CHAPTER` books read bare
  numbers as verses of chapter 1 when the display carries a colon. `_check_xrefs` after the insert
  (endpoint existence, display/target agreement, one book per range) exits the build on any
  failure. 11 `unittest`s. Asset rebuilt: 3,271 rows (7 non-canon dropped), 1 Peter 3 and
  Ephesians 5 → `22001001–22001017`, 14 one-chapter rows all on verses, zero dangling endpoints,
  `integrity_check` ok, 15,265,792 bytes (unchanged — the install stamp's `lastUpdateTime` half
  is what detects it).
- **X2 reader model — ✅ 2026-09-14.** `Xref` row + `BibleDatabase.xrefsForChapter` (Biblesprout's
  UNION ALL over both source kinds); `XrefLink` on `HeadingAtom.links` (default empty — the
  existing atom equality tests untouched); `atomsForBlocks(blocks, footnotes, xrefs)` attaches a
  block's links to its `r` heading and ignores note-sourced rows; `ReaderTypography.build(atoms,
  collectMarks)` → `Built(text, marks)`, `UnderlineSpan` over every link, `PageMark.Reference` /
  `.Caller` recorded only by `bodyPage` (the drawn layout) — `measure` never collects;
  `PageMarks.at` (pure: a reference hits `start until end`, a one-glyph caller `start..end`);
  `ReaderPage.marks`, `ReaderView.markAt(x, y)` (line under the finger, offset on it, an 8 dp
  slop past a line's ends) + `lineBounds(mark)` for the popup anchor; `ChapterPages` carries
  `footnotesById` + `noteLinks`. `ListSwipe.onTap` (`:sn-screen`): the UP of a one-finger,
  finger, in-region sequence that never qualified and never left the touch slop, in the
  region's coordinates. `XrefWire.of(startKey, endKey)`. Tests: `PageMarksTest` 3,
  `XrefWireTest` 4, one paginator link test → `:ext-bible` 133; `:sn-screen` 101 (ListSwipe
  itself is `MotionEvent`-bound and untested on the JVM, as before).
- **X3 doors — ✅ 2026-09-14.** `BibleActivity.onPageTap` off `ListSwipe.onTap` (chapter mode
  only; a tap on nothing is a no-op): a `PageMark.Reference` → `openPassageOver(XrefWire.of(…))`
  — a **second in-process instance** launched with `EXTRA_PASSAGE_WIRE` (decoded or ignored; wins
  over `BibleSession.reference`), opened via `openPassage(wire, stamp = true)`, both Send codes
  relayed up as Full chapter's are; a `PageMark.Caller` → `FootnotePopup` (new file): heading
  `"<book> <label>"` (`bible_chapter_title_text`; the verse, then the chapter, when a note has no
  label), Noto Serif 20 sp, the note's own links as black underlined `ClickableSpan`s →
  `openPassageOver`; placed under the caller's line (over it when there is no room), centred on
  the line, clamped 16 dp inside the window, 340 dp or what the window leaves, `INVISIBLE` until
  placed; the full transparent window dismisses; one at a time; `onDestroy` dismisses it.
- **X4 walk (adb, `.dev` Bible on the Nomad; the host untouched) — ✅ 2026-09-14.** John 3: the
  reference line underlined; "Romans 5:6–11" → passage (117 ms), Back → John 3 p1; verse 3's `*`
  → popup "John 3:3 · Or born from above; also in verse 7." under its line; tap outside dismissed.
  **1 Peter 3 → "Song of Solomon 1:1–17" → Song of Solomon 1 (3 pages) — the bug, fixed**; Back →
  1 Peter 3 p1; Recents listed both passages newest first; plain-text tap = nothing; swipe still
  turned. Genesis 1:3's `*` → "Genesis 1:3 · Cited in 2 Corinthians 4:6", the reference underlined
  → passage "2 Corinthians 4:6" → Send → The reference → both instances closed, the notebook
  received 17 chars. Trap for adb: the Send sheet's row bounds must be tapped at their centre
  (735, 935 on the Nomad) — a stale dump right after the sheet opens misses. **Left to the user's
  hand:** tap-vs-swipe feel, pen tap inert, popup placement/legibility, the underline at 0.8×.
- **X5 docs — ✅ 2026-09-14.** `bible.md` § Cross references (+ data § amended, span table, tests
  table → 133, futures struck, Related), `apps/notesprout_sn/CLAUDE.md` arc 41 entry, root
  `CLAUDE.md` (module line + docs table), `tools/bible/README.md`, this ledger. **Awaiting the
  user's hand walk on the Nomad for the freeze.**
- **Hand walk 1 — 2026-09-14: "The links work well. Going back works well too. The footnotes are
  working."** One ask: a single-finger swipe up to go back after a cross-reference link, as the
  notebook does along its trail → decision 10; `BibleActivity.walkBack()` off `ListSwipe.onSwipeUp`
  (`ownLaunch` = a Full-chapter landing or an `EXTRA_PASSAGE_WIRE` launch; silent on the root
  instance and while loading).
- **Hand walk 1, second ask — 2026-09-14:** the swipe up also returns from a link-opened reader
  (`openingReference != null` → `leave()`); the plain door stays silent.
- **FREEZE — ✅ 2026-09-14, the user's own hand walk on the Nomad ("This feels fantastic! My heart
  is happy!"). Arc 41 COMPLETE + FROZEN.** No code review (the user's call, the arc 37–40 waiver).
  Branch `crossref` stayed open for arc 42 "Notes" (`NOTES_PLAN.md`) and **merged to `main`
  2026-09-15 (`--no-ff`), then was deleted.** No next Bible phase (bookmarks, red letters, text size, longer or
  multi-page passages) without a fresh user decision.
