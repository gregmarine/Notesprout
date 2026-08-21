package com.symmetricalpalmtree.notesproutsn.core

import android.app.Activity
import android.content.Intent
import com.symmetricalpalmtree.notesproutsn.bootstrap.BootstrapActivity
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import java.util.Collections
import java.util.WeakHashMap

/**
 * Refuse to run a screen against a **closed** global index, and send it somewhere that can open one.
 *
 * [BootstrapActivity] is the only thing that opens (and if necessary unlocks) the index; it is the
 * launcher and finishes itself once it has forwarded, so it is not on the back stack. The one
 * route into a screen that never passes through it is Android rebuilding a task's activities by
 * itself after a background process kill (tap the app in Recents on a memory-tight e-ink device).
 * Nothing has opened the index in that process, and the first read would throw.
 *
 * Use it **first thing in `onCreate`** and nowhere else:
 * ```
 * if (!IndexGuard.ready(this)) return
 * ```
 * `onCreate` precedes every index touch, and nothing ever closes the index, so a screen past that
 * point cannot later find it shut. Finishing inside `onCreate` skips `onStart`/`onResume` — but
 * `onDestroy` still runs, so a screen that tears down `lateinit` state there must open with
 * `if (IndexGuard.bounced(this)) { super.onDestroy(); return }`.
 */
object IndexGuard {

    /** Activities [ready] turned away — weak keys so this never keeps a finished Activity alive. */
    private val bounced: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /**
     * True when the index is open. Otherwise sends the task back through [BootstrapActivity] as
     * the root of a clean task (`NEW_TASK | CLEAR_TASK`), finishes [activity], and returns false —
     * **return immediately**. Idempotent via [Activity.isFinishing].
     */
    fun ready(activity: Activity): Boolean {
        if (SnIndex.isReady()) return true
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

    /** True when [ready] turned [activity] away, so its `onDestroy` has nothing to release. */
    fun bounced(activity: Activity): Boolean = activity in bounced
}
