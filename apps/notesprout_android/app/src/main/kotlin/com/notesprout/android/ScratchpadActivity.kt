package com.notesprout.android

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.notesprout.android.databinding.ActivityScratchpadBinding

class ScratchpadActivity : AppCompatActivity() {

    companion object {
        /** Nullable — set when launched from a notebook; null when launched from MainActivity. */
        const val EXTRA_FROM_NOTEBOOK_ID        = "from_notebook_id"
        const val EXTRA_FROM_NOTEBOOK_NAME      = "from_notebook_name"
        const val EXTRA_FROM_NOTEBOOK_ENCRYPTED = "from_notebook_encrypted"
    }

    private lateinit var binding: ActivityScratchpadBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScratchpadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fromNotebookId = intent.getStringExtra(EXTRA_FROM_NOTEBOOK_ID)

        // "Send to Notebook" is only relevant when launched from a notebook.
        binding.btnSendToNotebook.visibility =
            if (fromNotebookId != null) View.VISIBLE else View.GONE

        // On large screens, constrain the window to 75% × 75% of the display, centered.
        // Set synchronously before the first layout pass so there is no full-screen flash.
        if (resources.getBoolean(R.bool.is_large_screen)) {
            val dm = resources.displayMetrics
            val lp = binding.scratchpadWindow.layoutParams as FrameLayout.LayoutParams
            lp.width   = (dm.widthPixels  * 0.75f).toInt()
            lp.height  = (dm.heightPixels * 0.75f).toInt()
            lp.gravity = Gravity.CENTER
            binding.scratchpadWindow.layoutParams = lp
        }

        // Tapping outside the bordered window (on the transparent root) dismisses.
        binding.root.setOnClickListener { finish() }
        // Consume touches inside the window so they don't propagate to the dismiss handler.
        binding.scratchpadWindow.setOnClickListener { }

        // Placeholder button no-ops — wired up in Session 3 (canvas) and later.
        binding.btnScratchpadPrev.setOnClickListener { }
        binding.btnScratchpadNext.setOnClickListener { }
        binding.btnScratchPen.setOnClickListener { }
        binding.btnScratchEraser.setOnClickListener { }
        binding.btnScratchLasso.setOnClickListener { }
        binding.btnScratchAddPage.setOnClickListener { }
        binding.btnScratchDeletePage.setOnClickListener { }
        binding.btnSendToNotebook.setOnClickListener { }
    }
}
