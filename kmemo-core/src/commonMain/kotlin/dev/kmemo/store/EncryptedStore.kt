package dev.kmemo.store

import dev.kmemo.CacheEntry
import dev.kmemo.CacheStore
import dev.kmemo.EntryCipher
import dev.kmemo.ScoredEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [CacheStore] that persists no readable prompt or response, wrapped around one that would.
 *
 * ```kotlin
 * val cache = semanticCache(embedder) {
 *     store = EncryptedStore(PostgresStore(dataSource), myCipher)
 * }
 * ```
 *
 * A decorator rather than a flag on each store, and rather than a step inside [dev.kmemo.SemanticCache].
 * Encryption is about what is *persisted*, and persistence is what a store owns: written here it covers
 * Postgres, Redis, HNSW, a file-backed store and anything a third party writes, in one place, held to
 * the same `CacheStoreContract` as everything else. Written inside the cache it would have to be
 * repeated for every path that touches an entry and would still not cover a store somebody else wrote.
 *
 * ### What the read path costs
 *
 * One decryption per candidate the delegate returns, not one per lookup. The guards read every
 * candidate's prompt as text, so every candidate has to be readable before the chain can refuse it:
 * a lookup with the default five candidates does five prompt decryptions and one response decryption
 * where an unencrypted store does none. That is the price and it is real. It buys the property that
 * nothing readable is written down, and there is no arrangement that avoids it, because the alternative
 * is a cipher that lets the guards work without decrypting, which means deterministic encryption, which
 * leaks equality across prompts.
 *
 * The number is a count rather than a duration on purpose: the duration is a property of the cipher you
 * supply, and multiplying it by the candidate count is arithmetic this library cannot do for you.
 *
 * ### What still works, and what changes
 *
 * The guard chain produces exactly the verdicts it produced before, because the cache still sees
 * plaintext: it is the store that holds ciphertext, and everything above it is unchanged.
 * `CacheLookup.Hit` still reports the matched prompt. Scopes, tags, TTL, eviction and
 * `invalidateByTag` all behave as the delegate makes them behave.
 *
 * What changes is that the delegate's own text search, if it has one, no longer sees words. No store in
 * this repository has one, since they all find entries by vector, but a backend somebody adds that
 * indexes the prompt column for text search will index ciphertext.
 *
 * The embedding is stored as it was. See [EntryCipher] for why, and for what it means.
 */
public class EncryptedStore(
    private val delegate: CacheStore,
    private val cipher: EntryCipher,
) : CacheStore {

    private val probeGuard = Mutex()
    private var probed = false

    override suspend fun put(entry: CacheEntry) {
        refuseDeterministicCiphers(entry.scope)
        delegate.put(
            CacheEntry(
                id = entry.id,
                scope = entry.scope,
                prompt = cipher.encrypt(entry.prompt, entry.scope),
                // The chunk boundaries travel inside the ciphertext rather than beside it. Left outside
                // they would be a plaintext description of the shape of the answer, and CacheEntry
                // rightly refuses to hold lengths that no longer sum to the text it is holding, which
                // ciphertext never will.
                response = cipher.encrypt(envelope(entry.chunkLengths, entry.response), entry.scope),
                embedding = entry.embedding,
                createdAt = entry.createdAt,
                metadata = entry.metadata,
                tags = entry.tags,
                embedder = entry.embedder,
            ),
        )
    }

    override suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry> =
        delegate.search(scope, embedding, limit).map { ScoredEntry(decrypted(it.entry), it.similarity) }

    override suspend fun touch(id: String) {
        delegate.touch(id)
    }

    override suspend fun remove(id: String): Boolean = delegate.remove(id)

    override suspend fun invalidateByTag(tag: String, scope: String?): Int =
        delegate.invalidateByTag(tag, scope)

    override suspend fun clear(scope: String?) {
        delegate.clear(scope)
    }

    override suspend fun size(scope: String?): Int = delegate.size(scope)

    private suspend fun decrypted(entry: CacheEntry): CacheEntry {
        val (lengths, response) = unwrap(cipher.decrypt(entry.response, entry.scope))
        return CacheEntry(
            id = entry.id,
            scope = entry.scope,
            prompt = cipher.decrypt(entry.prompt, entry.scope),
            response = response,
            embedding = entry.embedding,
            createdAt = entry.createdAt,
            metadata = entry.metadata,
            tags = entry.tags,
            embedder = entry.embedder,
            chunkLengths = lengths,
        )
    }

    /**
     * Encrypts a probe twice, once, and refuses to run if the two agree.
     *
     * The rule that encryption must be randomized is the one clause in [EntryCipher] that cannot be
     * traded away, and a rule enforced only by documentation is a rule that ships broken. A
     * deterministic cipher writes the same ciphertext for the same question, so two rows that match are
     * two people who asked the same thing, which on a small population identifies both. Checking costs
     * two encryptions in the lifetime of the store.
     */
    private suspend fun refuseDeterministicCiphers(scope: String) {
        if (probed) return
        probeGuard.withLock {
            if (probed) return
            val first = cipher.encrypt(PROBE, scope)
            val second = cipher.encrypt(PROBE, scope)
            require(first != second) {
                "${cipher::class.simpleName} encrypts the same plaintext to the same ciphertext. " +
                    "Deterministic encryption leaks equality across prompts, which is what an attacker " +
                    "holding this database wants: two identical rows are two users who asked the same " +
                    "question. Use a randomized mode with a fresh nonce per call."
            }
            probed = true
        }
    }

    private fun envelope(chunkLengths: List<Int>, response: String): String =
        chunkLengths.joinToString(",") + "\n" + response

    private fun unwrap(envelope: String): Pair<List<Int>, String> {
        val split = envelope.indexOf('\n')
        // A cipher that round-trips cannot produce this, so reaching it means the delegate is holding
        // something this store did not write. Saying so beats replaying a response with a header on it.
        require(split >= 0) {
            "a stored response has no chunk-length header; this store is reading entries it did not write"
        }
        val header = envelope.substring(0, split)
        val lengths = if (header.isEmpty()) emptyList() else header.split(",").map { it.toInt() }
        return lengths to envelope.substring(split + 1)
    }

    private companion object {
        private const val PROBE = "kmemo cipher probe"
    }
}
