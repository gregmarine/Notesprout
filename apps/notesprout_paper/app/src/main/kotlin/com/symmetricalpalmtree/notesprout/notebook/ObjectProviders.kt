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
    val signature: List<String>,
) {
    /** A client for the provider whose package is [providerKey], or null when it is not (any longer) known. */
    fun clientFor(context: Context, providerKey: String): ObjectProviderClient? =
        refs[providerKey]?.let { ObjectProviderClient(context, it, recognizerRef, markdownRef) }

    fun labelOf(providerKey: String): String = refs[providerKey]?.label?.toString() ?: providerKey

    companion object {
        private const val TAG = "ObjectProviders"

        /** Nothing installed (before the first load, and the release-safe fallback). */
        val NONE = ObjectProviders(emptyMap(), null, null, emptyList(), emptyList())

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
            for (ref in objects) {
                if (refs.containsKey(ref.packageName)) { Slog.d(TAG) { "skip second provider service in ${ref.packageName}" }; continue }
                val client = ObjectProviderClient(app, ref, recognizer, markdown)
                try {
                    val types = client.describeTypes()
                    val actions = client.describeActions()
                    if (types.isEmpty()) { Slog.d(TAG) { "skip ${ref.packageName}: no types" }; continue }
                    refs[ref.packageName] = ref
                    contributions += Contribution(ref.packageName, ref.label.toString(), types, actions)
                    Slog.d(TAG) { "${ref.packageName}: ${types.size} type(s), ${actions.size} action(s) (${actions.sumOf { it.subActions.size }} sub)" }
                } catch (e: ExtensionCallException) {
                    Slog.d(TAG) { "skip ${ref.packageName}: ${e.javaClass.simpleName}: ${e.message}" }
                }
            }
            return ObjectProviders(refs, recognizer, markdown, contributions, signatureOf(objects, recognizer, markdown))
        }
    }
}
