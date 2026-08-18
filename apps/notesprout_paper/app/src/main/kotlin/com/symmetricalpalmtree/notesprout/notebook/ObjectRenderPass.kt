package com.symmetricalpalmtree.notesprout.notebook

import android.content.Context
import android.graphics.Bitmap
import com.symmetricalpalmtree.notesprout.core.Bitmaps
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.CapabilityRequiredException
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.ObjectProviderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The render pass that fills [ObjectRenderCache] (arc 4 / H4): given the objects the screen wants
 * drawn, group them by provider identity → **one `ObjectProviderClient.renderAll` per provider**
 * (one bind, one Markdown proxy, N renders) → decode each verified WEBP (`Bitmaps.decodeBounded`,
 * edge cap) → hand back one [Result] per object. Runs on IO; the screen applies the results on Main
 * (cache put, width/height from the image, `notifyContentChanged` through `whenPenIdle`) — this
 * class touches no screen state and never logs a payload.
 *
 * An object whose provider is unknown (identity unparseable, package not among the loaded
 * providers) or whose render failed / returned nothing yields `bitmap == null` — the screen keeps
 * its placeholder and does not retry until the next page load or an edit of that object.
 */
class ObjectRenderPass(context: Context) {

    private val appContext = context.applicationContext

    /** One object's outcome: the payload + width + dpi it was rendered for (the cache key), and the image or null. */
    class Result(val id: String, val payload: String, val maxWidth: Int, val dpi: Float, val bitmap: Bitmap?)

    suspend fun render(objects: List<PageObject>, providers: ObjectProviders, pageWidth: Float, dpi: Float): List<Result> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Result>(objects.size)
            val t0 = System.currentTimeMillis()
            for ((providerKey, group) in objects.groupBy { ExtensionContract.parseIdentity(it.providerIdentity)?.first ?: "" }) {
                val client = if (providerKey.isEmpty()) null else providers.clientFor(appContext, providerKey)
                if (client == null) {
                    Slog.d(TAG) { "no provider for ${group.size} object(s) of '${providerKey.ifEmpty { "?" }}' — placeholders" }
                    for (o in group) out += Result(o.id, o.payload, ObjectRenderer.renderWidth(pageWidth, o), dpi, null)
                    continue
                }
                val requests = group.map { o ->
                    ObjectProviderClient.RenderRequest(
                        ExtensionContract.parseIdentity(o.providerIdentity)!!.second, o.payload, ObjectRenderer.renderWidth(pageWidth, o),
                    )
                }
                val copies = try {
                    client.renderAll(requests, dpi)
                } catch (e: CapabilityRequiredException) {
                    Slog.d(TAG) { "$providerKey: capability required (${e.message}) — ${group.size} placeholder(s)" }
                    List(requests.size) { null }
                } catch (e: ExtensionCallException) {
                    Slog.d(TAG) { "$providerKey: renderAll failed: ${e.javaClass.simpleName}: ${e.message}" }
                    List(requests.size) { null }
                }
                for ((i, o) in group.withIndex()) {
                    val copy = copies.getOrNull(i)
                    val bmp = copy?.let { Bitmaps.decodeBounded(it.bytes, ExtensionContract.MAX_IMAGE_EDGE_PX) }
                    if (copy != null && bmp == null) Slog.d(TAG) { "${o.id}: verified image failed to decode" }
                    out += Result(o.id, o.payload, requests[i].maxWidthPx, dpi, bmp)
                }
            }
            Slog.d(TAG) { "pass: ${objects.size} object(s) → ${out.count { it.bitmap != null }} rendered in ${System.currentTimeMillis() - t0} ms" }
            out
        }

    private companion object {
        const val TAG = "ObjectRenderPass"
    }
}
