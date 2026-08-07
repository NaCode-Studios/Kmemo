package dev.kmemo.store.qdrant

import dev.kdrant.QdrantClient
import dev.kdrant.model.Distance
import dev.kdrant.model.PointId
import dev.kdrant.model.VectorData
import dev.kdrant.model.WithPayload
import dev.kmemo.CacheEntry
import dev.kmemo.CacheStore
import dev.kmemo.ScoredEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [CacheStore] on Qdrant, so a team already running one does not have to operate a second database
 * for its cache.
 *
 * ```kotlin
 * val qdrant = Kdrant(host = "localhost", port = 6333)
 * val cache = semanticCache(embedder) {
 *     store = QdrantStore(qdrant, dimensions = 1_536)
 * }
 * ```
 *
 * The friction this removes is concrete. A team doing retrieval-augmented generation already operates
 * Qdrant, because that is where their documents are. Adding a cache used to mean adding Redis or
 * Postgres as well, and operating a second store for something that holds embeddings, which is the
 * thing the store they already have exists to hold. `InMemoryStore` avoids that and does not survive a
 * restart or reach a second instance, which rules it out for the deployment where a cache pays for
 * itself.
 *
 * **This is adoption friction, not validation.** A fourth store written in this repository is a fourth
 * store written by the authors of the conformance suite, and it proves what the other three prove. The
 * argument for it is that people already run Qdrant.
 *
 * ### The client is yours
 *
 * The store takes a [QdrantClient] rather than connection settings, so the wire, the credential, the
 * TLS trust anchors and the lifecycle stay where the caller can see them. Kdrant's REST engine is pure
 * Kotlin and its gRPC engine is one dependency away; both are the same interface and this store cannot
 * tell them apart. It never closes the client, because it did not open it.
 *
 * ### What it does with the collection
 *
 * The collection is created on first use if it is absent, single unnamed vector, [Distance.COSINE], at
 * [dimensions]. Cosine is not a choice: [CacheEntry] normalizes every embedding on construction and
 * [dev.kmemo.SemanticCache] reads the score as a cosine similarity, so a collection configured for a
 * different metric would return numbers the cache would compare against a cosine threshold.
 *
 * Two payload indexes are created with it, on `scope` and on `tags`. Filtering an unindexed payload
 * field in Qdrant is a full scan, and every lookup this store makes filters on `scope`.
 *
 * ### Expiry
 *
 * Qdrant has no TTL, so expiry is a payload field and a filter, exactly as it is on Postgres. Every
 * point carries `expiresAt` in epoch milliseconds, and a point written with no [ttl] carries a date far
 * enough out that it will not arrive. Nothing sweeps: an expired point is invisible to [search] and
 * uncounted by [size] the moment it expires, and it stops occupying space when [invalidateByTag],
 * [clear] or a rewrite removes it. That is the same trade the Postgres store makes and it is the right
 * one for a cache, where an entry nobody can see costs storage and nothing else.
 *
 * @param client the connected client. Not closed by this store.
 * @param dimensions the embedding width. Must match the [dev.kmemo.Embedder] the cache runs.
 * @param collection which Qdrant collection to use. Created if absent.
 * @param ttl how long an entry may be served, or `null` to keep it until it is removed.
 * @param clock the time source expiry is judged against.
 */
public class QdrantStore(
    private val client: QdrantClient,
    private val dimensions: Int,
    private val collection: String = DEFAULT_COLLECTION,
    private val ttl: Duration? = null,
    private val clock: Clock = Clock.System,
) : CacheStore {

    init {
        require(dimensions > 0) { "dimensions must be positive, was $dimensions" }
        require(collection.isNotBlank()) { "collection must not be blank" }
        require(ttl == null || ttl.isPositive()) { "ttl must be positive, was $ttl" }
    }

    private val gate = Mutex()
    private var prepared = false

    override suspend fun put(entry: CacheEntry) {
        prepare()
        client.upsert(collection, wait = true) {
            point(PointId.uuid(QdrantPointIds.of(entry.id))) {
                vector(*entry.embedding)
                payload {
                    put(ID, entry.id)
                    put(SCOPE, entry.scope)
                    put(PROMPT, entry.prompt)
                    put(RESPONSE, entry.response)
                    put(EMBEDDER, entry.embedder)
                    put(CREATED_SECONDS, entry.createdAt.epochSeconds)
                    put(CREATED_NANOS, entry.createdAt.nanosecondsOfSecond)
                    put(EXPIRES_AT, expiryOf(entry))
                    put(TAGS, JsonArray(entry.tags.map { JsonPrimitive(it) }))
                    put(CHUNKS, JsonArray(entry.chunkLengths.map { JsonPrimitive(it) }))
                    put(METADATA, JsonObject(entry.metadata.mapValues { JsonPrimitive(it.value) }))
                }
            }
        }
    }

    override suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry> {
        prepare()
        val now = clock.now().toEpochMilliseconds()
        val hits = client.search(collection) {
            query(*embedding)
            this.limit = limit
            withPayload = WithPayload.All
            // The stored vector comes back with the entry. It costs a vector per candidate over the
            // wire and it is not optional: `MmrReranker` scores candidates against each other, so a
            // store that returned a placeholder would leave reranking quietly comparing nothing.
            withVector = true
            filter {
                must {
                    SCOPE eq scope
                    EXPIRES_AT gt now
                }
            }
        }
        return hits.mapNotNull { hit ->
            val payload = hit.payload ?: return@mapNotNull null
            ScoredEntry(entryOf(payload, vectorOf(hit.vector, embedding.size)), hit.score.toDouble())
        }
    }

    override suspend fun remove(id: String): Boolean {
        prepare()
        // Qdrant's delete is idempotent and reports nothing about what it removed, and the contract
        // requires this to say whether an entry was actually there. One retrieve is the price of an
        // honest answer, and it is on the removal path rather than on any lookup.
        val point = listOf(PointId.uuid(QdrantPointIds.of(id)))
        val present = client.retrieve(collection, point, WithPayload.All).isNotEmpty()
        if (present) client.delete(collection, point, wait = true)
        return present
    }

    override suspend fun invalidateByTag(tag: String, scope: String?): Int {
        prepare()
        val now = clock.now().toEpochMilliseconds()
        val matching = client.count(collection, exact = true) {
            must {
                TAGS eq tag
                EXPIRES_AT gt now
                if (scope != null) SCOPE eq scope
            }
        }
        if (matching == 0L) return 0
        client.delete(collection, wait = true) {
            must {
                TAGS eq tag
                if (scope != null) SCOPE eq scope
            }
        }
        return matching.toInt()
    }

    override suspend fun clear(scope: String?) {
        prepare()
        if (scope == null) {
            // Dropping the collection is the only filter that reliably matches everything, and it also
            // reclaims the space of every expired point, which a scoped clear cannot.
            client.deleteCollection(collection)
            gate.withLock { prepared = false }
            prepare()
        } else {
            client.delete(collection, wait = true) { must { SCOPE eq scope } }
        }
    }

    override suspend fun size(scope: String?): Int {
        prepare()
        val now = clock.now().toEpochMilliseconds()
        return client.count(collection, exact = true) {
            must {
                EXPIRES_AT gt now
                if (scope != null) SCOPE eq scope
            }
        }.toInt()
    }

    /**
     * Creates the collection and its payload indexes once, on the first operation.
     *
     * Lazily rather than in the constructor, because talking to a server is IO and a constructor cannot
     * suspend. `ensureCollection` is race tolerant, so two processes starting together do not fight.
     */
    private suspend fun prepare() {
        if (prepared) return
        gate.withLock {
            if (prepared) return
            client.ensureCollection(collection) {
                vector {
                    size = dimensions.toLong()
                    distance = Distance.COSINE
                }
            }
            // Filtering an unindexed payload field is a full scan, and every lookup filters on scope.
            client.createPayloadIndex(collection, SCOPE) { keyword { } }
            client.createPayloadIndex(collection, TAGS) { keyword { } }
            client.createPayloadIndex(collection, EXPIRES_AT) { integer { } }
            prepared = true
        }
    }

    private fun expiryOf(entry: CacheEntry): Long {
        val ttl = ttl ?: return NEVER
        return (entry.createdAt + ttl).toEpochMilliseconds()
    }

    /** The stored vector, or a unit vector when a collection somebody else made returns none. */
    private fun vectorOf(vector: VectorData?, dimensions: Int): FloatArray = when (vector) {
        is VectorData.DenseArray -> vector.values
        is VectorData.Dense -> vector.values.toFloatArray()
        else -> FloatArray(dimensions).also { it[0] = 1.0f }
    }

    private fun entryOf(payload: JsonObject, embedding: FloatArray): CacheEntry = CacheEntry(
        id = payload.text(ID),
        scope = payload.text(SCOPE),
        prompt = payload.text(PROMPT),
        response = payload.text(RESPONSE),
        embedding = embedding,
        createdAt = Instant.fromEpochSeconds(
            payload[CREATED_SECONDS]?.jsonPrimitive?.content?.toLong() ?: 0L,
            payload[CREATED_NANOS]?.jsonPrimitive?.content?.toLong() ?: 0L,
        ),
        metadata = payload[METADATA]?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.content }
            .orEmpty(),
        tags = payload[TAGS]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
        embedder = payload.text(EMBEDDER),
        chunkLengths = payload[CHUNKS]?.jsonArray?.map { it.jsonPrimitive.content.toInt() }.orEmpty(),
    )

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    public companion object {
        /** The collection used when a caller does not name one. */
        public const val DEFAULT_COLLECTION: String = "kmemo_cache"

        private const val ID = "entryId"
        private const val SCOPE = "scope"
        private const val PROMPT = "prompt"
        private const val RESPONSE = "response"
        private const val EMBEDDER = "embedder"
        private const val CREATED_SECONDS = "createdAtSeconds"
        private const val CREATED_NANOS = "createdAtNanos"
        private const val EXPIRES_AT = "expiresAt"
        private const val TAGS = "tags"
        private const val CHUNKS = "chunkLengths"
        private const val METADATA = "metadata"


        /**
         * The expiry an entry with no TTL carries: the last day of the year 9999, in epoch
         * milliseconds.
         *
         * A sentinel rather than an absent field, so the filter is one `expiresAt > now` clause on
         * every path instead of a null case beside it. Payload numbers are JSON, so this has to stay
         * inside a double's exact integer range, which it does by an order of magnitude.
         */
        private const val NEVER: Long = 253_402_300_799_000
    }
}
