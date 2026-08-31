package com.symmetricalpalmtree.notesproutsn.importing

import com.symmetricalpalmtree.notesproutsn.extension.ImporterContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fork after delivery (arc 19 / M8), and the one thing it changes about the delivery itself. */
class ImportRoutingTest {

    @Test
    fun theTextKindForksAndEveryOtherKindDoesNot() {
        assertTrue(ImportRouting.isTextDocument(ImporterContract.RESULT_TEXT_DOCUMENT))
        assertFalse(ImportRouting.isTextDocument(ImporterContract.RESULT_NOTEBOOK))
    }

    @Test
    fun anAbsentTailMeansTheSoilPipeline() {
        // The compatible tail's whole promise: a pre-arc-19 importer's descriptor runs out before
        // the result kind, `ImporterInfo` defaults it to RESULT_NOTEBOOK, and that importer keeps
        // exactly the behaviour it had.
        assertFalse(ImportRouting.isTextDocument(0))
        assertTrue(ImportRouting.rejectsEmptyDelivery(0))
    }

    @Test
    fun anEmptyDeliveryIsARefusalForANotebookAndLegalForText() {
        // No `.soil` is zero bytes — carrying nothing to a probe that was always going to reject
        // it just moves the failure later.
        assertTrue(ImportRouting.rejectsEmptyDelivery(ImporterContract.RESULT_NOTEBOOK))
        // An empty `.txt` is a legal file, and an empty text document is exactly what it is.
        assertFalse(ImportRouting.rejectsEmptyDelivery(ImporterContract.RESULT_TEXT_DOCUMENT))
    }
}
