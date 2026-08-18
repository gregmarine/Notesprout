package com.symmetricalpalmtree.notesprout.extension

import android.os.Binder
import android.os.Parcel
import android.os.SharedMemory
import com.symmetricalpalmtree.notesprout.core.Slog
import kotlinx.coroutines.runBlocking

/**
 * The `IMarkdownRenderer` the host lends an object provider as the `markdown` in-parameter of
 * `render` (arc 4 / H3). Same shape as [RecognizerProxyBinder]: minted per bind for the provider's
 * uid, [revoke]d in [ObjectProviderClient]'s `finally`, [ProxyGate.check] first, then a two-hop
 * forward through the host's own [MarkdownClient] via `runBlocking` on the Binder thread.
 *
 * Inward caps re-applied before forwarding ([RenderCaps.checkArgs] → `IllegalArgumentException`,
 * source truncated). The reply is **re-wrapped**: the proxy owns the copy — the verified bytes go
 * into a fresh region ([RenderedImages.wrap], `PROT_READ`) parked per thread and closed in
 * `onTransact`'s `finally` once the reply (holding a dup of the descriptor) is marshalled — the
 * Templates handshake, on the host side this time. Failures → `IllegalStateException(<class>)`.
 */
class MarkdownProxyBinder(
    private val client: MarkdownClient,
    extUid: Int,
) : IMarkdownRenderer.Stub() {

    private val gate = ProxyGate(extUid, Binder::getCallingUid)
    private val pending = ThreadLocal<SharedMemory>()

    /** After this every method throws `SecurityException`. */
    fun revoke() = gate.revoke()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            return super.onTransact(code, data, reply, flags)
        } finally {
            pending.get()?.close()
            pending.remove()
        }
    }

    override fun render(markdown: String?, maxWidthPx: Int, dpi: Float, maxLines: Int, paddingPx: Int): RenderedImage? {
        gate.check()
        val source = markdown ?: return null
        try {
            RenderCaps.checkArgs(maxWidthPx, dpi, maxLines, paddingPx)
        } catch (e: RenderArgsException) {
            throw IllegalArgumentException(e.message)
        }
        val copy = try {
            runBlocking { client.render(source, maxWidthPx, dpi, maxLines, paddingPx) }
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "forward failed: ${e.message}" }
            throw IllegalStateException(e.javaClass.simpleName)
        } ?: return null
        val image = RenderedImages.wrap(copy)
        pending.set(image.memory)
        return image
    }

    private companion object {
        const val TAG = "MarkdownProxyBinder"
    }
}
