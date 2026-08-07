package com.notesprout.android.core

import android.app.Activity
import android.content.Intent
import com.notesprout.android.BootstrapActivity
import com.notesprout.android.data.index.NotesproutIndex
import java.util.Collections
import java.util.WeakHashMap

/**
 * Refuse to run a surface against a **closed** global index, and send it somewhere that can open one.
 *
 * [BootstrapActivity] is the only thing that ever opens (and if necessary unlocks) the index. It is
 * the launcher entry point, and it `finish()`es itself once it has forwarded — so it is *not* on the
 * back stack. `Application.onCreate` deliberately only `awaitReady()`s, because opening can need a
 * passphrase prompt and so cannot happen there.
 *
 * That leaves one route into a surface that never passes through Bootstrap: **Android rebuilding a
 * task's activities by itself** after the process was killed in the background. Tapping the app in
 * Recents is the everyday case, and on a memory-tight e-ink device it is an ordinary Tuesday. Nothing
 * has opened the index in that process, and the first read throws:
 *
 * ```
 * java.lang.IllegalStateException: NotesproutIndex is not open — call ensureReady() first
 *     at NotesproutIndex.db(NotesproutIndex.kt:169)
 *     at …Repository.<init> → <Surface>Activity.onCreate/onResume
 * ```
 *
 * Every index-backed repository throws when *constructed*, because each resolves its DAO in a default
 * constructor argument. A `by lazy` field defers that to the first read rather than avoiding it.
 *
 * ## Use it in `onCreate`, and nowhere else
 *
 * ```kotlin
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     if (!IndexGuard.ready(this)) return
 *     …
 * }
 * ```
 *
 * `onCreate` is both the earliest point and the only one needed:
 *
 * - **Earliest** — it precedes every index touch, whether the surface constructs its repository
 *   eagerly here or lazily on first read in `onResume`.
 * - **Only** — nothing in the app ever *closes* the index. There is no `NotesproutIndex.close()`; it
 *   opens once per process and stays open. So a surface that got past `onCreate` cannot later find it
 *   shut, and an `onResume` guard would be dead code.
 * - **Safe to return from** — finishing inside `onCreate` skips `onStart` and `onResume`, so a
 *   half-initialised screen is never started or resumed.
 *
 * Placing it before anything else also leaves app state untouched on the way out — notably
 * [com.notesprout.android.state.SurfaceStack], whose entries are what Bootstrap's restore reads to
 * put the user back where they were. A surface that recorded itself before bouncing would corrupt the
 * chain it is about to be restored from.
 *
 * ## …and check [bounced] in `onDestroy`
 *
 * `onDestroy` **does** still run — it is the one lifecycle method finishing early does not skip. A
 * screen that tears down `lateinit` views or engines there will die on the way out instead:
 *
 * ```
 * kotlin.UninitializedPropertyAccessException: lateinit property drawingView has not been initialized
 *     at CalendarActivity.onDestroy(CalendarActivity.kt:2342)
 * ```
 *
 * So any surface with an `onDestroy` that touches something built later in `onCreate` must open it
 * with:
 *
 * ```kotlin
 * override fun onDestroy() {
 *     if (IndexGuard.bounced(this)) { super.onDestroy(); return }
 *     …
 * }
 * ```
 *
 * Screens without an `onDestroy` override need nothing — there is no teardown to skip.
 */
object IndexGuard {

    /**
     * Activities [ready] has turned away, so [bounced] can tell their teardown there is nothing to
     * tear down.
     *
     * Weak keys: this must never be the reason an Activity outlives its destruction. Entries are
     * dropped by the collector once the finished Activity is unreachable, and the set is only ever
     * touched from the main thread, which is where the lifecycle callbacks run.
     */
    private val bounced: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /**
     * True when the index is open and [activity] may read it. Otherwise sends the task back through
     * [BootstrapActivity], finishes [activity], and returns false — **return immediately**.
     *
     * `NEW_TASK or CLEAR_TASK` so Bootstrap becomes the root of a clean task: the rebuilt activities
     * above it are all in the same closed-index state and would each fail in turn. Bootstrap opens
     * the index, forwards to the library, and the library's cold-launch restore rebuilds the whole
     * surface chain properly.
     *
     * The [Activity.isFinishing] check makes this idempotent, so a second call cannot start a second
     * Bootstrap.
     */
    fun ready(activity: Activity): Boolean {
        if (NotesproutIndex.isReady()) return true
        bounced += activity
        if (!activity.isFinishing) {
            activity.startActivity(
                Intent(activity, BootstrapActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            activity.finish()
        }
        return false
    }

    /**
     * True when [ready] turned [activity] away, so it never finished building and its `onDestroy`
     * has nothing to release. Only screens that override `onDestroy` need to ask.
     */
    fun bounced(activity: Activity): Boolean = activity in bounced
}
