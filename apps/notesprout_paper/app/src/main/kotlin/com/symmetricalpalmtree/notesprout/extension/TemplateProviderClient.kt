package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import android.os.SharedMemory
import com.symmetricalpalmtree.notesprout.core.Bitmaps

/** Every failure of an extension call, whatever the cause. The caller decides what the user sees. */
open class ExtensionCallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Bind-per-operation client for one template provider over the shared [ExtensionBinder]: bind
 * (`BIND_AUTO_CREATE`), await the connection (≤ [BIND_TIMEOUT_MS]), run the AIDL call on IO under a
 * timeout, **unbind in `finally`**. The core never holds a binding across screens. Every failure — no
 * connection, timeout, `RemoteException`, `SecurityException`, bad payload — surfaces as one
 * [ExtensionCallException]. This class owns the payload rules (mime, byte cap, exact size).
 */
class TemplateProviderClient(context: Context, private val ref: ProviderRef) {

    private val appContext = context.applicationContext

    /** The provider's templates, in its display order. */
    suspend fun list(): List<TemplateInfo> =
        call(LIST_TIMEOUT_MS) { it.listTemplates()?.filterNotNull() ?: emptyList() }   // AIDL lists may carry nulls

    /**
     * Render [templateId] at [widthPx]×[heightPx] for [dpi]. Returns the complete WEBP file, or null
     * only when the extension itself returned null (unknown id). The payload is untrusted: the MIME
     * type must be [ExtensionContract.MIME_WEBP], `0 < byteCount ≤ MAX_RENDER_BYTES`, and the bytes
     * must decode (header probe) to an image of exactly [widthPx]×[heightPx]; the `SharedMemory` is
     * always closed.
     */
    suspend fun render(templateId: String, widthPx: Int, heightPx: Int, dpi: Float): ByteArray? =
        call(RENDER_TIMEOUT_MS) { provider ->
            val result = provider.render(templateId, widthPx, heightPx, dpi) ?: return@call null
            copyOut(result, widthPx, heightPx)
        }

    private fun copyOut(result: RenderedTemplate, widthPx: Int, heightPx: Int): ByteArray {
        val memory: SharedMemory = result.memory
        try {
            if (result.mimeType != ExtensionContract.MIME_WEBP) {
                throw ExtensionCallException("unexpected mime type '${result.mimeType}'")
            }
            val n = result.byteCount
            if (n <= 0 || n > ExtensionContract.MAX_RENDER_BYTES || n > memory.size) {
                throw ExtensionCallException("bad byte count $n (region ${memory.size})")
            }
            val buffer = memory.mapReadOnly()
            val bytes = ByteArray(n)
            try {
                buffer.get(bytes)
            } finally {
                SharedMemory.unmap(buffer)
            }
            // Untrusted payload: the header must decode to an image of exactly the requested size
            // before the bytes are stored anywhere (a wrong-size template would be stretched onto
            // every page forever; the full decode happens bounded, at open, in NotebookSession).
            val size = Bitmaps.imageSize(bytes) ?: throw ExtensionCallException("payload is not a decodable image")
            if (size.first != widthPx || size.second != heightPx) {
                throw ExtensionCallException("payload is ${size.first}x${size.second}, requested ${widthPx}x$heightPx")
            }
            return bytes
        } finally {
            memory.close()
        }
    }

    /**
     * Bind, run [block] on IO under [timeoutMs], unbind — the shared [ExtensionBinder] path
     * (signature re-check at bind, unbind in `finally`, one [ExtensionCallException] for every failure).
     */
    suspend fun <T> call(timeoutMs: Long, block: (ITemplateProvider) -> T): T =
        ExtensionBinder.call(
            appContext, ref, ExtensionContract.ACTION_TEMPLATE_PROVIDER, TAG,
            asInterface = { ITemplateProvider.Stub.asInterface(it) },
            callTimeoutMs = timeoutMs,
            block = block,
        )

    companion object {
        private const val TAG = "TemplateProviderClient"
        const val BIND_TIMEOUT_MS = ExtensionBinder.BIND_TIMEOUT_MS
        const val LIST_TIMEOUT_MS = 2_000L
        /** E-ink CPUs: a full-page lossless WEBP encode is the slow part. */
        const val RENDER_TIMEOUT_MS = 15_000L
    }
}
