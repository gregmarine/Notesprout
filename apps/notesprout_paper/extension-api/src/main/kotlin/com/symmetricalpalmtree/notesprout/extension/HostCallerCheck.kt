package com.symmetricalpalmtree.notesprout.extension

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process

/**
 * Extension-side trust gate (belt-and-braces with the host's own signature check). Every AIDL stub
 * method of an extension calls [enforce] first: the caller's uid must map to [hostPackage] AND share
 * this extension's signature. Anything else is refused with a `SecurityException`. Shared by every
 * first-party extension and available to third parties through this library.
 *
 * [enforceActivity] is the sibling for an **extension-owned screen** (arc 6): an exported Activity
 * the host launches for a result calls it first thing in `onCreate` — before `setContentView` — and
 * returns if it answers false (the Activity has already been finished).
 */
object HostCallerCheck {

    fun enforce(context: Context, hostPackage: String) {
        val pm = context.packageManager
        val uid = Binder.getCallingUid()
        val callerPackages = pm.getPackagesForUid(uid) ?: emptyArray()
        if (hostPackage !in callerPackages) {
            throw SecurityException("caller is not the host")
        }
        if (pm.checkSignatures(uid, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("caller is not the host")
        }
    }

    /**
     * The Activity form of the gate: `callingPackage` (set only for a `startActivityForResult`-style
     * launch — a plain `startActivity` leaves it null and is refused) must equal [hostPackage] AND
     * share this extension's signature. On any refusal the Activity is `finish()`ed and false is
     * returned; the caller returns from `onCreate` at once without inflating anything.
     */
    fun enforceActivity(activity: Activity, hostPackage: String): Boolean {
        val caller = activity.callingPackage
        val ok = caller == hostPackage &&
            activity.packageManager.checkSignatures(caller, activity.packageName) == PackageManager.SIGNATURE_MATCH
        if (!ok) activity.finish()
        return ok
    }
}
