package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One selection-toolbar contribution, described by an object provider and **drawn by the host** (a
 * button on the notebook's floating selection toolbar). A **leaf** (no [subActions]) is what the
 * provider is asked to perform; a **parent** (with sub-actions) opens the host's sub-toolbar and is
 * never performed itself. Nesting is one level deep — the host drops anything deeper.
 *
 * - [id] — `[A-Za-z0-9_.-]+`, ≤ [ExtensionContract.MAX_ACTION_ID_CHARS]; unique within the provider.
 * - [label] — ≤ [ExtensionContract.MAX_ACTION_LABEL_CHARS]; drawn as the button text when
 *   [iconName] is not in the host's catalog, and always part of the long-press hint.
 * - [iconName] — a name from [IconNames], or null.
 * - [appliesTo] — [ActionApplies] bits; `0` means the host never shows it.
 * - [requires] — [Requires] bits.
 *
 * Hand-written Parcelable (write order fixed forever, tails may be appended): `writeString ×3
 * (null-safe icon); writeInt ×2; writeTypedList(subActions)`.
 */
class SelectionAction(
    val id: String,
    val label: String,
    val iconName: String?,
    val appliesTo: Int,
    val requires: Int,
    val subActions: List<SelectionAction>,
) : Parcelable {

    init { requireValid(id, label, appliesTo, requires) }

    private constructor(parcel: Parcel) : this(
        id = parcel.readString().orEmpty(),
        label = parcel.readString().orEmpty(),
        iconName = parcel.readString(),
        appliesTo = parcel.readInt(),
        requires = parcel.readInt(),
        subActions = ArrayList<SelectionAction>().also { parcel.readTypedList(it, CREATOR) },
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(label)
        dest.writeString(iconName)
        dest.writeInt(appliesTo)
        dest.writeInt(requires)
        dest.writeTypedList(subActions)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** `[A-Za-z0-9_.-]+` — the id alphabet (pure; the host re-checks it inward). */
        val ID_PATTERN: Regex = Regex("[A-Za-z0-9_.-]+")

        /** The structural rules the constructor enforces (pure — JVM-testable without a Parcel). */
        fun requireValid(id: String, label: String, appliesTo: Int, requires: Int) {
            require(id.length in 1..ExtensionContract.MAX_ACTION_ID_CHARS && ID_PATTERN.matches(id)) { "bad action id" }
            require(label.isNotBlank() && label.length <= ExtensionContract.MAX_ACTION_LABEL_CHARS) { "bad action label" }
            require(appliesTo >= 0 && appliesTo and ActionApplies.ALL.inv() == 0) { "bad appliesTo" }
            require(requires >= 0 && requires and Requires.ALL.inv() == 0) { "bad requires" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<SelectionAction> = object : Parcelable.Creator<SelectionAction> {
            override fun createFromParcel(parcel: Parcel): SelectionAction = SelectionAction(parcel)
            override fun newArray(size: Int): Array<SelectionAction?> = arrayOfNulls(size)
        }
    }
}
