package com.symmetricalpalmtree.notesprout.notebook

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The notebook's render-cache fill (arc 7 / L1 — a pure move of `scheduleRenderPass` /
 * `applyRenderResults` / `renderNow` out of `NotebookActivity` at its line cap, grown link-aware):
 * every live object — the page's own **and** the ones wrapped inside links — without a cached image
 * goes through one [ObjectRenderPass] batch; then every live link without a cached composite gets
 * one built ([LinkComposite]) from its children's cached bitmaps. A result landing for a *wrapped*
 * object invalidates its link's composite (rebuilt in the same step) and never resizes the row —
 * wrapped bounds are frozen by the wrap; the page's own objects keep the H4/H5 rules verbatim
 * (size-to-image persisted, `renderFailed` no-retry, stale-payload drop).
 *
 * Two entry points, exactly as before: [renderNow] (inline — create / apply / edit, awaited under
 * the screen's page-op lock, frame at once) and [scheduleRenderPass] (background — page load,
 * navigate, undo reload, provider reload, selection move; never holds the lock; a trigger during a
 * pass queues exactly one more). Composites build on Main in the apply step — one blit pass over
 * already-decoded bitmaps plus the stroke raster, comparable to what every commit's re-record
 * already draws.
 */
class RenderFlow(
    private val activity: AppCompatActivity,
    private val alive: () -> Boolean,
    private val page: () -> PageRef,
    private val liveObjects: () -> LinkedHashMap<String, PageObject>,
    private val liveLinks: () -> LinkedHashMap<String, PageLink>,
    private val providers: () -> ObjectProviders,
    private val cache: ObjectRenderCache,
    /** For sizing a page-level object to its rendered image — persisted + mirrored here (H4). */
    private val objectStore: () -> ObjectStore,
    /** One frame: at once for the inline path (H5), pen-idle for the background pass. */
    private val notify: (atOnce: Boolean) -> Unit,
    private val dpi: () -> Float,
) {
    private val renderPass by lazy { ObjectRenderPass(activity) }
    /** Objects (and link composites) whose render failed on this page load — not retried until the
     *  next load, a provider reload, or an edit. */
    val renderFailed = HashSet<String>()
    private var renderJob: Job? = null
    private var renderAgain = false

    /** Keep cached bitmaps for exactly the current page: its objects, its links' composites, and the
     *  links' wrapped objects (the cache is bounded by one page — H5). */
    fun retainCurrent() {
        val keep = HashSet<String>(liveObjects().keys)
        for (l in liveLinks().values) {
            keep += l.id
            for (o in l.objects) keep += o.id
        }
        cache.retain(keep)
    }

    /** Build any missing link composites from what is cached **right now** (Main, cheap blits) —
     *  called by the page-load paths before their at-once frame, so a link whose children are
     *  already cached is never presented as a whole dashed box (L4 checklist finding, SNN: the
     *  built-composite repaint is pen-idle-gated, and a pen *hovering* over the fresh link held it
     *  back for as long as the user examined it — the lasso that "fixed" it merely lifted the pen).
     *  Children still missing draw as inner placeholders; the pass invalidates + rebuilds the
     *  composite when their renders land. */
    fun buildCompositesNow() { rebuildLinkComposites(dpi()) }

    /** Render [objects] inline (IO) and apply — the create / apply / edit path, awaited under the
     *  page-op lock. The frame is presented **at once** (H5): the user just tapped a toolbar button
     *  or Save — the pen is up (hovering), `releaseRender` already ran. */
    suspend fun renderNow(objects: List<PageObject>) {
        val p = page()
        applyRenderResults(renderPass.render(objects, providers(), p.width.toFloat(), dpi()), atOnce = true)
    }

    /**
     * The background cache fill: every live object (page-level or wrapped) without a cached image
     * for its (payload, width, dpi) that hasn't failed on this load → one pass; then the link
     * composites. A trigger during a pass queues exactly one more (the page may have changed under
     * it). Never holds the page-op lock; results for objects no longer on the page are dropped.
     */
    fun scheduleRenderPass() {
        if (!alive()) return
        if (renderJob?.isActive == true) { renderAgain = true; return }
        renderJob = activity.lifecycleScope.launch {
            do {
                renderAgain = false
                val p = page()
                val d = dpi()
                val candidates = liveObjects().values + liveLinks().values.flatMap { it.objects }
                val misses = candidates.filter {
                    it.id !in renderFailed && cache.get(it.id, it.payload, ObjectRenderer.renderWidth(p.width.toFloat(), it), d) == null
                }
                if (misses.isNotEmpty()) {
                    val results = renderPass.render(misses, providers(), p.width.toFloat(), d)
                    if (!alive()) break
                    applyRenderResults(results)
                } else {
                    val built = rebuildLinkComposites(d)
                    if (built) notify(false)
                    if (!built) break
                }
            } while (renderAgain)
        }
    }

    /** Main: cache the images, size each page-level object to its image (persisted; anchored
     *  top-left), invalidate + rebuild the composites of links whose children changed, one frame. */
    fun applyRenderResults(results: List<ObjectRenderPass.Result>, atOnce: Boolean = false) {
        var changed = false
        val linkByChild = HashMap<String, PageLink>()
        for (l in liveLinks().values) for (o in l.objects) linkByChild[o.id] = l
        for (r in results) {
            val owner = linkByChild[r.id]
            val o = liveObjects()[r.id] ?: owner?.objects?.firstOrNull { it.id == r.id } ?: continue
            if (o.payload != r.payload) continue   // edited while rendering — the next pass has it
            val bmp = r.bitmap
            if (bmp == null) { renderFailed.add(r.id); continue }
            cache.put(r.id, r.payload, r.maxWidth, r.dpi, bmp)
            changed = true
            if (owner != null) {
                // A wrapped child's image landed: its composite is stale. Bounds stay frozen.
                cache.remove(owner.id)
                renderFailed.remove(owner.id)
                continue
            }
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            if (w != o.width || h != o.height) {
                val sized = o.copy(width = w, height = h)
                liveObjects()[sized.id] = sized
                objectStore().updatePayloadAndBounds(sized.id, sized.payload, sized.x, sized.y, sized.width, sized.height)
            }
        }
        if (rebuildLinkComposites(dpi())) changed = true
        if (!changed) return
        if (atOnce) { if (alive()) notify(true) }
        else notify(false)
    }

    /** Build the composite of every live link that has none cached (and isn't marked failed) from
     *  its children's cached bitmaps. Returns whether anything was built. Main thread. */
    private fun rebuildLinkComposites(d: Float): Boolean {
        val p = page()
        var built = false
        for (l in liveLinks().values) {
            if (l.id in renderFailed) continue
            if (cache.get(l.id, ObjectRenderer.LINK_COMPOSITE_KEY, ObjectRenderer.linkWidth(l), d) != null) continue
            val bmp = LinkComposite.build(l) { o ->
                cache.get(o.id, o.payload, ObjectRenderer.renderWidth(p.width.toFloat(), o), d)
            }
            if (bmp == null) { renderFailed.add(l.id); continue }
            cache.put(l.id, ObjectRenderer.LINK_COMPOSITE_KEY, ObjectRenderer.linkWidth(l), d, bmp)
            built = true
        }
        return built
    }
}
