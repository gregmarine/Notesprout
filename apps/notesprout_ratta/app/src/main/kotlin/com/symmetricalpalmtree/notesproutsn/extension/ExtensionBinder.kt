package com.symmetricalpalmtree.notesproutsn.extension

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout

/**
 * The one bind-per-operation path every extension call takes (the shape Paper's arcs proved):
 * explicit component, **signature re-checked at bind time** (not only at discovery — the package
 * could have been replaced with the same name and a different key while the screen holding the
 * [ProviderRef] was open), `BIND_AUTO_CREATE` on the app context, connection awaited ≤
 * [bindTimeoutMs], [block] run on IO under [callTimeoutMs] inside a supervisor scope, **unbind in
 * `finally`**. The host never holds a binding across screens.
 *
 * Every failure — no connection, timeout, `RemoteException`, `SecurityException`, bad payload —
 * surfaces as **one** [ExtensionCallException]; a `CancellationException` (the caller's scope is
 * gone) is re-thrown as is. The blocking Binder transaction cannot be interrupted, so on timeout the
 * caller resumes with [ExtensionCallException] while the orphaned call finishes on its own IO thread
 * and its result/exception is discarded.
 *
 * Clients wrap this with their own payload validation. Log lines go under the caller's [tag].
 * SN has no held-binding variant — the recognizer is the only point and it is call-shaped.
 */
object ExtensionBinder {

    const val BIND_TIMEOUT_MS = 3_000L

    suspend fun <I : Any, T> call(
        appContext: Context,
        ref: ProviderRef,
        action: String,
        tag: String,
        asInterface: (IBinder) -> I?,
        callTimeoutMs: Long,
        bindTimeoutMs: Long = BIND_TIMEOUT_MS,
        block: (I) -> T,
    ): T {
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
        val intent = Intent(action).setComponent(ref.component)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Slog.d(tag) { "bind ${ref.component.flattenToShortString()}" }
        try {
            // Trust is re-checked at bind time, not only at discovery.
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
                withTimeout(bindTimeoutMs) { connected.await() }
            } catch (e: TimeoutCancellationException) {
                throw ExtensionCallException("bind timeout after $bindTimeoutMs ms", e)
            }
            val iface = asInterface(binder)
                ?: throw ExtensionCallException("binder is not the expected interface for $action")
            val deferred = scope.async { block(iface) }
            return try {
                withTimeout(callTimeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw ExtensionCallException("call timeout after $callTimeoutMs ms", e)
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
            Slog.d(tag) { "unbind ${ref.component.flattenToShortString()}" }
        }
    }
}
