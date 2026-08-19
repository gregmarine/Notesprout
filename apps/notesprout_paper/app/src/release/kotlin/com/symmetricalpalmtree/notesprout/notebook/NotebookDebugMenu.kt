package com.symmetricalpalmtree.notesprout.notebook

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/** Release build: no debug tools exist on the notebook screen. */
object NotebookDebugMenu {
    fun install(activity: AppCompatActivity, bar: ViewGroup, provider: () -> RecognizeContext?, contents: suspend () -> ContentsSource.Result?) = Unit
}
