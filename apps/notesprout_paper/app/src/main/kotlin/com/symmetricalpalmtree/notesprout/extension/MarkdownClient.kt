package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import com.symmetricalpalmtree.notesprout.core.Slog

/**
 * Bind-per-operation client for the one Markdown renderer (arc 4 / H3), over the shared
 * [ExtensionBinder] (signature re-check at bind, bind ≤ 3 s, `render` ≤ [RENDER_TIMEOUT_MS] on IO,
 * unbind in `finally`, every failure → one [ExtensionCallException]). Stateless point — no store.
 *
 * **Outward caps run before the bind** ([RenderCaps]): the source is truncated to
 * `MAX_MARKDOWN_CHARS`, bad numbers → [RenderArgsException] without a bind. **Inward is untrusted**:
 * mime + byte count, then the WEBP header must decode to exactly the declared size within the edge
 * cap ([RenderCaps.imageProblem]) before the bytes go anywhere; the `SharedMemory` is always closed.
 * Returns the complete WEBP file, or null only when the extension itself returned null (nothing to
 * draw). Logs (tag [TAG]): sizes + durations — **never the text**.
 */
class MarkdownClient(context: Context, private val ref: ProviderRef) {

    private val appContext = context.applicationContext

    /** The verified WEBP bytes with their (header-confirmed) size, or null when the source renders to nothing. */
    suspend fun render(markdown: String, maxWidthPx: Int, dpi: Float, maxLines: Int, paddingPx: Int): RenderedImages.Copy? {
        RenderCaps.checkArgs(maxWidthPx, dpi, maxLines, paddingPx)
        val source = RenderCaps.markdown(markdown)
        val t0 = System.currentTimeMillis()
        val result = ExtensionBinder.call(
            appContext, ref, ExtensionContract.ACTION_MARKDOWN_RENDERER, TAG,
            asInterface = { IMarkdownRenderer.Stub.asInterface(it) },
            callTimeoutMs = RENDER_TIMEOUT_MS,
        ) { renderer ->
            val image = renderer.render(source, maxWidthPx, dpi, maxLines, paddingPx) ?: return@call null
            RenderedImages.copyOut(image)
        }
        Slog.d(TAG) { "render: ${source.length} chars → ${result?.let { "${it.widthPx}x${it.heightPx} px, ${it.bytes.size} B" } ?: "nothing"} in ${System.currentTimeMillis() - t0} ms" }
        return result
    }

    companion object {
        private const val TAG = "MarkdownClient"
        /** One markdown render on an e-ink CPU (layout + lossless WEBP encode of a heading-sized image). */
        const val RENDER_TIMEOUT_MS = 5_000L
    }
}
