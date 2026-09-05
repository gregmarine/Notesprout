package com.symmetricalpalmtree.notesproutsn.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The rotation journal (arc 26 / U3, D2) — written to [PassphraseStore] **before any file is
 * touched** and rewritten after every file, so a death anywhere inside a rotation is resumable.
 *
 * Lives in EncryptedSharedPreferences beside the cached global passphrase (the same posture:
 * device-local, never synced, never in the index, never in an Intent). While one exists the
 * library is in two keys: files already re-keyed open under [newPassphrase], the rest under the
 * cached global. `SnIndex.ensureReady` tries [newPassphrase] for the index and commits the rotation
 * itself when it fits (resume path 3); Bootstrap forwards to the Encryption screen so the banner
 * cannot be missed (path 2); the banner's Resume is path 1.
 *
 * @property pendingIds what is still to re-key, in [RotationPlan] order — notebooks, then
 *   `ext:<pkg>` stores, then the index id last. Ids only, never names.
 * @property newPassphrase the passphrase every file ends up under. Never logged.
 * @property minted true when [newPassphrase] is an auto-minted `NSPT-` key — the commit then clears
 *   the recovery-key acknowledgement so Bootstrap shows it once through `RecoveryKeyActivity`.
 * @property total how many ids the rotation started with — the progress dialog's `t` after a resume.
 * @property notebookCount how many of those ids were notebooks — the completion dialog's count.
 * @property startedAt device epoch-ms when the rotation began. A resume re-lists the library: a
 *   `GLOBAL` notebook not in [pendingIds] whose row is newer than this (created or imported
 *   between a Cancel and the Resume — the library is reachable in between) was minted under the
 *   OLD key and joins the list, so nothing can be left behind under a key the commit forgets.
 * @property quarantined notebooks that opened under **neither** key and were moved to `NOTEBOOK`
 *   scope (D2's quarantine). Reported at the end; U6's recovery is the way back.
 */
@Serializable
data class RotationMarker(
    val pendingIds: List<String>,
    val newPassphrase: String,
    val minted: Boolean,
    val total: Int = pendingIds.size,
    val notebookCount: Int = 0,
    val startedAt: Long = 0L,
    val quarantined: List<String> = emptyList(),
) {
    /** How many ids are done — [total] minus what is pending, quarantined ones counted as done. */
    val completed: Int get() = total - pendingIds.size

    fun without(id: String): RotationMarker = copy(pendingIds = pendingIds - id)

    /** Work that appeared after the marker was written ([RotationPlan.augment]): [extraNotebooks]
     *  join the pending notebooks, [stores] REPLACE the pending stores (the Garden listing is the
     *  truth for them), the index stays last. [total] / [notebookCount] grow by what was added. */
    fun augmented(extraNotebooks: List<String>, stores: List<String>): RotationMarker {
        val pendingNotebooks = pendingIds.filter { RotationPlan.kindOf(it) == RotationPlan.Kind.NOTEBOOK }
        val pendingStores = pendingIds.filter { RotationPlan.kindOf(it) == RotationPlan.Kind.STORE }
        val newStores = stores.map(RotationPlan::storeId)
        val added = extraNotebooks.count { it !in pendingNotebooks } + newStores.count { it !in pendingStores }
        val removed = pendingStores.count { it !in newStores }
        if (added == 0 && removed == 0) return this
        return copy(
            pendingIds = RotationPlan.order(pendingNotebooks + extraNotebooks, stores),
            total = total + added - removed,
            notebookCount = notebookCount + extraNotebooks.count { it !in pendingNotebooks },
        )
    }

    fun quarantine(id: String): RotationMarker = copy(pendingIds = pendingIds - id, quarantined = quarantined + id)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(marker: RotationMarker): String = json.encodeToString(serializer(), marker)

        /** Null for anything unreadable — a marker that cannot be read is no marker, and the cached
         *  global then decides everything (nothing was committed, so nothing is lost). */
        fun decode(text: String?): RotationMarker? {
            if (text.isNullOrEmpty()) return null
            return try {
                json.decodeFromString(serializer(), text).takeIf { it.newPassphrase.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
