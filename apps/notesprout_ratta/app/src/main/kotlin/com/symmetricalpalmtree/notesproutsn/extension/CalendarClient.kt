package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The host's client for the one calendar (arc 23 / Y1) — [ScratchPadClient]'s shape exactly, on
 * `ICalendar`: a **held** bind that brackets the showing of the extension's screen. One instance per
 * calling screen; [open] / [finish] are idempotent and the caller runs [finish] from its result
 * callback **and** from `onDestroy` while still open.
 *
 * [open]: `ExtensionStores.open` on IO (the pre-open rule — a cold KDF is seconds on the Nomad and
 * must never sit inside a call timeout) → mint one uid-bound [ExtensionStoreBinder] →
 * [ExtensionBinder.hold] (signature re-checked at bind) → `begin(store)` ≤ [CALL_TIMEOUT_MS] → the
 * screen Intent ([ExtensionContract.ACTION_CALENDAR_SCREEN], `setPackage(ref.packageName)`, the two
 * boolean extras — **nothing else rides the Intent**, the ink goes through the held service).
 * Returns null on any failure (reason logged; everything opened so far is released).
 *
 * [send] / [drainOutgoing] are the two transfers' host half (their doors land in Y3): the same
 * chunking, caps and timeouts as the pad's, with a [CalendarTarget] on every inbound chunk where
 * the pad has a placement int. [finish]: `end()` ≤ [CALL_TIMEOUT_MS] in a `try`, then unbind +
 * revoke the store binder in `finally`. Log tag [TAG] — counts + durations, never a stroke.
 */
class CalendarClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<ICalendar>? = null
    private var storeBinder: ExtensionStoreBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, hold the bind, `begin(store)`, and build the screen Intent — or null (logged). */
    suspend fun open(sendEnabled: Boolean, openReceived: Boolean): Intent? {
        if (held != null) { Slog.d(TAG) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val store: ExtensionStoreBinder
        try {
            val db = withContext(Dispatchers.IO) { ExtensionStores.open(appContext, ref.packageName) }
            val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
            store = ExtensionStoreBinder(db, extUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageManager.NameNotFoundException) {
            Slog.d(TAG) { "open failed: package gone ${ref.packageName}" }
            return null
        } catch (e: Exception) {
            Slog.d(TAG) { "open failed: store open ${e.javaClass.simpleName}: ${e.message}" }
            return null
        }
        val binding = try {
            ExtensionBinder.hold(appContext, ref, ExtensionContract.ACTION_CALENDAR, TAG,
                asInterface = { ICalendar.Stub.asInterface(it) })
        } catch (e: CancellationException) {
            store.revoke(); throw e
        } catch (e: ExtensionCallException) {
            store.revoke()
            Slog.d(TAG) { "open failed: hold ${e.message}" }
            return null
        }
        held = binding
        storeBinder = store
        try {
            binding.call(CALL_TIMEOUT_MS) { it.begin(store) }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "open failed: begin ${e.message}" }
            finish()
            return null
        }
        Slog.d(TAG) { "open: begin ok in ${System.currentTimeMillis() - t0} ms (send=$sendEnabled received=$openReceived)" }
        return Intent(ExtensionContract.ACTION_CALENDAR_SCREEN)
            .setPackage(ref.packageName)
            .putExtra(ExtensionContract.EXTRA_CALENDAR_SEND_ENABLED, sendEnabled)
            .putExtra(ExtensionContract.EXTRA_CALENDAR_OPEN_RECEIVED, openReceived)
    }

    /**
     * Notebook → calendar (Y3): hand [chunks] (from [TransferCaps.chunk], non-empty) to the held
     * extension with the [target] page on every one and the page px size they were authored in.
     * Throws [ExtensionCallException] (bind dead, timeout, refused).
     */
    suspend fun send(chunks: List<List<WireStroke>>, pageWidth: Float, pageHeight: Float, target: CalendarTarget) {
        val binding = held ?: throw ExtensionCallException("not open")
        require(chunks.isNotEmpty()) { "nothing to send" }
        val t0 = System.currentTimeMillis()
        var strokes = 0
        for ((i, chunk) in chunks.withIndex()) {
            val bundle = InkBundle(chunk, pageWidth, pageHeight)
            val last = i == chunks.lastIndex
            // The last chunk carries the whole placement — a Binder call cannot be cancelled, so a
            // budget that is too short reports a failure for ink that then lands anyway.
            binding.call(if (last) PLACE_TIMEOUT_MS else CALL_TIMEOUT_MS) { it.receiveInk(bundle, target, last) }
            strokes += chunk.size
        }
        Slog.d(TAG) { "send: ${chunks.size} chunks, $strokes strokes, target=${target.kind}/${target.date}/${target.half} in ${System.currentTimeMillis() - t0} ms" }
    }

    /** What [drainOutgoing] brought back: sanitized wire strokes + the page they were authored on + whether the caps cut it. */
    class Drained(val strokes: List<WireStroke>, val pageWidth: Float, val pageHeight: Float, val truncated: Boolean)

    /** Calendar → notebook (Y3): drain `takeOutgoing` chunk by chunk under [TransferCaps.Drain]. Throws [ExtensionCallException]. */
    suspend fun drainOutgoing(): Drained {
        val binding = held ?: throw ExtensionCallException("not open")
        val t0 = System.currentTimeMillis()
        val drain = TransferCaps.Drain()
        var pageWidth = 0f; var pageHeight = 0f
        var i = 0
        while (i <= ExtensionContract.TRANSFER_MAX_CHUNKS) {
            val bundle = binding.call(CALL_TIMEOUT_MS) { it.takeOutgoing(i) }
            if (i == 0) { pageWidth = bundle.pageWidth; pageHeight = bundle.pageHeight }
            if (!drain.add(bundle.strokes)) break
            i++
        }
        Slog.d(TAG) { "drainOutgoing: ${drain.chunks} chunks, ${drain.strokes.size} strokes${if (drain.truncated) " (truncated)" else ""} in ${System.currentTimeMillis() - t0} ms" }
        return Drained(drain.strokes, pageWidth, pageHeight, drain.truncated)
    }

    /** `end()` (best effort, ≤ [CALL_TIMEOUT_MS]), then unbind + revoke in `finally`. Idempotent. */
    suspend fun finish() {
        val binding = held ?: return
        held = null
        val store = storeBinder
        storeBinder = null
        try {
            if (!binding.isDead) binding.call(CALL_TIMEOUT_MS) { it.end() }
            Slog.d(TAG) { "finish: end ok" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "finish: end failed ${e.message}" }
        } finally {
            binding.close()
            store?.revoke()
        }
    }

    companion object {
        const val TAG = "CalendarClient"
        const val CALL_TIMEOUT_MS = 2_000L
        /** The last `receiveInk` chunk — the extension places the whole transfer inside this call.
         *  The pad's number to start; Y3 re-measures it on the Nomad. */
        const val PLACE_TIMEOUT_MS = 10_000L
    }
}
