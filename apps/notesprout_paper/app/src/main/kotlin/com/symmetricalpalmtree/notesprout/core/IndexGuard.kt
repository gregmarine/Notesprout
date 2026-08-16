package com.symmetricalpalmtree.notesprout.core

import android.app.Activity
import java.util.Collections
import java.util.WeakHashMap

object IndexGuard {

    private val bounced: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    fun ready(activity: Activity): Boolean {
        // Stub — always returns true until Phase 1 wires the real index.
        return true
    }

    fun bounced(activity: Activity): Boolean = activity in bounced
}
