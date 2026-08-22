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
 * Discovery + trust for SN's one extension point. A candidate `<service>` is kept only if it is
 * exported, its `<meta-data>` API version equals [ExtensionContract.API_VERSION], and it is signed
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
            if (apiVersion != ExtensionContract.API_VERSION) {
                Slog.d(TAG) { "skip $component: api version $apiVersion != ${ExtensionContract.API_VERSION}" }
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
