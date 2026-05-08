package com.notesprout.notesprout.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.io.File
import com.notesprout.notesprout.ui.EinkStyle

class TemplatePickerDialog(
    private val context: Context,
    private val currentTemplatePath: String?,
    private val onApply: (templatePath: String?) -> Unit
) {

    companion object {
        private const val TEMPLATES_DIR = "/storage/emulated/0/Documents/NoteSprout/Templates"
        private const val COLOR_SELECTED = "#2196F3"
    }

    fun show() {
        val pngFiles = File(TEMPLATES_DIR)
            .listFiles { f -> f.isFile && f.name.lowercase().endsWith(".png") }
            ?.sortedBy { it.name }
            ?: emptyList()

        var selectedPath: String? = currentTemplatePath

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(TextView(context).apply {
            text = "Choose Template"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })

        // Each entry holds the ImageView and its associated template path (null = "None")
        val itemImageViews = mutableListOf<Pair<ImageView, String?>>()

        val density = context.resources.displayMetrics.density
        fun borderDrawable(selected: Boolean, isNone: Boolean): GradientDrawable =
            GradientDrawable().apply {
                setColor(if (isNone) Color.WHITE else Color.TRANSPARENT)
                if (selected) setStroke(dp(3), Color.parseColor(COLOR_SELECTED))
                else setStroke((1.5f * density).toInt(), Color.BLACK)
            }

        fun refreshBorders() {
            itemImageViews.forEach { (iv, path) ->
                iv.background = borderDrawable(path == selectedPath, isNone = path == null)
            }
        }

        if (pngFiles.isEmpty()) {
            root.addView(TextView(context).apply {
                text = "No templates found. Add PNG files to /Documents/NoteSprout/Templates/ to get started."
                textSize = 14f
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        } else {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }

            fun addItem(label: String, path: String?, isFirst: Boolean) {
                val imgPad = dp(4)
                val imageView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(120), dp(160))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(imgPad, imgPad, imgPad, imgPad)
                    if (path != null) {
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = 4
                            inJustDecodeBounds = false
                        }
                        BitmapFactory.decodeFile(path, opts)?.let { setImageBitmap(it) }
                    }
                }

                itemImageViews.add(Pair(imageView, path))

                val labelView = TextView(context).apply {
                    text = label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT)
                }

                val itemLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(imageView)
                    addView(labelView)
                    setOnClickListener {
                        selectedPath = path
                        refreshBorders()
                    }
                }

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (!isFirst) leftMargin = dp(8)
                }
                row.addView(itemLayout, params)
            }

            addItem("None", null, isFirst = true)
            pngFiles.forEach { file ->
                addItem(file.nameWithoutExtension, file.absolutePath, isFirst = false)
            }

            // Apply initial selection borders after all items are registered
            refreshBorders()

            root.addView(HorizontalScrollView(context).apply { addView(row) })
        }

        fun flatBtn(label: String): Button = Button(context).apply {
            text = label
            setTextColor(Color.BLACK)
            background = EinkStyle.flatBackground(cornerRadiusDp = 4f, borderWidthDp = 1f)
            elevation = 0f
            stateListAnimator = null
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val cancelBtn = flatBtn("Cancel")
        val applyBtn = flatBtn("Apply")

        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(8), dp(8), dp(8), dp(8))
            val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            }
            addView(cancelBtn)
            addView(applyBtn, gap)
        })

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        cancelBtn.setOnClickListener { dialog.dismiss() }
        applyBtn.setOnClickListener {
            onApply(selectedPath)
            dialog.dismiss()
        }

        dialog.show()
        EinkStyle.applyToDialog(dialog)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()
}
