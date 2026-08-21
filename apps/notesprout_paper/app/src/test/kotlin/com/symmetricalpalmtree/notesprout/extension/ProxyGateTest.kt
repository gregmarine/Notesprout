package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyGateTest {

    @Test
    fun happyPath() {
        val gate = ProxyGate(extUid = 10_042, callingUid = { 10_042 })
        gate.check()
        gate.check()
        assertFalse(gate.revoked)
    }

    @Test
    fun uidMismatchRefused() {
        val gate = ProxyGate(extUid = 10_042, callingUid = { 10_043 })
        assertThrows(SecurityException::class.java) { gate.check() }
    }

    @Test
    fun revokedRefusedEvenForTheRightUid() {
        val gate = ProxyGate(extUid = 10_042, callingUid = { 10_042 })
        gate.check()
        gate.revoke()
        assertTrue(gate.revoked)
        assertThrows(SecurityException::class.java) { gate.check() }
        gate.revoke()   // idempotent
        assertThrows(SecurityException::class.java) { gate.check() }
    }
}
