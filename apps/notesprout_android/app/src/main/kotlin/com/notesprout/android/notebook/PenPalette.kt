package com.notesprout.android.notebook

import com.notesprout.android.core.InkColor

/**
 * The default ink palette — the swatches the colour panel offers before the user mixes anything.
 *
 * **Tuned for colour e-paper, not for a screen.** Kaleido's colour filter array costs roughly half
 * the luminance and mutes everything, and the Phase-0 device sweep found the Onyx overlay drops a
 * colour to black once its **dominant RGB channel falls below ~180**
 * ([InkColor.MIN_DOMINANT_CHANNEL]). So every entry here is bright *and* saturated. A dark forest
 * green — the obvious "green ink" choice, and the one this palette originally shipped with — is not
 * usable ink on this hardware.
 *
 * Kept deliberately small. BOOX's own 16-colour palette draws the complaint that muted colours are
 * hard to tell apart on these panels; eight well-separated hues beat sixteen that blur together.
 *
 * [GREEN] is provisional, pending calibration against real writing — bright enough to survive the
 * overlay, but bright colours also lose contrast against white paper, and only a device can settle
 * that trade-off.
 */
object PenPalette {

    const val BLACK = "#000000"
    const val GRAY = "#808080"
    const val RED = "#D0021B"
    const val ORANGE = "#E8590C"
    const val AMBER = "#C08A00"
    const val GREEN = "#00C853"
    const val BLUE = "#1148C4"
    const val PURPLE = "#7629B8"

    /** A palette entry: the stored hex plus the name used for its content description + long-press hint. */
    data class Swatch(val name: String, val hex: String)

    /**
     * Two rows of four. Black leads (the default ink); grey sits beside it as the one non-black that
     * reads correctly on *every* panel, colour or not. The six chromatic entries follow in hue order.
     */
    val DEFAULTS: List<Swatch> = listOf(
        Swatch("Black", BLACK),
        Swatch("Gray", GRAY),
        Swatch("Red", RED),
        Swatch("Orange", ORANGE),
        Swatch("Amber", AMBER),
        Swatch("Green", GREEN),
        Swatch("Blue", BLUE),
        Swatch("Purple", PURPLE),
    )

    /** Swatches per row in the panel grid. */
    const val COLUMNS = 4

    /** The palette entry matching [hex], or null when the colour was mixed in the custom picker. */
    fun named(hex: String): Swatch? = DEFAULTS.firstOrNull { it.hex.equals(hex, ignoreCase = true) }

    /** Display name for any ink: the palette name when it has one, else the bare hex. */
    fun labelFor(hex: String): String = named(hex)?.name ?: hex.uppercase()
}
