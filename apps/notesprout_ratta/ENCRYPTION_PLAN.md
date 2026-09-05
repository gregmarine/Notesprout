# ENCRYPTION_PLAN.md — Arc 26 "Keys" (Notesprout SN, branch `ratta`)

**Standalone plan for the full encryption implementation** — item 1 of `PARITY_BACKLOG.md`. This
file is the cross-session memory for the arc: read it whole at every phase start, together with
the root `CLAUDE.md` and `apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for
this arc** unless a standing trap needs checking; its protocol and traps are summarized at the end
so this file is enough. `DRIVE_PLAN.md` is the shape this file copies.

**Status:** wizard locked 2026-09-05 · U1 ✅ (2026-09-05) · U2 ✅ (2026-09-05) · U3 ✅ (2026-09-05) · U4 ✅ (2026-09-05) · U5 ⬜ · U6 ⬜ · U7 ⬜

---

## What this arc is

og Notesprout (`apps/notesprout_android` — reading reference, **no code copied**) has a complete
encryption surface: `EncryptionSettingsActivity` (reveal the recovery key, change the global
passphrase, forget it on this device), `crypto/GlobalRotation` (a journaled, resumable re-key of
every global-scope file), per-notebook key scope (`KeyScope` / `KeyResolver` / lock badges / prompt
on open / change passphrase + scope from the library), `NotebookRecovery` (the "Can't open this
notebook — try a passphrase / Repair and open" dialog) and the data-loss defenses recorded in
`docs/encryption.md`. SN is encrypt-by-default under one auto-minted `NSPT-` recovery key and has
**no encryption UI beyond the two bootstrap screens** — the key is shown once and never again in a
release build, cannot be replaced, and every notebook is global-scope.

This arc closes that gap **to og parity**, with three deliberate SN-isms recorded in the decision
table: rotation mints a new `NSPT-` key by default (a typed passphrase is the option), the
door is a library bottom-bar button (SN has no overflow), and `SoilMigrator`'s plaintext branch is
not ported (SN was born encrypted; `probe == Plaintext` at boot stays `FOREIGN_FILE`).

**Not a point, not an `API_VERSION` bump, no extension touched.** Host-only: `crypto/`, the
bootstrap, the library, the notebook open path, export/import/backup call sites. Version stays
`0.1.0-ratta`.

## What SN already has (do not rebuild)

| og piece | SN today | This arc |
|---|---|---|
| `SelfHealingKeyFactory` (heal on the first raw-key open exception) | `KeyOpener.roomFactoryFor` **verifies the cached raw key before every Room open** and invalidates a stale one — the verify-first form of the same defense; `SnIndex.ensureReady` does the same for the index; `SelfContainedSnapshot` (V4) does it on the raw path | U6 audits the remaining raw-path users of `KeyMaterial.peekOrLoad` and adds og's recovery *dialog*; no factory port |
| `SoilMigrator.rekeyInPlace` (export-and-key) | `ExportKeying.exportAndKeyToPrimary` — the shared destination-primary core, `copyUserVersion`, `restampMeta`, acceptance (probe + open + `integrity_check` + `user_version`) | U2 wraps it as an **in-place** rekey of a Garden file with og's `commitReplace` + interrupted-migration recovery |
| `SoilMigrator.encryptInPlace` / `decryptInPlace` / `GlobalConversion` / "Encrypt All" | not needed — no plaintext notebook can exist | nothing |
| `KeyScope` + index `keyScope` column | `KEY_SCOPE_GLOBAL` / `KEY_SCOPE_NOTEBOOK` constants; the index row and `notebook_meta` already carry `keyScope` (always `GLOBAL`) | U4 reads and writes it — **no schema change, no migration** |
| `AttemptLimiter` with per-bucket keys | `AttemptLimiter(key)` — `GLOBAL` and `"IMPORT"` buckets exist | U4 adds the notebook-id bucket |
| `NonDeletingErrorHandler` on ~18 raw opens | every open routes through `SoilCrypto` (+ `NonDestructiveOpenHelperFactory`); creation is its own named path | unchanged; every new open in this arc goes through the same door |
| `RecoveryKeyActivity` (show once) | exists, gated on `recovery_key_acknowledged` | U3 reuses it unchanged: a minted rotation **clears the acknowledgement**, so the next Bootstrap shows the new key through the existing gate |
| Debug "Show recovery key" / "Forget cached key" | `DebugMenu` | U1 **removes both** (decision 12) |

## Decisions (wizard 2026-09-05 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | New passphrase form on rotation | **Both: mint a new `NSPT-` key by default, "Choose my own" as the option.** A minted key is shown once through `RecoveryKeyActivity` (acknowledgement cleared by the rotation); a typed one is never shown again — the user typed it. Unlock accepts anything typed and still tries the Crockford confusable fold as a second attempt. |
| 2 | Per-notebook scope | **In this arc, full og parity** (U4–U5): prompt on every open, lock card, library-sheet Change passphrase / Change scope, create-time scope choice, import chooser, backup copies them uncompacted, recovery. |
| 3 | Import keying | **og's chooser returns** for a file that needed a foreign passphrase: *Keep this passphrase* (→ `NOTEBOOK`; **if the typed passphrase equals the global one the notebook stays `GLOBAL`** — og's downgrade rule) / *Use this device's key* (today's re-key) / *Set a new notebook passphrase*. Plaintext and same-device files never see the chooser (nothing to keep). Arc 16's "always re-key to global" becomes the *Use this device's key* branch. |
| 4 | Old backups after a rotation | **Warn before, offer after.** The change-passphrase confirm dialog says existing backups (local and cloud) open only with the old key until the next backup run; rotation **clears both stamp maps** (`stamps` + `cloudStamps`) so that run replaces every file; the completion dialog offers **Back up now** (opens the Backup screen) / **Done**. Nothing runs unasked. |
| 5 | The door | **New library bottom-left button after Backup**: `[Backup] [Encryption] [Import]`, Tabler `lock`, long-press hint "Encryption", hidden in shelves like its neighbours; the row is measured on the Nomad before it lands. |
| 6 | Forget on this device | **Ships in release** behind a confirm dialog (og's wording: the next launch asks for the recovery key; nothing is decrypted or modified). |
| 7 | Reveal recovery key | **No re-authentication**, like og. The device PIN is the gate. |
| 8 | Code review | **None in this arc** — no `/code-review` on any phase. Fable reads every phase's code before the walk (the model recipe). |
| 9 | Walks | **Sonnet drives adb walks**; the rotation and recovery walks (a wrong step can lock the library) **Fable drives by hand, step by step**. Nomad only (SNN `SN078D10012852`). |
| 10 | Name / letter / file | **Arc 26 "Keys"**, phases **U1–U7**, this standalone `ENCRYPTION_PLAN.md`. |
| 11 | Lock card | **Lock instead of cover, exactly og**: a `NOTEBOOK`-scope notebook's card shows `ic_lock_cover`-style glyph only; the index stores no cover for it (`setEncryptionState` nulls the blob; the seal's cover refresh skips it; `notebook_meta.cover` restamped null). |
| 12 | Prompt frequency | **Every open, like og.** The notebook screen and a link follow prompt each time; create/import seed a **single-use** `PassphraseCache` so the first open after setting a passphrase does not re-ask. The raw key is cached in the Keystore for speed (og does the same); the passphrase is never cached. |
| 13 | Typed passphrase rules | **≥ 8 characters, confirm field, trimmed, no other rule** — same for a chosen global passphrase and every notebook passphrase (`PassphraseRules`, pure, tested). |
| 14 | Debug menu | **Both duplicates removed** at U1. Test-data recipes that used "Show recovery key" use the Encryption screen. |
| 15 | App version | Stays `0.1.0-ratta`. |

## Design (Fable, 2026-09-05 — binding unless a phase-start question reopens it)

### D1 — The in-place rekey (`crypto/SoilRekey`, U2)

One recipe for every Garden file — a `.soil`, a `Garden/<pkg>.db` extension store, and the index:

1. **The file must be cold**: no connection in this process (`SoilOpenFiles.isOpen` false for a
   `.soil`; `ExtensionStores.closeAll()` for stores; `SnIndex.closeForRotation()` for the index),
   and its WAL absorbed. Absorb by the open-seal ritual under the *current* key (the shape
   `BackupEngine.compactPass` already uses) — never by deleting a non-empty `-wal`.
2. `ExportKeying.exportAndKeyToPrimary` from the file into a sibling **`<name>.rekey.tmp`** under
   the new key; `copyUserVersion`; for a `.soil` `restampMeta(encrypted = true, keyScope = <the
   scope the caller names>)`; acceptance = probe `Encrypted` + opens under the new key +
   `integrity_check` = ok + `user_version` equal. Failure deletes **only the tmp**.
3. **`commitReplace`** (og's order, fsync'd): fsync tmp → delete the original's `-wal`/`-shm`
   (empty or absent by step 1) → rename original → `<name>.old.bak` → rename tmp → original →
   fsync dir → delete `.old.bak`. If both renames fail, both copies stay.
4. `KeyMaterial.invalidate(fileId)` — the salt changed.

**Recovery of an interrupted commit** (`SoilRekey.recoverGarden`, run by Bootstrap once the index
is open, and by rotation resume before its loop): for every `*.rekey.tmp` / `*.old.bak` in
`Garden/`: original missing + tmp verifies under the current global → rename tmp in; original
missing + `.old.bak` present and tmp does not verify → rename `.old.bak` back; original present and
opening → delete the leftovers. **Nothing is deleted unless the surviving file verifies.**

`PRAGMA rekey` is never used (og's on-device finding, already the arc-15 law).

### D2 — Rotation (`crypto/GlobalRotation`, U3)

- **Journal first.** `RotationMarker(pendingIds, newPassphrase, minted: Boolean)` in
  `PassphraseStore` (EncryptedSharedPreferences) **before any file is touched**; `pendingIds` is
  rewritten after every file. Ids: notebook ids (index rows with `keyScope = GLOBAL`), then
  `ext:<pkg>` for every `Garden/<pkg>.db`, then `__notesprout_index__` last.
- **Order:** notebooks → extension stores → **index last** → commit (`setGlobalPassphrase(new)`,
  clear the marker, `KeyMaterial.clearAll`, clear both backup stamp maps, and if `minted`, clear
  `recovery_key_acknowledged`) → relaunch through `BootstrapActivity`.
- **Per file:** cancel check → if the original is missing but a verified tmp exists, finish the
  commit → if it already opens under the new key, skip (idempotent) → else `SoilRekey`. **Failure:**
  opens under the old key → transient, keep pending, stop with *Failed* (the user resumes); opens
  under **neither** → **quarantine**: `setEncryptionState(id, NOTEBOOK)` (lock card), drop from
  pending, continue; reported as "N notebooks could not be re-keyed and now need their own
  passphrase" (U6's recovery is the way back). Never deleted.
- **`NOTEBOOK`-scope notebooks are never touched** (they are not in the id list).
- **The index cannot be rotated while Room holds it.** `SnIndex.closeForRotation()` (checkpoint,
  close, `instance = null`) is the one new door on `SnIndex`; after it the Encryption screen must
  touch nothing but dialogs, and the only way out is the relaunch. `IndexGuard` already bounces
  every other screen.
- **Resume paths (three, all needed):**
  1. Encryption screen banner "A passphrase change is in progress — Resume" while a marker exists
     (og's banner); Change passphrase and Forget are GONE while it shows.
  2. **Bootstrap forwards to the Encryption screen** (not the library) while a marker exists — the
     resume cannot be missed; Back from there goes to the library.
  3. **Death after the index rekey, before commit**: the index opens only under the marker's new
     passphrase while the cached global is still the old one. `SnIndex.ensureReady` tries the
     marker's `newPassphrase` before answering `NEEDS_UNLOCK`; if it opens, it **commits the
     rotation itself** (the same commit as above) and answers `READY`.
- While a marker exists, `KeyResolver` (U4) offers the marker's new passphrase as a **second
  candidate** for a `GLOBAL` notebook whose file no longer opens under the cached global — an
  already-rotated notebook stays openable mid-rotation without a prompt.
- **UI (Encryption screen):** *Change passphrase…* → verify current (string match against the
  cache; the field accepts the confusable fold) → *New passphrase* dialog: **Generate a new
  recovery key** (default radio) / **Choose my own** (two fields, `PassphraseRules`) → confirm
  dialog carrying the **backups warning** (decision 4) → non-cancelable progress dialog "Re-keying
  n / t…" with **Cancel** (stops after the current file; the marker keeps the rest; resume later)
  → completion dialog **Back up now / Done** → relaunch. A minted key is shown by Bootstrap →
  `RecoveryKeyActivity` (acknowledgement cleared), then the library, then the Backup screen if
  *Back up now* was chosen (`EXTRA_THEN_BACKUP` on the Bootstrap intent — a boolean, never a secret).
- The engine runs in the activity's scope under `NonCancellable` per file, screen kept on. The
  journal makes activity death survivable; nothing else needs to.

### D3 — Notebook scope (`crypto/KeyScope`, `crypto/KeyResolver`, U4)

- `KeyScope { GLOBAL, NOTEBOOK }` ↔ the existing string column. `IndexRepository.encryptionInfo(id)`
  / `setEncryptionState(id, scope)` — the latter nulls the cover blob for `NOTEBOOK` and never
  bumps `updatedAt` (sacred). But a scope change re-keys the file, and a backup that compares
  stamps against `updatedAt` would keep the old-key copy forever — so **scope change and
  notebook-passphrase change clear that notebook's stamp in both maps** (the arc-16 import
  precedent — `stamps - id`).
- **`KeyResolver` is pure decision, prompts are UI.** `KeyResolver.forOpen(context, id)` →
  `Resolved.Global(passphrase)` / `Resolved.NeedsPrompt` / `Resolved.NoKey`; the rotation-marker
  second candidate lives here. `NotebookPassphrasePrompt.ask(activity, id, name): String?` is the
  one dialog: verify-then-accept loop over `SoilCrypto.verifyPassphrase`, `AttemptLimiter` bucket
  = the notebook id (lockout text inline, entry row GONE while locked — Unlock's shape), never
  hides the IME (the Ratta rule), takes `PassphraseCache.takeOnce(id)` first.
- **Open sites and what each does for `NOTEBOOK` scope** (every one is a call-site change; the
  list is the U4 checklist):
  - `NotebookSession.open` (the notebook screen): prompt, every time. The notebook screen's open
    failure that is a key failure hands off to U6's recovery.
  - `LinkFollowFlow` into a foreign notebook: prompt (a follow is a deliberate act).
  - `ForeignPageSource` / `PickerPageSource` / `LinkPickerActivity`: **the picker hides
    `NOTEBOOK`-scope notebooks** it has not been unlocked for this process (planner call — a
    preview grid must not prompt).
  - `SoilDatabase.readOnce` (recents, export source, page reads): answers **null** for a
    `NOTEBOOK`-scope notebook unless `NotebookUnlocks` (a per-process set of ids the user has
    prompted successfully for, cleared with the process) holds it. The notebook screen still
    prompts every open (decision 12); this set only lets silent *reads* follow a deliberate unlock.
  - `ExportOpen.readOnly` / `ExportActivity`: prompt at export time; **Keep** reads "Keep
    encryption (this notebook's passphrase)"; `restampMeta` writes the resulting scope.
  - `BackupEngine.compactPass`: **skip** for `NOTEBOOK` scope (og's rule — no unattended key);
    the copy still happens, `-wal` alongside, both before the stamp (the K3 rule already covers it).
  - `SelfContainedSnapshot` (cloud leg): the cached raw key if it verifies, else the notebook is
    **skipped and counted** ("needs its passphrase") — the cloud never holds a sidecar (V4 law) and
    a WAL cannot be absorbed without a key. Planner call; the Backup screen's status line says so.
  - `ExtensionStores`, the index, `NewNotebookActivity` create: global (stores and the index are
    never notebook-scoped).
- **The card:** `CardItem.Notebook` gains `locked`; the grid draws the lock glyph in the cover's
  place, no thumbnail fetch (the batched blob-free read the library already does stays blob-free).

### D4 — Doors (U5)

- **New Notebook:** a scope choice under the type/template rows — *This device's key* (default) /
  *Its own passphrase* → set-passphrase dialog (`PassphraseRules`, confirm field, og's wording
  "You will be prompted for this passphrase every time you open this notebook.") → create under it,
  `PassphraseCache.storeOnce`, `setEncryptionState(NOTEBOOK)`.
- **Library long-press sheet** on an encrypted notebook: *Change passphrase…* (GLOBAL → redirect
  dialog "Global notebooks share this device's key — use Encryption" with **Open Encryption**;
  NOTEBOOK → current passphrase (prompt + verify) → new (rules) → `SoilRekey` → invalidate +
  stamps cleared) and *Change encryption scope…* (GLOBAL→NOTEBOOK: old key from the session, new
  from the set-passphrase dialog; NOTEBOOK→GLOBAL: prompt current, rekey to the global, cover
  refresh on next seal). Both refuse while the notebook is open (`SoilOpenFiles`) — from the
  library it never is.
- **Import chooser** (decision 3) inside `ImportFlow` after the foreign passphrase verifies; the
  three placements/collision questions are unchanged and follow it.

### D5 — Recovery (`crypto/NotebookRecovery`, U6)

`NotebookSession.open` failure classified as a key failure (`SoilLockedException` / SQLCipher
"file is not a database", never a schema error) → `NotebookRecovery.offer(activity, id, name)`:
dialog "Can't open <name>" / "The saved key didn't unlock this notebook. If you know its
passphrase you can try it now. The notebook itself is intact." → **Try** silently tries the cached
global, then the rotation marker's new key if any → prompt loop (`NotebookPassphrasePrompt`,
bucket = id) → on success `KeyMaterial.invalidate(id)`; if the index says `GLOBAL` and the working
key ≠ the global → "Repair this notebook?" **Repair and open** = `SoilRekey` to the global; if
`NOTEBOOK` → `PassphraseCache.storeOnce` → reopen. **Once per launch** (`recoveryAttempted` on the
session, og's `openFixAttempted`). Plus the audit: every `KeyMaterial.peekOrLoad` user verifies +
invalidates before use (the V4 trap), and a **debug-only** "Break keying" long-press action to
exercise it on the Nomad (og's precedent — never a release entry point).

---

## Phases

Recipe for every phase: **Fable** plans, writes the crypto/engine seams (D1, D2, D3's resolver),
reviews every phase's code before the walk · **Opus** feature code on a Fable brief · **Sonnet**
scaffold, layouts, strings, docs, and the adb walks · ≤ 5 background agents. Gate: JVM tests for
all pure logic; a Sonnet walk for what adb can see; **rotation and recovery walked by Fable by
hand**; a **short numbered user checklist** for what adb cannot drive (typing on the Supernote is
on-screen-keyboard taps; every SAF pick; the live library after a relaunch). Then docs / memory /
`CLAUDE.md`, **commit + push**, user runs `/clear`. **No code review in any phase.**

Every phase's crypto walk starts by **writing the Nomad's current recovery key down** (the
Encryption screen, once U1 lands — the debug item is gone) so no walk can lock the dev library.

### U1 ✅ — The Encryption screen, the door, Reveal, Forget (2026-09-05)
- `EncryptionActivity` (`encryption/`): top bar `Cancel` (left, closes) · title; body = status
  ("Recovery key: set" · "N notebooks use this device's key" — the count reads `keyScope`, so it
  is honest from U4 on) · **Reveal recovery key…** (dialog: monospace key, **Copy** / **Close**;
  og's wording) · **Change passphrase…** (GONE until U3 — not-built controls do not exist) ·
  **Forget on this device…** (confirm → `clearGlobalPassphrase` + `KeySession.clear` +
  `KeyMaterial.clearAll` → `finishAffinity`; the next launch is Unlock).
- Library: `btnEncryption` after Backup (`ic_lock`, hint "Encryption"), shelf-hidden with its
  neighbours; `IndexGuard` first thing.
- `DebugMenu`: "Show recovery key" and "Forget cached key" removed.
- Tests: none pure beyond strings; walk = door, reveal (key matches `sn_secure`'s), Forget →
  Unlock → the key opens it (Fable by hand for the Forget half). **Nomad row measured.**
- **Questions at phase start:** (1) exact wording of the three dialogs — take og's verbatim unless
  told otherwise. Nothing else.

### U2 ✅ — The in-place rekey core (2026-09-05)
- `crypto/SoilRekey` per D1 (Fable): `rekeyInPlace(file, fileId, old, new, scope?)`, `commitReplace`,
  `recoverGarden`; `SnIndex.closeForRotation()`; `ExtensionStores.closeAll()` already exists.
- `crypto/PassphraseRules` (pure; decision 13) + tests. `crypto/PassphraseCache` (single-use, RAM).
- Bootstrap runs `recoverGarden` after the index opens.
- Tests: `PassphraseRules`, the commit-order state machine over fake files (pure sequencing with an
  injected fs), `recoverGarden`'s decision table over fake listings. SQLCipher itself is not on the
  JVM — the transform is proven on the Nomad: a debug-menu "Rekey one notebook (debug)" round-trip
  (old → throwaway → old) with `integrity_check` before/after, and a kill between rename steps
  reproduced by hand (`.old.bak` left behind → Bootstrap recovers it).
- **Questions at phase start:** none pending.

### U3 ✅ — Rotation (2026-09-05)
- `crypto/GlobalRotation` + `RotationMarker` per D2 (Fable); `SnIndex.ensureReady`'s marker path;
  Bootstrap's marker forward; `EXTRA_THEN_BACKUP`.
- Encryption screen (Opus): Change passphrase flow, Generate / Choose my own, backups warning,
  progress + Cancel, completion Back up now / Done, resume banner; stamps cleared on commit.
- Tests: marker encode/decode, id ordering (notebooks → stores → index), the per-file outcome
  table (skip / rekey / transient-stop / quarantine), commit's side-effect list, the Bootstrap
  marker decision.
- Walk (**Fable by hand**, with the key written down first): rotate with a minted key → Bootstrap
  shows the new key → library opens → every notebook + the calendar + tags + scratch pad stores
  open → Backup screen shows everything pending → rotate again with a typed passphrase → Forget →
  Unlock with the typed one → kill mid-rotation (adb `am force-stop` between files) → Bootstrap →
  Encryption screen banner → Resume → clean. The old local backup folder is then checked to be
  unopenable under the new key and replaced by one backup run.
- **Questions at phase start (answered 2026-09-05):** (1) Cancel mid-rotation → **og: stop after
  the current file, leave the banner**; no "finish now / later". (2) Progress dialog → title
  "Changing passphrase", **"Re-keying n of t…" + the current name** on its own line (stores read
  "Extension data", the index "Library index"), footer "Keep the app open. This can take a
  while.", Cancel only.

### U4 ✅ — Notebook scope: resolver, prompt, lock card, every open site (2026-09-05)
- `KeyScope`, `KeyResolver`, `NotebookUnlocks`, `NotebookPassphrasePrompt` per D3 (Fable the
  resolver + the prompt's verify loop; Opus the call sites; Sonnet the layouts/strings).
- `IndexRepository.encryptionInfo` / `setEncryptionState` (cover nulling, stamp clearing);
  `NotebookMetaStore` refresh sources `keyScope` from the index bit (the meta-refresh-wipe trap);
  the seal's cover capture skips `NOTEBOOK`.
- Every open site in D3's list; `BackupEngine.compactPass` skip; `SelfContainedSnapshot` skip +
  count; the picker's hide rule; `readOnce`'s null rule; rotation's id list filters by scope.
- Library grid: the lock card.
- No door creates a `NOTEBOOK` notebook yet — the walk uses a debug-menu "Make notebook-scoped
  (debug)" that runs the D4 scope-change core without the sheet (kept for U5's sheet, then removed).
- Tests: resolver decision table (scope × cache × marker), the limiter bucket, `NotebookUnlocks`,
  the picker/readOnce rules, the card model.
- Walk: Sonnet for the library/picker/export/backup visibility; **Fable by hand** for the prompt
  (typing on the on-screen keyboard), the lockout, and the link follow.
- **Questions at phase start (answered 2026-09-05):** (1) picker rule → **show a lock row that
  prompts** (not hide): the row wears the lock glyph in the cover's place; tapping it opens
  `NotebookPassphrasePrompt`, and on success the pages load and `NotebookUnlocks` records the
  id. (2) cloud-leg skip wording → **none**: a sealed notebook has no WAL (`SoilDatabase.seal`
  checkpoints TRUNCATE), so `SelfContainedSnapshot` **skips the open entirely when no sidecar was
  copied** (probe alone decides) and needs a key only to absorb a leftover WAL; a `NOTEBOOK`-scope
  file with a leftover WAL and no cached raw key falls into the existing "refused this run,
  counted failed, retried next run" outcome — no new status-line wording.

### U5 ⬜ — Notebook scope: the doors
- New Notebook scope choice; library sheet Change passphrase / Change encryption scope (both
  rekey paths over `SoilRekey`, stamps cleared, cover rules); the import chooser (decision 3,
  `ImportFlow` + `ImportKeying.toScope`); Export's Keep label + scope restamp; the U4 debug item
  removed.
- Tests: the import chooser's outcome table incl. the downgrade rule (typed == global → `GLOBAL`),
  the sheet's row visibility by scope, `ImportKeying` plan for each branch.
- Walk: Sonnet for sheet/create visibility; **user checklist** for every SAF import (three
  branches over one foreign-key export made on the Manta or by U2's debug rekey) and the
  create-with-passphrase flow; Fable by hand for scope round-trips (GLOBAL → NOTEBOOK → GLOBAL,
  cover gone then back on next seal).
- **Questions at phase start:** (1) New Notebook: radio row vs a checkbox "Own passphrase" —
  default radio (the type radio's shape). (2) whether the scope rows also appear on the notebook's
  own bar (og has `btnLock` for plaintext only — SN has no plaintext, so default **no**).

### U6 ⬜ — Recovery + the raw-path audit
- `NotebookRecovery` per D5; `NotebookSession`'s key-failure classification + once-per-launch
  latch; the `peekOrLoad` audit; debug "Break keying".
- Tests: the failure classifier, the offer's decision table (scope × which key worked), the latch.
- Walk (**Fable by hand**): Break keying on a GLOBAL notebook → open → Can't open → Try → prompt
  the throwaway → Repair and open → opens; same on a NOTEBOOK-scope one → opens without repair;
  a wrong passphrase ×3 → lockout text; Cancel → back at the library with the notebook intact.
- **Questions at phase start:** none pending.

### U7 ⬜ — Docs + freeze (no code, no code review)
- New `docs/encryption.md` (the arc's reference: the key model, the recovery key, the screen,
  rotation's journal + three resume paths + quarantine, scope + the resolver + the open-site table,
  the doors, recovery, the failure table, traps, futures) — Sonnet on a Fable outline, Fable
  read-back. Pointers in `docs/library.md` (the door, the lock card), `docs/notebook.md` (prompt on
  open, link follow), `docs/export.md` / `docs/import.md` / `docs/backup.md` / `docs/cloud.md`
  (their scope rules), `docs/extensions.md` (stores rotate with the global key — one line).
- App `CLAUDE.md` (module/screen lines, standing rules: every open resolves through `KeyResolver`;
  a rekey is `SoilRekey` or nothing), root `CLAUDE.md` branch bullet, `PARITY_BACKLOG.md` item 1
  → done with a pointer here, memory, this file's ledger + status line → **ARC COMPLETE + FROZEN**.
- **Questions at phase start:** none.

### Planner calls the wizard didn't cover (implementer follows; the user can override at phase start)
- Rotation ends in a **relaunch through Bootstrap** rather than an in-process index reopen
  (`SnIndex.dao()` consumers are not audited for cached DAOs; the relaunch is og's restore precedent).
- The picker **hides** locked notebooks; `readOnce` answers null for them unless unlocked this process.
- The cloud leg **skips and counts** a `NOTEBOOK`-scope notebook whose raw key is not cached.
- Scope/passphrase changes clear the notebook's backup stamps rather than bumping `updatedAt`.
- `PassphraseRules`: ≥ 8 chars after trim; no character-class rules; identical-to-current refused.
- The rotation marker stores the new passphrase in `PassphraseStore` (EncryptedSharedPreferences,
  the same posture as the cached global) — never in the index, never in an Intent.
- og's `abandonMarker` (drop a half-done rotation) is **not** built — it has no UI in og either.

---

## Protocol summary (from RATTA_PLAN.md — so that file need not be loaded)

- One phase per session. Flip `⬜ → 🔄` at start, ask that phase's questions wizard-style (one
  at a time) before any code, record an **Outcome** in the ledger at close.
- Commit + push only when tests pass or the user gives the all-clear, after docs/memory are in.
- **File tools can land a raw NUL byte** — byte-scan changed files for `\x00` before calling a
  phase done.
- Every SQLCipher open routes through `SoilCrypto`; creation is its own named path; a wrong key
  reports corruption **without** deleting (`NonDestructiveOpenHelperFactory`); never delete a
  non-empty `-wal`; `updatedAt` is sacred; passphrases never logged, never in an Intent, never in
  the index, never in a name or a message (`ExportKeying.sqlLiteral` is the one SQL-literal path).
- `IndexGuard.ready` first thing in every Activity that touches the index; `if (IndexGuard.bounced)`
  in `onDestroy`.
- Chrome: action buttons on the top bar after Cancel; bottom bars pager-only (the new library
  button is an *entry point*, placed by the user's call); GONE never disabled; toast only confirms,
  a dialog explains; a screen that explains then leaves does so on the dialog's dismiss
  (`Dialogs.confirm`, never `problem` + `finish`); TopGuard 0 on Ratta; Tabler icons only; no
  colour; a dialog root stays transparent (the `<shape>` stroke has no padding).
- **Unlock never hides the IME** (Ratta hardware keyboards type only while it is shown) — the same
  rule for every passphrase dialog this arc adds.
- Device: Supernote swallows `adb shell input text` — typing is on-screen-keyboard taps from
  screencap coordinates (dialogs shift ~350 px under the IME); a SAF pick cannot be driven by adb;
  verify `mResumedActivity` before any screencap conclusion; the Nomad sleeps behind a PIN (black
  screencap = ask the user); walk the **`.dev`** package; HOME before re-`am start`; walk-agent
  false failures — re-drive any FAIL by hand.
- `adb push` into `Android/data` deletes the target — push to `/data/local/tmp` then `shell cp`.

## Standing traps specific to this arc (from og's encryption work + SN's own)

- **`sqlcipher_export` drops `PRAGMA user_version`** — copy by hand and re-verify from the
  finished file (og's bricked G6 notebooks). `ExportKeying` already does; `SoilRekey` inherits it.
- **`PRAGMA rekey` is unreliable on device** — export-and-key only.
- **`KeyMaterial.peekOrLoad` can hand back a stale raw key** for a file not opened this session —
  verify + invalidate before any raw open (V4).
- **Clearing only the Keystore leaks the RAM copy** for the process lifetime — `invalidate`
  clears both (the Paper Phase-6 lesson, already in `KeyMaterial`).
- **A meta refresh sources scope from the index, never from the previous meta row** (og's
  meta-refresh-wipe trap).
- **Backup stamps compare `updatedAt`** — a rekey leaves it untouched, so a rotation or scope
  change that forgets the stamps leaves old-key copies in every backup forever.
- **The index cannot be closed under a live screen** — after `closeForRotation` the Encryption
  screen touches nothing but dialogs and exits only through Bootstrap.
- **A walk that rotates or forgets without the key written down locks the dev library** — the
  first step of every crypto walk is the Reveal dialog.
- **The Nomad's dev library has real test data** (the tag/calendar/events fixtures of arcs 21–24)
  — quarantine and Break-keying walks run on a throwaway notebook created for the walk.

## Ledger

### U1 — Outcome (2026-09-05)
- **Built:** `encryption/EncryptionActivity` + `activity_encryption.xml` (Backup's shape: back arrow +
  title, 1dp rule, scroll body; status lines, Reveal, Forget; `btnChange` present but GONE for U3),
  manifest entry, `sn-screen` `ic_lock` (Tabler `lock`), library `btnEncryption` between Backup and
  Import (tooltip, shelf-hidden with the group), `ObjectDao.countAliveNotebooksByScope` +
  `IndexRepository.countGlobalNotebooks` (the count reads `keyScope`), 18 strings, both debug-menu
  duplicates removed (`FakeObjectDao` gained the count).
- **Wording:** og verbatim with "global passphrase" read as "recovery key" (phase-start question 1).
- **Forget kills the process** after `finishAffinity` (the debug item's precedent): `SnIndex` has no
  close, and a relaunch into the live process would answer READY with no key cached — recorded here so
  U3's relaunch-through-Bootstrap design does not "simplify" it away.
- **Walk (Nomad, by hand):** door → screen ("Recovery key: set", "44 notebooks use this device's
  key") → Reveal shows the key monospace → Copy toasts → Forget confirm → process gone → Bootstrap →
  Unlock → the key pasted from the clipboard (long-press the field → Paste — a 45-character key needs
  no on-screen typing) → "Checking…" → library → a notebook opens (raw keys re-derived) → Encryption
  screen reads "set" again. Row measured: `[Backup] [Encryption] [Import]` fits with the pager centred.
- **Tests:** 989 in `:app`, 0 failures (no new pure logic beyond a count).
- **Trap for the next walks:** Reveal → Copy puts the key on the system clipboard, and it survives the
  process kill — the cheapest way to feed Unlock on a Supernote.

### U2 — Outcome (2026-09-05)
- **Built (`crypto/`):** `SoilRekey` (`rekeyInPlace` = cold check → WAL absorb by raw open +
  `wal_checkpoint(TRUNCATE)` + sidecar sweep, refusing a WAL that stays non-empty → `ExportKeying.
  exportAndKeyToPrimary` into `X.rekey.tmp` → `RekeyCommit.commitReplace` → `KeyMaterial.invalidate`;
  `recoverGarden` / `recoverOne` / `hasLeftovers`), `RekeyNames` (`.rekey.tmp` / `.old.bak`, the
  leftover grouping), `RekeyCommit` (og's order over an injected `RekeyFs`, five outcomes — Committed /
  RefusedLiveWal / OriginalNotMoved / RolledBack / BothKept), `RekeyRecovery` (the D1 decision table +
  an executor that re-verifies the survivor before every delete), `RekeyFs` + `RealRekeyFs` (fsync'd),
  `PassphraseRules` (≥ 8 after trim, confirm, not-current), `PassphraseCache` (single-use RAM).
  `ExportKeying.exportAndKeyToPrimary` gained `restamp` (false for a store / the index — no meta).
- **Doors:** `SnIndex.closeForRotation()` (checkpoint, close, `instance = null`, under the prepare
  mutex — the one door that closes the index); **`SnIndex.ensureReady` recovers the index's own
  leftovers before the probe** — a missing `notesprout.db` with a `.rekey.tmp` / `.old.bak` beside it is
  never a fresh install: recovered with the cached passphrase, else `DAMAGED_FILE` (nothing touched).
  Bootstrap runs `recoverGarden` (verifier = the cached global) right after the index opens, before
  the K1 compaction. Forget also clears `PassphraseCache`.
- **Debug (`.dev` only, `library/RekeyProbe` + two `DebugMenu` items):** *Rekey one notebook
  round-trip* and *Break a rekey commit* (variant A tmp verifies / B tmp garbage; kills the process
  like Forget). Both stay for U3–U6's walks.
- **Tests:** 1014 in `:app` (25 new — `RekeyCommitTest`, `RekeyRecoveryTest`, `PassphraseRulesTest`
  incl. the cache; `FakeRekeyFs` logs every op so the ORDER is pinned, not just the end state).
- **Walk (Nomad, Fable by hand, throwaway notebook `20260905_142626`):** round-trip PASS — `integrity_
  check` ok / 2 rows / 24576 B before, mid (under the throwaway) and after; global no longer opens
  mid-way, throwaway no longer opens after; raw key invalidated and re-warmed; ~4.0 s per direction
  (two KDF verifies + the copy on a 24 KiB file — the KDF dominates); the notebook opens on the raw-key
  path afterwards. Break A → kill → Bootstrap `RESTORED_TMP`, only the `.soil` left, opens. Break B →
  kill → Bootstrap `RESTORED_BAK`, only the `.soil` left, opens.
- **Planner notes for U3:** (1) rotation's verifier must accept BOTH the cached global and the marker's
  new passphrase (`recoverGarden` takes a `(File) -> Boolean` for exactly this); (2) the index-leftover
  guard in `ensureReady` uses only the cached global today — U3's marker path extends it; (3) a rekey
  costs two KDF verifies per file on top of the copy — the progress dialog's per-file estimate on the
  Nomad is ~4 s + copy time, not "instant".

### U3 — Outcome (2026-09-05)
- **Built (`crypto/`):** `RotationMarker` (kotlinx JSON in `PassphraseStore` — `pendingIds`,
  `newPassphrase`, `minted`, `total`, `notebookCount`, `startedAt`, `quarantined`; `commit()` writes,
  never `apply()`), `RotationPlan` (pure: `order` notebooks → `ext:<pkg>` stores → index LAST, `kindOf`,
  the `decide` / `afterFailure` outcome tables, `commitSteps`, `resumeCandidates`), `GlobalRotation`
  (`start` / `resume` / `commit`; cheap "still under the old key" via the cached raw key before any KDF;
  `ExtensionStores.closeAll()` before the first store; `BackupStore.clearAllStamps()` — BOTH maps —
  then `SnIndex.closeForRotation()` before the index; quarantine = `IndexRepository.quarantine` +
  `clearStamp`; each file under `NonCancellable`; Cancel honoured between files). `trustedVerifier`
  accepts the cached global OR the marker's key — Bootstrap's `recoverGarden`, rotation resume and the
  index-leftover guard all use it (U2's planner notes 1 + 2).
- **A resume re-lists the library** (a planner addition): the library is reachable between a Cancel
  and a Resume, so a notebook created/imported since (`createdAt`/`updatedAt ≥ startedAt`, or a raw key
  that still opens it) and every store on disk join the list — nothing is left under the old key.
- **Three resume paths, all walked:** the banner (`btnResume`; Change + Forget GONE meanwhile) ·
  `BootstrapRoute.afterOpen` (key screen first, then the marker, then the library; shared by Bootstrap,
  Unlock and the recovery-key screen's Continue — Unlock no longer hardwires the library) ·
  `SnIndex.openUnderMarkerOrUnlock` (the marker's key tried when the cached global does not open the
  index; `finishOpen` also self-commits when the cached global already IS the marker's key).
- **UI (Opus on a brief):** Change passphrase… → Current passphrase (string match + Crockford fold,
  wrong entry keeps the dialog, IME never touched) → New passphrase (Generate / Choose my own, two
  fields, `PassphraseRules` verdicts inline) → confirm with the backups warning (+ "shown once on
  restart" for a minted key) → progress "Re-keying n of t…" + name / "Extension data" / "Library
  index", Cancel swaps the last line to "Stopping after this file…" and goes GONE → Passphrase changed
  (Back up now / Done → `BootstrapActivity.relaunchIntent(thenBackup)`; `EXTRA_THEN_BACKUP` rides the
  recovery-key screen into `LibraryActivity`, which opens Backup once per cold launch) · Change paused
  (index still open — no relaunch) · Change interrupted (relaunch on dismiss: the index may be closed).
  Two dialog layouts (`dialog_passphrase_current` / `_new`); 43 strings. Unlock's wording now says
  "recovery key — or the passphrase you chose".
- **Tests:** 1035 in `:app` (21 new — `RotationMarkerTest`, `RotationPlanTest`, `BootstrapRouteTest`,
  `BackupStoreTest` both-map + `clearAllStamps`, `RawKeyDerivationTest` platform/loop agreement).
- **Walk (Nomad, Fable by hand):** Reveal matched the key on file → Change → paste current → Generate
  → Change → "Re-keying 2 of 52…" (45 notebooks + 6 stores + index, ~4.4 s/file) → Cancel at 5 →
  paused, banner up, Change/Forget gone → `am force-stop` → relaunch lands on the Encryption screen
  (path 2) → Resume from 6 → force-stop at 8, Garden clean → relaunch → Resume → committed at 15:15
  (`minted=true, quarantined=0`) → Back up now → Bootstrap → **new key shown once** → library → Backup
  screen → run copies everything (stamps cleared). Then the typed leg: paste current → Choose my own →
  `walkpass1` twice via the on-screen keyboard → committed in 3m53s, native heap flat at 26 MB → Back
  up now → library (no key screen — typed) → 44 copied / 7 stores / index → notebook + calendar open →
  Forget → Unlock with `walkpass1` → library. **The Nomad dev library's key is now `walkpass1`.**
- **The bug the walk found (fixed in this phase): a cold-derive burst kills the process.** The first
  post-rotation backup died at 10/104 — `Scudo OOM: exhausted 256M for size class 288/352`, native heap
  42 → 663 MB in 20 s. Cause: `RawKeyDerivation`'s hand loop calls Conscrypt `Mac.doFinal` 256,000× per
  key and every call leaves a native HMAC context for the GC (~80 MB churn per derive); the commit's
  `KeyMaterial.clearAll` made every file cold and the backup's compaction opened 45 in a row, each
  spawning a background warm on `Dispatchers.IO` — several concurrent derives exhausted the allocator's
  per-size-class budget. Latent since arc 1 (raw keys were always warm before). Fix: `deriveKey` goes
  through the platform `SecretKeyFactory("PBKDF2WithHmacSHA512")` (one native call, byte-identical —
  the loop stays as the fallback, and `KeyOpener` verifies a raw key before use so a provider mismatch
  could only cost a slow open), and `KeyOpener.warm` runs on `limitedParallelism(1)`. Measured: a
  derive is **8.8 s** on the Nomad via the platform (vs ~2–3 s for the loop) but native stays flat;
  after a rotation the ~45 warms drain serially in ~7 min of background CPU while opens fall back to
  SQLCipher's own KDF (~1.5 s each). Recorded here so nobody "optimises" the loop back in.
- **Planner notes for U4:** (1) a rotated GLOBAL notebook is unopenable mid-rotation until commit —
  `KeyResolver`'s marker second candidate is what fixes that (D2's last bullet), and `ExtensionStores.
  open` wants the same second candidate; (2) `IndexRepository.quarantine` is U3's minimal
  `setEncryptionState` — U4 grows it with the cover-null + `notebook_meta` restamp; (3) the Unlock hint
  is now generic — the `NSPT-…` hint is gone on purpose.

### U4 — Outcome (2026-09-05)
- **Phase-start answers:** the picker shows a **lock row that prompts** (not hide); no cloud-leg
  wording — `SelfContainedSnapshot` skips the open when no WAL was copied (a sealed notebook has
  none), so a locked notebook backs up without a key; a locked file WITH a leftover WAL is refused at
  `Slog` level (`LockedFile`) and counted failed like any other unabsorbed WAL.
- **Built (`crypto/`, Fable):** `KeyScope` (typed face of the column; `of(null)` = GLOBAL) ·
  `NotebookUnlocks` (per-process id set; `mark/has/forget/clear`) · `KeyResolver` (pure `decide`
  table: GLOBAL → `Passphrases([global, markerNew])` — the marker second candidate — / `NoKey`;
  NOTEBOOK → `Unlocked(rawKey)` only when unlocked this process AND the raw key is cached, else
  `NeedsPrompt`; `forOpen` wires the stores) · `KeyOpener.roomFactoryFor(…, resolved)` (one
  candidate = today's cold path; two = verify each; `Unlocked` verified, stale → invalidate + forget)
  · `NotebookPassphrasePrompt.ask(activity, id, name): String?` — the ONE dialog: `PassphraseCache.
  takeOnce` first (still verified), verify-then-accept loop, bucket = notebook id, entry row GONE +
  countdown while locked out (error cleared when it lifts), IME never hidden, field auto-focused; on
  success `recordSuccess` + `NotebookUnlocks.mark` + `KeyOpener.warm`, returns the typed passphrase.
- **Seams:** `SoilDatabase.open(…, resolved)` + `suspend resolve(context, id)` (index scope →
  resolver); `readOnce` answers null for a locked notebook and has an overload taking a `Resolved` —
  **a caller that just prompted passes `Passphrases(typed)`; the raw-key warm is ~9 s on the Nomad so
  an immediate read can never wait for `Unlocked`.** `ObjectSummary.keyScope` (in `SUMMARY_COLS`, so
  every listing sees scope blob-free); `ObjectDao.keyScopeOf`; `IndexRepository.keyScope(id)` +
  `setEncryptionState(id, scope)` (column, cover blob nulled for NOTEBOOK, both stamps cleared,
  `NotebookUnlocks.forget`; `updatedAt` untouched) — `quarantine` is now its NOTEBOOK case.
- **Every open site (Opus, three lanes):** notebook screen (`NotebookActivity.keyFor`: GLOBAL →
  `resolve`; NOTEBOOK → overlay down, prompt, overlay up; cancel = clear last-open pointer + finish,
  no dialog; `OpenResult.Failed.keyed` picks the user-grade sentence) · `NotebookSession.open(
  resolved)` · `refreshMeta` sources `keyScope` from the index row, `cover = null` (SN never stamped a
  meta cover) · `captureCover` skipped for NOTEBOOK (both onStop and close) · `LinkFollowFlow`:
  follow AND walk-back prompt for a NOTEBOOK target, cancel on walk-back pushes the trail entry back;
  `PassphraseCache.storeOnce` before `leaveFor` = one prompt per hop · link picker: lock row visible,
  tap → prompt → `openDrill(summary, passphrase)`; `ForeignPageSource(passphrase)` carries it for the
  source's lifetime (re-opens after every `sealAsync`); `PickMode.NOTEBOOK` (link-to-notebook) does
  not prompt — nothing is opened · library grid: `CardItem.Notebook.locked`, `paintLock` = `ic_lock`
  at ⅓ card width, cover fetch skipped (`LibraryActivity`, `LibrarySearch`, picker) · export:
  `ExportActivity.resolveSourceKey` prompts ONCE at the head of `loadCandidates`, `sourceKey` threaded
  into `ExportOpen.readOnly(…, resolved)` via `ExportArtifact.prepare` / `ExportRender.render` /
  `ExportText.assemble` / `DocumentPdfRender.render`; `Guard.LOCKED` / `Problem.LOCKED` +
  `export_notebook_locked_body` for the resolver-less path; `keyedArtifact` hands `ExportKeying`
  the SOURCE file's passphrase (typed for NOTEBOOK, session for GLOBAL) · backup: `Candidate.keyScope`,
  `compactPass` is suspend, **skipped for NOTEBOOK**, GLOBAL via `resolve`; `NotebookImport.
  refreshMeta` + `ExportArtifact.stampExportedAt` source scope from the index · Forget clears
  `NotebookUnlocks` · rotation's list was already scope-filtered.
- **Debug:** *Change key scope (debug)* — GLOBAL → NOTEBOOK (new + confirm under `PassphraseRules`,
  `SoilRekey.rekeyInPlace` → `setEncryptionState` → `PassphraseCache.storeOnce`) and NOTEBOOK →
  GLOBAL (the real prompt verifies, then rekey back); refuses an open notebook; "Re-keying…" dialog;
  `recreate()`. Kept for U5's sheet, then removed.
- **Tests:** 1049 in `:app` (14 new — `KeyResolverTest` decision table, `KeyScopeTest`,
  `NotebookUnlocksTest`; `FakeObjectDao` grew `keyScopeOf` + the summary column).
- **Walk (Nomad, Fable by hand + adb):** debug → `20260905_142626` → `notebook1` typed on the
  on-screen keyboard → **lock card** → tap → opened silently on the parked passphrase (cold open,
  warm queued) → back → tap → **prompt** → `wrongpass` → inline error, dialog + IME stay → `notebook1`
  → raw-key open (warm had landed) → back → three wrong entries → **"Too many attempts. Try again in
  26 s."**, entry row GONE → lifted → Cancel → library, no dialog → long-press → Export → **prompt** →
  Export screen with candidates → Backup → *compact pass skipped* in the log, **1 copied** (stamps
  cleared by the scope change), 43 up to date → debug → NOTEBOOK → GLOBAL (prompt verified, rekey)
  → plain card, no lock, no cover → opens prompt-free under `walkpass1`. Nomad library left every
  notebook GLOBAL. Not driven by adb (user checklist): the link follow / walk-back, the picker lock
  row + drill, the export's SAF pick, the cloud leg with a locked notebook.
- **Post-walk fix (the user's finding: "sometimes it asked, sometimes not"):** the parked hand-off was
  taken by whichever prompt came first — an Export or a picker drill spent it silently and the next
  notebook open asked. Now **only the notebook screen's open takes it** (`NotebookPassphrasePrompt.
  takeParked`, called by `NotebookActivity.keyFor`; `ask` never consults the cache) and a parked value
  **expires after `PassphraseCache.TTL_MS` = 60 s**. Re-walked: scope change → Export prompts → open
  after the window prompts. The other designed silent path — resuming an already-open notebook
  screen (home and back) — is not an open and stays silent, as in og. `PassphraseCacheTest` (4).
  1053 tests.
- **Planner notes for U5:** (1) the debug item IS the D4 core (rekey → `setEncryptionState` → cache
  seed) — the sheet's rows call the same three steps; (2) og's downgrade rule (typed == global →
  stays GLOBAL) is not enforced by the debug item — the sheet and the import chooser must; (3) the
  "Keep encryption" label + scope restamp in `ext-soil` / `ExportKeying` are still U5's; (4) the
  three export renderers take `resolved` — a new export path must thread it too; (5) a NOTEBOOK
  notebook's `.soil` export carries `keyScope = NOTEBOOK` in its meta, which is what the import
  chooser keys on.
