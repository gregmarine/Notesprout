package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ByteChunks
import com.symmetricalpalmtree.notesproutsn.extension.ISketchHost
import com.symmetricalpalmtree.notesproutsn.extension.ImageHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchGuideSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The sketch face's **guides** (arc 51 "Guides" / J3) — a grid and a reference image laid under
 * the page as a tool and never as a mark, per page. Everything the face does for them lives here so
 * [SketchActivity] keeps only its hooks: the load in `loadPage`, the outside-contact dismissal, the
 * floating rects, the overflow entry and the exit.
 *
 * ## The one picture
 *
 * Whatever is shown is **one** page-sized bitmap handed to g-paper's `setSheet` ([GuideSheet]):
 * the image at its opacity, the grid over it. g-paper draws it over white and under both rasters,
 * on the window and on the Supernote panel, and never exports, covers, rubs or smudges it — so no
 * rule anywhere on this face has to remember the guides exist. Nothing shown is `setSheet(null)`.
 * The sheet is held by reference, so the one set is kept in [sheet] until it is replaced and only
 * then recycled; the decoded reference is kept in [image] so an opacity or a visibility pick can
 * redraw without a Binder call. **Every sheet build and every swap of [image] happens under
 * [sheetLock]** — a build reads [image] on IO, and nothing may recycle it under that read. A build
 * reads [state] when it takes the lock, so a burst of picks ends on the newest one.
 *
 * ## What crosses the seam
 *
 * - **At every load**: `guides(pageKey)` and the image's chunks (none for a page with no image),
 *   before the rasters load — one coalesced rebuild on the panel. A failure of any kind is a line
 *   in the log and **no sheet**: never a crash, never a dialog on a page turn.
 * - **At every settings pick**: `putGuides` fire-and-forget on IO under a fair [Mutex] in pick
 *   order ([SketchActivity]'s `toolPushes` rule) — a failure is a log line, never a dialog
 *   interrupting a hand that is drawing. Not on the undo stack (the user's decision 6).
 * - **At a Pick… or a Remove**: the image's chunk stream under [SketchSaver]'s one push lock
 *   ([SketchSaver.pushGuideImage]), then `putGuides`. A failure there **is** a problem dialog: the
 *   person asked for something and it did not happen.
 *
 * ## The picker
 *
 * `ACTION_OPEN_DOCUMENT` for PNG / JPEG / WebP — the first system picker any extension opens. The
 * grant is read once and dropped: no persistable permission, nothing written to disk by the
 * extension, and **neither the Uri nor a byte of the picture is ever logged**. Decoded with
 * `ImageDecoder` (API 28+, within `minSdk` 29) rather than `BitmapFactory`, because it applies a
 * camera photo's EXIF orientation — the user's "no orientation" is no control for it, not a photo
 * lying on its side — and reads the size from the header in the same pass, so the sample size is
 * chosen before any pixel is allocated ([GuideSheet.sampleSize]).
 *
 * `HostCallerCheck` runs in the activity's `onCreate`: if DocumentsUI's memory pressure ever
 * recreates the face, `callingPackage` is what the framework restores — a thing for the walk to
 * see, not something to work around here.
 */
class SketchGuides(
    private val activity: AppCompatActivity,
    private val paper: PaperView,
    root: ViewGroup,
    barView: LinearLayout,
    anchor: View,
    bandBottom: () -> Int?,
    private val saver: SketchSaver,
    /** The showing's binder, or null when there is none. */
    private val host: () -> ISketchHost?,
    /** Whether the face is open and not closing — a pick before the page lands or after the exit
     *  began does nothing. */
    private val usable: () -> Boolean,
    /** The bar opened or closed — the screen re-pushes its exclusion rects. */
    private val onBarChanged: () -> Unit,
) {

    /** The page the guides belong to, and its size — set at each [load]. Main thread. */
    private var pageKey: String? = null
    private var pageWidth = 0
    private var pageHeight = 0

    /** The page's guides as the face knows them. Main thread. */
    private var state: GuideState = GuideState.NONE

    /** The decoded reference image, page-sized with its fit baked in; null for none. Swapped and
     *  recycled only under [sheetLock]. */
    private var image: Bitmap? = null

    /** The bitmap g-paper holds by reference; recycled only after its replacement is set. */
    private var sheet: Bitmap? = null

    /** Serialises sheet builds against image swaps and page loads. */
    private val sheetLock = Mutex()

    /** Keeps the `putGuides` pushes in pick order. The mutex is fair. */
    private val settingsPushes = Mutex()

    /** The page a Pick… was opened for — the result lands on that page or nowhere. */
    private var pickingFor: String? = null

    private val picker = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> onPickResult(result) }

    val bar = GuidesBar(
        root = root,
        bar = barView,
        anchor = anchor,
        bandBottom = bandBottom,
        paper = paper,
        state = { state },
        onChanged = { pick(it) },
        onPickImage = { pickImage() },
        onRemoveImage = { removeImage() },
    )

    // ── The panel ────────────────────────────────────────────────────────────

    val isShowing: Boolean get() = bar.isShowing

    fun rects(): List<Rect> = bar.rects()

    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)

    /** The Guides button's toggle, from the top bar or the collapsed overflow's own button. */
    fun toggle(anchor: View? = null) {
        if (bar.isShowing) {
            hide()
            return
        }
        if (!usable()) return
        val shown = if (anchor == null) bar.show() else bar.show(anchor)
        if (shown) onBarChanged()
    }

    /** Idempotent; answers whether it was showing, so a caller inside the collapsed chrome's close
     *  can leave the one exclusion push to it. */
    fun takeDown(): Boolean {
        if (!bar.isShowing) return false
        bar.hide()
        return true
    }

    /** Idempotent — every dismiss path but the collapsed chrome's calls this one. */
    fun hide() {
        if (takeDown()) onBarChanged()
    }

    // ── Loading a page ───────────────────────────────────────────────────────

    /**
     * Lay the page [pageKey] names' guides under it — called by `loadPage` after the template line
     * and **before** the two rasters load, on Main, so the panel rebuilds once for all three. The
     * host is asked, the image (if any) read chunk by chunk and decoded behind the header guard,
     * and the sheet built, all off the main thread; then `setSheet` on Main.
     */
    suspend fun load(pageKey: String, width: Int, height: Int) {
        sheetLock.withLock {
            this.pageKey = pageKey
            pageWidth = width
            pageHeight = height
            pickingFor = null
            val t0 = SystemClock.elapsedRealtime()
            val fetched = fetch(pageKey)
            val decoded = if (fetched == null || fetched.bytes.isEmpty()) {
                null
            } else {
                withContext(Dispatchers.IO) { RasterImage.decode(fetched.bytes, width, height) }
            }
            replaceImage(decoded)
            state = GuideState.fromSettings(fetched?.settings, hasImage = fetched?.hasImage == true)
            applySheet()
            Slog.d(TAG) {
                "guides loaded in ${SystemClock.elapsedRealtime() - t0} ms: grid ${state.gridKind}/" +
                    "${state.gridCount}${if (state.gridVisible) "" else " hidden"}, image " +
                    "${fetched?.bytes?.size ?: 0} B${if (state.imageVisible) "" else " hidden"}"
            }
        }
    }

    private class Fetched(val settings: SketchGuideSettings, val hasImage: Boolean, val bytes: ByteArray)

    /** The host's answer and the image's bytes, or null on any failure (logged, class name only). */
    private suspend fun fetch(pageKey: String): Fetched? = withContext(Dispatchers.IO) {
        val h = host() ?: return@withContext null
        try {
            val answer = h.guides(pageKey)
            val bytes = if (!answer.hasImage) {
                ByteArray(0)   // an absent image costs no chunk call
            } else {
                val chunks = ArrayList<ByteArray>(answer.imageChunks)
                for (i in 0 until answer.imageChunks) chunks += h.readGuideImageChunk(i)
                ByteChunks.join(chunks)
            }
            Fetched(answer.settings, answer.hasImage, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "the page's guides could not be read: ${t.javaClass.simpleName}")
            null
        }
    }

    // ── Settings picks ───────────────────────────────────────────────────────

    /** A pick on the panel: the sheet redrawn, and the page's settings remembered. */
    private fun pick(next: GuideState) {
        if (!usable()) return
        val key = pageKey ?: return
        state = next
        rebuild()
        remember(key, next)
        Slog.d(TAG) { "guides picked: $next" }
    }

    /** Rebuild the sheet from whatever [state] says when the lock is taken. */
    private fun rebuild() {
        activity.lifecycleScope.launch { sheetLock.withLock { applySheet() } }
    }

    /** `putGuides`, fire and forget, in pick order. Five small integers — loggable. */
    private fun remember(key: String, s: GuideState) {
        val settings = s.toSettings()
        activity.lifecycleScope.launch {
            settingsPushes.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val h = host() ?: throw IllegalStateException("no showing")
                        h.putGuides(key, settings)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "the page's guides could not be remembered: ${t.javaClass.simpleName} ($settings)")
                    }
                }
            }
        }
    }

    // ── The reference image ──────────────────────────────────────────────────

    /** Pick… — open the system picker for this page. No picker is a problem dialog. */
    private fun pickImage() {
        if (!usable()) return
        val key = pageKey ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
        try {
            pickingFor = key
            picker.launch(intent)
        } catch (e: ActivityNotFoundException) {
            pickingFor = null
            Log.w(TAG, "no document picker")
            Dialogs.problem(activity, R.string.guides_no_picker_title, R.string.guides_no_picker_body)
        }
    }

    private fun onPickResult(result: ActivityResult) {
        val wanted = pickingFor
        pickingFor = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return
        val key = pageKey ?: return
        // A result for another page — the face turned while the picker was up, which it cannot do
        // while stopped; a recreated face has no `wanted` at all and takes it for the page it shows.
        if (wanted != null && wanted != key) {
            Log.w(TAG, "a picked image arrived for a page no longer shown; dropped")
            return
        }
        if (!usable()) return
        activity.lifecycleScope.launch { takeImage(uri, key) }
    }

    /** Decode, fit, encode, push, and lay the picked image under the page [key] names. */
    private suspend fun takeImage(uri: Uri, key: String) {
        val w = pageWidth
        val h = pageHeight
        val t0 = SystemClock.elapsedRealtime()
        val prepared = withContext(Dispatchers.IO) { prepare(uri, w, h) }
        if (prepared !is Prepared.Ok) {
            problem(if (prepared is Prepared.TooLarge) R.string.guides_image_too_large_body else R.string.guides_image_unreadable_body)
            return
        }
        val error = saver.pushGuideImage(key, prepared.bytes)
        if (error != null) {
            prepared.page.recycle()
            problem(
                if (error.message == SketchContract.SKETCH_TOO_LARGE) R.string.guides_image_too_large_body
                else R.string.guides_image_not_saved_body,
            )
            return
        }
        Slog.d(TAG) { "reference image saved: ${prepared.bytes.size} B in ${SystemClock.elapsedRealtime() - t0} ms" }
        val landed = sheetLock.withLock {
            if (pageKey != key) {
                prepared.page.recycle()
                return@withLock false
            }
            replaceImage(prepared.page)
            state = state.withImage()
            applySheet()
            true
        }
        if (!landed) return
        remember(key, state)
        bar.refresh()
    }

    /** Remove — the page's image row soft-deleted, the sheet redrawn without it. */
    private fun removeImage() {
        if (!usable()) return
        val key = pageKey ?: return
        activity.lifecycleScope.launch {
            val error = saver.pushGuideImage(key, ByteArray(0))
            if (error != null) {
                problem(R.string.guides_image_not_removed_body)
                return@launch
            }
            val landed = sheetLock.withLock {
                if (pageKey != key) return@withLock false
                replaceImage(null)
                state = state.withoutImage()
                applySheet()
                true
            }
            if (!landed) return@launch
            remember(key, state)
            bar.refresh()
        }
    }

    private sealed class Prepared {
        class Ok(val bytes: ByteArray, val page: Bitmap) : Prepared()
        object TooLarge : Prepared()
        object Failed : Prepared()
    }

    /**
     * The whole IO half of a pick: the picture decoded at the smallest power-of-two sample that
     * keeps its long edge at or above the page's, fit into a page-sized transparent bitmap
     * ([GuideSheet.fitToPage]), encoded lossy ([GuideSheet.encode]), and checked **before** it is
     * offered to the host: over [SketchContract.MAX_BYTES] is refused here rather than half-pushed,
     * and bytes whose header does not say this page's size (a `VP8 ` file without `VP8X`) are the
     * host's `SKETCH_BAD_IMAGE` said early. Sizes and byte counts are logged; the picture never is.
     */
    private fun prepare(uri: Uri, pageW: Int, pageH: Int): Prepared {
        if (pageW <= 0 || pageH <= 0) return Prepared.Failed
        val decoded = try {
            val source = ImageDecoder.createSource(activity.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(GuideSheet.sampleSize(info.size.width, info.size.height, pageW, pageH))
            }
        } catch (e: Exception) {
            Log.w(TAG, "a picked image would not decode: ${e.javaClass.simpleName}")
            return Prepared.Failed
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "a picked image ran out of memory decoding")
            return Prepared.Failed
        }
        var page: Bitmap? = null
        try {
            page = GuideSheet.fitToPage(decoded, pageW, pageH) ?: return Prepared.Failed
            val bytes = GuideSheet.encode(page)
            Slog.d(TAG) { "picked ${decoded.width}x${decoded.height} → ${pageW}x$pageH, ${bytes.size} B" }
            if (bytes.isEmpty()) return Prepared.Failed.also { page.recycle() }
            if (bytes.size > SketchContract.MAX_BYTES) return Prepared.TooLarge.also { page.recycle() }
            if (!ImageHeader.matches(bytes, pageW, pageH)) {
                Log.w(TAG, "the encoded reference reads as ${ImageHeader.parse(bytes)?.chunk ?: "no WebP header"}, not a ${pageW}x$pageH VP8X; refused")
                page.recycle()
                return Prepared.Failed
            }
            return Prepared.Ok(bytes, page)
        } catch (e: OutOfMemoryError) {
            page?.recycle()
            Log.w(TAG, "a picked image ran out of memory fitting or encoding")
            return Prepared.Failed
        } finally {
            decoded.recycle()
        }
    }

    private fun problem(bodyRes: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.problem(activity, R.string.guides_image_not_saved_title, bodyRes)
    }

    // ── The sheet ────────────────────────────────────────────────────────────

    /** **Under [sheetLock], on Main.** Swap the decoded reference, recycling the old one — no build
     *  can be reading it, the lock says so. */
    private fun replaceImage(next: Bitmap?) {
        val old = image
        image = next
        if (old != null && old !== next) old.recycle()
    }

    /**
     * **Under [sheetLock], on Main.** Build the sheet for [state] on IO and set it; the one it
     * replaces is recycled only after `setSheet` has let go of it. A build that fails (a page-sized
     * allocation refused) is a line in the log and no sheet — the page itself is untouched.
     */
    private suspend fun applySheet() {
        val s = state
        val img = image
        val w = pageWidth
        val h = pageHeight
        val next = withContext(Dispatchers.IO) {
            try {
                GuideSheet.render(w, h, s, img)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "the guide sheet could not be allocated; the page shows without it")
                null
            } catch (e: Exception) {
                Log.w(TAG, "the guide sheet could not be drawn: ${e.javaClass.simpleName}")
                null
            }
        }
        if (activity.isDestroyed) { next?.recycle(); return }
        paper.setSheet(next)
        val old = sheet
        sheet = next
        if (old != null && old !== next) old.recycle()
    }

    private companion object {
        const val TAG = "SketchGuides"
    }
}
