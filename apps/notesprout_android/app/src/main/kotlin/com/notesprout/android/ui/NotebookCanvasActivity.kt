package com.notesprout.android.ui

import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.NoteSproutApp
import com.notesprout.android.R
import com.notesprout.android.data.BaseObject
import com.notesprout.android.plugins.structural.NotebookManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotebookCanvasActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTEBOOK_ID = "notebook_id"
        const val EXTRA_SOIL_PATH = "soil_path"
    }

    private lateinit var notebookManager: NotebookManager
    private lateinit var pageIndicator: TextView
    private lateinit var prevPage: TextView
    private lateinit var nextPage: TextView
    private var pages: List<BaseObject> = emptyList()
    private var currentPageIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notebook_canvas)

        val app = application as NoteSproutApp
        notebookManager = NotebookManager(app.databaseManager, app.pluginEngine)

        pageIndicator = findViewById(R.id.pageIndicator)
        prevPage = findViewById(R.id.prevPage)
        nextPage = findViewById(R.id.nextPage)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val app = application as NoteSproutApp
                app.databaseManager.closeCurrentDatabase()
                finish()
            }
        })

        findViewById<TextView>(R.id.backButton).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        prevPage.setOnClickListener {
            if (currentPageIndex > 0) {
                currentPageIndex--
                updatePageIndicator()
            }
        }

        nextPage.setOnClickListener {
            if (currentPageIndex < pages.size - 1) {
                currentPageIndex++
                updatePageIndicator()
            }
        }

        val soilPath = intent.getStringExtra(EXTRA_SOIL_PATH) ?: ""
        if (soilPath.isNotEmpty()) {
            loadPages(soilPath)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveMode.enter(window)
    }

    private fun loadPages(soilPath: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val notebookObj = notebookManager.openNotebook(soilPath)
            val notebookId = notebookObj?.id
                ?: intent.getStringExtra(EXTRA_NOTEBOOK_ID)
                ?: ""
            val notebookName = notebookObj?.let { notebookManager.getNotebookName(it) }
                ?: soilPath.substringAfterLast("/").removeSuffix(".soil")
            val pageList = if (notebookId.isNotEmpty()) {
                notebookManager.getPages(notebookId)
            } else emptyList()
            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.notebookTitle).text = notebookName
                pages = pageList
                currentPageIndex = 0
                updatePageIndicator()
            }
        }
    }

    private fun updatePageIndicator() {
        if (pages.isEmpty()) {
            pageIndicator.text = "0 / 0"
            prevPage.setTextColor(getColor(R.color.disabled_gray))
            nextPage.setTextColor(getColor(R.color.disabled_gray))
            return
        }
        val total = pages.size
        val current = currentPageIndex + 1
        pageIndicator.text = "$current / $total"
        prevPage.setTextColor(
            if (currentPageIndex == 0) getColor(R.color.disabled_gray)
            else getColor(R.color.white)
        )
        nextPage.setTextColor(
            if (currentPageIndex >= total - 1) getColor(R.color.disabled_gray)
            else getColor(R.color.white)
        )
    }
}
