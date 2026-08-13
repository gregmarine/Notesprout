# Proofread — spelling & grammar in the document editor

Spell checking plus a small set of grammar essentials for `DocumentEditorActivity` — the one
Notesprout surface where the user is producing *finished prose* rather than handwriting. Nothing
else checks anything: the notebook canvas, sticky notes, and `TextEditDialog` are untouched
(reuse there is a backlog item, not a plan).

Design stance, decided up front and load-bearing everywhere below: **silence over noise**. A
missed error costs a little; a wrong flag on legitimate writing teaches the user to ignore the
feature. Every rule and gate in this subsystem prefers not judging to misjudging.

---

## Shape of the subsystem

| Layer | File(s) | Role |
|---|---|---|
| Engine | `core/proofread/SpellEngine.kt` | SymSpellKt wrapped around the bundled dictionary; case handling, `shouldCheck` gate, suggestions |
| Tokenizer | `core/proofread/ProofreadTokenizer.kt` | Markdown-aware word spans + the shared `skipMask` (code, URLs, emails, link targets) |
| Check arithmetic | `core/proofread/ProofreadCheck.kt` | `lineRegion`, `affectsWholeDocument`, `misspelled`, and the `ProofreadDirty` offset-shifting accumulator |
| Grammar | `core/proofread/GrammarRules.kt` | Five hand-written rules, each same-line-contained |
| Controller | `ProofreadController.kt` | The thin Android layer: debounce, background pass, span diffing, popups, user dictionary |
| Surface | `MarkdownEditText` (inside `DocumentEditorActivity.kt`) | Draws the underlines in `onDraw`, reports taps |
| Storage | `data/index/UserDictionaryEntity.kt` / `UserDictionaryDao.kt` | The durable user dictionary (global index v11) |

Everything in `core/proofread/` is **pure Kotlin/JVM** — fed plain text and offsets, tested like
`MarkdownFormatter`/`MarkdownReflow` (tests in `src/test/.../core/proofread/`). The controller is
deliberately thin: Android specifics (handlers, spans, dialogs, Room) live there and only there.

## The engine and its dictionary

- **SymSpellKt** (`com.darkrockstudios:symspellkt`, MIT, pure Kotlin) — the one approved
  dependency for this feature. Chosen after the grammar-library search came up empty:
  LanguageTool cannot run on Android (known upstream bug), nlprule is dormant with LGPL
  binaries, and ML Kit GenAI proofreading needs AICore, absent on e-ink devices.
- Dictionary: SymSpell's English `en_82_765` frequency list, shipped gzipped as
  `assets/proofread/en_82765.dict` (~613 KB). Attribution in `NOTICE.txt` beside it — the
  *dictionary data* is CC-BY-3.0 (Google Ngram) + SCOWL; the SymSpell repo is MIT.
- ⚠️ **The extension is `.dict`, not `.gz`, on purpose.** AAPT gunzips any `.gz` asset at build
  time and strips the extension from the APK, so a `.gz` asset's runtime name never matches the
  source tree — this crashed on the Manta before it was understood. An opaque extension ships
  byte-identical. **Never name an asset `.gz`.** JVM tests read `src/main/assets` directly (the
  Gradle test source set mounts it), so they cannot catch APK-only asset divergence — only a
  device run can.
- **Loading is two-stage, because checking and suggesting have wildly different costs.**
  `isKnown` needs only a word→frequency map — one pass over the file (~50 ms JVM, **~0.9 s on a
  Supernote Nomad**). The SymSpell delete index behind `suggestions` is ~30 derived entries per
  word and measured **~41 s on the Nomad** (700 ms on a desktop JVM — the gap is the device, not
  the build type; forced AOT compilation changed nothing). So `SpellEngine.load` builds the map
  and flags can run immediately; `loadSuggestionIndex` feeds that same map into SymSpell in the
  background — no second read of the asset, so the index can never diverge from the words — and
  until it lands the tap popup honestly says *"suggestions are loading"* instead of pretending
  there are none. Both stages happen once per process (`SpellEngine.shared`); the index build
  runs on the **app scope**, not the editor's — the engine is process-shared, and a build tied
  to the activity would be cancelled by Back and restart from zero on every open. Nothing is
  loaded while proofread is off. Load failure is non-fatal: the feature stays quiet, and an
  explicit "Check document" retries; a failed index build leaves checking intact.
- The dictionary indexes lowercase terms; casing is the engine's job. `normalizeWord`
  (lowercase, typographic apostrophe folded to plain `'`) is **the normal form everywhere** —
  the user dictionary's storage form, every ignore set's membership form. Suggestions are
  re-cased to the shape of the word being corrected ("Teh" → "The").
- `shouldCheck` is the conservative gate: no single letters, nothing with a digit or non-ASCII
  letter ("café" is not misspelled English — it is not English), and no case shape other than
  all-lowercase or Titlecase, which spares acronyms ("EPD") and branded casing ("iPad") without
  a list of them. Possessives are known when their stem is ("gardener's").

## The tokenizer

The editor's text is Markdown (see [`documents.md`](documents.md)), so a naive word split would
spell-check code and URLs. `skipMask(text)` marks what is not prose — fenced and indented code,
inline backtick code, URLs, email addresses, and the `(target)` of links/images (labels still
checked) — and `wordSpans` yields word spans over the rest. One mask, two consumers: the spelling
pass and the grammar pass share it, so their idea of "prose" can never drift apart.

Tokens are *inclusive* and `shouldCheck` is the *exclusive* filter, kept separate on purpose: a
word the engine declines to judge ("2nd", "iPad") is still one token, not shrapnel like "nd".
Internal apostrophes join ("don't" is one token — the dictionary carries contractions); hyphens
separate ("e-ink" → "e", "ink"); a leading/trailing apostrophe is quotation, not word.

## When checks run

**Debounced, never per-keystroke; never in Preview.** Edits accumulate in a `ProofreadDirty`
range (tracked in current-text offsets — each new edit shifts what is already tracked,
outward-rounding, so over-covering costs a few lookups but under-covering can never leave a
stale flag). After **1.5 s** of typing idle, the range is expanded to whole lines
(`lineRegion`) and checked. Line bounds are safe word bounds because a word never crosses a
newline — and every grammar rule is same-line-contained for exactly the same reason.

The region is for the *screen's* sake, not the checker's: lookups are microseconds, but every
span added or removed is an e-ink repaint. Two mechanisms keep the region honest:

- **Backticks and tildes force a full pass** (`affectsWholeDocument`, checked on the removed
  text in `beforeTextChanged` too — the outgoing characters no longer exist afterwards). Typing
  a closing fence is exactly the moment the "code" below it becomes prose again.
- The check runs off the main thread against a **snapshot** with a **generation guard**: a
  result arriving after further typing is discarded and the pass escalates to a whole-document
  re-check on the next tick — the stale region's offsets can't be trusted after an edit before
  it, and a full pass is the only fold that cannot miss. The generation also bumps on every
  ignore/dictionary mutation, so an in-flight pass can never re-install a flag the user just
  dismissed. The background pass reads **snapshot copies** of the ignore and user-word sets —
  the live sets belong to the main thread.

The tokenizer always reads the **whole text** even for a small region (fence and link context
must be exact); only the region's words are judged, and a word straddling a region edge is
included whole.

Full passes run on open, on page flip, on "Check document", and when the feature is switched
on. A full pass requested before the dictionary is loaded is not lost — loading completes into
exactly that check. Preview pauses the timer (`setPaused`); returning to Write resumes anything
left owing.

## Flags on screen

Spelling gets a **dashed** inkBlack underline, grammar a **dotted** one — no color, per the
design system; the two textures are distinguishable at reading distance on e-ink. A
`CharacterStyle` cannot draw a dashed line, so `ProofreadFlagSpan` / `GrammarFlagSpan` are
**position-only markers** riding the `Editable` (they follow the text through edits), and
`MarkdownEditText.onDraw` paints under every span each draw pass.

Fresh results are **diffed against the existing spans, not rewritten**: a word still flagged at
the same offsets keeps its span, so a pass that finds nothing new repaints nothing — on e-ink
every needless invalidate is a visible flash. The grammar diff has one extra equality axis
(rule + message + replacement), because a pass can re-diagnose the same range.

## The tap popup

A **confirmed single tap** (GestureDetector `onSingleTapConfirmed` — never a double-tap, which
is the framework's select-word gesture; never a drag or long-press) reports a character offset;
a spelling span there opens a sheet of up to five suggestions plus **Add to dictionary**
(durable) and **Ignore for now** (session-only, by design). Two traps live here, both learned
on-device: the offset is resolved at ACTION_UP, because the confirmation arrives ~300 ms later —
after a tap that summoned the keyboard has resized and scrolled the view, when the same x/y
names a different character; and a tap inside an active selection is ignored (the selection
workflow owns it). Suggestions are looked up on tap, off the main thread, single-flight —
`Verbosity.All` over the whole dictionary is popup-only cost. While the suggestion index is
still building, the sheet titles itself *"— suggestions are loading"* rather than claiming
there are none.

A grammar span's message and fix were computed against the text as it stood, so the span
carries them plus the flagged **snippet**: a tap on a span whose text has drifted mid-debounce
is declined (the imminent re-check re-flags whatever still deserves it). The popup shows the
finding's message, a one-tap **Fix** when the rule has a mechanical correction, and **Ignore for
now**, which mutes that rule + snippet pair everywhere for the session.

Every replacement — suggestion or fix — goes through the `Editable`, the same route the format
bar takes, so **Ctrl+Z takes it back**.

## The user dictionary

`user_dictionary` in the **global index** (`notesprout.db`, Room v11 / `MIGRATION_10_11`) — a
user's vocabulary belongs to them, not to any one document, and the index is encrypted at rest
and already covered by backup. Full spec: [`global-index-format.md`](global-index-format.md).

The word (normalized form) is the primary key *and* the payload. Removal is a **hard DELETE** —
unlike content objects, there is no identity a tombstone could preserve, and a removed word must
stop vouching for itself immediately. Adds use REPLACE (re-adding is not an error).

The controller mirrors the table into memory alongside the engine load — words load *before*
the engine is published, so the first pass never flags a word added yesterday. DB writes go
through `NotesproutApplication.appScope`: an add followed immediately by Back must not be
cancelled with the activity. The manage list (Proofread sheet → "User dictionary") reads the
DAO fresh rather than trusting the mirror, and removing a word re-checks the document.

Because the editor now touches the global index, **`DocumentEditorActivity` is behind
`IndexGuard`** (`ready` in `onCreate`, `bounced` in `onDestroy`) — it fires only when Android
rebuilds the task after a process kill, where the notebook host is gone anyway.

## Grammar essentials

No viable offline grammar library exists for this app (see above), and a noisy home-grown one
would be worse than none. So: **five hand-written rules**, each tuned for precision, each
reading only within one line, each guard below present because the unguarded rule fires on real
prose. Per-rule JVM tests include false-positive guards.

| Rule | Fires on | Key guards | Fix |
|---|---|---|---|
| `repeated-word` | "the the" — identical all-letter words over same-line whitespace | `VALID_DOUBLES` (had/that/very/…); both-Capitalized = proper noun (Walla Walla); digits | first word |
| `sentence-capital` | lowercase word after `.` `!` `?` (+ optional closers `"'”’)*_]~`) and a same-line space | paragraph starts never judged; for `.`: previous word must exist, no digits, length > 1, not in `ABBREVIATIONS` (incl. months/days), not an ellipsis | capitalize |
| `lone-i` | lowercase "i" or i'm/i'll/i've/i'd | adjacent `-` (i-beam) or following `.` (i.e.) = notation | capitalize |
| `a-an` | article–vowel disagreement, judged by *sound* where the letter is reliable | every u-word unjudged both directions (university vs. uninteresting); one/once/eu/ew = consonant sound; `SILENT_H_PREFIXES` (hour/honest/…); acronyms, digits, cross-line, "a a" skipped; flag sits on the article alone | swap article |
| `unpaired` | per line: odd straight `"` count, smart-quote imbalance, unbalanced `()[]{}` | masked chars invisible; digit-then-`"` = inches; emoticon eyes `:) ;) :-) :^)`; enumeration markers "1)" "a)" | none — which side is wrong is the writer's call |

Multi-line pairs are legitimate in prose, which is why `unpaired` judges per line — and a line
is the incremental region's unit, so the rule set and the re-check arithmetic agree by
construction.

## Controls

The format bar's tail **Proofread** button (`ic_text_spellcheck` — on narrow screens it lives in
the overflow panel) opens a sheet: **Check document** · **User dictionary** · **Turn off
proofread** — or, when off, only **Turn on proofread**. Off *hides* the other actions rather
than disabling them (a disabled control is invisible on e-ink, see
[`design-system.md`](design-system.md)).

On/off is global (`DocumentPreferences.proofreadEnabled`, default **on**), like text size.
While off, the dictionary is never loaded — a user who turned the feature off should not pay
~90 MB of heap for it. Turning it on loads and checks everything; turning it off cancels
pending work and removes every flag.

## Performance notes

Measured 2026-08-13 (Phase 5), Supernote Nomad, debug build (forced AOT made no difference):

| | Mac JVM | Nomad |
|---|---|---|
| Stage 1 — word map, gates first flags | ~50 ms | **~0.9 s** |
| Stage 2 — SymSpell suggestion index, background | ~700 ms | **~41 s** |
| Dalvik heap with both loaded | — | ~91 MB allocated (192 MB limit) |

- The ~55× device gap on stage 2 is ~2.5M string allocations + hash-map inserts on a slow ARM
  core with GC churn — which is why it must never gate the flags. The word map costs ~7 MB of
  the heap; the index is the rest. Levers if heap ever pinches on a low-RAM device:
  `SpellCheckSettings` prefixLength 7 → 5, or a smaller dictionary tier.
- The pass itself is cheap; the expensive resource is the **screen**. Regional checks, span
  diffing, and the no-op invalidate guard all exist to keep e-ink repaints at zero when nothing
  changed.

## Future (backlog)

Other languages (add a frequency dictionary asset + a language choice), a review-stepper mode
(walk flag to flag), and reuse in `TextEditDialog`/sticky notes — see `BACKLOG.md`.
