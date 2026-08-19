package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.core.ActionSheetDialog
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.ext.scratchpad.R
import kotlinx.coroutines.launch

/**
 * Debug build only (no-op twin in `src/release`): a ⋯ at the end of the pad's top bar with
 * **"Store size"** (arc 6 / S1, removed in S3) — the key count + summed page-blob bytes of the
 * pad's slice of the host store, as a toast. Reads only; never a stroke in a log line.
 */
object ScratchDebugMenu {

    private const val TAG = "ScratchDebugMenu"

    fun install(activity: AppCompatActivity, bar: ViewGroup, document: () -> ScratchDocument?) {
        bar.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        val btn = AppCompatImageButton(activity, null, 0).apply {
            setImageResource(R.drawable.ic_dots)
            setBackgroundResource(R.drawable.bg_toolbar_button)
            val size = activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
            val pad = activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(pad, pad, pad, pad)
            contentDescription = "Debug tools"
            scaleType = ImageView.ScaleType.FIT_CENTER
            stateListAnimator = null
        }
        TooltipCompat.setTooltipText(btn, btn.contentDescription)
        btn.setOnClickListener {
            ActionSheetDialog(activity).title("Debug tools")
                .addAction(null, "Store size") { storeSize(activity, document()) }
                .show()
        }
        bar.addView(btn)
    }

    private fun storeSize(activity: AppCompatActivity, document: ScratchDocument?) {
        val doc = document ?: return
        activity.lifecycleScope.launch {
            val text = try {
                val (keys, bytes) = doc.sizeSummary()
                "Store: $keys keys, $bytes bytes of pages (${doc.ids.size} pages, current ${doc.pageBytes} B)"
            } catch (e: StoreUnavailable) {
                Slog.d(TAG) { "store size failed: ${e.message}" }
                "Store size: FAIL (see log)"
            }
            if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
        }
    }
}
