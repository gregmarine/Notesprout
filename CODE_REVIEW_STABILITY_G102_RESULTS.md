# G102 Automated Test Results — 2026-07-21 11:53

## Batch 1 — SoilMigrator orphan recovery (Phase 1 P0-1/2/3)
- **T1.1** `.enc.tmp` orphan (main missing) → recovered byte-identical, boot→Main, no crash. **PASS**
- **T1.2** `.old.bak` orphan (mid-swap kill) → recovered byte-identical. **PASS**
- **T1.3** `.rekey.tmp` orphan → recovered byte-identical. **PASS**
- **T1.4** stale `.old.bak` + main present → bak dropped, main untouched. **PASS**
- TestAlpha opens normally after recovery (not an empty stub).

## Batch 2 — Restore mid-commit recovery (Phase 1 P0-5, RestoreEngine.recoverInterrupted)
- **T2.1** aside present + live index MISSING (kill before install) → rolled back, index+library restored, aside cleaned, no crash. **PASS**
- **T2.2** aside present + live index PRESENT (commit finished) → aside discarded, live index untouched, no crash. **PASS**

## Batch 3 — Bootstrap resilience / no-wipe (Phase 2 P1-5, Phase 1 P0-4)
- **T3.1** corrupt/garbage index → **unlock prompt shown, NOT a crash; corrupt index NOT deleted (size+md5 unchanged)** — the critical no-wipe assertion. **PASS**
- Launch #2 stable (unlock prompt again, no crash-loop). **PASS**
- Recovery: restoring the good index self-heals (re-derives raw key from cached passphrase), library returns with all notebooks. **PASS**

## Batch 4 — Ghost-file guard (Phase 3 P2-11)
- **T4.1** deleted a notebook's `.soil`, kept its index row, tapped the card → toast shown, stayed on library, **no empty stub `.soil` created**, no crash. **PASS**

## Batch 5 — Content-corruption resilience (Phase 2 P1-3 blob guards + zlib bail)
- **T5.1** injected 3 corrupt blobs (fallback heading + text + link, garbage bytes) + 1 valid control heading; opened the notebook → **page loads, valid "Hello World" heading renders, corrupt objects silently skipped, no crash**; reopen (launch-restore replay) also clean — no crash-loop. **PASS** (screenshot: shots/t5-corrupt-page.png)

## Batch 6 — Real migration round-trip (Phase 1 P0-1/2/3 commitReplace, live path)
- **T6.1** Change Passphrase on TestGamma (test1234→newpass99) via UI → `SoilMigrator: rekeyInPlace complete`; **no orphan tmp/.bak left behind** (commitReplace cleaned up); md5 changed; user_version preserved (4); notebook content intact; **old passphrase rejected, new passphrase opens** on Mac sqlcipher AND in-app. **PASS** — validates the real migrator swap end-to-end on device.

## Batch 7 — Backup structure + WAL sidecar (Phase 3 P2-8)
- **T7.1** local backup run → "2 backed up, index copied"; both `.soil` + index in `dev/`; **no leftover `.part`/`.old`** (atomic write). **PASS**
- **T7.2** NOTEBOOK-scope notebook with non-empty `-wal` → **`-wal` sidecar copied** to backup; GLOBAL notebook (checkpointed) has **no** sidecar copied. **PASS**
- **T7.3** re-backup after removing the live `-wal` → **stale sidecar deleted** from backup (prevents fresh-soil + old-wal corruption on restore). **PASS**

## Batch (index) — Clipboard startup-crash guard (Phase 2 P1-2)
- **T-clip** injected a clipboard row with malformed items into the encrypted index → app boots to library, no crash, no crash-loop on relaunch. **PASS**

## Restore round-trip — real RestoreEngine.restore (Phase 1 P0-5, live path)
- Configured local backup, backed up, then **Restore from Backup** end-to-end: "Restored 2 notebooks" → `restore_replaced` cleaned → app restart → NEEDS_UNLOCK prompt (keys cleared) → recovery key unlocks → library back (2 notebooks), no crash. **PASS** (validates fetch→probe→free-space→aside-swap commit→key-clear→restart→unlock).

## Import path-traversal / ATTACH-injection (Phase 1 P0-7)
- Imported a crafted plaintext `.soil` whose `notebook_meta.notebookId` = `../../../databases/evil` and folderPath id = `../../evilfolder`:
  - notebookId **sanitized to a fresh UUID** (`10f450cf…soil` in Garden) — no traversal path written.
  - **No `evil*` file created outside Garden; no `databases/evil` escape.**
  - Evil folder id rejected → notebook landed safely at top level. No crash. **PASS**

## Test artifacts / notes
- "EvilBook" left in the dev library (harmless import).
- TestGamma's card shows a year-2286 date — an artifact of my WAL-sidecar test (I set updatedAt=9999999999999 to re-flag it for backup), not a bug. Cosmetic; can be reset on request.

## MANUAL (stylus) — run with Greg on G102, 2026-07-21
- **M1 Link copy/paste (paste-1555 regression)** — TestAlpha: draw → lasso → create link → lasso link → copy → paste → paste again. Both pastes landed, **no crash**, logcat clean (no SQLiteConstraint/1555). **PASS**
- **M2 Sticky note copy/paste (paste-1555 regression)** — TestAlpha: create sticky → lasso → copy → paste → paste again. Both landed, **no crash**, logcat clean. **PASS** (link+sticky both green → shared remapSubtreeRows helper validated)
- **M3 Shape transform — Scratch Pad (columnar dead-write, P2-1)** — draw shape → lasso-drag (columnarize) → resize → leave & return. Kept the transform (user-visible); DB confirms row is columnar (data len 0) with geometry in typed columns. **PASS**
- **M4 Shape transform — Calendar (P2-1, separate Activity)** — same flow on a calendar page. Kept the transform; DB confirms columnar row with geometry in typed columns. **PASS** (2 of the 3 hosts confirmed; DayDetail shares the identical fix)
- **M5 Sticky editor process-death durability (P2-12)** — write "SURVIVES" in a sticky editor → HOME → `am kill` → reopen. **FAIL (real bug found on-device).**
  - Diagnosis (logcat + user obs): the editor-side onSaveInstanceState restore DOES work — the OS recreated the editor with "SURVIVES" briefly visible. But the app's cold-launch **surface-restore** flow (Bootstrap → SurfaceStack rebuilds Notebook) then tears down the naturally-restored editor and rebuilds the notebook fresh, **discarding the restored content before it's persisted to the sticky's DB row.** Net = silent loss (the exact P2-12 symptom the fix was meant to close).
  - No TransactionTooLarge; not a crash/corruption. Lowest-severity item in the review (rare: process death behind an open editor).
  - Correct fix (redesign): persist the editor's in-flight content directly to the sticky's DB row on `onStop`/`onSaveInstanceState`, independent of the return-to-host round-trip, so a fresh surface-rebuild finds it already saved. Bundle-only approach is insufficient given surface-restore.

## M5 REFIX + retest (StickyEditDraft) — 2026-07-21
- Redesigned: editor writes a durable **file-backed draft** on `onStop` (not the instance-state Bundle — the "SURVIVES" draft was 39 KB, near the TransactionTooLarge ceiling); each host applies a leftover draft to the sticky's DB row on page load (`StickyEditDraft.applyTo`), then deletes it. New `StickyEditDraft.kt` + wiring in all 4 hosts (Notebook/Scratchpad/Calendar/DayDetail).
- Retest on G102: write "SURVIVES" → HOME → `am kill` → reopen → **SURVIVES restored.** Verified: draft file consumed (deleted) after page load; sticky's stroke children now persisted in TestAlpha's `.soil` (real ink rows). **PASS** — content is written to the notebook DB, not held temporarily.

## M5 FINAL — real-time persist (supersedes both earlier attempts) — 2026-07-21
- Device testing killed two approaches before this one: (1) instance-state Bundle — dropped by TransactionTooLarge (the "SURVIVES" canvas was 39 KB) and then discarded by the cold-launch surface-restore; (2) plaintext file draft — worked, but Greg flagged it isn't encrypted at rest (a real leak for private notebooks).
- FINAL design: the sticky editor persists straight to the sticky's own **encrypted** DB in real-time — the notebook `.soil` (keyed from the in-memory `KeySession`, never an Intent) or the already-open encrypted global index — debounced ~0.6 s on each content change + a flush on `onStop`. No plaintext file. New: `StickyNoteEditorActivity.intent(...)` carries host/notebookId/encrypted/bbox; `persistNow()`/`notebookDb()`; `schedulePersist()` hooked into pushHistory + undo/redo. Draft file + host apply/clear wiring removed.
- Retest on G102 (TestAlpha, GLOBAL-encrypted): wrote "SURVIVES2" → verified 14 stroke child-rows already in the encrypted `.soil` **while the editor was still open** (only readable with the recovery key = encrypted at rest) → HOME → `am kill` → reopen → **restored clean.** No plaintext draft file exists. **PASS.**
- Wired into all 4 hosts (Notebook tested end-to-end; Scratch Pad/Calendar/Day-note share the same path — spot-check recommended).
- **M6 Scratch Pad sticky (real-time persist, index path)** — wrote "SCRATCH1" → verified 33 KB of content in the encrypted `scratchpad` index table while backgrounded (recovery-key only) → `am kill` → reopen → **restored clean.** **PASS.** Confirms the index-backed path (shared by Calendar + Day-note); both persistence mechanisms (.soil + index) now validated on-device.
