package dev.kmemo.internal

import kotlin.random.Random

/**
 * Entry ids, without `java.util.UUID`.
 *
 * A cache entry's id has one job: two entries written by the same process, or by two processes sharing
 * a store, must not collide. It is never parsed, never ordered and never shown to anyone, so the
 * requirement is uniqueness and nothing else — which is why this is 128 random bits rendered as hex
 * rather than a formatted UUID with a version nibble in it.
 *
 * `Random.Default` is the platform's best available generator on every target. It is not a
 * cryptographic source, and it does not need to be: an id nobody can guess is not a property this
 * cache relies on, and the store is already scoped.
 */
internal object Ids {

    private const val BYTES = 16

    fun next(): String {
        val bytes = Random.nextBytes(BYTES)
        val out = StringBuilder(BYTES * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
