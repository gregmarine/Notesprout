package com.symmetricalpalmtree.notesprout.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Cheap "are we online" answer (`ACCESS_NETWORK_STATE`): the active network carries validated internet. */
object Connectivity {
    fun isOnline(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
