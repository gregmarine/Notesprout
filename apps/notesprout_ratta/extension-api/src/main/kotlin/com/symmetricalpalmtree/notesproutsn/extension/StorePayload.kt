package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One payload crossing the store seam (arc 22 / X1): a [StoreCodec] statement batch going in, or one
 * chunk of encoded rows coming out. **Exactly one** of [inline] / [region] is set — a payload at or
 * under [ExtensionContract.STORE_MAX_INLINE_BYTES] rides inline as a `byte[]`, anything above it
 * rides a [LargeValue] over ashmem (up to [ExtensionContract.STORE_MAX_VALUE_BYTES]).
 *
 * The handshake for a region is `putLarge`'s (arc 11 / J2): the **sender** creates it, writes,
 * protects it read-only, hands it over and closes its own handle once the transaction carrying it
 * is marshalled; the **receiver** copies out exactly `byteCount` bytes and closes ([readAndClose]).
 * [of] picks the carrier by size; [readAndClose] is the receiver's one call for either.
 *
 * Wire form: `int carrier (0 inline · 1 region) · byte[] | LargeValue`. [requireValid] runs at
 * construction, so at unmarshal too.
 */
class StorePayload(
    val inline: ByteArray?,
    val region: LargeValue?,
) : Parcelable {

    init {
        requireValid(inline?.size, region != null)
    }

    /** The bytes: the inline array as-is, or the region copied out **and closed**. Either way the
     *  caller owns the result and the region (if any) is finished with. */
    fun readAndClose(): ByteArray = inline ?: SharedBytes.readAndClose(region!!)

    /** The payload's size in bytes without touching a region's memory. */
    val byteCount: Int get() = inline?.size ?: region!!.byteCount

    override fun writeToParcel(dest: Parcel, flags: Int) {
        if (inline != null) {
            dest.writeInt(CARRIER_INLINE)
            dest.writeByteArray(inline)
        } else {
            dest.writeInt(CARRIER_REGION)
            dest.writeParcelable(region, flags)
        }
    }

    /** A region carries a file descriptor and must say so, or `Bundle.hasFileDescriptors()` lies. */
    override fun describeContents(): Int = if (region != null) Parcelable.CONTENTS_FILE_DESCRIPTOR else 0

    companion object {
        private const val CARRIER_INLINE = 0
        private const val CARRIER_REGION = 1

        /** The constructor's checks, pure so they are JVM-testable. */
        fun requireValid(inlineSize: Int?, hasRegion: Boolean) {
            require((inlineSize != null) != hasRegion) { "exactly one of inline / region must be set" }
            if (inlineSize != null) {
                require(inlineSize <= ExtensionContract.STORE_MAX_INLINE_BYTES) {
                    "inline payload exceeds ${ExtensionContract.STORE_MAX_INLINE_BYTES} bytes ($inlineSize)"
                }
            }
        }

        /** Wrap [bytes] in the carrier its size calls for: inline at or under the inline cap, else an
         *  ashmem region (device only). Throws on a payload above [ExtensionContract.STORE_MAX_VALUE_BYTES]. */
        fun of(bytes: ByteArray): StorePayload =
            if (bytes.size <= ExtensionContract.STORE_MAX_INLINE_BYTES) StorePayload(bytes, null)
            else StorePayload(null, SharedBytes.write(bytes))

        @JvmField
        val CREATOR: Parcelable.Creator<StorePayload> = object : Parcelable.Creator<StorePayload> {
            @Suppress("DEPRECATION")
            override fun createFromParcel(parcel: Parcel): StorePayload {
                val carrier = parcel.readInt()
                return if (carrier == CARRIER_INLINE) {
                    StorePayload(parcel.createByteArray(), null)
                } else {
                    StorePayload(null, parcel.readParcelable(LargeValue::class.java.classLoader))
                }
            }
            override fun newArray(size: Int): Array<StorePayload?> = arrayOfNulls(size)
        }
    }
}
