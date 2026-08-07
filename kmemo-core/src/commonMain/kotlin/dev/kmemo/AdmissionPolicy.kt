package dev.kmemo

/**
 * Decides whether a prompt has been asked often enough to be worth **storing**.
 *
 * Every miss writes, and that is the right default for a cache being filled deliberately. It is the
 * wrong one for a cache sitting in front of real traffic, where most prompts are asked once and never
 * again. A prompt asked at three in the morning by one user becomes an entry that lives until it is
 * evicted or expires, and every lookup after it pays to score it. On the exact-scan stores that cost is
 * linear in the store size, so it lands on every request rather than on the requests that caused it.
 *
 * [CachePolicy] is the wrong instrument for this and always was: it decides on the content of one
 * prompt and one response, and answers "may this be stored", never "is this worth storing". This
 * answers the second question, and only the second. It remembers a compact sketch of what has been
 * asked and admits an entry on the [minSightings]th sighting rather than the first, so a prompt earns
 * its place by being repeated.
 *
 * ### Two constraints, and both are about what admission may look at
 *
 * **It may only ever decide whether to write, never whether to serve.** A lookup is unaffected: a
 * prompt that has not been admitted is still looked up, still matched, still served if something in
 * the store answers it. So a bad admission decision costs a future miss and nothing else, which is the
 * same discipline [Quantization] follows.
 *
 * **The sketch is keyed on the exact prompt text within a scope, never on similarity.** A frequency
 * estimate that counted two different questions as the same question would be the false hit this
 * library exists to prevent, arriving through the write path.
 *
 * ### What it applies to
 *
 * The write that follows a **miss**: [SemanticCache.getOrPut], [SemanticCache.getOrPutAll] and
 * [SemanticCache.getOrPutStreaming]. Not [SemanticCache.put] and not [SemanticCache.warm], which are a
 * caller saying "store this" rather than traffic arriving, and second-guessing those would be
 * surprising in a way that a frequency estimate cannot justify.
 *
 * That is the opposite of [CachePolicy], which covers every write path including [SemanticCache.warm]
 * because a guarantee with one path around it is not a guarantee. The difference is what the two are:
 * one is a promise about what must never be persisted, the other is an optimisation about what is
 * worth persisting.
 *
 * ```kotlin
 * val cache = semanticCache(embedder) {
 *     admissionPolicy = AdmissionPolicy()   // admit on the second sighting
 * }
 * ```
 *
 * @param minSightings how many times a prompt must be seen before its answer is stored. `2` admits on
 *   the first repeat, which is the whole idea; higher values fill the store more slowly and give up
 *   more hits. `1` admits everything and is the same as no policy at all.
 * @param sketchWidth counters per row. Wider is a more accurate estimate and more memory: the default
 *   is 4,096 counters across 4 rows, so 64 KiB, whatever the traffic.
 * @param sketchDepth rows of counters. A key's estimate is the smallest of the [sketchDepth] counters
 *   it hashes to, so more rows make an over-estimate from a hash collision less likely.
 * @param resetAfter sightings after which every counter halves, so the estimate follows recent
 *   traffic. Without it a prompt asked twice a year apart looks like one asked twice this morning.
 */
public class AdmissionPolicy(
    public val minSightings: Int = DEFAULT_MIN_SIGHTINGS,
    public val sketchWidth: Int = DEFAULT_SKETCH_WIDTH,
    public val sketchDepth: Int = DEFAULT_SKETCH_DEPTH,
    public val resetAfter: Int = DEFAULT_RESET_AFTER,
) {
    init {
        require(minSightings >= 1) { "minSightings must be at least 1, was $minSightings" }
        require(sketchWidth > 0) { "sketchWidth must be positive, was $sketchWidth" }
        require(sketchDepth > 0) { "sketchDepth must be positive, was $sketchDepth" }
        require(resetAfter > 0) { "resetAfter must be positive, was $resetAfter" }
    }

    public companion object {
        /** Admit on the first repeat. */
        public const val DEFAULT_MIN_SIGHTINGS: Int = 2

        /** Counters per row. */
        public const val DEFAULT_SKETCH_WIDTH: Int = 4_096

        /** Rows of counters. */
        public const val DEFAULT_SKETCH_DEPTH: Int = 4

        /** Sightings between halvings. */
        public const val DEFAULT_RESET_AFTER: Int = 100_000
    }
}
