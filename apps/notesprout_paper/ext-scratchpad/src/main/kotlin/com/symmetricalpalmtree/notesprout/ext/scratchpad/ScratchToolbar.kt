package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.view.View
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.notesprout.ext.scratchpad.databinding.ActivityScratchPadBinding
import com.symmetricalpalmtree.notesprout.notebook.PaperToolbar

/**
 * The pad's chrome buttons (arc 6 / S1) over the shared [PaperToolbar] — Back in the top bar beside the
 * title, Pen · Eraser · Lasso in the bottom bar (S1 follow-up: the user moved the title up and the tools
 * down) — plus the **Send** button (top bar) — present only when the pad was opened from a notebook ([sendEnabled],
 * `EXTRA_SCRATCH_SEND_ENABLED`); S1 shows it as a no-op (user decision S1 Q4), S2 wires it to
 * `RESULT_SCRATCH_SEND`. Every tap releases the EPD render first, like the shared toolbar.
 */
class ScratchToolbar(
    binding: ActivityScratchPadBinding,
    private val paper: PaperView,
    sendEnabled: Boolean,
    onBack: () -> Unit,
    onSend: () -> Unit,
) {
    private val base = PaperToolbar(binding.bottomStrip, binding.btnBack, binding.btnPen, binding.btnEraser, binding.btnLasso, paper, onBack)

    init {
        binding.btnSend.visibility = if (sendEnabled) View.VISIBLE else View.GONE
        TooltipCompat.setTooltipText(binding.btnSend, binding.btnSend.contentDescription)
        binding.btnSend.setOnClickListener { paper.releaseRender(); onSend() }
    }

    fun sync(tool: Tool) = base.sync(tool)
}
