package com.symmetricalpalmtree.notesproutsn.extension

import java.util.Base64
import java.util.UUID

/**
 * A UUID written short (arc 21 / W4) — pure, stdlib only, and **a storage encoding, nothing more**.
 *
 * Every id in this family is a canonical `8-4-4-4-12` UUID: that is what `UUID.randomUUID()` writes,
 * and arc 16's `SafeImportId` refuses anything else even out of a stranger's file. Written as text
 * that costs 36 characters to carry 128 bits. Written as base64url it costs **22**, and the tag
 * index pays for two of them fifty thousand times.
 *
 * That arithmetic is the whole reason this exists. W4 made every assignment name its notebook and a
 * page assignment name its page as well; at 36 characters each the worst legal index no longer fits
 * [ExtensionContract.STORE_MAX_VALUE_BYTES], and the choice was to compact the ids or to lower a cap
 * the wizard had set. W1's precedent decided it: keep the caps, shrink the record.
 *
 * **In memory an id is always a UUID.** [TagIndex] holds UUIDs, the seam carries UUIDs, call sites
 * pass UUIDs; only [TagCodec]'s bytes are compact, and it converts at the door in both directions.
 * Nothing outside the codec should ever hold a compact id — it is a spelling of an id, not an id.
 *
 * [expand] answers a **lower-case** UUID. Hex case carries no meaning (`SafeImportId` accepts either
 * and `UUID.toString()` writes lower), but a caller matching expanded ids against ids it read
 * elsewhere should fold case rather than assume, which is what the host's search merge does.
 */
object CompactId {

    /** Characters a compact id occupies: 16 bytes of base64url, unpadded. */
    const val CHARS: Int = 22

    private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val DECODER: Base64.Decoder = Base64.getUrlDecoder()

    /**
     * [uuid] as [CHARS] base64url characters, or **null** when it is not a canonical UUID.
     *
     * Null is a real answer and callers drop the record rather than inventing one: an id that is not
     * a UUID cannot have come from this family, and storing it uncompacted would silently break the
     * arithmetic [TagCodec.WORST_CASE_BYTES] promises.
     */
    fun compact(uuid: String): String? {
        val parsed = try {
            UUID.fromString(uuid)
        } catch (e: IllegalArgumentException) {
            return null
        }
        // `UUID.fromString` is famously lenient — it accepts "1-2-3-4-5" and pads it out. Round-trip
        // through toString() so only the canonical form is ever accepted, which is the same rule
        // SafeImportId enforces on the way in from a file.
        if (!parsed.toString().equals(uuid, ignoreCase = true)) return null
        val bytes = ByteArray(16)
        var hi = parsed.mostSignificantBits
        var lo = parsed.leastSignificantBits
        for (i in 7 downTo 0) {
            bytes[i] = (hi and 0xFF).toByte()
            hi = hi ushr 8
        }
        for (i in 15 downTo 8) {
            bytes[i] = (lo and 0xFF).toByte()
            lo = lo ushr 8
        }
        return ENCODER.encodeToString(bytes)
    }

    /** The lower-case canonical UUID [compact] stands for, or **null** when it is not one. */
    fun expand(compact: String): String? {
        if (compact.length != CHARS) return null
        val bytes = try {
            DECODER.decode(compact)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size != 16) return null
        var hi = 0L
        var lo = 0L
        for (i in 0..7) hi = (hi shl 8) or (bytes[i].toLong() and 0xFF)
        for (i in 8..15) lo = (lo shl 8) or (bytes[i].toLong() and 0xFF)
        return UUID(hi, lo).toString()
    }

    /** True when [id] is a canonical UUID — the one shape a tag target may take. */
    fun isId(id: String): Boolean = compact(id) != null
}
