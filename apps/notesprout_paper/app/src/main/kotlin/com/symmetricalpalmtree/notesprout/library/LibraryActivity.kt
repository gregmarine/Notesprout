package com.symmetricalpalmtree.notesprout.library

import android.os.Bundle
import android.view.View
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
import com.symmetricalpalmtree.notesprout.data.prefs.BrowseState
import com.symmetricalpalmtree.notesprout.databinding.ActivityLibraryBinding
import kotlinx.coroutines.launch

/**
 * The library — folders and notebooks as a paginated card grid (never scrolls).
 *
 * Phase 1: the screen shell. Breadcrumb top bar (root only), constant bottom bar with every
 * button present, working pagination controls (hidden when there is one page), an empty-state
 * label, browse-state read/write. Creation, cards, sort and modes arrive in Phases 2 and 5.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var browseState: BrowseState
    private val repo by lazy { IndexRepository() }

    /** Current folder (null = root). */
    private var folderId: String? = null

    /** Pagination over the current listing (item count → pages). */
    private var pageIndex = 0
    private var pageCount = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        browseState = BrowseState(this)
        folderId = browseState.folderId

        wireBars()
        DebugMenu.install(this, binding.breadcrumbBar)

        lifecycleScope.launch {
            repo.ensurePinnedListExists()
            // A remembered folder that no longer exists → root.
            if (folderId != null && repo.alive(folderId!!) == null) {
                folderId = null
                browseState.folderId = null
            }
            refresh()
        }
    }

    private fun wireBars() {
        val later = View.OnClickListener {
            Toast.makeText(this, R.string.library_later, Toast.LENGTH_SHORT).show()
        }
        listOf(binding.btnPinned, binding.btnRecents, binding.btnSort, binding.btnNewFolder, binding.btnNewNotebook, binding.btnUp)
            .forEach { it.setOnClickListener(later) }
        binding.btnFirst.setOnClickListener { goToPage(0) }
        binding.btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        binding.btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        binding.btnLast.setOnClickListener { goToPage(pageCount - 1) }
        // Every icon button: long-press hint = its content description.
        listOf(binding.btnPinned, binding.btnRecents, binding.btnSort, binding.btnNewFolder, binding.btnNewNotebook,
               binding.btnUp, binding.btnFirst, binding.btnPrev, binding.btnNext, binding.btnLast)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    /** Re-read the current folder and redraw chrome + grid. */
    private suspend fun refresh() {
        renderBreadcrumb()
        val folders = repo.folders(folderId)
        val notebooks = repo.notebooks(folderId)
        val total = folders.size + notebooks.size
        binding.emptyState.visibility = if (total == 0) View.VISIBLE else View.GONE
        // Cards-per-page is measured from the real grid in Phase 2; the pager math is in place now.
        val perPage = CARDS_PER_PAGE_PLACEHOLDER.coerceAtLeast(1)
        pageCount = if (total == 0) 1 else (total - 1) / perPage + 1
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        renderPager()
    }

    private fun renderBreadcrumb() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = getString(R.string.library_root)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.inkBlack))
        })
        binding.btnUp.visibility = if (folderId == null) View.GONE else View.VISIBLE
    }

    private fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, pageCount - 1)
        if (clamped == pageIndex) return
        pageIndex = clamped
        renderPager()
    }

    /** Pager stays INVISIBLE (not GONE) with one page so the bar's centre never shifts. */
    private fun renderPager() {
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = "${pageIndex + 1} / $pageCount"
    }

    override fun onPause() {
        super.onPause()
        if (::browseState.isInitialized) browseState.folderId = folderId
    }

    private companion object {
        /** Until Phase 2 measures the grid, treat every listing as one page. */
        const val CARDS_PER_PAGE_PLACEHOLDER = Int.MAX_VALUE
    }
}
