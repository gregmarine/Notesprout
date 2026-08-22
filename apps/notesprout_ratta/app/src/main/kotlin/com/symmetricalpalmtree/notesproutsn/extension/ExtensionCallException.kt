package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The one failure type an extension call surfaces to the host: no provider connection, bind or call
 * timeout, `RemoteException`, `SecurityException`, a payload that failed validation — the caller
 * sees a single "the extension didn't answer" shape and decides what to tell the user.
 */
open class ExtensionCallException(message: String, cause: Throwable? = null) : Exception(message, cause)
