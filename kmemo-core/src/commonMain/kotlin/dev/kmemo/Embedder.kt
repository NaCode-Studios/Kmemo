package dev.kmemo

/**
 * Turns text into a vector. kmemo ships no embedding implementation on purpose: you bring your own
 * (OpenAI, Cohere, Voyage, a local ONNX model, whatever your stack already pays for), and kmemo
 * stays free of provider SDKs.
 *
 * Implementations must be **deterministic and stable**: the same text must map to the same vector
 * for the lifetime of a cache, and every vector must have the same dimension.
 *
 * Implementations are called from coroutines and must be safe to call concurrently.
 *
 * ```kotlin
 * val embedder = Embedder { text -> openAi.embeddings(model = "text-embedding-3-small", input = text).vector() }
 * ```
 *
 * ### On the native targets and on wasm, an embedder is a network call
 *
 * That sentence is the consequence of the four options above and it was never written down. Three of
 * them are network providers, and the fourth runs only on the JVM: there is no framework-neutral
 * multiplatform embedder in the Kotlin ecosystem, only a demonstration wrapper around ONNX Runtime
 * with no releases and a Gemma-focused binding under a licence that rules it out for most consumers.
 *
 * It matters because a semantic cache exists to avoid a round trip to a model. If deciding whether to
 * serve from cache needs a round trip to an embedding API, the saving is the difference between two
 * network calls rather than between one and none. The latency argument disappears and only the
 * token-cost argument survives, which is a much weaker case and not the one the multiplatform targets
 * were added for.
 *
 * M41 measured the alternative rather than assuming it. One forward pass at `all-MiniLM-L6-v2` shape,
 * arithmetic only, in ordinary Kotlin, on `macosArm64`: **2.5 seconds per call** at 274 MFLOP/s, with
 * 42 MB of encoder weights and a 46 MB vocabulary table beside them. An embedding API answers in 50 to
 * 200 ms, on hardware slower than the machine that measured this, so a pure-Kotlin on-device embedder
 * would be an order of magnitude slower than the call it exists to avoid. `OnDeviceEmbeddingTest` is
 * the measurement and the README carries the decision that follows from it.
 *
 * So on `iosArm64`, `macosArm64`, `linuxX64`, `mingwX64`, `js` and `wasmJs`, plan for a network
 * embedder, or supply a platform-native inference runtime through this seam yourself.
 */
public fun interface Embedder {

    /** Embeds a single [text]. Vectors need not be normalized; kmemo normalizes on the way in. */
    public suspend fun embed(text: String): FloatArray

    /**
     * Embeds [texts] in one go. The default implementation is a sequential loop; override it
     * whenever your provider exposes a batch endpoint, which is usually both cheaper and faster.
     * The result must preserve input order and have the same size as [texts].
     */
    public suspend fun embedAll(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    /**
     * Which model wrote these vectors, declared by you.
     *
     * An entry records the identity of the embedder that produced it, and a lookup through a
     * *different* identity refuses that entry instead of scoring it. See
     * [MissReason.EMBEDDER_MISMATCH]. Two embedding models do not share a vector space, so a
     * similarity computed across them is a number with no meaning, and a meaningless number near a
     * threshold is exactly the condition that produces a false hit. Nothing throws and nothing appears
     * in a log, because the dimension count usually matches: most upgrades within one provider's
     * family keep it, which is why the existing dimension check catches only the loud version of this
     * mistake.
     *
     * kmemo does not try to *infer* this. There is no way to read a model's name out of its output,
     * and a library that guessed would be wrong quietly. So you declare it, and what you declare is
     * opaque to kmemo: it is compared for equality and never parsed. Include everything that changes
     * the space: the provider, the model, the dimension count if the model is configurable, and your
     * own revision marker if you fine-tune.
     *
     * ```kotlin
     * val embedder = object : Embedder {
     *     override val identity: String = "openai:text-embedding-3-small:1536"
     *     override suspend fun embed(text: String) = openAi.embed(text)
     * }
     * ```
     *
     * The default is [UNDECLARED], and it is an identity like any other rather than a wildcard. A
     * cache that declares nothing writes and reads `undeclared` entries and behaves exactly as it did
     * before this existed. Declaring one on a store that already holds entries means those entries no
     * longer match. That is the honest outcome, since nobody can say what wrote them, and
     * `docs/MIGRATION.md` names the two ways through it.
     *
     * Must be **stable for the lifetime of the cache**, for the same reason the vectors must be: it is
     * read once, when the [SemanticCache] is constructed.
     */
    public val identity: String get() = UNDECLARED

    public companion object {
        /**
         * The identity of an embedder that declares none.
         *
         * Not a wildcard and not a "match anything" value. It is the single identity every caller
         * shared before identities existed, which is what lets an undeclared cache keep behaving
         * exactly as it always has while a declared one is held to its declaration.
         */
        public const val UNDECLARED: String = "undeclared"
    }
}
