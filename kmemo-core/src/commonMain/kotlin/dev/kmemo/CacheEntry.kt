package dev.kmemo

import kotlin.time.Instant

/**
 * One cached prompt/response pair plus the vector used to find it again.
 *
 * The [embedding] passed in is normalized on construction, so `CacheEntry.embedding` is always unit
 * length and [Vectors.dot] against another normalized vector yields cosine similarity directly.
 *
 * Identity is the [id] alone: two entries with the same id are the same entry, whatever else
 * changed. That keeps entries usable as map keys without ever comparing float arrays element by
 * element.
 */
public class CacheEntry(
    /** Store-unique identifier, assigned when the entry is written. */
    public val id: String,
    /**
     * Partition this entry belongs to. Lookups only ever see entries from their own scope, which is
     * how you keep a `gpt-4o` answer from being served to a `haiku` caller — see [SemanticCache].
     */
    public val scope: String,
    /** The prompt exactly as it was seen, kept verbatim because the guards re-read it on every hit. */
    public val prompt: String,
    /** The response to replay when this entry matches. */
    public val response: String,
    embedding: FloatArray,
    /** Write time, used for TTL expiry and for reporting the age of a hit. */
    public val createdAt: Instant,
    /** Free-form caller data, returned untouched on a hit (token counts, model id, trace id...). */
    public val metadata: Map<String, String> = emptyMap(),
    /**
     * Labels this entry can be invalidated by, in bulk, when the fact behind it changes.
     *
     * Distinct from [metadata], which is opaque payload the cache never reads. Tags are **indexed** by
     * the store, which is what lets `invalidateByTag` be a query rather than a full scan — the reason
     * this is a field on the entry and not a convention inside the metadata map.
     *
     * Keep them low-cardinality and about the *source of truth*, not about the request: `price-list`,
     * `policy-2026`, `customer-42`. A tag per prompt is a tag that never gets used.
     */
    public val tags: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
    }

    /**
     * Unit-normalized embedding of [prompt].
     *
     * Normalization happens here, once, rather than being asked of every caller — which means a
     * store rehydrating entries from Redis or Postgres cannot accidentally leave raw vectors in
     * play and silently compute similarities that are off by their magnitudes.
     */
    public val embedding: FloatArray = Vectors.normalize(embedding)

    /** Dimensionality of [embedding]. */
    public val dimensions: Int get() = embedding.size

    /**
     * Copy of this entry with a different [response], keeping id, embedding and creation time.
     * Useful when refreshing a stale answer without paying to re-embed the prompt.
     */
    public fun withResponse(response: String): CacheEntry =
        CacheEntry(id, scope, prompt, response, embedding, createdAt, metadata, tags)

    override fun equals(other: Any?): Boolean = this === other || (other is CacheEntry && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "CacheEntry(id=$id, scope=$scope, prompt=${prompt.take(48).let { if (prompt.length > 48) "$it…" else it }})"
}
