package com.notesprout.android.export

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.R
import com.notesprout.android.TemplateBrowserActivity
import com.notesprout.android.core.ImageCodec
import com.notesprout.android.core.Slog
import com.notesprout.android.data.PageRef
import com.notesprout.android.data.index.IndexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hands finished export files to their destination: the SAF save picker, the platform share sheet,
 * or the template library.
 *
 * This is the deduplicated form of plumbing that used to exist byte-for-byte in NotebookActivity,
 * MainActivity, and PageIndexActivity — five `CreateDocument` launchers, an `OpenDocumentTree`
 * folder picker, `writePendingExportTo`, `launchTextSave`, and the PNG-to-template import.
 *
 * **Must be constructed during the host activity's field initialization or `onCreate`** — the
 * `registerForActivityResult` calls below throw `IllegalStateException` once the activity reaches
 * STARTED, so a `by lazy` here would only fail at the end of the first export. [indexRepo] is a
 * provider rather than a value so eager construction doesn't force the index open.
 *
 * [onFinished] fires with a user-facing message
 * once a destination has accepted the files (or reports a failure); the host uses it to toast and
 * close. Cancelling a picker calls [onCancelled] instead, leaving the screen up so the user can
 * change their mind without re-running the export.
 */
class ExportDelivery(
    private val activity: AppCompatActivity,
    private val indexRepo: () -> IndexRepository,
    private val onFinished: (message: String) -> Unit,
    private val onCancelled: () -> Unit,
) {

    /** Files awaiting a destination, set immediately before a picker is launched. */
    private var pending: List<File> = emptyList()

    /** Page labels parallel to [pending], used to name imported templates. */
    private var pendingLabels: List<String> = emptyList()

    // ── SAF launchers ────────────────────────────────────────────────────────
    // One per mime type: CreateDocument fixes its mime at registration, and a mismatch makes the
    // picker append a second extension (a text/plain launcher on "notes.md" yields "notes.md.txt").

    private val savePdf = createDocument("application/pdf")
    private val savePng = createDocument("image/png")
    private val saveMarkdown = createDocument("text/markdown")
    private val saveText = createDocument("text/plain")
    private val saveSoil = createDocument("application/x-notesprout-soil")

    private fun createDocument(mime: String) =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
            if (uri == null) { onCancelled(); return@registerForActivityResult }
            writeSingle(uri)
        }

    /** Folder picker for a multi-file (per-page PNG) save. */
    private val saveIntoFolder =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            val files = pending
            if (treeUri == null || files.isEmpty()) { onCancelled(); return@registerForActivityResult }
            activity.lifecycleScope.launch {
                val written = withContext(Dispatchers.IO) { writeFilesToTree(treeUri, files) }
                onFinished(
                    if (written == files.size) "Exported ${files.size} images"
                    else "Exported $written of ${files.size} images"
                )
            }
        }

    /** Library folder picker for "Save as template". */
    private val pickTemplateFolder =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != AppCompatActivity.RESULT_OK) { onCancelled(); return@registerForActivityResult }
            val folderId = result.data
                ?.getStringExtra(TemplateBrowserActivity.RESULT_TEMPLATE_FOLDER_ID)
                ?.takeIf { it.isNotEmpty() }   // "" encodes root/null
            importAsTemplates(folderId)
        }

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * Route [files] to [spec]'s destination. [pages] is parallel to [files] for the PNG cases and
     * supplies the template names; it is ignored for single-document formats.
     */
    fun deliver(spec: ExportSpec, files: List<File>, pages: List<PageRef>) {
        if (files.isEmpty()) { onFinished("Nothing to export"); return }
        pending = files
        pendingLabels = pages.map { ExportNaming.sanitizeTemplate(ExportNaming.pageLabel(it)) }

        when (spec.destination) {
            ExportDestination.SAVE ->
                if (files.size > 1) saveIntoFolder.launch(null)
                else launcherFor(spec.format).launch(files.first().name)

            ExportDestination.SHARE -> share(spec, files)

            ExportDestination.TEMPLATE -> pickTemplateFolder.launch(
                Intent(activity, TemplateBrowserActivity::class.java)
                    .putExtra(TemplateBrowserActivity.EXTRA_MODE, TemplateBrowserActivity.MODE_PICK_FOLDER)
                    .putExtra(TemplateBrowserActivity.EXTRA_TITLE, "Save templates to…")
            )
        }
    }

    private fun launcherFor(format: ExportFormat) = when (format) {
        ExportFormat.PDF -> savePdf
        ExportFormat.PNG -> savePng
        ExportFormat.MARKDOWN -> saveMarkdown
        ExportFormat.TEXT -> saveText
        ExportFormat.SOIL -> saveSoil
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private fun writeSingle(uri: Uri) {
        val file = pending.firstOrNull() ?: return
        activity.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    null
                } catch (e: Exception) {
                    e.message ?: "Unknown error"
                }
            }
            onFinished(if (error == null) "Saved ${file.name}" else "Save failed: $error")
        }
    }

    /** Write [files] into the folder at [treeUri]. Returns the count written. Call on IO. */
    private fun writeFilesToTree(treeUri: Uri, files: List<File>): Int {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        var written = 0
        for (file in files) {
            try {
                val docUri = DocumentsContract.createDocument(
                    activity.contentResolver, treeDocUri, "image/png", file.name
                ) ?: continue
                activity.contentResolver.openOutputStream(docUri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                written++
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to write ${file.name} to tree", e)
            }
        }
        Slog.d(TAG) { "writeFilesToTree: wrote $written of ${files.size}" }
        return written
    }

    // ── Share ────────────────────────────────────────────────────────────────

    private fun share(spec: ExportSpec, files: List<File>) {
        val uris = ArrayList(files.map { uriFor(it) })
        val intent = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = spec.format.mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newRawUri("", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = spec.format.mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData.newRawUri("", uris.first())
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, "Share ${spec.format.label}"))
        onFinished("")
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)

    // ── Template import ──────────────────────────────────────────────────────

    /**
     * Import each pending PNG into the template library under [parentId] (null = root), naming each
     * after its page and de-duplicating against the templates already in that folder.
     */
    private fun importAsTemplates(parentId: String?) {
        val files = pending
        val labels = pendingLabels
        if (files.isEmpty()) { onFinished("Nothing to export"); return }

        activity.lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                // Fetch the folder's existing names once so de-dup is consistent across the batch.
                val existingNames = runCatching { indexRepo().getTemplates(parentId) }
                    .getOrElse { emptyList() }
                    .map { it.name }
                    .toMutableList()

                var count = 0
                for ((idx, file) in files.withIndex()) {
                    val raw = labels.getOrNull(idx) ?: ExportNaming.sanitizeTemplate(file.nameWithoutExtension)
                    val name = ExportNaming.uniqueTemplate(raw, existingNames)
                    existingNames.add(name)   // reserve so later iterations don't collide

                    try {
                        val bytes = file.readBytes()
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                            android.util.Log.w(TAG, "Template import: invalid bounds for ${file.name}")
                            continue
                        }
                        val base64 = ImageCodec.transcodeBytesToWebpBase64(bytes)
                            ?: Base64.encodeToString(bytes, Base64.NO_WRAP)
                        indexRepo().createTemplate(name, parentId, opts.outWidth, opts.outHeight, base64)
                        Slog.d(TAG) { "Imported template '$name' (${opts.outWidth}x${opts.outHeight})" }
                        count++
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Template import failed for ${file.name}", e)
                    }
                }
                count
            }
            onFinished(
                if (imported == files.size) "Saved $imported template${if (imported == 1) "" else "s"}"
                else "Saved $imported of ${files.size} templates"
            )
        }
    }

    private companion object { const val TAG = "ExportDelivery" }
}
