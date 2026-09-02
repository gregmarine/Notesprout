package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.notesproutsn.ink.InkTransferSession

/**
 * Process-wide state shared by [ScratchPadService] (the host's held bind) and `ScratchPadActivity`
 * (the screen) — they live in the same process. Everything it holds and every rule it holds it
 * under are `:ext-ink`'s [InkTransferSession] since arc 23: the store binder from `begin`, the
 * inbound ink accumulating over `receiveInk` chunks (J5) under one monitor with the caps re-check
 * and the placement bound by the first chunk, the outbound chunks for `takeOutgoing` (J5) and the
 * one-shot "open selected" record. `end()` clears it all. **Nothing here is ever written to disk by
 * the extension itself** — its data lives in the host store.
 *
 * The pad's two type parameters: the placement is the contract's placement **int**
 * (`PLACEMENT_NEW_PAGE` / `PLACEMENT_CURRENT_PAGE`), and the record is [ScratchStore.Received].
 *
 * **`recordInboundPageSize = true`** — the pad takes the sender's page size from the first chunk,
 * because a placement onto a new page mints that page at the size the ink was authored in. (The
 * calendar's answer is the other one, and that difference is the parameter rather than a second
 * copy of this class.)
 */
object ScratchSession : InkTransferSession<Int, ScratchStore.Received>(recordInboundPageSize = true)
