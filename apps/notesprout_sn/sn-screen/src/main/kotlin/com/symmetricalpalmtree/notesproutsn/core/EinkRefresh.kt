package com.symmetricalpalmtree.notesproutsn.core

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Method
import java.util.Locale

/**
 * A **full panel refresh**, asked of the Supernote firmware's own e-ink service (2026-09-19).
 *
 * E-ink ghosting is the compositor's problem, not the app's: a partial update leaves the previous
 * frame's darker pixels faintly behind, and a page of high-contrast dots — which is exactly what
 * the Supernote panel shows since the pencil went direct on `/dev/ebc` and the on-screen page
 * became a dither (g-paper 0.1.42) — accumulates it fast. A full refresh clears the panel and
 * repaints it, which is the firmware's own answer to that; there is no g-paper call for it,
 * because it is a property of the **screen**, not of the paper.
 *
 * **What the probe found, and what this is.** `ServiceManager.getService("eink")` answers a live
 * binder on the Nomad, `android.os.IEinkManager$Stub.asInterface` wraps it, and
 * `screenRefresh(boolean, int)` returns without throwing for an ordinary app — no permission, no
 * system signature. None of those three names is in the public SDK, so every step is reflection
 * and every step may be absent on the next firmware: this object's whole contract is **it either
 * refreshes or it does nothing, and it never costs more than one log line either way**.
 *
 * - **Ratta only.** [isRattaDevice] gates it — Notesprout SN is Supernote-only, but the check is
 *   written down here rather than assumed, because nothing else in this file would fail safely on
 *   hardware that has some *other* service called "eink".
 * - **One refusal, remembered.** The first failure of any kind — no service, no class, no method,
 *   a throw from the call — sets [refused] and is the only line ever logged about it. A page turn
 *   that cannot refresh must not pay a reflection lookup, a `Log.w` and a dead Binder hop on every
 *   turn thereafter.
 * - **Never a crash.** `Throwable` is caught, deliberately wider than the reflection checked
 *   exceptions: an `InvocationTargetException` wrapping anything at all, a `DeadObjectException`,
 *   a `NoSuchMethodError` from a firmware whose signature differs — none of them is worth a page
 *   turn.
 *
 * The resolved proxy and method are cached after the first success. A proxy that later dies takes
 * the ordinary refusal path (one line, then silence) rather than being re-resolved — a service
 * that went away mid-session is not one to keep asking.
 *
 * Call it on the **main thread, after the frame is on the glass**: the refresh is about what is
 * already showing, and asking before the present would clear the panel and then paint the new page
 * onto it partially — the very thing it exists to avoid.
 */
object EinkRefresh {

    private const val TAG = "EinkRefresh"

    private const val SERVICE_MANAGER = "android.os.ServiceManager"
    private const val EINK_SERVICE = "eink"
    private const val EINK_STUB = "android.os.IEinkManager\$Stub"

    /**
     * `screenRefresh`'s second argument — **the probe used 0 and this keeps it**.
     *
     * The first argument is `afterWindowHide` (false: refresh now, not when a window goes away).
     * The second is an int whose meaning is not published anywhere we can read — a mode, a
     * waveform, a count of frames, all guesses. 0 is what the probe called it with and what it
     * returned cleanly from. **The user's walk decides whether it should be something else**; if a
     * refresh is visibly wrong (a flash where a clean wipe was wanted, or nothing at all), this is
     * the first constant to try other values in.
     */
    // 1 = a full refresh regardless of what the compositor thinks changed — measured on the Nomad
    // 2026-09-19 by cycling (hide, n) through the probe: every variant with n = 1 refreshed, every
    // variant with n = 0, 2 or 3 did not once the panel had been drawn on directly; `hide` made no
    // difference either way.
    const val MODE = 1

    @Volatile private var refused = false
    @Volatile private var manager: Any? = null
    @Volatile private var screenRefresh: Method? = null

    /**
     * Ask the panel for a full refresh. Answers whether the call was made — never whether the
     * panel actually cleared, which nothing here can see.
     *
     * [context] is unused: the door is a framework service fetched by name, not a context service.
     * It is on the signature so every caller is an ordinary screen helper call and so a future
     * firmware that *does* publish this through `getSystemService` needs no caller changed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun fullRefresh(context: Context): Boolean {
        if (refused || !isRattaDevice()) return false
        val method = screenRefresh ?: resolve() ?: return false
        val target = manager ?: return false
        val t0 = SystemClock.elapsedRealtime()
        return try {
            method.invoke(target, false, MODE)
            Slog.d(TAG) { "full panel refresh in ${SystemClock.elapsedRealtime() - t0} ms" }
            true
        } catch (t: Throwable) {
            refuse("the panel refused a full refresh", t)
            false
        }
    }

    /** The one lookup, cached on success and refused for good on failure. */
    private fun resolve(): Method? {
        return try {
            val getService = Class.forName(SERVICE_MANAGER)
                .getMethod("getService", String::class.java)
            val binder = getService.invoke(null, EINK_SERVICE) as? IBinder
            if (binder == null) {
                refuse("this device has no \"$EINK_SERVICE\" service", null)
                return null
            }
            val stub = Class.forName(EINK_STUB)
            val proxy = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            if (proxy == null) {
                refuse("the \"$EINK_SERVICE\" binder would not wrap", null)
                return null
            }
            val method = proxy.javaClass.getMethod(
                "screenRefresh",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            manager = proxy
            screenRefresh = method
            method
        } catch (t: Throwable) {
            refuse("the panel's refresh door could not be opened", t)
            null
        }
    }

    /** One line, once, for the life of the process — then silence. */
    private fun refuse(what: String, t: Throwable?) {
        refused = true
        manager = null
        screenRefresh = null
        if (t == null) Log.w(TAG, "$what; page turns will not refresh it")
        else Log.w(TAG, "$what; page turns will not refresh it", t)
    }

    /**
     * True on Supernote hardware. `Build.MANUFACTURER` is `"Supernote"` across the verified fleet —
     * never `"ratta"`, which appears in no build property, and never anything that tells a Manta
     * from a Nomad (every `ro.product.*` is identical on the two). g-paper's own copy of this rule
     * is `internal` to its Ratta module, so it is written down once more here rather than reached
     * for.
     */
    private fun isRattaDevice(): Boolean =
        Build.MANUFACTURER.lowercase(Locale.ROOT).contains("supernote")
}
