package dev.kmemo

/**
 * Turns a typed response into the `String` a [SemanticCache] stores, and back.
 *
 * The cache keeps text — that is what an LLM returns — but the second-most-common thing to cache is a
 * *structured* output: a parsed JSON object, an extracted record, a tool-call. Rather than pull a
 * serialization library onto the core classpath, kmemo ships the seam and you bring the codec, exactly
 * as it does for [Embedder] and [CacheStore]. A kotlinx.serialization one is two lines:
 *
 * ```kotlin
 * val codec = object : ResponseCodec<Weather> {
 *     override fun encode(value: Weather) = Json.encodeToString(value)
 *     override fun decode(text: String) = Json.decodeFromString<Weather>(text)
 * }
 * val weather: Weather = cache.getOrPut(prompt, codec) { llm.extractWeather(it) }
 * ```
 *
 * [encode] and [decode] must round-trip: `decode(encode(v))` has to equal `v` for anything you cache,
 * or a hit will not match what a miss computed. [decode] receives exactly what [encode] produced for
 * some earlier call (never arbitrary text), so it does not need to be defensive against foreign input.
 */
public interface ResponseCodec<T> {

    /** Serializes [value] to the text the cache will store. */
    public fun encode(value: T): String

    /** Reconstructs the value from [text], which is always a prior [encode] output. */
    public fun decode(text: String): T
}
