package com.notesprout.android.core

import android.graphics.Color

/**
 * Ink colour: the `#RRGGBB` strings carried by [com.notesprout.android.data.LiveStroke.color] and
 * the `color` column, and the single place they become an Android colour int for painting.
 *
 * **[paintColor] is the one chokepoint every render site must use.** Stored ink is *never* rewritten
 * to match a device — a stroke authored in red on a colour panel stays red in the `.soil` forever and
 * merely *renders* black on a greyscale one. Keeping the fallback here (rather than at each of the
 * ~25 draw sites) is what makes that guarantee auditable: data in, device-appropriate pixels out.
 *
 * ### The Kaleido brightness floor
 * The Phase-0 spike (NoteAir5C, 2026-07-31) found the Onyx raw-drawing overlay renders colour fine —
 * including under the pinned `HAND_WRITING_REPAINT_MODE` fast waveform — but draws a colour as black
 * once its **dominant RGB channel drops below roughly 180**. Every probe colour at ≥184 survived; the
 * sole failure sat at 122.
 *
 * **This is a live-preview limit, not a storage or rendering one.** A below-floor stroke is captured,
 * stored and drawn in its true colour; it merely *looks* black while the stylus is moving, and
 * corrects itself the next time the committed layer repaints. So [isOverlaySafe] answers "will this
 * preview honestly while writing", which is worth telling the user about but is never a reason to
 * refuse a colour.
 */
object InkColor {

    /** Black — the default ink, and the fallback for anything unparseable. */
    const val DEFAULT = "#000000"

    /**
     * Observed floor for the dominant RGB channel below which the Onyx overlay paints black on a
     * Kaleido panel. Empirical, from the Phase-0 device sweep — not a vendor-documented constant.
     */
    const val MIN_DOMINANT_CHANNEL = 180

    /** Parse `#RRGGBB` / `#AARRGGBB` to a colour int, falling back to black on anything malformed. */
    fun toInt(hex: String): Int = runCatching { Color.parseColor(hex) }.getOrDefault(Color.BLACK)

    /** Format a colour int as `#RRGGBB` (alpha dropped — ink is always opaque). */
    fun toHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

    /**
     * The colour to actually paint [hex] with on this device.
     *
     * Today this is a straight parse. Phase 5 adds the greyscale-device branch here — returning
     * [Color.BLACK] when the panel cannot show colour — and every render site inherits it for free
     * precisely because they all route through this function.
     */
    fun paintColor(hex: String?): Int = toInt(hex ?: DEFAULT)

    /**
     * The colour to write [hex] with into a **file** — PNG, PDF, any export.
     *
     * Deliberately the true colour, never the device fallback: an export leaves the device, so it
     * must not inherit the limitations of the panel it happened to be produced on. A notebook
     * exported from a greyscale device still carries the red the author wrote in. This is the one
     * place that must *not* use [paintColor]; the separate name exists so that stays obvious once
     * [paintColor] grows its greyscale branch.
     */
    fun exportColor(hex: String?): Int = toInt(hex ?: DEFAULT)

    /**
     * True when [hex] should survive the Onyx overlay on a colour panel — i.e. its dominant channel
     * clears [MIN_DOMINANT_CHANNEL]. Achromatic inks (black, greys) are always safe: they are what
     * the panel falls back to anyway.
     */
    fun isOverlaySafe(hex: String): Boolean {
        val c = toInt(hex)
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        if (r == g && g == b) return true            // achromatic — nothing to lose
        return maxOf(r, g, b) >= MIN_DOMINANT_CHANNEL
    }
}
