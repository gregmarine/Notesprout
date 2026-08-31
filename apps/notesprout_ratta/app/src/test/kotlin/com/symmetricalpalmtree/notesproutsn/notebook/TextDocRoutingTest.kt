package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text-document routing tables (M8) — every row of both, plus the parked-close rule.
 *
 * `NotebookActivity` cannot be constructed in a JVM test at all; these are the decisions it routes
 * through, so the tables below are the tables it actually runs.
 */
class TextDocRoutingTest {

    // ── The open ──────

    @Test
    fun anOrdinaryNotebookAlwaysLoadsTheCanvas() {
        assertEquals(
            TextDocRouting.Open.CANVAS,
            TextDocRouting.openDecision(isTextDocument = false, canvasShown = false, reconnectPending = false),
        )
        // Even with a showing being reconnected to: an ordinary notebook shows its page underneath
        // the editor exactly as it always has.
        assertEquals(
            TextDocRouting.Open.CANVAS,
            TextDocRouting.openDecision(isTextDocument = false, canvasShown = false, reconnectPending = true),
        )
    }

    @Test
    fun aFreshTextDocumentLaunchesTheEditor() {
        assertEquals(
            TextDocRouting.Open.EDITOR_LAUNCH,
            TextDocRouting.openDecision(isTextDocument = true, canvasShown = false, reconnectPending = false),
        )
    }

    @Test
    fun aLiveShowingIsReconnectedToAndNeverLaunchedTwice() {
        assertEquals(
            TextDocRouting.Open.EDITOR_RECONNECT,
            TextDocRouting.openDecision(isTextDocument = true, canvasShown = false, reconnectPending = true),
        )
    }

    @Test
    fun theCanvasLatchMakesATextDocumentOrdinary() {
        // Shown once, ordinary for the rest of the incarnation — including across a recreate, where
        // the latch comes back out of the saved state.
        assertEquals(
            TextDocRouting.Open.CANVAS,
            TextDocRouting.openDecision(isTextDocument = true, canvasShown = true, reconnectPending = false),
        )
        // And the reconnect does not un-latch it: the editor is showing over pages that are loaded.
        assertEquals(
            TextDocRouting.Open.CANVAS,
            TextDocRouting.openDecision(isTextDocument = true, canvasShown = true, reconnectPending = true),
        )
    }

    // ── The open, with a showing that ended while it ran ──────

    @Test
    fun aParkedSealOutranksEveryOtherRoute() {
        assertEquals(
            TextDocRouting.Open.SEAL_AND_LEAVE,
            TextDocRouting.openDecision(
                isTextDocument = true, canvasShown = false, reconnectPending = true,
                parkedClose = TextDocRouting.Close.SEAL_TO_LIBRARY,
            ),
        )
    }

    @Test
    fun aParkedShowPagesLandsOnTheCanvasRatherThanRelaunching() {
        // The one thing that must never happen here: relaunching the editor the user just left.
        assertEquals(
            TextDocRouting.Open.CANVAS,
            TextDocRouting.openDecision(
                isTextDocument = true, canvasShown = false, reconnectPending = true,
                parkedClose = TextDocRouting.Close.LOAD_CANVAS,
            ),
        )
        // A parked catch-up (an ordinary notebook's showing, or a latched text document's) says the
        // same thing about the open: the canvas.
        assertEquals(
            TextDocRouting.Open.CANVAS,
            TextDocRouting.openDecision(
                isTextDocument = false, canvasShown = false, reconnectPending = true,
                parkedClose = TextDocRouting.Close.CATCH_UP,
            ),
        )
    }

    // ── The close ──────

    @Test
    fun anOrdinaryNotebookAlwaysCatchesUp() {
        for (mode in listOf(null, DocumentContract.CLOSE_SHOW_PAGES, DocumentContract.CLOSE_TO_LIBRARY)) {
            assertEquals(
                TextDocRouting.Close.CATCH_UP,
                TextDocRouting.closeDecision(isTextDocument = false, canvasShown = false, mode = mode),
            )
        }
    }

    @Test
    fun aTextDocumentWithItsPagesUpAlsoCatchesUp() {
        // The latch is one-way: a later showing ends in the ordinary catch-up, never in a seal —
        // sealing a notebook whose pages are on the glass would close a screen the user is using.
        for (mode in listOf(null, DocumentContract.CLOSE_SHOW_PAGES, DocumentContract.CLOSE_TO_LIBRARY)) {
            assertEquals(
                TextDocRouting.Close.CATCH_UP,
                TextDocRouting.closeDecision(isTextDocument = true, canvasShown = true, mode = mode),
            )
        }
    }

    @Test
    fun doneShowsThePages() {
        assertEquals(
            TextDocRouting.Close.LOAD_CANVAS,
            TextDocRouting.closeDecision(
                isTextDocument = true, canvasShown = false, mode = DocumentContract.CLOSE_SHOW_PAGES,
            ),
        )
    }

    @Test
    fun theLeaveDoorSealsToTheLibrary() {
        assertEquals(
            TextDocRouting.Close.SEAL_TO_LIBRARY,
            TextDocRouting.closeDecision(
                isTextDocument = true, canvasShown = false, mode = DocumentContract.CLOSE_TO_LIBRARY,
            ),
        )
    }

    @Test
    fun silenceIsTheLibraryToo() {
        // The back arrow, a process-death edge, a debug hook: the editor never said. A notebook
        // wrongly sealed reopens with one tap; a canvas wrongly loaded cannot be un-loaded.
        assertEquals(
            TextDocRouting.Close.SEAL_TO_LIBRARY,
            TextDocRouting.closeDecision(isTextDocument = true, canvasShown = false, mode = null),
        )
    }

    @Test
    fun aModeThisBuildDoesNotKnowIsSilence() {
        assertEquals(
            TextDocRouting.Close.SEAL_TO_LIBRARY,
            TextDocRouting.closeDecision(isTextDocument = true, canvasShown = false, mode = 99),
        )
    }

    // ── The park ──────

    @Test
    fun aResultThatBeatsTheOpenIsParked() {
        assertTrue(TextDocRouting.parkClose(opened = false))
        assertFalse(TextDocRouting.parkClose(opened = true))
    }
}
