# Arc 39 "Lookup" — the Bible from the document editor (plan + ledger)

**Branch `bible`, 2026-09-13 — a fresh user decision.** While typing notes about a verse in the
document editor, select "John 3:16", tap **Bible** in the text-selection toolbar, and NSE · Bible
opens on just those verses **over** the editor; Back returns to the editor exactly as it was —
caret, undo stack, unsaved text untouched. Works in Write and in Preview. Nothing here is a tenth
extension point: the document editor's host callback grows one compatible tail, and the Bible
point is used exactly as arc 38 left it.

Read first: `extensions/bible/docs/bible.md` § "Bible references", `apps/notesprout_sn/docs/document.md`,
`apps/notesprout_sn/docs/extensions.md` § the fifth point (the host-callback binder) and § the
Bible point. The rules of `apps/notesprout_sn/CLAUDE.md` bind throughout.

---

## The user's decisions (2026-09-13 wizard) — do not re-raise

1. **The trigger is a selection + a button**, in the editor **and** in the preview. First choice:
   an item in the system text-selection toolbar (the one the user already gets for Copy / Paste);
   fallback if the Nomad's ROM misbehaves: a selection-aware Bible button in the editor's header.
   (An earlier lean toward proofread-style underlines on reference-shaped text was withdrawn by
   the user the same day — not to be re-raised.)
2. **Freeze = Nomad hand walk, no code review** (the arc 37 / 38 waiver).
3. **A selection that does not resolve shows a popup alert — never a toast.** The user needs
   time to read it and confirm it.
4. **Selection only.** With nothing selected there is no door: the item lives in the selection
   toolbar, so it does not exist until text is selected. No caret-run guessing.
5. **No Send to notebook** on a passage view opened from the editor: the editor is on top, not a
   page. The reader is opened without the Send flag.

## Judgment calls (stated, not asked)

- **The editor cannot open the Bible itself** — `BibleActivity` / `BibleService` admit only the
  host package (and the reader's own), and the seam's standing rule is that no extension knows
  another exists. So the editor asks the host over the callback binder it already holds, and the
  host walks the Bible door on its behalf.
- **The seam: one compatible tail on `IDocumentHost`** — `openReference(text): int`
  (transaction code 12, after `closeNotebook`). The host resolves the text through
  `IBible.resolve` (bind-per-call) and **parks** the resolved wire in-process (`LookupHandoff`),
  answering `REFERENCE_OPENED`; `REFERENCE_UNKNOWN` = the reader said no; `REFERENCE_UNAVAILABLE`
  = no reader, a reader too old for references, or the ask failed. The call is synchronous on a
  Binder thread, so the editor shows its own "Opening the Bible…" dialog for its life — the host
  is stopped and can paint nothing.
- **The reader is launched by a host trampoline, never by the stopped notebook** (K3's finding,
  2026-09-13). The first cut launched from the notebook Activity behind the editor: the launch
  itself worked (the host owns an Activity in the foreground task's back stack), but an
  `ActivityResult` is delivered before `onResume`, and the notebook never resumes while the editor
  stands — the reader's bind stayed held and the second lookup answered "already showing". So on
  `REFERENCE_OPENED` the editor starts `BibleLookupActivity` for a result
  (`DocumentContract.ACTION_DOCUMENT_LOOKUP_SCREEN`, package-pinned, no extras): a translucent
  exported host screen that takes the park (calling-package-checked, fresh, once), shows the
  "Opening…" box, runs the ordinary showing bracket (`BibleClient.open` on the passage, no Send),
  launches the reader with its own launcher, and finishes the bind and itself when the reader
  returns. `RESULT_LOOKUP_FAILED` is its one result code (the editor explains).
- **`ExtensionContract.API_VERSION` 13 → 14** and `:ext-document`'s editor service redeclares
  **14** (the arc-18 skew guard: an editor that calls code 12 must never bind a host without it).
  `DocumentContract.MIN_API_VERSION_FOR_DOCUMENT_LOOKUP` = 14 is a **method** floor on the
  *extension's* declaration — the host puts the availability boolean on the editor's Intent only
  against an editor declaring ≥ 14. `MIN_API_VERSIONS` untouched; only `:ext-document` redeclares.
- **One boolean on the editor's Intent**, `EXTRA_DOCUMENT_BIBLE_AVAILABLE` — the calendar's
  `EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE` shape (Y4): discovery is the host's, an extension never
  queries for another. Set when the notebook's `BibleEntry` found a reader that understands
  references (the arc-38 floor). Without it the editor installs no selection item at all — GONE,
  never disabled. The editor's Intent carried nothing until now; it now carries one boolean and
  still no content, id or path (audit row 58).
- **No surface-stack entry for a showing opened this way.** A cold launch puts back the editor,
  not the look-up on top of it (a transient reading, not a place). The "Opening…" box is the
  trampoline's own: the stopped notebook's `OpeningOverlay` waits for a frame it never draws.
- **The text is prepared editor-side, purely** (`LookupText.prepare`): trimmed, inner whitespace
  and line breaks collapsed to one space, refused when blank or over
  `DocumentContract.MAX_REFERENCE_CHARS` (512, the wire cap's twin) — a refusal is the same
  "not a reference" alert without a Binder call. The host binder enforces the same cap.
- **The alert wording**: not a reference → "“<the words>” is not a scripture reference this
  Bible knows." with OK; unavailable → "NSE · Bible could not be opened…" with OK. Both
  `Dialogs.problem`, editor-side (the editor is the screen on the glass).
- **The selection item** is added through `setCustomSelectionActionModeCallback` on both the
  `EditText` and the selectable Preview `TextView`, `SHOW_AS_ACTION_IF_ROOM`, titled "Bible". The
  system toolbar is the ROM's chrome and is not restyled. The tap reads the owning view's
  selection, finishes the action mode, and runs the one lookup path.
- **The debug automation** gains `lookup` (text via the usual payload) so the seam walks over adb
  end to end; the selection toolbar itself is the user's hand walk.
- **Nothing typed, selected or resolved is ever logged** on either side — lengths, codes, durations.

## Phases

| Phase | What | Status |
|---|---|---|
| K1 | The seam: `IDocumentHost.openReference`, `DocumentContract` codes/cap/extra/floor/action/result, `API_VERSION` 14, `DocumentHostBinder` + `DocumentHostHooks` + `DocumentEditorClient`/`Entry` (the boolean), `LookupHandoff` + `BibleLookupActivity` (+ manifest, `Theme.Notesprout.Translucent`), `NotebookActivity` wiring | ✅ 2026-09-13 |
| K2 | The editor: `LookupText` (pure, 5 JVM tests — :ext-document 235), `LookupAction` (selection item on both surfaces, the wait dialog, the lookup-screen launcher, the two alerts), strings, manifest 6 → 14, automation `lookup` | ✅ 2026-09-13 |
| K3 | Build, JVM tests (:app 1691), `.dev` install on the Nomad, adb walk | ✅ 2026-09-13 |
| K4 | Docs: `docs/document.md` § Bible lookup + failure rows, `docs/extensions.md` (API ledger, module row, audit rows 58–59), `bible.md` § Lookup from the document editor, both `CLAUDE.md`, memory | ✅ 2026-09-13 |

## Ledger

- **K1 + K2 — 2026-09-13.** Seam, host, editor as the judgment calls describe. `EditorPrefsTest`'s
  `FakeHost` grew the twelfth method.
- **K3 — the Nomad walk (adb, `.dev` builds) — ✅ 2026-09-13.** First cut (launch from the
  stopped notebook, `BibleEntry.openBehind`): "John 3:16" → resolve 79 ms → `beginAt` 118 ms →
  the passage view over the editor in 230 ms end to end, no Send button; Back → the editor with
  caret 9, not dirty; "Hello world" → code 1 → the *Not a reference* alert with the words quoted
  and an OK. **Then the second good lookup answered code 2 — `openBehind: already showing`**: the
  reader's result had never reached the stopped notebook, the bind was still held. Redesigned
  around the trampoline (above) the same day; second cut: lookup 1 (Write) → `BibleLookupActivity`
  → `open: ready in 201 ms` → reader → Back → `reader returned` + `finish: end ok` → the editor,
  state intact; lookup 2 → free (58 ms open); Preview mode → lookup 3 → the same; `get_state`
  after each says `mode=…, caret=9, dirty=false`. Walk traps: the automation action is
  `….ext.document.AUTOMATION` (no `.dev`), and a payload with a space must ride `--es file`.
- **K4 — docs — ✅ 2026-09-13.** As the phase table says. **FROZEN pending the user's hand walk
  of the selection toolbar on the Nomad** (select a reference in Write and in Preview, tap Bible).
