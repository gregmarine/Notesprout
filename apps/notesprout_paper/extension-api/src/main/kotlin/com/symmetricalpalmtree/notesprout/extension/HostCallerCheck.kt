package com.symmetricalpalmtree.notesprout.extension

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process

/**
 * Extension-side trust gate (belt-and-braces with the host's own signature check). Every AIDL stub
 * method of an extension calls [enforce] first: the caller's uid must map to [hostPackage] AND share
 * this extension's signature. Anything else is refused with a `SecurityException`. Shared by every
 * first-party extension and available to third parties through this library.
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
}
