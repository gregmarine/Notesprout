# Notebook Size Research — In-Use Reduction & Backup Compaction

> **Status:** Research only — nothing decided or scheduled.
> Originally captured **2026-06-22** from reasoned estimates (no device was connected).
> **Re-measured 2026-07-02** against real notebooks pulled from a device (BOOX Go 10.3 Gen 2,
> `b7a46e13`). The 6/22 estimates that measurement contradicted have been **removed** — this
> file now reflects measured reality, not guesses.
>
> **Question that prompted this:** Why are `.soil` files so big, where can we reduce them in
> active use, and how can we further compact them for backups (which are archives and do not
> need display-optimized data like snapshots)?

---

## TL;DR (measured)

- **The 6/22 premise was backwards.** It claimed *"page snapshots dominate."* They don't.
  On a real heavy notebook, **stroke JSON is 73% of the file**; snapshots are 9%. On a light,
  template-heavy notebook, **templates are 38%**; strokes 33%; snapshots 13%. Snapshots are a
  secondary driver in both. What dominates depends on the payload mix.
- **Biggest lever — stroke JSON.** The per-point `ts` timestamp is **100% redundant** (every
  point in a stroke carries the *same* value — the save-time `now`) and is ~39% of each point.
  Nothing reads it, and the only useful thing it could encode — *when the stroke was drawn* — is
  already in the row's `createdAt` column. So **drop it entirely**; combined with quantizing
  coordinates to 1 decimal, stroke JSON shrinks by **~52%** — with no visible or functional change.
- **WEBP lossless is a large, low-risk image win.** Real re-compression: snapshots **−50 to −70%**,
  templates **−88 to −96%**. Lossless (not lossy) is the right mode for this black-on-transparent
  line art, and the read path is already format-agnostic (no migration, old PNG files still decode).
- **base64 → BLOB** recovers a further ~33% on image bytes but needs a schema migration and bends a
  core rule; it's a *secondary* lever, after WEBP.
- **Backups** compress spectacularly: a real 63 MiB notebook → **7 MiB** with transform + gzip
  (−89%), or 11 MiB with gzip alone (−82%) — *but still gated by the encryption byte-copy model.*
- **Vacuum is not the lever it was assumed to be.** On a cleanly-closed file it reclaimed 0.6 MiB
  of 63. The residual ~10 MiB overhead is inherent to having 10k+ separate rows, not slack.

---

## 0. Measured reality (2026-07-02)

**Method.** Pulled real `.soil` files over ADB and analysed with `sqlite3` + Python/Pillow
(libwebp — the same encoder Android's `Bitmap.compress` uses, so format numbers are representative).
Two notebooks of opposite profile, plus one encrypted.

### 0.1 Byte split — where the bytes actually are

**Heavy-writing notebook** — 66 MB file, plaintext, 29 pages, **10,174 strokes / 986,559 points:**

| Payload | Bytes | Share of file |
|---|---:|---:|
| **Stroke JSON** | **45.8 MB** | **73%** |
| Page snapshots | 5.5 MB | 9% |
| SQLite overhead (post-vacuum) | ~10 MB | 16% |
| Headings / links / templates | ~0.7 MB | 1% |

**Light notebook** — 2.5 MB file, plaintext, 13 pages, 288 strokes, **12 templates:**

| Payload | Bytes | Share of file |
|---|---:|---:|
| **Templates** | **0.94 MB** | **38%** |
| Stroke JSON | 0.83 MB | 33% |
| Page snapshots | 0.33 MB | 13% |

A third notebook (28 MB) was **encrypted** and would not open (`file is not a database` —
SQLCipher whole-file encryption), confirming the encryption constraint in §4.1 is real and current.

### 0.2 What only measurement revealed

- **`ts` is byte-for-byte redundant, not merely unread.** In **100%** of strokes in *both*
  notebooks, every point in a stroke shares one identical `ts` (a whole stroke is captured under a
  single `fallbackTimestamp`). Real sample:
  `{"x":257.9762,"y":390.0,"ts":1781441240786},{"x":257.85718,"y":390.0,"ts":1781441240786},…`
  — the 13-digit value repeats for the whole stroke. It is ~39% of every point (~48.6 B/point avg).
- **Vacuum barely helps a cleanly-closed file** (reclaimed 0.6 MiB of 63). The 6/22 doc's framing
  of vacuum/WAL slack as a "big lever" was an over-estimate for the normal close path.
- **Coordinates carry meaningless precision** — `389.88095`, `257.9762` — Float artifacts. At the
  panel's 227 DPI, 1 px ≈ 0.11 mm; sub-pixel precision is imperceptible.

### 0.3 Image format comparison (real snapshots & templates, WEBP **lossless**)

Stored as base64 in a TEXT column today, so the "now" column includes the +33% base64 tax:

| Content | Now (base64 PNG-100) | WEBP lossless (base64) | Saving |
|---|---:|---:|---:|
| Snapshots (heavy nb) | 5.5 MB | 2.7 MB | **−50%** |
| Snapshots (light nb) | 0.33 MB | 0.10 MB | **−70%** |
| Templates (heavy nb) | 45 KB | 1.9 KB | **−96%** |
| Templates (light nb) | 942 KB | 114 KB | **−88%** |

- **Lossless beats lossy here.** These are sparse black-on-transparent strokes/line-grids; WEBP
  lossless is both *smaller* than WEBP lossy *and* pixel-identical. Lossy wastes bits smoothing
  already-sharp edges and adds gray halos around ink — no reason to use it for this content.
- **Read path is format-agnostic.** Every decode routes through `BitmapDecode.decodeSampled` →
  `BitmapFactory.decodeByteArray`, which auto-detects PNG/WEBP. Switching the *write* format needs
  **no** DB migration and **no** format flag; old PNG rows and new WEBP rows coexist.
- **minSdk = 29 caveat.** `Bitmap.CompressFormat.WEBP_LOSSLESS` is API 30+. On API 29 use the
  deprecated `Bitmap.CompressFormat.WEBP` at quality 100 (that *is* lossless). One `if (SDK_INT>=30)`
  branch, or just use the legacy constant everywhere.

### 0.4 Stroke-JSON reduction (measured on the heavy notebook's 45.8 MB of strokes)

| Change | Result | Saving |
|---|---:|---:|
| Drop per-point `ts` | 27.9 MB | −39% |
| Quantize coords to 1 decimal + drop `ts` | 21.9 MB | **−52%** |
| Quantize to integer + drop `ts` | 18.2 MB | −60% |
| Keep **one** `ts` per stroke (for reference only) | 28.1 MB | −39% |

The light notebook matched almost exactly (−38.7% / −52.6% / −60.8%). The stroke-level-`ts` row is
shown only for comparison — **it is not the recommendation.** A stroke's creation time is already
stored on its row (`createdAt`, which — unlike `ts` — survives moves), so there is nothing to
preserve by keeping a stroke-level timestamp. **Drop `ts` outright.**

### 0.5 Real before/after (transforms applied to a real 63.0 MiB copy, then measured)

| Configuration | File size | Saving |
|---|---:|---:|
| **Original** | 63.0 MiB | — |
| Vacuum only | 62.4 MiB | −1% |
| WEBP images only | 59.5 MiB | −6% |
| Stroke shrink only (q1 + drop-`ts`) | 38.3 MiB | −39% |
| **All: q1 + drop-`ts` + WEBP** | **35.4 MiB** | **−44%** |
| All with integer coords | 30.6 MiB | −51% |
| **All + gzip (backup archive)** | **7.0 MiB** | **−89%** |
| Original + gzip (no transform) | 11.3 MiB | −82% |

---

## 1. Anatomy of a `.soil` file

Each notebook is one SQLite database (`.soil` extension) at
`getExternalFilesDir(null)/Garden/<uuid>.soil`. One `notebook` table; everything (pages, layers,
strokes, images, text, templates, metadata) is a row with a `data` TEXT column holding JSON. Schema
and rules: [`docs/data-architecture.md`](docs/data-architecture.md).

The on-disk weight lives in these JSON payloads plus structural overhead:

| Payload | Where | Shape | Measured rank |
|---|---|---|---|
| **Stroke points** | `StrokeData.points` on each stroke row | JSON array of `{x,y,ts}` | #1 (heavy notebooks) |
| **Template image** | `TemplateData.image` on each `type="template"` row | base64 PNG | #1 (light/template-heavy) |
| **Page snapshot** | `PageData.snapshot` on each `type="page"` row | base64 PNG | secondary in both |
| SQLite overhead | page structure, index, WAL, free pages | — | ~16% (not reclaimable by vacuum on clean files) |
| Embedded cover | `notebook_meta` (schema v3) single row | base64 PNG (plaintext only) | small |

Relevant model files:
- `data/ObjectData.kt` — `PageData` (`{width,height,template,snapshot}`, `:56`), `TemplateData` (`:73`).
- `data/StrokeData.kt` — `StrokeData(color, strokeWidth, points)`; `toPointFs()` (`:42`) drops
  `ts`/`pressure`/`tilt` for rendering.
- `data/StrokePoint.kt` — `StrokePoint(x, y, pressure?, tilt?, @SerialName("ts") timestamp)`.
- `data/LiveStroke.kt` — `toStrokeData()` (`:83`) is the only thing that writes `ts` (copies
  `srcPoints` ts forward, or stamps one `fallbackTimestamp` per fresh stroke → the uniformity above).
- `data/NotebookMeta.kt` / `data/NotebookMetaStore.kt` — embedded identity + cover.

---

## 2. Active-use size drivers (ranked by **measured** leverage)

### 2.1 Stroke JSON — the dominant driver

73% of the heavy notebook, 33% of the light one. Two independent, compounding wins, both safe
because nothing reads `ts` for rendering (`StrokeData.toPointFs()` ignores it) and sub-pixel
precision is invisible:

1. **`ts` — dead *and* redundant, so drop it.** 100% uniform per stroke (the save-time `now`,
   stamped on every point), ~39% of every point, and **read by nothing** — the only code touching a
   stored point's `ts` is the round-trip that rewrites it unchanged (`LiveStroke.kt:83`). Live drawing
   uses real hardware timestamps for shape-dwell detection (`OnyxNotebookView.kt:429`), but those are
   the live pen event, consumed on the spot and never persisted. The field's stated intent — a
   placeholder "for future devices" enabling ink replay / velocity-derived line weight / temporal
   recognition — was never built, and as populated it carries no per-point timing anyway. Its one
   useful signal, *when the stroke was drawn*, is already on the row (`createdAt`, `:7144` — and
   `createdAt` survives moves, whereas `ts` is clobbered with a fresh `now` on every re-save). So
   **drop it entirely**; a future device that captures real timing can add the field back then.
2. **Coordinate quantization.** Round to 1 decimal at serialize time (invisible at 227 DPI). Integer
   saves more (−60% combined) but 1-decimal (−52% combined) is the conservative default.

Decoder already tolerates missing keys (`ignoreUnknownKeys = true`; `pressure`/`tilt` nullable and
already omitted when null), so reads stay forward/backward compatible. The one code change: make
`StrokePoint.timestamp` defaulted/nullable so it can be omitted from output.

### 2.2 Images — snapshots + templates (format, then base64)

Snapshots are full-device-resolution (e.g. 1860×2480), 32-bit ARGB, transparent-base, PNG-100.
Templates are simple line-grids. Both are line art that WEBP lossless compresses far better than
PNG (§0.3). Two levers:

1. **WEBP lossless** (highest image leverage, low risk): −50 to −96%. Read-transparent, lossless,
   no migration. **Pair with a threading fix:** `captureSnapshot()` currently renders **and**
   compresses on the **main thread** during page nav/close (`NotebookActivity.navigateToPageInternal`
   `:5162`, `saveAndSwitchPage` `:5199`). WEBP encodes slower than PNG, so split the compress+base64
   off to `Dispatchers.IO` (return the `Bitmap` from the main thread, encode on IO) — a good refactor
   regardless, and it protects page-turn smoothness on slower e-ink SoCs.
2. **base64 → BLOB** (secondary): recovers the +33% TEXT tax on image bytes. After WEBP shrinks the
   images, the absolute saving is smaller (e.g. heavy-nb snapshots 2.7 MB → 2.1 MB). Needs a Room
   v3→v4 migration touching every image read/write site, and bends the "assets are base64 in `data`"
   rule (stays inline / single-table). Do **after** WEBP, not instead.

**Why snapshots exist (do not delete from live files):** they are the e-ink fast-load cache. On a
snapshot hit the page displays immediately while strokes deserialize in the background
(`NotebookActivity.tryLoadSnapshotBitmap` `:4091`, two-phase load in
[`docs/drawing-engine.md`](docs/drawing-engine.md)). The opportunity is to make them *cheaper*, not
to remove them.

### 2.3 SQLite overhead / vacuum — minor for clean files

~16% of the heavy file, but vacuum on a **cleanly-closed** notebook reclaimed only 0.6 MiB — the
close path already runs `hardDeleteOldSoftDeleted` + `incremental_vacuum` + `wal_checkpoint(TRUNCATE)`
(`NotebookActivity.sealNotebook` `:3909`, `:3943–3946`). The residual is real per-row structure
(10k+ stroke rows, each with id/parentId/boundingBox/type/3 timestamps + index), not reclaimable
slack. Materially reducing it would mean changing how points are batched into rows — a large
architectural change, out of scope here.

### 2.4 Template duplication across notebooks

Applying a library template copies its full-resolution image into the `.soil` as a `type="template"`
row **and** the same image lives in the global index library (`TemplateObject.image` in
`notesprout.db`). Within one notebook, pages sharing a template reference one row (good); across
notebooks the image is duplicated byte-for-byte. WEBP shrinks every copy; **content-hash dedup**
(one stored copy per unique image, referenced by hash) is the structural fix — larger change, flagged
as an opportunity, not low-hanging fruit.

### 2.5 Soft-deletes — mostly handled

`hardDeleteOldSoftDeleted(before = sessionStart)` (`data/NotebookDao.kt:318`) purges pre-session
soft-deletes on clean close; current-session soft-deletes are retained for undo safety. Space is only
returned when the close path completes. Not a standing driver.

---

## 3. Reduction options for files *in active use* (ranked by measured leverage)

1. **Stroke JSON: drop per-point `ts` + quantize coords to 1-dec.** −52% of stroke bytes; the dominant
   real win. Risk: low (nothing reads `ts`; `createdAt` already holds stroke timing). Effort: small
   (`StrokePoint`, `StrokeData`, `LiveStroke.toStrokeData`). Converges as pages are re-saved — no
   forced migration.
2. **WEBP lossless for snapshots + templates + cover.** −50 to −96% of image bytes. Risk: low (read
   path unchanged, lossless). Effort: small (both `captureSnapshot()` encoders, template-save, cover).
   Pair with moving the compress off the main thread (§2.2).
3. **base64 → BLOB for images.** Further −33% of image bytes. Risk: medium (Room v3→v4 migration,
   bends a core rule). After #2.
4. **Template content-hash dedup** across notebooks + index. Architectural; matters for heavy reuse.

> **Wire-format caveat.** `StrokeData.kt`/`ObjectData.kt` document the JSON as byte-compatible with
> the original org.json output. Items 1 change *newly written* rows only; reads stay compatible (codec
> tolerates missing/extra keys). Decide whether to lazy-rewrite on open, one-shot migrate, or let it
> converge as pages are touched. WEBP (item 2) needs no migration at all.

**Suggested slice:** #1 + #2 together — low-risk, no forced migration, and they take the worst real
notebook from 63 → ~35 MiB (−44%) while shrinking template-heavy notebooks even more.

---

## 4. Backup-specific compaction (the archive angle)

**Premise (still correct):** a backup is an *archive*, not a display-optimized live file. A
restored/imported `.soil` regenerates every snapshot on first open (the load path rebuilds on a
snapshot miss), so display-only data can be dropped from backups without loss.

Current backup model (see [`docs/backup.md`](docs/backup.md)): **pure byte-for-byte file copy**, no
transform (`data/backup/BackupEngine.kt` → `SafBackupWriter.replaceFile` / `DriveBackupWriter.replaceFile`);
**incremental by timestamp** (`data/backup/BackupPredicates.kt`); index copied last.

Archival reductions, biggest first (measured on the 63 MiB notebook):

1. **gzip the archive → 7 MiB with transform, 11 MiB without (−89% / −82%).** Stroke JSON and repeated
   `ts` values compress extremely well; gzip also recovers most of base64's overhead. Trade-off:
   backups become `.soil.gz`, so the import/restore path must inflate first.
2. **Strip snapshots** (and the `notebook_meta` cover, and `undo_redo_state`) — regenerated on first
   open. Pairs with gzip (PNG/WEBP compress poorly, so removing them helps the gzip result). Decide
   whether a restored notebook shows a placeholder card until first open (the cover is what lets
   MainActivity render the card without opening the file).
3. **Re-serialize (drop `ts`, quantize) + VACUUM** the backup copy — but if items 1/2 already run, most
   of this value is captured.

### 4.1 The hard constraint: encryption byte-copy model

Encrypted notebooks are backed up as a **byte-level ciphertext copy** — no decrypt, no passphrase
prompt (SQLCipher encrypts the whole file; confirmed by the 28 MB notebook that would not open).
Stripping/vacuum/re-serialize all require opening and rewriting the DB, which is impossible without
the key. So compaction splits in two:

| | Plaintext | Encrypted |
|---|---|---|
| Strip snapshots / cover / undo | ✅ | ❌ needs key |
| VACUUM · re-serialize | ✅ | ❌ needs key |
| Outer gzip | ✅ | ⚠️ SQLCipher ciphertext compresses poorly |

Reducing encrypted backups means either **decrypting during backup** (a security-model change —
passphrases are never logged, never in Intent extras, never in the index; see
[`docs/encryption.md`](docs/encryption.md)) or **accepting that encrypted backups stay large.**

### 4.2 Model trade-offs to weigh later

- Transform-on-backup makes backups **transformed copies**, not pure copies — more CPU per notebook,
  and the file differs from the live one. Incremental-by-timestamp still works.
- **Round-trip the import/restore path** with stripped + gzipped files before committing — confirm it
  tolerates a missing `snapshot`, missing cover, and absent `undo_redo_state`. (Import is the de facto
  restore today.)

---

## 5. Open questions for future "us"

Answered by the 2026-07-02 measurement (kept for the record):
- *Real snapshot-to-stroke ratio?* → On a heavy notebook, strokes are ~8× snapshots. Snapshots do
  **not** dominate; stroke JSON does.
- *Is snapshot reformatting worth it?* → Yes: WEBP lossless −50 to −96%, read-transparent, no migration.
- *Are encrypted notebooks a hard constraint?* → Yes, confirmed (won't open without key).

Still open:
- **BLOB vs base64:** worth a Room v3→v4 migration for the remaining ~33% on image bytes, or not?
- **Decrypt-during-backup:** ever acceptable for encrypted notebooks, or commit to large encrypted
  backups permanently?
- **Stroke-JSON rollout:** lazy-rewrite on open, one-shot migrate, or converge-on-touch?
- **Backup format:** switch to `.soil.gz` (needs restore-side inflate) or keep drop-in `.soil`?
- **Row-count overhead:** is the ~16% per-row SQLite overhead ever worth a batched-points redesign?

---

## 6. Key code references (verified 2026-07-02)

| Concern | File:line |
|---|---|
| Snapshot capture (Onyx) | `notebook/OnyxNotebookView.kt:2450` (compress `:2492`, base64 `:2494`) |
| Snapshot capture (Generic) | `notebook/GenericNotebookView.kt:1817` (compress `:1859`, base64 `:1861`) |
| Snapshot interface | `notebook/NotebookView.kt:615` |
| Snapshot compress on **main thread** (nav/close) | `NotebookActivity.kt:5162`, `:5199` |
| Snapshot fast-load (decode is format-agnostic) | `NotebookActivity.tryLoadSnapshotBitmap:4091` |
| Bounded, format-agnostic decode | `core/BitmapDecode.kt` (`decodeSampled`) |
| Page/Template JSON | `data/ObjectData.kt` (`PageData:56`, `snapshot:60`, `TemplateData:73`, `image:77`) |
| Stroke JSON codec | `data/StrokeData.kt` (`toPointFs:42` drops ts/pressure/tilt) |
| Point shape | `data/StrokePoint.kt` (`ts` = SerialName for `timestamp`) |
| Only writer of `ts` (source of per-stroke uniformity) | `data/LiveStroke.kt:83` (`toStrokeData`) |
| Soft-delete purge + vacuum on close | `NotebookActivity.sealNotebook:3909` (`:3943–3946`) |
| Embedded meta/cover | `data/NotebookMeta.kt`, `data/NotebookMetaStore.kt` |
| Backup engine (pure byte-copy) | `data/backup/BackupEngine.kt`; predicates `data/backup/BackupPredicates.kt` |
| minSdk (WEBP_LOSSLESS gate) | `app/build.gradle.kts:19` (`minSdk = 29`) |
</content>
</invoke>
