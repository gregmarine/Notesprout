package com.symmetricalpalmtree.notesproutsn.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the backup subsystem remembers (arc 17 / K2), serialized as kotlinx JSON into the
 * single `backup` row of the global index ([BackupStore]).
 *
 * The stamp map is og's D8 rule made portable: a notebook needs copying when it has no stamp or
 * its `updatedAt` is newer than its stamp ([BackupPredicates.needsBackup]). A stamp is written
 * **per successful copy** — a failed copy never stamps, so it retries next run — and the value is
 * the notebook's `updatedAt` *as read at work-list time*, never the wall clock: "I copied the
 * state as of this edit", so an edit that lands mid-run can never be masked by a later clock.
 *
 * [decode] never throws: a corrupt blob reads as a fresh config, whose worst case is re-copying
 * everything — the safe direction for a backup.
 */
@Serializable
data class BackupConfig(
    val version: Int = VERSION,
    /** The persisted SAF tree URI, or null while no folder has been chosen. */
    val treeUri: String? = null,
    /** Device-local epoch-ms of the last run in which at least one destination write succeeded. */
    val lastRunAt: Long? = null,
    /** The last successful run's copied count — the screen's status line survives a relaunch. */
    val lastCopied: Int? = null,
    /** The last successful run's skipped count (up-to-date + excluded + held + missing). */
    val lastSkipped: Int? = null,
    /** notebookId → the `updatedAt` its last successful copy carried. */
    val stamps: Map<String, Long> = emptyMap(),
) {
    companion object {
        /** Config grammar version. Written into the row's `flags` too. */
        const val VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Encoded UTF-8 JSON. Null only if serialization itself fails (never expected). */
        fun encode(config: BackupConfig): ByteArray? = try {
            json.encodeToString(serializer(), config).toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }

        /** The config in [bytes]; a fresh default for anything unusable — absent, malformed, or
         *  written by a newer grammar than this build understands. */
        fun decode(bytes: ByteArray?): BackupConfig {
            if (bytes == null || bytes.isEmpty()) return BackupConfig()
            val config = try {
                json.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                return BackupConfig()
            }
            return if (config.version in 1..VERSION) config else BackupConfig()
        }
    }
}
