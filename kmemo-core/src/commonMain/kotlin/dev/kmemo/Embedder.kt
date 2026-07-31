package dev.kmemo

/**
 * Turns text into a vector. kmemo ships no embedding implementation on purpose: you bring your own
 * (OpenAI, Cohere, Voyage, a local ONNX model, whatever your stack already pays for), and kmemo
 * stays free of provider SDKs.
 *
 * Implementations must be **deterministic and stable**: the same text must map to the same vector
 * for the lifetime of a cache, and every vector must have the same dimension. Switching embedding
 * model invalidates every entry that came before it — use a fresh store, or a different
 * `scope`, when you do.
 *
 * Implementations are called from coroutines and must be safe to call concurrently.
 *
 * ```kotlin
 * val embedder = Embedder { text -> openAi.embeddings(model = "text-embedding-3-small", input = text).vector() }
 * ```
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
}
