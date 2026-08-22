package com.symmetricalpalmtree.notesproutsn.notebook

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/** Release twin of the notebook's debug ⋯ — nothing is installed, nothing is drawn, the bar is
 *  exactly what `activity_notebook.xml` declares. */
@Suppress("UNUSED_PARAMETER")
object NotebookDebugMenu {
    fun install(activity: AppCompatActivity, bar: ViewGroup, provider: () -> RecognizeContext?) = Unit
}
