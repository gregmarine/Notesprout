package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What the link picker chose (arc 7 / L0) — `ILinkProvider.takeResult`'s reply: the opaque [payload]
 * the core stores in the link row's `text` column (never parsed, never logged) and the [chrome] flag
 * (`ExtensionContract.LINK_CHROME_*`). The chrome also lives *inside* the payload — it rides here so
 * the core can draw the underline immediately at creation without a `chromeOf` round trip (transient:
 * the core persists only the payload; chrome is re-described at every page load — L0 wizard Q4).
 *
 * Hand-written Parcelable (write order fixed forever, tails may be appended):
 * `writeString(payload); writeInt(chrome)`.
 */
class LinkChoice(
    val payload: String,
    val chrome: Int,
) : Parcelable {

    init { requireValid(payload, chrome) }

    private constructor(parcel: Parcel) : this(
        payload = parcel.readString().orEmpty(),
        chrome = parcel.readInt(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(payload)
        dest.writeInt(chrome)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** The structural rules the constructor enforces (pure — JVM-testable without a Parcel). */
        fun requireValid(payload: String, chrome: Int) {
            require(payload.isNotBlank()) { "payload is blank" }
            require(payload.length <= ExtensionContract.MAX_LINK_PAYLOAD_CHARS) { "payload too long" }
            require(
                chrome == ExtensionContract.LINK_CHROME_NONE ||
                    chrome == ExtensionContract.LINK_CHROME_UNDERLINE
            ) { "unknown chrome $chrome" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<LinkChoice> = object : Parcelable.Creator<LinkChoice> {
            override fun createFromParcel(parcel: Parcel): LinkChoice = LinkChoice(parcel)
            override fun newArray(size: Int): Array<LinkChoice?> = arrayOfNulls(size)
        }
    }
}
