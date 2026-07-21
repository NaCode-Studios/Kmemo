package dev.kmemo

import dev.kmemo.guard.GuardVerdict
import dev.kmemo.guard.MatchGuard
import dev.kmemo.guard.MatchGuards
import dev.kmemo.internal.KeyedMutex
import dev.kmemo.internal.NegativeCache
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
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
 *   [dev.kmemo.calibration.ThresholdCalibrator] rather than guessing.
 * @param guards vetoes applied to candidates that clear [threshold]. [MatchGuards.standard] by
 *   default; [MatchGuards.none] reproduces the naive similarity-only behaviour.
 * @param verifier optional final check, typically a cheap model call, run only on candidates that
 *   already passed everything else.
 * @param verifierTimeout optional cap on a single [Verifier.verify] call. On timeout — or if the
 *   verifier throws — the candidate is **rejected**, not served: a check that could not complete must
 *   fail closed, since the verifier exists precisely to keep an unconfirmed answer out. `null` (the
 *   default) applies no timeout.
 * @param candidates how many nearest entries to consider. Looking past the closest one matters:
 *   when a guard rejects the top candidate, the second may still be a correct answer.
 * @param coalesceConcurrentMisses whether concurrent [getOrPut] calls for the same prompt in the
 *   same scope wait for the first one instead of each calling the model. On by default: a cold
 *   cache under load is the case where duplicate calls are most expensive and most likely.
 * @param embedFailurePolicy what [getOrPut] does when the [Embedder] throws — propagate (the default)
 *   or fall back to [compute] so a lookup is never *worse* than no cache. See [EmbedFailurePolicy].
 *   [lookup], [get] and [put] have no fallback and always propagate. [CancellationException] always
 *   propagates.
 * @param negativeCacheSize when positive, turns on a bounded negative cache: the embedding of a prompt
 *   that just missed is remembered, so an immediate repeat of the *same brand-new prompt* is embedded
 *   once rather than once per caller. Extends the concurrent-miss coalescing to the near-in-time
 *   sequential case. It only ever reuses an embedding — it never suppresses the store search — so it
 *   cannot cause a false hit. `0` (the default) keeps it off.
 * @param negativeCacheTtl how long a remembered miss stays usable when [negativeCacheSize] is positive,
 *   or `null` to keep it until evicted. A short TTL is the point: it should cover a burst, not pin a
 *   stale embedding for a prompt that has since been answered elsewhere.
 * @param listeners observers notified of every hit, miss and write as it happens (see [CacheEvent]).
 *   Empty by default, and an empty list is free: with no listeners the cache builds no events and
 *   measures no latencies, so the hot path is exactly as it was. Each listener runs inline and must be
 *   fast and non-throwing — see [CacheListener].
 * @param clock time source for entry timestamps.
 */
public class SemanticCache(
    private val embedder: Embedder,
    private val store: CacheStore = InMemoryStore(),
    private val threshold: Double = DEFAULT_THRESHOLD,
    private val guards: List<MatchGuard> = MatchGuards.standard(),
    private val verifier: Verifier? = null,
    private val verifierTimeout: Duration? = null,
    private val candidates: Int = DEFAULT_CANDIDATES,
    private val coalesceConcurrentMisses: Boolean = true,
    private val embedFailurePolicy: EmbedFailurePolicy = EmbedFailurePolicy.PROPAGATE,
    private val negativeCacheSize: Int = 0,
    private val negativeCacheTtl: Duration? = null,
    private val listeners: List<CacheListener> = emptyList(),
    private val clock: Clock = Clock.systemUTC(),
) {

    init {
        require(threshold in -1.0..1.0) { "threshold must be within [-1.0, 1.0], was $threshold" }
        require(candidates > 0) { "candidates must be positive, was $candidates" }
        require(verifierTimeout == null || verifierTimeout.isPositive()) {
            "verifierTimeout must be positive, was $verifierTimeout"
        }
        require(negativeCacheSize >= 0) { "negativeCacheSize must be non-negative, was $negativeCacheSize" }
        require(negativeCacheTtl == null || negativeCacheTtl.isPositive()) {
            "negativeCacheTtl must be positive, was $negativeCacheTtl"
        }
    }

    private val inFlight = KeyedMutex()

    // Null unless negative caching is turned on; the hot path checks it with a single null test.
    private val negativeCache: NegativeCache? =
        if (negativeCacheSize > 0) NegativeCache(negativeCacheSize, negativeCacheTtl, clock) else null

    // Gates every piece of event machinery — timing measurement and event construction alike — so a
    // cache with no listeners pays nothing for observability. Snapshotted once: listeners is fixed.
    private val observed: Boolean = listeners.isNotEmpty()

    private val lookupCount = AtomicLong()
    private val hitCount = AtomicLong()
    private val belowThresholdCount = AtomicLong()
    private val guardRejectionCount = AtomicLong()
    private val verifierRejectionCount = AtomicLong()
    private val writeCount = AtomicLong()

    // One counter per guard, fixed at construction so no entry is ever inserted concurrently — the
    // hot path only ever does an atomic increment on a key that already exists. Guards that share a
    // name (unusual) share a counter, which keeps the per-guard sum equal to [guardRejectionCount].
    private val guardRejectionCountersByName: Map<String, AtomicLong> =
        guards.associate { it.name to AtomicLong() }

    /**
     * Looks up [prompt] and reports the full outcome, including why a miss was a miss.
     *
     * Costs one [Embedder.embed] call. Use this over [get] when you want to log or act on the
     * reason — a cache whose hit rate is 4% is untunable unless you know whether prompts are
     * landing below the threshold or being vetoed by a guard.
     */
    public suspend fun lookup(prompt: String, scope: String = DEFAULT_SCOPE): CacheLookup {
        val embedStart = if (observed) System.nanoTime() else 0L
        val embedding = embed(prompt, scope)
        val embedNanos = if (observed) System.nanoTime() - embedStart else 0L
        return lookup(prompt, scope, embedding, embedNanos = embedNanos)
    }

    /** Returns the cached response for [prompt], or `null`. The short form of [lookup]. */
    public suspend fun get(prompt: String, scope: String = DEFAULT_SCOPE): String? =
        (lookup(prompt, scope) as? CacheLookup.Hit)?.response

    /**
     * Explains how [prompt] would be decided in [scope], without changing anything.
     *
     * A read-only companion to [lookup] for tuning. It embeds the prompt (one [Embedder.embed] call)
     * and pulls the same nearest candidates, then reports each one's similarity and *every* guard's
     * verdict — not stopping at the first rejection, the way a real lookup does. It does **not** touch
     * [stats], does **not** mark any entry recently-used, and does **not** run the [Verifier]: a
     * diagnostic that moved the counters you are reading, or spent a model call, would defeat its own
     * purpose.
     *
     * Reach for it when a hit you expected did not happen. [CacheExplanation.decision] says whether
     * the threshold or a guard stood in the way, and [CandidateTrace.guardVerdicts] says which guard
     * and why — across every candidate, so a usable second-nearest entry is visible too.
     */
    public suspend fun explain(prompt: String, scope: String = DEFAULT_SCOPE): CacheExplanation {
        val embedding = embed(prompt, scope)
        val traces = store.search(scope, embedding, candidates).map { scored ->
            val verdicts = LinkedHashMap<String, GuardVerdict>(guards.size)
            for (guard in guards) verdicts[guard.name] = guard.evaluate(prompt, scored.entry.prompt)
            CandidateTrace(
                prompt = scored.entry.prompt,
                similarity = scored.similarity,
                aboveThreshold = scored.similarity >= threshold,
                guardVerdicts = verdicts,
            )
        }
        return CacheExplanation(prompt, scope, threshold, traces)
    }

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
    ): String = put(prompt, response, scope, metadata, embed(prompt, scope))

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
     * Concurrent callers asking the same thing are **coalesced**: the first one computes, the rest
     * wait and are served its answer. Without that, a cold cache under load is worse than no cache
     * — fifty requests for the same prompt arrive together, all miss, and all pay. Coalescing is
     * per scope and per exact prompt text; near-matches still go through the normal lookup. Pass
     * `coalesceConcurrentMisses = false` to let every caller compute independently.
     */
    public suspend fun getOrPut(
        prompt: String,
        scope: String = DEFAULT_SCOPE,
        metadata: Map<String, String> = emptyMap(),
        compute: suspend (String) -> String,
    ): String {
        val embedStart = if (observed) System.nanoTime() else 0L
        val embedding = try {
            embed(prompt, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The one call the cache makes on every lookup just failed. PROPAGATE surfaces it;
            // FALL_BACK_TO_COMPUTE degrades to an uncached model call so the caller is never worse off
            // than with no cache. The answer cannot be written back — there is no embedding to key it —
            // so nothing is cached until the embedder recovers.
            if (embedFailurePolicy == EmbedFailurePolicy.FALL_BACK_TO_COMPUTE) return compute(prompt)
            throw e
        }
        val embedNanos = if (observed) System.nanoTime() - embedStart else 0L
        val result = lookup(prompt, scope, embedding, embedNanos = embedNanos)
        if (result is CacheLookup.Hit) return result.response
        if (!coalesceConcurrentMisses) return computeAndPut(prompt, scope, metadata, embedding, compute)

        return inFlight.withKeyLock("$scope\u0000$prompt") {
            // Whoever held the lock before us may have just answered this exact question. Looking
            // again costs a store read; not looking costs a model call.
            // Not counted: this is the same caller's single lookup, resumed. Counting it again
            // reported more misses than there were calls and halved the hit rate of the very
            // workload coalescing exists to improve.
            val second = lookup(prompt, scope, embedding, counted = false)
            if (second is CacheLookup.Hit) {
                second.response
            } else {
                computeAndPut(prompt, scope, metadata, embedding, compute)
            }
        }
    }

    private suspend fun computeAndPut(
        prompt: String,
        scope: String,
        metadata: Map<String, String>,
        embedding: FloatArray,
        compute: suspend (String) -> String,
    ): String {
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
            guardRejectionsByGuard = guardRejectionCountersByName.mapValues { it.value.get() },
        )
    }

    /**
     * Embeds [prompt], reusing a recently-missed embedding for the same ([scope], [prompt]) when the
     * negative cache is on. The negative cache only ever supplies a precomputed vector, so this is a
     * transparent embed-call saver — the result is identical to a fresh [Embedder.embed].
     */
    private suspend fun embed(prompt: String, scope: String): FloatArray {
        negativeCache?.get(scope, prompt)?.let { return it }
        return Vectors.normalize(embedder.embed(prompt))
    }

    private suspend fun lookup(
        prompt: String,
        scope: String,
        embedding: FloatArray,
        counted: Boolean = true,
        embedNanos: Long = 0,
    ): CacheLookup {
        // `counted = false` is the coalescing re-check: the same caller's single lookup, resumed
        // after waiting. Counting it a second time reported more misses than there were calls and
        // halved the hit rate of the very workload coalescing exists to improve.
        if (counted) lookupCount.incrementAndGet()

        // Timings are only ever measured on the observed, counted path; nanoTime is cheap but not free,
        // and the coalescing re-check must not double-count a stage it did not run.
        val measure = observed && counted
        val searchStart = if (measure) System.nanoTime() else 0L
        val found = store.search(scope, embedding, candidates)
        val searchNanos = if (measure) System.nanoTime() - searchStart else 0L
        var verifierNanos = 0L

        if (found.isEmpty()) {
            rememberMiss(scope, prompt, embedding, counted)
            return CacheLookup.Miss(MissReason.EMPTY_SCOPE, null, null, null)
                .also { emitMiss(scope, prompt, it, embedNanos, searchNanos, verifierNanos, counted) }
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
                if (refusal == null) {
                    refusal = Refusal(
                        MissReason.REJECTED_BY_GUARD,
                        scored,
                        "${rejection.guardName}: ${rejection.reason}",
                        rejection.guardName,
                    )
                }
                continue
            }

            // Only time the verifier when there is one — otherwise verifierRejects returns instantly
            // and we would report a few nanoseconds of "verifier latency" for a check that never ran.
            val timeVerifier = measure && verifier != null
            val verifierStart = if (timeVerifier) System.nanoTime() else 0L
            val verifierDetail = verifierRejects(prompt, scored.entry.prompt, scored.similarity)
            if (timeVerifier) verifierNanos += System.nanoTime() - verifierStart
            if (verifierDetail != null) {
                if (refusal == null) {
                    refusal = Refusal(MissReason.REJECTED_BY_VERIFIER, scored, verifierDetail, guardName = null)
                }
                continue
            }

            return hit(scored, counted)
                .also { emitHit(scope, prompt, it, embedNanos, searchNanos, verifierNanos, counted) }
        }

        if (refusal == null) {
            val best = found.first()
            if (counted) belowThresholdCount.incrementAndGet()
            rememberMiss(scope, prompt, embedding, counted)
            return miss(MissReason.BELOW_THRESHOLD, best, "best similarity ${best.similarity} < $threshold")
                .also { emitMiss(scope, prompt, it, embedNanos, searchNanos, verifierNanos, counted) }
        }

        if (counted) {
            when (refusal.reason) {
                MissReason.REJECTED_BY_GUARD -> {
                    guardRejectionCount.incrementAndGet()
                    refusal.guardName?.let { guardRejectionCountersByName[it]?.incrementAndGet() }
                }
                else -> verifierRejectionCount.incrementAndGet()
            }
        }
        // Captured before the .also lambda: refusal is a var, so its smart-cast to non-null does not
        // survive into a closure.
        val guardName = refusal.guardName
        rememberMiss(scope, prompt, embedding, counted)
        return miss(refusal.reason, refusal.candidate, refusal.detail)
            .also { emitMiss(scope, prompt, it, embedNanos, searchNanos, verifierNanos, counted, guardName) }
    }

    private fun emitHit(
        scope: String,
        prompt: String,
        hit: CacheLookup.Hit,
        embedNanos: Long,
        searchNanos: Long,
        verifierNanos: Long,
        counted: Boolean,
    ) {
        if (!observed || !counted) return
        emit(
            CacheEvent.Hit(
                scope, prompt, hit.matchedPrompt, hit.similarity, hit.entryId,
                EventTimings(embedNanos, searchNanos, verifierNanos),
            ),
        )
    }

    private fun emitMiss(
        scope: String,
        prompt: String,
        miss: CacheLookup.Miss,
        embedNanos: Long,
        searchNanos: Long,
        verifierNanos: Long,
        counted: Boolean,
        guardName: String? = null,
    ) {
        if (!observed || !counted) return
        emit(
            CacheEvent.Miss(
                scope, prompt, miss.reason, miss.bestSimilarity, miss.detail, guardName,
                EventTimings(embedNanos, searchNanos, verifierNanos),
            ),
        )
    }

    /**
     * Delivers [event] to every listener, in order. A listener that throws is swallowed here — the
     * one place it can be — because a broken telemetry sink must never turn a good lookup into a
     * failed one. Callers gate on [observed] before building the event, so this is never reached with
     * an empty listener list.
     */
    private fun emit(event: CacheEvent) {
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (_: Exception) {
                // A listener's failure is its own; see CacheListener's contract.
            }
        }
    }

    /**
     * Remembers a counted miss's embedding in the negative cache, when it is enabled. Only the counted
     * lookup remembers: the coalescing re-check is the same caller's lookup resumed, and re-storing on
     * it would only refresh the timestamp. A no-op — not even a suspension point cost — when off.
     */
    private suspend fun rememberMiss(scope: String, prompt: String, embedding: FloatArray, counted: Boolean) {
        if (counted) negativeCache?.put(scope, prompt, embedding)
    }

    private class Refusal(
        val reason: MissReason,
        val candidate: ScoredEntry,
        val detail: String,
        // The guard that fired, for the per-guard breakdown; null for a verifier refusal.
        val guardName: String?,
    )

    private class GuardRejection(val guardName: String, val reason: String)

    private fun firstRejection(prompt: String, candidatePrompt: String): GuardRejection? {
        for (guard in guards) {
            val verdict = guard.evaluate(prompt, candidatePrompt)
            if (verdict is GuardVerdict.Reject) return GuardRejection(guard.name, verdict.reason)
        }
        return null
    }

    /**
     * Runs the verifier fail-closed: returns a rejection detail, or `null` when the candidate passed
     * (or there is no verifier). A verifier that throws or times out **rejects** — serving a response
     * it could not confirm is the one outcome the verifier exists to prevent, and a rejection costs a
     * single model call, the asymmetry the whole cache turns on. [CancellationException] is never
     * swallowed, so coroutine cancellation still propagates.
     */
    private suspend fun verifierRejects(prompt: String, candidate: String, similarity: Double): String? {
        val verifier = verifier ?: return null
        return try {
            val passed = if (verifierTimeout == null) {
                verifier.verify(prompt, candidate, similarity)
            } else {
                withTimeoutOrNull(verifierTimeout) { verifier.verify(prompt, candidate, similarity) }
                    ?: return "verifier timed out after $verifierTimeout"
            }
            if (passed) null else "verifier rejected this candidate"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "verifier failed (${e::class.simpleName ?: "error"}), treated as a rejection"
        }
    }

    private suspend fun hit(scored: ScoredEntry, counted: Boolean = true): CacheLookup.Hit {
        store.touch(scored.entry.id)
        if (counted) hitCount.incrementAndGet()
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
        // The prompt is now answerable from the store, so its "recently missed" note is stale — drop it
        // so a later lookup embeds fresh rather than reusing a vector for a miss that no longer holds.
        negativeCache?.remove(scope, prompt)
        if (observed) emit(CacheEvent.Write(scope, prompt, id))
        return id
    }

    /**
     * Seeds the cache with known prompt/response pairs, embedding them in **one batch call** where the
     * [Embedder] supports it (see [Embedder.embedAll]).
     *
     * The intended use is startup warming from a fixed set — an FAQ, a golden set of canned answers —
     * so the first real user of each prompt gets a hit instead of paying to populate the cache. It is
     * far cheaper than [put]ting them one by one: a provider that prices and rate-limits per request
     * embeds the whole batch for the cost of a single round trip.
     *
     * Entries are written in the given order and each gets a fresh id, exactly as [put] would; the
     * returned ids line up with [entries]. Warming does not consult or move any counter — it is a
     * write path, not a lookup — and it bypasses the negative cache. An empty [entries] is a no-op.
     *
     * @return the id assigned to each written entry, in the order of [entries].
     */
    public suspend fun warm(entries: List<WarmEntry>): List<String> {
        if (entries.isEmpty()) return emptyList()
        val embeddings = embedder.embedAll(entries.map { it.prompt })
        require(embeddings.size == entries.size) {
            "embedAll returned ${embeddings.size} vectors for ${entries.size} prompts; an Embedder " +
                "must return one vector per input, in order"
        }
        val now = clock.instant()
        val ids = ArrayList<String>(entries.size)
        for (i in entries.indices) {
            val entry = entries[i]
            val id = UUID.randomUUID().toString()
            store.put(
                CacheEntry(
                    id = id,
                    scope = entry.scope,
                    prompt = entry.prompt,
                    response = entry.response,
                    embedding = embeddings[i],
                    createdAt = now,
                    metadata = entry.metadata,
                ),
            )
            writeCount.incrementAndGet()
            negativeCache?.remove(entry.scope, entry.prompt)
            if (observed) emit(CacheEvent.Write(entry.scope, entry.prompt, id))
            ids += id
        }
        return ids
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
         * [dev.kmemo.calibration.ThresholdCalibrator].
         */
        public const val DEFAULT_THRESHOLD: Double = 0.95

        /** Nearest entries examined per lookup. Enough to recover when a guard vetoes the closest. */
        public const val DEFAULT_CANDIDATES: Int = 5
    }
}
