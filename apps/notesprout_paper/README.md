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
  contract lives in `:extension-api`; see `docs/extensions.md`.

## What Paper is **not** (v0)

No search · no handwriting recognition · no export / import / backup / restore · no scratch pad ·
no calendar · tasks · Today screen · no page index · no headings / text / shapes / links / sticky
notes / documents · no clipboard · no per-notebook keys or passphrase rotation · no template library
in the core (templates come from the Templates extension) · no Extensions UI yet (install/remove by
hand) · no pen colour/width/style panels · no landscape · no Drive · no telemetry. See `PAPER_PLAN.md` →
*Non-goals* for the full list and the reasoning (Paper adds only what the user wants — nothing carries
over from Notesprout just because it exists there).

## Build & install

```sh
cd apps/notesprout_paper
./gradlew assembleDebug               # all modules → app-debug.apk + ext-templates-debug.apk
./gradlew testDebugUnitTest           # all modules
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-templates/build/outputs/apk/debug/ext-templates-debug.apk   # the Templates extension
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.templates.dev        # BOOX may land it disabled
```

Two APKs: the core (`:app`) and the Templates extension (`:ext-templates`). Both are debug-signed by
the same `~/.android/debug.keystore`, which is what satisfies the same-signature trust rule in dev; an
extension built on another machine is not trusted by this one's core (expected). The extension has no
launcher icon — remove it via Settings → Apps (or `adb uninstall`).

The drawing surface (g-paper) is consumed from **mavenLocal**
(`com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.0`). If a g-paper symbol is unresolved,
run `./gradlew publishToMavenLocal` in `~/git/g-paper` first. Requires a Temurin-17 JDK (pinned in
`gradle.properties`), minSdk 29, arm64-v8a. BOOX trap: `install -r` can leave the package disabled →
`pm enable com.symmetricalpalmtree.notesprout.dev`.

## Where things are

| Concern | Path |
|---|---|
| Project memory / plan (read first) | `PAPER_PLAN.md` (v0), `PAPER_EXTENSIONS_PLAN.md` (extensions arc) |
| Standing rules + build facts | `CLAUDE.md` |
| Encryption & launch spine | `docs/crypto.md` |
| Containers & global index | `docs/data.md` |
| Library screen | `docs/library.md` |
| Notebook screen (paper, gestures, pages, undo) | `docs/notebook.md` |
| Extensions (model, contract v1, Templates extension, boundary audit, writing one) | `docs/extensions.md` |
