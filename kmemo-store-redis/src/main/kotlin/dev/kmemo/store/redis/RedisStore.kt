package dev.kmemo.store.redis

import dev.kmemo.CacheEntry
import dev.kmemo.CacheStore
import dev.kmemo.ScoredEntry
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.output.NestedMultiOutput
import io.lettuce.core.output.StatusOutput
import io.lettuce.core.protocol.CommandArgs
import io.lettuce.core.protocol.ProtocolKeyword
import io.lettuce.core.protocol.ProtocolVersion
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration

/**
 * A [CacheStore] backed by Redis with the **RediSearch** module, for a cache shared across processes.
 *
 * kmemo splits responsibilities so this adapter stays thin: the [dev.kmemo.SemanticCache] owns whether
 * a candidate is good enough to serve (threshold, guards, verification); this store only keeps vectors
 * and returns the nearest `k` in a scope. It therefore reimplements **no** match logic — the
 * nearest-neighbour search is Redis's `FT.SEARCH ... KNN`, on an exact `FLAT` index so results match
 * the conformance suite's ordering.
 *
 * **Layout.** Each entry is a Redis hash at `"<keyPrefix><id>"`. Scope is a `TAG` field, the embedding a
 * `FLOAT32` `VECTOR` field, and expiry a numeric `expiresAt` (epoch millis) field. A single RediSearch
 * index (created lazily on the first [put], once the embedding dimension is known) covers all of them.
 *
 * **Expiry.** TTL is enforced two ways that agree in production and stay testable: every read filters on
 * `expiresAt > now(clock)`, so expiry follows the injected [clock] deterministically; and a real Redis
 * key TTL is set for reclamation, so dead keys do not accumulate. An entry with no TTL stores
 * `expiresAt = Long.MAX_VALUE`.
 *
 * **Requirements.** Redis with the RediSearch module (e.g. `redis/redis-stack`). If the module is
 * absent, the first [put] fails fast with a clear message rather than silently degrading to no search.
 *
 * @param connection an open Lettuce connection with a **byte-array** codec, speaking **RESP2** (this
 *   store parses `FT.SEARCH` replies as classic RESP2 arrays; RESP3 returns a map it does not decode).
 *   The [RedisClient] secondary constructor handles both for you — prefer it.
 * @param indexName RediSearch index name; give each logical cache its own.
 * @param keyPrefix prefix for this cache's hash keys; the index only sees keys under it.
 * @param ttl how long an entry stays valid, or `null` to keep it until removed.
 * @param clock time source; substitute a fixed clock in tests instead of sleeping.
 * @param closeConnection whether [close] should also close [connection].
 */
public class RedisStore(
    private val connection: StatefulRedisConnection<ByteArray, ByteArray>,
    private val indexName: String = "kmemo-idx",
    private val keyPrefix: String = "kmemo:",
    private val ttl: Duration? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val closeConnection: Boolean = false,
) : CacheStore, AutoCloseable {

    /**
     * Connects to [client] with a byte-array codec, **pinning RESP2** (RediSearch replies are parsed as
     * RESP2 arrays), and owns the connection (closed by [close]).
     */
    public constructor(
        client: RedisClient,
        indexName: String = "kmemo-idx",
        keyPrefix: String = "kmemo:",
        ttl: Duration? = null,
        clock: Clock = Clock.systemUTC(),
    ) : this(connectResp2(client), indexName, keyPrefix, ttl, clock, closeConnection = true)

    init {
        require(ttl == null || ttl.isPositive()) { "ttl must be positive, was $ttl" }
        require(keyPrefix.isNotEmpty()) { "keyPrefix must not be empty" }
    }

    private val commands = connection.async()
    private val json = Json

    private val indexMutex = Mutex()
    @Volatile private var indexDimensions = -1

    override suspend fun put(entry: CacheEntry) {
        ensureIndex(entry.dimensions)

        val nowMillis = clock.millis()
        val expiresAtMillis = ttl?.let { nowMillis + it.inWholeMilliseconds } ?: Long.MAX_VALUE

        val fields = linkedMapOf(
            field(SCOPE) to entry.scope.toByteArray(UTF_8),
            field(PROMPT) to entry.prompt.toByteArray(UTF_8),
            field(RESPONSE) to entry.response.toByteArray(UTF_8),
            field(CREATED_AT) to entry.createdAt.toEpochMilli().toString().toByteArray(US_ASCII),
            field(EXPIRES_AT) to expiresAtMillis.toString().toByteArray(US_ASCII),
            field(METADATA) to encodeMetadata(entry.metadata),
            field(EMBEDDING) to encodeVector(entry.embedding),
        )

        val key = keyFor(entry.id)
        commands.hset(key, fields).await()
        // A real key TTL, relative to server time, only reclaims memory — correctness comes from the
        // expiresAt filter driven by [clock]. Set generously so the fake-clock tests are unaffected.
        if (ttl != null) commands.pexpire(key, ttl.inWholeMilliseconds).await()
    }

    override suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry> {
        require(limit > 0) { "limit must be positive, was $limit" }
        if (indexDimensions == -1) return emptyList()
        check(embedding.size == indexDimensions) {
            "query embedding has ${embedding.size} dimensions but the store holds " +
                "$indexDimensions-dimensional vectors; the embedding model likely changed."
        }

        val query = "(@$SCOPE:{${escapeTag(scope)}} @$EXPIRES_AT:[(${clock.millis()} +inf])" +
            "=>[KNN $limit @$EMBEDDING \$BLOB AS $SCORE]"
        val args = CommandArgs(CODEC)
            .add(indexName)
            .add(query)
            .add("PARAMS").add(2L).add("BLOB").add(encodeVector(embedding))
            .add("SORTBY").add(SCORE)
            .add("RETURN").add(7L)
            .add(SCOPE).add(PROMPT).add(RESPONSE).add(CREATED_AT).add(EMBEDDING).add(METADATA).add(SCORE)
            .add("LIMIT").add(0L).add(limit.toLong())
            .add("DIALECT").add(2L)

        val reply = commands.dispatch(Ft.SEARCH, NestedMultiOutput(CODEC), args).await()
        return parseSearch(reply)
    }

    override suspend fun remove(id: String): Boolean = commands.del(keyFor(id)).await() > 0L

    override suspend fun clear(scope: String?) {
        if (indexDimensions == -1) return
        val keys = matchingKeys(scope)
        if (keys.isNotEmpty()) commands.del(*keys.toTypedArray()).await()
    }

    override suspend fun size(scope: String?): Int {
        if (indexDimensions == -1) return 0
        val filter = if (scope == null) {
            "@$EXPIRES_AT:[(${clock.millis()} +inf]"
        } else {
            "(@$SCOPE:{${escapeTag(scope)}} @$EXPIRES_AT:[(${clock.millis()} +inf])"
        }
        val args = CommandArgs(CODEC)
            .add(indexName).add(filter)
            .add("LIMIT").add(0L).add(0L)
            .add("DIALECT").add(2L)
        val reply = commands.dispatch(Ft.SEARCH, NestedMultiOutput(CODEC), args).await()
        return ((reply.firstOrNull() as? Long) ?: 0L).toInt()
    }

    override fun close() {
        if (closeConnection) connection.close()
    }

    // ---- index -----------------------------------------------------------------------------------

    private suspend fun ensureIndex(dimensions: Int) {
        if (indexDimensions == dimensions) return
        indexMutex.withLock {
            if (indexDimensions == dimensions) return
            require(indexDimensions == -1) {
                "embedding dimension mismatch: this store's index holds $indexDimensions-dimensional " +
                    "vectors, but an entry has $dimensions. One embedding model per store."
            }
            createIndex(dimensions)
            indexDimensions = dimensions
        }
    }

    private suspend fun createIndex(dimensions: Int) {
        val args = CommandArgs(CODEC)
            .add(indexName)
            .add("ON").add("HASH")
            .add("PREFIX").add(1L).add(keyPrefix)
            .add("SCHEMA")
            .add(SCOPE).add("TAG")
            .add(EXPIRES_AT).add("NUMERIC")
            .add(EMBEDDING).add("VECTOR").add("FLAT").add(6L)
            .add("TYPE").add("FLOAT32")
            .add("DIM").add(dimensions.toLong())
            .add("DISTANCE_METRIC").add("COSINE")
        try {
            commands.dispatch(Ft.CREATE, StatusOutput(CODEC), args).await()
        } catch (e: RedisCommandExecutionException) {
            val message = e.message.orEmpty()
            if (message.contains("Index already exists", ignoreCase = true)) return
            if (message.contains("unknown command", ignoreCase = true) || message.contains("FT.CREATE")) {
                throw IllegalStateException(
                    "Redis does not have the RediSearch module (FT.CREATE failed). kmemo-store-redis " +
                        "needs RediSearch — run redis/redis-stack, or a Redis with the search module loaded.",
                    e,
                )
            }
            throw e
        }
    }

    // ---- search reply parsing --------------------------------------------------------------------

    private fun parseSearch(reply: List<Any?>): List<ScoredEntry> {
        if (reply.isEmpty()) return emptyList()
        val results = ArrayList<ScoredEntry>()
        var i = 1 // reply[0] is the total count
        while (i + 1 < reply.size) {
            val key = reply[i] as ByteArray
            val rawFields = reply[i + 1] as List<*>
            i += 2

            val fields = HashMap<String, ByteArray>(rawFields.size)
            var j = 0
            while (j + 1 < rawFields.size) {
                fields[String(rawFields[j] as ByteArray, UTF_8)] = rawFields[j + 1] as ByteArray
                j += 2
            }

            val id = String(key, UTF_8).removePrefix(keyPrefix)
            val entry = CacheEntry(
                id = id,
                scope = fields.string(SCOPE),
                prompt = fields.string(PROMPT),
                response = fields.string(RESPONSE),
                embedding = decodeVector(fields.getValue(EMBEDDING)),
                createdAt = Instant.ofEpochMilli(fields.string(CREATED_AT).toLong()),
                metadata = decodeMetadata(fields[METADATA]),
            )
            // RediSearch COSINE returns a distance in [0, 2]; similarity is 1 - distance.
            val similarity = 1.0 - fields.string(SCORE).toDouble()
            results += ScoredEntry(entry, similarity)
        }
        return results
    }

    private suspend fun matchingKeys(scope: String?): List<ByteArray> {
        val filter = if (scope == null) "*" else "@$SCOPE:{${escapeTag(scope)}}"
        val args = CommandArgs(CODEC)
            .add(indexName).add(filter)
            .add("NOCONTENT")
            .add("LIMIT").add(0L).add(MAX_CLEAR.toLong())
            .add("DIALECT").add(2L)
        val reply = commands.dispatch(Ft.SEARCH, NestedMultiOutput(CODEC), args).await()
        // With NOCONTENT the reply is [total, key1, key2, ...].
        return reply.drop(1).filterIsInstance<ByteArray>()
    }

    // ---- encoding helpers ------------------------------------------------------------------------

    private fun keyFor(id: String): ByteArray = (keyPrefix + id).toByteArray(UTF_8)

    private fun field(name: String): ByteArray = name.toByteArray(UTF_8)

    private fun encodeVector(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    private fun decodeVector(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }

    private fun encodeMetadata(metadata: Map<String, String>): ByteArray =
        json.encodeToString(METADATA_SERIALIZER, metadata).toByteArray(UTF_8)

    private fun decodeMetadata(bytes: ByteArray?): Map<String, String> {
        if (bytes == null || bytes.isEmpty()) return emptyMap()
        return json.decodeFromString(METADATA_SERIALIZER, String(bytes, UTF_8))
    }

    private fun Map<String, ByteArray>.string(name: String): String = String(getValue(name), UTF_8)

    /** Escapes RediSearch tag special characters so an arbitrary scope string is a single tag value. */
    private fun escapeTag(value: String): String = buildString {
        for (ch in value) {
            if (ch in TAG_SPECIALS) append('\\')
            append(ch)
        }
    }

    private enum class Ft : ProtocolKeyword {
        CREATE,
        SEARCH,
        ;

        private val encoded = ("FT." + name).toByteArray(US_ASCII)
        override fun getBytes(): ByteArray = encoded
    }

    private companion object {
        private val CODEC = ByteArrayCodec.INSTANCE

        private const val SCOPE = "scope"
        private const val PROMPT = "prompt"
        private const val RESPONSE = "response"
        private const val CREATED_AT = "createdAt"
        private const val EXPIRES_AT = "expiresAt"
        private const val METADATA = "metadata"
        private const val EMBEDDING = "embedding"
        private const val SCORE = "__score"

        private const val MAX_CLEAR = 10_000

        private val METADATA_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        private const val TAG_SPECIALS = ",.<>{}[]\"':;!@#\$%^&*()-+=~/ \t\n"
    }
}

/**
 * Opens a byte-array connection pinned to RESP2. Lettuce 6 negotiates RESP3 by default, and RediSearch
 * returns `FT.SEARCH` as a RESP3 *map* rather than the classic RESP2 array this store parses — so the
 * protocol is fixed before connecting.
 */
private fun connectResp2(client: RedisClient): StatefulRedisConnection<ByteArray, ByteArray> {
    client.setOptions(client.options.mutate().protocolVersion(ProtocolVersion.RESP2).build())
    return client.connect(ByteArrayCodec.INSTANCE)
}
