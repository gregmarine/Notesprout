package com.notesprout.android

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.databinding.ActivityNotebookPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight folder-browse activity for picking a destination notebook.
 *
 * Used by cross-notebook page copy/move (S3). Shows a flat list of folders and notebooks;
 * tapping a folder drills in, tapping a notebook returns RESULT_OK with its id + name.
 * The source notebook (identified by [EXTRA_EXCLUDE_NOTEBOOK_ID]) is excluded from the list.
 */
class NotebookPickerActivity : AppCompatActivity() {

    companion object {
        /** Notebook id to exclude from the pick list (the source notebook). */
        const val EXTRA_EXCLUDE_NOTEBOOK_ID = "exclude_notebook_id"
        const val RESULT_NOTEBOOK_ID   = "notebook_id"
        const val RESULT_NOTEBOOK_NAME = "notebook_name"
    }

    private sealed class BrowseItem {
        abstract val entity: ObjectEntity
        data class Folder(override val entity: ObjectEntity) : BrowseItem()
        data class Notebook(override val entity: ObjectEntity) : BrowseItem()
    }

    private lateinit var binding: ActivityNotebookPickerBinding

    private val indexRepo: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }
    private val inkBlackColor by lazy { ContextCompat.getColor(this, R.color.inkBlack) }
    private val inkLightColor by lazy { ContextCompat.getColor(this, R.color.inkLight) }

    private var excludeId: String = ""
    private val directoryStack = ArrayDeque<ObjectEntity?>().apply { add(null) }
    private var browseItems: List<BrowseItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityNotebookPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        excludeId = intent.getStringExtra(EXTRA_EXCLUDE_NOTEBOOK_ID) ?: ""

        binding.btnBack.setOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })

        loadBrowseAsync()
    }

    private fun handleBack() {
        if (directoryStack.size > 1) {
            directoryStack.removeLast()
            loadBrowseAsync()
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun loadBrowseAsync() {
        val parentId = directoryStack.last()?.id
        val currentFolder = directoryStack.last()

        // Update title to show current folder context.
        binding.tvTitle.text = currentFolder?.name ?: "Pick a Notebook"

        lifecycleScope.launch {
            browseItems = withContext(Dispatchers.IO) {
                val folders   = indexRepo.getFolders(parentId).sortedBy { it.name.lowercase() }
                val notebooks = indexRepo.getNotebooks(parentId)
                    .filter { it.id != excludeId }
                    .sortedBy { it.name.lowercase() }
                folders.map { BrowseItem.Folder(it) } +
                    notebooks.map { BrowseItem.Notebook(it) }
            }
            renderList()
        }
    }

    private fun renderList() {
        binding.listContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val dividerH = (1 * density).toInt()
        val padH = (16 * density).toInt()
        val padV = (14 * density).toInt()
        val iconSize = (24 * density).toInt()
        val iconGap = (12 * density).toInt()

        if (browseItems.isEmpty()) {
            val tv = AppCompatTextView(this).apply {
                text = "No notebooks here."
                setTextColor(inkLightColor)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(padH, (32 * density).toInt(), padH, padV)
            }
            binding.listContainer.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            return
        }

        browseItems.forEachIndexed { index, item ->
            if (index > 0) {
                val divider = View(this).apply {
                    setBackgroundColor(inkBlackColor)
                }
                binding.listContainer.addView(divider, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dividerH
                ))
            }

            val row = buildRow(item, padH, padV, iconSize, iconGap)
            binding.listContainer.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun buildRow(item: BrowseItem, padH: Int, padV: Int, iconSize: Int, iconGap: Int): LinearLayout {
        val isFolder = item is BrowseItem.Folder
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
            background = ColorDrawable(Color.TRANSPARENT)

            setOnClickListener {
                if (isFolder) {
                    directoryStack.add(item.entity)
                    loadBrowseAsync()
                } else {
                    android.content.Intent().also {
                        it.putExtra(RESULT_NOTEBOOK_ID,   item.entity.id)
                        it.putExtra(RESULT_NOTEBOOK_NAME, item.entity.name)
                        setResult(RESULT_OK, it)
                    }
                    finish()
                }
            }

            val iconRes = if (isFolder) R.drawable.ic_folder else R.drawable.ic_notebook
            val icon = AppCompatImageView(context).apply {
                setImageResource(iconRes)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setColorFilter(inkBlackColor)
            }
            addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).also {
                it.marginEnd = iconGap
            })

            val tv = AppCompatTextView(context).apply {
                text = item.entity.name
                textSize = 16f
                setTextColor(inkBlackColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (isFolder) {
                val chevron = AppCompatTextView(context).apply {
                    text = "›"
                    textSize = 20f
                    setTextColor(inkLightColor)
                    gravity = Gravity.CENTER_VERTICAL
                }
                addView(chevron, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
        }
    }
}
