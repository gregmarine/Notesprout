package com.symmetricalpalmtree.notesprout.extension

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SharedMemory
import com.symmetricalpalmtree.notesprout.core.Bitmaps
import com.symmetricalpalmtree.notesprout.core.Slog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout

/** Every failure of an extension call, whatever the cause. The caller decides what the user sees. */
class ExtensionCallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Bind-per-operation client for one template provider: bind (`BIND_AUTO_CREATE`), await the
 * connection (≤ [BIND_TIMEOUT_MS]), run the AIDL call on IO under a timeout, **unbind in `finally`**.
 * The core never holds a binding across screens. Every failure — no connection, timeout,
 * `RemoteException`, `SecurityException`, bad payload — surfaces as one [ExtensionCallException].
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
     * Bind, run [block] on IO under [timeoutMs], unbind. The blocking Binder transaction cannot be
     * interrupted, so on timeout the caller resumes with [ExtensionCallException] while the orphaned
     * call finishes on its own IO thread inside a supervisor scope (its result/exception is discarded).
     */
    suspend fun <T> call(timeoutMs: Long, block: (ITemplateProvider) -> T): T {
        val connected = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(ExtensionCallException("service disconnected: $name"))
            }
            override fun onBindingDied(name: ComponentName) {
                connected.completeExceptionally(ExtensionCallException("binding died: $name"))
            }
            override fun onNullBinding(name: ComponentName) {
                connected.completeExceptionally(ExtensionCallException("null binding: $name"))
            }
        }
        val intent = Intent(ExtensionContract.ACTION_TEMPLATE_PROVIDER).setComponent(ref.component)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Slog.d(TAG) { "bind ${ref.component.flattenToShortString()}" }
        try {
            // Trust is re-checked at bind time, not only at discovery: the package could have been
            // replaced (same name, different key) while the screen holding this ProviderRef was open.
            if (appContext.packageManager.checkSignatures(appContext.packageName, ref.packageName)
                != PackageManager.SIGNATURE_MATCH
            ) throw ExtensionCallException("signature no longer matches for ${ref.packageName}")
            val bound = try {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                throw ExtensionCallException("bindService refused: ${e.message}", e)
            }
            if (!bound) throw ExtensionCallException("bindService returned false for ${ref.component}")
            val binder = try {
                withTimeout(BIND_TIMEOUT_MS) { connected.await() }
            } catch (e: TimeoutCancellationException) {
                throw ExtensionCallException("bind timeout after ${BIND_TIMEOUT_MS} ms", e)
            }
            val provider = ITemplateProvider.Stub.asInterface(binder)
                ?: throw ExtensionCallException("binder is not an ITemplateProvider")
            val deferred = scope.async { block(provider) }
            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw ExtensionCallException("call timeout after $timeoutMs ms", e)
            } catch (e: ExtensionCallException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ExtensionCallException("${e.javaClass.simpleName}: ${e.message}", e)
            }
        } finally {
            scope.cancel()
            runCatching { appContext.unbindService(connection) }
            Slog.d(TAG) { "unbind ${ref.component.flattenToShortString()}" }
        }
    }

    companion object {
        private const val TAG = "TemplateProviderClient"
        const val BIND_TIMEOUT_MS = 3_000L
        const val LIST_TIMEOUT_MS = 2_000L
        /** E-ink CPUs: a full-page lossless WEBP encode is the slow part. */
        const val RENDER_TIMEOUT_MS = 15_000L
    }
}
