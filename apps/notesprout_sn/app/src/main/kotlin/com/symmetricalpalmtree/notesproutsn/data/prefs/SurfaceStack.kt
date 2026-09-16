package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The screens a cold launch can put back (arc 32 "Resume", decision 1; the Bible reader joined at
 * arc 37 / B0 on the user's call, the sketch face at arc 43 / K4). Templates, Backup, Export, Tags,
 * Encryption, Restore and every picker are deliberately **not** here: a surface that is not named
 * cannot be restored, which is the whole of the exclusion rule.
 *
 * **New names go at the end.** The stored blob carries the enum *name*, so the order here is not on
 * the wire — but a name this build does not know is dropped entry by entry
 * ([SurfaceStackCodec.decode]), which is what lets an older build read a stack a newer one wrote.
 */
enum class Surface { NOTEBOOK, CALENDAR, SCRATCH_PAD, DOCUMENT_EDITOR, BIBLE, SKETCH }

/**
 * One screen on the surface stack. **Ids and enum names only** — never a name, never a page:
 * per-surface position is each surface's own (the notebook's `refId`, the calendar's `state`, the
 * pad's `current`, the editor's `caret`), so an entry says only *which* surface, plus the notebook
 * it is and whether it was opened via a link where that applies.
 *
 * [token] identifies the Activity or entry **instance**, not the surface: the same notebook can
 * legitimately be on the stack twice (a link followed into itself), and a screen that finishes
 * itself into another one pops its own token only.
 */
data class SurfaceEntry(
    val token: String,
    val surface: Surface,
    val notebookId: String? = null,
    val viaLink: Boolean = false,
)

/**
 * The pure stack algebra (arc 32 / RS1) — a bottom-first list of [SurfaceEntry] and the four
 * mutations the screens make, Context-free so every rule is JVM-tested.
 *
 * - [attach] appends, or refreshes **in place** when the token is already there (a same-process
 *   recreate hands its saved token back and must not duplicate itself).
 * - [markTop] drops everything above the token — the resumed screen is the top by definition. A
 *   token that is not on the stack leaves it unchanged: a screen that never attached (an
 *   `IndexGuard` bounce) has no place to claim.
 * - [pop] removes that token's entry, wherever it is, and nothing else.
 *
 * [decode] treats the stored blob as untrusted input: a corrupt blob is an empty stack, and an
 * entry naming a surface this build does not know is dropped on its own — never the whole stack,
 * never a crash.
 */
object SurfaceStackCodec {

    /** The wire shape: the surface rides as its enum **name**, so an unknown one can be dropped
     *  entry by entry instead of failing the list. */
    @Serializable
    private data class Stored(
        val token: String,
        val surface: String,
        val notebookId: String? = null,
        val viaLink: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Stored.serializer())

    /** Never throws: null, blank, corrupt, or valid JSON of the wrong shape all read as empty; an
     *  entry with an unknown surface name or a blank token is dropped on its own. */
    fun decode(raw: String?): List<SurfaceEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val list = try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return list.mapNotNull { s ->
            if (s.token.isBlank()) return@mapNotNull null
            val surface = runCatching { Surface.valueOf(s.surface) }.getOrNull() ?: return@mapNotNull null
            SurfaceEntry(s.token, surface, s.notebookId, s.viaLink)
        }
    }

    fun encode(entries: List<SurfaceEntry>): String = json.encodeToString(
        serializer,
        entries.map { Stored(it.token, it.surface.name, it.notebookId, it.viaLink) },
    )

    fun attach(entries: List<SurfaceEntry>, entry: SurfaceEntry): List<SurfaceEntry> {
        val at = entries.indexOfFirst { it.token == entry.token }
        return if (at < 0) entries + entry
        else entries.toMutableList().also { it[at] = entry }
    }

    fun markTop(entries: List<SurfaceEntry>, token: String): List<SurfaceEntry> {
        val at = entries.indexOfFirst { it.token == token }
        return if (at < 0) entries else entries.subList(0, at + 1).toList()
    }

    fun pop(entries: List<SurfaceEntry>, token: String): List<SurfaceEntry> =
        entries.filterNot { it.token == token }

    /**
     * The pre-arc install's one-notebook restore (`lastOpenNotebookId` + `lastOpenViaLink`), read
     * as a one-entry stack. Only when there is no stack at all — a stack that exists, even empty,
     * was written by this build and the legacy keys are stale beside it.
     */
    fun migrate(stackRaw: String?, legacyNotebookId: String?, legacyViaLink: Boolean): List<SurfaceEntry> {
        if (stackRaw != null) return decode(stackRaw)
        val id = legacyNotebookId ?: return emptyList()
        return listOf(SurfaceEntry(token = "legacy", surface = Surface.NOTEBOOK, notebookId = id, viaLink = legacyViaLink))
    }
}

/**
 * `SharedPreferences("sn_view_state")`, key `surfaceStack` — the screens the user had open, so a
 * cold launch can put the whole chain back (arc 32 "Resume"). Beside [BrowseState] on purpose: the
 * same file, the same kind of thing — device-local browsing state, ids and enum names only, never
 * backed up, nothing trusted as still existing (`LibraryActivity` re-validates every entry on the
 * way back).
 *
 * Maintained from lifecycle by the host screens (attach in `onCreate`, mark top in `onResume`, pop
 * at every close) and from their entries by the extension screens (push after the launch, pop at
 * the result) — never from `onDestroy`, which a killed process never gets. **Main thread only**;
 * every mutation is mirrored to prefs at once.
 *
 * All the rules live in [SurfaceStackCodec]; this is only the prefs door.
 */
class SurfaceStack(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun entries(): List<SurfaceEntry> = SurfaceStackCodec.decode(prefs.getString(KEY, null))

    fun attach(entry: SurfaceEntry) = save(SurfaceStackCodec.attach(entries(), entry))

    fun markTop(token: String) = save(SurfaceStackCodec.markTop(entries(), token))

    fun pop(token: String) = save(SurfaceStackCodec.pop(entries(), token))

    /** Nothing is open: the library is resumed, and nothing can stand above it. */
    fun reset() = save(emptyList())

    /**
     * The cold-launch read: the stack as it was, then **cleared at once** — a target that fails is
     * never retried on the next launch (`reopenLastNotebookIfNeeded`'s read-once rule, kept). The
     * pre-arc keys are migrated here on their first read and removed with the rest.
     */
    fun snapshotAndClear(): List<SurfaceEntry> {
        val stack = SurfaceStackCodec.migrate(
            stackRaw = prefs.getString(KEY, null),
            legacyNotebookId = prefs.getString(LEGACY_KEY_LAST_OPEN, null),
            legacyViaLink = prefs.getBoolean(LEGACY_KEY_LAST_VIA_LINK, false),
        )
        prefs.edit().remove(KEY).remove(LEGACY_KEY_LAST_OPEN).remove(LEGACY_KEY_LAST_VIA_LINK).apply()
        return stack
    }

    private fun save(entries: List<SurfaceEntry>) {
        prefs.edit().putString(KEY, SurfaceStackCodec.encode(entries)).apply()
    }

    private companion object {
        const val FILE = "sn_view_state"
        const val KEY = "surfaceStack"
        /** `BrowseState`'s pre-arc-32 keys — read once by [snapshotAndClear], then gone. */
        const val LEGACY_KEY_LAST_OPEN = "lastOpenNotebookId"
        const val LEGACY_KEY_LAST_VIA_LINK = "lastOpenViaLink"
    }
}
