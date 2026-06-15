package com.notesprout.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import org.json.JSONObject
import java.io.File

/**
 * Template selection dialog for NotebookActivity.
 *
 * Two tabs:
 *   All      — scans getExternalFilesDir("Templates") for .png files + "Blank" entry.
 *   Notebook — lists type="template" rows already stored in the open notebook.
 *
 * Tapping an item immediately confirms the selection and dismisses the dialog.
 * The caller receives the chosen template id (empty string = Blank) and its decoded Bitmap.
 *
 * Design rules (from CLAUDE.md):
 *   - No Material Components
 *   - No elevation, no shadows, no ripple, no animations
 *   - All colours from @color/; no hardcoded values
 *   - AlertDialog styled with setElevation(0f) + shape_bordered after show()
 *   - Active tab: 1dp inkBlack bottom underline only (no filled indicator)
 */
class TemplateDialog(
    private val activity: AppCompatActivity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val db: SoilDatabase,
    private val pageId: String,
    private val notebookId: String,
    /** Called on the main thread when a template is confirmed.
     *  [templateId] is "" for Blank; [bitmap] is null for Blank. */
    private val onConfirm: (templateId: String, bitmap: Bitmap?) -> Unit,
    /**
     * Called when the user taps "Import Template". Caller should launch a file picker and, when
     * a file is imported, invoke [onDone] on the main thread so the list can refresh.
     * Null = import button is hidden.
     */
    private val onRequestImport: ((onDone: () -> Unit) -> Unit)? = null,
) {

    // ── Internal item models ─────────────────────────────────────────────────

    private data class FileItem(val file: File, val thumbnail: Bitmap?)
    private data class NotebookItem(val obj: NotebookObject, val thumbnail: Bitmap?, val label: String)

    // ── Entry point ──────────────────────────────────────────────────────────

    fun show() {
        lifecycleScope.launch {
            val (fileItems, notebookItems, currentTemplateId) = withContext(Dispatchers.IO) {
                loadData()
            }
            buildAndShow(fileItems, notebookItems, currentTemplateId)
        }
    }

    // ── Data loading (IO thread) ─────────────────────────────────────────────

    private fun templatesDir(): File = activity.getExternalFilesDir("Templates")!!

    private fun scanFileItems(): List<FileItem> {
        val dir = templatesDir().also { it.mkdirs() }
        val pngFiles = dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".png") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        return pngFiles.map { file -> FileItem(file, loadScaledBitmap(file, THUMB_PX)) }
    }

    private suspend fun loadData(): Triple<List<FileItem>, List<NotebookItem>, String> {
        // Current page template id
        val page = db.notebookDao().getObjectById(pageId)
        val currentTemplateId = page?.let { parseTemplateId(it.data) } ?: ""

        val fileItems = scanFileItems()

        // Notebook templates
        val nbObjects = db.notebookDao().getTemplatesSorted()
        val notebookItems = nbObjects.mapIndexed { index, obj ->
            val thumb = decodeBase64Thumb(obj.data, THUMB_PX)
            val name = try {
                JSONObject(obj.data).optString("name", "").takeIf { it.isNotEmpty() }
                    ?: "Template ${index + 1}"
            } catch (e: Exception) {
                "Template ${index + 1}"
            }
            NotebookItem(obj, thumb, name)
        }

        return Triple(fileItems, notebookItems, currentTemplateId)
    }

    // ── Dialog construction (main thread) ────────────────────────────────────

    private fun buildAndShow(
        fileItems: List<FileItem>,
        notebookItems: List<NotebookItem>,
        currentTemplateId: String,
    ) {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        val hasNotebookTemplates = notebookItems.isNotEmpty()
        // 4 columns on large-screen devices (NA5C: 1860px); 2 on smaller (P2P, GC7: ≤1264px).
        val numColumns = if (ctx.resources.displayMetrics.widthPixels >= 1500) 4 else 2

        // Mutable so it can be refreshed after a template import or deletion.
        val currentFileItems = fileItems.toMutableList()

        // Default tab: "Notebook" if it has content; otherwise "All"
        var activeTab = if (hasNotebookTemplates) TAB_NOTEBOOK else TAB_ALL

        // ── Custom title: "Template" label + Import button ───────────────────
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
        val importTitleBtn = AppCompatTextView(ctx).apply {
            text = "Import…"
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
            val padH2 = (10 * density).toInt()
            val padV2 = (6 * density).toInt()
            setPadding(padH2, padV2, padH2, padV2)
            setBackgroundResource(R.drawable.shape_bordered)
            visibility = if (onRequestImport != null) View.VISIBLE else View.GONE
        }
        titleView.addView(titleText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ))
        titleView.addView(importTitleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        // ── Root container ───────────────────────────────────────────────────
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── Tab row ──────────────────────────────────────────────────────────
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val tabAll = buildTabView("All", isEnabled = true)
        val tabNotebook = buildTabView("Notebook", isEnabled = hasNotebookTemplates)

        val tabLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tabRow.addView(tabAll, tabLp)
        tabRow.addView(tabNotebook, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 1dp divider below tabs
        val divider = View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.inkBlack))
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

        root.addView(tabRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt(),
        ))
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        // ── Build and show the AlertDialog ────────────────────────────────────
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(titleView)
            .setView(root)
            .create()

        // ── Tab indicator logic ───────────────────────────────────────────────
        fun refreshTabIndicators() {
            tabAll.background = if (activeTab == TAB_ALL) bottomBorderDrawable() else null
            tabNotebook.background = if (activeTab == TAB_NOTEBOOK) bottomBorderDrawable() else null
        }

        // ── Item click handler ────────────────────────────────────────────────
        fun onItemClicked(templateId: String, bitmap: Bitmap?) {
            onConfirm(templateId, bitmap)
            dialog.dismiss()
        }

        // ── Populate grid for a tab ───────────────────────────────────────────
        data class Cell(
            val label: String,
            val thumbnail: Bitmap?,
            val isSelected: Boolean,
            val onDelete: (() -> Unit)? = null,
            val onClick: () -> Unit,
        )

        fun populateTab(tab: Int) {
            gridContainer.removeAllViews()

            val cells: List<Cell> = when (tab) {
                TAB_ALL -> buildList {
                    add(Cell("Blank", null, currentTemplateId.isEmpty()) { onItemClicked("", null) })
                    for (item in currentFileItems) {
                        add(Cell(
                            label = item.file.nameWithoutExtension,
                            thumbnail = item.thumbnail,
                            isSelected = false,
                            onDelete = {
                                AlertDialog.Builder(ctx)
                                    .setTitle("Delete template?")
                                    .setMessage("Delete \"${item.file.nameWithoutExtension}\" from library? This cannot be undone.")
                                    .setPositiveButton("Delete") { _, _ ->
                                        item.file.delete()
                                        lifecycleScope.launch {
                                            val newItems = withContext(Dispatchers.IO) { scanFileItems() }
                                            currentFileItems.clear()
                                            currentFileItems.addAll(newItems)
                                            populateTab(TAB_ALL)
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .create()
                                    .also { d ->
                                        d.show()
                                        d.window?.setElevation(0f)
                                        d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
                                    }
                            },
                            onClick = {
                                lifecycleScope.launch {
                                    val (newId, fullBitmap) = withContext(Dispatchers.IO) {
                                        insertTemplateFromFile(item.file)
                                    }
                                    onItemClicked(newId, fullBitmap)
                                }
                            },
                        ))
                    }
                }
                TAB_NOTEBOOK -> buildList {
                    add(Cell("Blank", null, currentTemplateId.isEmpty()) { onItemClicked("", null) })
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
                else -> emptyList()
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
                            buildGridCell(c.label, c.thumbnail, c.isSelected, thumbHeightPx, density, c.onDelete) { c.onClick() },
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

        // Wire import button in the title bar.
        importTitleBtn.setOnClickListener {
            onRequestImport?.invoke {
                lifecycleScope.launch {
                    val newItems = withContext(Dispatchers.IO) { scanFileItems() }
                    currentFileItems.clear()
                    currentFileItems.addAll(newItems)
                    if (activeTab == TAB_ALL) populateTab(TAB_ALL)
                }
            }
        }

        tabAll.setOnClickListener {
            if (activeTab != TAB_ALL) {
                activeTab = TAB_ALL
                refreshTabIndicators()
                populateTab(TAB_ALL)
            }
        }
        tabNotebook.setOnClickListener {
            if (hasNotebookTemplates && activeTab != TAB_NOTEBOOK) {
                activeTab = TAB_NOTEBOOK
                refreshTabIndicators()
                populateTab(TAB_NOTEBOOK)
            }
        }

        // Initial render
        refreshTabIndicators()
        populateTab(activeTab)

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

    // ── Helper: insert template from a PNG file ───────────────────────────────

    /**
     * Read [file], encode to base64, insert a new type="template" row in the notebook.
     * Returns the new template UUID and the decoded full-resolution Bitmap.
     * Must be called on Dispatchers.IO.
     */
    private suspend fun insertTemplateFromFile(file: File): Pair<String, Bitmap?> {
        val bytes = file.readBytes()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val width = bitmap?.width ?: 0
        val height = bitmap?.height ?: 0
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val newId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dataJson = JSONObject().apply {
            put("width", width)
            put("height", height)
            put("name", file.nameWithoutExtension)
            put("image", base64)
        }.toString()

        val fullScreenBounds = """{"x":0.0,"y":0.0,"width":${width.toFloat()},"height":${height.toFloat()}}"""

        db.notebookDao().insertObject(
            com.notesprout.android.data.NotebookObject(
                id          = newId,
                type        = "template",
                parentId    = notebookId,
                boundingBox = fullScreenBounds,
                sortOrder   = 0,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = dataJson,
            ),
        )
        return Pair(newId, bitmap)
    }

    // ── Helper: decode full-res bitmap from stored template row ───────────────

    private fun decodeFullBitmap(data: String): Bitmap? {
        return try {
            val dataObj = JSONObject(data)
            val b64 = dataObj.getString("image")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    // ── Helper: build a single grid cell (thumbnail above, label below) ──────
    //
    // When [onDelete] is non-null, a small × button is overlaid at the top-right
    // corner of the cell so the user can remove the template from the library.

    private fun buildGridCell(
        label: String,
        thumbnail: Bitmap?,
        isSelected: Boolean,
        thumbHeightPx: Int,
        density: Float,
        onDelete: (() -> Unit)? = null,
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

        if (onDelete == null) return cell

        // Wrap in a FrameLayout so the trash button can be overlaid at top-right.
        val deleteBtnSize = (32 * density).toInt()
        val deletePad = (6 * density).toInt()
        val deleteBtn = AppCompatImageView(ctx).apply {
            setImageResource(R.drawable.ic_trash)
            setPadding(deletePad, deletePad, deletePad, deletePad)
            setOnClickListener { onDelete() }
        }
        val deleteLp = FrameLayout.LayoutParams(deleteBtnSize, deleteBtnSize, Gravity.TOP or Gravity.END).also {
            it.topMargin = deletePad
            it.marginEnd = deletePad
        }

        return FrameLayout(ctx).apply {
            addView(cell, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(deleteBtn, deleteLp)
        }
    }

    // ── Helper: build a tab TextView ─────────────────────────────────────────

    private fun buildTabView(text: String, isEnabled: Boolean): TextView {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        return AppCompatTextView(ctx).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            this.isEnabled = isEnabled
            setTextColor(
                if (isEnabled) ContextCompat.getColor(ctx, R.color.inkBlack)
                else ContextCompat.getColor(ctx, R.color.inkLight),
            )
        }
    }

    // ── Helper: 1dp bottom-border-only Drawable for active tab ───────────────

    private fun bottomBorderDrawable(): Drawable = object : Drawable() {
        private val paint = Paint().apply {
            color = Color.BLACK
            strokeWidth = activity.resources.displayMetrics.density
            style = Paint.Style.STROKE
            isAntiAlias = false
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawLine(
                b.left.toFloat(), b.bottom.toFloat() - paint.strokeWidth / 2f,
                b.right.toFloat(), b.bottom.toFloat() - paint.strokeWidth / 2f,
                paint,
            )
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    private fun loadScaledBitmap(file: File, maxSize: Int): Bitmap? {
        return try {
            val bytes = file.readBytes()
            Slog.d(TAG) { "loadScaledBitmap: read ${bytes.size} bytes from ${file.name}" }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            Slog.d(TAG) { "loadScaledBitmap: bounds ${opts.outWidth}x${opts.outHeight}" }
            opts.inSampleSize = computeInSampleSize(opts.outWidth, opts.outHeight, maxSize)
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            Slog.d(TAG) { "loadScaledBitmap: decoded bitmap=${bmp?.width}x${bmp?.height} (null=${bmp == null})" }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "loadScaledBitmap failed for ${file.name}", e)
            null
        }
    }

    private fun decodeBase64Thumb(data: String, maxSize: Int): Bitmap? {
        return try {
            val dataObj = JSONObject(data)
            val b64 = dataObj.getString("image")
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
        private const val TAB_ALL = 0
        private const val TAB_NOTEBOOK = 1
        // Grid thumbnail height in dp. Each cell takes half the dialog width; height is fixed so
        // grid rows are uniform. 200dp gives a tall-enough preview for portrait templates.
        private const val THUMB_HEIGHT_DP = 200
        // Target inSampleSize=2 for e-ink templates (1860×2480 → 930×1240 intermediate).
        private const val THUMB_PX = 1300

        fun parseTemplateId(data: String): String =
            try { JSONObject(data).optString("template", "") } catch (e: Exception) { "" }
    }
}
