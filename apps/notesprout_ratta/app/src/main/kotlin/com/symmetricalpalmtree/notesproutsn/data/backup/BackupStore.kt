package com.symmetricalpalmtree.notesproutsn.data.backup

import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectDao
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex

/**
 * The backup config's one row in `notesprout.db` (arc 17 / K2) — the [ClipStore] pattern: a
 * singleton row at a sentinel id, upserted whole, never soft-deleted, invisible to every
 * type-filtered listing. Living in the index (rather than prefs) is what lets the stamp map ride
 * the same encryption, the same backup, and the same restore as the rows it describes.
 */
class BackupStore(private val dao: ObjectDao = SnIndex.dao()) {

    /** The stored config; a fresh default when the row is absent or unusable (the decode never
     *  throws, and a guarded read treats an over-window blob the same way — `ClipStore`'s B3
     *  lesson, applied before it can fire: this blob is small, but the read is guarded anyway). */
    suspend fun read(): BackupConfig =
        BackupConfig.decode(runCatching { dao.backupBlob(ListIds.BACKUP_ID) }.getOrNull())

    /**
     * Forget [notebookId]'s stamp, if one stands. An import that lands on an existing id can
     * install content whose `updatedAt` is *older* than the stamp — without this, the notebook
     * reads "up to date" forever and the backup keeps the pre-import bytes (K3 review). Cheap
     * no-op when the notebook was never stamped.
     */
    suspend fun clearStamp(notebookId: String) {
        val config = read()
        if (notebookId in config.stamps || notebookId in config.cloudStamps) {
            // Both maps (arc 26 / U3): a stamp is a statement about one destination, and the reason
            // for forgetting it — the bytes changed under an unchanged `updatedAt` — holds for both.
            write(config.copy(stamps = config.stamps - notebookId, cloudStamps = config.cloudStamps - notebookId))
        }
    }

    /**
     * Forget every stamp in both maps (arc 26 / U3 — the global rotation, decision 4). A rekey
     * leaves `updatedAt` untouched, so without this every backup would keep its old-key copy of
     * every file forever; after it the next run replaces them all. Called while the index is still
     * open, right before the index's own rekey; harmless to repeat on a resume.
     */
    suspend fun clearAllStamps() {
        val config = read()
        if (config.stamps.isNotEmpty() || config.cloudStamps.isNotEmpty()) {
            write(config.copy(stamps = emptyMap(), cloudStamps = emptyMap()))
        }
    }

    /** Persist [config], replacing whatever was there. False if it would not encode (never
     *  expected); nothing is written then and the previous config stands. */
    suspend fun write(config: BackupConfig, now: Long = System.currentTimeMillis()): Boolean {
        val bytes = BackupConfig.encode(config) ?: return false
        dao.upsert(
            ObjectEntity(
                id = ListIds.BACKUP_ID,
                type = ObjectType.BACKUP,
                name = "backup",
                parentId = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                flags = config.version,
                blob = bytes,
            )
        )
        return true
    }
}
