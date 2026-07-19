package dev.nacode.kmemo

import dev.nacode.kmemo.guard.GuardVerdict
import dev.nacode.kmemo.guard.MatchGuard
import dev.nacode.kmemo.guard.MatchGuards
import dev.nacode.kmemo.store.InMemoryStore
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.nanoseconds
import java.time.Duration as JavaDuration

/**
 * A cache keyed by what a prompt *means* rather than by its exact bytes.
 *
 * Every prompt is embedded, compared against the prompts already cached, and — if something is close
 * enough and survives the guards — answered from the cache instead of from the model. Two prompts
 * worded differently but asking the same thing hit the same entry, which an exact-match cache can
 * never do.
 *
 * ```kotlin
 * val cache = SemanticCache(
 *     embedder = myEmbedder,
 *     store = InMemoryStore(maxEntries = 10_000, ttl = 1.hours),
 * )
 *
 * val answer = cache.getOrPut(prompt) { llm.complete(it) }
 * ```
 *
 * ### Why this is not just a threshold
 *
 * The failure mode of a semantic cache is not a miss, it is a **false hit**: returning a cached
 * answer to a question it does not answer. `Convert 100 USD to EUR` and `Convert 250 USD to EUR`
 * embed at around 0.99 with every mainstream model. There is no threshold that accepts real
 * paraphrases and rejects that pair, because on the similarity axis the pair is *closer* than most
 * paraphrases are.
 *
 * So similarity is only the first filter here. Candidates that clear [threshold] are then read as
 * text by a chain of [MatchGuard]s looking for concrete evidence that the answers must differ — a
 * different number, unit, entity, time reference, or a flipped comparison. Anything that survives
 * can be sent to an optional [Verifier] for a final check. The costs are asymmetric and the defaults
 * follow that: a wrong rejection costs one API call, a wrong acceptance costs a wrong answer.
 *
 * ### Scopes
 *
 * Every entry belongs to a `scope`, and lookups only see their own. Anything that changes what a
 * correct answer looks like — model, temperature, system prompt, tenant, user's language — belongs
 * in the scope string, otherwise the cache will happily serve one model's answer to another's
 * caller:
 *
 * ```kotlin
 * cache.getOrPut(prompt, scope = "gpt-4o|t=0.0|v3") { llm.complete(it) }
 * ```
 *
 * Instances are safe to share across coroutines, as long as the [Embedder] and [CacheStore] are.
 *
 * @param embedder turns prompts into vectors; you supply it.
 * @param store where entries live and how they expire. Defaults to a bounded in-memory store.
 * @param threshold minimum cosine similarity for a candidate to be considered at all. The default
 *   is deliberately tight; calibrate it against your own model with
 *   [dev.nacode.kmemo.calibration.ThresholdCalibrator] rather than guessing.
 * @param guards vetoes applied to candidates that clear [threshold]. [MatchGuards.standard] by
 *   default; [MatchGuards.none] reproduces the naive similarity-only behaviour.
 * @param verifier optional final check, typically a cheap model call, run only on candidates that
 *   already passed everything else.
 * @param candidates how many nearest entries to consider. Looking past the closest one matters:
 *   when a guard rejects the top candidate, the second may still be a correct answer.
 * @param clock time source for entry timestamps.
 */
public class SemanticCache(
    private val embedder: Embedder,
    private val store: CacheStore = InMemoryStore(),
    private val threshold: Double = DEFAULT_THRESHOLD,
    private val guards: List<MatchGuard> = MatchGuards.standard(),
    private val verifier: Verifier? = null,
    private val candidates: Int = DEFAULT_CANDIDATES,
    private val clock: Clock = Clock.systemUTC(),
) {

    init {
        require(threshold in -1.0..1.0) { "threshold must be within [-1.0, 1.0], was $threshold" }
        require(candidates > 0) { "candidates must be positive, was $candidates" }
    }

    private val lookupCount = AtomicLong()
    private val hitCount = AtomicLong()
    private val belowThresholdCount = AtomicLong()
    private val guardRejectionCount = AtomicLong()
    private val verifierRejectionCount = AtomicLong()
    private val writeCount = AtomicLong()

    /**
     * Looks up [prompt] and reports the full outcome, including why a miss was a miss.
     *
     * Costs one [Embedder.embed] call. Use this over [get] when you want to log or act on the
     * reason — a cache whose hit rate is 4% is untunable unless you know whether prompts are
     * landing below the threshold or being vetoed by a guard.
     */
    public suspend fun lookup(prompt: String, scope: String = DEFAULT_SCOPE): CacheLookup =
        lookup(prompt, scope, embed(prompt))

    /** Returns the cached response for [prompt], or `null`. The short form of [lookup]. */
    public suspend fun get(prompt: String, scope: String = DEFAULT_SCOPE): String? =
        (lookup(prompt, scope) as? CacheLookup.Hit)?.response

    /**
     * Caches [response] as the answer to [prompt] and returns the new entry's id.
     *
     * Costs one [Embedder.embed] call. Prefer [getOrPut], which embeds once for the lookup and the
     * write together.
     */
    public suspend fun put(
        prompt: String,
        response: String,
        scope: String = DEFAULT_SCOPE,
        metadata: Map<String, String> = emptyMap(),
    ): String = put(prompt, response, scope, metadata, embed(prompt))

    /**
     * Returns the cached answer to [prompt], or calls [compute] and caches what it returns.
     *
     * The main entry point, and the reason to prefer it over [get] plus [put]: the prompt is
     * embedded **once** and the vector is reused for both the lookup and the write. Doing it by hand
     * costs two embedding calls on every miss.
     *
     * ```kotlin
     * val answer = cache.getOrPut(prompt) { llm.complete(it) }
     * ```
     *
     * [compute] runs outside any lock, so concurrent misses on the same prompt each call it — this
     * is a cache, not a request coalescer. Wrap it if duplicate in-flight calls matter to you.
     */
    public suspend fun getOrPut(
        prompt: String,
        scope: String = DEFAULT_SCOPE,
        metadata: Map<String, String> = emptyMap(),
        compute: suspend (String) -> String,
    ): String {
        val embedding = embed(prompt)
        val result = lookup(prompt, scope, embedding)
        if (result is CacheLookup.Hit) return result.response

        val response = compute(prompt)
        put(prompt, response, scope, metadata, embedding)
        return response
    }

    /** Removes the entry [id], typically one reported by a [CacheLookup.Hit] that proved wrong. */
    public suspend fun invalidate(id: String): Boolean = store.remove(id)

    /** Removes every entry in [scope], or the whole cache when [scope] is `null`. */
    public suspend fun clear(scope: String? = null): Unit = store.clear(scope)

    /** Number of cached entries in [scope], or in the whole cache when [scope] is `null`. */
    public suspend fun size(scope: String? = null): Int = store.size(scope)

    /**
     * Counters since this instance was created. See [CacheStats] for what they tell you.
     *
     * The counters are read one at a time, so a lookup finishing mid-read could otherwise report
     * more hits than lookups — and a negative miss count. Reading hits first and clamping keeps the
     * invariants [CacheStats] documents; the cost is that a concurrent snapshot may under-report a
     * hit by one, which no one is tuning against.
     */
    public fun stats(): CacheStats {
        val hits = hitCount.get()
        val lookups = maxOf(lookupCount.get(), hits)
        return CacheStats(
            lookups = lookups,
            hits = hits,
            misses = lookups - hits,
            belowThreshold = belowThresholdCount.get(),
            guardRejections = guardRejectionCount.get(),
            verifierRejections = verifierRejectionCount.get(),
            writes = writeCount.get(),
        )
    }

    private suspend fun embed(prompt: String): FloatArray = Vectors.normalize(embedder.embed(prompt))

    private suspend fun lookup(prompt: String, scope: String, embedding: FloatArray): CacheLookup {
        lookupCount.incrementAndGet()

        val found = store.search(scope, embedding, candidates)
        if (found.isEmpty()) {
            return CacheLookup.Miss(MissReason.EMPTY_SCOPE, null, null, null)
        }

        // Whichever candidate was refused first — candidates arrive best-first, so this is the
        // closest one that was refused, and the reason, the prompt and the score all describe that
        // same entry. Reporting a reason from one candidate next to the similarity of another is
        // how a diagnostic turns into a wild goose chase.
        var refusal: Refusal? = null

        for (scored in found) {
            // Results are sorted by descending similarity, so the first one below the threshold
            // means every remaining one is too.
            if (scored.similarity < threshold) break

            val rejection = firstRejection(prompt, scored.entry.prompt)
            if (rejection != null) {
                if (refusal == null) refusal = Refusal(MissReason.REJECTED_BY_GUARD, scored, rejection)
                continue
            }

            if (verifier?.verify(prompt, scored.entry.prompt, scored.similarity) == false) {
                if (refusal == null) {
                    refusal = Refusal(
                        MissReason.REJECTED_BY_VERIFIER,
                        scored,
                        "verifier rejected this candidate",
                    )
                }
                continue
            }

            return hit(scored)
        }

        if (refusal == null) {
            val best = found.first()
            belowThresholdCount.incrementAndGet()
            return miss(MissReason.BELOW_THRESHOLD, best, "best similarity ${best.similarity} < $threshold")
        }

        when (refusal.reason) {
            MissReason.REJECTED_BY_GUARD -> guardRejectionCount.incrementAndGet()
            else -> verifierRejectionCount.incrementAndGet()
        }
        return miss(refusal.reason, refusal.candidate, refusal.detail)
    }

    private class Refusal(val reason: MissReason, val candidate: ScoredEntry, val detail: String)

    private fun firstRejection(prompt: String, candidatePrompt: String): String? {
        for (guard in guards) {
            val verdict = guard.evaluate(prompt, candidatePrompt)
            if (verdict is GuardVerdict.Reject) return "${guard.name}: ${verdict.reason}"
        }
        return null
    }

    private suspend fun hit(scored: ScoredEntry): CacheLookup.Hit {
        store.touch(scored.entry.id)
        hitCount.incrementAndGet()
        val age = JavaDuration.between(scored.entry.createdAt, clock.instant()).toNanos().nanoseconds
        return CacheLookup.Hit(
            response = scored.entry.response,
            matchedPrompt = scored.entry.prompt,
            similarity = scored.similarity,
            entryId = scored.entry.id,
            age = age,
            metadata = scored.entry.metadata,
        )
    }

    private fun miss(reason: MissReason, best: ScoredEntry, detail: String?): CacheLookup.Miss =
        CacheLookup.Miss(reason, best.similarity, best.entry.prompt, detail)

    private suspend fun put(
        prompt: String,
        response: String,
        scope: String,
        metadata: Map<String, String>,
        embedding: FloatArray,
    ): String {
        val id = UUID.randomUUID().toString()
        store.put(
            CacheEntry(
                id = id,
                scope = scope,
                prompt = prompt,
                response = response,
                embedding = embedding,
                createdAt = clock.instant(),
                metadata = metadata,
            ),
        )
        writeCount.incrementAndGet()
        return id
    }

    public companion object {
        /**
         * Scope used when a caller does not name one.
         *
         * Fine for a single model with fixed parameters. The moment a second model, a second system
         * prompt or a second tenant shares the cache, pass an explicit scope instead.
         */
        public const val DEFAULT_SCOPE: String = "default"

        /**
         * Conservative by design.
         *
         * The right value depends on your embedding model — the same pair of prompts scores 0.86
         * with one and 0.94 with another, so no library default can be correct for everyone. This
         * one errs toward missing rather than toward serving the wrong answer. Measure yours with
         * [dev.nacode.kmemo.calibration.ThresholdCalibrator].
         */
        public const val DEFAULT_THRESHOLD: Double = 0.95

        /** Nearest entries examined per lookup. Enough to recover when a guard vetoes the closest. */
        public const val DEFAULT_CANDIDATES: Int = 5
    }
}
