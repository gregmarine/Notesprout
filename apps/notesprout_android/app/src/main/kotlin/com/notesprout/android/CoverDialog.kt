package com.notesprout.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.notesprout.android.data.BoundingBox
import com.notesprout.android.data.CoverObject
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.SoilDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Cover image selection dialog.
 *
 * Presents two tappable cards:
 *   - "Last Opened Page" — uses the page snapshot as the cover
 *   - "Select Image" — picks an image file from storage
 *
 * Apply commits the pending selection; Cancel dismisses with no change.
 *
 * Design rules: no elevation, shape_bordered window, no Material Components,
 * no hardcoded colours. IO work on Dispatchers.IO. kotlinx.serialization only.
 */
class CoverDialog(
    private val activity: AppCompatActivity,
    private val lifecycleScope: LifecycleCoroutineScope,
    /** Absolute path to the .soil notebook file. A Room DB is opened internally. */
    private val soilFilePath: String,
    /**
     * Snapshot bitmap of the last-opened page, captured by the caller.
     * Used for the "Last Opened Page" card preview.
     */
    private val lastOpenedSnapshot: Bitmap?,
    /**
     * Called by the dialog when it needs to open the system image picker.
     * The caller must register an ActivityResultLauncher and invoke it here;
     * when a URI is selected, the caller calls [onImagePicked].
     */
    private val onRequestImagePick: (onResult: (Uri) -> Unit) -> Unit,
    /** Called on the main thread after a successful Apply. */
    private val onCoverChanged: () -> Unit,
) {

    // ── Pending selection sealed type ────────────────────────────────────────

    private sealed class PendingSelection {
        object None : PendingSelection()
        object LastOpenedPage : PendingSelection()
        data class SelectedImage(val base64: String) : PendingSelection()
    }

    // ── State ────────────────────────────────────────────────────────────────

    private var pendingSelection: PendingSelection = PendingSelection.None
    private var notebookId: String = ""

    // ── Live card references (set during buildAndShow) ────────────────────────

    private var lastPageCardView: View? = null
    private var selectImageCardView: View? = null
    private var selectImagePreview: AppCompatImageView? = null
    private var applyButton: AppCompatButton? = null

    // ── Initial data loaded on IO ────────────────────────────────────────────

    private data class InitialData(
        val notebookId: String,
        val existingCoverBitmap: Bitmap?,
    )

    // ── Entry point ──────────────────────────────────────────────────────────

    fun show() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { loadInitialData() }
            notebookId = data.notebookId
            buildAndShow(data)
        }
    }

    // ── Data loading (IO thread) ─────────────────────────────────────────────

    private suspend fun loadInitialData(): InitialData {
        var db: SoilDatabase? = null
        return try {
            db = openDb()
            val dao = db.notebookDao()

            val notebookObj = dao.getNotebookObject()
                ?: return InitialData("", null)
            val metadata = NotebookMetadata.fromJson(notebookObj.id, notebookObj.data)
            val nbId = metadata.id

            val coverBitmap: Bitmap? = if (metadata.cover.isNotEmpty()) {
                val coverObj = dao.getCoverForNotebook(nbId)
                coverObj?.let { decodeCoverBitmap(it.data) }
            } else {
                null
            }

            InitialData(nbId, coverBitmap)
        } catch (_: Exception) {
            InitialData("", null)
        } finally {
            db?.close()
        }
    }

    private fun decodeCoverBitmap(dataJson: String): Bitmap? {
        return try {
            val coverObj = coverCodec.decodeFromString<CoverObject>(dataJson)
            if (coverObj.image.isEmpty()) return null
            val bytes = Base64.decode(coverObj.image, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    // ── Dialog construction (main thread) ────────────────────────────────────

    private fun buildAndShow(data: InitialData) {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        val dm = ctx.resources.displayMetrics

        val padPx = (16 * density).toInt()
        val gapPx = (12 * density).toInt()

        // Root: vertical layout — cards row + button row
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx, padPx, padPx)
        }

        // ── Two cards side by side ─────────────────────────────────────────
        val cardRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Card height: tall enough to show page previews — use 40% of screen height.
        val cardHeightPx = (dm.heightPixels * 0.40f).toInt()

        val lastPageCard = buildCard(
            label = "Last Opened Page",
            bitmap = lastOpenedSnapshot,
            density = density,
            heightPx = cardHeightPx,
        )
        val selectImageCard = buildCard(
            label = "Select Image",
            bitmap = data.existingCoverBitmap,
            density = density,
            heightPx = cardHeightPx,
        )

        lastPageCardView = lastPageCard
        selectImageCardView = selectImageCard

        // Store the image preview inside the select card so we can update it.
        selectImagePreview = selectImageCard.findViewWithTag("previewImage")

        val cardLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        cardRow.addView(lastPageCard, cardLp)
        cardRow.addView(selectImageCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = gapPx
        })

        root.addView(cardRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        // ── Button row (Apply / Cancel) ────────────────────────────────────
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelBtn = AppCompatButton(ctx).apply {
            text = "Cancel"
            setBackgroundResource(R.drawable.shape_bordered)
            setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
            textSize = 14f
            isAllCaps = false
            stateListAnimator = null
        }
        val applyBtn = AppCompatButton(ctx).apply {
            text = "Apply"
            setBackgroundResource(R.drawable.shape_bordered)
            setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
            textSize = 14f
            isAllCaps = false
            stateListAnimator = null
        }
        applyButton = applyBtn

        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        btnRow.addView(applyBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.marginStart = gapPx })

        root.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.topMargin = gapPx })

        // ── Build dialog ───────────────────────────────────────────────────
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Set Cover")
            .setView(root)
            .create()

        // ── Card tap handlers ──────────────────────────────────────────────
        lastPageCard.setOnClickListener {
            pendingSelection = PendingSelection.LastOpenedPage
            refreshCardSelection()
        }

        selectImageCard.setOnClickListener {
            // Always open picker — even if a selection is already pending.
            onRequestImagePick { uri ->
                processPickedImage(ctx, uri)
            }
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }

        applyBtn.setOnClickListener {
            val sel = pendingSelection
            if (sel == PendingSelection.None) {
                dialog.dismiss()
                return@setOnClickListener
            }
            applyBtn.isEnabled = false
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { applySelection(sel) }
                onCoverChanged()
                dialog.dismiss()
            }
        }

        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        dialog.window?.setLayout(
            (dm.widthPixels * 0.85f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    // ── Card builder ─────────────────────────────────────────────────────────

    private fun buildCard(
        label: String,
        bitmap: Bitmap?,
        density: Float,
        heightPx: Int,
    ): LinearLayout {
        val ctx = activity
        val padPx = (6 * density).toInt()
        val borderPx = (density + 0.5f).toInt()

        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx, padPx, padPx)
            setBackgroundResource(R.drawable.bg_toolbar_button)
        }

        // Thumbnail frame — always bordered; 1dp padding insets the image so it
        // doesn't render over the border stroke at the edges.
        val thumbFrame = FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.shape_bordered)
            setPadding(borderPx, borderPx, borderPx, borderPx)
        }

        val imageView = AppCompatImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            tag = "previewImage"
            if (bitmap != null) setImageBitmap(bitmap)
            else setImageResource(R.drawable.ic_polaroid)
        }
        thumbFrame.addView(imageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        cell.addView(thumbFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            heightPx,
        ))

        val tv = AppCompatTextView(ctx).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
            val padV = (4 * density).toInt()
            setPadding(0, padV, 0, padV)
        }
        cell.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        return cell
    }

    // ── Image pick result ─────────────────────────────────────────────────────

    /** Called from the Activity's ActivityResultLauncher callback with the chosen URI. */
    fun onImagePicked(uri: Uri) {
        processPickedImage(activity, uri)
    }

    private fun processPickedImage(ctx: AppCompatActivity, uri: Uri) {
        lifecycleScope.launch {
            val base64 = withContext(Dispatchers.IO) { encodeImageFromUri(ctx, uri) }
            if (base64 != null) {
                pendingSelection = PendingSelection.SelectedImage(base64)
                // Update the Select Image card preview.
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    selectImagePreview?.setImageBitmap(bmp)
                }
                refreshCardSelection()
            }
        }
    }

    private fun encodeImageFromUri(ctx: AppCompatActivity, uri: Uri): String? {
        return try {
            val inputStream = ctx.contentResolver.openInputStream(uri) ?: return null
            val rawBytes = inputStream.use { it.readBytes() }

            // Decode original to get dimensions.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
            val srcW = opts.outWidth
            val srcH = opts.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            // Target: image height = screen height. Scale down only — never up.
            val targetH = ctx.resources.displayMetrics.heightPixels
            val scale = if (srcH > targetH) targetH.toFloat() / srcH else 1f
            val dstW = (srcW * scale).toInt().coerceAtLeast(1)
            val dstH = (srcH * scale).toInt().coerceAtLeast(1)

            // Compute inSampleSize for efficient decode.
            var inSampleSize = 1
            while (srcH / inSampleSize > dstH * 2) inSampleSize *= 2

            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            val sampled = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return null

            val scaled = if (sampled.width == dstW && sampled.height == dstH) {
                sampled
            } else {
                Bitmap.createScaledBitmap(sampled, dstW, dstH, true).also {
                    if (it !== sampled) sampled.recycle()
                }
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (scaled !== sampled) scaled.recycle()

            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    // ── Card selection visual state ───────────────────────────────────────────

    private fun refreshCardSelection() {
        val isLastPage = pendingSelection is PendingSelection.LastOpenedPage
        val isImage = pendingSelection is PendingSelection.SelectedImage
        lastPageCardView?.isSelected = isLastPage
        selectImageCardView?.isSelected = isImage
    }

    // ── Apply logic (IO thread) ───────────────────────────────────────────────

    private suspend fun applySelection(selection: PendingSelection) {
        val db = openDb()
        try {
            val dao = db.notebookDao()
            val notebookObj = dao.getNotebookObject() ?: return
            val metadata = NotebookMetadata.fromJson(notebookObj.id, notebookObj.data)
            val nbId = metadata.id
            val now = System.currentTimeMillis()

            when (selection) {
                is PendingSelection.LastOpenedPage -> {
                    // Soft-delete any existing cover row.
                    val existing = dao.getCoverForNotebook(nbId)
                    if (existing != null) {
                        dao.softDeleteById(existing.id, now)
                    }
                    // Clear coverId in metadata.
                    val updated = notebookObj.copy(
                        data = metadata.copy(cover = "").toJson(),
                        updatedAt = now,
                    )
                    dao.upsertNotebookObject(updated)
                }

                is PendingSelection.SelectedImage -> {
                    // Soft-delete any existing cover row first.
                    val existing = dao.getCoverForNotebook(nbId)
                    if (existing != null) {
                        dao.softDeleteById(existing.id, now)
                    }
                    // Insert new cover row.
                    val newCoverId = UUID.randomUUID().toString()
                    val coverData = coverCodec.encodeToString(CoverObject(image = selection.base64))
                    dao.insertObject(
                        NotebookObject(
                            id          = newCoverId,
                            parentId    = nbId,
                            boundingBox = BoundingBox().toJson(),
                            sortOrder   = 0,
                            createdAt   = now,
                            updatedAt   = now,
                            deletedAt   = null,
                            type        = "cover",
                            data        = coverData,
                        )
                    )
                    // Update notebook metadata with new coverId.
                    val updated = notebookObj.copy(
                        data = metadata.copy(cover = newCoverId).toJson(),
                        updatedAt = now,
                    )
                    dao.upsertNotebookObject(updated)
                }

                PendingSelection.None -> { /* nothing to do */ }
            }
        } finally {
            db.close()
        }
    }

    // ── Room helper ───────────────────────────────────────────────────────────

    private fun openDb(): SoilDatabase = SoilDatabase.builder(activity, soilFilePath).build()

    // ── Codec ─────────────────────────────────────────────────────────────────

    companion object {
        private val coverCodec = Json { ignoreUnknownKeys = true }
    }
}
