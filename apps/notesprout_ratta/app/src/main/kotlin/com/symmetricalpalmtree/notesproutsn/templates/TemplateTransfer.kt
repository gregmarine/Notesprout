package com.symmetricalpalmtree.notesproutsn.templates

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.data.template.PagePaper
import com.symmetricalpalmtree.notesproutsn.data.template.PaperSource
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateFit
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateImport
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import com.symmetricalpalmtree.notesproutsn.library.NameRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * **Pictures in and pictures out** (arc 13 / G4) — the template library's two doors to the rest of
 * the device, and the only place in Notesprout SN that has ever opened a system file picker.
 *
 * It lives beside [TemplateBrowser] rather than inside it because the browser is about *what is in
 * the library*, and this is about what crosses its edge: two `ActivityResultLauncher`s, a decoder,
 * an encoder, and the three sheets that go with them. The browser calls three entry points
 * ([startImport], [export], [chooseFit]) and takes a `reload` when a row changed.
 *
 * **Import**, in order, and the order is the point:
 *
 *  1. `ACTION_OPEN_DOCUMENT`, limited to PNG / JPEG / WEBP.
 *  2. Decode bounds-first, sample down, resize exactly to the page's long edge, re-encode
 *     **lossless WEBP** — all on IO, all before a word is asked of the user.
 *  3. Over [TemplateImport.MAX_BLOB_BYTES] → a **problem dialog** and nothing else happens. Asking
 *     for a name and a fit mode and *then* refusing the file would waste the only two decisions the
 *     user makes.
 *  4. Fit and name, then the row.
 *
 * **Export** is `ACTION_CREATE_DOCUMENT` and a PNG rendered at this device's page size — the same
 * [PagePaper] render the page itself gets, so what lands in the file is what the paper looks like.
 * Only imported static templates export: the built-ins do not long-press at all (G1's rule, which
 * G4's phase gate collided with and lost).
 *
 * *The one piece of state that outlives a call* is [pendingExportId] — the row a
 * `CREATE_DOCUMENT` is being run for. It is saved and restored ([saveState] / [restoreState])
 * because DocumentsUI is a whole other process on a memory-tight e-ink device, and a host killed
 * behind it would otherwise come back holding a `Uri` with nothing to write into it.
 */
class TemplateTransfer(
    private val activity: AppCompatActivity,
    private val repo: IndexRepository,
    private val currentFolder: () -> String?,
    private val onChanged: () -> Unit,
) {

    /** This device's portrait page in pixels — what an import is scaled to and an export drawn at. */
    private val pageWidthPx: Int
    private val pageHeightPx: Int

    /** The template a `CREATE_DOCUMENT` is in flight for. See the class note on why it is saved. */
    private var pendingExportId: String? = null

    /** Where an import will land — read when the picker opens, for the reason in [startImport]. */
    private var landingFolder: String? = null

    private val importLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) ingest(uri)
    }

    private val exportLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val id = pendingExportId
        pendingExportId = null
        if (result.resultCode == Activity.RESULT_OK && uri != null && id != null) write(id, uri)
    }

    init {
        val metrics = activity.resources.displayMetrics
        pageWidthPx = minOf(metrics.widthPixels, metrics.heightPixels)
        pageHeightPx = maxOf(metrics.widthPixels, metrics.heightPixels)
    }

    // ── Host state ───────────────────────────────────────────────────────────

    fun saveState(outState: Bundle) = outState.putString(KEY_PENDING_EXPORT, pendingExportId)

    fun restoreState(savedInstanceState: Bundle?) {
        pendingExportId = savedInstanceState?.getString(KEY_PENDING_EXPORT)
    }

    // ── Import ───────────────────────────────────────────────────────────────

    /**
     * Open the picker. The landing folder is read at the *tap*, not at the result: the browser is
     * still on screen underneath and the user could have walked elsewhere — except they cannot,
     * because the picker is up, which is exactly why reading it now is safe and reading it later
     * would not be.
     */
    fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, TemplateImport.MIME_TYPES)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            landingFolder = currentFolder()
            importLauncher.launch(intent)
        } catch (e: Exception) {
            Log.w(TAG, "no document picker: $e")
            Dialogs.problem(activity, R.string.template_import_no_picker_title, R.string.template_import_no_picker_body)
        }
    }

    private fun ingest(uri: Uri) {
        activity.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { decodeAndEncode(uri) }
            when (loaded) {
                is Loaded.Failed -> Dialogs.problem(
                    activity, R.string.template_import_failed_title, R.string.template_import_failed_body,
                )
                is Loaded.TooBig -> Dialogs.problem(
                    activity, R.string.template_import_too_big_title,
                    activity.getString(
                        R.string.template_import_too_big_body,
                        TemplateImport.megabytes(loaded.bytes),
                        TemplateImport.megabytes(TemplateImport.MAX_BLOB_BYTES),
                    ),
                )
                is Loaded.Ok -> chooseImportFit(loaded)
            }
        }
    }

    private sealed class Loaded {
        /** [bytes] are the stored pixels; [suggestedName] comes from the file, already scrubbed. */
        class Ok(val bytes: ByteArray, val suggestedName: String) : Loaded()
        class TooBig(val bytes: Int) : Loaded()
        object Failed : Loaded()
    }

    /**
     * The whole IO half of an import. Two opens of the same `Uri` rather than one read into a byte
     * array: the bounds pass needs a stream and so does the decode, and slurping the file first
     * would put an unbounded picture in RAM to save re-opening a content provider.
     */
    private fun decodeAndEncode(uri: Uri): Loaded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            // The stream's absence is the failure here, NOT the decode's return: a bounds pass
            // returns null by contract (it only fills outWidth/outHeight), so folding the two
            // together with an elvis refuses every image ever picked. The bounds themselves are
            // checked on the next line, which is where an unreadable file actually shows up.
            val stream = open(uri) ?: return Loaded.Failed
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            Log.w(TAG, "import bounds failed: $e")
            return Loaded.Failed
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "import: no usable bounds")
            return Loaded.Failed
        }

        val maxEdge = pageHeightPx
        val opts = BitmapFactory.Options().apply {
            inSampleSize = TemplateImport.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val decoded = try {
            open(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            Log.w(TAG, "import decode failed: $e")
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "import decode ${bounds.outWidth}x${bounds.outHeight} ran out of memory")
            null
        } ?: return Loaded.Failed

        var source = decoded
        try {
            TemplateImport.scaledSize(decoded.width, decoded.height, maxEdge)?.let { (w, h) ->
                source = Bitmap.createScaledBitmap(decoded, w, h, true)
            }
            // The same encoder the page's own template blob goes through, API guard included.
            val bytes = BuiltInTemplates.toWebp(source)
            Slog.d(TAG) {
                "import ${bounds.outWidth}x${bounds.outHeight} → ${source.width}x${source.height}" +
                    " sample=${opts.inSampleSize} ${bytes.size} bytes"
            }
            if (bytes.isEmpty()) return Loaded.Failed
            if (TemplateImport.overCap(bytes.size)) return Loaded.TooBig(bytes.size)
            return Loaded.Ok(bytes, TemplateImport.nameFrom(displayName(uri), fallbackName()))
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "import resize/encode ran out of memory")
            return Loaded.Failed
        } finally {
            if (source !== decoded) source.recycle()
            decoded.recycle()
        }
    }

    private fun open(uri: Uri): InputStream? = activity.contentResolver.openInputStream(uri)

    /** The file's own name, for the name field's suggestion. Absent is fine — [TemplateImport]
     *  falls back — so a provider that answers nothing costs a default, not an error. */
    private fun displayName(uri: Uri): String? = try {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) {
        Log.w(TAG, "display name unavailable: $e")
        null
    }

    private fun fallbackName(): String = activity.getString(R.string.template_import_default_name)

    /** Fit first, then the name — the fit is what the picture *is* as paper, and the name is what
     *  it is called. Cancelling either abandons the import; nothing has been written yet. */
    private fun chooseImportFit(loaded: Loaded.Ok) = fitSheet(R.string.template_fit_title, null) { fit ->
        askName(loaded, fit)
    }

    private fun askName(loaded: Loaded.Ok, fit: Int) {
        val parentId = landingFolder
        var accepting = false
        NameDialog.show(
            activity,
            titleRes = R.string.template_import_name_title,
            confirmRes = R.string.template_import_confirm,
            initial = loaded.suggestedName,
            hintRes = R.string.template_import_name_hint,
        ) { name, dismiss ->
            if (accepting) return@show
            NameRules.validate(name)?.let { problem ->
                Dialogs.problem(activity, R.string.name_problem_title, NameDialog.problemMessage(activity, problem))
                return@show
            }
            if (TemplateLibrary.isReservedName(parentId, name)) {
                Dialogs.problem(
                    activity, R.string.name_problem_title,
                    activity.getString(R.string.template_name_reserved, TemplateLibrary.RESERVED_ROOT_NAME),
                )
                return@show
            }
            accepting = true
            activity.lifecycleScope.launch {
                try {
                    if (repo.nameTaken(parentId, ObjectType.TEMPLATE, name)) {
                        Dialogs.problem(
                            activity, R.string.name_problem_title,
                            activity.getString(R.string.rename_duplicate_template, name),
                        )
                        return@launch
                    }
                    repo.createTemplate(
                        name = name,
                        parentId = parentId,
                        kind = TemplateLibrary.KIND_IMAGE,
                        fit = fit,
                        image = loaded.bytes,
                    )
                    dismiss()
                    onChanged()
                    Toast.makeText(activity, R.string.template_imported, Toast.LENGTH_SHORT).show()
                } finally {
                    accepting = false
                }
            }
        }
    }

    // ── Fit… ─────────────────────────────────────────────────────────────────

    /**
     * Re-fit a template already in the library. The stored picture never changes — only how it is
     * laid onto a page — which is the whole reason the arc stores the original rather than a
     * page-sized render.
     */
    fun chooseFit(s: ObjectSummary) = fitSheet(R.string.template_fit_title, TemplateFit.sanitize(s.flags)) { fit ->
        activity.lifecycleScope.launch {
            if (!repo.setTemplateFit(s.id, fit)) {
                Dialogs.problem(activity, R.string.template_duplicate_gone_title, R.string.template_duplicate_gone_body)
            }
            onChanged()
        }
    }

    /** The three modes, in [TemplateFit.MODES] order, with the one in force ticked. */
    private fun fitSheet(titleRes: Int, current: Int?, onPick: (Int) -> Unit) {
        val sheet = ActionSheetDialog(activity).title(activity.getString(titleRes))
        for (mode in TemplateFit.MODES) {
            sheet.addAction(
                if (mode == current) R.drawable.ic_check else null,
                activity.getString(fitLabel(mode)),
            ) { onPick(mode) }
        }
        sheet.show()
    }

    private fun fitLabel(mode: Int): Int = when (mode) {
        TemplateFit.STRETCH -> R.string.template_fit_stretch
        TemplateFit.FILL -> R.string.template_fit_fill
        else -> R.string.template_fit_fit
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /** Ask where to put a PNG of [s], at this device's page size. */
    fun export(s: ObjectSummary) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(EXPORT_MIME)
            .putExtra(Intent.EXTRA_TITLE, "${s.name}.png")
        try {
            pendingExportId = s.id
            exportLauncher.launch(intent)
        } catch (e: Exception) {
            pendingExportId = null
            Log.w(TAG, "no document creator: $e")
            Dialogs.problem(activity, R.string.template_import_no_picker_title, R.string.template_import_no_picker_body)
        }
    }

    private fun write(id: String, uri: Uri) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { renderAndWrite(id, uri) }
            if (ok) {
                Toast.makeText(activity, R.string.template_exported, Toast.LENGTH_SHORT).show()
            } else {
                Dialogs.problem(activity, R.string.template_export_failed_title, R.string.template_export_failed_body)
            }
        }
    }

    /**
     * Render the row's paper at the page's size and write it out as PNG. The same [PagePaper] call
     * the page itself makes, with the row's own fit — an export that arranged the picture
     * differently from the paper it stands for would be a different picture.
     */
    private suspend fun renderAndWrite(id: String, uri: Uri): Boolean {
        val row = try {
            repo.templateRow(id)
        } catch (e: Exception) {
            Log.w(TAG, "export row read failed: $e")
            null
        } ?: return false
        val bytes = row.blob ?: return false
        val paper = PaperSource.Image(bytes, TemplateFit.sanitize(row.flags))
        val page = PagePaper.render(paper, pageWidthPx, pageHeightPx, dpi()) ?: return false
        return try {
            activity.contentResolver.openOutputStream(uri)?.use { out ->
                page.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                true
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "export write failed: $e")
            false
        } finally {
            page.recycle()
        }
    }

    private fun dpi(): Float = activity.resources.displayMetrics.densityDpi.toFloat()

    private companion object {
        const val TAG = "TemplateTransfer"
        const val KEY_PENDING_EXPORT = "templateTransfer.pendingExport"
        const val EXPORT_MIME = "image/png"
    }
}
