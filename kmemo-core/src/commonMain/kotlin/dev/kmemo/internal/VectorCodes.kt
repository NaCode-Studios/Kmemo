package dev.kmemo.internal

import dev.kmemo.Quantization
import kotlin.math.roundToInt

/**
 * Compressed forms of a unit-normalized vector, used to rank candidates before they are rescored
 * exactly. Never used to decide anything — see [Quantization].
 *
 * The scores here are deliberately not calibrated to cosine similarity. They only ever order
 * candidates against each other within one scan, and every survivor is rescored before any threshold
 * sees it, so a monotone stand-in is enough and skipping the normalization saves a division per entry.
 */
internal object VectorCodes {

    /** The code for [vector] under [quantization], or `null` when there is nothing to encode. */
    fun encode(vector: FloatArray, quantization: Quantization): VectorCode? = when (quantization) {
        Quantization.NONE -> null
        Quantization.INT8 -> Int8Code(encodeInt8(vector))
        Quantization.BINARY -> BinaryCode(encodeBinary(vector))
    }

    /**
     * Components scaled onto a signed byte.
     *
     * `127` rather than `128`: the vectors are unit-normalized so a component can be exactly `-1.0` or
     * `1.0`, and scaling by 128 would take `1.0` to `128`, which does not fit a signed byte and wraps
     * to `-128` — the largest possible component turning into the smallest possible code.
     */
    private fun encodeInt8(vector: FloatArray): ByteArray {
        val code = ByteArray(vector.size)
        for (i in vector.indices) {
            val scaled = (vector[i] * 127f)
            code[i] = when {
                scaled >= 127f -> 127
                scaled <= -127f -> -127
                else -> scaled.roundToInt().toByte()
            }
        }
        return code
    }

    /** One bit per dimension, set when the component is positive. */
    private fun encodeBinary(vector: FloatArray): LongArray {
        val words = LongArray((vector.size + 63) / 64)
        for (i in vector.indices) {
            if (vector[i] > 0f) words[i ushr 6] = words[i ushr 6] or (1L shl (i and 63))
        }
        return words
    }
}

/** A compressed vector that can score itself against a query encoded the same way. */
internal sealed interface VectorCode {
    /** Higher is nearer. Comparable only against codes of the same kind and dimension. */
    fun score(query: VectorCode): Double
}

internal class Int8Code(private val values: ByteArray) : VectorCode {
    override fun score(query: VectorCode): Double {
        val other = (query as Int8Code).values
        var sum = 0
        for (i in values.indices) sum += values[i] * other[i]
        return sum.toDouble()
    }
}

internal class BinaryCode(private val words: LongArray) : VectorCode {
    override fun score(query: VectorCode): Double {
        val other = (query as BinaryCode).words
        // Bits that agree, counted directly: matching sign on a dimension is evidence the two vectors
        // point the same way, and there is nothing else left in this representation to weigh.
        var agreeing = 0
        for (i in words.indices) agreeing += (words[i].inv() xor other[i]).countOneBits()
        return agreeing.toDouble()
    }
}
