# Paper

> Where thought has a place to grow 🌱

**Paper** is an experimental from-scratch rebuild of [Notesprout](../notesprout_android) — a
handwriting-first, meditative notes app for e-ink devices. It keeps Notesprout's `.soil` container
family, global-index model, global-encryption model, and e-ink design philosophy, and drops
everything else. This is **v0 "paper"**: bare paper, and only what the app truly needs to be a set of
paper notebooks.

- **What it is:** a library of folders and notebooks. A notebook is a stack of pages you write on with
  a pen and flip through — nothing more.
- **Status:** experimental (`0.1.0-paper`, `versionCode 1`). Lives on the `paper` branch; `main` is
  untouched. It installs alongside a real Notesprout on the same device (label "Notesprout Paper",
  applicationId `com.symmetricalpalmtree.notesprout`).
- **License:** MIT.

## What Paper does (v0)

- **Library** — folders + notebooks in a paginated card grid (no scrolling); breadcrumb navigation;
  create / rename / move / delete; pin/unpin with a Pinned view; a Recents view; sort by name or
  last-modified. Cards show the last-open page as a cover.
- **Notebooks** — 1..N pages of pen strokes, drawn on the g-paper (`~/git/g-paper`) surface across
  three engines (Onyx / Ratta-Supernote firmware ink / a generic canvas). Pen · Eraser · Lasso.
  In-memory undo/redo. Flip pages by swiping; insert with a two-finger swipe; delete by long-press.
- **Encryption** — encrypt-by-default under a single global key. A `NSPT-…` recovery key is minted and
  shown once at first launch; that key *is* the passphrase. Files are portable: stock SQLCipher 4
  opens a `.soil` (or the index) with the passphrase on any machine.
- **Portrait, e-ink, no colour** — the existing Notesprout design system verbatim: black ink on white
  paper, Tabler outline icons, no animation, no ripple, no elevation.
- **Extensions** — everything beyond paper arrives as an opt-in, removable **extension**: a separate
  APK with no launcher icon that the core discovers via `PackageManager` and calls over AIDL, trusted
  only when it carries the core's own signature (API v1). The first is **Templates**
  (`:ext-templates`): it offers Lined / Dotted / Grid on the New-notebook screen and renders the chosen
  one into the WEBP the `.soil` stores. Without it the screen has no Template section and notebooks
  are blank; notebooks created with it keep their template wherever they go, extension or not. The
  second is **Naming** (`:ext-naming`): a folder can be given a default-name scheme (`Meeting {date}
  {n:2}`) from the New-folder dialog or the folder's long-press, and +Notebook in that folder opens
  pre-named by it; the schemes live in a **core-owned encrypted store** (`Garden/<pkg>.db`, under the
  global key — the extension never sees a key or a path) that survives the extension's removal.
  Without it every entry point is absent and names are the standard `yyyyMMdd_HHmmss`. The third is
  **ML Kit** (`:ext-mlkit`): the first implementation of the engine-neutral *handwriting recognizer*
  capability point — bare stroke geometry in, plain text out, the ~20 MB `en-US` model downloaded on
  first use into the extension's own sandbox. Nothing user-visible calls it yet — the only caller is
  the debug build's notebook ⋯ → "Recognize page (ML Kit)" (result in a dialog, stored nowhere);
  later extensions will consume it *through the core*, never by binding it themselves. The contract
  lives in `:extension-api`; see `docs/extensions.md` (§"The capability pattern", boundary-audit rows
  14–17). The fourth is **Markdown** (`:ext-markdown`): a second capability point — markdown text in,
  a transparent lossless-WEBP image out (a verbatim port of the original Notesprout's markdown
  parser + spans). The fifth is **Heading** (`:ext-heading`): the reference implementation of the
  generic *object provider* point — it turns lasso'd ink into a heading (`#`-prefixed markdown, six
  levels) through the recognizer and draws it through the Markdown renderer, both lent to it by the
  core as per-bind proxies (it never binds them itself); H3 built the point, the extension and the
  proxies, H4 wired the user story into the notebook screen (lasso → **H** → H1–H6 → heading; re-size,
  tap-to-edit, move, delete, undo — all one step each), H5 froze it (`docs/extensions.md` audit rows
  18–24). **Arc 5 (the Contents)** enhanced the Heading with the original's table of contents:
  `describeOutline` was *appended* to the object-provider interface (the first exercised compatible
  AIDL change — `API_VERSION` stays 1; the core probes an installed provider at load and tolerates an
  older one), the Heading answers it with each heading's words + level, and the core gathers, sorts,
  nests (H1–H6, orphans attached) and draws the **Contents** itself — a top-bar `list` button (only
  once the notebook holds a heading) or a one-finger swipe down the paper opens it as a 60 % sidebar
  (full screen under 480 dp); tap a row to turn to its page (`docs/notebook.md` §"Contents (arc 5)",
  `docs/extensions.md` audit rows 25–27). The sixth is the **Scratch Pad** (`:ext-scratchpad`, arc 6):
  the first extension that owns a *screen* — one global multi-page jotter (pen / eraser / lasso, swipe
  and arrow flips, two-finger insert, undo / redo), reachable from the notebook's top bar and the
  library's bottom bar while it is installed, its pages kept in the host's encrypted extension store
  (≤ 4 MiB each over `SharedMemory`), with two-way ink transfer — lasso → **Pad** → New / Current page
  (the pad opens with it selected) and the pad's Send (page or selection) back into the notebook,
  pasted 1:1 as one undoable step (`docs/scratchpad.md`, `docs/extensions.md` audit rows 28–32).

## What Paper is **not** (v0)

No search · no handwriting recognition · no export / import / backup / restore ·
no calendar · tasks · Today screen · no page index · no headings / text / shapes / links / sticky
notes / documents · no clipboard · no per-notebook keys or passphrase rotation · no template library
in the core (templates come from the Templates extension) · no Extensions UI yet (install/remove by
hand) · no pen colour/width/style panels · no landscape · no Drive · no telemetry. See `PAPER_PLAN.md` →
*Non-goals* for the full list and the reasoning (Paper adds only what the user wants — nothing carries
over from Notesprout just because it exists there).

## Build & install

```sh
cd apps/notesprout_paper
./gradlew assembleDebug               # all modules → app-debug.apk + ext-templates-debug.apk + ext-naming-debug.apk + ext-mlkit-debug.apk + ext-markdown-debug.apk + ext-heading-debug.apk + ext-scratchpad-debug.apk
./gradlew testDebugUnitTest           # all modules
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-templates/build/outputs/apk/debug/ext-templates-debug.apk   # the Templates extension
adb -s <serial> install -r ext-naming/build/outputs/apk/debug/ext-naming-debug.apk         # the Naming extension
adb -s <serial> install -r ext-mlkit/build/outputs/apk/debug/ext-mlkit-debug.apk           # the ML Kit extension (model needs Wi-Fi once)
adb -s <serial> install -r ext-markdown/build/outputs/apk/debug/ext-markdown-debug.apk     # the Markdown extension
adb -s <serial> install -r ext-heading/build/outputs/apk/debug/ext-heading-debug.apk       # the Heading extension
adb -s <serial> install -r ext-scratchpad/build/outputs/apk/debug/ext-scratchpad-debug.apk # the Scratch Pad extension (arc 6 — owns a screen)
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.templates.dev        # BOOX may land it disabled
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.naming.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.mlkit.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.markdown.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.heading.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.scratchpad.dev
```

Seven APKs: the core (`:app`), the Templates extension (`:ext-templates`), the Naming extension
(`:ext-naming` — per-folder default-name schemes such as `Meeting {date} {n:2}`, kept in a core-owned
encrypted store), the ML Kit extension (`:ext-mlkit` — the handwriting recognizer; the only module
that depends on ML Kit), the Markdown extension (`:ext-markdown`), the Heading extension
(`:ext-heading`) and the Scratch Pad extension (`:ext-scratchpad` — arc 6, complete: the first
extension that owns a *screen*; it and `:app` share the `:paper-screen` library — the e-ink resources
and the paper-screen helpers, g-paper as its `api`). All are debug-signed by the same `~/.android/debug.keystore`, which is what satisfies
the same-signature trust rule in dev; an extension built on another machine is not trusted by this
one's core (expected). Extensions have no launcher icon (listed as "NSE · Templates Dev" / "NSE ·
Naming Dev" / "NSE · ML Kit Dev" / "NSE · Markdown Dev" / "NSE · Heading Dev" / "NSE · Scratch Pad Dev") — remove them via Settings → Apps (or `adb uninstall`).

The drawing surface (g-paper) is consumed from **mavenLocal**
(`com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.1`). If a g-paper symbol is unresolved,
run `./gradlew publishToMavenLocal` in `~/git/g-paper` first. Requires a Temurin-17 JDK (pinned in
`gradle.properties`), minSdk 29, arm64-v8a. BOOX trap: `install -r` can leave the package disabled →
`pm enable com.symmetricalpalmtree.notesprout.dev`.

## Where things are

| Concern | Path |
|---|---|
| Project memory / plan (read first) | `PAPER_PLAN.md` (v0), `PAPER_EXTENSIONS_PLAN.md` (arc 1 — extension API + Templates), `PAPER_NAMING_PLAN.md` (arc 2 — extension store + Naming), `PAPER_RECOGNITION_PLAN.md` (arc 3 — handwriting-recognizer capability point + ML Kit), `PAPER_OBJECTS_PLAN.md` (arc 4 — content objects, selection toolbar, Markdown + Heading), `PAPER_CONTENTS_PLAN.md` (arc 5 — the Contents), `PAPER_SCRATCHPAD_PLAN.md` (arc 6 — the Scratch Pad, complete) |
| Standing rules + build facts | `CLAUDE.md` |
| Encryption & launch spine | `docs/crypto.md` |
| Containers & global index | `docs/data.md` |
| Library screen | `docs/library.md` |
| Notebook screen (paper, gestures, pages, undo) | `docs/notebook.md` |
| Extensions (model, contract v1, Templates + Naming + ML Kit + Markdown + Heading extensions, the extension store, the capability + object points, the appended `describeOutline`, boundary audit, writing one) | `docs/extensions.md` |
