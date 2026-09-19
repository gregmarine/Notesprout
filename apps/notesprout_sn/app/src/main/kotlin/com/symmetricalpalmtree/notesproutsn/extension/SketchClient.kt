package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.CancellationException

/**
 * The host's client for the one sketch screen (arc 43 / K4) — [DocumentEditorClient]'s shape for
 * SN's **tenth** point and its sixth screen-owning one: the operation is the showing of the
 * extension's screen, so the bind brackets it. One instance per showing; [open] / [finish] are
 * idempotent and the caller runs [finish] from its result callback **and** from `close()` while
 * still open.
 *
 * **No store.** Every other screen-owning point is lent an [IExtensionStore] at `begin()`, because
 * every other one has something of its own to remember — the pad's pages, the calendar's bookmark,
 * the reader's position, the editor's caret. A sketch has none: the pixels live in the host's
 * `.soil`, the bookmark is the notebook's own page, the undo history dies with the sitting, and
 * there is one tool with no settings. So [open] is shorter than the editor's by exactly the store:
 * mint one uid-bound [SketchHostBinder] over a fresh [SketchHostSession] → [ExtensionBinder.hold]
 * (signature re-checked at bind) → `begin(host)` ≤ [CALL_TIMEOUT_MS] → the screen Intent.
 *
 * **The Intent carries nothing here.** The caller adds the one global chrome flag
 * ([ExtensionContract.EXTRA_CHROME_HIDDEN]) and that is the whole of it — every byte this seam
 * moves crosses the host binder, which is what makes all of it uid-gated and revocable. The caller
 * launches with an `ActivityResultLauncher`: a plain `startActivity` leaves the extension's
 * `callingPackage` null and its screen refuses it.
 *
 * [finish]: `end()` ≤ [END_TIMEOUT_MS] in a `try`, then in `finally` unbind and revoke the host
 * binder (which drops the showing's windows and any half-received save with it). Every path:
 * result, cancel, the caller's death, a failed `begin`.
 *
 * Log tag [TAG] — counts + durations, never a pixel.
 */
class SketchClient(context: Context, val ref: ProviderRef) {

    private val appContext = context.applicationContext
    private var held: ExtensionBinder.HeldBinding<ISketch>? = null
    private var hostBinder: SketchHostBinder? = null

    val isOpen: Boolean get() = held != null

    /**
     * Mint the host binder, hold the bind, `begin(host)` and build the screen Intent — or null on
     * any failure (reason logged; everything opened so far released). [hooks] is the open
     * notebook's read/write half — see [SketchHostBinder.Hooks] for the thread contract it runs
     * under.
     */
    suspend fun open(hooks: SketchHostBinder.Hooks): Intent? {
        if (held != null) { Slog.d(TAG) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val host = try {
            val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
            SketchHostBinder(extUid, SketchHostSession(), hooks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageManager.NameNotFoundException) {
            Slog.d(TAG) { "open failed: package gone ${ref.packageName}" }
            return null
        }
        val binding = try {
            ExtensionBinder.hold(appContext, ref, SketchContract.ACTION_SKETCH, TAG,
                asInterface = { ISketch.Stub.asInterface(it) })
        } catch (e: CancellationException) {
            host.revoke(); throw e
        } catch (e: ExtensionCallException) {
            host.revoke()
            Slog.d(TAG) { "open failed: hold ${e.message}" }
            return null
        }
        held = binding
        hostBinder = host
        try {
            binding.call(CALL_TIMEOUT_MS) { it.begin(host) }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "open failed: begin ${e.message}" }
            finish()
            return null
        }
        Slog.d(TAG) { "open: begin ok in ${System.currentTimeMillis() - t0} ms" }
        // The package and nothing else, so the exported screen of some other app can never answer
        // this action. The chrome flag is the caller's to add — it is entry-level state, not this
        // showing's.
        return Intent(SketchContract.ACTION_SKETCH_SCREEN).setPackage(ref.packageName)
    }

    /**
     * `end()` (best effort, ≤ [END_TIMEOUT_MS]), then unbind + the revoke in `finally`. Idempotent.
     *
     * **Why `end()` gets its own, longer clock** (the editor's M4 reasoning, and it bites harder
     * here). `end()` is not a question — it is the screen's last chance to push pixels it has not
     * saved yet, and a parked save is re-pushed inside its handler, synchronously, through this
     * host binder. So the wait has to cover a whole page's rasters in 512 KiB chunks *plus* the
     * host's own write of them, which on the reconnect path includes [SketchHostHooks]' bounded wait for a
     * `.soil` that is still opening. A Binder call cannot be cancelled in any case — the timeout
     * only bounds how long we *wait* for it, so the cost of being generous is nothing and the cost
     * of being short is a drawing that has **no other copy at all**.
     */
    suspend fun finish() {
        val binding = held ?: return
        held = null
        val host = hostBinder
        hostBinder = null
        try {
            if (!binding.isDead) binding.call(END_TIMEOUT_MS) { it.end() }
            Slog.d(TAG) { "finish: end ok" }
        } catch (e: CancellationException) {
            throw e   // the caller's scope is gone — the finally below still releases the bind
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "finish: end failed ${e.message}" }
        } finally {
            binding.close()
            // Revoking the host binder also clears its session — the showing's windows and any
            // half-received save go with the bind, never outlive it.
            host?.revoke()
        }
    }

    companion object {
        const val TAG = "SketchClient"

        /** `begin` — a handshake and nothing else (the screen asks for its state afterwards). */
        const val CALL_TIMEOUT_MS = 2_000L

        /** `end` only — the screen's final flush rides it. See [finish]. */
        const val END_TIMEOUT_MS = 15_000L
    }
}
