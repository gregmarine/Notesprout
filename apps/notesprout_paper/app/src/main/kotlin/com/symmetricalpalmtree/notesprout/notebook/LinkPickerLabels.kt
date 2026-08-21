package com.symmetricalpalmtree.notesprout.notebook

import android.content.Context
import com.symmetricalpalmtree.notesprout.R
import kotlinx.coroutines.CancellationException

/**
 * Page labels for the link picker's "This notebook" grid (arc 7 / L2, Q2): "Page n", plus the
 * page's first outline heading where the Contents has one ("Page 3 — Meeting notes"). The heading
 * map comes from the same gather the Contents dialog uses ([ContentsSource.gather]) — best-effort:
 * a failed or unavailable outline just means plain "Page n" labels (picker labels are cosmetic,
 * never load-bearing). The composed labels cross the extension boundary through the catalog binder
 * (a recorded L2 widening: heading-derived text of the *current notebook only*, during a pick
 * showing only) and are capped by `LinkCatalogGate.entry` like every label. Never logged.
 */
object LinkPickerLabels {

    /** First outline label per page id, or empty on any failure / no outline-capable provider. */
    suspend fun headings(context: Context, session: NotebookSession, providers: ObjectProviders): Map<String, String> {
        val result = try {
            ContentsSource.gather(context, session, providers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        }
        val roots = (result as? ContentsSource.Result.Ok)?.roots ?: return emptyMap()
        return firstLabelPerPage(roots, session.pages.map { it.id })
    }

    /** The first (document-order) outline label per page id — pure, JVM-tested. */
    fun firstLabelPerPage(roots: List<OutlineTree.Node>, pageIds: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (node in OutlineTree.all(roots)) {
            val pageId = pageIds.getOrNull(node.pageIndex) ?: continue
            if (pageId !in out) out[pageId] = node.label
        }
        return out
    }

    /** The display label for 1-based page [n] — with a heading: "Page n — heading". */
    fun compose(context: Context, n: Int, heading: String?): String =
        if (heading.isNullOrBlank()) context.getString(R.string.link_page_label, n)
        else context.getString(R.string.link_page_heading_label, n, heading)
}
