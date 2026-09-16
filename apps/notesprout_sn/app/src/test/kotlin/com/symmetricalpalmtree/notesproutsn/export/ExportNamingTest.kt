package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** og's sanitize rule, pinned: this is the name the family has always given an exported file. */
class ExportNamingTest {

    private val id = "8f14e45f-ceea-467a-9b8d-8f14e45fceea"

    @Test
    fun keepsAPlainName() {
        assertEquals("Field notes", ExportNaming.base("Field notes", id))
    }

    @Test
    fun spacesInsideSurvive() {
        assertEquals("My great notebook", ExportNaming.base("My great notebook", id))
    }

    @Test
    fun stripsEverythingOutsideTheCharset() {
        assertEquals("aB9_-.", ExportNaming.base("aB9_-./\\:*?\"<>|", id))
        // A separator is removed, not replaced: the two halves close up.
        assertEquals("Meeting2026", ExportNaming.base("Meeting/2026", id))
        assertEquals("ntes", ExportNaming.base("nötes", id))
        assertEquals("emoji", ExportNaming.base("emoji🌱", id))
    }

    @Test
    fun stripsFirstThenTrims() {
        // The stripping is what exposes the outer spaces; trimming first would leave them behind.
        assertEquals("name", ExportNaming.base("  ***name***  ", id))
        assertEquals("name", ExportNaming.base(" name ", id))
    }

    @Test
    fun emptyFallsBackToTheId() {
        assertEquals(id, ExportNaming.base("", id))
        assertEquals(id, ExportNaming.base("   ", id))
        assertEquals(id, ExportNaming.base("/////", id))
        assertEquals(id, ExportNaming.base("🌱🌱", id))
    }

    @Test
    fun dotAndDotDotFallBackToTheId() {
        assertEquals(id, ExportNaming.base(".", id))
        assertEquals(id, ExportNaming.base("..", id))
        // Three dots is a legal (if odd) filename, so it is kept.
        assertEquals("...", ExportNaming.base("...", id))
        // A leading dot is legal too: only bare "." and ".." are the directory names.
        assertEquals(".hidden", ExportNaming.base(".hidden", id))
    }

    @Test
    fun suggestedFileNameAppendsTheExporterExtension() {
        assertEquals("Field notes.soil", ExportNaming.suggestedFileName("Field notes", id, "soil"))
        assertEquals("$id.soil", ExportNaming.suggestedFileName("///", id, "soil"))
        assertEquals("Field notes.pdf", ExportNaming.suggestedFileName("Field notes", id, "pdf"))
    }

    @Test
    fun specNameTruncatesToTheContractCap() {
        val long = "n".repeat(ExporterContract.MAX_NAME_CHARS + 50)
        val spec = ExportNaming.specName(long, id)
        assertEquals(ExporterContract.MAX_NAME_CHARS, spec.length)
        assertTrue(spec.all { it == 'n' })
    }

    @Test
    fun specNameIsTheSameBaseAsTheFilename() {
        assertEquals(ExportNaming.base("Field notes", id), ExportNaming.specName("Field notes", id))
        assertEquals(id, ExportNaming.specName("..", id))
        // What the spec's own constructor demands: no separator, no NUL. Spaces stay.
        val spec = ExportNaming.specName("a/b c d", id)
        assertEquals("ab c d", spec)
        assertTrue('/' !in spec)
    }

    // ── Page scope (arc 30 / PE2) ────────────────────────────────────────────

    @Test
    fun pageStemUsesTheHeadingWhenThereIsOne() {
        assertEquals("Field notes - Standup", ExportNaming.pageStem("Field notes", id, 3, "Standup"))
    }

    @Test
    fun pageStemFallsBackToThePageNumber() {
        assertEquals("Field notes - page 3", ExportNaming.pageStem("Field notes", id, 3, null))
        // A heading that strips to nothing names nothing — the number stands in.
        assertEquals("Field notes - page 3", ExportNaming.pageStem("Field notes", id, 3, "🌱 ✨"))
        assertEquals("Field notes - page 3", ExportNaming.pageStem("Field notes", id, 3, "   "))
        assertEquals("Field notes - page 3", ExportNaming.pageStem("Field notes", id, 3, ".."))
    }

    @Test
    fun pageStemSanitizesTheHeadingLikeTheName() {
        assertEquals("Field notes - Q2plan draft", ExportNaming.pageStem("Field notes", id, 1, "Q2/plan: *draft*"))
        // A dash of any other kind is stripped, which is why the separator is a plain hyphen.
        assertEquals("Field notes - a  b", ExportNaming.pageStem("Field notes", id, 1, "a — b"))
    }

    @Test
    fun pageStemCapsALongHeading() {
        val long = "x".repeat(200)
        val stem = ExportNaming.pageStem("N", id, 1, long)
        assertEquals("N - " + "x".repeat(ExportNaming.MAX_TITLE_CHARS), stem)
    }

    @Test
    fun pageStemWithoutAPlaceNamesTheNotebookAlone() {
        // The page could not be placed (it vanished): never "page 0".
        assertEquals("Field notes", ExportNaming.pageStem("Field notes", id, 0, null))
        // ...but a heading still names it.
        assertEquals("Field notes - Standup", ExportNaming.pageStem("Field notes", id, 0, "Standup"))
    }

    @Test
    fun aSketchPageIsItsInkPagesNamePlusTheWord() {
        // Arc 43 / K7, the user's phase-start call: the two files of one page sort together and
        // say which is which.
        assertEquals("Field notes - Standup sketch", ExportNaming.pageStem("Field notes", id, 3, "Standup", sketch = true))
        assertEquals("Field notes - page 3 sketch", ExportNaming.pageStem("Field notes", id, 3, null, sketch = true))
        // No heading and no place: the notebook alone, still saying which of the two it is.
        assertEquals("Field notes sketch", ExportNaming.pageStem("Field notes", id, 0, null, sketch = true))
        // The default is the ink page — every caller that never heard of sketches is unchanged.
        assertEquals(
            ExportNaming.pageStem("Field notes", id, 3, "Standup"),
            ExportNaming.pageStem("Field notes", id, 3, "Standup", sketch = false),
        )
    }

    @Test
    fun theTitleCapIsAppliedBeforeTheSketchSuffix() {
        // The cap is about the user's heading; the suffix is the app's own word and is never
        // truncated — a file called "…xxx sketc" would lie about what is in it.
        val long = "x".repeat(200)
        assertEquals(
            "N - " + "x".repeat(ExportNaming.MAX_TITLE_CHARS) + " sketch",
            ExportNaming.pageStem("N", id, 1, long, sketch = true),
        )
    }

    @Test
    fun aSketchPagesNameIsBuiltFromItsOwnPagesNumber() {
        // The bundle interleaves, so the position in the file list is NOT the page number: page 2
        // of the notebook is the third and fourth files when page 1 carries a sketch.
        val names = listOf(
            ExportNaming.PageName(1, "Plan"),
            ExportNaming.PageName(1, "Plan", sketch = true),
            ExportNaming.PageName(2, null),
            ExportNaming.PageName(2, null, sketch = true),
        )
        assertEquals(
            listOf("NB - Plan.png", "NB - Plan sketch.png", "NB - page 2.png", "NB - page 2 sketch.png"),
            names.map {
                ExportNaming.fileName(
                    ExportNaming.pageStem("NB", id, it.number, it.title, it.sketch), "png",
                )
            },
        )
    }

    @Test
    fun everyPageOfAPerPageExportIsNamedFromItsOwnPage() {
        // Arc 31 / HV1: one file per page, each named by the Contents rule or by its number.
        val titles = listOf("Plan", null, "Plan")
        val names = titles.mapIndexed { index, title ->
            ExportNaming.fileName(ExportNaming.pageStem("NB", id, index + 1, title), "png")
        }
        assertEquals(listOf("NB - Plan.png", "NB - page 2.png", "NB - Plan.png"), names)
        // Two pages under one heading make two files of one name, and that is the PROVIDER's
        // question: SAF de-dupes with "(1)", an upload replaces by name, and the host renames
        // nothing behind the user's back.
        assertEquals(names[0], names[2])
    }

    @Test
    fun stemBasedNamesAgreeWithTheOriginals() {
        val stem = ExportNaming.pageStem("Field notes", id, 2, null)
        assertEquals("Field notes - page 2.pdf", ExportNaming.fileName(stem, "pdf"))
        assertEquals(stem, ExportNaming.specNameOf(stem))
        assertEquals(ExportNaming.suggestedFileName("Field notes", id, "pdf"), ExportNaming.fileName(ExportNaming.base("Field notes", id), "pdf"))
        assertTrue(ExportNaming.specNameOf("y".repeat(500)).length <= ExporterContract.MAX_NAME_CHARS)
    }

    // ── The calendar's own stem (arc 31 / HV4) ───────────────────────────────

    @Test
    fun aMonthIsNamedForTheMonth() {
        assertEquals(
            "Calendar - September 2026",
            ExportNaming.calendarStem(CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0)),
        )
    }

    @Test
    fun aWeekIsNamedForItsSunday() {
        assertEquals(
            "Calendar - Week of 2026-09-06",
            ExportNaming.calendarStem(CalendarTarget(CalendarTarget.KIND_WEEK, "2026-09-06", 0)),
        )
    }

    @Test
    fun aDayIsNamedForTheDayAndSaysNothingAboutItsHalf() {
        val stem = "Calendar - 2026-09-08"
        assertEquals(stem, ExportNaming.calendarStem(CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-08", 0)))
        assertEquals(stem, ExportNaming.calendarStem(CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-08", 1)))
    }

    @Test
    fun everyCalendarStemSurvivesTheSanitizeUntouched() {
        // Letters, digits, spaces and the ASCII hyphen — legal by construction, not by trimming.
        for (target in listOf(
            CalendarTarget(CalendarTarget.KIND_MONTH, "2026-01-01", 0),
            CalendarTarget(CalendarTarget.KIND_WEEK, "2025-12-28", 0),
            CalendarTarget(CalendarTarget.KIND_DAY, "2026-12-31", 1),
        )) {
            val stem = ExportNaming.calendarStem(target)
            assertEquals(stem, ExportNaming.base(stem, "fallback-id"))
        }
    }
}
