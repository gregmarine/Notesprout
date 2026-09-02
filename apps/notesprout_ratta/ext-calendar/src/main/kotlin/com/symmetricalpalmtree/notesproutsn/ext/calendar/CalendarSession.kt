package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.ink.InkTransferSession

/**
 * Process-wide state shared by [CalendarService] (the host's held bind) and `CalendarActivity` (the
 * screen) — they live in the same process. Everything it holds and every rule it holds it under are
 * `:ext-ink`'s [InkTransferSession] since arc 23: the store binder from `begin`, the inbound ink
 * accumulating over `receiveInk` chunks under one monitor with the caps re-check and the page bound
 * by the first chunk, the outbound chunks for `takeOutgoing` and the one-shot "open selected"
 * record. `end()` clears it all. **Nothing here is ever written to disk by the extension itself** —
 * its data lives in the host store.
 *
 * The calendar's two type parameters: the placement is a real type, [CalendarTarget] (which every
 * chunk carries and which is unmarshal-validated), and the record is [CalendarStore.Received].
 *
 * **`recordInboundPageSize = false`** — the sender's page size is dropped, because a calendar page
 * is minted `0 × 0` and takes the screen's size the first time a screen shows it; the notebook
 * page's size is the notebook's. (The pad's answer is the other one, and that difference is the
 * parameter rather than a second copy of this class.)
 */
object CalendarSession : InkTransferSession<CalendarTarget, CalendarStore.Received>(recordInboundPageSize = false)
