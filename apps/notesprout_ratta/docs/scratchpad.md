# The Scratch Pad (arc 11)

A second sheet of paper, always the same one, one tap away from the library or from a notebook —
and **the first thing SN does not own**. The pad is an extension APK (`NSE · Scratch Pad`,
`:ext-scratchpad`) with its own process, its own g-paper surface and its own undo stack. The core
grows no second drawing surface; what it grows is a button and a contract.

This is the pad's own reference. The seam it rides on — the AIDL, the held bind, the extension
store, trust — is [`docs/extensions.md`](extensions.md); the parts of the screen it borrows are
[`docs/sn-screen.md`](sn-screen.md); the notebook it talks to is [`docs/notebook.md`](notebook.md).

**Status: arc 11 complete** — J1 `:sn-screen` · J2 the store · J3 the point · J4 the screen +
both entry buttons · J5 the two transfers · J6 review, docs, freeze.

## Why an extension at all

The pad *could* have been a second Activity in `:app`. It is not, on the user's explicit call — and
that call is the "fresh user decision" the arc-3 extension rule demands before SN may hold a second
capability point. What it bought:

- The core keeps **one** drawing surface. A pad inside `:app` would have been the `RattaNotebookView`
  sibling-copy trap in a new costume: two screens drifting apart on lasso, erase and gesture logic.
- The seam is **proven under load**. A screen-owning point is the hardest thing the extension system
  can be asked to do — an exported Activity, a bind held across a whole showing, ink crossing in
  both directions, and two EPD pipelines swapping hands. It works, and everything after it is easier.
- The pad **writes nothing to disk**. Its pages live in the host's encrypted per-package store,
  lent for the showing and revoked with the unbind.

Two structural moves rode along: `:sn-screen` (J1), the shared paper-screen library both surfaces
build from; and the full extension store (J2), encrypted per-package KV with an ashmem path for
large values.

## The screen

`ScratchPadActivity` (`:ext-scratchpad`) is the notebook's shape, built from `:sn-screen`:
full-bleed g-paper, two thin chrome bars, `PageGestures` for the finger vocabulary, `PaperChrome`
for the exclusion rects, `UndoRedoStack` for the history, `SelectionAnchor` for the floating bar.

| | |
|---|---|
| Top bar | Back · Pen · Eraser · Lasso · "Scratch Pad" (centred on the screen) · **Send** (only with a notebook behind it) |
| Bottom bar | ← · page indicator · → , centred on the screen |
| Tools | **Fixed and they are the notebook's**: PEN, black, the notebook's pen width; the notebook's eraser radius. No panels, no colour, nothing remembered — a pad that lassoed differently one tap from the notebook would read as a bug. |
| Gestures | The notebook's, minus what the pad has no use for: 1-finger horizontal swipe = flip (past the last page, insert one) · 2-finger horizontal swipe = insert before / after · 2-finger stationary double-tap = undo · 3-finger = redo · 1-finger long-press = ask to delete this page. No link follow, no trail walk-back, no Contents, no Recents. |
| Selection | Smart lasso + scribble erase, armed before the listener attaches. The floating bar is Send selection (with a notebook behind) then Delete — Delete last, as on the notebook's bar. |
| Undo | Pad-level and **in memory**: it survives page turns and dies with the screen. |

**The caller check is the first statement in `onCreate`**, before anything is inflated. The screen is
exported (the host launches it by action) and only a `startActivityForResult` from the host package
with a matching signature gets in — a plain `am start` has a null `callingPackage` and is refused.
Verified on the Nomad every phase: `refused caller (none)`.

### The EPD handoff — the arc's headline risk

Two paper surfaces in two processes, one firmware ink pipeline. The ordering is load-bearing:

```
notebook: releaseForHandoff()  →  launch
pad:      onResume → resumeDrawing()
pad:      every exit → finishWithHandoff() = releaseForHandoff() then finish()
notebook: onResume → resumeDrawing()          ← lands BEFORE the pad's window closes
```

The departing window's close must land *after* the caller's reclaim; g-paper's ownership guards are
process-local statics, so the departing side's release has to be its full teardown. It worked both
ways on the Nomad first try, on Back and on a Send exit alike, and needed **no g-paper change** —
the pin stayed 0.1.6. A failure here goes to g-paper, never to a host workaround.

**Back awaits the flush.** The host's result callback runs `end()` → unbind → revoke the moment the
pad finishes, so a save still in flight would hit a revoked binder. The exit flushes under the
page-op lock first, and only then hands off and finishes.

## Pages and the store

The pad's pages are a flat list in the host's store for `com.…notesproutsn.ext.scratchpad`:

| Key | Value |
|---|---|
| `pages` | UTF-8, one page id per line, in order (a page id is a UUID minted by the pad) |
| `current` | the current page id — where the pad opens next time |
| `page/<id>` | the page blob: `ScratchPageCodec` (page size + the strokes) |

Values up to `STORE_MAX_INLINE_BYTES` (512 KiB) go through `put` / `get`; above that through
`putLarge` / `getLarge` over ashmem, the region closed in a `finally` on both sides. A missing
`pages` key is first run — one blank page is created.

**The full rule.** A page blob over `STORE_MAX_VALUE_BYTES` (4 MiB) is refused whole: `PageFullException`,
never split, never written elsewhere. `ScratchDocument` keeps the *exact* encoded running size, so a
stroke that would cross the line is removed and a dialog says so — once per visit, not once per
stroke. On the transfer path the same rule refuses the **whole** placement (below).

Deleting the last page empties it rather than removing it: the pad always has at least one page.

## The transfers

Both directions are **copies**. Both cross **only through the held service** — never the Intent,
never a file. Both carry **no ids** (fresh ones are minted on the receiving side) and keep
**coordinates 1:1**: the pad page and the notebook page are both this device's screen, so a cross-size
page is clipped exactly like any other ink.

### Notebook → pad

1. The selection toolbar's 7th button, **Send to Scratch Pad**, shown only for an ink-only selection.
2. The placement sheet: **New page** / **Current page**.
3. `TransferCaps.withinLimits` — **checked before any bind** (over → "Too much to send").
4. `open` → `begin(store)` → the chunks over `receiveInk(bundle, placement, last)`, `placement` and
   `last` on every one; the last call carries the whole placement and gets a 10 s budget.
5. The service re-checks the **running totals** as chunks accumulate (the untrusted-input half of
   step 3), mints fresh ids, and places through `ScratchStore.receive` **on the Binder thread** —
   New page inserts after the current one at the bundle's size, Current page appends keeping its own.
   The target becomes `current`, so the screen opens on it.
6. The screen is launched with `EXTRA_SCRATCH_OPEN_RECEIVED` and consumes the record **once**: it
   switches to the **lasso before `setSelection`**, selects what arrived, and records **one** undo
   step. The tool the user had comes back pen-idle at dismissal — unless they picked another one
   meanwhile.

### Pad → notebook

1. The top bar's **Send** is the whole current page; the selection bar's **Send** is the lasso's
   strokes. Both `ic_pencil_down`; both **absent** without a notebook behind the pad. An empty pick
   raises "Nothing to send" — never silence.
2. The page is flushed under the page-op lock first (**the pad keeps its ink** — this is a copy), the
   chunks are parked in `ScratchSession`, and the screen finishes with `RESULT_SCRATCH_SEND`.
3. The host drains `takeOutgoing(i)` on the bind it is **still holding** — stopping at the first
   empty bundle, at the summed caps, or at the chunk budget plus one probe past it, so it learns
   whether anything was left behind. The bind is finished **after** the paste, not before it.
4. Every chunk is `requireValid` at unmarshal and then sanitized: unknown style → PEN, width clamped,
   **colour forced opaque black** (SN's ink is fixed black — no colour crosses in).
5. Fresh ids are minted host-side and the strokes are written in one transaction, appended after the
   destination page's current max `"order"` with relative order preserved (the arc-8 rebase rule —
   writing order is load-bearing). One `Action.ObjectsPasted` step, landing **selected** with the
   lasso armed, and the arc-8 "Pasted" toast.
6. Only then `finish()` — `end()`, unbind, revoke.

A paste from the pad lands *selected*, which is exactly the selection the notebook's Pad button
needs — so the whole round trip is finger-drivable.

### What one placement records on the pad's undo stack

| Placement | Action | Undo | Redo |
|---|---|---|---|
| Current page | `Pasted` | removes exactly what arrived | puts exactly it back |
| New page | `Page` (with `afterBlob`) | removes the page **with its cargo** | brings the page back **with its ink** |

`ScratchAction.Page` carries the affected page's ink on *each* side of the move, which is what lets
one shape cover three acts — insert (blank both sides), delete (ink on the `before` side) and a
received new page (ink on the `after` side) — without the arc growing a fifth kind.

## Failure table

Every failure is a dialog that says what happened and what is still true. Toasts only confirm.

| What went wrong | Where | What the user sees | State |
|---|---|---|---|
| No trusted pad installed | host | the button is **GONE** (never disabled — invisible on e-ink) | — |
| Open failed (disabled, replaced, store unreadable) | host | "Scratch pad unavailable" | nothing sent, discovery re-runs |
| Selection over the transfer caps | host, **before any bind** | "Too much to send" | nothing sent |
| The pad's target page would cross 4 MiB | pad → `SCRATCH_PAGE_FULL` → host | "Scratch page is full — nothing was sent" | **nothing placed, no page inserted**; the pad is not opened |
| The drain hit a cap or the chunk budget | host | "Not everything came back" + the pasted count | what came is pasted; the rest is **still on the pad** |
| The drain failed outright, or brought back nothing | host | "Nothing came back" | nothing pasted; the ink is **still on the pad** |
| The paste could not be written | host | "…could not be written. Nothing was changed" | nothing pasted; the ink is still on the pad |
| Send with no ink picked | pad | "Nothing to send" | the pad stays up |
| A stroke would cross 4 MiB while writing | pad | "Page is full" (once per visit) | the stroke is removed, nothing written |
| A page blob will not decode | pad | "Page unreadable" | shown empty and **left untouched** — nothing is written over it |
| The store binder is gone | pad | "Scratch pad unavailable" | the pad finishes |

## Entry points

| Where | Behaviour |
|---|---|
| Library bottom bar, after Recents | opens the pad with **no** Send buttons — there is no notebook to send to |
| Notebook top bar, before Recents | hands the EPD pipeline over first; the pad gets both Send buttons |
| Notebook selection toolbar, 7th button (ink-only) | the outbound transfer above |

`ScratchPadEntry` serves **both doors** — one class, because everything about them is the same except
the one line that is not (the notebook's `releaseForHandoff()`). Two near-identical files would have
been the sibling-copy trap `:sn-screen` exists to keep out of this app. Whether the pad shows its
Send buttons is a property of the **caller**, not of whether ink was handed over: the notebook's
plain top-bar tap must still come back able to send.

The button is `GONE` unless a trusted pad is installed, discovery re-runs on every `onResume` **and
after a failed open**, and a busy guard allows one showing at a time. An `OpeningOverlay` goes up at
the tap and the open runs only once its frame is on the glass: a **cold** open measured 3 123 ms on
the Nomad (SQLCipher's KDF creating the store) against 114 ms warm, and a tap with no answer for
three seconds reads as a tap that missed.

## What the pad is not

- **It opens no `.soil`.** It has no notebook, no page rows, no index. Its ink is its own.
- **The notebook is not sealed behind it** — the one way this hop differs from arc 10's notebook
  switch. What the notebook gives up is the *pipeline*, not its data: its session, its undo stack and
  its unsaved page are all still there when the result comes back, which is exactly what the paste
  lands on.
- **It has no clipboard, no headings, no links.** Five undo kinds against the notebook's fourteen.
- **It never writes to disk itself** — no file, no prefs, no second store.

## Frame silence

The pad carries the SN-wide rule (never present an app frame while `paper.isPenActive`) and adds no
new exception: its frames are the notebook's recorded exceptions in scratch-pad form — the delete
confirm at a long-press, the selection bar's show at lasso completion (and its re-anchor after a
move, and its show over a received placement), the "Opening…" box's hide when the page lands, and a
problem dialog at a pen-up or a chrome tap. Host-side, the pad button's overlay rides the C1
exception: the same act as the Contents and Recents buttons.

## Where the code is

| | |
|---|---|
| `:ext-scratchpad` `ScratchPadActivity` | the screen, the handoff, the received-placement consume, `send()` |
| `ScratchToolbar` / `ScratchSelectionToolbar` | the chrome, the fixed tools, both Send buttons |
| `ScratchDocument` | pages in memory over the store, the exact running size behind the full rule, the undo replay |
| `ScratchStore` / `ScratchPages` / `ScratchPageCodec` | the key layout, the pure list rules, the blob format |
| `ScratchPadService` / `ScratchSession` | the held bind's four methods; the process-wide state a showing lends |
| `ScratchInk` / `ScratchUndo` | wire ⇄ paper on the extension side; the five actions |
| `:extension-api` `IScratchPad.aidl` | `begin` · `receiveInk` · `takeOutgoing` · `end` |
| `WireStroke` / `InkBundle` / `InkChunks` / `ExtensionContract` | the wire types, the chunker, the caps |
| `:app` `ScratchPadClient` | the held bind, `open` / `send` / `drainOutgoing` / `finish` |
| `:app` `TransferCaps` | the host's caps, chunking, sanitize, and its own wire ⇄ paper twin |
| `:app` `ScratchPadEntry` | both entry doors, the busy guard, the overlay, both transfers' host half |
| `:app` `NotebookActivity` / `NotebookSession.pasteStrokes` | the placement sheet, the caps gate, the paste |
