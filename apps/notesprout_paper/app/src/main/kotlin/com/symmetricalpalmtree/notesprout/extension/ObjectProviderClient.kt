package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesprout.core.IconCatalog
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.notebook.ToolbarAction

/** The provider needs a capability the host could not lend (nothing installed): `Requires.RECOGNIZER` or `Requires.MARKDOWN`. */
class CapabilityRequiredException(val requires: Int, cause: Throwable) :
    ExtensionCallException(if (requires == Requires.RECOGNIZER) ExtensionContract.RECOGNIZER_REQUIRED else ExtensionContract.MARKDOWN_REQUIRED, cause)

/**
 * Bind-per-operation client for one object provider (arc 4 / H3), over the shared [ExtensionBinder]
 * (signature re-check at bind, bind ≤ 3 s, IO, unbind in `finally`, every failure → one
 * [ExtensionCallException]). Stateless point — no store; the objects live in the core's `.soil`.
 *
 * **The two proxies are minted per bind and revoked in this client's own `finally`, right after the
 * shared unbind** (the `NamerClient` store shape): [RecognizerProxyBinder] is handed only to
 * [createFromInk], [MarkdownProxyBinder] only to [render]; each is **null** when the corresponding
 * registry lookup ([recognizerRef] / [markdownRef]) is empty — the core never fakes a capability.
 * A proxied call is two hops, so those two budgets are the inner timeout plus a margin
 * ([CREATE_TIMEOUT_MS] / [RENDER_TIMEOUT_MS]); everything else is [CALL_TIMEOUT_MS].
 *
 * **Everything inward is untrusted**: `describeTypes` filtered to well-formed typeIds and capped;
 * `describeActions` through [ActionCaps] (icons via the core catalog); `activeActionIds` id-checked;
 * a [CreatedObject]'s typeId must be one the provider declares (same bind) and its payload is cut to
 * `MAX_OBJECT_TEXT_CHARS`; `describeEdit` through [EditCaps]; the rendered image through
 * [RenderedImages.copyOut]. **Outward**: the payload is cut to `MAX_OBJECT_TEXT_CHARS`, ink runs
 * [InkCaps.check] before the bind, edit text is cut to `MAX_EDIT_TEXT_CHARS`, render args pass
 * [RenderCaps.checkArgs]. The provider's `IllegalStateException(RECOGNIZER_REQUIRED / MARKDOWN_REQUIRED)`
 * surfaces typed as [CapabilityRequiredException]; a proxied `RECOGNIZER_NOT_READY` as
 * [RecognizerNotReadyException]. Logs (tag [TAG]): counts + durations — **never a payload**.
 */
class ObjectProviderClient(
    context: Context,
    private val ref: ProviderRef,
    private val recognizerRef: ProviderRef? = null,
    private val markdownRef: ProviderRef? = null,
) {

    private val appContext = context.applicationContext

    /** The typeIds the provider owns — well-formed ones only, at most `MAX_TYPES`. */
    suspend fun describeTypes(): Set<String> = call(CALL_TIMEOUT_MS) { p -> types(p) }

    /** The provider's toolbar contributions, capped and icon-resolved ([ActionCaps]); label = the provider's. */
    suspend fun describeActions(): List<ToolbarAction> = call(CALL_TIMEOUT_MS) { p ->
        val raw = p.describeActions()?.filterNotNull() ?: emptyList()
        ActionCaps.sanitize(raw, ref.label.toString(), IconCatalog::resolve)
    }

    /** Which of the provider's action ids are active for this object (well-formed ids only, capped). */
    suspend fun activeActionIds(typeId: String, payload: String): Set<String> = call(CALL_TIMEOUT_MS) { p ->
        val raw = p.activeActionIds(typeId, outPayload(payload))?.filterNotNull() ?: emptyList()
        raw.asSequence()
            .filter { it.length in 1..ExtensionContract.MAX_ACTION_ID_CHARS && SelectionAction.ID_PATTERN.matches(it) }
            .take(ExtensionContract.MAX_ACTIONS * (1 + ExtensionContract.MAX_SUB_ACTIONS))
            .toSet()
    }

    /**
     * Turn a pure-stroke selection into an object through the leaf [actionId]. The recognizer proxy
     * (or null) is minted for this bind. Returns null when the provider recognized nothing usable.
     */
    suspend fun createFromInk(actionId: String, strokes: List<InkStroke>, areaWidth: Float, areaHeight: Float): CreatedObject? {
        InkCaps.check(strokes, areaWidth, areaHeight)
        val proxy = recognizerRef?.let { RecognizerProxyBinder(RecognizerClient(appContext, it), extUid()) }
        val t0 = System.currentTimeMillis()
        try {
            val created = call(CREATE_TIMEOUT_MS) { p ->
                val c = p.createFromInk(actionId, strokes, areaWidth, areaHeight, proxy) ?: return@call null
                if (c.typeId !in types(p)) throw ExtensionCallException("created typeId '${c.typeId}' is not one the provider declares")
                CreatedObject(c.typeId, c.payload.take(ExtensionContract.MAX_OBJECT_TEXT_CHARS))
            }
            Slog.d(TAG) { "createFromInk $actionId: ${strokes.size} strokes → ${created?.let { "type ${it.typeId}, ${it.payload.length} chars" } ?: "nothing"} in ${System.currentTimeMillis() - t0} ms" }
            return created
        } finally {
            proxy?.revoke()
        }
    }

    /** The payload after the leaf [actionId], or null for "no change". */
    suspend fun applyAction(actionId: String, typeId: String, payload: String): String? = call(CALL_TIMEOUT_MS) { p ->
        inPayload(p.applyAction(actionId, typeId, outPayload(payload)))
    }

    /** How to draw the edit dialog ([EditCaps] applied), or null when the object is not editable. */
    suspend fun describeEdit(typeId: String, payload: String): EditSpec? = call(CALL_TIMEOUT_MS) { p ->
        p.describeEdit(typeId, outPayload(payload))?.let(EditCaps::sanitize)
    }

    /** The payload after the user saved [text], or null for "no change". */
    suspend fun applyEdit(typeId: String, payload: String, text: String): String? = call(CALL_TIMEOUT_MS) { p ->
        inPayload(p.applyEdit(typeId, outPayload(payload), text.take(ExtensionContract.MAX_EDIT_TEXT_CHARS)))
    }

    /**
     * Render the object at [dpi] within [maxWidthPx]. The markdown proxy (or null) is minted for this
     * bind. Returns the verified WEBP + size, or null when there is nothing to draw.
     */
    suspend fun render(typeId: String, payload: String, maxWidthPx: Int, dpi: Float): RenderedImages.Copy? {
        RenderCaps.checkArgs(maxWidthPx, dpi, 0, 0)
        val proxy = markdownRef?.let { MarkdownProxyBinder(MarkdownClient(appContext, it), extUid()) }
        val t0 = System.currentTimeMillis()
        try {
            val copy = call(RENDER_TIMEOUT_MS) { p ->
                val image = p.render(typeId, outPayload(payload), maxWidthPx, dpi, proxy) ?: return@call null
                RenderedImages.copyOut(image)
            }
            Slog.d(TAG) { "render $typeId: ${payload.length} chars → ${copy?.let { "${it.widthPx}x${it.heightPx} px, ${it.bytes.size} B" } ?: "nothing"} in ${System.currentTimeMillis() - t0} ms" }
            return copy
        } finally {
            proxy?.revoke()
        }
    }

    private fun types(p: IObjectProvider): Set<String> =
        (p.describeTypes()?.filterNotNull() ?: emptyList()).asSequence()
            .filter(ExtensionContract::isTypeId).take(ExtensionContract.MAX_TYPES).toSet()

    private fun outPayload(payload: String): String = payload.take(ExtensionContract.MAX_OBJECT_TEXT_CHARS)

    /** Inward payload: null / blank → null (no change), else cut to the cap. */
    private fun inPayload(raw: String?): String? = raw?.take(ExtensionContract.MAX_OBJECT_TEXT_CHARS)?.takeIf { it.isNotBlank() }

    private fun extUid(): Int = try {
        appContext.packageManager.getPackageUid(ref.packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        throw ExtensionCallException("package gone: ${ref.packageName}", e)
    }

    private suspend fun <T> call(timeoutMs: Long, block: (IObjectProvider) -> T): T =
        ExtensionBinder.call(
            appContext, ref, ExtensionContract.ACTION_OBJECT_PROVIDER, TAG,
            asInterface = { IObjectProvider.Stub.asInterface(it) },
            callTimeoutMs = timeoutMs,
        ) { provider ->
            try {
                block(provider)
            } catch (e: IllegalStateException) {
                // Binder-marshalable by contract; three messages are typed so the caller can name what is missing.
                when (e.message) {
                    ExtensionContract.RECOGNIZER_REQUIRED -> throw CapabilityRequiredException(Requires.RECOGNIZER, e)
                    ExtensionContract.MARKDOWN_REQUIRED -> throw CapabilityRequiredException(Requires.MARKDOWN, e)
                    ExtensionContract.RECOGNIZER_NOT_READY -> throw RecognizerNotReadyException(e)
                    else -> throw ExtensionCallException("${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }

    companion object {
        private const val TAG = "ObjectProviderClient"
        /** describe / apply / edit — pure calls. */
        const val CALL_TIMEOUT_MS = 2_000L
        /** One recognizer hop inside (`RecognizerClient.INK_TIMEOUT_MS` 10 s) + margin. */
        const val CREATE_TIMEOUT_MS = 15_000L
        /** One markdown hop inside (`MarkdownClient.RENDER_TIMEOUT_MS` 5 s) + margin. */
        const val RENDER_TIMEOUT_MS = 8_000L
    }
}
