package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The two wrappers' construction rules (arc 22 / X1) — the constructor IS the unmarshal validation. */
class StoreWireRulesTest {

    @Test
    fun payload_exactlyOneCarrier() {
        StorePayload.requireValid(0, false)
        StorePayload.requireValid(ExtensionContract.STORE_MAX_INLINE_BYTES, false)
        StorePayload.requireValid(null, true)
        assertThrows(IllegalArgumentException::class.java) { StorePayload.requireValid(null, false) }
        assertThrows(IllegalArgumentException::class.java) { StorePayload.requireValid(1, true) }
        assertThrows(IllegalArgumentException::class.java) {
            StorePayload.requireValid(ExtensionContract.STORE_MAX_INLINE_BYTES + 1, false)
        }
    }

    @Test
    fun payload_inline_readsBackWhole() {
        val bytes = byteArrayOf(1, 2, 3)
        val p = StorePayload.of(bytes)
        assertArrayEquals(bytes, p.inline)
        assertEquals(3, p.byteCount)
        assertArrayEquals(bytes, p.readAndClose())
        assertEquals(0, p.describeContents())
        assertThrows(IllegalArgumentException::class.java) { StorePayload(null, null) }
    }

    @Test
    fun result_handleRule() {
        StoreResult.requireValid(StoreResult.NO_HANDLE, false)
        StoreResult.requireValid(0, true)
        StoreResult.requireValid(7, true)
        assertThrows(IllegalArgumentException::class.java) { StoreResult.requireValid(-1, true) }
        assertThrows(IllegalArgumentException::class.java) { StoreResult.requireValid(0, false) }
        assertThrows(IllegalArgumentException::class.java) { StoreResult.requireValid(3, false) }
        assertEquals(-1, StoreResult.NO_HANDLE)
        val r = StoreResult(StorePayload(byteArrayOf(), null), StoreResult.NO_HANDLE, false)
        assertEquals(0, r.describeContents())
    }
}
