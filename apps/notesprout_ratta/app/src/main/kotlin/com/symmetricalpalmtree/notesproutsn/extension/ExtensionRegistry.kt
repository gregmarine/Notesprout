package com.symmetricalpalmtree.notesproutsn.extension

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A discovered, trusted extension service for one extension point. */
data class ProviderRef(
    val component: ComponentName,
    val packageName: String,
    val label: CharSequence,
    val apiVersion: Int,
)

/**
 * Discovery + trust for SN's five extension points. A candidate `<service>` is kept only if it is
 * exported, its `<meta-data>` API version is in `1..`[ExtensionContract.API_VERSION] (the declared
 * number is what the extension *requires* of the host — the arc-18 / D3 skew guard, reasoned at
 * the constant), and it is signed
 * with the host's own certificate (`checkSignatures == SIGNATURE_MATCH` — same-signature only).
 * Everything else is skipped with a `Slog.d`. Disabled packages/components are never returned by the
 * query, so `pm disable` == uninstalled from the host's point of view.
 */
object ExtensionRegistry {

    private const val TAG = "ExtensionRegistry"

    /**
     * The one trusted handwriting recognizer, or null. When several survive the filter the **first**
     * by (label, package) is used and the rest are dropped with a `Slog.d` — choosing an engine is a
     * future arc's territory.
     */
    suspend fun handwritingRecognizer(context: Context): ProviderRef? = withContext(Dispatchers.IO) {
        val all = discover(context.applicationContext, ExtensionContract.ACTION_HANDWRITING_RECOGNIZER)
        for (extra in all.drop(1)) Slog.d(TAG) { "ignoring additional recognizer ${extra.component.flattenToShortString()}" }
        all.firstOrNull()
    }

    /**
     * The one trusted scratch pad, or null (arc 11 / J3). Same filter and same first-wins rule: a
     * second installed pad is ignored with a `Slog.d`. Re-run on every resume of a screen that shows
     * the pad's entry button — a package can be disabled or replaced under it.
     */
    suspend fun scratchPad(context: Context): ProviderRef? = withContext(Dispatchers.IO) {
        val all = discover(context.applicationContext, ExtensionContract.ACTION_SCRATCH_PAD)
        for (extra in all.drop(1)) Slog.d(TAG) { "ignoring additional scratch pad ${extra.component.flattenToShortString()}" }
        all.firstOrNull()
    }

    /**
     * The one trusted document editor, or null (arc 19 / M3 — SN's **fifth** capability point, and
     * its second screen-owning one). Same filter and same first-wins rule as [scratchPad]: a second
     * installed editor is ignored with a `Slog.d`, because choosing between editors is not a
     * question this arc asks. Re-run on every resume of a screen showing the Document button — a
     * package can be disabled or replaced under it.
     */
    suspend fun documentEditor(context: Context): ProviderRef? = withContext(Dispatchers.IO) {
        val all = discover(context.applicationContext, DocumentContract.ACTION_DOCUMENT_EDITOR)
        for (extra in all.drop(1)) Slog.d(TAG) { "ignoring additional document editor ${extra.component.flattenToShortString()}" }
        all.firstOrNull()
    }

    /**
     * Every trusted notebook exporter, ordered by (label, package) — plural on purpose (arc 15 /
     * E1): any number may register, the Export screen lists them all, and with exactly one the
     * chooser collapses to a label. Re-run at every library-sheet open — a package can be
     * disabled or replaced under a standing screen.
     */
    suspend fun exporters(context: Context): List<ProviderRef> = withContext(Dispatchers.IO) {
        discover(context.applicationContext, ExporterContract.ACTION_NOTEBOOK_EXPORTER)
    }

    /**
     * Every trusted notebook importer, ordered by (label, package) — plural for the same reason
     * [exporters] is (arc 16 / I1): any number may register, and the library's Import button is
     * there when at least one is and **GONE** when none is. Re-run on every library resume — a
     * package can be disabled or replaced under a standing screen.
     */
    suspend fun importers(context: Context): List<ProviderRef> = withContext(Dispatchers.IO) {
        discover(context.applicationContext, ImporterContract.ACTION_NOTEBOOK_IMPORTER)
    }

    @Suppress("DEPRECATION")
    private fun discover(context: Context, action: String): List<ProviderRef> {
        val pm = context.packageManager
        val candidates = pm.queryIntentServices(Intent(action), PackageManager.GET_META_DATA)
        val kept = ArrayList<ProviderRef>(candidates.size)
        for (ri in candidates) {
            val si = ri.serviceInfo ?: continue
            val component = ComponentName(si.packageName, si.name)
            if (!si.exported) {
                Slog.d(TAG) { "skip $component: not exported" }
                continue
            }
            val apiVersion = si.metaData?.getInt(ExtensionContract.META_API_VERSION, -1) ?: -1
            if (apiVersion !in 1..ExtensionContract.API_VERSION) {
                Slog.d(TAG) { "skip $component: api version $apiVersion outside 1..${ExtensionContract.API_VERSION}" }
                continue
            }
            if (pm.checkSignatures(context.packageName, si.packageName) != PackageManager.SIGNATURE_MATCH) {
                Slog.d(TAG) { "skip $component: signature mismatch" }
                continue
            }
            val label = si.applicationInfo?.loadLabel(pm) ?: si.packageName
            kept += ProviderRef(component, si.packageName, label, apiVersion)
        }
        val sorted = kept.sortedWith(compareBy<ProviderRef>({ it.label.toString() }, { it.packageName }))
        Slog.d(TAG) { "$action: ${sorted.size} provider(s) of ${candidates.size} candidate(s)" }
        return sorted
    }
}
