package com.symmetricalpalmtree.notesproutsn.ink

import android.os.IBinder
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.InkBundle
import com.symmetricalpalmtree.notesproutsn.extension.StorePayload
import com.symmetricalpalmtree.notesproutsn.extension.StoreResult
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shared transfer session's refusals and its accumulate-and-place, statement-shaped: no Binder,
 * no store, nothing but the pure rules the two services used to hold a copy of each. What is pinned
 * here is exactly what an untrusted host could get wrong — a transfer over the caps, a placement
 * that changes mid-transfer, a store that has gone away — plus the one documented difference
 * between the pad and the calendar (`recordInboundPageSize`).
 */
class InkTransferSessionTest {

    /** A placement type with real equality, standing in for the pad's int and the calendar's target. */
    private data class Target(val name: String)

    private class Session(record: Boolean) : InkTransferSession<Target, String>(recordInboundPageSize = record)

    /** Enough of the store to be handed across; nothing here ever runs a statement. */
    private class NoStore : IExtensionStore {
        override fun asBinder(): IBinder? = null
        override fun schemaVersion(): Int = 0
        override fun applySchema(schema: StoreSchema?) = Unit
        override fun exec(batch: StorePayload?): LongArray = LongArray(0)
        override fun query(statement: StorePayload?): StoreResult? = null
        override fun next(handle: Int): StoreResult? = null
        override fun close(handle: Int) = Unit
    }

    private fun wire(points: Int = 1) = WireStroke(
        FloatArray(points), FloatArray(points), FloatArray(points), FloatArray(points),
        3f, Stroke.BLACK, "PEN",
    )

    private fun bundle(strokes: Int, points: Int = 1, w: Float = 100f, h: Float = 200f) =
        InkBundle(List(strokes) { wire(points) }, w, h)

    private fun opened(record: Boolean = false): Session = Session(record).also { it.begin(NoStore()) }

    // ── The happy path ───────────────────────────────────────────────────────

    @Test
    fun chunksAccumulateAndOnlyTheLastOnePlaces() {
        val s = opened()
        var calls = 0
        val place: (IExtensionStore, List<Stroke>, Target) -> String = { _, strokes, t ->
            calls++
            "${t.name}:${strokes.size}"
        }
        assertNull(s.receiveChunk(bundle(2), Target("a"), last = false, place = place))
        assertEquals(0, calls)
        assertEquals(2, s.inbound.size)

        val placed = s.receiveChunk(bundle(3), Target("a"), last = true, place = place)!!
        assertEquals(1, calls)
        assertEquals("a:5", placed.received)
        assertEquals(5, placed.strokes)
        // The record is left for the screen to consume once, and the inbound is empty again.
        assertEquals("a:5", s.received)
        assertTrue(s.inbound.isEmpty())
        assertEquals(0, s.inboundPoints)
        assertNull(s.inboundPlacement)
    }

    @Test
    fun theStrokesHandedToThePlacementHaveFreshIdsAndTheWiresGeometry() {
        val s = opened()
        var seen: List<Stroke> = emptyList()
        s.receiveChunk(bundle(2, points = 4), Target("a"), last = true) { _, strokes, _ -> seen = strokes; "ok" }
        assertEquals(2, seen.size)
        assertEquals(4, seen[0].points.size)
        assertEquals(2, seen.map { it.id }.toSet().size)   // minted here, never the wire's
    }

    // ── The refusals ─────────────────────────────────────────────────────────

    @Test
    fun aPlacementThatChangesMidTransferIsRefusedAndDropsTheWholeInbound() {
        val s = opened()
        s.receiveChunk(bundle(2), Target("a"), last = false) { _, _, _ -> "never" }
        try {
            s.receiveChunk(bundle(2), Target("b"), last = false) { _, _, _ -> "never" }
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertEquals("placement changed mid-transfer", e.message)
        }
        assertTrue(s.inbound.isEmpty())
        assertNull(s.inboundPlacement)
        assertNull(s.received)
    }

    @Test
    fun aTransferOverTheStrokeCapIsRefusedAndDropsTheWholeInbound() {
        val s = opened()
        val chunk = ExtensionContract.TRANSFER_CHUNK_STROKES
        val whole = ExtensionContract.MAX_TRANSFER_STROKES
        var sent = 0
        try {
            while (sent <= whole) {
                s.receiveChunk(bundle(chunk), Target("a"), last = false) { _, _, _ -> "never" }
                sent += chunk
            }
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.startsWith("transfer over the caps"))
        }
        assertTrue(s.inbound.isEmpty())
        assertNull(s.received)
    }

    @Test
    fun aTransferOverThePointCapIsRefused() {
        val s = opened()
        // One stroke over the per-chunk point cap is a legal bundle (never split); two of them are
        // over the whole-transfer cap, which is the running total no single bundle can see.
        val huge = ExtensionContract.MAX_TRANSFER_POINTS / 2 + 1
        s.receiveChunk(bundle(1, points = huge), Target("a"), last = false) { _, _, _ -> "never" }
        try {
            s.receiveChunk(bundle(1, points = huge), Target("a"), last = false) { _, _, _ -> "never" }
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.startsWith("transfer over the caps"))
        }
        assertTrue(s.inbound.isEmpty())
    }

    @Test
    fun aTransferWithNoStoreIsTheOneStoreFailureText() {
        val s = Session(record = false)   // no `begin`
        try {
            s.receiveChunk(bundle(1), Target("a"), last = true) { _, _, _ -> "never" }
            fail("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(InkTransferSession.STORE_UNAVAILABLE, e.message)
        }
    }

    @Test
    fun aStoreThatFailsDuringThePlacementIsTheSameText_andDropsTheInbound() {
        val s = opened()
        try {
            s.receiveChunk(bundle(1), Target("a"), last = true) { _, _, _ ->
                throw StoreUnavailable(RuntimeException("gone"))
            }
            fail("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(InkTransferSession.STORE_UNAVAILABLE, e.message)
        }
        assertTrue(s.inbound.isEmpty())
        assertNull(s.received)
    }

    // ── The one documented difference ────────────────────────────────────────

    @Test
    fun theSendersPageSizeIsRecordedOnlyWhenTheConsumerAsksForIt() {
        val pad = opened(record = true)
        pad.receiveChunk(bundle(1, w = 1404f, h = 1872f), Target("a"), last = false) { _, _, _ -> "never" }
        assertEquals(1404f, pad.inboundPageWidth, 0f)
        assertEquals(1872f, pad.inboundPageHeight, 0f)

        val calendar = opened(record = false)
        calendar.receiveChunk(bundle(1, w = 1404f, h = 1872f), Target("a"), last = false) { _, _, _ -> "never" }
        assertEquals(0f, calendar.inboundPageWidth, 0f)
        assertEquals(0f, calendar.inboundPageHeight, 0f)
    }

    @Test
    fun onlyTheFirstChunkSetsThePageSize() {
        val s = opened(record = true)
        s.receiveChunk(bundle(1, w = 10f, h = 20f), Target("a"), last = false) { _, _, _ -> "never" }
        s.receiveChunk(bundle(1, w = 99f, h = 99f), Target("a"), last = false) { _, _, _ -> "never" }
        assertEquals(10f, s.inboundPageWidth, 0f)
        assertEquals(20f, s.inboundPageHeight, 0f)
    }

    // ── Here → notebook, and the teardown ────────────────────────────────────

    @Test
    fun parkedChunksComeBackInOrder_andPastTheEndIsAnEmptyBundle() {
        val s = opened()
        s.park(listOf(listOf(wire(), wire()), listOf(wire())), 800f, 1000f)
        assertEquals(2, s.outgoing(0).strokes.size)
        assertEquals(1, s.outgoing(1).strokes.size)
        assertEquals(800f, s.outgoing(0).pageWidth, 0f)
        assertEquals(1000f, s.outgoing(0).pageHeight, 0f)
        // "Done" is an empty bundle, never an error — the host probes one past the budget.
        assertEquals(0, s.outgoing(2).strokes.size)
        assertEquals(0, s.outgoing(-1).strokes.size)
    }

    @Test
    fun endClearsEverythingTheShowingHeld() {
        val s = opened(record = true)
        s.park(listOf(listOf(wire())), 800f, 1000f)
        s.receiveChunk(bundle(1), Target("a"), last = true) { _, _, _ -> "placed" }
        assertEquals("placed", s.received)

        s.clear()
        assertNull(s.store)
        assertNull(s.received)
        assertNull(s.inboundPlacement)
        assertTrue(s.inbound.isEmpty())
        assertEquals(0, s.outbound.size)
        assertEquals(0f, s.outboundPageWidth, 0f)
        assertEquals(0f, s.outboundPageHeight, 0f)
    }

    @Test
    fun aSecondBeginReplacesTheStoreAndDropsWhatTheLastShowingLeft() {
        val s = opened()
        s.park(listOf(listOf(wire())), 1f, 1f)
        val next = NoStore()
        s.begin(next)
        assertSame(next, s.store)
        assertEquals(0, s.outbound.size)
        assertNull(s.received)
    }
}
