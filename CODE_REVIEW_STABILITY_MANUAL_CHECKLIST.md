# Stylus / manual test checklist — pruning-stability on G102
(The dev app has TestAlpha [GLOBAL], TestGamma [private, passphrase `newpass99`], EvilBook.
 Automated tests can't drive the Onyx raw-pen pipeline — these need your hand.)

## Phase 2 — the paste crash (HIGHEST priority regression test)
1. Open TestAlpha. Draw a few strokes. Lasso-select them and **create a Link** from the selection.
2. Lasso the link → **Copy** → **Paste on the same page**. → must NOT crash (was UNIQUE-1555).
3. Paste the same clipboard **again** (twice total). → must NOT crash.
4. Repeat with a **sticky note**: create one, lasso-copy, paste twice. → no crash.
5. Draw strokes, don't let them recognize (scribble) → lasso → make it a link containing that
   fallback content → copy/paste. → no crash.

## Phase 3 — shape transform on calendar/scratchpad (silent-loss regression)
6. Open Scratch Pad. Draw a shape (or convert a stroke to a shape). Lasso-drag it once
   (makes the row columnar). Then **resize/rotate** it in transform mode. Leave the page and come
   back. → the shape must KEEP its new size/rotation (was silently snapping back).
7. Same on a **Calendar** month page and a **Day note**.

## Phase 3 — sticky-note editor process-death durability
8. Open a sticky note editor, write something, then (with USB) run:
   `adb -s b7a46e13 shell am kill com.notesprout.android.dev` while the editor is open,
   reopen the app → the editing session/note should be preserved (not silently discarded).

## Phase 4 — events semantics (calendar)
9. Create a **recurring** event (e.g. yearly "Birthday" starting a past year). Tap a future
   occurrence → Edit → change ONLY the title → "All events in the series". → past occurrences and
   the original **anchor year must be preserved** (not re-anchored to the occurrence you tapped).
10. Editing an occurrence override / "this and following": the **reminders must carry over**.
11. In the event editor, set an **"ends on" date before the start date** → Save must be **blocked**
    with a message (not save an invisible, undeletable event).

## General e-ink feel
12. The new Bootstrap "Couldn't open your library" error dialog (hard to trigger normally) and the
    various toasts should render acceptably on e-ink if you hit them.
