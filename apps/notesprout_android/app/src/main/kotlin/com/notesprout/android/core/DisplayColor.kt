package com.notesprout.android.core

import android.os.Build
import java.util.Locale

/**
 * Whether this device's screen can actually show colour.
 *
 * Colour ink is offered only where it can be seen. Everywhere else the palette is hidden and every
 * stroke renders black — **without touching the stored ink**, so a notebook written in red on a
 * colour panel opens on a greyscale one as legible black and returns to red when it goes back. The
 * ink is data; this is only presentation.
 *
 * Rendering a colour *as colour* on a greyscale panel would be the worse failure: the hardware
 * dithers it to a mid-grey, and a yellow or light-green note becomes nearly invisible on white
 * paper. Black is the honest fallback.
 *
 * Resolved once at process start ([init] from `NotesproutApplication.onCreate`) — the screen cannot
 * change underneath a running process, and [supportsColor] is read on every stroke draw.
 */
object DisplayColor {

    /**
     * BOOX colour models, as a backstop for [Device.getColorType] returning 0 when the reflective
     * hidden-API call fails. Matched as case-insensitive substrings of [Build.MODEL].
     *
     * **Extend this when a colour device reports 0.** It is deliberately an allowlist rather than a
     * pattern: "ends in C" would catch NoteAir4C and TabXC but is a coincidence of naming, not a
     * rule, and getting it wrong in the permissive direction means shipping invisible ink.
     */
    private val BOOX_COLOR_MODELS = listOf(
        "NoteAir5C", "NoteAir4C", "NoteAir3C", "NoteAir2P",  // NoteAir C-series
        "TabXC", "Tab X C", "TabUltraC", "Tab Ultra C",
        "GoColor7", "Go Color 7",
        "NovaColor", "Nova3Color", "Poke5",
    )

    /**
     * Non-BOOX devices that are e-ink and greyscale. Everything else that is not a BOOX is assumed to
     * be an ordinary LCD/OLED tablet, which can obviously show colour.
     */
    private val BW_EINK_MODELS = listOf("Supernote", "Nomad", "Manta")

    @Volatile
    private var resolved: Boolean? = null

    /**
     * True when colour ink should be shown. Defaults to **true** if [init] somehow has not run: on a
     * colour device that is correct, and on a greyscale one it degrades to today's pre-colour
     * behaviour (ink dithers) rather than hiding a feature the user paid for. `Application.onCreate`
     * runs before any Activity, so in practice this is always resolved.
     */
    val supportsColor: Boolean
        get() = resolved ?: true

    /** Resolve once. Safe to call repeatedly; only the first call does work. */
    fun init(context: android.content.Context) {
        if (resolved != null) return
        val forced = debugOverride(context)
        resolved = (forced ?: detect()).also {
            Slog.d("DisplayColor") { "model=${Build.MODEL} supportsColor=$it forced=$forced" }
        }
    }

    /**
     * Debug-only override, so the greyscale path can be exercised **on a colour device**.
     *
     * Half of this feature only appears on hardware that cannot show it, and the colour devices are
     * Tier 2 while the greyscale fleet is Tier 1 — without this the fallback would ship having never
     * been seen. Set it from a host shell and relaunch:
     *
     * ```
     * adb shell "run-as com.notesprout.android.dev sh -c \
     *   'mkdir -p shared_prefs; cat > shared_prefs/notesprout_debug.xml'" <<< \
     *   "<map><string name=\"force_color\">false</string></map>"
     * ```
     *
     * Absent (the normal case) or on a release build it returns null and real detection runs.
     */
    private fun debugOverride(context: android.content.Context): Boolean? {
        if (!com.notesprout.android.BuildConfig.DEBUG) return null
        return runCatching {
            context.getSharedPreferences("notesprout_debug", android.content.Context.MODE_PRIVATE)
                .getString("force_color", null)
                ?.toBooleanStrictOrNull()
        }.getOrNull()
    }

    private fun detect(): Boolean {
        val model = Build.MODEL.lowercase(Locale.ROOT)

        if (isBooxDevice()) {
            // Primary signal: the Onyx SDK's own colour-panel type. Verified on a NoteAir5C, where it
            // reports 1 (impl SDMDevice); the base implementation returns 0. No system property
            // exposes this — the SDK call is the only signal there is.
            val colorType = runCatching {
                com.onyx.android.sdk.device.Device.currentDevice().colorType
            }.getOrNull()
            if (colorType != null && colorType != 0) return true
            // 0 is ambiguous: a genuinely greyscale panel, or a reflection failure. Hence the list.
            return BOOX_COLOR_MODELS.any { model.contains(it.lowercase(Locale.ROOT)) }
        }

        if (BW_EINK_MODELS.any { model.contains(it.lowercase(Locale.ROOT)) }) return false
        return true
    }
}
