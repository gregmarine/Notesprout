package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generic face-routing tables (arc 43 / K3) — [FaceRouting] itself, with the show-pages
 * advisory as a parameter rather than one face's constant. [TextDocRoutingTest] pins the document
 * editor's answers and [SketchRoutingTest] the sketch screen's; this suite pins the table both of
 * them run.
 *
 * `NotebookActivity` cannot be constructed in a JVM test at all; these are the decisions it routes
 * through, so the tables below are the tables it actually runs.
 */
class FaceRoutingTest {

    /** An arbitrary advisory value — the point of the generic table is that it is a parameter. */
    private val showPages = 7

    // ── The open ──────

    @Test
    fun `a notebook with no face always loads the canvas`() {
        for (reconnect in listOf(false, true)) {
            assertEquals(
                FaceRouting.Open.CANVAS,
                FaceRouting.openDecision(opensIntoFace = false, canvasShown = false, reconnectPending = reconnect),
            )
        }
    }

    @Test
    fun `a fresh faced notebook launches its face`() {
        assertEquals(
            FaceRouting.Open.EDITOR_LAUNCH,
            FaceRouting.openDecision(opensIntoFace = true, canvasShown = false, reconnectPending = false),
        )
    }

    @Test
    fun `a live showing is reconnected to and never launched twice`() {
        assertEquals(
            FaceRouting.Open.EDITOR_RECONNECT,
            FaceRouting.openDecision(opensIntoFace = true, canvasShown = false, reconnectPending = true),
        )
    }

    @Test
    fun `the canvas latch makes a faced notebook ordinary`() {
        for (reconnect in listOf(false, true)) {
            assertEquals(
                FaceRouting.Open.CANVAS,
                FaceRouting.openDecision(opensIntoFace = true, canvasShown = true, reconnectPending = reconnect),
            )
        }
    }

    // ── The open, with a showing that ended while it ran ──────

    @Test
    fun `a parked seal outranks every other route`() {
        assertEquals(
            FaceRouting.Open.SEAL_AND_LEAVE,
            FaceRouting.openDecision(
                opensIntoFace = true, canvasShown = false, reconnectPending = true,
                parkedClose = FaceRouting.Close.SEAL_TO_LIBRARY,
            ),
        )
    }

    @Test
    fun `any other parked close is the canvas, never a relaunch`() {
        // The one thing that must never happen here: relaunching the face the user just left.
        for (parked in listOf(FaceRouting.Close.LOAD_CANVAS, FaceRouting.Close.CATCH_UP)) {
            assertEquals(
                FaceRouting.Open.CANVAS,
                FaceRouting.openDecision(
                    opensIntoFace = true, canvasShown = false, reconnectPending = true,
                    parkedClose = parked,
                ),
            )
        }
    }

    // ── The close ──────

    @Test
    fun `a notebook with no face always catches up`() {
        for (mode in listOf(null, showPages, 0, 99)) {
            assertEquals(
                FaceRouting.Close.CATCH_UP,
                FaceRouting.closeDecision(
                    opensIntoFace = false, canvasShown = false, mode = mode, showPagesMode = showPages,
                ),
            )
        }
    }

    @Test
    fun `a faced notebook with its pages up also catches up`() {
        // The latch is one-way: a later showing ends in the ordinary catch-up, never in a seal —
        // sealing a notebook whose pages are on the glass would close a screen the user is using.
        for (mode in listOf(null, showPages, 0, 99)) {
            assertEquals(
                FaceRouting.Close.CATCH_UP,
                FaceRouting.closeDecision(
                    opensIntoFace = true, canvasShown = true, mode = mode, showPagesMode = showPages,
                ),
            )
        }
    }

    @Test
    fun `the show-pages advisory is the caller's value and only it loads the canvas`() {
        assertEquals(
            FaceRouting.Close.LOAD_CANVAS,
            FaceRouting.closeDecision(
                opensIntoFace = true, canvasShown = false, mode = showPages, showPagesMode = showPages,
            ),
        )
        // The same number means nothing to a face that did not name it.
        assertEquals(
            FaceRouting.Close.SEAL_TO_LIBRARY,
            FaceRouting.closeDecision(
                opensIntoFace = true, canvasShown = false, mode = showPages, showPagesMode = showPages + 1,
            ),
        )
    }

    @Test
    fun `silence, and anything this build does not know, seals to the library`() {
        for (mode in listOf(null, 0, 1, 99, -4)) {
            if (mode == showPages) continue
            assertEquals(
                FaceRouting.Close.SEAL_TO_LIBRARY,
                FaceRouting.closeDecision(
                    opensIntoFace = true, canvasShown = false, mode = mode, showPagesMode = showPages,
                ),
            )
        }
    }

    // ── The park ──────

    @Test
    fun `a result that beats the open is parked`() {
        assertTrue(FaceRouting.parkClose(opened = false))
        assertFalse(FaceRouting.parkClose(opened = true))
    }
}
