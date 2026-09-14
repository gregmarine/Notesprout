# Arc 40 "Verses" — the verses themselves on the page (branch `bible`, 2026-09-13)

**The user's decision (2026-09-13):** "we currently have a way to insert a Bible reference from the
extension directly into a notebook. Next, we'll add a way to insert the actual Bible text into the
page as a text object. For now, we'll only support small references. Full chapters are too big for
this idea for now. And text that is too long for a single page should not be allowed."

Arc 38 put a *reference* on the page (the user's words wrapped in a `KIND_BIBLE` link); B9 sent
one from the reader. This arc puts the **verses** on the page: an ordinary text object holding
the passage's words, still wrapped in the same Bible link. Scripture crosses the seam for the
first time — one compatible tail, one method floor, nothing on an Intent.

## The user's locked decisions (2026-09-13 wizard) — do not re-raise

1. **Three doors, all of them:** the reader's Send in passage mode offers *reference or verses*
   (chapter mode stays reference-only); the notebook's reference dialog (Insert-bar Bible and the
   lasso's Bible) gains an **"Insert the verses"** switch; and a lone selected Bible reference
   object gets a lasso-bar **Verses** action that lands the passage text beside it.
2. **What lands:** a text object holding the verses, **wrapped in the `KIND_BIBLE` link** — a
   finger tap still opens the passage view.
3. **Shape:** a bold label line (`**John 3:16–18**`), then the verses as one paragraph per chapter
   run with **plain verse numbers** (`16 For God so loved…`).
4. **Verse cap: 10 verses** total across the reference, enforced by the extension; whole chapters
   are refused outright. The host adds a **page-fit** check (measured height must fit the page).
   A refusal is an **alert with OK**, never a toast.
5. **Column:** left edge at **10 % of the page width, wrapping at the page's right edge** — the
   text model re-derives width from `x` on every load, so a true centred 80 % column cannot
   survive a reload (the user chose this over a stored wrap column). The *expand* door lands the
   verses **directly below the reference, same left edge** when they fit there, else at the same
   10 % rule from the top of the free space.
6. **Freeze = Nomad hand walk, no code review** (the arc 37–39 waiver).

## Judgment calls (stated, not asked)

- **Edit on a verses object** is the ordinary `TextEditDialog`, not the reference dialog: the
  words are scripture, not a reference to resolve. The link's payload kind tells the two apart:
  `LinkPayload.KIND_BIBLE_TEXT` (4) — same wire in the notebookId slot, same follow, different
  Edit. Paper/og read it as unusable like kind 3.
- **The wire is what crosses**, never a chapter/verse tuple: `IBible.passageText(wire)` returns
  `PassageText(status, text)`; `STATUS_OK` carries the Markdown, `STATUS_TOO_LONG` (over the cap
  or a whole chapter) carries nothing, and null is "could not read". `MAX_TEXT_CHARS` = 4 000 —
  ten verses of the BSB never approach it; a reply over it never unmarshals.
- **Method floor** `MIN_API_VERSION_FOR_BIBLE_TEXT` = 15 (`API_VERSION` 14 → 15); transaction
  code 6; only `:ext-bible` redeclares. `MIN_API_VERSION_FOR_BIBLE` stays 11.
- **The reader's Send chooser** is a two-button dialog in passage mode (Reference / Verses); the
  verses choice returns `RESULT_BIBLE_SEND_TEXT` (2) and the host calls
  `takeOutgoingReference()` then `passageText(wire)` on the held bind, before `end()`.
- **One undo step** (`Action.BibleRefCreated`, the same triple: link + text + no ink) — the
  verses object *is* a Bible reference object with different words.
- **Never logged**: neither the wire nor a character of the text; counts and durations only.

## Phases

| Phase | Model | What |
|---|---|---|
| **V1** seam | Fable | `IBible.passageText`, `PassageText` Parcelable, floor 15, manifest 15; extension `PassageText.kt` (pure Markdown builder + cap, JVM-tested); `BibleService.passageText` |
| **V2** reader door | Fable | Send chooser in passage mode, `RESULT_BIBLE_SEND_TEXT`, `BibleClient.passageText`, `BibleEntry` drains both |
| **V3** host doors | Fable | `KIND_BIBLE_TEXT`, `BibleRefFlow.insertVerses` (page-fit, 10 % column, alerts), dialog switch, lasso **Verses** for a lone Bible link, Edit routing |
| **V4** walk | Sonnet + the user's hand | `.dev` builds on the Nomad: Send verses, dialog switch, expand, the too-long and whole-chapter refusals, follow, undo |
| **V5** docs | Sonnet | `bible.md`, `extensions.md` (ledger, module row, audit row 60), `objects.md`, `links.md`, both `CLAUDE.md`, this ledger |

## Ledger

- **V1 seam + V2 reader door + V3 host doors — ✅ 2026-09-13 `99234f5f`.** `IBible.passageText`
  (code 6), `PassageText(status, text)` Parcelable (`STATUS_OK` / `STATUS_TOO_LONG`,
  `MAX_TEXT_CHARS` 4000), `API_VERSION` 14 → 15 as `MIN_API_VERSION_FOR_BIBLE_TEXT`, `:ext-bible`
  declares 15, `RESULT_BIBLE_SEND_TEXT` = 2. Extension: `PassageMarkdown` (`withinCap` — no
  whole-chapter range, no cross-chapter range, ≤ 10 verses as named; `build` — bold label line,
  one paragraph per chapter run, a bold "Book chapter" line at every later crossing; 8 tests →
  `:ext-bible` 120), `BibleService.passageText` (opens the source per call; status/count/duration
  logged, never the text), the reader's Send in passage mode = an `ActionSheetDialog` "Send to
  notebook · The reference / The verses" (`leaveWith(reference, code)`). Host: `BibleClient
  .passageText` on the held bind + the bind-per-call twin (`TEXT_TIMEOUT_MS` = the resolve budget),
  `BibleEntry.supportsText` / `passageText` / `onSentText` (drains take-then-text before `end()`;
  a null read after the reader closed still reaches the notebook so it can alert),
  `LinkPayload.KIND_BIBLE_TEXT` = 4 (`isBibleText`; `referenceOf` answers both kinds; `LinkNav`
  follows both), `BibleRefDialog.show(activity, initial, offerVerses) { typed, verses -> }` with
  the "Insert the verses" pill (`Widget.Notesprout.Toggle`'s items set in code), `BibleRefFlow`:
  `readVerses` ("Reading verses…" `RecognizingOverlay`, the two alerts), `landVerses` (the one
  landing: `VersePlacement`, `KIND_BIBLE_TEXT`, `armLassoForLanding`, `Action.BibleRefCreated`,
  "Placed …" toast), `insertVersesSent`, `expand`, `editVerses` (`TextEditDialog`, blank Save =
  Cancel, payload untouched, `applyWords` split out of `applyEdit`), `SelectionToolbar`'s
  `versesButton` (`ic_quote`, `SelectionMode.LINK` + `isVersesAvailable`),
  `NotebookActivity.editLinkTarget` routes kind 4 to `editVerses`. `VersePlacement` (pure, 7
  tests → `:app` 1700): `leftEdge` = 10 %, `fits`, `nearY` (outward scan in 16 dp steps, 8 dp gap),
  `below` (anchor bottom + 2 gaps, else `nearY`), `without` (a conversion's ink cleared from the
  occupied set).
- **V4 the Nomad walk (adb, `.dev` builds) — ✅ 2026-09-13.** Passed: the reader's chooser and
  "The verses" (413 chars in 27 ms; landed selected, bold label, numbered verses, 10 % edge);
  Edit on the verses object = the Text dialog on the Markdown; a finger tap follows to the
  passage view; the reference dialog's pill (renders under the field, caption tap toggles);
  "ps 23" with the pill on → **"Too long for a page"** alert; "ps 23:1-3" with the pill on →
  landed below the ink at the nearest clear band, survived a page flip unchanged; "The
  reference" Send still lands a plain reference, now with the **Verses** button in its bar;
  Verses on it → the passage placed. **Two findings, both fixed:** (1) a successor selection
  injected at an insert-landed selection's dismissal was stranded under PEN — the
  transfer-paste latch fired in `onSelectionDismissed` after the successor was selected; now
  the latch is *kept* (not cleared) when a successor is injected and fires at the successor's
  own dismissal; (2) a page with no clear band **stacked** the verses on the earlier block
  (FreePlacement's centre fallback) — `VersePlacement.nearY` now answers null when nothing is
  clear, and the "No room on this page" alert is the answer (a block of verses over what is
  there is unreadable twice over). Left to the user's hand: the lasso convert with the pill, a
  Save from the verses' Edit, undo/redo, the move by pen, and the look of the column's right
  edge (the text runs to the page edge — a right margin needs a stored wrap column, declined).
