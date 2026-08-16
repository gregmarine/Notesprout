package com.symmetricalpalmtree.notesprout.ext.templates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process

/**
 * Extension-side trust gate (belt-and-braces with the host's own signature check). Every AIDL stub
 * method calls [enforce] first: the caller's uid must map to [BuildConfig.HOST_PACKAGE] AND share this
 * extension's signature. Anything else is refused.
 */
object CallerCheck {

    fun enforce(context: Context) {
        val pm = context.packageManager
        val uid = Binder.getCallingUid()
        val callerPackages = pm.getPackagesForUid(uid) ?: emptyArray()
        if (BuildConfig.HOST_PACKAGE !in callerPackages) {
            throw SecurityException("caller is not the host")
        }
        if (pm.checkSignatures(uid, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("caller is not the host")
        }
    }
}
