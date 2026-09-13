# Arc 38 "Reference" — Bible reference objects in the notebook (plan + ledger)

**Branch `bible`, 2026-09-13 — a fresh user decision.** A lassoed handwritten reference ("John
3:16", "John 3:14-18, Proverbs 3:5-6") becomes a text object wrapped in a link whose target is a
passage of the Bible; a finger tap opens NSE · Bible on **just those verses** with a **Full
chapter** door; Back returns to the notebook. Nothing here is a tenth extension point: the Bible
point grows two compatible tails.

Read first: `extensions/bible/docs/bible.md`, `apps/notesprout_sn/docs/links.md`,
`apps/notesprout_sn/docs/objects.md` § Text objects, `apps/notesprout_sn/docs/extensions.md`
§ "The Bible point". The rules of `apps/notesprout_sn/CLAUDE.md` bind throughout.

---

## The user's decisions (2026-09-13 wizard) — do not re-raise

1. **Any combination of references in one link**, across books: `John 1:1, John 3:16-17`,
   `John 3:14-18, Proverbs 3:5-6`. The passage view shows only those verses, in the order written.
2. **The page shows the user's own words** — whatever they left in the edit dialog. The link
   resolves to the canonical passage underneath; the confirmation toast names what it matched.
3. **A reference row in Recents opens the passage view**, not the chapter. Rows are the canonical
   display form ("John 3:16–18; Proverbs 3:5–6").
4. **Two doors in the notebook**: the lasso bar's Bible button (ink-only selections, gated on the
   extension, beside Pad / Calendar / Tag) **and** an Insert-bar Bible button that opens the
   reference dialog empty and places a typed reference at the page centre.
5. **Freeze = Nomad walk, no code review.** The lasso, the hand feel and multi-finger gestures are
   left to the user's hand; JVM tests for every pure piece.

## Judgment calls (stated, not asked)

- The conversion is: recognize (one line, `HeadingConvert`) → **dialog prefilled** with the
  recognized text (the heading dialog's shape) → Save → the host asks the extension to
  **resolve** → valid: a text object in the ink's place, wrapped in a Bible link, **one undo
  step**; invalid: a problem dialog ("…is not a reference this Bible knows") whose positive button
  reopens the dialog with the text kept; Cancel leaves the ink exactly as it was. **A dead Bible
  link cannot be created** — nothing exists until the reference resolves.
- **The parser and the bounds check live in the extension**, never the host: only the `.bible`
  knows how many verses Psalm 117 has. Biblesprout's `ReferenceParser` + `Canon` alias table are
  ported (`parseAll` — commas/semicolons, a bare number continuing the previous book).
- **Validity** = every book named is in the canon, every chapter named ≤ the book's chapter
  count, and every verse named explicitly exists (`John 3:99` is refused; `John 3` whole is fine).
- **Wire form of a resolved reference** (crosses the seam, stored in the link payload):
  ranges `USFM:c:v-c:v` joined by `,` — e.g. `JHN:3:14-3:18,PRO:3:5-3:6`; a whole chapter is
  `JHN:3:0-3:999` (`VerseKey.MAX_VERSE`). No `|` (the link payload's separator), ASCII only,
  capped at 512 chars. The host treats it as opaque; the extension's `ReferenceCodec` is the
  only reader. `ResolvedReference(wire, label)` is the Parcelable reply.
- **Link payload**: a fourth kind on the existing grammar, `L1|<chrome>|3|<wire>|` — the
  reference rides the notebookId slot, pageId empty. Everything a link already does (render,
  underline, move, delete, copy, undo, unlink) is reused. Paper / og decode kind 3 as unusable
  → dead link there, accepted.
- **Edit** on a lone Bible link opens the reference dialog prefilled with the wrapped text
  object's content (never the page picker); Save re-resolves and rewrites both the text and the
  payload in one undo step. **Unlink** leaves the text object. The picker never sees kind 3.
- **The seam**: `IBible` gains `resolve(text)` (bind-per-call, no store, ≤ 2 s) and
  `beginAt(store, wire)` (the held-bind showing, opened on the passage view). `API_VERSION`
  11 → 12, **method floor** `MIN_API_VERSION_FOR_BIBLE_REFERENCE` = 12; the action floor stays
  11 so an 11 reader still serves the plain door. The host offers the two notebook doors only
  when the discovered reader declares ≥ 12; a follow of a Bible link against an 11 reader is the
  dead-target dialog with its own wording ("update NSE · Bible").
- **Passage view** = `BibleActivity` in **passage mode** (`BibleSession.reference` set by
  `beginAt`): top bar `[Back] · <canonical label> · [Full chapter]` (a text button — words read
  better than glyphs), the reader band, the pager. Atoms: a MAJOR heading naming the book +
  chapter at every chapter crossing (Biblesprout's shape), then paragraph flow with verse
  numbers, from the `verse` table's plain text. Paginated by `ChapterPaginator.paginate` with
  equal first/other heights; no position write in passage mode.
- **Full chapter** launches a **second `BibleActivity` in-process** (chapter mode, extras
  `usfm/chapter/verse` of the first range — in-process extras are not the seam) with an
  `ActivityResultLauncher`; the caller check admits **our own package** via
  `startActivityForResult` before falling through to `HostCallerCheck.enforceActivity`. Back
  there finishes to the passage; Back on the passage finishes to the notebook (`RESULT_OK`).
  Chapter mode from Full chapter writes the position like any reading; it is **not** a pick.
- **Recents**: a passage opened from the notebook is a **pick** and is stamped. Store step
  `BibleSchema.V3` adds `recent_ref(ref TEXT PRIMARY KEY, at INTEGER NOT NULL)` — a second
  table, never an edit of the landed `recent` step; read = the union of both, newest first,
  `KEEP` 30 of the union, each table trimmed to 30. The passage being shown is dropped (the
  notebook rule); a tap on a reference row opens the passage view in place.
- **Cold-launch restore** reopens the plain reader, not the passage (the surface stack carries
  no reference).
- No colour, no new Gradle deps, `Slog` only, reference text never logged ("where, not what":
  counts and durations only; a reference names where the user has read).

---

## Phases

| Phase | Owner | Scope |
|---|---|---|
| **R1** seam + parser | Fable | `IBible.aidl` + `ResolvedReference`; `API_VERSION` 12 + method floor; `Reference.kt` (`Passage`, `ReferenceParser`, `ReferenceCodec`, `Canon` aliases + `lookup`); `BibleDatabase.versesForRange` + `verseExists` / `chapterCount`; `BibleService.resolve` / `beginAt`; `BibleSession.reference`; manifest 12; JVM tests |
| **R2** passage view + Full chapter + Recents rows | Opus | `PassageLoader`, passage mode in `BibleActivity`, the in-process chapter launch, `BibleSchema.V3` + `recent_ref`, `RecentEntry` union in `RecentChapters` / `RecentsPanel`, strings, tests |
| **R3** host: object + doors + follow | Opus | `LinkPayload.KIND_BIBLE`, `BibleRefFlow` (convert / insert / edit, the dialog, the resolve call, one-step undo actions), lasso-bar + Insert-bar buttons, `LinkNav.Follow.Bible` + `LinkFollowFlow` → `BibleEntry.open(reference)`, `BibleClient.beginAt` / `resolve`, gating on ≥ 12, tests |
| **R4** Nomad walk | Sonnet + the user's hand | adb: build/install `.dev` host + `.dev` Bible, resolve round-trips, follow, Full chapter, back chain, Recents; hand: lasso, dialog typing |
| **R5** docs + freeze | Sonnet | `docs/bible.md`, `docs/links.md`, `docs/objects.md`, `docs/extensions.md`, both `CLAUDE.md`, this ledger, memory |

## Ledger

- **R1 seam + parser — ✅ 2026-09-13.** `IBible` grew `resolve(String): ResolvedReference?` and `beginAt(store, reference)` after `end()` (codes 3/4); `ResolvedReference(wire, label)` Parcelable in `:extension-api` (constructor `require`s = the host's whole check of the wire: `[A-Z0-9:,-]`, ≤ 512); `API_VERSION` 11 → 12 with the METHOD floor `MIN_API_VERSION_FOR_BIBLE_REFERENCE` = 12 (`MIN_API_VERSIONS` untouched); `:ext-bible` manifest declares 12. Extension: `Canon` got Biblesprout's alias table + `lookup`/`normalize` back; `Reference.kt` = `Passage` (+ `format`), `ReferenceParser` (`parse` / `parseAll` — any bad part refuses the whole line), `ReferenceCodec` (`encode` / total `decode` folding one book's adjacent ranges / `label`), `ReferenceResolver.valid` (pure over `chapterCount` + `verseExists`); `BibleDatabase` is `Closeable` and gained `versesForRange` (the `verse` table) + `verseExists`; `BibleService.resolve` opens the source per call (`use`), logs a count and a duration only; `beginAt` requires a decodable wire and parks it in `BibleSession.reference`. Tests: `ReferenceTest` (12) — `:ext-bible` 77, `:extension-api` green.

- **R2 passage view + Full chapter + Recents rows — ✅ 2026-09-13.** `BibleActivity` is one screen
  in **two modes**, `passage: PassagePages?` the only flag: `BibleSession.reference` is read once
  in `onCreate`, and non-null-and-decodable opens the passage — the reference's canonical label as
  the title, the reader band, the pager, a **silent no-op** past either end (no chapter flow in a
  citation) and **no position write**. Contents and Recents keep both doors in both modes (the
  Contents highlights `passage.openAt`); a chapter pick from either switches the screen to chapter
  mode **in place**, done in `openChapter`'s success so a failed open leaves the screen as it was.
  `PassageLoader` (+ the pure `PassageAtoms`) builds the pages through the new
  `ChapterLoader.withSource` — one open source, one `TextPaint`, one thread measuring it — reading
  the `verse` table's plain text (a MAJOR heading + one paragraph per chapter run, a number and
  words per verse) and paginating with equal first/other heights; a range the source has nothing
  for is skipped, a wire that yields nothing is the problem dialog
  (`bible_passage_unavailable_body`). **Full chapter** is a text button
  (`Widget.Notesprout.OutlinedButton`, `minHeight` = the button dimen) in a new end group with
  `btnRecents`, which keeps the right edge because its panel comes in from there; the title's
  margins are re-balanced against the two groups' **measured** widths
  (`BibleActivity.balanceTitle`) so the screen-centre holds in both modes. It launches a **second
  in-process `BibleActivity`** (`ActivityResultLauncher`, extras `USFM`/`CHAPTER`/`VERSE`) — the
  caller check admits `callingPackage == packageName` FIRST, before `HostCallerCheck.enforceActivity`
  (which finishes the Activity when it refuses, so the short-circuit is load-bearing); that
  instance ignores the session reference, opens on `pageContaining(verse)`, writes position like
  any reading, and is **not** a pick. Store: `BibleSchema.V3` = V2's steps untouched + `recent_ref
  (ref TEXT PRIMARY KEY, at INTEGER NOT NULL)`; `BibleSql.SELECT_RECENT_REFS` /
  `UPSERT_RECENT_REF` / `TRIM_RECENT_REFS`; `BibleStore.readRecentRefs` (lenient — an undecodable
  wire is dropped, never thrown) / `writeRecentRef` (upsert + trim in one batch). `RecentEntry`
  (`Chapter` · `Reference`) is the union `RecentChapters.select` now merges **by `at`, descending
  and stable** — two histories, each already stored newest-first — dropping the current chapter or
  the current wire, deduping each kind, capped at `KEEP`; `RecentsPanel` renders either and a
  reference row calls `onPickedPassage(wire)`, which re-opens the passage in place and re-stamps
  it. A passage opened from the host is a pick, stamped fire-and-forget on IO when it first shows.
  Strings: `bible_full_chapter`, `bible_passage_unavailable_body`, and "Recent chapters"/"No recent
  chapters" → "Recents"/"No recents". Nothing logs a wire or a label — page counts and durations
  only. Tests: `PassageAtomsTest` (9), `BibleSqlTest` +2, `RecentChaptersTest` rewritten for the
  union (11 → 16) — `:ext-bible` 77 → **93**, `assembleDebug` green.

- **R3 host: object + doors + follow — ✅ 2026-09-13.** The host's whole half, landed the same
  commit as R2. `LinkPayload.KIND_BIBLE` = 3 (`notebook/LinkPayload.kt`): the wire rides the
  `notebookId` slot, checked by `ResolvedReference.isWire` rather than the id rules; `decode()`
  reports `Decoded.notebookId = null` and a new `Decoded.reference` field instead, so
  `NotebookRemap`/`ObjectClip`/`PageClip` can never mistake a reference for a notebook id (each
  checked — none needed a change). `BibleRefFlow` (`notebook/BibleRefFlow.kt`) is the three doors
  in one place, kept out of `NotebookActivity` the way `TextFlow`/`LinkPickFlow` are: `convert`
  (the lasso bar's Bible — `HeadingConvert.run(multiLine = false)`, then the dialog prefilled),
  `insertAtCentre` (the Insert bar's Bible — the dialog empty), `edit` (a lone Bible link, checked
  `link.texts.singleOrNull()` with every other wrapped list empty before trusting it). All three
  share one `dialog()` → `host.resolve(typed)` → success wraps, failure raises the "Not a Bible
  reference" problem dialog whose positive button reopens the dialog prefilled (judgment call: the
  fix is almost always one character). `BibleRefDialog` is `HeadingEditDialog`'s one-field shape,
  with the one deliberate difference that **a blank Save is a Cancel here** (a Bible link's text
  *is* its reference; blank means nothing honest to keep, never "delete"). One undo step per act —
  `NotebookUndo.Action.BibleRefCreated`/`.BibleRefEdited`, composing what would otherwise be a
  `TextCreated` + `LinkCreated`/`LinkEdited` pair — because undoing them separately would strand a
  plain text object that used to be a link. `LinkRenderer.invalidate(id)` is the one new escape
  hatch from the link-composite cache's "same padded size ⇒ same picture" rule, needed because an
  edited reference's re-measured box very often does not change size while its words do.
  `LinkStore.updateBounds` is the matching write — the only rewrite of a link's own box that is
  not a move. `LinkNav.Follow.Bible(reference)` and `LinkFollowFlow`'s handler for it neither push
  the trail nor seal the notebook (the reader returns by result onto this same page); refused
  (`BibleEntry.openBible` answering `false`) is the dead-target dialog with its own wording
  (`link_target_bible_body`). Both doors — the lasso bar's Bible (`SelectionToolbar.onBible`,
  `STROKES`-only) and the Insert bar's ninth kind (`InsertBar.Kind.BIBLE`, appended, never
  inserted mid-row) — are gated on `BibleEntry.supportsReferences`, the **method** floor, not
  merely "a reader is installed." `BibleClient` grew a bind-per-call `Companion.resolve` and an
  `open(reference)` overload; `BibleEntry` grew `supportsReferences`/`resolve`/`open(reference)`.
  Tests: `LinkPayloadTest` (19 total, incl. the new kind's round-trip and the Paper/og dead-kind
  fixture), `LinkNavTest` (12, incl. `Follow.Bible`), `LinkStoreTest` (18, incl. `updateBounds`),
  `NotebookRemapTest`/`ObjectClipTest`/`PageClipTest` + one Bible-payload pass-through fixture
  each — `:app` 1667 → **1679** tests.
  R2 + R3 landed as one commit (`1ec24961`), `:ext-bible` 93 / `:app` 1679.

- **R4 the Nomad walk — ✅ 2026-09-13** (adb-driven `.dev` builds; the lasso conversion and hand
  feel left to the user's own hand, since adb cannot drive a lasso). Insert → Bible → typed "jn
  3:16-18, prov 3:5-6" → resolved in the extension in 465 ms (first call, cold process; 1,136 ms
  round trip host-side) → landed as an underlined link showing the user's own words, selected in
  LINK mode. A finger tap → `beginAt` → the passage view showed "John 3:16–18; Proverbs 3:5–6"
  with a "John 3" heading, verses 16–18, a "Proverbs 3" heading, verses 5–6, on one page (316 ms);
  Full chapter opened John 3 on page 3/6, the page holding verse 16 (581 ms); Back returned to the
  passage; Recents (while the passage was showing) listed 3 chapters and dropped the passage
  itself ("3 of 3+1"); picking Job 1 then reopening Recents listed the reference row first;
  tapping it reopened the passage view in place (55 ms, cached); Back from the passage returned to
  the notebook (`end` ran, the bind closed). "hezekiah 3" was refused in 3 ms → the "Not a Bible
  reference" dialog quoted the words back; its Edit button reopened the dialog prefilled. **One
  post-walk fix**: that button's label had reused "Edit link" — wrong, since no link exists yet at
  that point; it now reads plain "Edit" (`bible_reference_edit_action`). **Left to the user's
  hand**: the lasso-bar conversion, Edit on an existing reference, undo/redo of both a create and
  an edit, and the eleven-button lasso bar's width on the Nomad. **Trap recorded**: the reference
  dialog centres at y≈935 px before the IME shows and rises to y≈587 once it is up — an adb walk
  must tap the field at the pre-IME position first.

- **R5 docs — ✅ 2026-09-13.** `extensions/bible/docs/bible.md` grew a major "Bible references
  (arc 38)" section (the seam tails, `Reference.kt`, the wire grammar, the passage view, the
  Recents' second table, privacy, a failure-table addendum) plus updates to the intro, the
  decisions list, the store section (`BibleSchema.V3`), the tests table (93 tests, 11 files) and
  "Not in this arc"; the R4 walk numbers were added to "What the walks proved." `docs/links.md`
  gained the Bible kind's payload row, `Decoded.reference`, `LinkNav.Follow.Bible`, the follow
  path, and updated JVM-test counts. `docs/objects.md` gained the selection-toolbar Bible column,
  the Insert bar's ninth kind, `BibleRefFlow`/`BibleRefDialog` in the collaborators table, the two
  undo actions, and the one-text-per-Bible-link invariant. `docs/extensions.md` gained "Arc 38's
  two tails" under § The Bible point, boundary-audit rows 55–56, and the `API_VERSION` 12 ledger
  entry. Both `CLAUDE.md` files and this ledger were updated. No code disagreed with the plan;
  the R4 fix (the Edit-button string) was the only place the walk found something worth recording
  beyond what R2/R3 already built.
- **FREEZE — ✅ 2026-09-13, the user's hand walk on the Nomad** ("Looks and works good!"): the lasso-bar conversion of handwritten "John 3:14-18", the dialog, the link, the follow. One walk finding, not ours: the `.dev` host bound the RELEASE ML Kit first ("The recognizer extension didn't respond" — `caller is not the host`), the arc-36 standing trap; `com.symmetricalpalmtree.notesproutsn.ext.mlkit` was `pm disable-user`'d on the Nomad (re-enable on request). The eleven-button lasso bar fits the Nomad. No code review (decision 5). **Arc 38 is COMPLETE + FROZEN; branch `bible` not merged to `main`.**
