package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.CloudStatus

/**
 * **Where an export goes** (arc 25 / V3) — the Destination row's whole decision core, pure so that
 * the three rules it stands on are pinned by JVM test rather than by a device walk.
 *
 * The row asks one question — *this device, or the cloud* — and it exists only when there is a
 * second answer to give:
 *
 *  - **[rowVisible]**: a trusted cloud provider is installed. **GONE otherwise, never disabled** (the
 *    family rule): a radio pair with one operable half reads as broken, not as settled. Installed is
 *    the only question here — a provider that will not *answer* keeps its row, because "did not
 *    answer" is a thing to say at the tap, not a reason to hide the door.
 *  - **[settled]**: the [ExportDocumentRules.sourceRowVisible] rule pointed at this row. A standing
 *    *cloud* answer is forced back to *local* whenever the row is not on screen — a provider
 *    uninstalled under a standing screen must never leave an export aimed at a cloud that is no
 *    longer there.
 *  - **[onCloudTap]**: what tapping the cloud radio does, given the last [CloudStatus] the provider
 *    gave (null when it did not answer at all). The order is the Backup screen's Cloud line, and for
 *    the same reason: a build with no credentials must say so before it says anything about an
 *    account, because offering a sign-in that cannot work is a worse answer than a plain refusal.
 *    Everything else that is not a live connection — no account, or no answer — is the **inline
 *    Connect offer**, which is the one thing that can help in either case.
 *
 * The status is deliberately **not** remembered across discoveries by the screen: the connect door
 * changes it, and a stale "connected" would send an export at a cloud that has since been
 * disconnected. This object holds no state at all, which is what makes that the caller's rule to
 * keep rather than a thing hidden here.
 */
object ExportDestination {

    /** The row's two answers. *Local* is the default and what every export before this arc did. */
    enum class Choice {
        /** SAF: the picker names a document on this device. */
        LOCAL,

        /** The provider's own tree, through the host-drawn browser. */
        CLOUD,
    }

    /** What a tap on the cloud radio means right now. */
    enum class Tap {
        /** Connected — take the answer. */
        SELECT,

        /** The extension was built without its credentials: the *not set up* problem dialog. */
        NOT_CONFIGURED,

        /** No account, or no answer: the inline *Connect to <provider>?* offer. */
        OFFER_CONNECT,
    }

    /** Whether the Destination row is on screen at all. */
    fun rowVisible(providerInstalled: Boolean): Boolean = providerInstalled

    /** [choice] as it stands once the row's own visibility has had its say — cloud never survives
     *  the row going away. */
    fun settled(choice: Choice, rowVisible: Boolean): Choice =
        if (rowVisible) choice else Choice.LOCAL

    /** What a tap on the cloud radio should do, given the last status (null = the provider did not
     *  answer). See the class doc for why the order is this one. */
    fun onCloudTap(status: CloudStatus?): Tap = when {
        status == null -> Tap.OFFER_CONNECT
        !status.configured -> Tap.NOT_CONFIGURED
        !status.connected -> Tap.OFFER_CONNECT
        else -> Tap.SELECT
    }

    /**
     * What the cloud radio, the offer dialog and every cloud sentence call the provider: the name it
     * gave for itself, and the extension's own label when it gave none. An unnamed destination is
     * not a destination a person can choose between.
     */
    fun providerName(status: CloudStatus?, extensionLabel: String): String =
        status?.providerName?.takeIf { it.isNotBlank() } ?: extensionLabel
}
