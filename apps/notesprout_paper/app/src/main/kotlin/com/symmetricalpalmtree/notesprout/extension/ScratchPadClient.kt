package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesprout.data.extstore.ExtensionStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The pad's target page would cross the store's value cap (`SCRATCH_PAGE_FULL`) — nothing was placed. */
class ScratchPageFullException(cause: Throwable) : ExtensionCallException(ExtensionContract.SCRATCH_PAGE_FULL, cause)

/**
 * The host's client for the one scratch pad (arc 6 / S0) — the first **held** bind: the operation is
 * the showing of the extension's screen, so the bind brackets it (rule 26). One instance per calling
 * screen; [open] / [finish] are idempotent and the caller runs [finish] from its result callback
 * **and** from `onDestroy` while still open.
 *
 * [open]: `ExtensionStores.open` on IO (pre-open rule — the cold KDF never sits inside a call
 * timeout) → mint one uid-bound [ExtensionStoreBinder] → [ExtensionBinder.hold] (signature
 * re-checked at bind) → `begin(store)` ≤ [CALL_TIMEOUT_MS] → the screen Intent
 * ([ExtensionContract.ACTION_SCRATCH_PAD_SCREEN], `setPackage(ref.packageName)`, the two boolean
 * extras — **nothing else rides the Intent**, the ink goes through the held service in S2). Returns
 * null on any failure (reason logged; everything opened so far is released) — the caller shows the
 * core's `scratch_failed` dialog. The caller launches the Intent with an `ActivityResultLauncher`
 * (`startActivityForResult`-style, so the extension's `callingPackage` check can pass — risk 2).
 *
 * [finish]: `end()` ≤ [CALL_TIMEOUT_MS] in a `try`, then unbind + revoke the store binder in
 * `finally` — every path: result, cancel, the caller's death, a failed `begin`.
 *
 * [send] (S2): notebook → pad — the chunks through `receiveInk`, one call ≤ [CALL_TIMEOUT_MS] each (the last one, which carries the placement, ≤ [PLACE_TIMEOUT_MS])
 * on the same held bind, `placement` + `last` on every chunk; the extension's
 * `IllegalStateException(SCRATCH_PAGE_FULL)` is typed as [ScratchPageFullException]. Called
 * **between** [open] and the launch, so the pad opens on the received page. [drainOutgoing] (S2):
 * pad → notebook after `RESULT_SCRATCH_SEND` — `takeOutgoing(i)` until an empty bundle, the summed
 * caps or the chunk budget ([TransferCaps.Drain]); every chunk is `requireValid` at unmarshal and
 * sanitized. Log tag [TAG] — counts + durations, never a stroke.
 */
class ScratchPadClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<IScratchPad>? = null
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
            ExtensionBinder.hold(appContext, ref, ExtensionContract.ACTION_SCRATCH_PAD, TAG,
                asInterface = { IScratchPad.Stub.asInterface(it) })
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
        return Intent(ExtensionContract.ACTION_SCRATCH_PAD_SCREEN)
            .setPackage(ref.packageName)
            .putExtra(ExtensionContract.EXTRA_SCRATCH_SEND_ENABLED, sendEnabled)
            .putExtra(ExtensionContract.EXTRA_SCRATCH_OPEN_RECEIVED, openReceived)
    }

    /**
     * Notebook → pad: hand [chunks] (from [TransferCaps.chunk], non-empty) to the held extension with
     * [placement] (`PLACEMENT_NEW_PAGE` / `PLACEMENT_CURRENT_PAGE`) and the page px size they were
     * authored in. Throws [ScratchPageFullException] (the target page would cross the store cap —
     * nothing placed) or [ExtensionCallException] (bind dead, timeout, refused).
     */
    suspend fun send(chunks: List<List<PaperStroke>>, pageWidth: Float, pageHeight: Float, placement: Int) {
        val binding = held ?: throw ExtensionCallException("not open")
        require(chunks.isNotEmpty()) { "nothing to send" }
        val t0 = System.currentTimeMillis()
        var strokes = 0
        for ((i, chunk) in chunks.withIndex()) {
            val bundle = InkBundle(chunk, pageWidth, pageHeight)
            val last = i == chunks.lastIndex
            // The last chunk carries the whole placement (read + decode + re-encode + write of a page up to
            // 4 MiB on an e-ink CPU) — a Binder call cannot be cancelled, so a budget that is too short
            // reports a failure for ink that then lands anyway. PLACE_TIMEOUT_MS for that one call.
            binding.call(if (last) PLACE_TIMEOUT_MS else CALL_TIMEOUT_MS) {
                try {
                    it.receiveInk(bundle, placement, last)
                } catch (e: IllegalStateException) {
                    if (e.message == ExtensionContract.SCRATCH_PAGE_FULL) throw ScratchPageFullException(e)
                    throw e
                }
            }
            strokes += chunk.size
        }
        Slog.d(TAG) { "send: ${chunks.size} chunks, $strokes strokes, placement=$placement in ${System.currentTimeMillis() - t0} ms" }
    }

    /** What [drainOutgoing] brought back: sanitized wire strokes + the page they were authored on + whether the caps cut it. */
    class Drained(val strokes: List<PaperStroke>, val pageWidth: Float, val pageHeight: Float, val truncated: Boolean)

    /** Pad → notebook: drain `takeOutgoing` chunk by chunk under [TransferCaps.Drain]. Throws [ExtensionCallException]. */
    suspend fun drainOutgoing(): Drained {
        val binding = held ?: throw ExtensionCallException("not open")
        val t0 = System.currentTimeMillis()
        val drain = TransferCaps.Drain()
        var pageWidth = 0f; var pageHeight = 0f
        var i = 0
        // One probe past the chunk budget: a non-empty chunk there means something was left behind.
        while (i <= ExtensionContract.TRANSFER_MAX_CHUNKS) {
            val bundle = binding.call(CALL_TIMEOUT_MS) { it.takeOutgoing(i) }
            if (i == 0) { pageWidth = bundle.pageWidth; pageHeight = bundle.pageHeight }
            if (!drain.add(bundle.strokes)) break
            i++
        }
        Slog.d(TAG) { "drainOutgoing: ${drain.chunks} chunks, ${drain.strokes.size} strokes${if (drain.truncated) " (truncated)" else ""} in ${System.currentTimeMillis() - t0} ms" }
        return Drained(drain.strokes, pageWidth, pageHeight, drain.truncated)
    }

    /** `end()` (best effort, ≤ 2 s), then unbind + revoke in `finally`. Idempotent. */
    suspend fun finish() {
        val binding = held ?: return
        held = null
        val store = storeBinder
        storeBinder = null
        try {
            if (!binding.isDead) binding.call(CALL_TIMEOUT_MS) { it.end() }
            Slog.d(TAG) { "finish: end ok" }
        } catch (e: CancellationException) {
            throw e   // the caller's scope is gone — the finally below still releases the bind
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "finish: end failed ${e.message}" }
        } finally {
            binding.close()
            store?.revoke()
        }
    }

    companion object {
        const val TAG = "ScratchPadClient"
        const val CALL_TIMEOUT_MS = 2_000L
        /** The last `receiveInk` chunk — the extension places the whole transfer inside this call (S3 review). */
        const val PLACE_TIMEOUT_MS = 10_000L
    }
}
