# Notebook Encryption — Phase 2 Plan

> Continuation of `NOTEBOOK_ENCRYPTION_PLAN.md` (Phase 1, S1–S9 ✅ DONE). Phase 1 shipped the core
> SQLCipher model; Phase 2 tackles the deferred / found-along-the-way items. Same working agreement
> as Phase 1: each session is a self-contained, ordered chunk sized for **Sonnet at medium effort**.
> Do sessions **one at a time, in order**.

## Working Agreement (read first)

- **Effort:** Sonnet, medium. One cohesive theme per session.
- **Testing is the user's job.** There is **no "Claude verifies" adb track** in Phase 2. Claude does a
  **clean build + install debug on G10 (`34E517F9`)** at the end of each session and hands off. The
  user runs all on-device tests and reports the verdict. Claude does **not** run device/data checks.
- **Per-session loop:** implement → clean build → install debug on **G10** → user tests → user reports
  issues → fix → clean rebuild → reinstall → on **"all tests pass"**: mark the session ✅ DONE, commit
  with a `🌱` prefix, **do not push**.
- **Wrap-up (S9)** additionally installs on **P2P (`287d2364`)** for a final two-device matrix.
- **Standing constraints (CLAUDE.md) still apply:** Kotlin/Java 17 · `kotlinx.serialization` only ·
  no Material Components · no `org.json` · never `runBlocking` on UI · `Slog.d` not `Log.d` · passphrases
  **never** logged, never in Intent extras, never in the global index · every `.soil` open routes
  through `SoilCrypto`.
- **Reuse the Phase 1 primitives** — `SoilCrypto`, `KeyResolver`, `SoilMigrator`, `PassphraseStore`,
  `KeySession`, `PassphraseCache`, `PassphrasePrompt`, `IndexRepository.getEncryptionInfo/setEncryptionState`.
  Grow their surfaces minimally; do not introduce a parallel path.

## Status Legend

`⬜ NOT STARTED` · `🔨 IN PROGRESS` · `🧪 AWAITING TEST` (built + installed, awaiting user verdict) · `✅ DONE`

## Phase-2 Decisions (locked before S1)

- **Biometric gate — DROPPED.** Not pursued in Phase 2 (no `androidx.biometric` dependency added).
- **Password-protected PDF export — APPROVED** using **PdfBox-Android** (`com.tom-roush:pdfbox-android`,
  **Apache-2.0**). We deliberately avoid iText (AGPL — incompatible with the project's MIT license).
  This is the only new Gradle dependency in Phase 2 (CLAUDE.md requires discussion — this plan is it).
- **Cross-notebook page-copy leak confirm — MOVED TO PHASE 3.** There is no cross-notebook page
  copy/move feature today (it's on the user's personal backlog). The plaintext-leak confirm can't exist
  until the copy/move feature does — deferred and gated on it.
- **Bulk encrypt/decrypt — MOVED TO PHASE 3.** There is no multi-select for notebooks/folders today
  (also on the user's personal backlog). Bulk encryption is deferred and gated on multi-select landing.
- **Ordering:** quick, visible UX wins first (S1–S2), then leak-hygiene (S3), then key-management
  (S4–S6), then password-PDF (S8), then wrap-up (S9).
- **Search-leak audit (was seed item 11) + KDF interop test (was seed item 9)** are verification/doc
  tasks with no expected code change — folded into the **S9 wrap-up**. (Confirmed in planning:
  `SearchEngine` only matches notebook/folder/template **names** from the index; ML Kit recognition is
  **not** persisted anywhere, so no encrypted page text reaches any index today.)

## Session Status Board

| # | Session | Status |
|---|---|---|
| P2.S1 | Passphrase-dialog & open-time UX (show/hide toggle, "Opening…" overlay) | ✅ DONE |
| P2.S2 | New-notebook flow: encryption scope in TemplateBrowser + toolbar-merge verify | ✅ DONE |
| P2.S3 | Undo/redo persistence for encrypted notebooks (store inside the `.soil`) | ✅ DONE |
| P2.S4 | Re-key a single notebook: change passphrase + change scope (`PRAGMA rekey`) | ✅ DONE |
| P2.S5 | Global passphrase management UI (view / change / forget) + rotation trigger | ✅ DONE |
| P2.S6 | Global passphrase rotation: cancellable/resumable batch re-key | ✅ DONE |
| P2.S7 | Passphrase attempt rate-limiting (escalating lockout + countdown) | ✅ DONE |
| P2.S8 | Password-protected PDF export (PdfBox-Android) | ✅ DONE |
| P2.S9 | Wrap-up (search/KDF audit, docs, edge cases, P2P + G10 matrix) | ⬜ NOT STARTED |

> S5/S6 (global key management) are split into UI vs the rotation engine — tightly coupled but each is a
> full medium session. If they prove light on device, they can be merged at commit time; keep them
> separate while building.

---

## P2.S1 — Passphrase-Dialog & Open-Time UX

**Status:** ✅ DONE

**Goal:** Two low-risk, visible polish items: (a) a **show/hide passphrase** eye toggle on every
passphrase dialog, and (b) an **"Opening…" overlay** on the canvas while an encrypted notebook unlocks
+ builds Room + loads the first page (it currently shows blank for a few seconds after the prompt
closes).

### Files
- `app/src/main/res/layout/dialog_passphrase.xml` — add the eye toggle to the input row(s).
- `app/src/main/kotlin/com/notesprout/android/crypto/PassphrasePrompt.kt` — wire the toggle.
- **New** `app/src/main/res/drawable/ic_eye.xml`, `ic_eye_off.xml` (Tabler, e-ink stroke style).
- `app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt` — open-time overlay (open path
  established in Phase 1 S3; the Room build + `loadStrokes()` first-render window).
- `app/src/main/res/layout/activity_notebook.xml` — a simple overlay view (or reuse an existing
  full-screen container).

### Steps
1. **Eye icons.** Add `ic_eye.xml` / `ic_eye_off.xml` as 24dp vector drawables matching the existing
   Tabler convention (`fillColor=transparent`, `strokeColor=@color/inkBlack`, `strokeWidth=2`, round
   caps) — same style as `ic_lock.xml`. Use the official Tabler `eye` / `eye-off` path data.
2. **dialog_passphrase.xml.** Wrap each password `AppCompatEditText` in a horizontal row with an
   `AppCompatImageView` (id `btnTogglePassphrase`, and `btnToggleConfirm` if the confirm field gets its
   own) pinned to the right end, `ic_eye_off` initial state (masked), `inkBlack` tint, transparent
   background, `stateListAnimator=@null`. Keep the 1dp inkBlack bordered background on the row, not the
   inner field, so the icon sits inside the border.
3. **PassphrasePrompt.kt.** On toggle tap, flip the field between `InputType.TYPE_CLASS_TEXT or
   TYPE_TEXT_VARIATION_PASSWORD` and `… or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, swap the icon
   (`ic_eye` ↔ `ic_eye_off`), and **restore the cursor to the end** (`setSelection(text.length)`) since
   changing inputType resets it. A single toggle may reveal **both** fields when `confirm = true`
   (simpler), or give each its own — pick one toggle controlling both for simplicity; document it.
   - **Never log the field value** regardless of state. No snapshot of the dialog.
   - Preserve BOOX IME behaviour (don't re-trigger `showSoftInput` on toggle).
4. **Opening overlay.** In `activity_notebook.xml` add a top-level overlay (`FrameLayout` or include),
   id `openingOverlay`, `visibility=gone`: a centered card with 1dp inkBlack border, paperWhite
   background, a single `TextView` "Opening…" (no spinner/decorative animation — e-ink rule). It must
   sit above the canvas and intercept touches while shown.
5. **Show/hide timing.** In `NotebookActivity` open path, **only when `info.encrypted`**: show
   `openingOverlay` immediately after the passphrase resolves (just before the Room build), and hide it
   when the **first page finishes rendering** (`loadStrokes()` returns / first draw completes — use the
   existing first-render hook if one exists, else hide right after `loadStrokes()`). Plaintext notebooks
   open fast enough — never show the overlay for them.

### Build / install
```sh
cd apps/notesprout_android && ./gradlew assembleDebug \
  && adb -s 34E517F9 install -r app/build/outputs/apk/debug/app-debug.apk
```

### User tests
1. Open any passphrase dialog (create encrypted / open encrypted / decrypt). Tap the eye → text reveals;
   tap again → re-masks. Cursor stays put; confirm field reveals too (if single-toggle). BOOX keyboard
   behaves.
2. Create + confirm flow still validates (mismatch rejected, empty rejected).
3. Open an **encrypted** notebook → "Opening…" overlay appears right after the prompt closes and clears
   when the page draws. Open a **plaintext** notebook → **no** overlay.

### Completion criteria
Eye toggle works on all passphrase dialogs without leaking/relogging; opening overlay shows only for
encrypted notebooks and clears on first render.

---

## P2.S2 — New-Notebook Flow: Encryption Scope in TemplateBrowser (+ toolbar-merge verify)

**Status:** ✅ DONE

**Goal:** Choose **name + template + encryption** in one cohesive screen. Today `TemplateBrowserActivity`
collects name + template and returns; `MainActivity.onTemplatePicked` (~line 203) then pops a **separate**
`ActionSheetDialog` (`showEncryptionScopePicker`, ~1373). Fold the scope choice into the browser's
name-collection step and pass it back in the result. Also **verify** the already-present toolbar
auto-merge surfaces the Phase 1 `lock`/`lockOff` buttons.

### Files
- `app/src/main/kotlin/com/notesprout/android/TemplateBrowserActivity.kt` — name-collection UI (the
  `EXTRA_COLLECT_NAME` path) + result constants (~74–113).
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — `onTemplatePicked` (~203–213),
  `showEncryptionScopePicker` (~1373 — remove or repurpose), `createNotebook` (~1389).
- `app/src/main/kotlin/com/notesprout/android/data/toolbar/ToolbarPreferencesManager.kt` — verify only.

### Steps
1. **TemplateBrowserActivity name screen.** Where it currently collects the notebook name (when
   `EXTRA_COLLECT_NAME`), add an e-ink scope selector beneath the name field: three options matching the
   existing `showEncryptionScopePicker` copy — **No Encryption** (default) / **Encrypt (Global
   Passphrase)** / **Encrypt (Notebook Passphrase)**. Use radio-style `AppCompatRadioButton`s with the
   project's drawable backgrounds, or a compact inline `ActionSheet`-consistent control. No Material.
2. **Result payload.** Add `const val RESULT_KEY_SCOPE = "result_key_scope"` and put the chosen scope as
   a string (`""` / `"GLOBAL"` / `"NOTEBOOK"`) into the result Intent alongside `RESULT_NOTEBOOK_NAME` /
   `RESULT_TEMPLATE_ID`. Do **not** collect the passphrase here — only the scope; the passphrase is
   resolved by `createNotebook` → `KeyResolver` as today (keeps passphrase out of cross-activity data).
3. **MainActivity.onTemplatePicked.** Parse `RESULT_KEY_SCOPE` into `KeyScope?`, and call
   `createNotebook(name, templateId, scope)` **directly** — delete the `showEncryptionScopePicker`
   detour. Keep `createNotebook`'s existing `KeyResolver.resolveForConvertToEncrypted` step (still
   prompts for the passphrase/confirm at the right moment).
4. **Remove dead code.** Delete `showEncryptionScopePicker` if nothing else calls it (grep first).
5. **Toolbar-merge verify (seed item 6).** `ToolbarPreferencesManager.load()` already appends registry
   keys missing from a persisted `order` (lines 30–35). **Confirm** the appended `lock`/`lockOff` keys
   render as **visible** buttons (not present-but-hidden) for a user with a pre-Phase-1 saved config. If
   they land hidden, fix the merge so newly-appended keys default to visible. No new mechanism — just make
   the existing one correct for these two keys.

### Build / install
G10 command (as S1).

### User tests
1. New Notebook → the browser screen shows name + template + the three encryption choices together. Pick
   **Global** → after Create you're prompted once for the global passphrase (or not, if already set) →
   notebook opens encrypted. Pick **Notebook** → prompted with confirm. Pick **None** → opens plaintext,
   no prompt.
2. There is **no** second standalone "Encryption" action sheet after the browser closes.
3. On a device whose toolbar was customised before Phase 1, open a notebook and confirm `lock`/`lockOff`
   appear in the toolbar (or in the customization list as visible).

### Completion criteria
Name + template + encryption chosen in one screen; old detour removed; toolbar lock buttons confirmed
visible for legacy configs.

---

## P2.S3 — Undo/Redo Persistence for Encrypted Notebooks

**Status:** ✅ DONE

**Goal:** Restore cross-session undo/redo for encrypted notebooks. Phase 1 **skips** the plaintext
`*.soil.undoredo` sidecar for encrypted notebooks (`NotebookActivity` ~1837/~1879) to avoid a plaintext
leak, so encrypted notebooks lose their undo history on close. Persist that state **inside the encrypted
`.soil`** instead — encrypted at rest for free, no new crypto.

### Files
- `app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt` — undo/redo persist/restore
  (~1837 delete, ~1858 read, ~1879 write; `undoRedoPersistenceFile` ~8957).
- `app/src/main/kotlin/com/notesprout/android/data/SoilDatabase.kt` (or the schema/migration file) — a
  small meta table/row for the undo/redo blob.

### Steps
1. **Meta table.** Add a single-row meta table via a Room migration, e.g.
   `undo_redo_state(id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT)`. Because the DB is
   SQLCipher-encrypted, the blob is encrypted at rest with no extra work on our side. Bump the schema
   version and add the migration alongside the existing ones.
2. **Write (onStop).** For **encrypted** notebooks, instead of skipping, write `undoRedoManager.toJson()`
   into the meta row through the **keyed** Room/raw connection. For **plaintext** notebooks keep the
   existing `*.undoredo` sidecar path unchanged (no behaviour change). Keep the write off the UI thread —
   match the existing `onStop` persistence (never `runBlocking` on UI).
3. **Read (open).** For encrypted notebooks, read the meta row back and rehydrate the undo stack; for
   plaintext, keep reading the sidecar.
4. **Cleanup / migration interplay.**
   - Keep deleting any stray plaintext `*.undoredo` sidecar for encrypted notebooks (existing ~1837
     logic) — defends against a sidecar left by a pre-Phase-2 build.
   - On **encrypt** (`SoilMigrator.encryptInPlace`): the source's sidecar is already deleted by Phase 1;
     future state lives in the meta row. No change needed there beyond confirming it.
   - On **decrypt** (`SoilMigrator.decryptInPlace`): the meta row travels with the `sqlcipher_export`
     into the now-plaintext file — acceptable (it holds the same undo JSON a sidecar would). The plaintext
     open path reads the sidecar, so the in-DB row is simply ignored for plaintext; optionally clear it on
     decrypt to avoid a stale duplicate. Document the choice.

### Build / install
G10 command.

### User tests
1. In an **encrypted** notebook: draw, undo a few times, close, reopen → undo/redo history is restored
   (redo still works). Confirm **no** `*.undoredo` sidecar file is created for the encrypted notebook.
2. Plaintext notebook undo/redo across close/reopen still works exactly as before (regression).
3. Encrypt a plaintext notebook that had undo history, reopen → undo state is sane (no crash); decrypt it
   back, reopen → undo state still sane.

### Completion criteria
Encrypted notebooks keep undo/redo across sessions with **zero** plaintext sidecar; plaintext path
unchanged; encrypt/decrypt transitions don't corrupt or crash the undo stack.

---

## P2.S4 — Re-Key a Single Notebook: Change Passphrase + Change Scope

**Status:** ✅ DONE

**Goal:** Let the user change an encrypted notebook's passphrase, and switch its **scope**
(GLOBAL ↔ NOTEBOOK), **without** a full decrypt→re-encrypt round trip — using SQLCipher `PRAGMA rekey`.
This is the primitive S6 (global rotation) builds on.

### Files
- `app/src/main/kotlin/com/notesprout/android/crypto/SoilMigrator.kt` — add `rekeyInPlace`.
- `app/src/main/kotlin/com/notesprout/android/crypto/KeyResolver.kt` — small helpers for "old key" +
  "new scope key" resolution if not already expressible.
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — context-menu actions.
- (Optional) `app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt` — if also offered from the
  open toolbar; **default: context-menu only** to keep scope tight (note in Phase 3 if toolbar wanted).

### Steps
1. **`SoilMigrator.rekeyInPlace(file, oldPassphrase, newPassphrase)`** (`suspend`, `Dispatchers.IO`):
   open `file` with the zetetic driver using `oldPassphrase`, run `PRAGMA rekey = '<newPassphrase>'`,
   checkpoint, close. Then `SoilCrypto.verifyPassphrase(file, newPassphrase)` to confirm. `PRAGMA rekey`
   re-encrypts in place (no temp file needed), but still guard with try/catch and surface failures to the
   caller for a Toast. Clean any WAL/SHM left behind so the folder shows only the `.soil`.
   - If on-device testing shows `PRAGMA rekey` is unreliable for any reason, fall back to the existing
     export round-trip (`decrypt to tmp with old → encrypt tmp to dest with new → atomic replace`); note
     whichever path is used.
2. **Context menu — "Change Passphrase"** (encrypted notebooks only): resolve **old** key via
   `resolveForDecrypt` (always prompts — proves the user knows it), then prompt-with-confirm for the
   **new** passphrase, run `rekeyInPlace(file, old, new)`. Scope unchanged → no index write needed.
   - **Restrict to NOTEBOOK-scope notebooks.** A GLOBAL notebook must not drift from the cached global,
     so route GLOBAL passphrase changes through S5/S6 rotation. If the user invokes Change Passphrase on a
     GLOBAL notebook, show a note: "Global notebooks change passphrase via Settings → Encryption."
3. **Context menu — "Change Encryption Scope"** (encrypted notebooks only):
   - **NOTEBOOK → GLOBAL:** resolve old (notebook) key (`resolveForDecrypt`); obtain the global key via
     `resolveForConvertToEncrypted(GLOBAL)` (cached or establish); `rekeyInPlace(file, oldNotebookKey,
     globalKey)`; `setEncryptionState(id, true, GLOBAL)`.
   - **GLOBAL → NOTEBOOK:** resolve old (global) key; prompt-with-confirm for a fresh notebook passphrase;
     `rekeyInPlace(file, globalKey, newNotebookKey)`; `setEncryptionState(id, true, NOTEBOOK)`.
   - Show an "Updating…" progress dialog; refresh the card on success.
4. **KeySession invalidation.** If the notebook being re-keyed is somehow the foreground one, ensure its
   `KeySession` entry is cleared/updated (default flow re-keys from the list while closed, so this is
   mostly defensive).

### Build / install
G10 command.

### User tests
1. NOTEBOOK-scope notebook → context menu → **Change Passphrase** → enter current (verified), set new +
   confirm → reopen now requires the **new** passphrase; the old one is rejected.
2. NOTEBOOK → **Change Encryption Scope → Global** → reopen uses the cached global (no prompt). Card
   still shows lock.
3. GLOBAL → **Change Encryption Scope → Notebook** → set new notebook passphrase → reopen now prompts
   every time with that passphrase.
4. Wrong "current passphrase" on any of these is rejected cleanly; cancel aborts with the file untouched
   and still openable with the original key. Change Passphrase on a GLOBAL notebook shows the redirect note.

### Completion criteria
Single-notebook re-key + scope-switch work via `PRAGMA rekey`; index `keyScope` updated correctly;
failures/cancel leave the original file intact and openable.

---

## P2.S5 — Global Passphrase Management UI

**Status:** ✅ DONE

**Goal:** A place to manage the device's cached GLOBAL passphrase: **view status** (set / not set),
**change** it (which triggers the S6 rotation), and **forget on this device**. No Settings screen
exists today — add a lightweight one reachable from MainActivity's overflow.

### Files
- **New** `app/src/main/kotlin/com/notesprout/android/EncryptionSettingsActivity.kt` (or a dedicated
  dialog if an activity feels heavy — prefer a small activity for future settings growth).
- **New** `app/src/main/res/layout/activity_encryption_settings.xml`.
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — add an overflow entry "Encryption…".
- `app/src/main/kotlin/com/notesprout/android/crypto/PassphraseStore.kt` — already has
  `hasGlobalPassphrase` / `clearGlobalPassphrase`; reuse.

### Steps
1. **Entry point.** Add an "Encryption" item to MainActivity's existing overflow/`ActionSheetDialog`
   menu that launches `EncryptionSettingsActivity`.
2. **Settings screen (e-ink).** A plain vertical layout, 1dp inkBlack borders, no Material:
   - **Status row:** "Global passphrase: **Set**" or "**Not set**" (from `PassphraseStore.hasGlobalPassphrase`).
   - **Count row (informational):** "N notebooks use the global passphrase" — query the index for
     `encrypted == true && keyScope == GLOBAL`.
   - **Button: "Change Global Passphrase…"** → launches the S6 rotation flow (resolve old global, prompt
     new + confirm, batch re-key). Disabled/hidden when not set.
   - **Button: "Forget on This Device"** → confirm dialog explaining: cached global is cleared; global
     notebooks will **prompt once** on next open (and re-cache). Does **not** decrypt anything. On
     confirm: `PassphraseStore.clearGlobalPassphrase` + `KeySession` clear if relevant. Toast confirms.
3. **No passphrase echoed** anywhere on screen; status is boolean only. Never log.
4. **Resumable-rotation banner (ties to S6).** If a rotation marker exists (S6), show a "Resume rotation"
   banner/button at the top. (Wire fully in S6; leave the hook here.)

### Build / install
G10 command.

### User tests
1. Overflow → Encryption → screen shows correct **Set/Not set** status and the global-notebook count.
2. "Forget on This Device" → confirm → status flips to Not set → open a global notebook → prompted once
   → reopen → no prompt (re-cached). No notebook was decrypted.
3. "Change Global Passphrase…" launches the rotation flow (full behaviour validated in S6).

### Completion criteria
Encryption settings screen reachable, shows accurate global status + count, "forget" clears the cache
without touching any notebook, change-button launches rotation.

---

## P2.S6 — Global Passphrase Rotation (Cancellable / Resumable Batch Re-Key)

**Status:** ✅ DONE

**Goal:** Change the global passphrase and **re-key every `keyScope == GLOBAL` notebook** from the old
global to the new one in a single, **cancellable and crash-resumable** operation. Builds on
`SoilMigrator.rekeyInPlace` (S4).

### Files
- **New** `app/src/main/kotlin/com/notesprout/android/crypto/GlobalRotation.kt` — orchestrator + the
  resume marker.
- `app/src/main/kotlin/com/notesprout/android/EncryptionSettingsActivity.kt` — drive the flow + progress.
- `app/src/main/kotlin/com/notesprout/android/crypto/PassphraseStore.kt` — final `setGlobalPassphrase`.

### Steps
1. **Inputs.** Resolve the **old** global (must match the cached value — verify via `verifyPassphrase`
   against any one global notebook, or against the cache). Prompt-with-confirm for the **new** global.
   Abort if old can't be verified.
2. **Enumerate.** From the index, collect all `encrypted == true && keyScope == GLOBAL` notebook ids +
   `.soil` paths.
3. **Resume marker** (in `PassphraseStore`'s `EncryptedSharedPreferences`, never plaintext): persist
   `{ newGlobalHash-or-flag, pendingIds: [...] }` **before** starting. The new global itself can be held
   in `EncryptedSharedPreferences` for the duration so a resume can finish without re-prompting (it's
   already the device-secure store). Clear the marker only after full success.
4. **Per-notebook loop** (each on `Dispatchers.IO`, idempotent): for each pending id —
   `verifyPassphrase(file, newGlobal)` first → if it already opens with the **new** key, it was done in a
   prior interrupted run, **skip** and drop from pending. Else `rekeyInPlace(file, oldGlobal, newGlobal)`,
   verify, then remove that id from the persisted `pendingIds`. This makes the batch crash-safe:
   re-running continues where it stopped, and any notebook is either fully old or fully new.
5. **Cancellation.** A Cancel button stops after the current notebook completes (don't interrupt mid-
   `rekey`). Already-rotated notebooks stay on the new key; the marker keeps the rest pending so the user
   can resume. **Important:** while a rotation is partially done, keep the **old** global cached and only
   call `setGlobalPassphrase(new)` at the very end; for the in-flight window, opens of already-rotated
   notebooks fall through `KeyResolver`'s verify-fail-then-prompt path. Document this window clearly
   (brief, only during an in-progress/cancelled rotation).
6. **Finish.** When `pendingIds` is empty: `PassphraseStore.setGlobalPassphrase(new)`, clear the marker,
   Toast "Global passphrase changed (N notebooks re-keyed)."
7. **Resume on launch.** On app start (or when entering Encryption settings), if a marker exists, surface
   the "Resume rotation" banner (hook from S5) and let the user continue or, if the user supplies the new
   passphrase, finish. Keep it simple: resume only from the Encryption settings screen.
8. **Progress UI.** "Re-keying 3 / 12…" e-ink progress dialog; disable other actions during the run.

### Build / install
G10 command.

### User tests
1. Have ≥3 GLOBAL notebooks. Settings → Change Global Passphrase → old verified, new + confirm → progress
   counts up → all global notebooks reopen with the **new** global (no prompt); old global is rejected.
   NOTEBOOK-scope notebooks are untouched.
2. Cancel mid-rotation → already-rotated notebooks open with the new global (after one prompt if needed);
   remaining ones still open with the old; Settings shows "Resume rotation"; resuming finishes the rest.
3. Kill the app mid-rotation (swipe away) → relaunch → "Resume rotation" appears → resume completes; no
   notebook ends up unopenable.

### Completion criteria
Batch re-key changes the global passphrase across all global notebooks; cancel and crash both leave
every notebook openable and the operation resumable; new global cached only on full completion.

---

## P2.S7 — Passphrase Attempt Rate-Limiting

**Status:** ✅ DONE

**Goal:** After repeated wrong-passphrase rejections, impose an **escalating lockout** before the next
attempt is allowed, with a **countdown** in the prompt. Slows offline brute-force on a compromised
device (complements SQLCipher's PBKDF2 cost; does not replace it).

### Files
- **New** `app/src/main/kotlin/com/notesprout/android/crypto/AttemptLimiter.kt` — persisted counters +
  lockout schedule.
- `app/src/main/kotlin/com/notesprout/android/crypto/KeyResolver.kt` — consult the limiter in the
  prompt/verify loop (`resolveForOpen`, `resolveForDecrypt`).
- `app/src/main/kotlin/com/notesprout/android/crypto/PassphrasePrompt.kt` — show countdown / disable OK
  while locked out.

### Steps
1. **AttemptLimiter** (`object`, backed by `EncryptedSharedPreferences` so it survives process death):
   keyed **per notebook id**, plus a separate **global** bucket (constant `"GLOBAL"` key) for
   global-scope prompts. State per key: `consecutiveFailures: Int`, `lockoutUntil: Long (epoch ms)`.
   - `fun check(key): Long` → returns ms remaining in lockout (0 if allowed).
   - `fun recordFailure(key)` → increment; apply schedule → set `lockoutUntil`.
   - `fun recordSuccess(key)` → reset counters + clear lockout.
   - **Schedule (tune on device):** 1–2 failures → no delay; 3 → 30 s; 5 → 5 min; 10 → 1 hr (cap). Use a
     simple lookup/`when`.
2. **KeyResolver integration.** Before showing the prompt (and before each verify), call `check(key)`. If
   locked out, show the prompt in a **disabled** state with a live countdown (or a dedicated "Locked —
   try again in mm:ss" dialog), don't accept input. On a wrong verify → `recordFailure`; on success →
   `recordSuccess`. Cancel does not change counters.
3. **PassphrasePrompt countdown.** When invoked in locked-out mode, the dialog shows "Too many attempts.
   Try again in **mm:ss**", disables OK + the field, and ticks down (a `CountDownTimer` or coroutine
   `delay` loop tied to the dialog lifecycle) until 0, then re-enables. e-ink: update text once per
   second, no animation.
4. **Never log** attempt values or passphrases. Counters store no passphrase material.

### Build / install
G10 command.

### User tests
1. Open an encrypted notebook, enter wrong passphrase 3× → lockout kicks in with a visible countdown;
   OK disabled until it elapses. Correct passphrase after the countdown opens it and resets the counter.
2. Escalation: drive failures up (5, then 10) → delays grow per schedule. Counter survives app kill
   (relaunch still shows remaining lockout).
3. A successful open resets the counter (subsequent wrong attempt starts from 1 again). Cancelling the
   prompt does not advance the counter.

### Completion criteria
Escalating lockout enforced and persisted across process death; countdown shown; success resets; no
passphrase/attempt material logged.

---

## P2.S8 — Password-Protected PDF Export (PdfBox-Android)

**Status:** ✅ DONE

**Goal:** Offer an **encrypted PDF** export option so the exported file itself is protected at rest
(currently `NotebookExporter` produces plaintext PDFs via Android's `PdfDocument`). Add the password as a
**post-process** step using PdfBox-Android (Apache-2.0).

### Files
- `app/build.gradle.kts` — add `implementation("com.tom-roush:pdfbox-android:2.0.27.0")` (Apache-2.0).
- `app/src/main/kotlin/com/notesprout/android/NotesproutApplication.kt` — `PDFBoxResourceLoader.init(this)`
  once in `onCreate` (after the SQLCipher lib load).
- `app/src/main/kotlin/com/notesprout/android/NotebookExporter.kt` — optional password param + encrypt
  step (`export`, `exportPagesPdf`, `exportPage` — the PDF producers, ~54/132/507).
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` + `NotebookActivity.kt` — export entry
  dialogs: add "Password-protect PDF?" choice.

### Steps
1. **Dependency.** Add PdfBox-Android. Confirm it resolves and doesn't conflict (it ships its own font
   assets — verify APK builds). Init the resource loader once at app start.
2. **Export-options dialog.** Where the user picks PDF export, add an option "Protect with password".
   When chosen, prompt-with-confirm for an **export password** via `PassphrasePrompt` (this is a separate
   password from the notebook passphrase — do **not** assume they want to reuse it; offer a free entry).
   Default remains unprotected (no behaviour change for existing flows).
3. **Encrypt step.** Keep the existing bitmap → `android.graphics.pdf.PdfDocument` rendering writing to a
   **temp** file. When a password was supplied, post-process: `PDDocument.load(tempPdf)` →
   `StandardProtectionPolicy(ownerPwd, userPwd, AccessPermission())` with `setEncryptionKeyLength(128)`
   (AES) → `doc.protect(policy)` → `doc.save(finalFile)` → close → delete the temp. When no password, the
   temp is the final file (rename), unchanged.
4. **Memory/size.** PDFs of many high-res page bitmaps can be large; do the PdfBox load/save on
   `Dispatchers.IO` with the existing "Exporting…" progress dialog covering the extra step. Never block UI.
5. **Hygiene.** Delete the intermediate plaintext temp PDF after producing the encrypted one (don't leave
   a plaintext copy in the cache/exports dir). Never log the export password.

### Build / install
G10 command. (First build pulls the new dependency — expect a longer build.)

### User tests
1. Export a notebook to PDF **without** password → opens normally in any reader (unchanged).
2. Export **with** password → set + confirm → the resulting PDF prompts for the password in a PDF reader
   and opens with it; a wrong password is rejected by the reader. No plaintext temp PDF remains.
3. Export an **encrypted** notebook to a password-protected PDF → notebook passphrase prompt (to read
   pages) **and** the separate export-password prompt both behave; output is protected.

### Completion criteria
Optional password-protected PDF export works (AES-128, prompts in standard readers), default unprotected
path unchanged, no plaintext temp left behind, password never logged.

---

## P2.S9 — Wrap-Up

**Status:** ⬜ NOT STARTED

**Goal:** Verification-only audits (search-leak, KDF interop), documentation, edge-case sweep, and a
two-device test matrix on **G10 (`34E517F9`)** and **P2P (`287d2364`)**.

### Steps
1. **Search-leak audit (seed item 11) — verify + document.** Confirm (re-grep at wrap time) that no
   search / recents / recognition path persists encrypted page **content** to the index: `SearchEngine`
   matches only index **names**; ML Kit recognition output isn't stored. Document the invariant in
   `docs/encryption.md` ("no page content is ever indexed; search is name-only") so future search-index
   work can't regress it. If any new content-indexing has appeared since planning, file it to Phase 3.
2. **KDF interop test (seed item 9) — verify + document.** Encrypt a notebook in-app, pull the `.soil`,
   and on a desktop with **stock SQLCipher** open it with the same passphrase (`PRAGMA key='…'; SELECT
   count(*) FROM sqlite_master;`). Confirm stock default cipher params interoperate (we never customised
   `kdf_iter`/page size). Record the exact params + the verified CLI recipe in `docs/encryption.md`
   ("Portability / interop" section). *(This desktop step is the user's to run; Claude documents the
   recipe and the expected result.)*
3. **Docs:**
   - `docs/encryption.md` — add Phase 2 sections: re-key (`PRAGMA rekey`), scope change, global rotation
     (+ resume marker), rate-limiting, undo/redo-in-`.soil` for encrypted, encrypted-PDF export, the
     search-leak invariant, and the interop recipe. Update component table (`GlobalRotation`,
     `AttemptLimiter`).
   - `CLAUDE.md` — note the new PdfBox-Android dependency in the encryption guardrail row; mention the
     Encryption settings screen.
   - `docs/data-architecture.md` — note the `undo_redo_state` meta table.
   - `docs/toolbar.md` — only if toolbar changed (it didn't beyond the S2 verify).
4. **Edge-case sweep (verify or file to Phase 3):**
   - Re-key/scope-change cancel leaves the original openable (S4).
   - Rotation crash/cancel resume correctness (S6).
   - Rate-limit counter survives process death; success resets (S7).
   - Encrypted-PDF leaves no plaintext temp (S8).
   - Undo/redo restored from `.soil` for encrypted; plaintext sidecar untouched (S3).
5. **MEMORY:** update `project_encryption_architecture.md` (or add a Phase-2 memory) with the new
   primitives (`rekeyInPlace`, `GlobalRotation` resume marker, `AttemptLimiter`, undo/redo-in-DB,
   PdfBox-Android export) and refresh `MEMORY.md`.
6. **Testing:** clean build, install on **G10** and **P2P**.
   - **User tests (full Phase-2 matrix on both devices):** eye toggle + opening overlay; new-notebook
     scope-in-browser; undo/redo across reopen (encrypted); change passphrase + change scope; global
     management (forget/status); global rotation (+ cancel + resume); rate-limit lockout + countdown;
     password-protected PDF export.
7. On **all tests pass**: mark every Phase-2 session ✅ DONE; final wrap-up commit (docs + MEMORY,
   `🌱` prefix). **Do not push.**

---

## Phase 3 — Deferred / Found-Along-The-Way

> Seed list for a future phase. Populate as items surface during Phase 2.

- **Cross-notebook page-copy plaintext-leak confirm** *(blocked)* — warn when copying a page from an
  encrypted notebook into a plaintext one. **Gated on the cross-notebook page copy/move feature existing**
  (currently not implemented; on the user's personal backlog). Revisit once that ships.
- **Bulk encrypt / decrypt (multi-select + whole-folder)** *(blocked)* — encrypt/decrypt many notebooks
  at once, and encrypt a whole folder. **Gated on multi-select for notebooks/folders existing**
  (currently not implemented; on the user's personal backlog). Revisit once that ships. Includes a
  sub-decision: per-notebook distinct passphrases vs one shared passphrase for a NOTEBOOK-scope batch.
- **Biometric gate** *(explicitly dropped from Phase 2)* — optionally require fingerprint/face to release
  the cached global passphrase. Needs `androidx.biometric` (new dependency — would require discussion).
- **Change passphrase from the open toolbar** — Phase 2 puts re-key/scope-change in the context menu
  only; add an in-notebook entry if wanted.
- **Export password = notebook passphrase option** — let the user opt to reuse the notebook passphrase as
  the PDF password instead of entering a separate one.
- **Encrypted PNG/ZIP export** — password-protected archive for PNG exports (PDF is covered in S8).
- **Rotation as a foreground service / WorkManager job** — for very large libraries, move global rotation
  off the activity so it survives navigation, with a persistent notification.
- **Recents thumbnail for encrypted notebooks** — currently the lock icon; consider a user-set cover.
- **Search over decrypted content (opt-in)** — if full-text search of page content is ever added, design
  an explicitly opt-in, encrypted-at-rest index (the S9 audit documents that none exists today).
- **Cross-session undo/redo for plaintext notebooks** — S3 added in-`.soil` undo persistence for encrypted notebooks; extend the same cross-session behaviour to plaintext notebooks (currently plaintext loses history on explicit close; only survives background→foreground via sidecar). Store undo state in the `undo_redo_state` table on close, read it back on open — same pattern, no crypto needed.
