package dev.kmemo.store

import dev.kmemo.CacheStore
import dev.kmemo.EntryCipher
import dev.kmemo.tck.CacheStoreContract
import kotlin.time.Duration

/**
 * [EncryptedStore] held to the same contract as every other store.
 *
 * A decorator is exactly where a conformance suite earns its keep. It rewrites two fields of every
 * entry on the way in and back on the way out, and a corner it gets wrong is a corner that serves a
 * wrong answer silently, which is the failure mode the suite was written for. The delegate is
 * [InMemoryStore], which already passes on its own, so anything that fails here is the decorator.
 */
class EncryptedStoreConformanceTest : CacheStoreContract() {

    override fun createStore(ttl: Duration?): CacheStore =
        EncryptedStore(InMemoryStore(ttl = ttl, clock = clock), NonceCipher())

    /** Randomized and reversible, which is the whole of what the decorator requires. Not cryptography. */
    private class NonceCipher : EntryCipher {
        private var nonce = 0

        override suspend fun encrypt(plaintext: String, scope: String): String {
            val shift = ++nonce
            return "$shift:" + plaintext.map { (it.code + shift).toChar() }.joinToString("")
        }

        override suspend fun decrypt(ciphertext: String, scope: String): String {
            val split = ciphertext.indexOf(':')
            val shift = ciphertext.substring(0, split).toInt()
            return ciphertext.substring(split + 1).map { (it.code - shift).toChar() }.joinToString("")
        }
    }
}
