package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `LargeValue` wraps an ashmem region, so only its validation is JVM-reachable — and that is the
 * part that matters: `requireValid` runs at construction, which means it also runs at **unmarshal**,
 * where it is the one thing standing between a malformed parcel and a read past the region's end.
 */
class LargeValueTest {

    @Test
    fun acceptsZeroBytes_inAOneByteRegion() {
        // ashmem refuses a zero-size region, so an empty value rides a 1-byte one with byteCount 0.
        LargeValue.requireValid(byteCount = 0, memorySize = 1)
    }

    @Test
    fun acceptsUpToTheValueCap() {
        LargeValue.requireValid(
            byteCount = ExtensionContract.STORE_MAX_VALUE_BYTES,
            memorySize = ExtensionContract.STORE_MAX_VALUE_BYTES,
        )
    }

    @Test
    fun rejectsOverTheValueCap() {
        assertThrows(IllegalArgumentException::class.java) {
            LargeValue.requireValid(
                byteCount = ExtensionContract.STORE_MAX_VALUE_BYTES + 1,
                memorySize = ExtensionContract.STORE_MAX_VALUE_BYTES + 1,
            )
        }
    }

    @Test
    fun rejectsNegativeCount() {
        assertThrows(IllegalArgumentException::class.java) {
            LargeValue.requireValid(byteCount = -1, memorySize = 16)
        }
    }

    @Test
    fun rejectsCountBeyondTheRegion() {
        assertThrows(IllegalArgumentException::class.java) {
            LargeValue.requireValid(byteCount = 17, memorySize = 16)
        }
    }
}
