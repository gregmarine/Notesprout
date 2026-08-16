package com.symmetricalpalmtree.notesprout.notebook

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesprout.core.IndexGuard
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.data.prefs.BrowseState
import com.symmetricalpalmtree.notesprout.databinding.ActivityNotebookBinding

class NotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotebookBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        val name = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: ""
        val notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: run { finish(); return }

        binding.notebookName.text = name
        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)

        BrowseState(this).lastOpenNotebookId = notebookId
    }

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        BrowseState(this).lastOpenNotebookId = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        fun intent(context: Context, notebookId: String, name: String): Intent =
            Intent(context, NotebookActivity::class.java).apply {
                putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                putExtra(EXTRA_NOTEBOOK_NAME, name)
            }
    }
}
