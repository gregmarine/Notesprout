package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Draws the visible page's links into g-paper's committed layer — the arc-6 [ContentRenderer],
 * alongside [HeadingRenderer].
 *
 * **Below the ink** ([ContentLayer.BELOW_STROKES], the K1 wizard's og-parity call): fresh ink over
 * a link stays visible on top. Each link draws its composite bitmap ([LinkComposite] — the wrapped
 * strokes + headings at 1:1 page px) or the standard dashed placeholder when the composite could
 * not build, then its chrome: a 1 dp inkBlack underline across the bounds' bottom when the link's
 * decoded chrome says so — drawn **live**, never baked, so a chrome edit repaints without a
 * rebuild.
 *
 * [update] is the one way in, on Main: it swaps the working copy and (re)builds composites —
 * reusing a cached bitmap when the link's drawable size is unchanged, which makes a move free
 * (composites are translation-invariant) — and drops entries for links no longer on the page.
 * Composites are therefore ready **before** the page-load frame that paints them (the arc's
 * standing hover-repaint trap: never build render content behind a pen-idle gate).
 *
 * Implements the live-drag pair ([draw] with exclusions + [drawObject]) so a dragged link rides
 * under the pen as its real self; [drawObject] is one `drawBitmap`, cheap at drag refresh rate.
 * [hitTargets] puts every link's bounds into lasso selection — whole-link, the locked model.
 */
class LinkRenderer(
    private val density: Float,
    scaledDensity: Float,
) : ContentRenderer {

    override val layer = ContentLayer.BELOW_STROKES

    /** The visible page's links — read on Main and on the engine's re-record path only. */
    var links: List<PageLink> = emptyList()
        private set

    private val composites = HashMap<String, Bitmap>()
    private val textPaint = HeadingRenderer.basePaint(scaledDensity)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val underline = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE }
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.BLACK
        pathEffect = DashPathEffect(floatArrayOf(DASH_PX, DASH_PX), 0f)
    }

    /**
     * Build the composites [update] would otherwise have to build, **off the Main thread** — the
     * page-load paths call this from their suspend load block so a link-heavy flip (or an undo
     * replay's refresh) never rasterizes bitmaps inside the Main-thread display frame (K5
     * review). The needed set is decided on the caller's (Main) thread against the live cache;
     * only the raster work hops to [Dispatchers.Default]. Hand the result to [update] —
     * prebuilding is an optimisation only, and [update] still builds anything missing.
     */
    suspend fun prebuild(links: List<PageLink>): Map<String, Bitmap> {
        val todo = links.filter { l ->
            val cached = composites[l.id]
            val (w, h) = LinkComposite.sizeOf(l)
            cached == null || cached.width != w || cached.height != h
        }
        if (todo.isEmpty()) return emptyMap()
        return withContext(Dispatchers.Default) {
            buildMap {
                for (l in todo) LinkComposite.build(l, density, textPaint)?.let { put(l.id, it) }
            }
        }
    }

    /**
     * Swap the working copy and reconcile the composite cache: install a [prebuilt] bitmap when
     * one was handed in (still size-checked — the link could have changed since [prebuild]),
     * else keep a cached bitmap whose size still matches the link's drawable size (a move — the
     * composite is translation-invariant), build the rest, drop the departed. Main thread; call
     * before the frame that must paint the result (`loadStrokes` / `notifyContentChanged`).
     */
    fun update(links: List<PageLink>, prebuilt: Map<String, Bitmap> = emptyMap()) {
        this.links = links
        val wanted = links.associateBy { it.id }
        composites.keys.retainAll(wanted.keys)
        for (l in links) {
            // The expected size is the PADDED one (LinkComposite.sizeOf — bounds + the stroke-width
            // margin), or a stale unpadded bitmap would be "reused" forever at the wrong offset.
            val (w, h) = LinkComposite.sizeOf(l)
            val fresh = prebuilt[l.id]
            if (fresh != null && fresh.width == w && fresh.height == h) {
                composites[l.id] = fresh
                continue
            }
            val cached = composites[l.id]
            if (cached != null && cached.width == w && cached.height == h) continue
            val built = LinkComposite.build(l, density, textPaint)
            if (built != null) composites[l.id] = built else composites.remove(l.id)
        }
    }

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())

    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (l in links) {
            if (l.id in excludedContentIds) continue
            drawLink(canvas, l)
        }
    }

    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val l = links.firstOrNull { it.id == contentId } ?: return false
        drawLink(canvas, l)
        return true
    }

    override fun hitTargets(): List<HitTarget> = links.map { HitTarget(it.id, it.bounds) }

    private fun drawLink(canvas: Canvas, l: PageLink) {
        val bmp = composites[l.id]
        if (bmp != null && !bmp.isRecycled) {
            // The composite is padOf() larger than the bounds on every side (stroke ink overhangs
            // its point-bounds) — offset back so the content lands page-exact.
            val pad = LinkComposite.padOf(l).toFloat()
            canvas.drawBitmap(bmp, l.x - pad, l.y - pad, bitmapPaint)
        } else {
            // Inset by half the stroke so the 1 px dash sits inside the bounds (and the hit rect).
            canvas.drawRect(l.x + 0.5f, l.y + 0.5f, l.x + l.width - 0.5f, l.y + l.height - 0.5f, placeholder)
        }
        if (l.chrome == LinkPayload.CHROME_UNDERLINE) {
            val px = density.coerceAtLeast(1f)           // 1 dp, never sub-px
            underline.strokeWidth = px
            val y = l.y + l.height - px / 2f             // inside the bounds' clearance band
            canvas.drawLine(l.x, y, l.x + l.width, y, underline)
        }
    }

    private companion object {
        const val DASH_PX = 6f
    }
}
