# Design System — E-Ink First (Never Violate These)

> Referenced from `CLAUDE.md`. The core palette + visual rules live in CLAUDE.md; this doc carries
> the full design system plus the dialog / IME implementation patterns.

**Palette (UI Chrome Only):**
- `inkBlack` = `#000000`
- `paperWhite` = `#FFFFFF`
- `inkLight` = `#888888` — disabled/secondary text only
- `borderGray` = `#CCCCCC` — subtle dividers only (**invisible on e-ink** — use inkBlack for any visible border)
- No color in UI chrome — ever.

**Visual Rules:**
- No shadows, elevation, gradients, or blur
- No Material splash or ripple (`rippleColor=transparent`, `stateListAnimator=null`)
- Animations: none or minimum — never decorative. `android:windowAnimationStyle="@null"` in `Theme.Notesprout` suppresses all system slide/fade transitions globally.
- Borders: 1dp solid inkBlack; corner radius: 4dp
- Typography: clear, high-contrast, black on white

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
