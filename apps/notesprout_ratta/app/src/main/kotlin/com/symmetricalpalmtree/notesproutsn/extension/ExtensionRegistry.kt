package com.symmetricalpalmtree.notesproutsn.extension

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
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
 * Discovery + trust for SN's eight extension points. A candidate `<service>` is kept only if it is
 * exported, its `<meta-data>` API version is one [ExtensionContract.accepts] for the point — the
 * range `1..API_VERSION` (the declared number is what the extension *requires* of the host — the
 * arc-18 / D3 skew guard, reasoned at the constant), **with the floor** the point carries
 * ([ExtensionContract.minApiVersion] — 6 on the three store-taking points since arc 22 / X1, because
 * a replaced `IExtensionStore` breaks the old-extension/new-host direction too; 7 on the calendar
 * point, born there in arc 23 / Y1; 8 on the cloud point, born there in arc 25 / V1) — and it is signed
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
     * The one trusted tag manager, or null (arc 21 / W1 — SN's **sixth** capability point, and its
     * third screen-owning one). Same filter and same first-wins rule as [scratchPad]: a second
     * installed manager is ignored with a `Slog.d`, because two tag indexes would be two libraries.
     * Re-run every time a tag door is about to be offered — a package can be disabled or replaced
     * under a standing screen, and every one of those doors is **GONE** when this answers null.
     */
    suspend fun tagManager(context: Context): ProviderRef? = withContext(Dispatchers.IO) {
        val all = discover(context.applicationContext, ExtensionContract.ACTION_TAG_MANAGER)
        for (extra in all.drop(1)) Slog.d(TAG) { "ignoring additional tag manager ${extra.component.flattenToShortString()}" }
        all.firstOrNull()
    }

    /**
     * The one trusted calendar, or null (arc 23 / Y1 — SN's **seventh** capability point, and its
     * fourth screen-owning one). Same filter and same first-wins rule as [scratchPad]: a second
     * installed calendar is ignored with a `Slog.d`, because two organizers would be two bookmarks.
     * Re-run on every resume of a screen that shows the calendar's entry button.
     */
    suspend fun calendar(context: Context): ProviderRef? = withContext(Dispatchers.IO) {
        val all = discover(context.applicationContext, ExtensionContract.ACTION_CALENDAR)
        for (extra in all.drop(1)) Slog.d(TAG) { "ignoring additional calendar ${extra.component.flattenToShortString()}" }
        all.firstOrNull()
    }

    /**
     * The one trusted cloud provider, or null (arc 25 / V1 — SN's **eighth** capability point, and
     * the first that is **generic over a provider**: `NSE · Google Drive` is the first one, a second
     * provider would be another extension on this same point, not another point).
     *
     * First-wins like every other singular point, but for a different reason than the calendar's:
     * two providers are not two of anything broken, they are a question this arc does not ask — a
     * chooser is a future decision (`DRIVE_PLAN.md` § "Host side"). So the extras are dropped, and
     * because more than one installed provider is a real configuration oddity rather than routine
     * noise it is a `Log.w` and not a `Slog.d`; the ordinary "how many did discovery keep" line
     * stays a `Slog.d` inside [discover].
     *
     * Re-run every time a cloud door is about to be offered — a package can be disabled or replaced
     * under a standing screen, and every one of those doors is **GONE** when this answers null.
     */
    suspend fun cloud(context: Context): ProviderRef? = withContext(Dispatchers.IO) {
        val all = discover(context.applicationContext, CloudContract.ACTION_CLOUD_STORAGE)
        if (all.size > 1) {
            Log.w(TAG, "${all.size} cloud providers installed — using ${all[0].packageName}, ignoring the rest")
            for (extra in all.drop(1)) Slog.d(TAG) { "ignoring additional cloud provider ${extra.component.flattenToShortString()}" }
        }
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
            if (!ExtensionContract.accepts(action, apiVersion)) {
                Slog.d(TAG) {
                    "skip $component: api version $apiVersion outside " +
                        "${ExtensionContract.minApiVersion(action)}..${ExtensionContract.API_VERSION}"
                }
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
