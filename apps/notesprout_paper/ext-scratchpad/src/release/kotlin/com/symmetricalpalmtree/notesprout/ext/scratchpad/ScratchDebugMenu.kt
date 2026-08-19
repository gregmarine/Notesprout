package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/** Release build: no debug tools exist on the scratch pad. */
object ScratchDebugMenu {
    fun install(activity: AppCompatActivity, bar: ViewGroup, document: () -> ScratchDocument?) = Unit
}
