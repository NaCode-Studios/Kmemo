package dev.kmemo

/**
 * One prompt/response pair to preload into a [SemanticCache] via [SemanticCache.warm].
 *
 * The same fields [SemanticCache.put] takes, bundled so a whole batch can be embedded in one call.
 * Keep the [scope] identical to the one real lookups will use — a warmed entry in the wrong scope is
 * invisible, exactly as it would be for a normal write.
 *
 * @param prompt the prompt to cache the answer for, stored verbatim (the guards re-read it on a hit).
 * @param response the answer to replay when this prompt is matched.
 * @param scope the partition the entry belongs to; defaults to [SemanticCache.DEFAULT_SCOPE].
 * @param metadata free-form caller data returned untouched on a hit.
 */
public class WarmEntry(
    public val prompt: String,
    public val response: String,
    public val scope: String = SemanticCache.DEFAULT_SCOPE,
    public val metadata: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "WarmEntry(scope=$scope, prompt=${prompt.take(48)})"
}
