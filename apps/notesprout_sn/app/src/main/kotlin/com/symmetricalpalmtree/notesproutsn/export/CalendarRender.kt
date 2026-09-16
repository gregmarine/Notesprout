package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.CalendarClient
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import com.symmetricalpalmtree.notesproutsn.extension.ProviderRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * **The fourth producer** (arc 31 / HV4), beside [ExportRender], [DocumentPdfRender] and
 * [ExportText] — and the only one that does not draw the pages itself.
 *
 * Where the other three read a `.soil` and bake, this one *asks*: the calendar owns its own pages
 * and its own arithmetic (that is the whole reason `ACTION_CALENDAR` is a point and not a table in
 * this app), so the host lends it its store, hands it a file to write into, and receives the same
 * [PageBundle] every `SOURCE_PAGES` exporter already reads. The seam does the work; from the Export
 * screen's side this is one more `StreamSource.Ready(file)` and nothing else changes.
 *
 * Three rules make that safe:
 *
 *  1. **The file is the host's.** It is written into [ExportArtifact.freshDir] — the one export
 *     cache directory every producer shares and `runExport`'s `finally` wipes — and the descriptor
 *     that carries it across is opened and closed by [CalendarClient.render].
 *  2. **Bytes from an extension are untrusted.** Nothing here believes the bundle: it is opened with
 *     [PageBundle.Reader] and read to the end, which is what bounds-checks the page count, every
 *     page's dimensions and every image's length before a single byte is handed on. A page count
 *     that is not the plan's, or a link trailer where a version-1 bundle can have none, is a
 *     refusal — the extension answered a different question than the one it was asked.
 *  3. **A failure names nothing.** One sentence, from the resources, with no path, no exception
 *     text and no date in it; the class name goes to the log and stays there.
 */
object CalendarRender {

    private const val TAG = "CalendarRender"

    /** The bundle, or the sentence saying why there is none — [ExportRender]'s two shapes, so the
     *  screen's `when` reads the same for every producer. */
    sealed class Outcome {
        /** [pageNames] is the plan's own stems: one per page, in order, which is what a per-page
         *  delivery names its files after (a calendar page has no heading to fall back on, and no
         *  notebook to be prefixed with — so each name carries the finished stem in its `title`
         *  and is used **verbatim**; see `ExportActivity.stemFor`). */
        class Ready(val file: File, val bytes: Long, val pageNames: List<ExportNaming.PageName>) : Outcome()

        class Failed(val message: String) : Outcome()
    }

    /**
     * Draw [plan]'s pages through [ref]'s `render` into a fresh bundle. [widthPx] x [heightPx] is
     * the size for a target the calendar has never minted a page for; a minted one keeps its own.
     */
    suspend fun render(
        context: Context,
        ref: ProviderRef,
        plan: CalendarRenderPlan,
        widthPx: Int,
        heightPx: Int,
    ): Outcome {
        val file = try {
            withContext(Dispatchers.IO) { File(ExportArtifact.freshDir(context), FILE_NAME) }
        } catch (e: IOException) {
            Slog.d(TAG) { "the export cache directory could not be made: ${e.javaClass.simpleName}" }
            return Outcome.Failed(context.getString(R.string.export_calendar_failed_body))
        }
        try {
            CalendarClient.render(context, ref, plan.targets, widthPx, heightPx, plan.flags, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtensionCallException) {
            // The message is the client's own — a timeout, a dead bind, a refusal the calendar
            // raised. None of it is a sentence for the screen.
            Slog.d(TAG) { "render failed: ${e.message}" }
            return Outcome.Failed(context.getString(R.string.export_calendar_failed_body))
        }
        val bytes = withContext(Dispatchers.IO) {
            try {
                verify(file, plan.targets.size)
                file.length()
            } catch (e: IOException) {
                Slog.d(TAG) { "the calendar's bundle would not read: ${e.message}" }
                -1L
            } catch (e: IllegalStateException) {
                Slog.d(TAG) { "the calendar's bundle is not what was asked for: ${e.message}" }
                -1L
            }
        }
        if (bytes <= 0L) return Outcome.Failed(context.getString(R.string.export_calendar_failed_body))
        Slog.d(TAG) { "rendered ${plan.targets.size} calendar page(s), $bytes bytes" }
        return Outcome.Ready(file, bytes, plan.pageNames())
    }

    /** The plan's stems as the bundle's page names — one per page, in the bundle's own order, each
     *  a finished stem rather than a heading to build one from. */
    private fun CalendarRenderPlan.pageNames(): List<ExportNaming.PageName> =
        stems.map { ExportNaming.PageName(title = it) }

    /**
     * Read the whole bundle back. The [PageBundle.Reader] *is* the header verification — it checks
     * the magic, the version, the page count against [PageBundle.MAX_PAGES], and every page's
     * dimensions and byte length against their caps before anything allocates. What is added here
     * is the one thing the container cannot know: this bundle was asked for [expectedPages] pages
     * and must have exactly that many, and being a version-1 bundle it must carry no links.
     */
    private fun verify(file: File, expectedPages: Int) {
        file.inputStream().use { input ->
            PageBundle.Reader(input).use { reader ->
                check(reader.pageCount == expectedPages) {
                    "${reader.pageCount} pages for $expectedPages target(s)"
                }
                repeat(reader.pageCount) { reader.readPage() }
                check(reader.readLinks().isEmpty()) { "a version-1 bundle may carry no links" }
            }
        }
    }

    /** The bundle's name in the shared export cache directory. */
    private const val FILE_NAME = "calendar.pages"
}
