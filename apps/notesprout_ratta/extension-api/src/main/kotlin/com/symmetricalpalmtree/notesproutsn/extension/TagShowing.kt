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
 * **The target is a pair** (arc 21 / W4): a notebook, and — when the showing is about one page —
 * that page. It is never a page alone. Before W4 a page showing carried a page id and no way to say
 * which notebook it belonged to, which is exactly the gap that made tagged pages unfindable from the
 * library; the same shape now runs through [AssignmentRecord], the store's rows and this parcel, so no
 * layer can hold a page without its notebook.
 *
 * Wire form: `String notebookId · String pageId (nullable) · String targetLabel · int mode ·
 * String prefill (nullable) · String[] pageIds · String[] pageLabels`. A future field is a
 * compatible tail.
 */
class TagShowing(
    /** The notebook the showing is about — **always present**, page showings included. */
    val notebookId: String,
    /** The page within [notebookId], when the tags being edited hang on a page rather than the
     *  notebook itself. Null makes this a notebook showing. */
    val pageId: String?,
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

    /** [TARGET_NOTEBOOK] or [TARGET_PAGE] — derived from whether [pageId] is present, never carried
     *  separately (the W4 rule: a stored kind is a second copy of the answer). */
    val targetKind: Int get() = if (pageId == null) TARGET_NOTEBOOK else TARGET_PAGE

    /** The thing the tags hang on: the page when there is one, else the notebook. */
    val targetId: String get() = pageId ?: notebookId

    init {
        // Both ids are canonical UUIDs, which is also what keeps a path character or a NUL out of
        // them: the UUID alphabet has neither. The hand-rolled character checks W1 carried here were
        // a weaker spelling of the same guarantee.
        require(TagRules.isId(notebookId)) { "notebookId is not a UUID" }
        require(pageId == null || TagRules.isId(pageId)) { "pageId is not a UUID" }
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
            require(TagRules.isId(id)) { "page id is not a UUID" }
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
        dest.writeString(notebookId)
        dest.writeString(pageId)
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
            notebookId = parcel.readString() ?: "",
            pageId = parcel.readString(),
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
