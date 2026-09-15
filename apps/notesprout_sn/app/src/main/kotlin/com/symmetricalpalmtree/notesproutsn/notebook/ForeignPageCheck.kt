package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * "Is that page still there?", asked of a notebook that is **not** the one open on this screen —
 * the one-shot read-only pre-check every hop into another notebook owes the user before it lands.
 *
 * Lifted out of [LinkFollowFlow] at arc 42 / N3, where a second caller appeared
 * ([BibleNoteFollow]): landing on the wrong page — or on a notebook's remembered page because the
 * target died — is a lie the user cannot see, and two copies of that check would be two chances
 * to drift apart (the `RattaNotebookView` sibling-copy trap, one file at a time).
 *
 * The read goes through [SoilDatabase.readOnce], the single owner of the open → read →
 * always-seal ritual, and answers **false on any failure at all**: the caller then explains
 * rather than guessing. It must only ever be pointed at a genuinely foreign notebook — one file,
 * one connection, family-wide.
 */
object ForeignPageCheck {

    /**
     * Whether [pageId] is a live page of [notebookId]'s `.soil`, still parented to it.
     *
     * [typed] is the passphrase the caller has just collected for a `NOTEBOOK`-scope target (arc
     * 26 / U4): the read carries it rather than resolving, which would answer `NeedsPrompt` until
     * the prompt's raw-key warm finishes (~9 s on the Nomad) and turn a live page into a dead one.
     */
    suspend fun alive(context: Context, notebookId: String, pageId: String, typed: String?): Boolean {
        val ctx = context.applicationContext
        val check: suspend (SoilDao) -> Boolean = { dao ->
            val row = dao.byId(pageId)
            row != null &&
                row.deletedAt == null &&
                row.type == SoilSchema.TYPE_PAGE &&
                row.parentId == notebookId
        }
        val answer =
            if (typed == null) SoilDatabase.readOnce(ctx, notebookId, check)
            else SoilDatabase.readOnce(ctx, notebookId, KeyResolver.Resolved.Passphrases(typed), check)
        return answer ?: false
    }
}
