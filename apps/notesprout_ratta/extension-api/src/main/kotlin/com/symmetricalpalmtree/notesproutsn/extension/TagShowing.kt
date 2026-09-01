package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * Everything one showing of the tag screen needs to know (arc 21 / W1) — handed over on the **held
 * bind**, by `ITagManager.configureShowing`, after `begin` and before the screen is launched.
 *
 * **Nothing rides the screen's Intent.** Not because ids are secret, but because tag text and target
 * labels are the user's own words: an Intent extra is readable in a `dumpsys` and survives in the
 * recent-tasks description, and the arc-19 rule (document text crosses the bind, never the Intent)
 * applies to a tag for exactly the same reason. The Intent carries the action and the package, and
 * that is all.
 *
 * The constructor `require`s **are** the validation, both directions — unmarshal is validation, the
 * family rule since E1. This parcel crosses **into** the extension, which treats the host as
 * untrusted input just as the host treats a descriptor.
 *
 * Wire form: `int targetKind · String targetId · String targetLabel · int mode · String prefill
 * (nullable) · String[] pageIds · String[] pageLabels`. A future field is a compatible tail.
 */
class TagShowing(
    /** [TARGET_NOTEBOOK] or [TARGET_PAGE] — what the tags being edited hang on. */
    val targetKind: Int,
    /** The target's opaque host id (a notebook or page UUID — the M3 pageKey precedent). */
    val targetId: String,
    /** The target's display name, resolved host-side. Display only; never a path. */
    val targetLabel: String,
    /** [MODE_BROWSE] / [MODE_ADD] / [MODE_MANAGE]. */
    val mode: Int,
    /** Text to prefill the add field with — the recognized selection (W3). Null in every other flow. */
    val prefill: String? = null,
    /** MANAGE only (W2): the notebook's page ids, in page order. Empty otherwise. */
    val pageIds: List<String> = emptyList(),
    /** MANAGE only (W2): the display label for each of [pageIds], same order and length. Page
     *  *numbers* are the host's to name — the extension has no idea what a page is called. */
    val pageLabels: List<String> = emptyList(),
) : Parcelable {

    init {
        require(targetKind == TARGET_NOTEBOOK || targetKind == TARGET_PAGE) { "unknown target kind ($targetKind)" }
        require(targetId.isNotEmpty() && targetId.length <= ExtensionContract.MAX_TARGET_ID_CHARS) {
            "targetId length ${targetId.length} outside 1..${ExtensionContract.MAX_TARGET_ID_CHARS}"
        }
        require('\u0000' !in targetId && '/' !in targetId) { "targetId carries a path character" }
        require(targetLabel.length <= ExtensionContract.MAX_TARGET_LABEL_CHARS) {
            "targetLabel length ${targetLabel.length} > ${ExtensionContract.MAX_TARGET_LABEL_CHARS}"
        }
        require('\u0000' !in targetLabel) { "targetLabel carries NUL" }
        require(mode == MODE_BROWSE || mode == MODE_ADD || mode == MODE_MANAGE) { "unknown mode ($mode)" }
        if (prefill != null) {
            require(prefill.length <= ExtensionContract.MAX_TAG_CHARS) {
                "prefill length ${prefill.length} > ${ExtensionContract.MAX_TAG_CHARS}"
            }
        }
        require(pageIds.size == pageLabels.size) { "pageIds/pageLabels length mismatch" }
        require(pageIds.size <= MAX_PAGES) { "pageIds size ${pageIds.size} > $MAX_PAGES" }
        for (id in pageIds) {
            require(id.isNotEmpty() && id.length <= ExtensionContract.MAX_TARGET_ID_CHARS) { "bad page id" }
        }
        for (label in pageLabels) {
            require(label.length <= ExtensionContract.MAX_TARGET_LABEL_CHARS) { "page label too long" }
        }
        // MANAGE is the only mode that carries pages, and it is meaningless without a notebook to
        // manage — a page's own screen has nothing to page through.
        if (mode != MODE_MANAGE) require(pageIds.isEmpty()) { "pages outside MODE_MANAGE" }
        if (mode == MODE_MANAGE) require(targetKind == TARGET_NOTEBOOK) { "MODE_MANAGE on a page" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(targetKind)
        dest.writeString(targetId)
        dest.writeString(targetLabel)
        dest.writeInt(mode)
        dest.writeString(prefill)
        dest.writeStringList(pageIds)
        dest.writeStringList(pageLabels)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val TARGET_NOTEBOOK: Int = 0
        const val TARGET_PAGE: Int = 1

        /** Open on the target's tags, nothing focused — the library's "Tags…" row. */
        const val MODE_BROWSE: Int = 0

        /** Open with the add field focused and the keyboard up — the notebook's quick-add (W2). */
        const val MODE_ADD: Int = 1

        /** Open on the notebook **and** every page's tags (W2). */
        const val MODE_MANAGE: Int = 2

        /** A bound on MANAGE's arrays — a notebook far past any real page count, and a cap the
         *  unmarshal can refuse rather than allocate. */
        const val MAX_PAGES: Int = 5_000

        private fun read(parcel: Parcel): TagShowing = TagShowing(
            targetKind = parcel.readInt(),
            targetId = parcel.readString() ?: "",
            targetLabel = parcel.readString() ?: "",
            mode = parcel.readInt(),
            prefill = parcel.readString(),
            pageIds = ArrayList<String>().also { parcel.readStringList(it) },
            pageLabels = ArrayList<String>().also { parcel.readStringList(it) },
        )

        @JvmField
        val CREATOR: Parcelable.Creator<TagShowing> = object : Parcelable.Creator<TagShowing> {
            override fun createFromParcel(parcel: Parcel): TagShowing = read(parcel)
            override fun newArray(size: Int): Array<TagShowing?> = arrayOfNulls(size)
        }
    }
}
