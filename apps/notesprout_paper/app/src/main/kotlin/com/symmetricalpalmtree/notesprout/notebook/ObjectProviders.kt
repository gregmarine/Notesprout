package com.symmetricalpalmtree.notesprout.notebook

import android.content.Context
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.ObjectProviderClient
import com.symmetricalpalmtree.notesprout.extension.ProviderRef

/**
 * The object providers the notebook screen knows about for one open (arc 4 / H4): every trusted
 * `OBJECT_PROVIDER` with its typeIds + capped toolbar [contributions] (fetched **once per open** on
 * IO — `describeTypes` + `describeActions` per provider, a provider that fails either is skipped
 * with a log line), plus the one recognizer and the one Markdown renderer the core would lend
 * through the proxies (null when none is installed — the core never fakes a capability).
 * Arc 5: after the describes, each provider is **probed** for `describeOutline` (`supportsOutline`,
 * one blank payload) — [Contribution.outline]; [hasOutline] / [outlineProviders] drive the Contents.
 * The probe's result does **not** join [signature] (the extension set decides it, not a capability).
 * `providerKey` == the extension's package name — the left half of an object identity
 * `<pkg>:<typeId>` (`ExtensionContract.objectIdentity`), which is how a selected object finds its
 * provider (`SelectionActions.shapeOf`).
 *
 * [signature] is the cheap change detector for `onResume`: the discovered components of all three
 * points, so an extension installed / removed / disabled while the screen was away is picked up
 * (`ExtensionRegistry` never returns a disabled package) without re-binding anything.
 */
class ObjectProviders private constructor(
    private val refs: Map<String, ProviderRef>,
    val recognizerRef: ProviderRef?,
    val markdownRef: ProviderRef?,
    val contributions: List<Contribution>,
    /** The discovery signature — or, when a provider's describe calls failed at load (cold process past
     *  the bind timeout), that signature plus [PARTIAL], which no fresh discovery ever equals, so the
     *  next `onResume` compare reloads instead of leaving the provider silent for the whole open (H5). */
    val signature: List<String>,
) {
    /** A client for the provider whose package is [providerKey], or null when it is not (any longer) known. */
    fun clientFor(context: Context, providerKey: String): ObjectProviderClient? =
        refs[providerKey]?.let { ObjectProviderClient(context, it, recognizerRef, markdownRef) }

    fun labelOf(providerKey: String): String = refs[providerKey]?.label?.toString() ?: providerKey

    /** Any loaded provider answers `describeOutline` (arc 5) — the Contents button shows / the swipe acts. */
    val hasOutline: Boolean get() = contributions.any { it.outline }

    /** The provider keys (packages) that answer `describeOutline`, in [contributions] order. */
    val outlineProviders: List<String> get() = contributions.filter { it.outline }.map { it.providerKey }

    companion object {
        private const val TAG = "ObjectProviders"

        /** Nothing installed (before the first load, and the release-safe fallback). */
        val NONE = ObjectProviders(emptyMap(), null, null, emptyList(), emptyList())
        private const val PARTIAL = "!partial"

        /** Discovery of the three points → the component list [signature] compares (IO). */
        suspend fun signature(context: Context): List<String> {
            val objects = ExtensionRegistry.objectProviders(context)
            val recognizer = ExtensionRegistry.handwritingRecognizer(context)
            val markdown = ExtensionRegistry.markdownRenderer(context)
            return signatureOf(objects, recognizer, markdown)
        }

        private fun signatureOf(objects: List<ProviderRef>, recognizer: ProviderRef?, markdown: ProviderRef?): List<String> =
            objects.map { "o:" + it.component.flattenToShortString() } +
                listOfNotNull(recognizer?.let { "r:" + it.component.flattenToShortString() }, markdown?.let { "m:" + it.component.flattenToShortString() })

        /** Discover + describe every provider (IO; binds each once for types and once for actions). */
        suspend fun load(context: Context): ObjectProviders {
            val app = context.applicationContext
            val objects = ExtensionRegistry.objectProviders(app)
            val recognizer = ExtensionRegistry.handwritingRecognizer(app)
            val markdown = ExtensionRegistry.markdownRenderer(app)
            val refs = LinkedHashMap<String, ProviderRef>()
            val contributions = ArrayList<Contribution>()
            var partial = false
            for (ref in objects) {
                if (refs.containsKey(ref.packageName)) { Slog.d(TAG) { "skip second provider service in ${ref.packageName}" }; continue }
                val client = ObjectProviderClient(app, ref, recognizer, markdown)
                try {
                    val types = client.describeTypes()
                    val actions = client.describeActions()
                    if (types.isEmpty()) { Slog.d(TAG) { "skip ${ref.packageName}: no types" }; continue }
                    refs[ref.packageName] = ref
                    // Arc 5: the outline probe — one blank payload; capable ⇔ a one-entry reply. A provider built
                    // before `describeOutline` existed fails it (empty reply / exception) and is simply not capable.
                    val outline = client.supportsOutline(types.first())
                    contributions += Contribution(ref.packageName, ref.label.toString(), types, actions, outline)
                    Slog.d(TAG) { "${ref.packageName}: ${types.size} type(s), ${actions.size} action(s) (${actions.sumOf { it.subActions.size }} sub), outline=$outline" }
                } catch (e: ExtensionCallException) {
                    // Known but undescribed: its objects still go to the render pass (placeholders until it answers),
                    // no toolbar contribution, and the load is marked partial so resume retries.
                    Slog.d(TAG) { "${ref.packageName} did not describe itself (${e.javaClass.simpleName}: ${e.message}) — partial load" }
                    refs[ref.packageName] = ref
                    partial = true
                }
            }
            val sig = signatureOf(objects, recognizer, markdown)
            return ObjectProviders(refs, recognizer, markdown, contributions, if (partial) sig + PARTIAL else sig)
        }
    }
}
