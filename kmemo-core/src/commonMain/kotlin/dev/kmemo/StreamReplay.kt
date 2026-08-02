package dev.kmemo

/**
 * How [SemanticCache.getOrPutStreaming] replays a cached answer on a hit.
 *
 * A cache hit on a streaming path has to decide what a "stream" of an answer that is already written
 * down even means, and the honest answers are few. This enum exists so the decision is made in the
 * API, by the caller, rather than falling out of an implementation detail.
 *
 * ### What is *not* here, and why
 *
 * There is no option that reproduces the original timing. Doing it would mean recording how long each
 * chunk took to arrive from the provider — figures that describe one model call, on one network, on
 * one day — and then sleeping through them to serve an answer the cache already has in memory. That
 * is a cache made deliberately slower in order to look like the thing it replaced, and the whole
 * argument for this library is that it does not dress one measurement up as another. A hit is fast.
 * Letting it *look* slow would be the pretence, not the honesty.
 *
 * If a product genuinely needs a typewriter effect, that is a presentation decision and it belongs in
 * the presentation layer, where it can be tuned, disabled and tested — not baked into a cache and paid
 * for in stored bytes on every entry.
 */
public enum class StreamReplay {

    /**
     * Emit the same chunks the provider produced, one after another, with no delay. The default.
     *
     * The content is identical to what the original caller received, boundary for boundary: a
     * collector that concatenates gets the same string, and a collector that renders per chunk renders
     * the same steps. Only the wait is gone, which is the part the cache was hired to remove.
     *
     * An entry written by any path other than [SemanticCache.getOrPutStreaming] has no recorded
     * boundaries, so it replays as a single chunk. That is not a special case, it is the truth about
     * that entry: nobody ever streamed it.
     */
    AS_STREAMED,

    /**
     * Emit the whole answer as one element.
     *
     * For a collector that is going to join the chunks anyway, or one whose per-element cost is real
     * — a UI that re-renders on every emission does not want four hundred of them for text it could
     * have taken in one. This is what `2.0` did unconditionally.
     */
    WHOLE,
}
