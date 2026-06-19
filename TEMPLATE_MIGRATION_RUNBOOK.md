# Template Migration Runbook (for a Haiku session)

> **Purpose:** NoteSprout's template system moved from flat PNG files
> (`getExternalFilesDir("Templates")`) into the global index (`notesprout.db`) as first-class
> `type="template"` objects (see `TEMPLATE_SYSTEM_PLAN.md`). The app ships **fresh** — there is **no
> in-app migration**. This runbook migrates a user's *existing* on-device PNG templates into the new
> index library, as an **operational task run from the dev machine over ADB**. It is **not app code.**
>
> **You are a Haiku session.** Follow these steps literally. Make **no design decisions** — every
> value is fixed here. If anything looks off (a path missing, a count mismatch, a decode failure),
> **STOP and report** rather than guessing.

---

## 0. Pick the build you're migrating

NoteSprout installs two independent apps. Each has its **own** `notesprout.db` and **own**
`Templates/` directory. Migrate whichever the user names; the only difference is the package id.

| Build | Package id | Run for this build when… |
|---|---|---|
| **Debug** (dev) | `com.notesprout.android.dev` | active development / the side-by-side dev app |
| **Stable** (release) | `com.notesprout.android` | the user's primary installed app |

Set these once and reuse them everywhere below:

```sh
PKG=com.notesprout.android.dev      # debug   — OR —
PKG=com.notesprout.android          # stable
SERIAL=34E517F9                     # G10 (BOOX Go 10.3). Change if migrating another device.
BASE=/sdcard/Android/data/$PKG/files
```

> **Both builds:** if the user wants *both* migrated, run this entire runbook twice — once per `PKG`.
> They never share data; nothing is reused between them. Use a separate local working dir per build.

---

## 1. Pre-flight

```sh
adb -s "$SERIAL" get-state                 # must print: device
adb -s "$SERIAL" shell pm path "$PKG"      # must print a package path → app is installed
adb -s "$SERIAL" shell ls "$BASE/Templates" 2>/dev/null   # the old PNG library
```

- If `pm path` prints nothing → the build isn't installed. **STOP**, tell the user.
- If `$BASE/Templates` doesn't exist or is empty → there are **no legacy templates** for this build.
  Nothing to migrate. **STOP**, report "no legacy templates found."
- Note the PNG count — you'll reconcile against it at the end.

### 1a. Quiesce the database (avoid WAL data loss)

`notesprout.db` runs in **WAL mode** and stays open the whole time the app is alive. Pulling only the
main DB file while uncommitted data sits in the `-wal` sidecar would lose writes. Force-stop the app so
nothing holds the DB open, then pull **all three** SQLite files and let local `sqlite3` fold the WAL in:

```sh
adb -s "$SERIAL" shell am force-stop "$PKG"
```

> Do **not** skip the force-stop. Do **not** edit the DB live on-device.

---

## 2. Pull the data to the dev machine

```sh
WORK=/tmp/nsp_migrate_${PKG}
rm -rf "$WORK" && mkdir -p "$WORK/Templates"

# Global index + its WAL sidecars (sidecars may not exist — that's fine)
adb -s "$SERIAL" pull "$BASE/notesprout.db"      "$WORK/notesprout.db"
adb -s "$SERIAL" pull "$BASE/notesprout.db-wal"  "$WORK/notesprout.db-wal" 2>/dev/null || true
adb -s "$SERIAL" pull "$BASE/notesprout.db-shm"  "$WORK/notesprout.db-shm" 2>/dev/null || true

# The legacy PNG templates
adb -s "$SERIAL" pull "$BASE/Templates/."        "$WORK/Templates/"
ls -1 "$WORK/Templates" | wc -l                  # sanity: matches the device count from step 1
```

> **Keep `notesprout.db-wal`/`-shm` next to `notesprout.db` locally.** `sqlite3` needs them in the same
> directory to merge any pending WAL when it opens the DB. Don't rename or move the main file away from
> them.

---

## 3. Insert the templates (local Python script)

The script below reads each PNG in `Templates/`, derives the template **name** from the filename stem,
decodes width/height, base64-encodes the bytes (**no line wrapping**, matching the app's `NO_WRAP`),
and inserts one `type="template"` row per PNG into the `objects` table. By default it nests them under a
single **`Imported templates`** `type="template_folder"` row at root (tidy; the user can move them
later). To drop them at root instead, set `USE_FOLDER = False`.

### Target schema (do not deviate)

`objects` table — columns in order:
`id, type, name, parentId, createdAt, updatedAt, deletedAt, data`

- `id` — a fresh `uuid4` string per row.
- `type` — `"template"` for templates, `"template_folder"` for the wrapper folder.
- `name` — the template/folder name (PNG filename **without** extension for templates). **The name
  lives in this column, NOT in the JSON.**
- `parentId` — `NULL` = root. Templates point at the Imported-folder id (or `NULL` if `USE_FOLDER=False`).
- `createdAt` / `updatedAt` — epoch **milliseconds** (`int(time.time()*1000)`).
- `deletedAt` — `NULL` (not deleted).
- `data` — for templates, the `TemplateObject` JSON exactly:
  `{"width":W,"height":H,"image":"<base64 NO_WRAP>"}`. For the folder, `"{}"`.

> The app reads `data` with `ignoreUnknownKeys` + defaults, but write all three keys for templates and
> nothing extra. The folder's `data` is `{}`.

### `migrate_templates.py`

```python
#!/usr/bin/env python3
import base64, json, os, sqlite3, struct, sys, time, uuid, glob

WORK = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
DB   = os.path.join(WORK, "notesprout.db")
PNGS = os.path.join(WORK, "Templates")
USE_FOLDER  = True               # False → insert templates at root
FOLDER_NAME = "Imported templates"

def png_size(path):
    # Read width/height from the PNG IHDR (bytes 16..24). Validates the signature too.
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"not a PNG: {path}")
    w, h = struct.unpack(">II", head[16:24])
    return w, h

now = int(time.time() * 1000)
files = sorted(glob.glob(os.path.join(PNGS, "*.png")) +
               glob.glob(os.path.join(PNGS, "*.PNG")))
if not files:
    print("No PNG templates found in", PNGS, "— nothing to do.")
    sys.exit(0)

con = sqlite3.connect(DB)
cur = con.cursor()

# Existing template names at the target parent — skip exact-name dupes so re-runs are idempotent.
folder_id = None
if USE_FOLDER:
    row = cur.execute(
        "SELECT id FROM objects WHERE type='template_folder' AND name=? "
        "AND parentId IS NULL AND deletedAt IS NULL", (FOLDER_NAME,)).fetchone()
    if row:
        folder_id = row[0]
    else:
        folder_id = str(uuid.uuid4())
        cur.execute("INSERT INTO objects VALUES (?,?,?,?,?,?,?,?)",
                    (folder_id, "template_folder", FOLDER_NAME, None, now, now, None, "{}"))

existing = {r[0] for r in cur.execute(
    "SELECT name FROM objects WHERE type='template' AND deletedAt IS NULL AND "
    + ("parentId=?" if folder_id else "parentId IS NULL"),
    ((folder_id,) if folder_id else ())).fetchall()}

inserted, skipped = 0, 0
for path in files:
    name = os.path.splitext(os.path.basename(path))[0]
    if name in existing:
        print(f"  skip (already present): {name}")
        skipped += 1
        continue
    w, h = png_size(path)
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")   # one line, no wrapping = NO_WRAP
    data = json.dumps({"width": w, "height": h, "image": b64}, separators=(",", ":"))
    cur.execute("INSERT INTO objects VALUES (?,?,?,?,?,?,?,?)",
                (str(uuid.uuid4()), "template", name, folder_id, now, now, None, data))
    existing.add(name)
    inserted += 1
    print(f"  + {name}  ({w}x{h})")

con.commit()

# Fold any WAL into the main file and empty the sidecars, so the pushed DB is self-contained.
cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
con.commit()
con.close()
print(f"\nDone: inserted {inserted}, skipped {skipped}, total PNGs {len(files)}.")
```

Run it:

```sh
python3 migrate_templates.py "$WORK"
```

- **Reconcile:** `inserted + skipped` must equal the PNG count from steps 1 & 2. If a PNG failed to
  decode, the script raises and stops — report the offending filename to the user; don't push a partial
  DB without telling them.
- The script is **idempotent**: re-running skips templates whose name already exists at the target, so
  a retry after a partial run is safe.

### Verify before pushing

```sh
sqlite3 "$WORK/notesprout.db" \
  "SELECT type, COUNT(*) FROM objects WHERE deletedAt IS NULL GROUP BY type;"
sqlite3 "$WORK/notesprout.db" \
  "SELECT name, length(data) FROM objects WHERE type='template' ORDER BY name;"
```

Expect a `template` count equal to your inserted templates (plus any pre-existing) and one
`template_folder` if `USE_FOLDER=True`. `length(data)` should be large (base64 image).

---

## 4. Push back

The app must **not** be running while you overwrite its DB (you force-stopped it in step 1a — keep it
stopped). Push the rebuilt main DB, then **remove the device's stale `-wal`/`-shm`** so the app opens a
fresh, consistent WAL (we already truncated the WAL locally, so the main file is authoritative):

```sh
adb -s "$SERIAL" shell am force-stop "$PKG"          # re-assert, in case the user reopened it
adb -s "$SERIAL" push "$WORK/notesprout.db" "$BASE/notesprout.db"
adb -s "$SERIAL" shell rm -f "$BASE/notesprout.db-wal" "$BASE/notesprout.db-shm"
```

> Do **not** push the local `-wal`/`-shm`. They were merged + truncated in step 3; the device must
> start clean from the main file.

---

## 5. Verify on device

1. Launch the app on the device.
2. Open **Templates** from the MainActivity toolbar (the `ic_template` button).
3. Confirm the **Imported templates** folder exists (or the templates appear at root if
   `USE_FOLDER=False`), with one card per migrated PNG, correct names, and thumbnails that render.
4. Open a notebook → template button → **Browse Templates…** → confirm an imported template applies to
   the current page.

Report to the user: build migrated, template count, folder used, and the verification result.

---

## 6. Cleanup & notes

- The legacy `$BASE/Templates/` PNGs are **left untouched** — migration only *copies* into the index.
  Only delete them if the user explicitly asks (`adb -s "$SERIAL" shell rm -rf "$BASE/Templates"`).
- Local working copy under `/tmp/nsp_migrate_*` can be removed after the user confirms success.
- **If you migrated both builds**, re-run the whole runbook with the other `PKG` and a fresh `$WORK`.
- **Record what you did** at the bottom of `TEMPLATE_SYSTEM_PLAN.md` §4.4 (date, build(s), device,
  template count) so the migration history lives with the plan.

---

## Quick reference

| Thing | Value |
|---|---|
| Debug package | `com.notesprout.android.dev` |
| Stable package | `com.notesprout.android` |
| Index DB (device) | `/sdcard/Android/data/<pkg>/files/notesprout.db` |
| Legacy templates (device) | `/sdcard/Android/data/<pkg>/files/Templates/*.png` |
| G10 serial | `34E517F9` |
| Template object type | `template` |
| Template folder type | `template_folder` |
| `objects` columns | `id, type, name, parentId, createdAt, updatedAt, deletedAt, data` |
| Template `data` JSON | `{"width":W,"height":H,"image":"<base64 NO_WRAP>"}` |
| Template name lives in | the `name` **column** (not the JSON) |
| Timestamps | epoch **milliseconds** |
