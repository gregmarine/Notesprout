package com.symmetricalpalmtree.notesprout.notebook

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.notesprout.extension.LinkCatalogSource

/** Release build: no debug tools exist on the notebook screen. */
object NotebookDebugMenu {
    fun install(
        activity: AppCompatActivity,
        bar: ViewGroup,
        provider: () -> RecognizeContext?,
        linkCatalog: () -> LinkCatalogSource? = { null },
    ) = Unit
}
