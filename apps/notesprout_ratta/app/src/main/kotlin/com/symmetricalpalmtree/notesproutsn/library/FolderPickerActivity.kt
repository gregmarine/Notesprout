package com.symmetricalpalmtree.notesproutsn.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortField
import com.symmetricalpalmtree.notesproutsn.data.prefs.SortOrder
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityFolderPickerBinding
import kotlinx.coroutines.launch

/**
 * "Move to…" — the library's grid again, folders only, with one destination question at the end.
 *
 * Same chrome so the move feels like browsing rather than a mode: breadcrumb + Cancel on top,
 * pagination + **Move here** at the bottom. What is *not* the same:
 *
 *  - A folder being moved is filtered out of every listing, so its own subtree can never be
 *    entered — you cannot navigate into a place that is about to be inside the thing you carry.
 *    [IndexRepository.isSelfOrDescendant] backstops that at the moment of the move.
 *  - Cards do not long-press. There is exactly one verb here.
 *  - A name collision in the destination is a **problem dialog** and the picker stays open, so the
 *    user can walk somewhere else without starting over.
 */
class FolderPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderPickerBinding
    private val repo by lazy { IndexRepository() }

    private var movingId = ""
    private var movingType = ObjectType.NOTEBOOK
    private var movingName = ""
    private var currentFolderId: String? = null

    /** The folder being moved — hidden from every listing. Empty when moving a notebook. */
    private var excludeId = ""

    private var pageIndex = 0
    private var pageCount = 1
    private var items = emptyList<CardItem>()
    private var grid: LibraryGrid? = null
    private var gridMeasured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        movingId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { finish(); return }
        movingType = intent.getStringExtra(EXTRA_ITEM_TYPE) ?: ObjectType.NOTEBOOK
        movingName = intent.getStringExtra(EXTRA_ITEM_NAME).orEmpty()
        currentFolderId = intent.getStringExtra(EXTRA_CURRENT_PARENT)
        excludeId = if (movingType == ObjectType.FOLDER) movingId else ""

        wireBars()

        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (gridMeasured) return@addOnGlobalLayoutListener
            val w = binding.gridContainer.width
            val h = binding.gridContainer.height
            if (w <= 0 || h <= 0) return@addOnGlobalLayoutListener
            gridMeasured = true
            grid = LibraryGrid(binding.gridContainer, ::onCardTap).also { it.measure(this, w, h) }
            lifecycleScope.launch { refresh() }
        }
    }

    private fun wireBars() = with(binding) {
        btnCancel.setOnClickListener { finish() }
        btnMoveHere.setOnClickListener { moveHere() }
        btnUp.setOnClickListener { navigateUp() }
        btnFirst.setOnClickListener { goToPage(0) }
        btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        btnLast.setOnClickListener { goToPage(pageCount - 1) }
        listOf(btnUp, btnFirst, btnPrev, btnNext, btnLast)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    private suspend fun refresh() {
        renderBreadcrumb()
        val folders = repo.folders(currentFolderId).filter { it.id != excludeId }
        items = SortRules.sort(folders, SortField.NAME, SortOrder.ASC).map { CardItem.Folder(it) }

        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        pageCount = GridMath.pageCount(items.size, grid?.cardsPerPage ?: 1)
        pageIndex = GridMath.clampPage(pageIndex, pageCount)
        grid?.bind(items, pageIndex, emptyMap())
        renderPager()
    }

    private fun renderBreadcrumb() {
        val ink = ContextCompat.getColor(this, R.color.inkBlack)
        lifecycleScope.launch {
            val ancestry = repo.ancestry(currentFolderId)
            val container = binding.breadcrumbContainer
            container.removeAllViews()
            container.addView(label(getString(R.string.move_title), ink))
            container.addView(crumb(getString(R.string.library_root), ink) { navigateTo(null) })
            for (ref in ancestry) {
                if (ref.id == excludeId) continue
                container.addView(separator(ink))
                container.addView(crumb(ref.name, ink) { navigateTo(ref.id) })
            }
        }
        binding.btnUp.visibility = if (currentFolderId == null) View.GONE else View.VISIBLE
    }

    private fun label(text: String, color: Int): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            setPadding((4 * d).toInt(), 0, (8 * d).toInt(), 0)
        }
    }

    private fun crumb(text: String, color: Int, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun separator(color: Int): TextView = TextView(this).apply {
        text = " / "
        textSize = 14f
        setTextColor(color)
    }

    private fun onCardTap(item: CardItem) = navigateTo(item.summary.id)

    private fun navigateTo(id: String?) {
        currentFolderId = id
        pageIndex = 0
        lifecycleScope.launch { refresh() }
    }

    private fun navigateUp() {
        val current = currentFolderId ?: return
        lifecycleScope.launch {
            val ancestry = repo.ancestry(current)
            navigateTo(if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else null)
        }
    }

    private fun goToPage(index: Int) {
        val clamped = GridMath.clampPage(index, pageCount)
        if (clamped == pageIndex) return
        pageIndex = clamped
        grid?.bind(items, pageIndex, emptyMap())
        renderPager()
    }

    private fun renderPager() {
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = getString(R.string.page_indicator, pageIndex + 1, pageCount)
    }

    private fun moveHere() {
        lifecycleScope.launch {
            if (repo.nameTaken(currentFolderId, movingType, movingName, movingId)) {
                val msg = if (movingType == ObjectType.NOTEBOOK) R.string.move_collision_notebook
                          else R.string.move_collision_folder
                Dialogs.problem(this@FolderPickerActivity, R.string.move_collision_title, getString(msg, movingName))
                return@launch
            }
            if (movingType == ObjectType.FOLDER && repo.isSelfOrDescendant(currentFolderId, movingId)) {
                Dialogs.problem(this@FolderPickerActivity, R.string.move_collision_title, getString(R.string.move_into_self))
                return@launch
            }
            repo.move(movingId, currentFolderId)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (currentFolderId != null) navigateUp() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_ITEM_TYPE = "itemType"
        private const val EXTRA_ITEM_NAME = "itemName"
        private const val EXTRA_CURRENT_PARENT = "currentParent"

        fun intent(
            context: Context,
            itemId: String,
            itemType: String,
            itemName: String,
            currentParent: String?,
        ): Intent = Intent(context, FolderPickerActivity::class.java)
            .putExtra(EXTRA_ITEM_ID, itemId)
            .putExtra(EXTRA_ITEM_TYPE, itemType)
            .putExtra(EXTRA_ITEM_NAME, itemName)
            .putExtra(EXTRA_CURRENT_PARENT, currentParent)
    }
}
