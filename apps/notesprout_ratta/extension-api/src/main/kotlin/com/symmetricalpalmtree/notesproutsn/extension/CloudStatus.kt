package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What the host may know about a cloud provider's account (arc 25 / V1): whether the extension was
 * built with its provider credentials at all ([configured]), whether an account is connected, and
 * the label the person recognizes the account by. Nothing else — no token, no id, no URL.
 *
 * [configured] is false when the extension APK was built without its client credentials (the
 * `DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET` env vars were blank at build time). The host dialogs on
 * it rather than offering a Connect that cannot work. An unconfigured provider is never connected.
 *
 * `status()` never blocks on the network: this is what the store says, not what the provider
 * would say if asked. A token that was revoked server-side surfaces as [CloudContract.NOT_CONNECTED]
 * on the first operation that needs it, not here.
 *
 * The constructor `require`s **are** the validation, both directions — unmarshal is validation, the
 * family rule since E1. [accountLabel] is user content: `toString` prints its length only.
 *
 * Wire form: `int connected · int configured · String accountLabel · String providerName`. A future
 * field is a compatible tail.
 */
class CloudStatus(
    /** An account is connected — the provider holds a refresh token it believes is good. */
    val connected: Boolean,
    /** The extension has the credentials it needs to connect anyone at all. */
    val configured: Boolean,
    /** How the person knows the account — an email for Drive. Empty when not connected. */
    val accountLabel: String,
    /** The provider's display name ("Google Drive") — what the host's rows print. */
    val providerName: String,
) : Parcelable {

    init {
        requireValid(connected, configured, accountLabel, providerName)
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(if (connected) 1 else 0)
        dest.writeInt(if (configured) 1 else 0)
        dest.writeString(accountLabel)
        dest.writeString(providerName)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is CloudStatus && other.connected == connected && other.configured == configured &&
            other.accountLabel == accountLabel && other.providerName == providerName

    override fun hashCode(): Int =
        ((if (connected) 1 else 0) * 31 + (if (configured) 1 else 0)) * 31 * 31 +
            accountLabel.hashCode() * 31 + providerName.hashCode()

    /** Never the label — it is the person's account. */
    override fun toString(): String =
        "CloudStatus($providerName, connected=$connected, configured=$configured, label=${accountLabel.length} chars)"

    companion object {
        /** The constructor's checks, pure so they are JVM-testable. */
        fun requireValid(connected: Boolean, configured: Boolean, accountLabel: String, providerName: String) {
            require(!connected || configured) { "connected without being configured" }
            require(CloudContract.isLabel(accountLabel, CloudContract.MAX_ACCOUNT_LABEL_CHARS)) { "account label is not display text" }
            require(connected || accountLabel.isEmpty()) { "account label without a connection" }
            require(providerName.isNotBlank()) { "provider name is blank" }
            require(providerName == providerName.trim()) { "provider name has outer whitespace" }
            require(CloudContract.isLabel(providerName, CloudContract.MAX_PROVIDER_NAME_CHARS)) { "provider name is not display text" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<CloudStatus> = object : Parcelable.Creator<CloudStatus> {
            override fun createFromParcel(parcel: Parcel): CloudStatus =
                CloudStatus(
                    connected = parcel.readInt() != 0,
                    configured = parcel.readInt() != 0,
                    accountLabel = parcel.readString() ?: "",
                    providerName = parcel.readString() ?: "",
                )

            override fun newArray(size: Int): Array<CloudStatus?> = arrayOfNulls(size)
        }
    }
}
