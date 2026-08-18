package com.symmetricalpalmtree.notesprout.ext.markdown

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
import com.symmetricalpalmtree.notesprout.extension.IMarkdownRenderer
import com.symmetricalpalmtree.notesprout.extension.RenderedImage

/**
 * The MARKDOWN_RENDERER capability point (arc 4 / H0). Bound by the Notesprout Paper core — and, from
 * H3 on, reached by object providers only through the core's proxy; never launched by a user (no
 * Activity). Stateless: AIDL methods run on Binder threads and hold nothing between calls.
 *
 * Handshake = the Templates one, verbatim: the WEBP is written into a `SharedMemory` region parked in
 * a per-thread [pending] slot and closed in `onTransact`'s `finally`, once the reply (holding a dup of
 * the descriptor) has been marshalled. Logs sizes + durations only — never the markdown.
 */
class MarkdownRendererService : Service() {

    private val binder = object : IMarkdownRenderer.Stub() {

        private val pending = ThreadLocal<SharedMemory>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                return super.onTransact(code, data, reply, flags)
            } finally {
                pending.get()?.close()
                pending.remove()
            }
        }

        override fun render(markdown: String?, maxWidthPx: Int, dpi: Float, maxLines: Int, paddingPx: Int): RenderedImage? {
            HostCallerCheck.enforce(this@MarkdownRendererService, BuildConfig.HOST_PACKAGE)
            val source = markdown ?: return null
            MarkdownBitmap.Sizing.checkArgs(source.length, maxWidthPx, dpi, maxLines, paddingPx)   // IllegalArgumentException
            val t0 = SystemClock.elapsedRealtime()
            val bitmap = MarkdownBitmap.render(source, maxWidthPx, dpi, maxLines, paddingPx) ?: run {
                if (BuildConfig.DEBUG) Log.d(TAG, "render: nothing to draw (${source.length} chars)")
                return null
            }
            val w = bitmap.width
            val h = bitmap.height
            val bytes = try { MarkdownBitmap.encodeWebp(bitmap) } finally { bitmap.recycle() }
            val shared = SharedMemory.create(null, bytes.size)
            val buffer = shared.mapReadWrite()
            buffer.put(bytes)
            SharedMemory.unmap(buffer)
            shared.setProtect(OsConstants.PROT_READ)
            pending.set(shared)
            if (BuildConfig.DEBUG) Log.d(TAG, "render ${w}x$h px, ${bytes.size} B, ${source.length} chars, maxW=$maxWidthPx dpi=$dpi lines=$maxLines pad=$paddingPx in ${SystemClock.elapsedRealtime() - t0} ms")
            return RenderedImage(shared, bytes.size, ExtensionContract.MIME_WEBP, w, h)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "MarkdownRendererService"
    }
}
