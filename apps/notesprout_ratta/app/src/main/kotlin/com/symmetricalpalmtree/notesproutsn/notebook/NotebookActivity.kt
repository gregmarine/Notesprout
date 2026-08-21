package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNotebookBinding

/**
 * **R2 stub.** R3 replaces the body with the full-bleed g-paper drawing surface, the toolbar and
 * the session's serial `SoilWriter`; what is fixed here is the *entry contract*, because the
 * library already depends on it:
 *
 *  - identity arrives as [EXTRA_NOTEBOOK_ID] + [EXTRA_NOTEBOOK_NAME] — **never a `File`**. A path
 *    is derived from the id through `soilFile()` and nowhere else;
 *  - the index is proven open first ([IndexGuard]), like every index-touching screen.
 */
class NotebookActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        val binding = ActivityNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val name = intent.getStringExtra(EXTRA_NOTEBOOK_NAME).orEmpty()
        binding.stubText.text = getString(R.string.notebook_stub_body, name)
    }

    companion object {
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        fun intent(context: Context, notebookId: String, notebookName: String): Intent =
            Intent(context, NotebookActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
    }
}
