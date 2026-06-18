# Notesprout — Claude Code Project Intelligence

A handwriting-first, meditative notes app. Think paper, but smarter underneath. Built for e-ink
devices first (BOOX), expanding to iPad, Android tablets, phones, and web.

- **Slogan:** "Where thought has a place to grow 🌱"
- **License:** MIT · **Monorepo root:** `~/git/Notesprout`
- `apps/notesprout_android` — Native Android app (primary active codebase)

---

## Detailed Documentation (`docs/`)

CLAUDE.md holds the always-relevant guardrails. Subsystem detail lives in `docs/` — **read the
matching doc before working in that area:**

| Area | Doc |
|---|---|
| Global index (`notesprout.db`) + `.soil` file rules, Room/WAL | [`docs/data-architecture.md`](docs/data-architecture.md) |
| Full e-ink design system, AlertDialog / IME patterns | [`docs/design-system.md`](docs/design-system.md) |
| Toolbar: base, overflow, full customization layer | [`docs/toolbar.md`](docs/toolbar.md) |
| Drawing engines, EPD rules, perf, page snapshots, templates, undo/redo | [`docs/drawing-engine.md`](docs/drawing-engine.md) |
| Heading / Text (+ markdown) / Line objects | [`docs/content-objects.md`](docs/content-objects.md) |
| Link objects: data model, chrome, follow, back-stack, lasso/undo | [`docs/links.md`](docs/links.md) |
| Scribble-erase, smart lasso, snap-to-guide, align & distribute | [`docs/lasso-and-gestures.md`](docs/lasso-and-gestures.md) |
| MainActivity features (browse/search/sort/export/ML Kit) + recents | [`docs/mainactivity-and-recents.md`](docs/mainactivity-and-recents.md) |

Standing backlogs at monorepo root: `CODE_REVIEW_PRUNING.md`, `TOOLBAR_CUSTOMIZATION_PLAN.md`
(Session 8 open), `SUPERNOTE_SUPPORT_PLAN.md`.

---

## Core Philosophy — Never Violate These

- Human-first: fixed screen-size pages, never infinite scroll
- Meditative, paper-like writing experience
- A coexistence of human and machine — intelligent underneath, calm on the surface
- Everything is an object (universal BaseObject model — relational, compositional)
- Pages feel like physical pages. The app should never feel like a web app.

---

## Standard Constraints

These apply everywhere — do not repeat them in feature sections.

- **Language:** Kotlin (Java 17 target — use Temurin-17 JDK; `org.gradle.java.home` in `gradle.properties` pins Temurin-17)
- **JSON serialization:** `kotlinx.serialization` only — zero reflection, code-generated. Never use `org.json`. Use `toJson()` / `fromJson()`.
- **No new Gradle dependencies** without explicit discussion.
- **No Material Components** — `com.google.android.material` is not a dependency; do not add it.
- **Never `runBlocking` on the UI thread** — ANR risk, especially on large stroke/snapshot data.
- **No `Log.d` directly** — use `Slog.d(tag) { "msg" }` (`core/Slog.kt`, `inline fun` gated on `BuildConfig.DEBUG`). Release builds pay zero cost (lambda never evaluated). `Log.e` / `Log.w` survive into release.

---

## Architecture — Foundational Decisions

- Notebook = a `.soil` file (SQLite DB) at `getExternalFilesDir(null)/Garden/<uuid>.soil` — flat dir, UUID filenames, no permissions
- Folder/notebook structure lives **exclusively** in the global index (`notesprout.db`) — never derived from the filesystem
- **`soilFile(context, notebookId)` (`data/SoilFile.kt`)** is the single canonical way to derive a `.soil` path. No other code constructs one.
- Hierarchy: Notebook → Pages → Layers → Content Objects. Layers: base (template, locked) + content layers.
- Every object carries: id, parentId, boundingBox, order, createdAt, updatedAt, deletedAt, data
- Soft deletes only (set `deletedAt`); stable UUIDs everywhere
- Activities receive notebook identity as `EXTRA_NOTEBOOK_ID` + `EXTRA_NOTEBOOK_NAME` — never a `File` object

Full schema, Room setup, and WAL/sidecar rules: [`docs/data-architecture.md`](docs/data-architecture.md).

---

## Design System — E-Ink First (Never Violate These)

**Palette (UI chrome only — no color, ever):** `inkBlack` `#000000` · `paperWhite` `#FFFFFF` ·
`inkLight` `#888888` (disabled/secondary text) · `borderGray` `#CCCCCC` (**invisible on e-ink** — use
inkBlack for any visible border/divider).

- No shadows, elevation, gradients, blur. No Material ripple (`rippleColor=transparent`, `stateListAnimator=null`).
- Animations none/minimal, never decorative (`android:windowAnimationStyle="@null"` in `Theme.Notesprout`).
- Borders 1dp solid inkBlack; corner radius 4dp. Typography: high-contrast black on white.
- Theme is `Theme.AppCompat.Light.NoActionBar`; buttons are `AppCompatButton` with explicit drawable backgrounds.
- **Source of truth — never hardcode:** colors `res/values/colors.xml`, styles `styles.xml`, theme `themes.xml`.

AlertDialog styling + BOOX IME-dismissal patterns: [`docs/design-system.md`](docs/design-system.md).

---

## Build Variants & Install

- **Debug** (`com.notesprout.android.dev`) — active dev; installs alongside stable. **Default — always build/install debug unless told otherwise.**
- **Release** (`com.notesprout.android`) — stable; release installs are always explicit.

```sh
# Debug → app/build/outputs/apk/debug/app-debug.apk
cd apps/notesprout_android && ./gradlew assembleDebug

# Release (unsigned — must sign before sideloading)
cd apps/notesprout_android && ./gradlew assembleRelease
~/development/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out app/build/outputs/apk/release/app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

adb -s <serial> install -r <apk-path>
```

Install all requested devices in a single shell block. If the user says devices are ready, **skip
`adb devices`** — go straight to build and install. Users refer to devices by nickname (e.g. "G10").

### Device Serials & Tiers

| Device | Serial | | Device | Serial |
|---|---|---|---|---|
| BOOX NoteAir5C (NA5C) | `92c16533` | | BOOX Go Color 7 (GC7) | `98d56306` |
| BOOX Note Max (MAX) | `6325773d` | | BOOX NoteAir4C (NA4C) | `1d36f870` |
| BOOX Go 10.3 (G10) | `34E517F9` | | BOOX Tab XC (TXC) | `d852bed0` |
| BOOX Go 7 (G7) | `17845014` | | Wacom Movink Pad 11 (MIP11) | `5HL21V5007384` |
| BOOX Palma2 Pro (P2P) | `287d2364` | | Supernote Nomad (SNN) | `SN078D10012852` |

- **Tier 1 (primary, always-tested):** BOOX Go 10.3 (**flagship**), Note Max, Go 7, Palma2 Pro
- **Tier 2 (QA):** NoteAir5C/4C, Tab XC, Go Color 7 Gen II, Wacom Movink Pad 11 & 14 (GenericDrawingEngine)
- **Future:** iPad + Apple Pencil, iPhone 14, MacBook/Web, Supernote Nomad & Manta (GenericDrawingEngine)

---

## Code Review Pruning List

`CODE_REVIEW_PRUNING.md` (monorepo root) is the standing pruning backlog. Items are IDed by severity:
**C**ritical / **M**oderate / **L**ow (e.g. `C1`, `M3`, `L2`). When the user says "Let's prune C1"
(or any ID), open the file, read that item's entry (files, line numbers, root cause, suggested fix),
and resolve it. Mark resolved items `✅ DONE` in place — never renumber. Add new findings with the
next free ID in their severity tier.

---

## Branch Strategy

- `main` — stable releases only
- `germination` — previous post-MVP feature branch (reference, not active)
- `seed` — current active development

---

## Community Nomenclature

Release notes → Growth Logs · Bug fixes → Pruning · New features → New Branches ·
Contributors → Gardeners · README → The Soil · CLAUDE.md → The Soil for Claude Code
