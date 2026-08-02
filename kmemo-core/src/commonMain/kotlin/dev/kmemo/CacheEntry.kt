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
    /**
     * [Embedder.identity] of the model that produced [embedding].
     *
     * Without it an entry says what it means but not what measured it, and a cache that swapped models
     * would go on scoring old vectors against new ones in a space they do not share. A [SemanticCache]
     * stamps this on every write and refuses, on lookup, any entry that does not carry its own.
     *
     * A store that persists entries must round-trip it. One rehydrated without it reads as
     * [Embedder.UNDECLARED], which is correct for everything written before identities existed: nobody
     * can say what wrote those, and `undeclared` is exactly that statement.
     */
    public val embedder: String = Embedder.UNDECLARED,
    /**
     * Length, in characters, of each chunk [response] arrived in, when it arrived as a stream.
     *
     * Empty for every entry written by [SemanticCache.put], [SemanticCache.getOrPut] or
     * [SemanticCache.warm]. Nobody streamed those, and an empty list says so rather than inventing a
     * single boundary that happens to be the whole text.
     * [SemanticCache.getOrPutStreaming] fills it, which is what lets a later hit replay the same
     * chunks the first caller saw instead of one lump the size of the answer.
     *
     * Lengths rather than the chunks themselves: [response] already holds every character, and a
     * second copy of the text would double what a streamed entry costs to store for information that
     * is three bytes per chunk.
     */
    public val chunkLengths: List<Int> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        // Loud rather than lenient, and aimed squarely at a store that persists the lengths and the
        // response through different columns: a list that no longer describes this text would replay a
        // sliced-up answer to somebody who asked a real question, and the slicing would look like the
        // model's own. Refusing to construct the entry is the only reading anyone can act on.
        require(chunkLengths.isEmpty() || chunkLengths.sum() == response.length) {
            "chunkLengths sum to ${chunkLengths.sum()} but the response is ${response.length} " +
                "characters; they no longer describe it"
        }
        require(chunkLengths.all { it > 0 }) { "chunkLengths must all be positive, was $chunkLengths" }
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
     *
     * [chunkLengths] is **dropped**, not carried: it described the text being replaced, and a new
     * answer did not arrive in the old answer's chunks. The refreshed entry replays whole, which is
     * the honest report of an answer nobody streamed.
     */
    public fun withResponse(response: String): CacheEntry =
        CacheEntry(id, scope, prompt, response, embedding, createdAt, metadata, tags, embedder)

    override fun equals(other: Any?): Boolean = this === other || (other is CacheEntry && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "CacheEntry(id=$id, scope=$scope, prompt=${prompt.take(48).let { if (prompt.length > 48) "$it…" else it }})"
}
