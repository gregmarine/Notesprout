package com.notesprout.android.notebook.ratta

import android.graphics.Color
import com.notesprout.android.core.InkColor

/**
 * Stored ink hex → the nearest of the four colour codes the Supernote firmware pen accepts
 * (docs/drawing-engine.md, Ratta section → "Live-vs-baked appearance").
 *
 * The baked stroke always keeps its true stored hex — [InkColor.paintColor] at every render
 * site, same as every other device; nothing about persisted data changes here. This mapping
 * styles ONLY the live firmware overlay: the panel is greyscale, so its rendering of any
 * colour is a dithered grey whose tone tracks the colour's luminance. The job is therefore
 * to pick the firmware grey closest to the tone the baked stroke will render at, so the
 * pen-lift handoff (live overlay → baked polyline) is invisible.
 *
 * Thresholds are midpoints between the tones the four codes RENDER at, calibrated by eye
 * with the probe's COL cycler (Phase 8 round 1, 2026-08-09, identical on Nomad and Manta).
 * The codes paint far lighter than their names suggest: DARK_GRAY renders as a light grey
 * (≈ #AAAAAA, luma ~170), GRAY lighter still (≈ #CCCCCC, ~204), and LIGHT_GRAY is nearly
 * invisible on the white panel (≈ #F0F0F0, ~240 — only near-white ink maps there, which is
 * consistent: near-white baked ink is equally invisible on paper). Keeping the live preview
 * plain BLACK remains the documented cheap fallback if the mapped greys don't earn their
 * keep on the panel.
 */
object RattaInkMap {

    /** Ceiling for [SupernoteInk.Color.BLACK] — midpoint of black (0) and DARK_GRAY's ~170. */
    private const val BLACK_MAX_LUMA = 85f

    /** Ceiling for [SupernoteInk.Color.DARK_GRAY] — midpoint of ~170 and GRAY's ~204. */
    private const val DARK_GRAY_MAX_LUMA = 187f

    /** Ceiling for [SupernoteInk.Color.GRAY] (~204 vs LIGHT_GRAY's ~240); above → LIGHT_GRAY. */
    private const val GRAY_MAX_LUMA = 222f

    /**
     * The firmware colour code whose rendered grey best matches what the panel will show
     * for a baked stroke of colour [hex] (Rec. 601 luma; malformed/null falls back black).
     */
    fun firmwareColorFor(hex: String?): Int {
        val c = InkColor.paintColor(hex)
        val luma = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        return when {
            luma <= BLACK_MAX_LUMA     -> SupernoteInk.Color.BLACK
            luma <= DARK_GRAY_MAX_LUMA -> SupernoteInk.Color.DARK_GRAY
            luma <= GRAY_MAX_LUMA      -> SupernoteInk.Color.GRAY
            else                       -> SupernoteInk.Color.LIGHT_GRAY
        }
    }
}
