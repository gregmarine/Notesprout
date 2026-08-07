package com.notesprout.android.notebook

/**
 * The two ink palettes: 16 greys, and 16 colours.
 *
 * **Both are offered on every device.** An earlier build detected colour panels and hid colour ink on
 * greyscale ones; that was dropped after trying it on a Go 10.3 — the dithered greys were perfectly
 * legible, and overriding the user's choice bought nothing. The [GREYSCALE] palette is the better
 * answer to the same problem: rather than reinterpreting a colour the panel cannot show, it offers
 * tones the panel renders *exactly*, and it is the default everywhere.
 */
object PenPalette {

    /** A palette entry: the stored hex plus the name used for its content description + tooltip. */
    data class Swatch(val name: String, val hex: String)

    /** Which set the panel is showing. Persisted — see [PenColorPreferences.loadPalette]. */
    enum class Kind { GREYSCALE, COLOR }

    /** Swatches per row, in every row of both palettes and the custom row. */
    const val COLUMNS = 8

    /** How many user-assignable custom slots the colour palette carries. */
    const val CUSTOM_SLOTS = 8

    /**
     * The 16 greys e-ink actually renders.
     *
     * Not an arbitrary ramp: e-paper is a **4-bit greyscale** panel, so the hardware quantizes to
     * exactly 16 levels, and the canonical mapping of those levels to sRGB is increments of `0x11`
     * (17) — `#000000`, `#111111`, … `#FFFFFF`. Choosing anything else would just be picking values
     * the panel then rounds to these anyway.
     *
     * Ends at pure white deliberately. White ink is invisible on white paper, but the ramp is the
     * hardware's own and truncating it would be arbitrary — and white has real uses over a dark
     * template or as a knock-out stroke.
     */
    val GREYSCALE: List<Swatch> = (0..15).map { level ->
        val v = level * 0x11
        Swatch(greyName(level), String.format("#%02X%02X%02X", v, v, v))
    }

    /**
     * The ends are named; the fourteen between them are just their level.
     *
     * The number is not filler — it makes a grey **referable** ("grey 5 for margin notes") and shows
     * at a glance that the ramp is the panel's own sixteen steps rather than an arbitrary gradient.
     * "Grey 5" would be redundant under a grey swatch in a grey palette.
     */
    private fun greyName(level: Int): String = when (level) {
        0 -> "Black"
        15 -> "White"
        else -> level.toString()
    }

    /**
     * The 16-colour set, matching the BOOX Notes pen palette: five greys (black → white) then eleven
     * colours. Two rows of eight, in BOOX's own order.
     *
     * **These are measured, not guessed.** BOOX builds its palette programmatically into a
     * RecyclerView rather than storing it in resources, so it cannot be read out of the APK. The
     * values were sampled from the live picker's framebuffer on a NoteAir5C (2026-07-31) — and the
     * whole second row independently matches the `colour_list` / `shape_colour_1..8` array that *is*
     * in the APK's resources, which cross-validates the sampling.
     *
     * Worth noticing what BOOX does here: it prints `WT`/`RD`/`GN`/`BU` **inside** the white, red,
     * green and blue swatches — the four whose greyscale renderings are hardest to tell apart. Their
     * answer to "colours are ambiguous on a B&W panel" is to label them, not to hide them.
     *
     * The greys are evenly spaced at `0x40` (0, 64, 128, 192, 255) rather than on the 4-bit ladder
     * the [GREYSCALE] palette uses — five stops across the full range instead of sixteen.
     */
    val COLOR: List<Swatch> = listOf(
        // Row 1 — five greys, then red / green / blue
        Swatch("Black", "#000000"),
        Swatch("Dark grey", "#404040"),
        Swatch("Grey", "#808080"),
        Swatch("Light grey", "#C0C0C0"),
        Swatch("White", "#FFFFFF"),
        Swatch("Red", "#FF6163"),
        Swatch("Green", "#00B036"),
        Swatch("Navy", "#000084"),
        // Row 2 — matches the APK's shape_colour_1..8, in order
        Swatch("Cyan", "#00F0FF"),
        Swatch("Magenta", "#EE00FF"),
        Swatch("Orange", "#FFAA00"),
        Swatch("Yellow", "#F0FF00"),
        Swatch("Dark green", "#008000"),
        Swatch("Purple", "#9338BE"),
        Swatch("Blue", "#00AAFF"),
        Swatch("Vermilion", "#FF4400"),
    )

    fun swatches(kind: Kind): List<Swatch> = when (kind) {
        Kind.GREYSCALE -> GREYSCALE
        Kind.COLOR -> COLOR
    }

    /** The palette entry matching [hex] in either set, or null when it was mixed by the user. */
    fun named(hex: String): Swatch? =
        (GREYSCALE + COLOR).firstOrNull { it.hex.equals(hex, ignoreCase = true) }

    /** Display name for any ink: the palette name when it has one, else the bare hex. */
    fun labelFor(hex: String): String = named(hex)?.name ?: hex.uppercase()
}
