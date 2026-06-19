package com.notesprout.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.notesprout.android.core.Slog
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.SoilDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.notesprout.android.data.PageData
import com.notesprout.android.data.TemplateData

/**
 * Template selection dialog for NotebookActivity.
 *
 * Single-view picker (no tabs) with:
 *   - **Blank** entry (clears the page template), and
 *   - **"In this notebook"** grid — `type="template"` rows stored in the open notebook's `.soil`.
 *   - **"Browse Templates…"** button in the title bar (launches TemplateBrowserActivity in PICK mode).
 *
 * Tapping an item immediately confirms the selection and dismisses the dialog.
 * The caller receives the chosen template id (empty string = Blank) and its decoded Bitmap.
 *
 * Design rules (from CLAUDE.md):
 *   - No Material Components
 *   - No elevation, no shadows, no ripple, no animations
 *   - All colours from @color/; no hardcoded values
 *   - AlertDialog styled with setElevation(0f) + shape_bordered after show()
 */
class TemplateDialog(
    private val activity: AppCompatActivity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val db: SoilDatabase,
    private val pageId: String,
    /** Called on the main thread when a template is confirmed.
     *  [templateId] is "" for Blank; [bitmap] is null for Blank. */
    private val onConfirm: (templateId: String, bitmap: Bitmap?) -> Unit,
    /**
     * Called when the user taps "Browse Templates…". Caller should launch
     * TemplateBrowserActivity in MODE_PICK.
     */
    private val onBrowseLibrary: () -> Unit,
) {

    // ── Internal item model ─────────────────────────────────────────────────

    private data class NotebookItem(val obj: NotebookObject, val thumbnail: Bitmap?, val label: String)

    // ── Entry point ──────────────────────────────────────────────────────────

    fun show() {
        lifecycleScope.launch {
            val (notebookItems, currentTemplateId) = withContext(Dispatchers.IO) {
                loadData()
            }
            buildAndShow(notebookItems, currentTemplateId)
        }
    }

    // ── Data loading (IO thread) ─────────────────────────────────────────────

    private suspend fun loadData(): Pair<List<NotebookItem>, String> {
        // Current page template id
        val page = db.notebookDao().getObjectById(pageId)
        val currentTemplateId = page?.let { parseTemplateId(it.data) } ?: ""

        // Notebook templates (type="template" rows in the .soil)
        val nbObjects = db.notebookDao().getTemplatesSorted()
        val notebookItems = nbObjects.mapIndexed { index, obj ->
            val thumb = decodeBase64Thumb(obj.data, THUMB_PX)
            val name = TemplateData.fromJson(obj.data)?.name?.takeIf { it.isNotEmpty() }
                ?: "Template ${index + 1}"
            NotebookItem(obj, thumb, name)
        }

        return Pair(notebookItems, currentTemplateId)
    }

    // ── Dialog construction (main thread) ────────────────────────────────────

    private fun buildAndShow(
        notebookItems: List<NotebookItem>,
        currentTemplateId: String,
    ) {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        // 4 columns on large-screen devices (NA5C: 1860px); 2 on smaller (P2P, GC7: ≤1264px).
        val numColumns = if (ctx.resources.displayMetrics.widthPixels >= 1500) 4 else 2

        // ── Custom title: "Template" label + Browse Templates… button ───────
        val titleView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (16 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }
        val titleText = AppCompatTextView(ctx).apply {
            text = "Template"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
        }
        val browseTitleBtn = AppCompatTextView(ctx).apply {
            text = "Browse Templates…"
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
            val padH2 = (10 * density).toInt()
            val padV2 = (6 * density).toInt()
            setPadding(padH2, padV2, padH2, padV2)
            setBackgroundResource(R.drawable.shape_bordered)
        }
        titleView.addView(titleText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ))
        titleView.addView(browseTitleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        // ── Root container ───────────────────────────────────────────────────
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── Scrollable grid ───────────────────────────────────────────────────
        val gapPx = (8 * density).toInt()
        val thumbHeightPx = (THUMB_HEIGHT_DP * density).toInt()

        val scrollView = ScrollView(ctx)
        val gridContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gapPx, gapPx, gapPx, gapPx)
        }
        scrollView.addView(
            gridContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        // ── Build and show the AlertDialog ────────────────────────────────────
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(titleView)
            .setView(root)
            .create()

        // ── Item click handler ────────────────────────────────────────────────
        fun onItemClicked(templateId: String, bitmap: Bitmap?) {
            onConfirm(templateId, bitmap)
            dialog.dismiss()
        }

        // ── Build cell list and populate grid ─────────────────────────────────
        data class Cell(
            val label: String,
            val thumbnail: Bitmap?,
            val isSelected: Boolean,
            val onClick: () -> Unit,
        )

        fun populateGrid() {
            gridContainer.removeAllViews()

            val cells: List<Cell> = buildList {
                // Blank is always first.
                add(Cell("Blank", null, currentTemplateId.isEmpty()) { onItemClicked("", null) })
                // Templates already in this notebook.
                for (item in notebookItems) {
                    add(Cell(item.label, item.thumbnail, item.obj.id == currentTemplateId) {
                        lifecycleScope.launch {
                            val fullBitmap = withContext(Dispatchers.IO) {
                                decodeFullBitmap(item.obj.data)
                            }
                            onItemClicked(item.obj.id, fullBitmap)
                        }
                    })
                }
            }

            // Lay cells out in rows of numColumns (2 or 4 depending on screen width).
            var i = 0
            while (i < cells.size) {
                val rowLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { if (i > 0) it.topMargin = gapPx }

                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

                for (col in 0 until numColumns) {
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .also { if (col > 0) it.marginStart = gapPx }
                    val c = cells.getOrNull(i + col)
                    if (c != null) {
                        row.addView(
                            buildGridCell(c.label, c.thumbnail, c.isSelected, thumbHeightPx, density) { c.onClick() },
                            lp,
                        )
                    } else {
                        // Empty spacer keeps the last partial row balanced.
                        row.addView(View(ctx), lp)
                    }
                }

                gridContainer.addView(row, rowLp)
                i += numColumns
            }
        }

        // Wire "Browse Templates…" button in the title bar.
        browseTitleBtn.setOnClickListener {
            dialog.dismiss()
            onBrowseLibrary()
        }

        // Initial render
        populateGrid()

        dialog.show()
        // Flat Notesprout styling — applied after show() because window only exists then.
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        // Constrain width to 90% of screen and height to 70% so the list is scrollable.
        val dm = ctx.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.9f).toInt(),
            (dm.heightPixels * 0.7f).toInt(),
        )
    }

    // ── Helper: decode full-res bitmap from stored template row ───────────────

    private fun decodeFullBitmap(data: String): Bitmap? {
        return try {
            val b64 = TemplateData.fromJson(data)?.image?.takeIf { it.isNotEmpty() } ?: return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    // ── Helper: build a single grid cell (thumbnail above, label below) ──────

    private fun buildGridCell(
        label: String,
        thumbnail: Bitmap?,
        isSelected: Boolean,
        thumbHeightPx: Int,
        density: Float,
        onClick: () -> Unit,
    ): View {
        val ctx = activity
        val pad = (6 * density).toInt()

        // Inner cell: vertical layout, click target, selection indicator.
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            if (isSelected) setBackgroundResource(R.drawable.shape_bordered)
            setOnClickListener { onClick() }
        }

        // Thumbnail frame — always bordered.
        val thumbFrame = FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.shape_bordered)
            val borderPx = (density + 0.5f).toInt()
            setPadding(borderPx, borderPx, borderPx, borderPx)
        }
        if (thumbnail != null) {
            val iv = AppCompatImageView(ctx).apply {
                setImageBitmap(thumbnail)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            thumbFrame.addView(iv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        cell.addView(thumbFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, thumbHeightPx,
        ))

        // Label below thumbnail.
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

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    private fun decodeBase64Thumb(data: String, maxSize: Int): Bitmap? {
        return try {
            val b64 = TemplateData.fromJson(data)?.image?.takeIf { it.isNotEmpty() } ?: return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            Slog.d(TAG) { "decodeBase64Thumb: decoded ${bytes.size} bytes from base64" }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            Slog.d(TAG) { "decodeBase64Thumb: bounds ${opts.outWidth}x${opts.outHeight}" }
            opts.inSampleSize = computeInSampleSize(opts.outWidth, opts.outHeight, maxSize)
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            Slog.d(TAG) { "decodeBase64Thumb: decoded bitmap=${bmp?.width}x${bmp?.height} (null=${bmp == null})" }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "decodeBase64Thumb failed", e)
            null
        }
    }

    private fun computeInSampleSize(w: Int, h: Int, maxSize: Int): Int {
        var sample = 1
        while (maxOf(w, h) / sample > maxSize) sample *= 2
        // Cap at 4 — beyond this, thin template lines (≥4px at full res) become
        // sub-pixel and disappear entirely due to nearest-neighbor decimation.
        return sample.coerceIn(1, 4)
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "Notesprout"
        // Grid thumbnail height in dp. Each cell takes half the dialog width; height is fixed so
        // grid rows are uniform. 200dp gives a tall-enough preview for portrait templates.
        private const val THUMB_HEIGHT_DP = 200
        // Target inSampleSize=2 for e-ink templates (1860×2480 → 930×1240 intermediate).
        private const val THUMB_PX = 1300

        fun parseTemplateId(data: String): String = PageData.fromJson(data).template
    }
}
