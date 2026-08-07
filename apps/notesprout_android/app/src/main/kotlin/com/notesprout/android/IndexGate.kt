package com.notesprout.android

import android.app.Activity
import android.content.Intent
import com.notesprout.android.data.index.NotesproutIndex

/**
 * Bounce back through [BootstrapActivity] when the encrypted index is not open yet.
 *
 * **Why any screen needs this.** The index cannot be opened synchronously — it is encrypted, and
 * preparing it may require a one-time migration or an unlock prompt — so `Application.onCreate`
 * cannot do it and `NotesproutIndex.db()` *throws* when it is not ready. [BootstrapActivity] is the
 * gate that calls `ensureReady()`, and on a normal launch every screen is reached through it.
 *
 * **But Android can start a screen without it.** When the system reclaims a backgrounded process and
 * the user returns, it restores the task by recreating the **top Activity directly** in a fresh
 * process — Bootstrap never runs. The same happens after a crash, and for a `.soil` deep link. Any
 * screen that touches the index in `onCreate` then dies with
 * `IllegalStateException: NotesproutIndex is not open`. Reproduced on a NoteAir5C by backgrounding
 * the app, `am kill`-ing it, and tapping the icon to return.
 *
 * The bounce preserves the intent so a deep link survives, and preparation/unlock still happens
 * exactly once, in the one place that knows how to ask for it. Bootstrap's own forward then restores
 * the user's surface stack, so they land back where they were.
 *
 * Call at the very top of `onCreate`, immediately after `super.onCreate`, and **return when it
 * returns true** — the Activity has been finished and must not continue:
 *
 * ```
 * super.onCreate(savedInstanceState)
 * if (bounceIfIndexNotReady()) return
 * ```
 *
 * [BootstrapActivity] itself must never call this: it *is* the gate, and would bounce forever.
 */
fun Activity.bounceIfIndexNotReady(): Boolean {
    if (NotesproutIndex.isReady()) return false
    bounced += this
    startActivity(Intent(intent).setClass(this, BootstrapActivity::class.java))
    finish()
    return true
}

/**
 * Activities that bailed out of `onCreate` via [bounceIfIndexNotReady], so their `onDestroy` knows
 * not to tear down state that was never built.
 *
 * `finish()` inside `onCreate` makes Android skip `onStart`/`onResume`/`onPause`/`onStop` and go
 * **straight to `onDestroy`** — so that one callback is the only exposure, but it is a real one: it
 * runs against half-constructed Activities and will throw on any `lateinit` the aborted `onCreate`
 * never reached. Weakly held, so a finished Activity is still collectable.
 */
private val bounced: MutableSet<Activity> =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap())

/**
 * True when this Activity aborted `onCreate` via [bounceIfIndexNotReady]. Any `onDestroy` that
 * touches state built during `onCreate` must check this first:
 *
 * ```
 * override fun onDestroy() {
 *     if (indexBounced) { super.onDestroy(); return }
 *     …
 * }
 * ```
 */
val Activity.indexBounced: Boolean get() = this in bounced
