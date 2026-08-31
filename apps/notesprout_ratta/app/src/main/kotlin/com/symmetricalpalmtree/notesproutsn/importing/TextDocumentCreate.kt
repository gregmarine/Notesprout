package com.symmetricalpalmtree.notesproutsn.importing

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.DocumentRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMetaStore
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.notebook.TextCover
import com.symmetricalpalmtree.notesproutsn.templates.TemplatePick
import com.symmetricalpalmtree.notesproutsn.templates.TemplatePicks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Create a **new text document notebook** from text that came from outside the app (arc 19 / M8 —
 * today the `.md` / `.txt` import; anything else that arrives as prose later).
 *
 * This is
 * [com.symmetricalpalmtree.notesproutsn.library.NewNotebookActivity.createNotebook]'s
 * eight-step create contract for the **Blank-template** case, and deliberately nothing more: same
 * order, same rules, same `.soil` shape — mint the id, create the file, write the notebook root
 * row, write page 1, write `notebook_meta`, seal, and only then write the index row. **The two must
 * be changed together**, and each names the other in a comment so a reader of either finds the
 * pair. What is added here is one row and one flag:
 *
 *  - a **`document` row parented to the notebook root** ([SoilSchema.TYPE_DOCUMENT]) holding the
 *    imported text — the notebook document, M7's merged-final-draft slot, which is where a text
 *    document keeps everything it has. It is written through [DocumentRepository.save], the
 *    hand-edit path, so `srcUpdatedAt` stays **NULL**: this text was authored elsewhere and was
 *    never drafted from any page, and a watermark would claim it was. Blank text writes **no row
 *    at all** (the repository's blank-means-absent rule) — an empty `.txt` imports as an empty
 *    document, which is exactly what it is.
 *  - `textDocument = true` on both writes — the index row's `NotebookFlags.TEXT_DOCUMENT` bit (the
 *    authority) and the meta's mirror (what travels in the file).
 *
 * The paper is **Blank**: a text document's page exists so the notebook has one — the same page
 * the ✓ *Show pages* door opens onto — and blank paper writes no `template` row at all, which is
 * what blank IS in this format.
 *
 * The cover is rendered **here, immediately** rather than left to the first seal: the notebook may
 * not be opened for weeks, and a card with no cover reads as an empty notebook.
 *
 * The text is the user's content — never logged, lengths only.
 */
object TextDocumentCreate {

    private const val TAG = "TextDocCreate"

    /**
     * Everything above, on IO. Returns the new notebook's id; throws on failure, leaving no index
     * row behind (a half-written `.soil` is left on disk — never delete data on failure — and
     * nothing names it).
     *
     * [passphrase] is the caller's: it lives only in process RAM (`KeySession`), and the caller is
     * the one that can turn its absence into an honest sentence on screen.
     */
    suspend fun create(
        context: Context,
        repo: IndexRepository,
        name: String,
        parentFolderId: String?,
        text: String,
        passphrase: String,
        now: Long = System.currentTimeMillis(),
    ): String = withContext(Dispatchers.IO) {
        val notebookId = UUID.randomUUID().toString()
        val file = soilFile(context, notebookId)

        val metrics = context.resources.displayMetrics
        val pageW = minOf(metrics.widthPixels, metrics.heightPixels)
        val pageH = maxOf(metrics.widthPixels, metrics.heightPixels)

        val db = SoilDatabase.create(context, notebookId, file, passphrase)
        try {
            val dao = db.dao()
            val pageId = UUID.randomUUID().toString()

            dao.upsert(
                SoilObjectEntity(
                    id = notebookId, parentId = SoilSchema.ROOT_PARENT, type = SoilSchema.TYPE_NOTEBOOK,
                    createdAt = now, updatedAt = now, text = name, refId = pageId,
                )
            )

            // Blank paper: no `template` row, and the page's refId is the empty string that means
            // "no template" — the create screen's Blank case, byte for byte.
            dao.upsert(
                SoilObjectEntity(
                    id = pageId, parentId = notebookId, type = SoilSchema.TYPE_PAGE,
                    order = 0, createdAt = now, updatedAt = now,
                    refId = "", width = pageW.toFloat(), height = pageH.toFloat(),
                )
            )

            // The one row the handwritten create does not write. `save` (not `saveDrafted`): the
            // text was authored elsewhere, so the watermark stays NULL.
            DocumentRepository(db.documentDao(), dao).save(notebookId, text, now)

            NotebookMetaStore.write(
                db.raw(),
                NotebookMeta(
                    notebookId = notebookId, name = name, createdAt = now, updatedAt = now,
                    folderPath = repo.ancestry(parentFolderId),
                    appVersionCode = versionCode(context),
                    textDocument = true,
                ),
            )
            db.seal(file)
        } catch (e: Exception) {
            // Seal regardless so the handle is closed; the half-written file is left on disk (never
            // delete data on failure) and no index row ever names it.
            runCatching { db.seal(file) }
            throw e
        }

        repo.createNotebook(
            id = notebookId,
            name = name,
            parentId = parentFolderId,
            templateKind = TemplatePicks.birthKind(TemplatePick.Blank),
            pageCount = 1,
            textDocument = true,
            now = now,
        )
        Slog.d(TAG) { "created text document $notebookId (${text.length} chars)" }

        // Immediately, not at the first seal: see the class note.
        TextCover.render(repo, notebookId, text)
        notebookId
    }

    private fun versionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)
}
