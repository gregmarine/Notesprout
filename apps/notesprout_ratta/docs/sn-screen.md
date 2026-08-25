# `:sn-screen` — the shared paper-screen library

*Arc 11 / J1. Read this before adding, moving, or removing anything in the module.*

SN grew a second paper surface in arc 11: the Scratch Pad, which lives in its own extension APK,
in its own process, with its own g-paper canvas. Both surfaces need the same e-ink design
resources and the same handful of screen helpers, and the alternative to sharing them is the
sibling-copy trap og Notesprout still carries — `RattaNotebookView` is a hand-maintained copy of
`GenericNotebookView`, and every fix to shared logic has to be applied to both files or one of them
silently rots. `:sn-screen` is that trap refused up front.

## What the module is allowed to depend on

g-paper (`api`, so `PaperView` and `Stroke` reach both consumers transitively) and androidx
(`core-ktx`, `appcompat`). **Never `:app`** — the module has to build with the host absent — and
**never `:extension-api`**. That second exclusion is deliberate and load-bearing: keeping the
contract out of here is what makes the host's transfer mapping and the extension's own ink mapping
two twin translations rather than one shared class that quietly becomes part of the wire format.
No Room, no SQLCipher, no serialization: nothing here knows what a `.soil` is.

`:app` depends on `:sn-screen`; so will `:ext-scratchpad`. g-paper is **not** declared in either
consumer — it arrives through this module's `api(...)`, and the version pin lives here.

## The namespace and the R-class flag

The module's Android namespace is `com.symmetricalpalmtree.notesproutsn.screen` — deliberately not
the app's, because two modules sharing a namespace would collide on `R` and `BuildConfig`. The
Kotlin **packages** of everything that moved are unchanged (`…notesproutsn.core`,
`…notesproutsn.notebook`), which is why the move needed no import sweep in `:app`; the only three
lines that changed are the `R` / `BuildConfig` imports of `Dialogs`, `ActionSheetDialog` and
`Slog`, which now point at the module's own.

`gradle.properties` sets **`android.nonTransitiveRClass=false`**. AGP 8.11 defaults it to
non-transitive, and without the line every moved resource falls out of `:app`'s `R` — hundreds of
compile errors. Do not remove it.

`Slog` gates on **this module's** `BuildConfig.DEBUG` (`buildFeatures.buildConfig = true`). The
app's debug build consumes the library's debug variant, so the gate means exactly what it meant in
`:app`. Any module whose tested code reaches `Slog` also needs
`testOptions.unitTests.isReturnDefaultValues = true`.

## What lives here

| Kotlin | What it is |
|---|---|
| `core/StrokeCodec`, `core/InkColorCodec` | the format-B stroke blob and the ink-colour token — the family's byte-compatible encodings |
| `core/Slog` | the debug-gated logger |
| `core/Dialogs` | the bordered-window `AlertDialog` helpers (`problem`, and the window styling every dialog routes through) |
| `core/ActionSheetDialog` | the "what do you want to do with this?" sheet — hairline-separated rows built in code, because the row *count* is the content |
| `core/TopGuard` | the top-edge guard — **0 on Ratta**, where chrome sits flush at the top |
| `core/Immersive` | system bars hidden, transient by swipe |
| `notebook/PageMath` | page-index arithmetic |
| `notebook/SelectionAnchor` | where a floating bar may sit relative to a selection |
| `notebook/PageGestures` | the finger vocabulary — flips, inserts, the two swipes, the multi-finger undo/redo taps, the long-press. Pen-gated throughout |
| `notebook/UndoRedoStack<A>` | the generic LIFO history plus its `generation` counter. The notebook's fourteen action kinds stay in `:app` as `NotebookUndo.Action` |
| `notebook/PaperToolbar` | back + the three tool buttons, **binding-free** |
| `notebook/PaperChrome` | exclusion rects and the over-chrome hit test, with the host-specific parts as suppliers |

Resources: `values/{colors,dimens,styles,themes}`, `values-sw720dp/dimens`, the 39 chrome
`ic_*.xml` (the 37 that moved plus `ic_sketching` and `ic_pencil_down`), the button/border/radio
drawables the moved styles reference, and a `strings.xml` holding only `ok` and `cancel` — the two
strings the moved helpers reference themselves. Every other string stays in `:app`.

**`ic_launcher_foreground.xml` and every `mipmap-*` stay in `:app`.** The launcher glyph is the
host's identity, not shared chrome; the Scratch Pad extension draws its own.

## Two helpers that were written fresh, not moved

- **`PaperToolbar`** is not `NotebookToolbar` relocated. The notebook's is hard-bound to
  `ActivityNotebookBinding` and carries the clipboard-loaded icon swap and the lasso re-tap, so it
  stays in `:app`. `PaperToolbar` takes the views themselves and does only what a spartan second
  surface needs. Both obey the same two rules: release the render first but **pen-gated**, and
  `sync` is the truth rather than our taps (g-paper arms and restores tools on its own).
- **`PaperChrome`** is not `NotebookActivity.pushExclusions` relocated. The notebook's also carries
  `paper.snapMarginPx` (arc 9) and reads its Contents and Recents flows by name; it stays exactly
  where it is, and adopting the helper in the notebook was explicitly not arc 11's business. What
  the two share is the *shape*, so the host-specific parts arrive as `extraRects` /
  `extraContains` / `blockAll` suppliers.

## When you change something here

A change to a shared helper reaches two screens in two processes. The notebook is the older
consumer and the one with a full test suite behind it — check it as well as the pad, and keep
anything notebook-specific in `:app` rather than growing a parameter here for it.
