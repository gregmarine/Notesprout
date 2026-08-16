package com.symmetricalpalmtree.notesprout.library

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.IndexGuard
import com.symmetricalpalmtree.notesprout.core.TopGuard

class LibraryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return

        val root = FrameLayout(this)
        TopGuard.applyInsetPadding(root)

        val label = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 32f
            setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.inkBlack))
            gravity = Gravity.CENTER
        }

        root.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        setContentView(root)
    }
}
