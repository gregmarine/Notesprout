# Design System — E-Ink First (Never Violate These)

> Referenced from `CLAUDE.md`. The core palette + visual rules live in CLAUDE.md; this doc carries
> the full design system plus the dialog / IME implementation patterns.

**Palette (UI Chrome Only):**
- `inkBlack` = `#000000`
- `paperWhite` = `#FFFFFF`
- `inkLight` = `#888888` — text the user is **meant not to read**: a hint that disappears on the first
  keystroke, a disabled control. Anything carrying information takes `inkBlack`, however secondary it
  is. E-ink washes mid-greys out, and a caption the user has to squint at has failed at the one job it
  had. Make it smaller to make it secondary; do not make it grey.
- `borderGray` = `#CCCCCC` — subtle dividers only (**invisible on e-ink** — use inkBlack for any visible border)
- No color in UI chrome — ever, with exactly one exception (below).

**The ink exception (colour ink, v1.2):**

Colour is allowed in chrome **only where the colour itself is the content** — where the control's
whole job is to choose or report an ink:

- **The pen-colour panel's swatches** (`panel_pen_color.xml` + `PenColorPanelController`). A swatch
  that could not show its colour would be useless.
- **The pen button's icon**, tinted with the armed ink. Without it the only way to learn what colour
  is loaded is to open the panel — a real cost on a device where every panel open is an EPD refresh.

That is the whole list. A status, a highlight, an accent, a category tag, a selected state anywhere
else: still forbidden. Two properties keep the exception from spreading:

- Everything goes through **`core/InkColor.paintColor()`**, which returns black on a greyscale device.
  So on the B&W devices the rule is not merely respected, it is *unchanged* — the panel is hidden and
  the pen icon is black, exactly as before colour existed.
- **Selection is never signalled with colour** inside the panel, because colour is already carrying
  the content. A selected swatch gets a heavier black outer ring plus a white gap ring — which is what
  makes selection legible on the black swatch, where a heavier black border would be invisible.

Colour choices are constrained by hardware, not taste: the Onyx overlay draws an ink as black once its
dominant RGB channel falls below ~180 (`InkColor.MIN_DOMINANT_CHANNEL`, measured on a Kaleido 3
panel) — a *live-preview* limit, since the stroke still stores and renders correctly.
`InkColor.isOverlaySafe()` tests a candidate; `PenPalette` holds the vetted set.

**Greyscale devices (`core/DisplayColor`):**

Most of the fleet cannot show colour at all, so colour ink is offered only where it can be seen.

- **Detection** resolves once at process start (`DisplayColor.init` from `Application.onCreate`, after
  the hidden-API bypass — the probe reflects into a hidden framework call). On BOOX the signal is
  `Device.currentDevice().colorType` (`1` on a NoteAir5C, `0` on the base implementation); **no system
  property exposes this**, the SDK call is all there is. A `0` is ambiguous — genuinely greyscale, or
  a failed reflection — so an explicit model allowlist backs it up. Non-BOOX devices are ordinary
  LCD/OLED and assumed colour, minus a small known-B&W-e-ink deny list.
- **Every ink renders black**, via the single `InkColor.paintColor()` chokepoint. The stored value is
  **never rewritten**: a notebook written in red on a colour panel opens as legible black on a
  greyscale one and is still red when it goes back. Verified in both directions.
- Letting the true colour through instead would be *worse* than black — the panel dithers it to a
  mid-grey and a yellow or light-green note nearly vanishes against white paper.
- **The palette is hidden, not disabled.** `PenColorPanelController.show()` returns early, so
  re-tapping the pen button is the silent no-op it was before colour existed. Disabled controls are
  visually silent on e-ink (they read as broken, not unavailable), which is why the app has none.
- **Exports are unaffected** — `InkColor.exportColor()` is deliberately separate and always true
  colour, because the file leaves the device.
- **Debug builds can force either mode**, so the greyscale path is testable on a colour device (the
  colour devices are Tier 2; the greyscale fleet is Tier 1, so the fallback would otherwise ship
  having never been seen). See `DisplayColor.debugOverride`.

**Visual Rules:**
- No shadows, elevation, gradients, or blur
- No Material splash or ripple (`rippleColor=transparent`, `stateListAnimator=null`)
- Animations: none or minimum — never decorative. `android:windowAnimationStyle="@null"` in `Theme.Notesprout` suppresses all system slide/fade transitions globally.
- Borders: 1dp solid inkBlack; corner radius: 4dp
- Typography: clear, high-contrast, black on white

**Icons — Tabler outline, one house style:**
- Every icon in the app is a [Tabler](https://tabler.io/icons) **outline** glyph (MIT), converted to a
  vector drawable at `res/drawable/ic_<name>.xml`. Icons are the app's one visual vocabulary — mixing
  sets is as jarring on e-ink as mixing fonts.
- The template, identical in all of them: `24dp × 24dp`, `viewport 24×24`, and every path
  `fillColor=@android:color/transparent`, `strokeColor=@color/inkBlack`, `strokeWidth=2`,
  `strokeLineCap/Join=round`. Stroke weight is what makes a set look like a set — never rescale a glyph
  to a different weight.
- Head the file with a comment naming the source (`<!-- Tabler "list-check" icon (outline/list-check.svg) -->`),
  which is both the attribution and the breadcrumb for re-fetching it.
- **Look before you download** — there are ~100 already, and reuse keeps meanings stable: the same
  chevrons serve page navigation in the notebook and in the document editor. To add one, fetch
  `https://raw.githubusercontent.com/tabler/tabler-icons/main/icons/outline/<name>.svg` and map each
  `<path d="…">` to `android:pathData`, dropping any `stroke="none"` bounding rect (it is not part of
  the drawing).
- **Words where they fit; icons where they must.** A mode switch or a commit action reads better as a
  word on e-ink, where an unfamiliar glyph costs a long-press to decode — but only if the row still
  fits. **Measure a chrome row against the narrowest supported device before choosing words**: P2P is
  `sw439dp` (G6 is 571dp, and the 10" devices are far wider, so a row that looks roomy in development
  can drop its last control on a phone-shaped screen). The document editor's header started as
  `Write | Preview | Done` and lost *Done* off the edge there; it is now all icons. A button you cannot
  reach is worse than one you have to learn.
- **Those dp figures are the *default*, not a guarantee.** BOOX exposes a **per-app dp override** in
  its EinkWise settings, so a user can shrink the effective width of any screen at will — a P2P set
  that way lays out nearer `sw379dp` than its nominal `sw439dp`. Anything that packs a fixed number of
  cells across the width should therefore **derive its cell size from the space it is actually given**
  rather than from a dp constant, or it clips on a device that measures fine in development.
  `PenColorPanelController.cellWidthPx()` is the worked example — its eight swatch columns lost the
  last one entirely until they were sized from the available width.
- Give every icon button a **long-press hint naming it**, and use that same string as the content
  description. This is what makes an icon-only row learnable, so it is not optional.

**Source of Truth:**
- Colors: `app/src/main/res/values/colors.xml`
- Styles/typography: `app/src/main/res/values/styles.xml`
- Theme: `app/src/main/res/values/themes.xml`
- Do not hardcode colors or styles — always reference named resources

**What NOT To Do:**
- No color in UI chrome; no shadows/elevation; no decorative animations; no pill-shaped or fully sharp buttons
- Do not use Material Components — theme is `Theme.AppCompat.Light.NoActionBar`; buttons are `AppCompatButton` with explicit drawable backgrounds

**Top guard band (never park tappable chrome against the top edge):**

On BOOX, reaching for a control sitting hard against the top of the screen pulls the Android status
bar down instead of hitting the button. **Every screen must reserve a guard band along the top edge
that no tappable chrome occupies.** `core/TopGuard.kt` is the single source of truth — never
hardcode a value or re-derive the inset:

- `TopGuard.heightPx(context)` — the device's `status_bar_height`, read from the platform resource
  (24dp fallback). It resolves whether or not the bar is currently showing, so a top toolbar lands at
  the same height on an immersive screen as it does in the library.
- `TopGuard.applyInsetPadding(root)` — for screens that leave the system bars **visible**. Pads by
  the live `systemBars()` inset (MainActivity's long-standing behaviour).
- `TopGuard.applyInsetPadding(root, followIme = true)` — same, but the bottom padding also clears the
  **software keyboard**, so a screen that types shrinks instead of hiding its content behind the IME.
  Used by the document editor. Two traps this exists to avoid:
  - `android:windowSoftInputMode="adjustResize"` in the manifest is **not** sufficient. The framework
    reports the inset, but nothing in a hand-built hierarchy consumes it unless a view is told to (no
    `fitsSystemWindows` anywhere), and on a `targetSdk 35` edge-to-edge window the old automatic resize
    is gone entirely. Keep the manifest flag — below API 30 it is what makes `Type.ime()` resolve at
    all — but the padding is what actually moves the layout.
  - Take `max(systemBars.bottom, ime.bottom)`, never the sum: the keyboard covers the navigation bar,
    so adding them leaves a nav-bar-high dead strip under the keyboard.
  - Shrinking the container is only half of it — the room the keyboard takes is the room the caret was
    in. A surface that scrolls its own text must nudge the caret's line back into view on a height
    change (`DocumentEditorActivity.keepCaretVisible`).
- `TopGuard.applyRootPadding(root)` — for **immersive** screens (`controller.hide(systemBars())`).
  Their inset is `0`, so the inset listener is a no-op there and the guard must be reserved outright.
  This is the usual mistake: copying the inset listener onto an immersive screen fixes nothing.

Scope — **the guard applies to anything the user taps, not to drawing bounds:**

- Canvases stay full-bleed and ink is welcome inside the guard band. Do not shrink a drawing surface
  to make room for it. Drawing is excluded only from the chrome's own bounds.
- On BOOX, the pen-exclusion rect should be derived from the chrome's laid-out position
  (`NotebookActivity.computeToolbarExclusionRect()` reads `tb.top`), so it tracks a guard shift with
  no extra code.
- A vertical (LEFT/RIGHT) bar still touches the top edge — give it the guard too.
- Draggable and free-positioned chrome clamps against the guard as its minimum Y: the FLOAT toolbar
  (both while dragging *and* when restoring a saved position from before the guard existed),
  popovers, overflow menus, and secondary toolbars.

Chrome that starts below the top edge needs a **1dp inkBlack top border** of its own — otherwise it
floats. See `toolbar_background_top/_left/_right.xml` and `shape_toc_panel_border.xml`.

Watch for layouts that shrink a canvas as a side effect: in a vertical `LinearLayout` root, root
padding pushes the toolbar down *and* shortens everything below it. Prefer the `NotebookActivity`
model — a full-bleed canvas with the toolbar overlaying it in a `FrameLayout` — for any screen whose
content geometry depends on canvas height. (`BACKLOG.md` records the calendar Day view, which derives
its row pitch from canvas height and misaligns when shortened.)

**AlertDialog styling pattern:**
- `dialog.window?.setSoftInputMode(...)` before `show()`
- `dialog.window?.setElevation(0f)` and `setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)` after `show()` — window only exists once shown

**Floating-window border weight:** anything that floats over a screen — dialogs, prompts, messages,
the scratch pad and sticky note editor windows — uses `shape_dialog_bordered` (**2dp** inkBlack,
4dp radius). With no shadows or elevation on e-ink, the heavier stroke is what lifts a window off
the page. Inner chrome *inside* those windows (buttons, cards, inputs, swatches) stays on
`shape_bordered` at 1dp. A panel that paints its own background behind the stroke needs padding
equal to the stroke width (2dp) so the border isn't covered.

**Keyboard (IME) dismissal in dialogs:**
- On some BOOX devices the IME does not auto-dismiss on dialog close. Always explicitly hide in button click handlers — **not** `setOnDismissListener`.
- Use `imm.hideSoftInputFromWindow(editText.windowToken, 0)` while the dialog is still alive. `setNegativeButton("Cancel", null)` must become a real listener that also hides the IME.
- Never use the activity's `window.decorView.windowToken` — the IME is bound to the dialog's window and ignores hide requests from the wrong token.
