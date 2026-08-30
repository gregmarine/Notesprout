package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The extension-owned document editor screen — **the M3 stub**, and the live proof that the fifth
 * point's callback seam works end to end. It reads the current target's state and its whole text
 * back through [EditorSession]'s host binder and shows them; it edits nothing, saves nothing and
 * asks for nothing else. **M4 replaces this file with the real editor** (Write/Preview, the format
 * bar, autosave through `saveChunk`); keep it small until then — everything added here is something
 * M4 has to unpick.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is exported
 * (it has to be — the host launches it by action) and only a `startActivityForResult` from the host
 * package gets in. A plain `am start` from a shell leaves `callingPackage` null and is refused.
 *
 * **No paper, no handoff.** Unlike the scratch pad this screen has no g-paper surface, so there is
 * no EPD pipeline to reclaim on the way in or release on the way out, and the host does not hand
 * one over. Whether that holds on the Nomad is the M3 on-device question — if the ink daemon draws
 * beneath this window, the answer is the pad's ordering, host-side.
 *
 * **Loading is off Main and the text is never logged.** `current()` and every `readChunk` are
 * blocking Binder calls; they run on IO and only the finished strings are posted back. A failure of
 * any kind — no showing, a revoked binder, a host that closed underneath us — puts one plain line
 * in the text view and logs the exception's class. Lengths and counts, never content.
 */
class DocumentEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentEditorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        binding = ActivityDocumentEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)   // 0 on Ratta — chrome sits flush at the top edge

        binding.btnClose.setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        binding.btnDone.setOnClickListener { setResult(Activity.RESULT_OK); finish() }
        TooltipCompat.setTooltipText(binding.btnClose, binding.btnClose.contentDescription)
        TooltipCompat.setTooltipText(binding.btnDone, binding.btnDone.contentDescription)

        load()
    }

    /**
     * Ask the host for the current target and pull its text across, chunk by chunk. Both halves are
     * one IO hop: the state names how many `readChunk` calls serve the window it just parked, so
     * they must not be separated by anything that could let a new window be loaded between them —
     * at M3 nothing can, and at M6 the flip guards make that explicit.
     */
    private fun load() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val host = EditorSession.host ?: throw IllegalStateException("no showing")
                    val state = host.current()
                    val text = buildString {
                        for (i in 0 until state.textChunks) append(host.readChunk(i))
                    }
                    Loaded(state, text)
                } catch (e: Exception) {
                    // The class name only: an exception's message from either side of this seam
                    // could carry a path, and its content certainly could.
                    Slog.d(TAG) { "load failed: ${e.javaClass.simpleName}" }
                    null
                }
            }
            if (isFinishing || isDestroyed) return@launch
            if (loaded == null) {
                binding.text.setText(R.string.document_load_failed)
                return@launch
            }
            val state = loaded.state
            binding.title.text = state.title.ifEmpty { getString(R.string.document_title) }
            // −1 is the notebook scope (M7) — not a page, so nothing to number.
            binding.pageIndicator.text = if (state.pageIndex >= 0) {
                getString(R.string.document_page_indicator, state.pageIndex + 1, state.pageCount)
            } else ""
            binding.text.text = loaded.text
            Slog.d(TAG) { "loaded ${loaded.text.length} chars in ${state.textChunks} chunk(s)" }
        }
    }

    /** What one load brought back — the state and the reassembled text, materialised off Main. */
    private class Loaded(val state: DocumentPageState, val text: String)

    private companion object {
        const val TAG = "DocumentEditor"
    }
}
