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

**AlertDialog styling pattern:**
- `dialog.window?.setSoftInputMode(...)` before `show()`
- `dialog.window?.setElevation(0f)` and `setBackgroundDrawableResource(R.drawable.shape_bordered)` after `show()` — window only exists once shown

**Keyboard (IME) dismissal in dialogs:**
- On some BOOX devices the IME does not auto-dismiss on dialog close. Always explicitly hide in button click handlers — **not** `setOnDismissListener`.
- Use `imm.hideSoftInputFromWindow(editText.windowToken, 0)` while the dialog is still alive. `setNegativeButton("Cancel", null)` must become a real listener that also hides the IME.
- Never use the activity's `window.decorView.windowToken` — the IME is bound to the dialog's window and ignores hide requests from the wrong token.
