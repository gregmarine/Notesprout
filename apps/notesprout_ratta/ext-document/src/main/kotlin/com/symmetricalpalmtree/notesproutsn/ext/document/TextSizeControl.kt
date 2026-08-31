package com.symmetricalpalmtree.notesproutsn.ext.document

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The editor's text size (arc 19 / M5) — og's five steps, the sheet that picks one, and the size in
 * force applied to both surfaces.
 *
 * It is a control rather than a tool: it acts on the *screen*, not on the buffer or the selection,
 * which is why it does not live with [EditorTools]. The value outlives the showing — it is stored in
 * the host's extension store through [EditorPrefs], because an extension writes nothing to disk
 * itself.
 */
internal class TextSizeControl(
    private val context: Context,
    private val binding: ActivityDocumentEditorBinding,
    private val scope: CoroutineScope,
    private val isPreviewing: () -> Boolean,
    /** The renderer bakes sizes into spans from the paint it was handed, so a live preview has to
     *  be rebuilt rather than just re-measured. */
    private val renderPreview: () -> Unit,
) {

    /** The size in force, in sp — **not** `editor.textSize`, which is px. */
    var sp: Float = EditorPrefs.DEFAULT_TEXT_SIZE
        private set

    /** Pick a text size. The tick marks the one in force; the choice outlives the showing. */
    fun prompt() {
        val sheet = ActionSheetDialog(context).title(context.getString(R.string.text_size_title))
        for ((labelRes, size) in EditorPrefs.SIZES) {
            val label = context.getString(labelRes)
            sheet.addAction(
                null,
                if (size == sp) context.getString(R.string.text_size_current, label) else label,
            ) { apply(size) }
        }
        sheet.show()
    }

    /** Draw both surfaces at [size]. [persist] is false only at load, where the value came *from*
     *  the store and writing it back would be a Binder round trip that changes nothing. */
    fun apply(size: Float, persist: Boolean = true) {
        sp = size
        binding.editor.textSize = size
        binding.previewText.textSize = size + EditorPrefs.PREVIEW_BUMP
        if (persist) scope.launch(Dispatchers.IO) { EditorPrefs.saveTextSize(size) }
        if (isPreviewing()) renderPreview()
        Slog.d(TAG) { "text size → ${size}sp" }
    }

    private companion object {
        const val TAG = "DocumentEditor"
    }
}
