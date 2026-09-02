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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

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
 *
 * [hold] (arc 11 / J3) is the bind half of [call] without the unbind — for the one case where "the
 * operation" is the showing of an extension-owned screen: the [HeldBinding] it returns runs any
 * number of timed calls over the same connection and is [HeldBinding.close]d (unbind) in the
 * caller's `finally` when the screen is over. Still bind-per-operation — the operation is just
 * longer.
 */
object ExtensionBinder {

    const val BIND_TIMEOUT_MS = 3_000L

    /**
     * A bind that outlives one call (arc 11 / J3). [call] runs [block] on IO under [timeoutMs] with
     * the same exception mapping as [ExtensionBinder.call]; after the connection dies
     * (`onBindingDied` / `onServiceDisconnected`) or [close] every call throws
     * [ExtensionCallException]. [close] unbinds (idempotent) — the caller's `finally`.
     */
    class HeldBinding<I : Any> internal constructor(
        private val appContext: Context,
        private val tag: String,
        private val component: ComponentName,
        private val connection: ServiceConnection,
        val iface: I,
        private val deadFlag: AtomicReference<String?>,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var closed = false

        /** The most recent [call]'s transaction — the one a timeout may have orphaned. */
        @Volatile private var lastCall: Deferred<*>? = null

        /** True once the connection died or [close] ran — the next [call] throws. */
        val isDead: Boolean get() = closed || deadFlag.get() != null

        suspend fun <T> call(timeoutMs: Long, block: (I) -> T): T {
            deadFlag.get()?.let { throw ExtensionCallException(it) }
            if (closed) throw ExtensionCallException("binding closed")
            val deferred = scope.async { block(iface) }
            lastCall = deferred
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
        }

        /**
         * Wait ≤ [timeoutMs] for the most recent call's transaction to finish — the one a timeout
         * left running (arc 23 / Y4). **A Binder call cannot be cancelled**: the extension is still
         * working (a placement's batches, say) after the host has stopped waiting, and tearing the
         * bind down under it — `end()`, unbind, the store binder revoked — would land the revoke
         * between the extension's batches, where its own compensation is refused by the same gate.
         * So a client settles before it ends. Answers [Settled.OK] when the orphaned call completed
         * without throwing (the work landed: a late success is a success), [Settled.FAILED] when it
         * threw, and [Settled.PENDING] when it is still running past this second budget — the one
         * case that stays a guess, and it is logged as one.
         */
        suspend fun settle(timeoutMs: Long): Settled {
            val d = lastCall ?: return Settled.OK
            if (d.isCompleted.not()) withTimeoutOrNull(timeoutMs) { d.join() }
            if (!d.isCompleted) {
                Slog.d(tag) { "settle: a call is still running after $timeoutMs ms — tearing down under it" }
                return Settled.PENDING
            }
            return if (d.isCancelled || runCatching { d.await() }.isFailure) Settled.FAILED else Settled.OK
        }

        enum class Settled { OK, FAILED, PENDING }

        /** Unbind; idempotent. Orphaned calls are cancelled (their Binder transaction finishes on
         *  its own thread and is discarded) — [settle] first when one may be running. */
        fun close() {
            if (closed) return
            closed = true
            scope.cancel()
            runCatching { appContext.unbindService(connection) }
            Slog.d(tag) { "unbind ${component.flattenToShortString()} (held)" }
        }
    }

    /**
     * Bind and keep the binding: explicit component, signature re-checked immediately before the
     * bind (the same rule [call] follows), `BIND_AUTO_CREATE` on the app context, connection awaited
     * ≤ [bindTimeoutMs]. On any failure the (attempted) bind is released before the
     * [ExtensionCallException] is thrown; on success the caller owns the returned [HeldBinding] and
     * must [HeldBinding.close] it.
     */
    suspend fun <I : Any> hold(
        appContext: Context,
        ref: ProviderRef,
        action: String,
        tag: String,
        asInterface: (IBinder) -> I?,
        bindTimeoutMs: Long = BIND_TIMEOUT_MS,
    ): HeldBinding<I> {
        val connected = CompletableDeferred<IBinder>()
        val dead = AtomicReference<String?>(null)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                dead.compareAndSet(null, "service disconnected: $name")
                connected.completeExceptionally(ExtensionCallException("service disconnected: $name"))
            }
            override fun onBindingDied(name: ComponentName) {
                dead.compareAndSet(null, "binding died: $name")
                connected.completeExceptionally(ExtensionCallException("binding died: $name"))
            }
            override fun onNullBinding(name: ComponentName) {
                dead.compareAndSet(null, "null binding: $name")
                connected.completeExceptionally(ExtensionCallException("null binding: $name"))
            }
        }
        val intent = Intent(action).setComponent(ref.component)
        Slog.d(tag) { "hold ${ref.component.flattenToShortString()}" }
        var bound = false
        try {
            if (appContext.packageManager.checkSignatures(appContext.packageName, ref.packageName)
                != PackageManager.SIGNATURE_MATCH
            ) throw ExtensionCallException("signature no longer matches for ${ref.packageName}")
            bound = try {
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
            return HeldBinding(appContext, tag, ref.component, connection, iface, dead)
        } catch (t: Throwable) {
            if (bound) runCatching { appContext.unbindService(connection) }
            Slog.d(tag) { "hold failed → unbind ${ref.component.flattenToShortString()}: ${t.message}" }
            throw t
        }
    }

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
