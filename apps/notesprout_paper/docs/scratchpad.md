# Scratch Pad — `NSE · Scratch Pad` (arc 6)

The scratch pad is Paper's one global, multi-page jotter: reachable from the notebook's top bar and
the library's bottom bar, persisted across restarts, with two-way ink transfer to and from the
notebook it was opened from (S2). It is **an extension that owns a screen** — the first exercise of the
UI rule's tier 2 (`docs/extensions.md` §"ScratchPad (contract)"): the core launches
`ScratchPadActivity` for a result and returns from it; the core grows no second drawing surface.
This file is the extension's own reference — the screen, its tools, pages, store layout and failures.
Contract, host clients and the boundary rules live in `docs/extensions.md`; the notebook side in
`docs/notebook.md` §"Scratch Pad (arc 6)"; the library side in `docs/library.md`. Plan:
`PAPER_SCRATCHPAD_PLAN.md` (S0 ✅ · S1 ✅ · S2 🧪 · S3 ⬜).

## Module (`:ext-scratchpad`)

Package `com.symmetricalpalmtree.notesprout.ext.scratchpad` (debug `.dev`), label `NSE · Scratch Pad`
(+ ` Dev`), puzzle icon, no launcher Activity. Depends on `:extension-api` + `:paper-screen` (g-paper
arrives through it — hence the ~25 MB APK and the manifest's `tools:replace` / libc++ `pickFirsts`),
never `:app`; no Room / SQLCipher / serialization — **its data lives in the host's extension store**
(the arc-2 rule; the extension writes nothing to disk itself, ever).

| File | Role |
|---|---|
| `ScratchPadApplication` | `OnyxEngine.register(this)` + `RattaEngine.register()` — the pad's process hosts a paper surface, so it registers g-paper's engines itself (rule 27) |
| `ScratchPadService` (`IScratchPad.Stub`) | the held bind: `begin(store)` holds the store binder in `ScratchSession` (reads the page list on the Binder thread — first run creates one page; logs `pages=n`), `end()` clears it; `receiveInk` (S2: chunks accumulate, caps re-checked, on `last` placed through `ScratchStore.receive` on the Binder thread) and `takeOutgoing` (S2: chunk *i* of what Send parked, empty past the end). `HostCallerCheck.enforce` first in every method |
| `ScratchSession` (object) | process-wide: the held store binder, the inbound ink accumulating over chunks, the outbound chunks, the one-shot `received` record (page + stroke ids to open selected) — the Service and the Activity share the process |
| `ScratchInk` (pure, JVM-tested) | the extension's wire ⇄ paper mappings: inward fresh ids / `timeMillis 0` / unknown style → PEN / width clamped 0.5..50 px; outward geometry + width + colour + style name, id and time dropped (the host's twin is `TransferCaps`; `:paper-screen` cannot host a shared one — it never sees `:extension-api`) |
| `ScratchStore` | the key layout over `IExtensionStore` (below), blocking — called on IO / the Binder thread only; `receive` = the notebook → pad placement (S2) |
| `ScratchPageCodec` (pure, JVM-tested) | page blob ⇄ (pageWidth, pageHeight, strokes); `HEADER_BYTES`, `strokeBytes(stroke)` |
| `ScratchPages` (pure, JVM-tested) | the id-list math (insert after / before, delete, clamp) over the shared `PageMath` |
| `ScratchDocument` (JVM-tested over a fake store) | the pages in memory + persistence: page list, current page's strokes (writing order), page size, the running encoded size, `load` / `goTo` / `insert` / `deleteCurrent` / `flush`, the mutations `add` / `remove` / `translate`, and the undo replay `revert` / `reapply` |
| `ScratchUndo` | the pad's action set: `Drew` · `Erased` · `Moved` · `Page` · `Pasted` (S2 — ink that arrived from the notebook; undo removes it) |
| `ScratchPadActivity` | the screen (below) |
| `ScratchToolbar` | the top bar over the shared `PaperToolbar` + the Send button's visibility |
| `ScratchSelectionToolbar` | the floating Delete · [Send] bar, anchored by the shared `ToolbarAnchor` |
| `ScratchDebugMenu` (`src/debug`, no-op twin in `src/release`) | ⋯ → "Store size" (keys + page bytes as a toast; removed in S3) |

## The screen (`ScratchPadActivity`)

**Launch + trust.** Exported, custom action `ACTION_SCRATCH_PAD_SCREEN`, `<category DEFAULT>`, no
launcher filter, portrait. First thing in `onCreate` — before anything is inflated —
`HostCallerCheck.enforceActivity(this, HOST_PACKAGE)`: `callingPackage` must be the host **and** share
the signature; a refusal finishes the Activity (`am start` from a shell → `refused caller (none)`).
The only Intent data: `EXTRA_SCRATCH_SEND_ENABLED` (opened from a notebook → the Send buttons show)
and `EXTRA_SCRATCH_OPEN_RECEIVED` (S2). The store comes from `ScratchSession.store` — the binder the
host handed to `begin` on the held bind; absent (no `begin`) → `scratch_store_unavailable` → finish.

**Shape — the notebook's.** Full-bleed g-paper (`GPaper.create`, the pad's own engine in its own
process), immersive, `TopGuard.applyRootPadding(topBar)`; top bar **Back · "Scratch Pad" · [Send] ·
[debug ⋯]**; bottom bar **Pen · Eraser · Lasso … `<` · `n / N` · `>`** (S1 follow-up, user's call: the
title up, the tools down — the arrows are `ToolbarButton`s, never disabled, a no-op at a bound); the
floating **Delete · [Send]** bar over a lasso
selection. Tools fixed: `PEN_WIDTH_PX 3f`, `ERASER_RADIUS_PX 15f`, black, smart lasso / scribble
erase off. Chrome geometry through the shared `PaperChrome` — the top bar, the bottom strip and the
selection bar are exclusion rects; **while the "Opening…" popup is up the whole paper is one exclusion
rect** (pen input refused until the first page is on the paper — S1 Q1); the chrome release on a finger
landing on chrome and the frame-silence rule for the strip text (`whenPenIdle`) are the notebook's.
`resumeDrawing()` in `onResume`, `release()` in `onDestroy`.

**Open.** `ScratchDocument.load()` on IO (page list + the remembered current page's blob) → wait for
the paper's first layout (a page without a size takes **the surface size** — `ensurePageSize`, written
into the blob on the first save) → `setPageSize` + `loadStrokes` → `opened` → the popup comes down and
the chrome rects replace the whole-paper block. Measured S1: MIP11 ≈ 0.13 s cold process / 0.04–0.05 s
warm (page + 1–3 strokes); the host side (`ScratchPadClient.open`: store + hold + `begin`) ≈ 0.34 s
cold / 0.02 s warm on MIP11.

**Ink → document.** `onStrokeCommitted` → `document.add` (the full rule below; refused → the stroke is
removed from the paper again + `scratch_page_full` **once per page visit**) → `Drew` → a debounced
save; `onStrokesErased` → `remove` → `Erased`; `onSelectionMoved` → `translate` → `Moved` + the
selection bar re-anchored; the selection bar's Delete → `remove` + `paper.removeStrokes` (a data-in
call — no erase callback comes back) → `Erased`.

**Pages.** One-finger swipe flips; **past the last page it inserts** (the arc-1 rule); two-finger
horizontal swipe inserts before / after; the strip's arrows flip; finger long-press → sheet "Delete
this page" → "Delete this page and its ink?" → delete (the **last page is emptied, never removed**);
`n / N` follows. Every page op runs under one `pageOps` mutex (like the notebook) and **flushes the
page it leaves first**. The current page id is persisted (`current`) — the pad reopens where it was
left.

**Undo / redo.** `UndoRedoStack<ScratchUndo.Action>` (the shared generic stack, `MAX 100`), pad-level —
survives page turns, cleared when the screen closes. Multi-finger double-tap: 2 = undo, 3 = redo
(shared `PageGestures`). Replay = `ScratchDocument.revert` / `reapply`: a stroke action on another page
turns to it first (`goTo`); an action whose page is gone is dropped; a `Page` action (insert / delete)
moves the page list between its `before` / `after` states, **restoring the deleted page's ink** from
the blob captured when it went (and capturing it again when redone). After every replay the current
page is re-shown wholesale (`loadStrokes` — one refresh) and a save is scheduled.

**Saves (S1 Q5).** `SAVE_DEBOUNCE_MS 800` after every edit (`saveRunnable` on the root view), plus a
flush on page leave, in `onPause`, and **on Back — awaited before `finish()`** (the host's result
callback runs `end()` → unbind → revoke right after, so a save left in flight would hit a revoked
binder); a destroy that is not a normal close flushes on a process-wide scope. `flush` snapshots the
strokes on Main and encodes + writes on IO; `dirty` is cleared before the write and restored if it
fails (the next flush retries). Log: `savePage n strokes, b bytes in t ms` — counts and sizes, never
ink.

**The full rule.** `ScratchDocument` keeps the page's **exact** encoded size as a running total
(`HEADER_BYTES + Σ strokeBytes` — each stroke is encoded on its own, so the sum is exact; a move
re-measures the moved strokes because the geometry is zlib-compressed per stroke). `add` refuses a
stroke that would push it past `STORE_MAX_VALUE_BYTES` (4 MiB): nothing written, nothing split; the
screen removes the stroke from the paper and says `scratch_page_full` once per page visit. A replay
that lands over the cap (an undo re-adding ink after the page filled) stays in memory and says the
same — the blob is written again once something is removed.

## Transfers (S2)

Both directions are **copies**, cross **only through the held service** (never the Intent, never a
file — rule 25), carry **no ids** (fresh ids minted on the receiving side), and keep the strokes'
**coordinates 1:1** (S2 Q1 — the pad page and the notebook page are both the device's screen; a
cross-device page is clipped by the page like any other ink). Wire form: `InkBundle` chunks of
`PaperStroke` (`docs/extensions.md` §"ScratchPad (contract)"), chunked per Binder call by the
shared `InkChunks` (300 strokes / 20 000 points); a whole transfer ≤ `MAX_TRANSFER_STROKES` 10 000 /
`MAX_TRANSFER_POINTS` 400 000.

**Notebook → pad (`receiveInk`).** The core's `scratch` action ("Pad") on an ink-only lasso → the
placement sheet (New page / Current page) → caps checked **before any bind** → `open` (store, hold,
`begin`) → the chunks (`placement` + `last` on each) → toast "Sent to scratch pad" → the screen
launched with `EXTRA_SCRATCH_OPEN_RECEIVED`. The service re-checks the running totals against the
caps (over → `IllegalArgumentException`, the inbound dropped), and on `last` mints ids
(`ScratchInk.toStrokes`) and places them on the Binder thread through **`ScratchStore.receive`**:
**New page** = a page inserted after the current one (size = the bundle's page size; `0 × 0` → the
surface size at first layout) · **Current page** = appended to the current page's blob (its own size
kept; the bundle's if it has none yet); the target page becomes `current`, so the screen opens on it.
The **full rule** applies to the result: over 4 MiB → `IllegalStateException(SCRATCH_PAGE_FULL)` —
nothing placed, nothing inserted (the host says "The scratch pad's page is full — send to a new page").
The record of what landed (`ScratchSession.received` = page id + stroke ids) is consumed **once** by
the screen: after `opened`, if the record's page is the current one, the screen switches to the
**lasso** (a selection under the pen can't be dragged or dismissed; the prior tool comes back pen-idle
when the selection is dismissed, unless the user picked another tool meanwhile), selects the strokes
(host-initiated `setSelection` — no `onSelectionCreated` echo; the Delete · Send bar shows) and records
**one step** on the pad's stack: a **New page** placement as `Action.Page` (undo removes the page that
came with it — `Received.pagesBefore` / `currentBefore`; redo brings it back with its ink), a
Current-page one as `Pasted` — **undo on the pad removes what just arrived, redo restores it** (S2 Q5).

**Pad → notebook (`takeOutgoing`).** The top-bar **Send** = every stroke on the current page; the
selection bar's **Send** = the lasso's strokes — both in writing order. An empty pick → the
`scratch_nothing_to_send` dialog (a tap that did nothing is a dialog, never silence). Otherwise the
page is **flushed first** (the pad keeps its ink), the strokes are mapped outward and chunked into
`ScratchSession.outbound`, and the screen finishes with `RESULT_SCRATCH_SEND`. The host then drains
`takeOutgoing(0..)` on the still-held bind until an empty bundle, the summed caps or the chunk budget
(`TransferCaps.Drain` — a cut is said with `scratch_truncated`), sanitizes, mints ids and pastes
(`docs/notebook.md` §"Scratch Pad"); only then `end` → unbind → revoke. Both Send buttons exist
only when the pad was opened from a notebook (`EXTRA_SCRATCH_SEND_ENABLED`).

## Store layout (`ScratchStore`, over the host's `IExtensionStore`)

| Key | Value |
|---|---|
| `pages` | UTF-8, one page id per line, in order (a page id = a random UUID minted by the extension) |
| `current` | the current page id |
| `page/<id>` | the page blob (`ScratchPageCodec`) — absent until the page is first saved |

Values ≤ `STORE_MAX_INLINE_BYTES` (512 KiB) go through `put` / `get`; above it `putLarge` / `getLarge`
(`SharedBytes` — the region the extension creates is closed after the call; the one the host returns
is read and closed in a `finally`); `readPage` tries `get` first and falls to `getLarge` on
`STORE_VALUE_LARGE`. A missing `pages` = first run → one blank page. A page delete deletes its blob
(`deletePage`); the undo path uses `setPages` / `removePageBlob` / `savePage`. Every store exception →
`StoreUnavailable`. Nothing is vacuumed (no compaction in Paper — Deferred).

**Blob** (`ScratchPageCodec`, big-endian): `u8 version(1) · f32 pageWidth · f32 pageHeight · u32 count ·
per stroke { u16 idLen + UTF-8 id · f32 width · i32 colorArgb · u8 styleNameLen + ASCII name · u32 blobLen
+ StrokeCodec format-B blob }` — the `.soil` stroke encoding with a header; a truncated tail drops the
partial stroke, a malformed geometry blob skips that stroke, an unknown version is unreadable.

## Failures (what the user sees — all the extension's own state, rule 5)

| Situation | Result |
|---|---|
| Launched without `callingPackage` / by a non-host / a foreign signature | finished before anything shows (`refused caller …`) |
| No store held (`begin` never ran), or any store exception while loading / flipping / saving | `scratch_store_unavailable` dialog (OK → finish, `RESULT_CANCELED`) |
| A stroke that would push the page past 4 MiB | removed from the paper; `scratch_page_full` once per page visit |
| A replay that lands over the cap | kept in memory, same dialog; written once something is removed |
| Back | the current page flushed, `RESULT_CANCELED`, the host finishes the bind |
| Send with no ink on the page / in the selection | `scratch_nothing_to_send` dialog, nothing sent |
| Send | the page flushed, the strokes parked, `RESULT_SCRATCH_SEND` — the host drains, pastes, then finishes the bind |
| `receiveInk` over the caps (host-side bug — the host checks first) | `IllegalArgumentException`, inbound dropped; the host says "didn't respond" |
| `receiveInk` that would cross 4 MiB | `IllegalStateException(SCRATCH_PAGE_FULL)`, nothing placed; the host's `scratch_page_full_host` dialog, the pad not opened |
| `receiveInk` with the store gone | `IllegalStateException("store unavailable")`; the host says "didn't respond" |
| Home / screen off | `onPause` flush; the host keeps the bind until its result callback |

## Entry points (core side — `docs/notebook.md`, `docs/library.md`)

Both exist **only while a trusted `SCRATCH_PAD` extension is installed** (re-discovered on every
resume): the notebook's top-bar **sketching** button (after Lasso, before the debug ⋯ — S1 Q2;
`ScratchPadFlow`, Send enabled, `paper.releaseForHandoff()` immediately before the launch) and the
library's bottom-bar **sketching** button after Recents (S1 Q3; `ScratchPadLaunch`, no send target). Both
run `ScratchPadClient.open` (store pre-open → held bind → `begin`) → an `ActivityResultLauncher` →
on any result `finish` (`end` → unbind → revoke) — `docs/extensions.md` §"ScratchPad (contract)".
