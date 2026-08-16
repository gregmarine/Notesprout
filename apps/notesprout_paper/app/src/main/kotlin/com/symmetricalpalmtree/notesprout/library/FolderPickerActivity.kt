package com.symmetricalpalmtree.notesprout.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.IndexGuard
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.data.index.IndexRepository
import com.symmetricalpalmtree.notesprout.data.index.ObjectSummary
import com.symmetricalpalmtree.notesprout.data.index.ObjectType
import com.symmetricalpalmtree.notesprout.databinding.ActivityFolderPickerBinding
import kotlinx.coroutines.launch

class FolderPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderPickerBinding
    private val repo by lazy { IndexRepository() }

    private var movingId = ""
    private var movingType = ""
    private var movingName = ""
    private var currentFolderId: String? = null
    private var excludeId = ""

    private var pageIndex = 0
    private var pageCount = 1
    private var folders = emptyList<ObjectSummary>()
    private var columns = 2
    private var cardsPerPage = 1
    private var gridMeasured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        movingId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { finish(); return }
        movingType = intent.getStringExtra(EXTRA_ITEM_TYPE) ?: ObjectType.NOTEBOOK
        movingName = intent.getStringExtra(EXTRA_ITEM_NAME) ?: ""
        currentFolderId = intent.getStringExtra(EXTRA_CURRENT_PARENT)
        excludeId = if (movingType == ObjectType.FOLDER) movingId else ""

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnMoveHere.setOnClickListener { moveHere() }
        binding.btnBack.setOnClickListener { navigateUp() }
        binding.btnFirst.setOnClickListener { goToPage(0) }
        binding.btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        binding.btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        binding.btnLast.setOnClickListener { goToPage(pageCount - 1) }

        listOf(binding.btnBack, binding.btnFirst, binding.btnPrev, binding.btnNext, binding.btnLast)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (!gridMeasured && binding.gridContainer.width > 0 && binding.gridContainer.height > 0) {
                gridMeasured = true
                measureGrid()
                lifecycleScope.launch { refresh() }
            }
        }
    }

    private fun measureGrid() {
        val density = resources.displayMetrics.density
        val w = binding.gridContainer.width
        val h = binding.gridContainer.height
        val widthDp = w / density
        columns = if (widthDp >= 480) 3 else 2
        val cardW = w / columns
        val cardH = (cardW * 1.4f).toInt()
        val rows = (h / cardH).coerceAtLeast(1)
        cardsPerPage = columns * rows
    }

    private suspend fun refresh() {
        renderBreadcrumb()
        val allFolders = repo.folders(currentFolderId)
            .filter { it.id != excludeId }
            .sortedBy { it.name.lowercase() }
        folders = allFolders

        binding.emptyState.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE

        pageCount = if (folders.isEmpty()) 1 else (folders.size - 1) / cardsPerPage + 1
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        renderPager()
        renderGrid()
    }

    private fun renderBreadcrumb() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()
        val inkBlack = ContextCompat.getColor(this, R.color.inkBlack)

        lifecycleScope.launch {
            val ancestry = repo.ancestry(currentFolderId)
            container.removeAllViews()

            val moveLabel = TextView(this@FolderPickerActivity).apply {
                text = getString(R.string.move_title)
                textSize = 16f
                setTextColor(inkBlack)
                val d = resources.displayMetrics.density
                setPadding((4 * d).toInt(), 0, (8 * d).toInt(), 0)
            }
            container.addView(moveLabel)

            val rootCrumb = makeCrumbView(getString(R.string.library_root), inkBlack) { navigateTo(null) }
            container.addView(rootCrumb)

            for (ref in ancestry) {
                if (ref.id == excludeId) continue
                container.addView(makeSeparator(inkBlack))
                val crumb = makeCrumbView(ref.name, inkBlack) { navigateTo(ref.id) }
                container.addView(crumb)
            }
        }

        binding.btnBack.visibility = if (currentFolderId == null) View.GONE else View.VISIBLE
    }

    private fun makeCrumbView(label: String, color: Int, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            setOnClickListener { onClick() }
        }
    }

    private fun makeSeparator(color: Int): TextView =
        TextView(this).apply { text = " / "; textSize = 14f; setTextColor(color) }

    private fun navigateTo(id: String?) {
        currentFolderId = id
        pageIndex = 0
        lifecycleScope.launch { refresh() }
    }

    private fun navigateUp() {
        lifecycleScope.launch {
            if (currentFolderId == null) return@launch
            val ancestry = repo.ancestry(currentFolderId)
            val parent = if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else null
            navigateTo(parent)
        }
    }

    private fun renderGrid() {
        binding.gridContainer.let { c ->
            val grid = c.findViewById<GridLayout>(R.id.pickerGrid) ?: GridLayout(this).apply {
                id = R.id.pickerGrid
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            grid.removeAllViews()
            grid.columnCount = columns

            val start = pageIndex * cardsPerPage
            val end = minOf(start + cardsPerPage, folders.size)
            val containerWidth = binding.gridContainer.width
            val cardW = containerWidth / columns
            val cardH = (cardW * 1.4f).toInt()

            for (i in start until end) {
                val folder = folders[i]
                val view = LayoutInflater.from(this).inflate(R.layout.card_folder, null)
                view.findViewById<TextView>(R.id.folderName).text = folder.name
                val lp = GridLayout.LayoutParams().apply {
                    width = cardW
                    height = cardH
                }
                view.layoutParams = lp
                view.setOnClickListener { navigateTo(folder.id) }
                grid.addView(view)
            }
            if (grid.parent == null) c.addView(grid)
        }
    }

    private fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, pageCount - 1)
        if (clamped == pageIndex) return
        pageIndex = clamped
        renderPager()
        renderGrid()
    }

    private fun renderPager() {
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = "${pageIndex + 1} / $pageCount"
    }

    private fun moveHere() {
        lifecycleScope.launch {
            if (repo.nameTaken(currentFolderId, movingType, movingName, movingId)) {
                val msgRes = if (movingType == ObjectType.NOTEBOOK)
                    R.string.move_collision_notebook else R.string.move_collision_folder
                Toast.makeText(this@FolderPickerActivity, getString(msgRes, movingName), Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (movingType == ObjectType.FOLDER && currentFolderId != null) {
                if (repo.isSelfOrDescendant(currentFolderId, movingId)) {
                    Toast.makeText(this@FolderPickerActivity, "Cannot move a folder into itself", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            repo.move(movingId, currentFolderId)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (currentFolderId != null) navigateUp()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_ITEM_TYPE = "itemType"
        private const val EXTRA_ITEM_NAME = "itemName"
        private const val EXTRA_CURRENT_PARENT = "currentParent"

        fun intent(context: Context, itemId: String, itemType: String, itemName: String, currentParent: String?): Intent =
            Intent(context, FolderPickerActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId)
                putExtra(EXTRA_ITEM_TYPE, itemType)
                putExtra(EXTRA_ITEM_NAME, itemName)
                putExtra(EXTRA_CURRENT_PARENT, currentParent)
            }
    }
}
