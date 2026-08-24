package com.symmetricalpalmtree.notesproutsn.data.clip

import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectDao
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex

/**
 * The global clipboard's one row in `notesprout.db`, read and written (arc 7).
 *
 * **Single slot, sticky:** every copy or cut is an upsert over [ListIds.CLIPBOARD_ID], so there is
 * never more than one payload; a paste leaves it loaded (paste the same page into several
 * notebooks), and it survives a force-stop because it lives in the index rather than in memory.
 * There is no Clear in the UI; the row is soft-deleted in exactly one case — [clear], the recovery
 * for a payload that turned out to be unreadable (B3 review).
 *
 * Living in the index is also what makes the clipboard the recovery for a cut whose source notebook
 * has since been closed: the undo stack is per-notebook and dies with the screen, the payload does
 * not.
 */
class ClipStore(private val dao: ObjectDao = SnIndex.dao()) {

    /**
     * Put [env] on the clipboard, replacing whatever was there. Returns the header the in-memory
     * mirror should now hold, or **null when the payload does not fit** ([ClipEnvelope.MAX_BYTES]) —
     * in which case nothing is written and the previous clipboard stands.
     */
    suspend fun write(env: ClipEnvelope): ClipHeader? {
        val bytes = ClipEnvelope.encode(env) ?: return null
        dao.upsert(
            ObjectEntity(
                id = ListIds.CLIPBOARD_ID,
                type = ObjectType.CLIPBOARD,
                name = env.kind,
                parentId = null,
                createdAt = env.copiedAt,
                updatedAt = env.copiedAt,
                deletedAt = null,
                flags = env.version,
                blob = bytes,
                refId = env.sourceNotebookId,
            )
        )
        return ClipHeader(env.kind, env.sourceNotebookId, env.copiedAt, env.version)
    }

    /** What is on the clipboard, without the payload. Null when it is empty. */
    suspend fun readHeader(): ClipHeader? =
        dao.clipHeader(ListIds.CLIPBOARD_ID)?.takeIf { it.kind.isNotEmpty() }

    /**
     * The payload. Null when the clipboard is empty **or** its bytes are unusable — the decode
     * never throws, so a corrupt row reads as an empty clipboard rather than a crash.
     *
     * The **read itself** is guarded too (B3 review): a row written by a build with a laxer cap can
     * be larger than the cursor window SQLCipher reads it back through, which throws rather than
     * returning bytes. Same answer either way — an unusable clipboard — and the caller's recovery
     * ([clear]) then retires the row.
     */
    suspend fun readEnvelope(): ClipEnvelope? =
        ClipEnvelope.decode(runCatching { dao.clipBlob(ListIds.CLIPBOARD_ID) }.getOrNull())

    /**
     * Retire an unusable clipboard row: it stops advertising a Paste that can only fail, and its
     * megabytes leave the index. Not a Clear feature — the only caller is the paste-failed recovery,
     * and the next copy writes the row back whole.
     */
    suspend fun clear(now: Long) = dao.clipClear(ListIds.CLIPBOARD_ID, now)
}
