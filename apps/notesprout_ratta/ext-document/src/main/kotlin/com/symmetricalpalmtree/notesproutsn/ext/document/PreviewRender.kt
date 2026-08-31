package com.symmetricalpalmtree.notesproutsn.ext.document

import android.content.res.Resources
import android.view.View
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownParser
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownRenderer

/**
 * The Preview surface's one act (arc 19 / M4, lifted out of the Activity at M8) — the buffer, run
 * through the shared `:markdown` engine, into the reading `TextView`.
 *
 * It is a **function of the two views and the display**, and of nothing else the screen holds, which
 * is why it can live here: no target, no saver, no mode flag. Every caller — entering Preview, a
 * flip that lands under it, a Bring in, a text-size change — asks for exactly the same thing.
 *
 * **Once per ask, never on a timer.** A render is a full re-layout of the rendered spans, and on
 * e-ink a surface that repaints itself is a surface that ghosts.
 */
internal object PreviewRender {

    /** Render the current Markdown, if the Preview surface is the one on screen. */
    fun render(binding: ActivityDocumentEditorBinding, resources: Resources) {
        if (binding.previewScroll.visibility != View.VISIBLE) return
        val view = binding.previewText
        val width = view.width - view.paddingLeft - view.paddingRight
        if (width <= 0) {
            // First show: no measured width yet, and the horizontal rule's span needs one.
            view.post { render(binding, resources) }
            return
        }
        val markdown = binding.editor.text?.toString().orEmpty()
        view.text = if (markdown.isBlank()) "" else MarkdownRenderer.render(
            MarkdownParser.parse(markdown),
            availableWidthPx = width,
            paint = view.paint,
            density = resources.displayMetrics.density,
            blockGapPx = (8f * resources.displayMetrics.density).toInt(),
        )
        binding.previewScroll.scrollTo(0, 0)
    }
}
