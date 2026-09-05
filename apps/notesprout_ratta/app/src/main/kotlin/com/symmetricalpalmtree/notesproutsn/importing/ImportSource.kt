package com.symmetricalpalmtree.notesproutsn.importing

/**
 * **Where an import comes from** (arc 25 / V5) — the source question's whole decision core, pure so
 * that a dialog answered by *index* can never turn into a crash in the flow.
 *
 * The rule is one sentence: *a second source exists only when a trusted cloud provider is
 * installed.* Without one the tap goes straight to the document picker, byte for byte as every
 * import before this phase did — no dialog, no beat, nothing to dismiss. With one, the tap asks
 * first, and the two answers are offered in the order the person is most likely to want them: this
 * device, then the provider.
 *
 * What is deliberately **not** here is what tapping the cloud answer then *does*: that is
 * [com.symmetricalpalmtree.notesproutsn.export.ExportDestination.onCloudTap], the same rule the
 * Export screen's Destination row keeps, reused rather than copied — a second copy of it would be a
 * second place for "connected?" to be answered differently.
 */
object ImportSource {

    /** The two places a file can come from. [LOCAL] is what every import before arc 25 did. */
    enum class Source {
        /** SAF `ACTION_OPEN_DOCUMENT`: a document on this device. */
        LOCAL,

        /** The one installed cloud provider's tree, through the host-drawn browser. */
        CLOUD,
    }

    /** The answers on offer, in the order they are drawn. */
    fun choices(providerInstalled: Boolean): List<Source> =
        if (providerInstalled) listOf(Source.LOCAL, Source.CLOUD) else listOf(Source.LOCAL)

    /** Whether the tap asks anything at all — one answer is not a question. */
    fun asksSource(providerInstalled: Boolean): Boolean = choices(providerInstalled).size > 1

    /** The answer at [index], or null when the index names nothing: a list dialog answers with a
     *  position, and a position the flow cannot place must end the beat rather than end the app. */
    fun sourceAt(index: Int, providerInstalled: Boolean): Source? =
        choices(providerInstalled).getOrNull(index)
}
