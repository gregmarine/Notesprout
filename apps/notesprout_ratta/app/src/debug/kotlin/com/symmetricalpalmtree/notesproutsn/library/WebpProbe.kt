package com.symmetricalpalmtree.notesproutsn.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import java.io.ByteArrayOutputStream

/**
 * Debug build only — the on-device measurement behind the encoder question in
 * [BuiltInTemplates.toWebp]'s header.
 *
 * **Why it has to run on the device.** The whole question is about *Skia's* WEBP encoders, not
 * libwebp's. og Notesprout rejected `WEBP_LOSSLESS` on an on-device finding that Skia's lossless
 * path bloats to 2-6x PNG; the same comparison run against desktop libwebp does not reproduce that
 * at all, which is evidence the effect is Skia-specific and that no host tool can stand in for it.
 * So this measures with the same `Bitmap.compress` calls the app itself ships.
 *
 * **What it measures, and why each case is here.**
 *  - The three built-ins at the device's **real page size** — the bytes `BuiltInTemplates.toWebp`
 *    actually writes into a `.soil`. Opaque line art; the case SN's lossless choice is aimed at.
 *  - A **cover-sized** render (512 long edge, [com.symmetricalpalmtree.notesproutsn.notebook.CoverSnapshot]'s
 *    scale) — opaque too, but small and downscaled, so its ruling is grey rather than flat black.
 *    This is the one place SN already matches og, and the point is to find out whether it should.
 *  - A **photo-like** page: smooth gradients plus noise, standing in for an imported picture. That
 *    is the content `toWebp` handles worst if lossless is wrong, and it is size-capped on import.
 *  - An **alpha** page: ink on transparency, which is *og's own scenario*, run here so the 2-6x
 *    claim can be confirmed or refuted on this hardware rather than inherited.
 *
 * Nothing is written to any database and no app state is touched — bitmaps are built, encoded,
 * measured and recycled.
 *
 * **Every row is logged and written to a file the moment it is measured, not at the end.** A
 * page-sized `WEBP_LOSSLESS` encode is seconds, not milliseconds, and there are four encodes per
 * case — the whole run is minutes on a Supernote. The first version reported only at the end and
 * read as "it just shows a toast", so the report is now built incrementally: `Slog` after each row
 * (visible in `adb logcat -s DebugMenu`) and the file rewritten each time, so an interrupted or
 * abandoned run still leaves everything it managed to measure.
 */
object WebpProbe {

    /** One row of the report. [lossless] / [lossyQ100] / [lossyQ90] / [png] are encoded sizes. */
    data class Row(
        val label: String,
        val pixels: String,
        val lossless: Int,
        val losslessMs: Long,
        val lossyQ100: Int,
        val lossyQ100Ms: Long,
        val lossyQ90: Int,
        val png: Int,
    ) {
        /** Positive = lossless is the smaller encoder, which is what SN currently assumes. */
        val losslessWins: Boolean get() = lossless < lossyQ100
        val ratio: Float get() = maxOf(lossless, lossyQ100).toFloat() / minOf(lossless, lossyQ100).coerceAtLeast(1)
        val vsPng: Float get() = lossless.toFloat() / png.coerceAtLeast(1)
    }

    /** Where the report is written after every row, so `adb pull` gets it without the UI. */
    fun reportFile(context: Context) = java.io.File(context.getExternalFilesDir(null), "webp-probe.txt")

    /**
     * [onRow] fires on the calling (background) thread after each case, so the caller can show
     * progress. Runs the cases cheapest-first: the cover is seconds, a page-sized lossless encode
     * is not, and an abandoned run should still have left the small cases behind.
     */
    fun run(context: Context, onRow: (Row) -> Unit = {}): List<Row> {
        val m = context.resources.displayMetrics
        val pageW = minOf(m.widthPixels, m.heightPixels)
        val pageH = maxOf(m.widthPixels, m.heightPixels)
        val dpi = m.densityDpi.toFloat()
        val rows = mutableListOf<Row>()

        fun add(label: String, bmp: Bitmap) {
            val row = measure(label, bmp)
            rows += row
            Slog.d(TAG) { "webp probe row: ${line(row)}" }
            runCatching { reportFile(context).writeText(report(context, rows)) }
            onRow(row)
        }

        // 1. Cover-sized first: the cheapest case, so something lands within seconds.
        BuiltInTemplates.render(TemplateKind.LINED, pageW, pageH, dpi)?.let { full ->
            val edge = 512
            val f = edge.toFloat() / maxOf(full.width, full.height)
            val small = Bitmap.createScaledBitmap(
                full, (full.width * f).toInt().coerceAtLeast(1), (full.height * f).toInt().coerceAtLeast(1), true,
            )
            add("cover 512 (opaque)", small)
            small.recycle(); full.recycle()
        }

        // 2-4. The real page bake, exactly as BuiltInTemplates.render produces it.
        for (kind in listOf(TemplateKind.LINED, TemplateKind.DOTTED, TemplateKind.GRID)) {
            val bmp = BuiltInTemplates.render(kind, pageW, pageH, dpi) ?: continue
            add("page ${kind.name.lowercase()}", bmp)
            bmp.recycle()
        }

        // 5. Photo-like: an imported picture's worst case for a lossless encoder.
        photoLike(pageW, pageH)?.let { add("photo-like import", it); it.recycle() }

        // 6. og's own case: ink on transparency. Confirms or refutes the 2-6x claim on this device.
        alphaInk(pageW, pageH)?.let { add("alpha ink (og case)", it); it.recycle() }

        return rows
    }

    private const val TAG = "DebugMenu"

    private fun measure(label: String, bmp: Bitmap): Row {
        val (ll, llMs) = timed { encode(bmp, lossless = true, quality = 100) }
        val (q100, q100Ms) = timed { encode(bmp, lossless = false, quality = 100) }
        val (q90, _) = timed { encode(bmp, lossless = false, quality = 90) }
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.size()
        return Row(label, "${bmp.width}x${bmp.height}", ll, llMs, q100, q100Ms, q90, png)
    }

    private inline fun timed(block: () -> Int): Pair<Int, Long> {
        val t = System.currentTimeMillis()
        val n = block()
        return n to (System.currentTimeMillis() - t)
    }

    /** The same API guard [BuiltInTemplates.toWebp] and `CoverSnapshot.encode` ship. */
    private fun encode(bmp: Bitmap, lossless: Boolean, quality: Int): Int {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        bmp.compress(format, quality, out)
        return out.size()
    }

    /** Smooth gradient + per-pixel noise: compresses like a photograph, not like line art. */
    private fun photoLike(w: Int, h: Int): Bitmap? = try {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        var seed = 12345L
        for (y in 0 until h) {
            for (x in 0 until w) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                val noise = ((seed ushr 33) % 51).toInt() - 25
                val v = (120 + 80 * (x - w / 2) / w + 60 * (y - h / 2) / h + noise).coerceIn(0, 255)
                px[y * w + x] = Color.rgb(v, v, v)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        bmp
    } catch (e: OutOfMemoryError) { null }

    /** Ink on a transparent page — the content og measured, at this device's page size. */
    private fun alphaInk(w: Int, h: Int): Bitmap? = try {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        var seed = 999L
        fun next(n: Int): Int { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 33) % n).toInt() }
        var y = h / 10
        while (y < h - h / 10) {
            var x = w / 12
            while (x < w - w / 6) {
                val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                for (i in 1..12) {
                    path.lineTo((x + i * 6).toFloat(), (y - next(40)).toFloat())
                }
                canvas.drawPath(path, paint)
                x += 110
            }
            y += h / 24
        }
        bmp
    } catch (e: OutOfMemoryError) { null }

    private fun line(r: Row): String =
        "%-20s %-11s %6dK/%-4d %6dK/%-4d %6dK %6dK  %s %.2fx  (LL=%.2fx PNG)".format(
            r.label, r.pixels,
            r.lossless / 1024, r.losslessMs,
            r.lossyQ100 / 1024, r.lossyQ100Ms,
            r.lossyQ90 / 1024, r.png / 1024,
            if (r.losslessWins) "LOSSLESS" else "q100", r.ratio, r.vsPng,
        )

    /** Fixed-width text so the Manta's and the Nomad's reports line up when pasted side by side. */
    fun report(context: Context, rows: List<Row>): String {
        val m = context.resources.displayMetrics
        val sb = StringBuilder()
        // Build.MODEL is "Supernote Nomad" on the Manta too — every ro.product.* is identical
        // across the two. The resolution is the only thing in reach that tells them apart.
        sb.append("${Build.MODEL} (${m.widthPixels}x${m.heightPixels} @${m.densityDpi}dpi) ")
        sb.append("= ${if (m.widthPixels >= 1920) "MANTA" else "NOMAD"}, API ${Build.VERSION.SDK_INT}\n")
        sb.append("case                 pixels      lossless    q100     q90      png    winner\n")
        for (r in rows) sb.append(line(r)).append('\n')
        return sb.toString()
    }
}
