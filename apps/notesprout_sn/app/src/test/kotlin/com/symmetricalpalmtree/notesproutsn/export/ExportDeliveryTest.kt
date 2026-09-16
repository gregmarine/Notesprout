package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arc 31 / HV1: the host reads the delivery tail only from a service that declared it can be
 * split, and per-page delivery is the folder case — more than one page.
 *
 * The parcelables are constructed directly, no `Parcel` touched (the family's test shape).
 */
class ExportDeliveryTest {

    private fun pages(delivery: Int) = ExporterInfo(
        "PNG image", "png", "image/png", emptyList(),
        ExporterContract.SOURCE_PAGES, com.symmetricalpalmtree.notesproutsn.extension.PageBundle.VERSION_1,
        delivery,
    )

    private val soil = ExporterInfo("Notesprout notebook", "soil", "application/octet-stream", emptyList())

    // ── The tail ─────────────────────────────────────────────────────────────

    @Test
    fun theTailIsReadAtTheDeclaredFloorAndAbove() {
        val info = pages(ExporterContract.DELIVERY_PER_PAGE)
        assertEquals(
            ExporterContract.DELIVERY_PER_PAGE,
            ExportDelivery.delivery(ExporterContract.MIN_API_VERSION_FOR_DELIVERY, info),
        )
        assertEquals(
            ExporterContract.DELIVERY_PER_PAGE,
            ExportDelivery.delivery(ExporterContract.MIN_API_VERSION_FOR_DELIVERY + 3, info),
        )
    }

    @Test
    fun belowTheFloorItIsOneFileWhateverTheParcelCarried() {
        val info = pages(ExporterContract.DELIVERY_PER_PAGE)
        for (declared in 1 until ExporterContract.MIN_API_VERSION_FOR_DELIVERY) {
            assertEquals(
                "a service declaring $declared must read as one file",
                ExporterContract.DELIVERY_ONE_FILE,
                ExportDelivery.delivery(declared, info),
            )
        }
    }

    @Test
    fun everyExporterThatNeverDeclaredATailIsOneFile() {
        assertEquals(ExporterContract.DELIVERY_ONE_FILE, ExportDelivery.delivery(1, soil))
        assertEquals(ExporterContract.DELIVERY_ONE_FILE, ExportDelivery.delivery(99, soil))
        assertEquals(
            ExporterContract.DELIVERY_ONE_FILE,
            ExportDelivery.delivery(99, pages(ExporterContract.DELIVERY_ONE_FILE)),
        )
    }

    // ── The folder case ──────────────────────────────────────────────────────

    @Test
    fun perPageIsPerPageOnlyAtTheWholeNotebook() {
        assertTrue(ExportDelivery.perPage(ExporterContract.DELIVERY_PER_PAGE, ExportScope.Whole))
        // One page is one file through the ordinary picker — nothing about the flow changes.
        assertFalse(ExportDelivery.perPage(ExporterContract.DELIVERY_PER_PAGE, ExportScope.Page("p1")))
    }

    @Test
    fun aSketchedPageIsTwoPagesAndSoGoesToAFolder() {
        // Arc 43 / K7: the page's sketch is a second bundle page, so a per-page exporter writes
        // two files — counted like the calendar's Day, not assumed like the Whole.
        assertTrue(
            ExportDelivery.perPage(ExporterContract.DELIVERY_PER_PAGE, ExportScope.Page("p1"), pageHasSketch = true),
        )
        // The fact only matters at page scope: a one-file exporter still writes one file of it.
        assertFalse(
            ExportDelivery.perPage(ExporterContract.DELIVERY_ONE_FILE, ExportScope.Page("p1"), pageHasSketch = true),
        )
    }

    @Test
    fun aOneFileExporterIsNeverPerPage() {
        assertFalse(ExportDelivery.perPage(ExporterContract.DELIVERY_ONE_FILE, ExportScope.Whole))
        assertFalse(ExportDelivery.perPage(ExporterContract.DELIVERY_ONE_FILE, ExportScope.Page("p1")))
    }

    @Test
    fun theWholeNotebookIsAFolderEvenWithOnePageInIt() {
        // Deliberately simple: Whole = a folder. A scope that sometimes made a file and sometimes
        // a folder is not a rule anyone could hold.
        assertTrue(ExportDelivery.perPage(ExporterContract.DELIVERY_PER_PAGE, ExportScope.Whole))
    }

    @Test
    fun theTwoRulesCompose() {
        // What the screen actually asks: an old service's per-page claim never reaches the folder.
        val info = pages(ExporterContract.DELIVERY_PER_PAGE)
        assertFalse(ExportDelivery.perPage(ExportDelivery.delivery(8, info), ExportScope.Whole))
        assertTrue(ExportDelivery.perPage(ExportDelivery.delivery(9, info), ExportScope.Whole))
    }

    // ── The calendar (arc 31 / HV4) ──────────────────────────────────────────

    private fun calendar(kind: Int, date: String, half: Int = 0) =
        ExportScope.Calendar(CalendarTarget(kind, date, half))

    @Test
    fun aMonthOrAWeekIsOneFileThroughTheOrdinaryPicker() {
        assertFalse(
            ExportDelivery.perPage(
                ExporterContract.DELIVERY_PER_PAGE, calendar(CalendarTarget.KIND_MONTH, "2026-09-01"),
            )
        )
        assertFalse(
            ExportDelivery.perPage(
                ExporterContract.DELIVERY_PER_PAGE, calendar(CalendarTarget.KIND_WEEK, "2026-09-06"),
            )
        )
    }

    @Test
    fun aDayIsTwoPagesAndSoGoesToAFolder() {
        for (half in listOf(CalendarTarget.HALF_AM, CalendarTarget.HALF_PM)) {
            assertTrue(
                ExportDelivery.perPage(
                    ExporterContract.DELIVERY_PER_PAGE,
                    calendar(CalendarTarget.KIND_DAY, "2026-09-08", half),
                )
            )
        }
    }

    @Test
    fun aOneFileExporterIsNeverPerPageAtACalendarEither() {
        assertFalse(
            ExportDelivery.perPage(
                ExporterContract.DELIVERY_ONE_FILE, calendar(CalendarTarget.KIND_DAY, "2026-09-08"),
            )
        )
    }
}
