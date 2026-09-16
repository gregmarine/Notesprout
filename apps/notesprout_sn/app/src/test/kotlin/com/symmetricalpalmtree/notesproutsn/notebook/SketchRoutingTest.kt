package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sketch face's answers to [FaceRouting]'s table (arc 43 / K3) — [TextDocRoutingTest]'s sibling.
 * The generic rules are pinned in [FaceRoutingTest]; what this suite is for is the one thing that
 * is the sketch screen's own: **Back is `RESULT_CANCELED`, and that seals.**
 */
class SketchRoutingTest {

    /** `Activity.RESULT_CANCELED` — 0, what Back and a killed screen both give. Written as the
     *  literal rather than imported, for [SketchContract.RESULT_SKETCH_SHOW_PAGES]'s own reason: a
     *  plain JVM test pins these numbers without `android.app` in the room. */
    private val canceled = 0

    // ── The open ──────

    @Test
    fun `an ordinary notebook always loads the canvas`() {
        for (reconnect in listOf(false, true)) {
            assertEquals(
                SketchRouting.Open.CANVAS,
                SketchRouting.openDecision(isSketch = false, canvasShown = false, reconnectPending = reconnect),
            )
        }
    }

    @Test
    fun `a fresh sketch notebook launches the face, and a live showing is reconnected to`() {
        assertEquals(
            SketchRouting.Open.SKETCH_LAUNCH,
            SketchRouting.openDecision(isSketch = true, canvasShown = false, reconnectPending = false),
        )
        assertEquals(
            SketchRouting.Open.SKETCH_RECONNECT,
            SketchRouting.openDecision(isSketch = true, canvasShown = false, reconnectPending = true),
        )
    }

    @Test
    fun `the canvas latch makes a sketch notebook ordinary`() {
        assertEquals(
            SketchRouting.Open.CANVAS,
            SketchRouting.openDecision(isSketch = true, canvasShown = true, reconnectPending = true),
        )
    }

    @Test
    fun `a parked seal outranks everything and a parked anything-else is the canvas`() {
        assertEquals(
            SketchRouting.Open.SEAL_AND_LEAVE,
            SketchRouting.openDecision(
                isSketch = true, canvasShown = false, reconnectPending = true,
                parkedClose = SketchRouting.Close.SEAL_TO_LIBRARY,
            ),
        )
        assertEquals(
            SketchRouting.Open.CANVAS,
            SketchRouting.openDecision(
                isSketch = true, canvasShown = false, reconnectPending = true,
                parkedClose = SketchRouting.Close.LOAD_CANVAS,
            ),
        )
    }

    // ── The close ──────

    @Test
    fun `an ordinary notebook always catches up`() {
        for (mode in listOf(null, canceled, SketchContract.RESULT_SKETCH_SHOW_PAGES)) {
            assertEquals(
                SketchRouting.Close.CATCH_UP,
                SketchRouting.closeDecision(isSketch = false, canvasShown = false, mode = mode),
            )
        }
    }

    @Test
    fun `a sketch notebook with its pages up also catches up`() {
        for (mode in listOf(null, canceled, SketchContract.RESULT_SKETCH_SHOW_PAGES)) {
            assertEquals(
                SketchRouting.Close.CATCH_UP,
                SketchRouting.closeDecision(isSketch = true, canvasShown = true, mode = mode),
            )
        }
    }

    @Test
    fun `show pages loads the canvas`() {
        assertEquals(
            SketchRouting.Close.LOAD_CANVAS,
            SketchRouting.closeDecision(
                isSketch = true, canvasShown = false, mode = SketchContract.RESULT_SKETCH_SHOW_PAGES,
            ),
        )
    }

    /** The face's own rule, and the reason this is a second facade: the sketch screen's advisory is
     *  an Activity result, which is never absent. `RESULT_CANCELED` is the screen saying "done, and
     *  I did not ask for the pages" — a to-library answer, not an unanswered question. */
    @Test
    fun `Back seals to the library`() {
        assertEquals(
            SketchRouting.Close.SEAL_TO_LIBRARY,
            SketchRouting.closeDecision(isSketch = true, canvasShown = false, mode = canceled),
        )
    }

    @Test
    fun `silence and an unknown code seal too`() {
        for (mode in listOf(null, 99, -4)) {
            assertEquals(
                SketchRouting.Close.SEAL_TO_LIBRARY,
                SketchRouting.closeDecision(isSketch = true, canvasShown = false, mode = mode),
            )
        }
    }

    /** The two faces answer the *same* code differently, which is the whole point of the split:
     *  0 is the editor's ✓ Done and the sketch screen's Back. */
    @Test
    fun `zero means the opposite thing on the two faces`() {
        assertEquals(
            FaceRouting.Close.LOAD_CANVAS,
            TextDocRouting.closeDecision(isTextDocument = true, canvasShown = false, mode = 0),
        )
        assertEquals(
            FaceRouting.Close.SEAL_TO_LIBRARY,
            SketchRouting.closeDecision(isSketch = true, canvasShown = false, mode = 0),
        )
    }

    // ── The park ──────

    @Test
    fun `a result that beats the open is parked`() {
        assertTrue(SketchRouting.parkClose(opened = false))
        assertFalse(SketchRouting.parkClose(opened = true))
    }
}
