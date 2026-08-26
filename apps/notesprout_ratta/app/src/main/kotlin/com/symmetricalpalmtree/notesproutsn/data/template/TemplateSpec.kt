package com.symmetricalpalmtree.notesproutsn.data.template

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

/** How an axis states its density: a physical spacing, or a number of features across the page. */
@Serializable
enum class DensityMode { SPACING, COUNT }

/**
 * One axis of a generator's density. The two modes are **not two views of one number** — they say
 * different things and survive a page-size change differently. `SPACING` is 8 mm and stays 8 mm on
 * any page; `COUNT` is 27 lines and stays 27 lines. Only the field matching [mode] is meaningful
 * (the other is the screen's live read-out, kept in step so a mode toggle shows the equivalent),
 * and only the meaningful one reaches the canonical form.
 */
@Serializable
data class DensityAxis(
    val mode: DensityMode = DensityMode.SPACING,
    val spacingMm: Float = TemplateGeometry.SPACING_MM,
    val count: Int = 0,
)

/**
 * **A generator, written down** (arc 13 / G2) — the recipe the app draws from, as opposed to a
 * static template, which is pixels the library keeps. Pure Kotlin, `kotlinx.serialization`, no
 * `android.graphics`: everything that decides where a rule goes is JVM-testable, and
 * [TemplateGeometry.plan] turns one of these into positions at any page size.
 *
 * Millimetres throughout, never pixels. Paper is measured in millimetres, so a spec authored on a
 * Nomad renders the same physical paper on a Manta — which is also why a *saved* variant stores
 * the spec rather than a bitmap.
 *
 * **The stock spec of a kind must render exactly what the app rendered before it existed.** That
 * is why [STOCK_THICKNESS_MM] and [STOCK_DOT_MM] are written as the mdpi-authored constants
 * converted to millimetres rather than as round numbers: `25.4 / 160` is one authored pixel, and
 * `mm * dpi / 25.4` of it comes back bit-identical to the old `px * dpi / 160`
 * (pinned across the family's densities in `TemplateGeometryTest`). Existing notebooks and new ones
 * have to agree, and `.soil` template reuse across old and new files rests on it.
 *
 * [token] is what a `.soil` `template` row's `text` carries: exactly `LINED` / `DOTTED` / `GRID`
 * for a stock spec — every file Paper and every earlier SN build ever wrote says that, and they
 * must keep matching — and `LINED#<8 hex>` for anything the user adjusted.
 */
@Serializable
data class TemplateSpec(
    val kind: TemplateKind = TemplateKind.LINED,
    /** Y axis: rules on Lined, horizontals on Grid, dot rows on Dotted. */
    val rows: DensityAxis = DensityAxis(),
    /** X axis: verticals on Grid, dot columns on Dotted. Ignored by Lined. */
    val cols: DensityAxis = DensityAxis(),
    val topMm: Float = 0f,
    val bottomMm: Float = 0f,
    val leftMm: Float = 0f,
    val rightMm: Float = 0f,
    /** A vertical rule at the left inset. Off by default — stock paper has no margin. */
    val marginRule: Boolean = false,
    val thicknessMm: Float = STOCK_THICKNESS_MM,
    val dotMm: Float = STOCK_DOT_MM,
    /** Ink level on the e-paper ladder, [SHADE_MIN]…[SHADE_BLACK]. */
    val shade: Int = SHADE_BLACK,
) {

    /**
     * The same spec with every field inside its legal band. Specs come back out of the global
     * index and, one day, out of a file another build wrote, so nothing downstream may assume a
     * sane number: an inset wider than the page or a count of two million is untrusted input, not
     * a bug to crash on.
     *
     * Each inset is capped at [MAX_INSET_MM] here; the guard that matters — a pair that would
     * swallow the page it is applied to — needs the page and so lives in [TemplateGeometry.plan].
     */
    fun sanitized(): TemplateSpec {
        val k = if (kind == TemplateKind.BLANK) TemplateKind.LINED else kind
        val (t, b) = clampPair(topMm, bottomMm)
        val (l, r) = clampPair(leftMm, rightMm)
        return copy(
            kind = k,
            rows = rows.sanitized(),
            cols = cols.sanitized(),
            topMm = t, bottomMm = b, leftMm = l, rightMm = r,
            thicknessMm = thicknessMm.clampMm(MIN_THICKNESS_MM, MAX_THICKNESS_MM, STOCK_THICKNESS_MM),
            dotMm = dotMm.clampMm(MIN_DOT_MM, MAX_DOT_MM, STOCK_DOT_MM),
            shade = if (shade in SHADE_MIN..SHADE_BLACK) shade else SHADE_BLACK,
        )
    }

    /**
     * True when this is the kind's factory paper — the one case that keeps a bare token.
     *
     * Judged on the **canonical form**, not on field equality: a Lined spec carrying some dot size
     * nobody can see draws stock lined paper, so it *is* stock lined paper. Identity is what the
     * canonical form says, everywhere.
     */
    val isStock: Boolean get() = canonical() == stock(kind).canonical()

    /**
     * The `.soil` `template` row's `text` for this spec: the bare kind name when it is stock, and
     * `KIND#<8 hex>` otherwise. It is deliberately **independent of page size** — reuse is
     * `text` + page size, so the token answers "which paper" and the size answers "which page".
     */
    fun token(): String = if (isStock) kind.name else "${kind.name}#${digest8()}"

    /** First 8 hex of SHA-256 over [canonical] — short enough to read in a log, long enough here. */
    fun digest8(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical().toByteArray(Charsets.UTF_8))
        return buildString(8) { for (i in 0 until 4) append(String.format(Locale.ROOT, "%02x", bytes[i])) }
    }

    /**
     * The spec as one stable line — the thing the digest is taken over, and the reason two specs
     * that draw the same paper get the same token.
     *
     * Two rules make it stable: fields a kind **ignores** are left out (a Lined spec's untouched
     * column axis must not change its identity), and every number is written at a fixed precision
     * in [Locale.ROOT] (a device whose locale writes `8,000` would otherwise fork the library).
     */
    fun canonical(): String {
        val s = sanitized()
        return buildString {
            append(CANONICAL_VERSION).append('|').append(s.kind.name)
            append("|y=").append(s.rows.canonical())
            if (s.kind != TemplateKind.LINED) append("|x=").append(s.cols.canonical())
            append("|m=").append(mm(s.topMm)).append(',').append(mm(s.bottomMm))
                .append(',').append(mm(s.leftMm)).append(',').append(mm(s.rightMm))
            append("|r=").append(if (s.marginRule) 1 else 0)
            append("|t=").append(mm(s.thicknessMm))
            if (s.kind == TemplateKind.DOTTED) append("|d=").append(mm(s.dotMm))
            append("|s=").append(s.shade)
        }
    }

    /** JSON for the index row a saved variant lives in. */
    fun encode(): ByteArray = json.encodeToString(serializer(), sanitized()).toByteArray(Charsets.UTF_8)

    companion object {

        /** Bumped only if the canonical grammar changes — which re-tokens every custom spec. */
        const val CANONICAL_VERSION = "v1"

        /**
         * One mdpi-authored pixel in millimetres — today's rule thickness, exactly.
         * `25.4 / 160` is what makes `mm * dpi / 25.4` reproduce `px * dpi / 160` bit for bit.
         */
        const val STOCK_THICKNESS_MM = 25.4f / 160f

        /** Today's dot: a 2 px mdpi **radius**, so 4 px of diameter, in millimetres. */
        const val STOCK_DOT_MM = 4f * 25.4f / 160f

        const val MIN_THICKNESS_MM = 0.05f
        const val MAX_THICKNESS_MM = 2f
        const val MIN_DOT_MM = 0.1f
        const val MAX_DOT_MM = 3f

        /** The widest a single inset may be. Pairs are clamped again against the page. */
        const val MAX_INSET_MM = 60f

        const val MIN_SPACING_MM = 1f
        const val MAX_SPACING_MM = 50f
        const val MIN_COUNT = 1
        const val MAX_COUNT = 400

        /**
         * The ink ladder: **15 usable levels**, [SHADE_BLACK] being black. E-paper renders 16
         * greys and the 16th is the paper itself — a rule drawn in it would not be a lighter rule,
         * it would be no rule at all, so it is not offered.
         */
        const val SHADE_MIN = 1
        const val SHADE_BLACK = 15

        /** The 0…255 grey a [shade] paints in: black at [SHADE_BLACK], 17 apart up the ladder. */
        fun greyFor(shade: Int): Int = (SHADE_BLACK - shade.coerceIn(SHADE_MIN, SHADE_BLACK)) * 17

        /** The factory paper for [kind] — every default in this class, and a bare [token]. */
        fun stock(kind: TemplateKind): TemplateSpec = TemplateSpec(
            kind = if (kind == TemplateKind.BLANK) TemplateKind.LINED else kind
        )

        /**
         * The spec in [bytes], or null for anything unusable. Never throws: a saved variant whose
         * payload a later build wrote — or a corrupt one — must degrade to "no spec", which the
         * card and the apply path already handle by falling back to the base kind.
         */
        fun decode(bytes: ByteArray?): TemplateSpec? {
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            return try {
                json.decodeFromString(serializer(), String(bytes, Charsets.UTF_8)).sanitized()
            } catch (_: Exception) {
                null
            }
        }

        /** A spec is a few hundred bytes; anything near this is not one. */
        const val MAX_BYTES = 64 * 1024

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun mm(v: Float): String = String.format(Locale.ROOT, "%.4f", v)

        private fun Float.clampMm(min: Float, max: Float, fallback: Float): Float =
            if (isNaN() || isInfinite()) fallback else coerceIn(min, max)

        /** Both insets of one axis, each in band and together leaving the page room to be paper. */
        private fun clampPair(a: Float, b: Float): Pair<Float, Float> {
            val x = a.clampMm(0f, MAX_INSET_MM, 0f)
            val y = b.clampMm(0f, MAX_INSET_MM, 0f)
            return x to y
        }

        /**
         * In `COUNT` the number is the whole statement, so it is floored at [MIN_COUNT] — an axis
         * asked for zero rules is not a blank page, it is a spec nobody wrote. In `SPACING` the
         * count is only the screen's read-out and is left alone.
         */
        private fun DensityAxis.sanitized(): DensityAxis = DensityAxis(
            mode = mode,
            spacingMm = spacingMm.clampMm(MIN_SPACING_MM, MAX_SPACING_MM, TemplateGeometry.SPACING_MM),
            count = if (mode == DensityMode.COUNT) count.coerceIn(MIN_COUNT, MAX_COUNT)
                    else count.coerceIn(0, MAX_COUNT),
        )

        private fun DensityAxis.canonical(): String =
            if (mode == DensityMode.COUNT) "C:$count" else "S:${mm(spacingMm)}"
    }
}
