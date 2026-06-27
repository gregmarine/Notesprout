# Notesprout — Backlog

> Consolidated backlog of deferred / future items harvested from the per-feature implementation
> plans as they were completed and retired. Each shipped plan was removed once done; the items below
> are the things those plans explicitly punted. **The full retired plans (with detailed design notes,
> file paths, and session breakdowns) remain recoverable from git history** if you need the context
> behind any item.
>
> Standing design docs that are **not** folded in here (read them directly):
> - `SUPERNOTE_SUPPORT_PLAN.md` — full Supernote (Ratta) ink-path design, not started.
> - `NOTEBOOK_SIZE_RESEARCH.md` — `.soil` size-reduction + backup-compaction research (no plan yet).
>
> Nothing here is scheduled. Pull an item into its own plan before building it.

---

## Sticky Notes — deferred items

> From the retired `STICKY_NOTE_PLAN.md` (all 7 sessions shipped). Items below were explicitly
> deferred during build or noted as non-goals for v1.

- **Cross-size content scaling.** When the editor window size differs from the authored
  `contentWidth/Height` (rotation, cross-device paste), proportionally rescale embedded content
  instead of rendering as-authored + clipping.
- **In-editor autosave / process-death durability.** The editor holds content in memory until close;
  an OS kill mid-edit loses unsaved strokes. Persist incrementally (or on `onPause`) so a kill is
  recoverable. (Short sessions, same process as the paused host — acceptable for v1.)
- **Multi-page sticky notes.** D1 chose single page; multi-page would need in-window page nav/add/delete.
- **Native text/line insertion inside the editor.** D2 chose pen / eraser / lasso + paste only.
  Adding the text and line insertion buttons inside the window is a follow-on decision.
- **Content affordance on the icon.** Currently one static sticker icon regardless of whether the
  note has content. A "has content" indicator or mini-preview would improve discoverability.
- **Sticky-note content in search / TOC.** Content is hidden and intentionally excluded from ML Kit
  search and page-name/TOC rules for v1. An explicit opt-in search over embedded content is future work.
- **Live undo inside the editor.** D3: only one before/after undo action per window session. Live
  in-window undo (stroke-level) is a follow-on feature.
- **Endnote pagination / fit.** S6 renders each note's content onto a single endnote page sized to
  the content; content larger than one page is not split across multiple endnote pages (acceptable for v1).

---

## Scratch Pad — phase 2 / deferred

> From the completed `SCRATCHPAD_PLAN.md` (all 8 sessions shipped). Items below were explicitly
> deferred during build or noted as non-goals for phase 1.

- **True geometric crop (Send to Scratch Pad).** Current "Crop to fit" translates the selection to
  origin `(0,0)` and lets the canvas clip the overflow. No geometric point-cutting of strokes that
  straddle the boundary. A proper crop would bisect strokes at the page edge.
- **Per-pad pen style picker.** Phase 1 reuses the global `ToolPreferencesManager` pen settings.
  A scratch-pad-local pen style (separate from the notebook pen) would need its own prefs key and
  toolbar picker.
- **Snapshot fast-load.** `PageData.snapshot` is persisted on `onSnapshotReady` but the load path
  always does a full `buildRenderBitmap` (no snapshot-first shortcut). Could skip bitmap rebuild
  when a valid snapshot exists.
- **Move-vs-copy on "Send to Notebook".** Current behaviour: Send is a copy — content stays in the
  scratch pad after sending. A "move" variant (auto-clear the sent page after transfer) may be useful
  and was deferred pending UX decision.
- **Scratch-pad-only undo persistence.** There is no undo/redo on the scratch pad. If undo is added,
  persisting the undo stack across restarts (like notebooks do via `undo_redo_state`) would be needed.
- **Scratch pad snapshot in recents / cover.** No thumbnail is generated for the scratch pad in the
  recents bar or anywhere else in the UI.
- **Encryption path for global DB.** The scratch pad is permanently plaintext because `notesprout.db`
  is never encrypted. If per-user DB encryption is ever added, the scratch pad inherits it
  automatically; the existing `awaitEncryptionClipboardConfirm` warning would need revisiting.

---

## Toolbar Customization — Session 8 (UI/UX polish)

> From the retired `TOOLBAR_CUSTOMIZATION_PLAN.md`. Core Sessions 1–7 shipped (feature is live and
> documented in `CLAUDE.md` / `docs/toolbar.md`). Session 8 was a living idea-backlog for *feel*,
> revisited once the feature was usable in hand. (One Session-8 idea — **pin the gear button so it's
> always shown** — already shipped.)

- **Split-panel "Customize Toolbar" dialog (grid, not list).** Redesign into two stacked grid panels:
  a top **"Showing"** panel (visible buttons, arrangeable in order) and a bottom **"Hidden / Available"**
  panel. Drag a button between panels to show/hide; drag within the top panel to reorder — making order
  + visibility a single direct manipulation instead of separate reorder + toggle steps. Considerations
  to work through: 2-D grid drag-reorder + reflow (more involved than the current list); still
  hand-rolled (no RecyclerView); how Close/gear pinning reads in a grid (locked cells?); how the
  mini-set picker composes with this layout; cross-panel drag must preserve the move-not-clone +
  key-stability contracts.
- **Remove the toolbar dividers.** Re-evaluate whether the auto-managed group dividers earn their keep;
  consider dropping them for a cleaner, calmer bar. Not yet decided.

---

## Multi-Page Selection — Phase 2

> From the retired `MULTI_PAGE_SELECTION_PLAN.md` (Phase 1 + its in-plan P2.1/P2.2 all shipped). These
> were flagged "do NOT build without discussion."

- **Range selection** — tap first, shift/long-press last to select a span, for faster large selections.
- **Selection count badge** on each grid page, or a footer summary while paginating.
- **Drag-to-reorder** multiple pages directly (vs. the before/after destination tap).
- **Export ordering choice** — page order vs. selection order for PDF/PNG export.
- **Persist selection** across leaving/returning to the index (currently cleared on exit).
- **"Invert selection"** control alongside Select All.

---

## Notebook Encryption — Phase 3 (deferred / found-along-the-way)

> From the retired `NOTEBOOK_ENCRYPTION_PHASE2_PLAN.md`. Phase 1 + Phase 2 shipped in full. Some gates
> below were written before the cross-notebook page-transfer and multi-page features landed — re-check
> each gate before assuming it's still blocked.

- **Cross-notebook page-copy plaintext-leak confirm** — warn when copying a page from an encrypted
  notebook into a plaintext one. *Originally gated on cross-notebook page copy/move existing.* That
  feature has since shipped (the retired Clipboard + Page-Transfer plan) **with a "smart" encryption
  warning already built in** (warn only when protection actually drops). **Re-verify whether this is
  already satisfied** before treating it as open work.
- **Bulk encrypt / decrypt (multi-select + whole-folder)** *(blocked)* — encrypt/decrypt many notebooks
  at once, and encrypt a whole folder. Gated on **multi-select for notebooks/folders** existing (today
  only multi-*page* selection exists, not multi-notebook/folder). Sub-decision when unblocked:
  per-notebook distinct passphrases vs. one shared passphrase for a NOTEBOOK-scope batch.
- **Biometric gate** — optionally require fingerprint/face to release the cached global passphrase.
  Needs `androidx.biometric` (new Gradle dependency → requires discussion).
- **Change passphrase from the open toolbar** — Phase 2 put re-key / scope-change in the context menu
  only; add an in-notebook toolbar entry if wanted.
- **Export password = notebook passphrase option** — let the user opt to reuse the notebook passphrase
  as the PDF password instead of entering a separate one.
- **Encrypted PNG / ZIP export** — password-protected archive for PNG exports (PDF is already covered).
- **Rotation as a foreground service / WorkManager job** — move global-passphrase rotation off the
  activity for very large libraries, with a persistent notification so it survives navigation.
- **Recents thumbnail for encrypted notebooks** — currently a lock icon; consider a user-set cover.
- **Search over decrypted content (opt-in)** — if full-text search of page content is ever added,
  design an explicitly opt-in, encrypted-at-rest index (none exists today, by design).
- **Cross-session undo/redo for plaintext notebooks** — encrypted notebooks already persist undo/redo
  inside the `.soil` (`undo_redo_state` table); extend the same store-on-close / read-on-open behaviour
  to plaintext notebooks (today plaintext only survives background→foreground via the sidecar, and
  loses history on explicit close). Same pattern, no crypto needed.

---

## Link Objects — Phase 2

> From the retired `LINK_OBJECTS_PLAN.md` (Phase 1 — page + notebook links — shipped in full).

- **File / website link targets** — explicitly out of scope in Phase 1; the next link kinds to add.
- **Snapshot-invalidation refinement** — a noted "future pass" to tighten how a link edit invalidates
  the page snapshot (Phase 1 reloads the page and clears selection; revisit for a lighter touch).

---

## Full Notebook Import — out of scope / future

> From the retired `FULL_NOTEBOOK_IMPORT_PLAN.md` (single-file import shipped in full).

- **Bulk / folder import** — import many `.soil` files or a whole exported folder set at once. Shares
  the same multi-select gate as bulk export / encryption Phase 3.
- **Encrypt-on-import for plaintext** — deliberately not offered (the user can lock after import).
  Revisit only if requested.
- **Cross-notebook link auto-repair beyond folders** — import recreates folders with the same IDs to
  help link resolution; full link rewrite/repair across an imported set is a separate effort.
- **Conflict-aware merge** — Replace overwrites; there is no page-level merge of an imported copy into
  an existing notebook.
- **Determinate progress for very large copies** — replace the indeterminate "Importing…" modal with a
  progress bar. Not needed for typical notebook sizes.

---

## Backup — future (restore is a separate effort)

> From the retired `BACKUP_PLAN.md` (Phase 1 backup — LOCAL/SAF + Google Drive REST — shipped in full).

- **Restore** — the inverse of backup (pull `.soil` files + index back onto a device). Explicitly out
  of scope for Phase 1; the backup format was designed to make it possible (display name + folder
  ancestry travel inside each `.soil` via `notebook_meta`).
- **Backup garbage collection** — deleting a notebook does not remove its backup file; a GC/prune pass
  for orphaned backups is future work.
- **Resumable / chunked Drive upload** — `Content-Range` + `308 Resume Incomplete` for large files
  (current path is a single upload). A documented future enhancement.

---

## Shape Objects — deferred items

> From the active `sprout` branch shape-objects work (S1 data model + geometry shipped; S2 recognizer
> in progress). Items below were noted during build and are not yet scheduled.

- **"Convert to Shape" in lasso context menu.** The debug `[→ Shape]` button already exists in the
  lasso toolbar. Promote it to a non-debug lasso context-menu action so users can manually trigger
  stroke→shape conversion for strokes that didn't auto-trigger (drawn too slowly, missed confidence
  threshold, or the user changed their mind after drawing). Decide icon and label ("Convert to Shape"
  or "Recognize Shape"); handle the same undo/redo path as the auto-trigger.
- **1:1 aspect ratio snap for shape objects.** Add a "Square it" / "Make uniform" toggle in the lasso
  context menu for selected shape objects. Not a hard lock — a one-shot snap that resizes the shape to
  a 1:1 bounding box around its current center, then deselects (or re-selects with the new size).
  Especially useful for circles, squares, stars, and diamonds where slight drawing asymmetry produces
  obviously uneven shapes. The regularized() function already handles aspect-ratio normalization
  internally; this just exposes a manual user-triggered version of that normalization.

---

## Operational task — migrate legacy on-device PNG templates into the index

> From the retired `TEMPLATE_MIGRATION_RUNBOOK.md`. The template system moved from flat PNG files
> (`getExternalFilesDir("Templates")`) into the global index (`notesprout.db`) as `type="template"`
> objects. The app ships **fresh — there is no in-app migration.** This is a **one-time operational
> task run from the dev machine over ADB** (not app code) to bring a user's *existing* on-device PNG
> templates into the new library. Only needed for devices that held templates under the old PNG scheme.

**Procedure outline** (the full runbook — exact commands + the `migrate_templates.py` script — is in
git history if needed):

1. **Pick the build** — debug (`com.notesprout.android.dev`) or stable (`com.notesprout.android`); each
   has its own `notesprout.db` and `Templates/` dir. Run once per build if migrating both.
2. **Quiesce** — `adb am force-stop <pkg>` so nothing holds the WAL-mode DB open.
3. **Pull** `notesprout.db` (+ `-wal`/`-shm` sidecars) and `files/Templates/*.png` to a working dir.
4. **Insert** one `type="template"` row per PNG into the `objects` table, under an
   `"Imported templates"` `type="template_folder"` at root (or root directly). For each PNG: name =
   filename stem (stored in the `name` **column**, not the JSON), `data` =
   `{"width":W,"height":H,"image":"<base64 NO_WRAP>"}`, timestamps in epoch **ms**, fresh `uuid4` id.
   Idempotent — skips names already present. Run `PRAGMA wal_checkpoint(TRUNCATE)` so the main DB is
   self-contained.
5. **Push back** the rebuilt main DB; delete the device's stale `-wal`/`-shm` so it starts clean from
   the main file. (Do not push local sidecars.)
6. **Verify** in-app: Templates browser shows the imported cards; an imported template applies to a page.
7. Legacy PNGs are left untouched (copy, not move) — delete only if the user asks.

`objects` columns: `id, type, name, parentId, createdAt, updatedAt, deletedAt, data`.
