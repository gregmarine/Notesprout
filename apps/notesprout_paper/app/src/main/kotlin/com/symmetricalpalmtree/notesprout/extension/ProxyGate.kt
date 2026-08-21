package com.symmetricalpalmtree.notesprout.extension

/**
 * The uid / revocation check behind every capability proxy the host lends an extension (arc 4 /
 * H3 — `RecognizerProxyBinder`, `MarkdownProxyBinder`): the `ExtensionStoreGate` shape without the
 * store. Pure (the calling-uid supplier is injected) so it runs on the JVM.
 *
 * A proxy is minted **per bind** for one extension uid ([extUid], from `PackageManager.getPackageUid`)
 * and [revoke]d in the client's `finally` right after the unbind; every proxied method calls [check]
 * first — the caller must be that uid and the gate not revoked, else `SecurityException` (which
 * Binder carries intact). A late call from an orphaned transaction therefore fails closed.
 */
class ProxyGate(
    private val extUid: Int,
    private val callingUid: () -> Int,
) {
    @Volatile
    var revoked: Boolean = false
        private set

    /** After this every proxied method throws `SecurityException`. */
    fun revoke() {
        revoked = true
    }

    /** `SecurityException` unless the caller is [extUid] and the gate is live. */
    fun check() {
        if (revoked) throw SecurityException("proxy revoked")
        if (callingUid() != extUid) throw SecurityException("proxy belongs to another uid")
    }
}
