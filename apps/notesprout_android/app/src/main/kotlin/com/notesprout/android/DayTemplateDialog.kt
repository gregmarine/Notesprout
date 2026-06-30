package com.notesprout.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Base64
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
import com.notesprout.android.data.TemplateData
import com.notesprout.android.data.index.CalendarDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Template quick-picker for a day-detail page. Mirrors [TemplateDialog] but reads `type="template"`
 * rows from the `calendar` table (via [CalendarDao]) instead of a notebook `.soil` — these are the
 * templates already copied into the calendar by an earlier "Browse Templates…" pick.
 *
 *   - **Blank** entry (clears the page template).
 *   - Grid of templates already in the calendar table.
 *   - **"Browse Templates…"** in the title bar → caller launches TemplateBrowserActivity (PICK).
 *
 * Tapping an item confirms immediately. The caller receives the chosen template id ("" = Blank) and
 * its decoded full-res Bitmap (null = Blank). Same flat e-ink styling as [TemplateDialog].
 */
class DayTemplateDialog(
    private val activity: AppCompatActivity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val dao: CalendarDao,
    private val currentTemplateId: String,
    private val onConfirm: (templateId: String, bitmap: Bitmap?) -> Unit,
    private val onBrowseLibrary: () -> Unit,
) {

    private data class Item(val id: String, val data: String, val thumbnail: Bitmap?, val label: String)

    fun show() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                dao.getTemplatesSorted().mapIndexed { index, row ->
                    val thumb = decodeBase64Thumb(row.data, THUMB_PX)
                    val name = TemplateData.fromJson(row.data)?.name?.takeIf { it.isNotEmpty() }
                        ?: "Template ${index + 1}"
                    Item(row.id, row.data, thumb, name)
                }
            }
            buildAndShow(items)
        }
    }

    private fun buildAndShow(items: List<Item>) {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        val numColumns = if (ctx.resources.displayMetrics.widthPixels >= 1500) 4 else 2

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
        titleView.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleView.addView(browseTitleBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val gapPx = (8 * density).toInt()
        val thumbHeightPx = (THUMB_HEIGHT_DP * density).toInt()

        val scrollView = ScrollView(ctx)
        val gridContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gapPx, gapPx, gapPx, gapPx)
        }
        scrollView.addView(gridContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(titleView)
            .setView(root)
            .create()

        fun onItemClicked(templateId: String, bitmap: Bitmap?) {
            onConfirm(templateId, bitmap)
            dialog.dismiss()
        }

        data class Cell(val label: String, val thumbnail: Bitmap?, val isSelected: Boolean, val onClick: () -> Unit)

        val cells: List<Cell> = buildList {
            add(Cell("Blank", null, currentTemplateId.isEmpty()) { onItemClicked("", null) })
            for (item in items) {
                add(Cell(item.label, item.thumbnail, item.id == currentTemplateId) {
                    lifecycleScope.launch {
                        val full = withContext(Dispatchers.IO) { decodeFullBitmap(item.data) }
                        onItemClicked(item.id, full)
                    }
                })
            }
        }

        var i = 0
        while (i < cells.size) {
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { if (i > 0) it.topMargin = gapPx }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until numColumns) {
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { if (col > 0) it.marginStart = gapPx }
                val c = cells.getOrNull(i + col)
                if (c != null) {
                    row.addView(buildGridCell(c.label, c.thumbnail, c.isSelected, thumbHeightPx, density) { c.onClick() }, lp)
                } else {
                    row.addView(View(ctx), lp)
                }
            }
            gridContainer.addView(row, rowLp)
            i += numColumns
        }

        browseTitleBtn.setOnClickListener {
            dialog.dismiss()
            onBrowseLibrary()
        }

        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
        val dm = ctx.resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.9f).toInt(), (dm.heightPixels * 0.7f).toInt())
    }

    private fun decodeFullBitmap(data: String): Bitmap? = try {
        val b64 = TemplateData.fromJson(data)?.image?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) { null }

    private fun buildGridCell(
        label: String, thumbnail: Bitmap?, isSelected: Boolean,
        thumbHeightPx: Int, density: Float, onClick: () -> Unit,
    ): View {
        val ctx = activity
        val pad = (6 * density).toInt()
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            if (isSelected) setBackgroundResource(R.drawable.shape_bordered)
            setOnClickListener { onClick() }
        }
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
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        cell.addView(thumbFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, thumbHeightPx))
        val tv = AppCompatTextView(ctx).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
            val padV = (4 * density).toInt()
            setPadding(0, padV, 0, padV)
        }
        cell.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return cell
    }

    private fun decodeBase64Thumb(data: String, maxSize: Int): Bitmap? = try {
        val b64 = TemplateData.fromJson(data)?.image?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.inSampleSize = computeInSampleSize(opts.outWidth, opts.outHeight, maxSize)
        opts.inJustDecodeBounds = false
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: Exception) { null }

    private fun computeInSampleSize(w: Int, h: Int, maxSize: Int): Int {
        var sample = 1
        while (maxOf(w, h) / sample > maxSize) sample *= 2
        return sample.coerceIn(1, 4)
    }

    companion object {
        private const val THUMB_HEIGHT_DP = 200
        private const val THUMB_PX = 1300
    }
}
