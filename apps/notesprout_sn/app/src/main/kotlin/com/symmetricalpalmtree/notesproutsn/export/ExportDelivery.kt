package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo

/**
 * **How many files one export produces** (arc 31 / HV1) — pure, JVM-tested, the host's whole
 * reading of `ExporterInfo`'s third compatible tail.
 *
 * The seam is unchanged by it: `export(source, destination, spec)` is still one call, one file.
 * What a [ExporterContract.DELIVERY_PER_PAGE] exporter (a raster format — PNG has no notion of a
 * multi-page document) changes is what the **host** does around those calls: at a scope of more
 * than one page it bakes the bundle once, splits it into one-page bundles ([BundleSplit]) and calls
 * the exporter once per page into a folder the user picked.
 *
 * Two rules, and each is a decision:
 *
 *  - [delivery] — **the tail is read only from a service declaring at least
 *    [ExporterContract.MIN_API_VERSION_FOR_DELIVERY]** ([com.symmetricalpalmtree.notesproutsn.extension.ProviderRef.apiVersion],
 *    which is the manifest's own number, not the parcel's). Below that it is
 *    [ExporterContract.DELIVERY_ONE_FILE] whatever the parcel carried, so the declaration and the
 *    tail can never disagree about what the host will do — the D3 skew-guard recipe, read from the
 *    host's side.
 *  - [perPage] — per-page delivery **at more than one page**. At [ExportScope.Page] there is
 *    exactly one page — unless it carries a **sketch** (arc 43 / K7, decision 5), which exports as
 *    a second page after it and so makes the page two files and a folder; otherwise a per-page
 *    exporter is a single file through the ordinary picker and nothing about the flow changes.
 *    Like the calendar's Day below, that is counted, not assumed: "a sketched page is two files"
 *    is a fact about the page, not a rule the person has to hold. At [ExportScope.Whole] it is
 *    the folder case, deliberately
 *    including a one-page notebook: *Whole notebook = a folder* is a rule the person can hold, and
 *    a scope that sometimes made a folder and sometimes a file would not be.
 *
 *    [ExportScope.Calendar] is the one place that rule is counted rather than assumed (arc 31 /
 *    HV4): a Day is two pages and goes to a folder, a Month or a Week is one page and goes through
 *    the ordinary picker. The person is not choosing a scope there — the calendar's own page
 *    decides — so "a Day is two files" is a fact about the day, not a rule they have to hold.
 */
object ExportDelivery {

    /**
     * The delivery the host acts on for an exporter whose service declares [apiVersion] and whose
     * descriptor is [info].
     */
    fun delivery(apiVersion: Int, info: ExporterInfo): Int =
        if (apiVersion >= ExporterContract.MIN_API_VERSION_FOR_DELIVERY) info.delivery
        else ExporterContract.DELIVERY_ONE_FILE

    /** Whether this export writes one file per page: a per-page exporter at [ExportScope.Whole],
     *  at an [ExportScope.Page] whose page carries a sketch ([pageHasSketch] — the bundle is two
     *  pages, arc 43 / K7), or at a [ExportScope.Calendar] target that draws more than one page. */
    fun perPage(delivery: Int, scope: ExportScope, pageHasSketch: Boolean = false): Boolean =
        delivery == ExporterContract.DELIVERY_PER_PAGE && when (scope) {
            ExportScope.Whole -> true
            is ExportScope.Page -> pageHasSketch
            is ExportScope.Calendar -> CalendarRenderPlan.pages(scope.target) > 1
        }
}
