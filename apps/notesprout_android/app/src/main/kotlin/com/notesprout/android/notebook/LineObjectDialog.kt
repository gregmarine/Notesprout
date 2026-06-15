package com.notesprout.android.notebook

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import com.notesprout.android.R
import com.notesprout.android.data.LineObject
import com.notesprout.android.data.LineOrientation
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.LineStyle
import com.notesprout.android.databinding.DialogInsertLinesBinding
import java.util.UUID

class LineObjectDialog(
    private val context: Context,
    private val pageWidthPx: Int,
    private val pageHeightPx: Int,
    private val density: Float,
    private val onInsert: (List<LineRender>) -> Unit,
) {

    private var selectedStyle = LineStyle.SOLID
    private var selectedOrientation = LineOrientation.HORIZONTAL

    fun show() {
        val binding = DialogInsertLinesBinding.inflate(LayoutInflater.from(context))

        // Style toggle
        updateStyleButtons(binding, selectedStyle)
        binding.btnStyleSolid.setOnClickListener {
            selectedStyle = LineStyle.SOLID
            updateStyleButtons(binding, selectedStyle)
        }
        binding.btnStyleDashed.setOnClickListener {
            selectedStyle = LineStyle.DASHED
            updateStyleButtons(binding, selectedStyle)
        }
        binding.btnStyleDotted.setOnClickListener {
            selectedStyle = LineStyle.DOTTED
            updateStyleButtons(binding, selectedStyle)
        }

        // Orientation toggle
        updateOrientationButtons(binding, selectedOrientation)
        binding.btnOrientationHorizontal.setOnClickListener {
            selectedOrientation = LineOrientation.HORIZONTAL
            updateOrientationButtons(binding, selectedOrientation)
        }
        binding.btnOrientationVertical.setOnClickListener {
            selectedOrientation = LineOrientation.VERTICAL
            updateOrientationButtons(binding, selectedOrientation)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Insert Lines")
            .setView(binding.root)
            .setPositiveButton("Insert", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            imm.hideSoftInputFromWindow(binding.editStrokeWidth.windowToken, 0)
            dialog.dismiss()
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val lines = buildLines(binding) ?: return@setOnClickListener
            imm.hideSoftInputFromWindow(binding.editStrokeWidth.windowToken, 0)
            dialog.dismiss()
            onInsert(lines)
        }
    }

    private fun updateStyleButtons(binding: DialogInsertLinesBinding, style: LineStyle) {
        binding.btnStyleSolid.isSelected  = style == LineStyle.SOLID
        binding.btnStyleDashed.isSelected = style == LineStyle.DASHED
        binding.btnStyleDotted.isSelected = style == LineStyle.DOTTED
    }

    private fun updateOrientationButtons(binding: DialogInsertLinesBinding, orientation: LineOrientation) {
        binding.btnOrientationHorizontal.isSelected = orientation == LineOrientation.HORIZONTAL
        binding.btnOrientationVertical.isSelected   = orientation == LineOrientation.VERTICAL
    }

    private fun buildLines(binding: DialogInsertLinesBinding): List<LineRender>? {
        val strokeWidthDp = binding.editStrokeWidth.text.toString().toFloatOrNull()?.coerceAtLeast(0.5f) ?: 1f
        val count = binding.editLineCount.text.toString().toIntOrNull()?.coerceIn(1, 200) ?: return null
        val marginTopDp     = binding.editMarginTop.text.toString().toFloatOrNull()    ?: SNAP_MARGIN_DP
        val marginBottomDp  = binding.editMarginBottom.text.toString().toFloatOrNull() ?: SNAP_MARGIN_DP
        val marginLeftDp    = binding.editMarginLeft.text.toString().toFloatOrNull()   ?: SNAP_MARGIN_DP
        val marginRightDp   = binding.editMarginRight.text.toString().toFloatOrNull()  ?: SNAP_MARGIN_DP

        val marginTopPx    = marginTopDp    * density
        val marginBottomPx = marginBottomDp * density
        val marginLeftPx   = marginLeftDp   * density
        val marginRightPx  = marginRightDp  * density
        val strokeWidthPx  = strokeWidthDp  * density
        // Inflate perpendicular extent so center-point lasso hit tests work reliably.
        val halfInflatePx  = maxOf(strokeWidthPx / 2f, 4f * density)

        val now = System.currentTimeMillis()
        val lines = mutableListOf<LineRender>()

        when (selectedOrientation) {
            LineOrientation.HORIZONTAL -> {
                val usableHeight = pageHeightPx - marginTopPx - marginBottomPx
                if (usableHeight <= 0f || count <= 0) return null
                val startX = marginLeftPx
                val endX   = pageWidthPx - marginRightPx
                if (endX <= startX) return null
                // Dot spacing = line-to-line spacing so the grid is square (equal gaps in both axes).
                val dotSpacingPx = if (count > 1) usableHeight / (count - 1).toFloat()
                                   else marginTopDp * density

                for (i in 0 until count) {
                    val y = if (count == 1) {
                        marginTopPx + usableHeight / 2f
                    } else {
                        marginTopPx + usableHeight * i / (count - 1).toFloat()
                    }
                    lines.add(
                        LineRender(
                            id = UUID.randomUUID().toString(),
                            boundingBox = android.graphics.RectF(startX, y - halfInflatePx, endX, y + halfInflatePx),
                            startX = startX, startY = y, endX = endX, endY = y,
                            style = selectedStyle,
                            orientation = selectedOrientation,
                            strokeWidthDp = strokeWidthDp,
                            dotSpacingPx = dotSpacingPx,
                        )
                    )
                }
            }
            LineOrientation.VERTICAL -> {
                val usableWidth = pageWidthPx - marginLeftPx - marginRightPx
                if (usableWidth <= 0f || count <= 0) return null
                val startY = marginTopPx
                val endY   = pageHeightPx - marginBottomPx
                if (endY <= startY) return null
                // Dot spacing = line-to-line spacing so the grid is square (equal gaps in both axes).
                val dotSpacingPx = if (count > 1) usableWidth / (count - 1).toFloat()
                                   else marginLeftDp * density

                for (i in 0 until count) {
                    val x = if (count == 1) {
                        marginLeftPx + usableWidth / 2f
                    } else {
                        marginLeftPx + usableWidth * i / (count - 1).toFloat()
                    }
                    lines.add(
                        LineRender(
                            id = UUID.randomUUID().toString(),
                            boundingBox = android.graphics.RectF(x - halfInflatePx, startY, x + halfInflatePx, endY),
                            startX = x, startY = startY, endX = x, endY = endY,
                            style = selectedStyle,
                            orientation = selectedOrientation,
                            strokeWidthDp = strokeWidthDp,
                            dotSpacingPx = dotSpacingPx,
                        )
                    )
                }
            }
        }

        return lines.ifEmpty { null }
    }
}
