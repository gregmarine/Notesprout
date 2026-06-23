# Notebook Size Research — In-Use Reduction & Backup Compaction

> **Status:** Research only. No plan, no implementation. Captured 2026-06-22 for a future
> "us" to review as the Garden grows. Nothing here has been decided or scheduled.
>
> **Question that prompted this:** Why are `.soil` files so big, where can we reduce them
> in active use, and how can we further compact them for backups (which are archives and do
> not need display-optimized data like snapshots)?

---

## TL;DR

- **Page snapshots dominate both problems.** They are a full-device-resolution, 32-bit,
  PNG-quality-100, base64 raster cache stored *inside* the canonical store — and they are
  100% reconstructable from strokes + template.
- **In active use**, the highest-leverage wins are: reformat/shrink snapshots, drop the
  unused per-point timestamp, and quantize coordinate precision.
- **For backups specifically**, stripping snapshots (plus VACUUM, plus optional gzip) is the
  big archival lever — *fully available for plaintext notebooks, but gated by the
  ciphertext-byte-copy model for encrypted ones.* That encryption constraint is the key
  decision to make before any of this becomes a plan.

---

## 1. Anatomy of a `.soil` file

Each notebook is one SQLite database (`.soil` extension) at
`getExternalFilesDir(null)/Garden/<uuid>.soil`. One `notebook` table; everything (pages,
layers, strokes, images, text, templates, metadata) is a row with a `data` TEXT column
holding JSON. Schema and rules: [`docs/data-architecture.md`](docs/data-architecture.md).

The on-disk weight lives in three JSON payloads plus structural overhead:

| Payload | Where | Shape |
|---|---|---|
| **Page snapshot** | `PageData.snapshot` on each `type="page"` row | base64 PNG string |
| **Stroke points** | `StrokeData.points` on each stroke row | JSON array of `{x,y,ts,…}` |
| **Template image** | `TemplateData.image` on each `type="template"` row | base64 PNG string |
| Embedded cover | `notebook_meta` (schema v3) single row | base64 PNG (plaintext only) |
| SQLite overhead | WAL, free pages, journal | — |

Relevant model files:
- `data/ObjectData.kt` — `PageData` (`{width,height,template,snapshot}`), `TemplateData`,
  `BoundingBox`.
- `data/StrokeData.kt` — `StrokeData(color, strokeWidth, points)`.
- `data/StrokePoint.kt` — `StrokePoint(x, y, pressure?, tilt?, @SerialName("ts") timestamp)`.
- `data/NotebookMeta.kt` / `data/NotebookMetaStore.kt` — embedded identity + cover.

---

## 2. Active-use size drivers (ranked)

### 2.1 Page snapshots — biggest driver, pure redundancy

`captureSnapshot()` exists in both engines:
- `notebook/OnyxNotebookView.kt:2013`
- `notebook/GenericNotebookView.kt:1491`
- interface: `notebook/NotebookView.kt:433`

Both do the same thing: render the **full device-resolution** page into an
`ARGB_8888` bitmap (transparent base) and:

```kotlin
bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)   // OnyxNotebookView.kt:2053
return Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
```

Four compounding costs:

1. **Resolution × depth × quality.** Full-page (e.g. Note Max ~1650×2200) × 32-bit ARGB ×
   lossless PNG-100. A moderately-to-heavily filled page can be hundreds of KB to >1 MB.
2. **Base64 inflation** — another ~33% on top, since it lives in a TEXT column.
3. **Reconstructable.** The snapshot is *only* a fast-load raster cache for e-ink. It is
   fully rebuildable from strokes + template on next open. See the two-phase page load in
   [`docs/drawing-engine.md`](docs/drawing-engine.md) ("Page Snapshot System"):
   white → template → snapshot PNG → new content. Functionally it is a cache stored inside
   the source of truth.
4. **Churn multiplier.** A snapshot is re-persisted on close, dialog overlay, page nav,
   `setTemplate`, `setEraserMode(true)`, etc. (capture triggers listed in
   `drawing-engine.md`). Every rewrite frees the previous snapshot's SQLite pages, which sit
   in the free list / WAL until `PRAGMA incremental_vacuum` + `wal_checkpoint(TRUNCATE)` run
   on clean close (`NotebookActivity.kt:3278`). A heavily-edited notebook bloats well beyond
   its logical content between vacuums.

**Why it exists (do not casually remove):** snapshots are the e-ink fast-path. On load, a
snapshot hit lets the page display immediately while strokes deserialize in the background
(`NotebookActivity.loadCurrentPage`, fast vs. full path). Removing snapshots entirely would
regress cold-open latency on large pages. The opportunity is to make them *cheaper*, not to
delete them from live files.

### 2.2 Per-point timestamp (`ts`) — dead weight in stroke JSON

Each point serializes (from `StrokeData.kt` / `StrokePoint.kt`) as:

```json
{ "x": 100.0, "y": 200.0, "ts": 1716000000000 }
```

`pressure` / `tilt` are nullable and **already omitted** when null (`explicitNulls = false`),
and hardware capture is not implemented, so they cost nothing today. But **`ts` is written
on every point** — a 13-digit epoch-millis `Long`, often larger than the coordinates it
accompanies — and it is **never read for rendering or any logic**.

Confirmed by grep: the only consumers of a point's `timestamp` are storage round-trips:
- `data/LiveStroke.kt:87` copies `src[i].timestamp` forward on re-serialize.
- `data/LiveStroke.kt:90` stamps a `fallbackTimestamp` on fresh strokes.
- `StrokeData.toPointFs()` (`StrokeData.kt:42`) — used for rendering — **ignores** `ts`,
  `pressure`, and `tilt` entirely (returns only `PointF(x, y)`).

So on a stroke of thousands of densely-sampled points, `ts` is frequently the single largest
component of each point object while contributing nothing functional. (Original intent: keep
the field for "future devices" — see the `StrokePoint` KDoc.)

### 2.3 Coordinate precision

`StrokePoint.x` / `.y` are raw input `Float`s serialized at full precision by
kotlinx.serialization (e.g. `127.38492279052734`). On an e-ink canvas, sub-pixel precision
beyond ~1 decimal place is not perceptible. Rounding to integer or 1 decimal would shrink
every point with no visible effect.

### 2.4 Template duplication across notebooks

Applying a library template copies its **full-resolution PNG** into the notebook's `.soil`
as a `type="template"` row (`TemplateData.image`, base64). See the Template System section of
[`docs/drawing-engine.md`](docs/drawing-engine.md) and Templates in
[`docs/data-architecture.md`](docs/data-architecture.md).

- **Within one notebook:** pages sharing a template reference one `.soil` template row — good,
  no duplication.
- **Across notebooks:** the *same* template image is duplicated in every `.soil` that uses
  it, **and again** in the global index library (`TemplateObject.image` in `notesprout.db`).
  Heavy template users carry many byte-for-byte copies of the same image.

Content-hash dedup (one stored copy, referenced by hash) is a larger architectural change —
flagged as an opportunity, not low-hanging fruit.

### 2.5 Soft-deletes — mostly handled

- `hardDeleteOldSoftDeleted(before = sessionStart)` purges pre-session soft-deletes on clean
  close (`data/NotebookDao.kt:293`, called at `NotebookActivity.kt:3277`).
- Current-session soft-deletes are intentionally retained for undo/redo safety on abnormal
  teardown.
- Reclamation depends on a **clean close** running `incremental_vacuum` +
  `wal_checkpoint(TRUNCATE)`. Not a major standing driver, but worth knowing the space is
  only actually returned when the close path completes.

### 2.6 Embedded images / assets

Per the core rules, all assets are base64 inline in `data` — no external files. Embedded
image decodes are bounded through `core/BitmapDecode.decodeSampled(...)` (OOM guard), but the
**stored** bytes are whatever was embedded. Photos/large rasters embedded into a notebook are
a direct size cost. (Not the current dominant driver versus snapshots, but the same base64
inflation applies.)

---

## 3. Reductions that apply to files *in active use*

Ordered by leverage. **All of these change live files**, so each has a compatibility note.

1. **Snapshot format (highest leverage).** The snapshot is grayscale/black-on-transparent
   e-ink content stored as 32-bit PNG-100. Levers that keep the fast-load behavior intact:
   - Lossless **WEBP** instead of PNG.
   - A **lower bitmap config** (the content is effectively 1-bit/grayscale + alpha).
   - **Cap snapshot resolution** below full device resolution.
   Any of these shrinks the single largest payload without touching the vector source of truth.

2. **Drop or delta-encode per-point `ts`.** Nothing reads it. Cleanest win is to stop
   writing it; if the "future hardware" intent is to be preserved, store one stroke-level
   base timestamp plus small deltas. Decoder already tolerates missing keys
   (`ignoreUnknownKeys = true`, nullable/defaulted fields), so reads stay compatible.

3. **Quantize coordinates** to int or 1 decimal at serialization time.

4. **Template content-hash dedup** (architectural) — one stored copy per unique image,
   referenced by hash, across `.soil` files and the index.

> **Wire-format caveat.** `StrokeData.kt` and `ObjectData.kt` both document the JSON as
> "byte-compatible with the previous org.json output, no DB migration required." Items 2 and 3
> change *newly written* rows. Reading stays forward/backward compatible (the codec tolerates
> missing/extra keys), but old and new rows would differ on disk. Be deliberate — decide
> whether to lazy-rewrite on open, one-shot migrate, or simply let it converge as pages are
> touched.

---

## 4. Backup-specific compaction (the archive angle)

**Premise (correct):** a backup is an *archive*, not a display-optimized live file. A
restored/imported `.soil` regenerates every snapshot on first open (the load path rebuilds on
a snapshot miss). So data that exists purely for display can be dropped from backups without
loss.

Current backup model (see [`docs/backup.md`](docs/backup.md)):
- **Pure file copy**, byte-for-byte. No transform.
- **Incremental by timestamp** — a notebook is re-copied only when `updatedAt >`
  last-backed-up time (`data/backup/BackupPredicates.kt`).
- **Drive replace-in-place** by UUID filename (PATCH existing file ID).
- **Index copied last** after all per-notebook timestamps are stamped.

Archival reductions, biggest first:

1. **Strip snapshots.** Remove the `snapshot` field from every page row in the backup copy.
   Likely the single largest backup-size reduction available, and lossless in archival terms
   (regenerated on first open after restore). Same logic applies to:
   - the embedded `notebook_meta` **cover snapshot** (regenerable), and
   - the **`undo_redo_state`** table (schema v2 — encrypted-notebook session undo history;
     meaningless in an archive). See `docs/data-architecture.md` "Schema Version 2/3".
   - *Caveat:* the cover snapshot is what lets MainActivity render the card without opening
     the `.soil`, and `notebook_meta` is used by import for portable display. Decide whether a
     restored notebook should show a placeholder until first open, or whether the small cover
     is worth keeping while stripping only the per-page snapshots.

2. **VACUUM the backup copy.** Collapse free pages / WAL slack accumulated from snapshot
   churn (§2.1). A freshly-vacuumed copy is materially smaller than the live file *even before
   stripping*.

3. **Compress the archive (gzip/zstd the `.soil`).** Stroke JSON is highly repetitive and
   compresses extremely well; gzip also recovers much of base64's ~25% overhead. PNG
   snapshots compress poorly, so this pairs best **with** snapshot-stripping. Trade-off:
   backups become `.soil.gz` (or similar) rather than drop-in `.soil` files — the
   import/restore path would need to inflate first.

### 4.1 The hard constraint: encryption byte-copy model

This cuts across **every** backup-compaction idea above.

Encrypted notebooks are currently backed up as a **byte-level ciphertext copy** — no
decrypt, no passphrase prompt (`docs/backup.md` D10; SQLCipher encrypts the whole file). But
stripping snapshots, VACUUM, and re-serialization all require **opening and rewriting the
DB**, which is impossible without the key. So compaction splits into two worlds:

| | Plaintext notebooks | Encrypted notebooks |
|---|---|---|
| Strip snapshots / cover / undo | ✅ open, strip, copy | ❌ needs key |
| VACUUM | ✅ | ❌ needs key |
| Re-serialize (drop `ts`, quantize) | ✅ | ❌ needs key |
| Outer compression (gzip ciphertext) | ✅ | ⚠️ possible but SQLCipher ciphertext compresses poorly |

Reducing encrypted notebooks would mean either **decrypting during backup** (a
security-model change — passphrases are never logged, never in Intent extras, never in the
index; see [`docs/encryption.md`](docs/encryption.md)) or **accepting that encrypted backups
stay large.**

### 4.2 Model trade-offs to weigh later

- A strip/vacuum/compress pipeline makes backups **transformed copies**, not pure copies:
  more CPU per notebook, and the backed-up file differs from the live one.
- **Incremental-by-timestamp** still works (keyed on live `updatedAt`), but the transform
  cost is paid on every changed notebook each run.
- **Round-trip the restore/import path** with stripped (and possibly compressed) files before
  committing — confirm the full-notebook import pipeline tolerates a missing `snapshot`,
  missing cover, and absent `undo_redo_state`. (Restore is still future work per
  `docs/backup.md`; the import path is the de facto restore today.)

---

## 5. Open questions for future "us"

- **Measure, don't guess.** Pull real notebooks and compute the actual byte split:
  snapshots vs. stroke JSON vs. templates vs. SQLite slack. ADB pull path:
  `/sdcard/Android/data/com.notesprout.android.dev/files/Garden/<uuid>.soil`. (No devices were
  connected when this was written, so all magnitudes here are reasoned estimates.)
- What is the real snapshot-to-stroke byte ratio on a heavily-used notebook? That ratio
  decides whether snapshots alone justify a project.
- Does the import/restore path already tolerate a stripped `.soil` (no snapshot/cover/undo)?
  Trace it before assuming.
- Is decrypt-during-backup ever acceptable for encrypted notebooks, or do we commit to "big
  encrypted backups" as a permanent trade-off?
- Should snapshot reformatting (WEBP / lower depth / capped res) be an **in-use** change
  (benefits live files too) or only a **backup-time** transform (keeps live fast-path
  untouched)?

---

## 6. Key code references

| Concern | File:line |
|---|---|
| Snapshot capture (Onyx) | `notebook/OnyxNotebookView.kt:2013` (compress `:2053`) |
| Snapshot capture (Generic) | `notebook/GenericNotebookView.kt:1491` (compress `:1531`) |
| Snapshot interface | `notebook/NotebookView.kt:433` |
| Page/Template/Bbox JSON | `data/ObjectData.kt` (`PageData:55`, `TemplateData:72`) |
| Stroke JSON codec | `data/StrokeData.kt` (`toPointFs:42` drops ts/pressure/tilt) |
| Point shape | `data/StrokePoint.kt` (`ts` = SerialName for `timestamp`) |
| `ts` round-trip (only consumers) | `data/LiveStroke.kt:87`, `:90` |
| Soft-delete purge on close | `data/NotebookDao.kt:293`; called `NotebookActivity.kt:3277` |
| Close-path vacuum/checkpoint | `NotebookActivity.kt:3278-3281` |
| Embedded meta/cover | `data/NotebookMeta.kt`, `data/NotebookMetaStore.kt` |
| Backup engine / predicates | `data/backup/BackupEngine.kt`, `data/backup/BackupPredicates.kt` |
| Encrypted byte-copy rule | `docs/backup.md` (D10), `docs/encryption.md` |
