package com.notesprout.android.notebook.ratta

import android.content.Context
import android.graphics.Rect
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Binder client for the Supernote (Ratta) firmware's stylus ink daemon.
 *
 * Ported verbatim from the hardware-validated PoC at `~/git/SupernoteDemo`
 * (org.iccnet.supernotedemo.SupernoteInk), itself a Kotlin port of the KOReader
 * `supernote_ink.lua` plugin. Validated on both a Nomad and a Manta.
 *
 * The firmware registers a Binder service ("service_myservice", legacy alias
 * "service.myservice") with interface token "android.demo.IMyService". The app claims
 * pen ownership, configures where the firmware may NOT paint (disable areas), sets the
 * active pen/eraser, and clears the EPDC ink overlay — all via raw Parcel transactions.
 * The firmware itself paints live stroke pixels to the e-ink overlay at sub-frame
 * latency; point data still arrives through the normal Android MotionEvent stream.
 *
 * Every method is a safe no-op when the firmware binder is absent, so callers can invoke
 * these unconditionally. Per the branch's no-fallback decision, failures are loud: every
 * firmware failure logs at Log.w (release-visible, never Slog) and fires [onFailure] so
 * the hosting view can surface a toast.
 *
 * Reference: https://github.com/plateaukao/supernote_draw (SupernoteInk.kt / HandWriteClient).
 * Pen codes are confirmed for Nomad (deviceType 3 / A5X2); the Manta runs the byte-identical
 * firmware build (measured 2026-08-08, see SUPERNOTE_SUPPORT_PLAN.md Phase 0).
 */
object SupernoteInk {
    private const val TAG = "SupernoteInk"

    private const val IFACE_TOKEN = "android.demo.IMyService"
    private const val APP_NAME = "notesprout"
    private val SERVICE_NAMES = arrayOf("service_myservice", "service.myservice")

    // Firmware transaction codes (from the decompiled HandWriteClient).
    private const val TX_WRITE_APP_INFO = 0
    private const val TX_DISABLE_AREA = 1
    private const val TX_PEN = 2
    private const val TX_DRAW_BUFFER = 6

    /** Pen type codes for the firmware's penTypeArray. */
    object Pen {
        const val NEEDLE = 10
        const val INK = 16          // highlighter is MARK
        const val MARK = 11
        const val CALLIGRAPHY = 15
    }

    /** Firmware color codes (grayscale on e-ink). */
    object Color {
        const val BLACK = 0
        const val DARK_GRAY = -101
        const val GRAY = -102
        const val LIGHT_GRAY = 254
    }

    /**
     * Fired (with a short human-readable message) on any firmware failure, so the hosting
     * view can toast it — the branch's "if something is broken, I want to know" contract.
     * Hosts should rate-limit to one toast per Activity instance.
     */
    @Volatile
    var onFailure: ((String) -> Unit)? = null

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        onFailure?.invoke(msg)
    }

    private var binder: IBinder? = null
    // Tri-state: null = untested, false = absent, true = present.
    private var available: Boolean? = null
    private var einkApiDumped = false

    @Synchronized
    fun isAvailable(): Boolean {
        available?.let { return it }
        binder = lookupBinder()
        val ok = binder != null
        available = ok
        if (!ok) Log.i(TAG, "service_myservice not present; firmware ink disabled")
        return ok
    }

    private fun lookupBinder(): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            for (name in SERVICE_NAMES) {
                val b = getService.invoke(null, name) as? IBinder
                if (b != null) {
                    Log.i(TAG, "found firmware binder \"$name\"")
                    return b
                }
            }
            null
        } catch (t: Throwable) {
            fail("binder lookup failed: ${t.message}")
            null
        }
    }

    /**
     * Run one transaction. [writeArgs] writes the per-call int payload after the
     * "interface token + app name" preamble that every transaction shares.
     */
    @Synchronized
    private fun transact(code: Int, writeArgs: (Parcel) -> Unit) {
        if (!isAvailable()) return
        var b = binder
        if (b == null || !b.isBinderAlive) {
            // Firmware service may have restarted; re-look-up once.
            b = lookupBinder()
            binder = b
            if (b == null) {
                available = false
                fail("firmware binder gone, marking unavailable")
                return
            }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_TOKEN)
            data.writeString(APP_NAME)
            writeArgs(data)
            b.transact(code, data, reply, 0)
        } catch (t: Throwable) {
            // DeadObjectException etc. — drop the cached proxy so the next call re-looks up.
            fail("transact($code) failed: ${t.message}")
            binder = null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** tx=0 WRITE_APP_INFO — claim pen ownership for this app. */
    fun claimPen() = transact(TX_WRITE_APP_INFO) {
        it.writeInt(0)
        it.writeInt(0)
    }

    /** tx=2 PEN — set active pen type, EMR size, and color. */
    fun setPen(type: Int, sizeEmr: Int, color: Int) = transact(TX_PEN) {
        it.writeInt(type)
        it.writeInt(sizeEmr)
        it.writeInt(color)
    }

    /** tx=2 PEN — configure the firmware eraser (type 1 = round, 3 = rectangular). */
    fun setEraser(rectangular: Boolean, sizeEmr: Int) = transact(TX_PEN) {
        it.writeInt(if (rectangular) 3 else 1)
        it.writeInt(sizeEmr)
        it.writeInt(255)
    }

    /** tx=6 DRAW_BUFFER — clear the EPDC ink overlay (after baking strokes into our layer). */
    fun clearAll() = transact(TX_DRAW_BUFFER) {
        it.writeInt(255)
        it.writeInt(0)
    }

    /** tx=1 DISABLE_AREA — one full-screen rect where the firmware must not paint. */
    fun setFullScreenDisable(width: Int, height: Int) = transact(TX_DISABLE_AREA) {
        it.writeInt(1)          // rect count
        it.writeInt(0)          // x
        it.writeInt(0)          // y
        it.writeInt(width)
        it.writeInt(height)
        it.writeInt(0)          // reserved / flags
    }

    /** tx=1 DISABLE_AREA — keep firmware ink off the given rects (screen coords, e.g. our toolbar). */
    fun setDisableAreas(rects: List<Rect>) = transact(TX_DISABLE_AREA) { p ->
        p.writeInt(rects.size)
        for (r in rects) {
            p.writeInt(r.left)
            p.writeInt(r.top)
            p.writeInt(r.width())
            p.writeInt(r.height())
            p.writeInt(0)
        }
    }

    /** tx=1 DISABLE_AREA — clear all disable areas (firmware may paint everywhere). */
    fun clearDisableAreas() = transact(TX_DISABLE_AREA) {
        it.writeInt(0)          // zero rects
    }

    private fun einkService(context: Context): Any? =
        try { context.getSystemService("eink") } catch (t: Throwable) { null }

    /**
     * Enable Regal (E-Ink anti-ghosting waveform). This is the standard remedy for a
     * partial-refresh "ghost" that only clears on a later refresh — it lets the firmware
     * clean residual pixels without a full-screen flash. [level] semantics are unknown;
     * 0 is a safe default and the call no-ops if the signature doesn't match.
     */
    fun enableAutoRegal(context: Context, enable: Boolean, level: Int = 0) {
        if (!isAvailable()) return
        val eink = einkService(context) ?: return
        try {
            eink.javaClass.getMethod(
                "enableAutoRegal",
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(eink, enable, level)
            Log.i(TAG, "enableAutoRegal($enable,$level) ok")
        } catch (t: Throwable) {
            fail("enableAutoRegal failed: ${t.message}")
        }
    }

    /**
     * Refresh the e-ink screen. [full] requests a full (flashy) refresh; false is a lighter
     * partial refresh. [mode] waveform is firmware-defined (0 default). Never call per stroke
     * or per handoff — it flashes; enableAutoRegal at setup is what keeps handoffs clean.
     */
    fun screenRefresh(context: Context, full: Boolean, mode: Int = 0) {
        if (!isAvailable()) return
        val eink = einkService(context) ?: return
        try {
            eink.javaClass.getMethod(
                "screenRefresh",
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(eink, full, mode)
            Log.i(TAG, "screenRefresh($full,$mode) ok")
        } catch (t: Throwable) {
            fail("screenRefresh failed: ${t.message}")
        }
    }

    /** Full-frame clean refresh (flashes the whole screen). Acceptable on clear / erase-all only. */
    fun sendOneFullFrame(context: Context) {
        if (!isAvailable()) return
        val eink = einkService(context) ?: return
        try {
            eink.javaClass.getMethod("sendOneFullFrame").invoke(eink)
            Log.i(TAG, "sendOneFullFrame ok")
        } catch (t: Throwable) {
            fail("sendOneFullFrame failed: ${t.message}")
        }
    }

    /**
     * Reflection on getSystemService("eink").enableFullUiAuto(boolean). Required so a
     * third-party app gets ink painted everywhere, not just inside whitelisted firmware
     * apps. Phase 0 dumped this firmware's EinkManager and confirmed the method exists;
     * the guard stays to protect against future firmware.
     */
    fun enableFullUiAuto(context: Context, enable: Boolean) {
        if (!isAvailable()) return
        try {
            val eink = context.getSystemService("eink") ?: run {
                fail("eink system service not present")
                return
            }
            if (!einkApiDumped) {
                einkApiDumped = true
                // One-time diagnostic: log the eink service's full API surface.
                val sigs = eink.javaClass.methods
                    .filter { it.declaringClass != Any::class.java }
                    .map { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }
                    .distinct().sorted()
                Log.i(TAG, "eink=${eink.javaClass.name} methods:\n${sigs.joinToString("\n")}")
            }
            val m = eink.javaClass.getMethod("enableFullUiAuto", Boolean::class.javaPrimitiveType)
            m.invoke(eink, enable)
            Log.i(TAG, "enableFullUiAuto($enable) ok via ${eink.javaClass.name}")
        } catch (t: Throwable) {
            fail("enableFullUiAuto unavailable: ${t.message}")
        }
    }
}
