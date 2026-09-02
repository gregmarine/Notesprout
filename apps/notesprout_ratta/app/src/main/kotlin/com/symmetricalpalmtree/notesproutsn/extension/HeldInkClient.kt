package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

/**
 * What a [HeldInkClient] needs to know about **one** ink-carrying screen-owning point: its two
 * actions, its two Intent extras, its interface, its four calls and its three budgets. One
 * implementation per point (the client's own companion), and nothing else — everything that is a
 * *rule* rather than a name lives in [HeldInkClient] itself, once.
 *
 * [P] is the point's placement type: the scratch pad's is an `Int` (`PLACEMENT_*`), the calendar's a
 * [CalendarTarget]. It rides **every** chunk of a transfer in both, which is why it is a parameter
 * of [receiveInk] and not of the last call alone.
 */
interface HeldInkPoint<I : Any, P> {

    /** The log tag every line this point's client writes goes under — counts + durations, never a stroke. */
    val tag: String

    /** The service action the held bind is made on (`ACTION_SCRATCH_PAD` / `ACTION_CALENDAR`). */
    val serviceAction: String

    /** The extension-owned screen's action, launched for a result and never with anything else on it. */
    val screenAction: String

    /** The boolean extra saying this caller can be sent ink back — the notebook. */
    val sendEnabledExtra: String

    /** The boolean extra saying ink was handed over before the launch. */
    val openReceivedExtra: String

    /** Every call but the two below: a state read, so 2 s on both points. */
    val callTimeoutMs: Long

    /**
     * The **last** `receiveInk` chunk: the extension places the whole transfer inside that one call,
     * on an e-ink CPU. A Binder call cannot be cancelled, so a budget that is too short reports a
     * failure for ink that then lands anyway — which is why a timeout here is settled, not believed.
     */
    val placeTimeoutMs: Long

    /** How long [HeldInkClient.finish] waits for a call a timeout orphaned before it tears down. */
    val settleTimeoutMs: Long

    fun asInterface(binder: IBinder): I?

    fun begin(iface: I, store: IExtensionStore)

    fun receiveInk(iface: I, chunk: InkBundle, placement: P, last: Boolean)

    fun takeOutgoing(iface: I, chunkIndex: Int): InkBundle

    fun end(iface: I)

    /** How the placement reads in the send log line. Names and numbers only — never a stroke. */
    fun describe(placement: P): String
}

/** What [HeldInkClient.drainOutgoing] brought back: sanitized wire strokes + the page they were
 *  authored on + whether the caps cut it. One class for both points — the two transfers differ in
 *  what they are sent, never in what comes back. */
class DrainedInk(
    val strokes: List<WireStroke>,
    val pageWidth: Float,
    val pageHeight: Float,
    val truncated: Boolean,
)

/**
 * The host's client for **one showing** of an ink-carrying, screen-owning extension point — SN's
 * only bind held across more than one call, because the operation *is* the showing. One instance per
 * calling screen; [open] / [finish] are idempotent and the caller runs [finish] from its result
 * callback **and** from `onDestroy` while still open.
 *
 * The scratch pad (arc 11 / J3) and the calendar (arc 23 / Y1) are the two points, and this is one
 * implementation of them, not two: their held-bind lifecycle, chunking, budgets, settle rule and
 * teardown were identical to the line, and a fix applied to one copy and not the other is exactly
 * the `RattaNotebookView` sibling-copy trap. Everything that differs is a name, and every name is a
 * [HeldInkPoint].
 *
 * [open]: `ExtensionStores.open` on IO (the pre-open rule — a cold KDF is ≈ 3 s on the Nomad and must
 * never sit inside a call timeout) → mint one uid-bound [ExtensionStoreBinder] → [ExtensionBinder.hold]
 * (signature re-checked at bind) → `begin(store)` ≤ [HeldInkPoint.callTimeoutMs] → the screen Intent
 * (the point's screen action, `setPackage(ref.packageName)`, the two boolean extras — **nothing else
 * rides the Intent**, the ink goes through the held service). Returns null on any failure (reason
 * logged; everything opened so far is released). The caller launches the Intent with an
 * `ActivityResultLauncher` — a plain `startActivity` leaves the extension's `callingPackage` null and
 * its screen refuses it.
 *
 * [send]: host → extension — the chunks through `receiveInk` on the same held bind, the placement +
 * `last` on every chunk; the **last** call takes [HeldInkPoint.placeTimeoutMs], and a timeout there
 * is [ExtensionBinder.HeldBinding.settle]d rather than believed: a late success IS a success, because
 * the ink is on the page (arc 23 / Y4).
 *
 * [drainOutgoing]: extension → host after the point's send result — `takeOutgoing(i)` until an empty
 * bundle, the summed caps or the chunk budget ([TransferCaps.Drain]); every chunk is `requireValid`
 * at unmarshal and sanitized.
 *
 * [finish]: settle any orphaned call, then `end()` ≤ the call budget in a `try`, then unbind + revoke
 * the store binder in `finally` — every path: result, cancel, the caller's death, a failed `begin`.
 */
open class HeldInkClient<I : Any, P>(
    context: Context,
    val ref: ProviderRef,
    private val point: HeldInkPoint<I, P>,
) {

    private val appContext = context.applicationContext
    private val tag = point.tag
    private var held: ExtensionBinder.HeldBinding<I>? = null
    private var storeBinder: ExtensionStoreBinder? = null

    val isOpen: Boolean get() = held != null

    /** Pre-open the store, hold the bind, `begin(store)`, and build the screen Intent — or null (logged). */
    suspend fun open(sendEnabled: Boolean, openReceived: Boolean): Intent? {
        if (held != null) { Slog.d(tag) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val store: ExtensionStoreBinder
        try {
            val db = withContext(Dispatchers.IO) { ExtensionStores.open(appContext, ref.packageName) }
            val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
            store = ExtensionStoreBinder(db, extUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageManager.NameNotFoundException) {
            Slog.d(tag) { "open failed: package gone ${ref.packageName}" }
            return null
        } catch (e: Exception) {
            Slog.d(tag) { "open failed: store open ${e.javaClass.simpleName}: ${e.message}" }
            return null
        }
        val binding = try {
            ExtensionBinder.hold(appContext, ref, point.serviceAction, tag,
                asInterface = { point.asInterface(it) })
        } catch (e: CancellationException) {
            store.revoke(); throw e
        } catch (e: ExtensionCallException) {
            store.revoke()
            Slog.d(tag) { "open failed: hold ${e.message}" }
            return null
        }
        held = binding
        storeBinder = store
        try {
            binding.call(point.callTimeoutMs) { point.begin(it, store) }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(tag) { "open failed: begin ${e.message}" }
            finish()
            return null
        }
        Slog.d(tag) { "open: begin ok in ${System.currentTimeMillis() - t0} ms (send=$sendEnabled received=$openReceived)" }
        return Intent(point.screenAction)
            .setPackage(ref.packageName)
            .putExtra(point.sendEnabledExtra, sendEnabled)
            .putExtra(point.openReceivedExtra, openReceived)
    }

    /**
     * Host → extension: hand [chunks] (from [TransferCaps.chunk], non-empty) to the held extension
     * with [placement] on every one and the page px size they were authored in. Throws
     * [ExtensionCallException] (bind dead, timeout, refused).
     */
    suspend fun send(chunks: List<List<WireStroke>>, pageWidth: Float, pageHeight: Float, placement: P) {
        val binding = held ?: throw ExtensionCallException("not open")
        require(chunks.isNotEmpty()) { "nothing to send" }
        val t0 = System.currentTimeMillis()
        var strokes = 0
        for ((i, chunk) in chunks.withIndex()) {
            val bundle = InkBundle(chunk, pageWidth, pageHeight)
            val last = i == chunks.lastIndex
            // The last chunk carries the whole placement (the rows, the renumber and one INSERT per
            // stroke, in one transaction on an e-ink CPU) — a Binder call cannot be cancelled, so a
            // budget that is too short reports a failure for ink that then lands anyway.
            try {
                binding.call(if (last) point.placeTimeoutMs else point.callTimeoutMs) {
                    point.receiveInk(it, bundle, placement, last)
                }
            } catch (e: ExtensionCallException) {
                // A timed-out placement is still running — the call cannot be cancelled. Wait for it
                // once more: a late success IS a success (the ink is on the page), and only a call
                // that actually threw, or is still running past a second budget, is a failure.
                if (last && e.cause is TimeoutCancellationException &&
                    binding.settle(point.placeTimeoutMs) == ExtensionBinder.HeldBinding.Settled.OK) {
                    Slog.d(tag) { "send: the placement landed late (past ${point.placeTimeoutMs} ms)" }
                } else throw e
            }
            strokes += chunk.size
        }
        Slog.d(tag) {
            "send: ${chunks.size} chunks, $strokes strokes, ${point.describe(placement)} " +
                "in ${System.currentTimeMillis() - t0} ms"
        }
    }

    /** Extension → host: drain `takeOutgoing` chunk by chunk under [TransferCaps.Drain]. Throws
     *  [ExtensionCallException]. */
    suspend fun drainOutgoing(): DrainedInk {
        val binding = held ?: throw ExtensionCallException("not open")
        val t0 = System.currentTimeMillis()
        val drain = TransferCaps.Drain()
        var pageWidth = 0f; var pageHeight = 0f
        var i = 0
        // One probe past the chunk budget: a non-empty chunk there means something was left behind.
        while (i <= ExtensionContract.TRANSFER_MAX_CHUNKS) {
            val bundle = binding.call(point.callTimeoutMs) { point.takeOutgoing(it, i) }
            if (i == 0) { pageWidth = bundle.pageWidth; pageHeight = bundle.pageHeight }
            if (!drain.add(bundle.strokes)) break
            i++
        }
        Slog.d(tag) { "drainOutgoing: ${drain.chunks} chunks, ${drain.strokes.size} strokes${if (drain.truncated) " (truncated)" else ""} in ${System.currentTimeMillis() - t0} ms" }
        return DrainedInk(drain.strokes, pageWidth, pageHeight, drain.truncated)
    }

    /** Settle any orphaned call (a placement still running past its budget — the store must not be
     *  revoked under it), then `end()` (best effort, ≤ [HeldInkPoint.callTimeoutMs]), then unbind +
     *  revoke in `finally`. Idempotent. */
    suspend fun finish() {
        val binding = held ?: return
        held = null
        val store = storeBinder
        storeBinder = null
        try {
            if (!binding.isDead) binding.settle(point.settleTimeoutMs)
            if (!binding.isDead) binding.call(point.callTimeoutMs) { point.end(it) }
            Slog.d(tag) { "finish: end ok" }
        } catch (e: CancellationException) {
            throw e   // the caller's scope is gone — the finally below still releases the bind
        } catch (e: ExtensionCallException) {
            Slog.d(tag) { "finish: end failed ${e.message}" }
        } finally {
            binding.close()
            store?.revoke()
        }
    }
}
