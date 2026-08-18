package com.symmetricalpalmtree.notesprout.ext.heading

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.notesprout.extension.CreatedObject
import com.symmetricalpalmtree.notesprout.extension.EditSpec
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
import com.symmetricalpalmtree.notesprout.extension.IHandwritingRecognizer
import com.symmetricalpalmtree.notesprout.extension.IMarkdownRenderer
import com.symmetricalpalmtree.notesprout.extension.IObjectProvider
import com.symmetricalpalmtree.notesprout.extension.InkStroke
import com.symmetricalpalmtree.notesprout.extension.RenderedImage
import com.symmetricalpalmtree.notesprout.extension.SelectionAction

/**
 * The OBJECT_PROVIDER point's reference implementation (arc 4 / H3): the **heading** object type.
 * Bound by the Notesprout Paper core only; never launched by a user (no Activity). Stateless — a
 * heading is a core row whose payload is markdown (`HeadingText`); this service keeps nothing.
 *
 * The capabilities it needs arrive as in-parameters: `createFromInk` recognizes the lasso'd ink
 * through the core's recognizer proxy (the lasso box is the writing area, no pre-context — the
 * original's single-shot path); `render` hands the payload to the core's Markdown proxy and returns
 * that reply **as-is** (the proxy's region is the reply — the heading never decodes pixels; this
 * process's own handle on the region is parked per thread and closed in `onTransact`'s `finally`
 * once the reply — holding a dup of the descriptor — is marshalled, the Templates handshake). A
 * missing capability is `IllegalStateException(RECOGNIZER_REQUIRED / MARKDOWN_REQUIRED)`.
 *
 * Exceptions that cross Binder intact are the only ones thrown (`SecurityException`,
 * `IllegalArgumentException`, `IllegalStateException`). Logs: counts + durations — never text.
 */
class ObjectProviderService : Service() {

    private val binder = object : IObjectProvider.Stub() {

        /** The region received from the Markdown proxy and returned in this transaction's reply. */
        private val pending = ThreadLocal<SharedMemory>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                return super.onTransact(code, data, reply, flags)
            } finally {
                pending.get()?.close()
                pending.remove()
            }
        }

        override fun describeTypes(): MutableList<String> {
            enforce()
            return mutableListOf(HeadingActions.TYPE_ID)
        }

        override fun describeActions(): MutableList<SelectionAction> {
            enforce()
            return HeadingActions.describe().toMutableList()
        }

        override fun activeActionIds(typeId: String?, payload: String?): MutableList<String> {
            enforce()
            if (typeId != HeadingActions.TYPE_ID || payload == null) return mutableListOf()
            return mutableListOf(HeadingActions.leafId(HeadingText.levelOf(payload)))
        }

        override fun createFromInk(
            actionId: String?, strokes: MutableList<InkStroke>?, areaWidth: Float, areaHeight: Float,
            recognizer: IHandwritingRecognizer?,
        ): CreatedObject? {
            enforce()
            val level = HeadingActions.levelOf(actionId) ?: throw IllegalArgumentException("unknown action")
            requireNotNull(strokes) { "strokes is null" }
            require(strokes.size <= ExtensionContract.MAX_INK_STROKES) { "too many strokes" }
            require(areaWidth > 0f && areaHeight > 0f) { "non-positive writing area" }
            if (recognizer == null) throw IllegalStateException(ExtensionContract.RECOGNIZER_REQUIRED)
            val t0 = SystemClock.elapsedRealtime()
            val raw = try {
                recognizer.recognizeInk(strokes, areaWidth, areaHeight, "")
            } catch (e: android.os.RemoteException) {
                throw IllegalStateException("recognizer call failed: ${e.javaClass.simpleName}")
            }
            val text = HeadingText.fold(raw ?: "")
            if (BuildConfig.DEBUG) Log.d(TAG, "createFromInk h$level: ${strokes.size} strokes → ${text.length} chars in ${SystemClock.elapsedRealtime() - t0} ms")
            if (text.isBlank()) return null
            return CreatedObject(HeadingActions.TYPE_ID, HeadingText.withLevel(text, level))
        }

        override fun applyAction(actionId: String?, typeId: String?, payload: String?): String? {
            enforce()
            if (typeId != HeadingActions.TYPE_ID || payload == null) return null
            val level = HeadingActions.levelOf(actionId) ?: return null
            if (level == HeadingText.levelOf(payload)) return null   // same level → no change
            val words = HeadingText.strip(payload)
            if (words.isBlank()) return null
            return HeadingText.withLevel(words, level)
        }

        override fun describeEdit(typeId: String?, payload: String?): EditSpec? {
            enforce()
            if (typeId != HeadingActions.TYPE_ID || payload == null) return null
            return EditSpec(EDIT_TITLE, HeadingText.strip(payload), EDIT_HINT, EDIT_MAX_CHARS, false)
        }

        override fun applyEdit(typeId: String?, payload: String?, text: String?): String? {
            enforce()
            if (typeId != HeadingActions.TYPE_ID || payload == null) return null
            val words = HeadingText.fold(text ?: "")
            if (words.isBlank()) return null
            val next = HeadingText.withLevel(words, HeadingText.levelOf(payload))
            return if (next == payload) null else next
        }

        override fun render(typeId: String?, payload: String?, maxWidthPx: Int, dpi: Float, markdown: IMarkdownRenderer?): RenderedImage? {
            enforce()
            if (typeId != HeadingActions.TYPE_ID || payload == null) return null
            require(maxWidthPx > 0) { "maxWidthPx must be > 0" }
            require(dpi > 0f && !dpi.isNaN()) { "dpi must be > 0" }
            if (markdown == null) throw IllegalStateException(ExtensionContract.MARKDOWN_REQUIRED)
            val t0 = SystemClock.elapsedRealtime()
            val padding = Math.round(PADDING_DP * dpi / 160f).coerceIn(0, ExtensionContract.RENDER_PADDING_MAX_PX)
            val image = try {
                markdown.render(payload, maxWidthPx, dpi, MAX_LINES, padding)
            } catch (e: android.os.RemoteException) {
                throw IllegalStateException("markdown call failed: ${e.javaClass.simpleName}")
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "render: ${payload.length} chars → ${image?.widthPx ?: 0}x${image?.heightPx ?: 0} px in ${SystemClock.elapsedRealtime() - t0} ms")
            image?.let { pending.set(it.memory) }
            return image
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "ObjectProviderService"
        /** The original's heading typography: single line, 8 dp inner padding (part of the image). */
        const val MAX_LINES = 1
        const val PADDING_DP = 8f
        const val EDIT_TITLE = "Edit heading"
        const val EDIT_HINT = "Heading text"
        const val EDIT_MAX_CHARS = 500
    }
}
