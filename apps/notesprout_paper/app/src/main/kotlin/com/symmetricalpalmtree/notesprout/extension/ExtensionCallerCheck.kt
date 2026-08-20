package com.symmetricalpalmtree.notesprout.extension

import android.app.Activity
import android.content.pm.PackageManager

/**
 * Host-side trust gate for the one host-owned screen an extension launches (arc 7 / L3 —
 * `ACTION_LINK_NEW_NOTEBOOK_SCREEN` on `NewNotebookActivity`): the mirror of the extensions' own
 * `HostCallerCheck.enforceActivity`. `callingPackage` is set only for a launch-for-a-result — a
 * plain `startActivity` leaves it null and is refused — and must share this app's signature (the
 * host's own package passes trivially, so the library's launcher path needs no special case). On
 * any refusal the Activity is `finish()`ed and false returned; the caller returns from `onCreate`
 * at once without inflating anything.
 */
object ExtensionCallerCheck {

    fun enforceActivity(activity: Activity): Boolean {
        val caller = activity.callingPackage
        val ok = caller != null &&
            activity.packageManager.checkSignatures(caller, activity.packageName) == PackageManager.SIGNATURE_MATCH
        if (!ok) activity.finish()
        return ok
    }
}
