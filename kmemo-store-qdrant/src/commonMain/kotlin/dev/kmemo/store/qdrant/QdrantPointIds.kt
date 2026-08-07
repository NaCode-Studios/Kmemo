package dev.kmemo.store.qdrant

/**
 * A cache entry id as a Qdrant point id.
 *
 * Qdrant takes an unsigned integer or a UUID string and nothing else. `CacheStore` takes any string: a
 * kmemo id is 128 random bits rendered as 32 hex characters, which is exactly what a UUID holds, and an
 * id from anywhere else is whatever its author chose. The first case is a reformat, total and with no
 * chance of two ids meeting. The second is mixed down to 128 bits.
 *
 * A hash is not reversible, so `QdrantStore` also writes the id into the point's payload and always
 * reads it back from there. That is what keeps this honest: the point id is an address, the entry's own
 * id is data, and neither is inferred from the other.
 */
internal object QdrantPointIds {

    private const val HEX_LENGTH = 32
    private const val HEX = "0123456789abcdef"

    private const val FNV_OFFSET_A = -0x340d631b7bdddcdbL
    private const val FNV_OFFSET_B = 0x27d4eb2f165667c5L
    private const val FNV_PRIME = 0x100000001b3L

    /** [id] as a UUID string Qdrant will accept. */
    fun of(id: String): String {
        if (id.length == HEX_LENGTH && id.all { it in HEX }) return hyphenate(id)
        return hyphenate(hash128(id))
    }

    private fun hyphenate(hex: String): String =
        hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" +
            hex.substring(16, 20) + "-" + hex.substring(20, 32)

    /**
     * 128 bits of an arbitrary id, as 32 hex characters.
     *
     * Two independent 64-bit FNV-1a passes, forward and backward with different offset bases, each run
     * through a SplitMix64 finalizer. The finalizer is the part that matters: FNV alone avalanches
     * poorly on short inputs, and two poorly avalanched halves are not 128 bits of anything. What is
     * needed here is that two distinct ids do not meet, not that the output is unpredictable, so this
     * is a mixing function and deliberately not a cryptographic hash.
     */
    private fun hash128(id: String): String {
        val bytes = id.encodeToByteArray()
        var forward = FNV_OFFSET_A
        var backward = FNV_OFFSET_B
        for (index in bytes.indices) {
            forward = (forward xor (bytes[index].toLong() and 0xFF)) * FNV_PRIME
            backward = (backward xor (bytes[bytes.size - 1 - index].toLong() and 0xFF)) * FNV_PRIME
        }
        return hex(mix(forward)) + hex(mix(backward))
    }

    private fun mix(value: Long): Long {
        var mixed = value
        mixed = (mixed xor (mixed ushr 30)) * -0x40a7b892e31b1a47L
        mixed = (mixed xor (mixed ushr 27)) * -0x6b2fb644ecceee15L
        return mixed xor (mixed ushr 31)
    }

    private fun hex(value: Long): String {
        val out = StringBuilder(16)
        for (shift in 60 downTo 0 step 4) out.append(HEX[((value ushr shift) and 0xF).toInt()])
        return out.toString()
    }
}
