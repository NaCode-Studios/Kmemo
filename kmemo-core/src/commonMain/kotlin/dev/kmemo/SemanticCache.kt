@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.kmemo

import dev.kmemo.guard.GuardVerdict
import dev.kmemo.guard.MatchGuard
import dev.kmemo.guard.MatchGuards
import dev.kmemo.internal.CandidateOrder
import dev.kmemo.internal.ExactCache
import dev.kmemo.internal.GuardChain
import dev.kmemo.internal.Ids
import dev.kmemo.internal.KeyedMutex
import dev.kmemo.internal.NegativeCache
import dev.kmemo.internal.ShadowRun
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicLong
import kotlin.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

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
 *   propagates. Every fall-back is counted in [CacheStats.degradedLookups] and reported as
 *   [CacheEvent.Degraded], so stepping aside is never silent.
 * @param exactCacheSize when positive, turns on the **exact-match layer**: a bounded map from an exact
 *   ([scope], prompt) to the entry it resolved to, so a byte-for-byte repeat is answered without an
 *   [Embedder] call *or* a store search. An identical prompt in the same scope is the same question, so
 *   the fast path runs no guards and adds no false-hit risk by construction. `0` (the default) keeps it
 *   off.
 * @param exactCacheTtl how long a remembered prompt may be **served** from the exact layer. Set it no
 *   longer than your store's TTL: the layer answers without consulting the store, which is what owns
 *   expiry and eviction, so a longer window is a window in which it can serve something the store has
 *   already dropped. Past the TTL nothing stale is served — the remembered *embedding* is still reused,
 *   so the lookup goes through the ordinary path with the network call already paid. `null` means the
 *   layer never expires, which is only correct when the store has no TTL either.
 * @param thresholds per-scope overrides of [threshold], consulted by scope name with the global value
 *   as the fallback. One threshold is necessarily wrong for a service answering both regulated and
 *   casual questions, and tuning the single value to the strictest caller makes every other caller pay
 *   for it.
 * @param shadowThresholds when non-empty, puts the cache in **shadow mode**: every [getOrPut] runs the
 *   full lookup, reports what it *would* have decided at each of these thresholds through
 *   [CacheEvent.Shadow], and then **always** computes. Nothing is ever served from the cache, so a false
 *   hit cannot reach a user while you are still choosing a threshold; writes still happen, because a
 *   shadow cache that never fills would report a miss for everything and measure nothing.
 * @param reranker reorders the candidates that cleared the threshold before the guards see them, or
 *   `null` (the default) to try them nearest-first. It reorders and never rescores, and it runs *after*
 *   the threshold filter, so it can change the order the cache tries candidates in but never which
 *   candidates are eligible. [MmrReranker] is the one that ships; what it buys is fewer [Verifier] calls
 *   on a cache whose nearest entries are near-duplicates of each other.
 * @param deduplicateWrites when non-null, a similarity at or above which a new entry **replaces** the
 *   existing entry it duplicates instead of joining it. A cache that has answered the same question in
 *   six phrasings stores six copies of one answer, and every later lookup pays to score all six. Only an
 *   entry that clears this similarity *and* passes every guard is replaced, so deduplication can never
 *   merge two entries the cache would have refused to serve for each other. `null` (the default) keeps
 *   every write. Costs one extra store search per write, on the write path rather than the read path.
 * @param adaptiveThresholds when non-null, lets each scope's threshold follow its own traffic instead of
 *   staying where it was configured. **Requires a [verifier]**, and the constructor throws without one:
 *   adaptation lowers the threshold as well as raising it, and the only thing that makes lowering safe
 *   is something above the threshold that can tell a right answer from a wrong one. Pass the same object
 *   in [listeners] so it can see the outcomes it adapts on. See [AdaptiveThresholds].
 * @param cachePolicy vetoes writes of data that must never be persisted, consulted once per write on
 *   every write path including [warm]. `null` (the default) caches everything the cache decides to
 *   cache. A vetoed write is a policy decision, not a failure: the call still returns its response.
 *   See [CachePolicy].
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
 * @param writeBehindScope when non-null, turns on **write-behind**: on a [getOrPut] miss the cache
 *   returns as soon as [compute] does and the store write is applied off the caller's critical path,
 *   by a single worker running on this scope. Writes are applied in submission order while buffered;
 *   if the buffer is full the write falls through synchronously rather than being dropped, so a write
 *   is never lost (only, rarely, reordered under saturation). The window between compute and write is
 *   a small chance of a duplicate compute for the same brand-new prompt. Cancel this scope to stop the
 *   worker. `null` (the default) writes through synchronously, and [put]/[warm] always do.
 * @param writeBehindCapacity how many pending writes to buffer before falling through to a synchronous
 *   write. Only meaningful when [writeBehindScope] is set.
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
    private val writeBehindScope: CoroutineScope? = null,
    private val writeBehindCapacity: Int = DEFAULT_WRITE_BEHIND_CAPACITY,
    private val clock: Clock = Clock.System,
    private val cachePolicy: CachePolicy? = null,
    private val exactCacheSize: Int = 0,
    private val exactCacheTtl: Duration? = null,
    private val thresholds: Map<String, Double> = emptyMap(),
    private val shadowThresholds: List<Double> = emptyList(),
    private val reranker: CandidateReranker? = null,
    private val deduplicateWrites: Double? = null,
    private val adaptiveThresholds: AdaptiveThresholds? = null,
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
        require(writeBehindCapacity > 0) { "writeBehindCapacity must be positive, was $writeBehindCapacity" }
        require(exactCacheSize >= 0) { "exactCacheSize must be non-negative, was $exactCacheSize" }
        require(exactCacheTtl == null || exactCacheTtl.isPositive()) {
            "exactCacheTtl must be positive, was $exactCacheTtl"
        }
        for ((scope, value) in thresholds) {
            require(value in -1.0..1.0) { "threshold for scope '$scope' must be within [-1.0, 1.0], was $value" }
        }
        for (value in shadowThresholds) {
            require(value in -1.0..1.0) { "shadow threshold must be within [-1.0, 1.0], was $value" }
        }
        require(deduplicateWrites == null || deduplicateWrites in -1.0..1.0) {
            "deduplicateWrites must be within [-1.0, 1.0], was $deduplicateWrites"
        }
        // Not a guard rail that can be argued around: adaptation lowers the threshold when the traffic
        // says it can be lowered, and the only thing that makes that safe is something above the
        // threshold able to tell a right answer from a wrong one. Without a verifier this would be
        // guessing with correctness, which is the one thing this cache is built not to do.
        require(adaptiveThresholds == null || verifier != null) {
            "adaptiveThresholds requires a verifier. It moves the threshold on how often the verifier " +
                "refuses what it sees, and with no verifier there is no evidence and nothing catching " +
                "what a lowered threshold lets through."
        }
    }

    /**
     * The identity this cache writes onto every entry and demands of every entry it serves.
     *
     * Read once, here, rather than on each lookup. [Embedder.identity] is documented as stable for the
     * lifetime of the cache, and consulting it per lookup would let an implementation change the
     * answer halfway through — turning the one check that exists to make a swap loud into a source of
     * intermittent misses nobody could reproduce.
     */
    private val embedderIdentity: String = embedder.identity

    private val guardChain = GuardChain(guards)

    private val candidateOrder = CandidateOrder(reranker)

    private val shadowRun = ShadowRun(shadowThresholds, guardChain)

    private val inFlight = KeyedMutex()

    // Null unless negative caching is turned on; the hot path checks it with a single null test.
    private val negativeCache: NegativeCache? =
        if (negativeCacheSize > 0) NegativeCache(negativeCacheSize, negativeCacheTtl, clock) else null

    // Null unless the exact-match layer is turned on; the hot path checks it with a single null test.
    private val exactCache: ExactCache? =
        if (exactCacheSize > 0) ExactCache(exactCacheSize, exactCacheTtl, clock) else null

    // Gates every piece of event machinery — timing measurement and event construction alike — so a
    // cache with no listeners pays nothing for observability. Snapshotted once: listeners is fixed.
    private val observed: Boolean = listeners.isNotEmpty()

    // The write-behind queue, drained in order by one worker on [writeBehindScope]. Null when
    // write-behind is off, in which case every write is synchronous.
    private val writeChannel: Channel<CacheEntry>? =
        if (writeBehindScope != null) Channel(writeBehindCapacity) else null

    init {
        val channel = writeChannel
        if (writeBehindScope != null && channel != null) {
            writeBehindScope.launch {
                // Single consumer → FIFO. A failed write is swallowed so one bad store call cannot kill
                // the worker and silently turn every later write synchronous; the entry is simply not
                // cached, which is a future miss, not a correctness problem.
                for (entry in channel) {
                    try {
                        putEntry(entry)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Dropped write; see above.
                    }
                }
            }
        }
    }

    private val lookupCount = AtomicLong(0)
    private val hitCount = AtomicLong(0)
    private val belowThresholdCount = AtomicLong(0)
    private val guardRejectionCount = AtomicLong(0)
    private val verifierRejectionCount = AtomicLong(0)
    private val writeCount = AtomicLong(0)
    private val degradedCount = AtomicLong(0)
    private val writeVetoCount = AtomicLong(0)
    private val exactHitCount = AtomicLong(0)
    private val embedderMismatchCount = AtomicLong(0)

    // One counter per guard, fixed at construction so no entry is ever inserted concurrently — the
    // hot path only ever does an atomic increment on a key that already exists. Guards that share a
    // name (unusual) share a counter, which keeps the per-guard sum equal to [guardRejectionCount].
    private val guardRejectionCountersByName: Map<String, AtomicLong> =
        guards.associate { it.name to AtomicLong(0) }

    /**
     * Looks up [prompt] and reports the full outcome, including why a miss was a miss.
     *
     * Costs one [Embedder.embed] call. Use this over [get] when you want to log or act on the
     * reason — a cache whose hit rate is 4% is untunable unless you know whether prompts are
     * landing below the threshold or being vetoed by a guard.
     */
    public suspend fun lookup(
        prompt: String,
        scope: String = DEFAULT_SCOPE,
        context: List<String> = emptyList(),
    ): CacheLookup {
        val key = keyed(prompt, context)
        val recalled = exactHit(key, scope, counted = true)
        if (recalled != null && recalled.fresh) {
            val entry = recalled.entry
            val age = (clock.now() - entry.createdAt)
            return CacheLookup.Hit(entry.response, entry.prompt, 1.0, entry.id, age, entry.metadata)
        }
        val embedStart = if (observed) TimeSource.Monotonic.markNow() else null
        val embedding = recalled?.embedding ?: embed(key, scope)
        val embedNanos = embedStart?.elapsedNow()?.inWholeNanoseconds ?: 0L
        // Already counted by the exact layer when it recalled a stale entry, so do not count twice.
        return lookup(key, scope, embedding, counted = recalled == null, embedNanos = embedNanos)
    }

    /** Returns the cached response for [prompt], or `null`. The short form of [lookup]. */
    public suspend fun get(
        prompt: String,
        scope: String = DEFAULT_SCOPE,
        context: List<String> = emptyList(),
    ): String? = (lookup(prompt, scope, context) as? CacheLookup.Hit)?.response

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
            val verdicts = guardChain.verdicts(prompt, scored.entry)
            CandidateTrace(
                prompt = scored.entry.prompt,
                similarity = scored.similarity,
                aboveThreshold = scored.similarity >= threshold,
                guardVerdicts = verdicts,
                // Every guard is still evaluated on a mismatched candidate, unlike in a real lookup.
                // An explanation exists to show the whole picture, and "the guards would have passed
                // this, but nothing about the score was real" is the picture.
                embedderMatches = scored.entry.embedder == embedderIdentity,
            )
        }
        return CacheExplanation(prompt, scope, thresholdFor(scope), traces)
    }

    /**
     * Caches [response] as the answer to [prompt] and returns the new entry's id.
     *
     * Costs one [Embedder.embed] call. Prefer [getOrPut], which embeds once for the lookup and the
     * write together.
     *
     * The returned id is the one **assigned** to the entry. If a [CachePolicy] vetoes the write, no
     * entry is stored and the id names nothing — the veto is reported as [CacheEvent.WriteVetoed] and
     * counted in [CacheStats.writesVetoed], which is where a caller that needs to know should look.
     * The return type is kept as-is rather than made nullable because narrowing it would break every
     * existing caller for a case that only arises once a policy is configured.
     */
    public suspend fun put(
        prompt: String,
        response: String,
        scope: String = DEFAULT_SCOPE,
        metadata: Map<String, String> = emptyMap(),
        tags: Set<String> = emptySet(),
    ): String = put(prompt, response, scope, metadata, embed(prompt, scope), tags)

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
     * Pass [context] for a turn in a conversation. Without it the cache keys on the last turn alone, so
     * "what about the second one?" can be served an answer computed after a completely different
     * exchange — the context is not weighed, it is *ignored*. With it, the prior turns are folded into
     * what gets embedded and keyed, so a different conversation is a different question. [compute]
     * still receives the bare [prompt]: the context is the cache's business, not the model's.
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
    ): String = getOrPut(prompt, emptyList(), emptySet(), scope, metadata, compute)

    /**
     * The conversation-aware [getOrPut]: keys the turn on [context] as well as [prompt].
     *
     * A separate overload rather than a parameter on the one above, because inserting anything ahead of
     * a trailing lambda rebinds it for every caller who passes `scope` or `metadata` positionally. That
     * is a source break, and `1.x` does not take those.
     */
    public suspend fun getOrPut(
        prompt: String,
        context: List<String>,
        tags: Set<String> = emptySet(),
        scope: String = DEFAULT_SCOPE,
        metadata: Map<String, String> = emptyMap(),
        compute: suspend (String) -> String,
    ): String {
        val key = keyed(prompt, context)
        if (shadowThresholds.isNotEmpty()) {
            return shadowCompute(key, scope, metadata, tags) { compute(prompt) }
        }

        val recalled = exactHit(key, scope, counted = true)
        if (recalled != null && recalled.fresh) return recalled.entry.response

        val embedStart = if (observed) TimeSource.Monotonic.markNow() else null
        val embedding = try {
            recalled?.embedding ?: embed(key, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The one call the cache makes on every lookup just failed. PROPAGATE surfaces it;
            // FALL_BACK_TO_COMPUTE degrades to an uncached model call so the caller is never worse off
            // than with no cache. The answer cannot be written back — there is no embedding to key it —
            // so nothing is cached until the embedder recovers.
            if (embedFailurePolicy == EmbedFailurePolicy.FALL_BACK_TO_COMPUTE) {
                degraded(scope, listOf(key), DegradedOperation.GET_OR_PUT, e)
                return compute(prompt)
            }
            throw e
        }
        val embedNanos = embedStart?.elapsedNow()?.inWholeNanoseconds ?: 0L
        val result = lookup(key, scope, embedding, embedNanos = embedNanos)
        if (result is CacheLookup.Hit) return result.response
        if (!coalesceConcurrentMisses) {
            return computeAndPut(key, scope, metadata, embedding, tags) { compute(prompt) }
        }

        return inFlight.withKeyLock("$scope\u0000$key") {
            // Whoever held the lock before us may have just answered this exact question. Looking
            // again costs a store read; not looking costs a model call.
            // Not counted: this is the same caller's single lookup, resumed. Counting it again
            // reported more misses than there were calls and halved the hit rate of the very
            // workload coalescing exists to improve.
            val second = lookup(key, scope, embedding, counted = false)
            if (second is CacheLookup.Hit) {
                second.response
            } else {
                computeAndPut(key, scope, metadata, embedding, tags) { compute(prompt) }
            }
        }
    }

    /**
     * The batch form of [getOrPut]: looks up many prompts at once, embedding them in a **single**
     * [Embedder.embedAll] call.
     *
     * Most embedding providers price and rate-limit per request, not per token, so embedding fifty
     * prompts in one call is far cheaper and faster than fifty calls. Each prompt is then looked up
     * with its vector, and every miss is computed with [compute] and cached, exactly as [getOrPut]
     * does; the returned responses line up with [prompts].
     *
     * This is a batch primitive, so it trades a little of [getOrPut]'s cleverness for the batch win:
     * concurrent-miss coalescing and the negative cache (both keyed on a single prompt) are skipped,
     * and misses are computed one after another in order — wrap [compute] yourself if you want the
     * model calls to run concurrently. Writes still honour write-behind when it is on.
     * [embedFailurePolicy] applies to the batch embed the same way it does to a single one.
     *
     * @return the answer for each prompt, in the order of [prompts]. Empty for an empty input.
     */
    public suspend fun getOrPutAll(
        prompts: List<String>,
        scope: String = DEFAULT_SCOPE,
        metadata: Map<String, String> = emptyMap(),
        compute: suspend (String) -> String,
    ): List<String> {
        if (prompts.isEmpty()) return emptyList()

        val embeddings = try {
            embedAllNormalized(prompts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (embedFailurePolicy == EmbedFailurePolicy.FALL_BACK_TO_COMPUTE) {
                // One event for the batch, not one per prompt: a single embedAll call failed once.
                degraded(scope, prompts, DegradedOperation.GET_OR_PUT_ALL, e)
                return prompts.map { compute(it) }
            }
            throw e
        }

        val responses = ArrayList<String>(prompts.size)
        for (i in prompts.indices) {
            val prompt = prompts[i]
            val embedding = embeddings[i]
            val result = lookup(prompt, scope, embedding)
            responses += if (result is CacheLookup.Hit) {
                result.response
            } else {
                computeAndPut(prompt, scope, metadata, embedding, compute = compute)
            }
        }
        return responses
    }

    /**
     * A [getOrPut] that caches a **structured** response of type [T], not just its text.
     *
     * The cache still stores a `String` — [codec] is what turns your object into one and back — so
     * caching a parsed JSON object, an extracted record, or a tool-call is the same one-embedding
     * round trip as caching text. On a hit the stored text is decoded; on a miss [compute] runs, its
     * result is encoded and cached, and the same value is returned. See [ResponseCodec] for the
     * round-trip contract.
     *
     * ```kotlin
     * val weather: Weather = cache.getOrPut(prompt, weatherCodec) { llm.extractWeather(it) }
     * ```
     */
    public suspend fun <T> getOrPut(
        prompt: String,
        codec: ResponseCodec<T>,
        scope: String = DEFAULT_SCOPE,
        metadata: Map<String, String> = emptyMap(),
        compute: suspend (String) -> T,
    ): T {
        val text = getOrPut(prompt, scope, metadata) { p -> codec.encode(compute(p)) }
        return codec.decode(text)
    }

    /**
     * A [getOrPut] for **streaming** completions: forwards a streamed answer to the caller as it
     * arrives, keeps it, and replays it chunk for chunk on a later hit.
     *
     * Every chat interface in production streams, because a first token at 300 ms is the difference
     * between a product that feels alive and one that does not. Without this a streaming team either
     * cannot put a cache on that path at all, or has to buffer the whole response before showing any
     * of it — which spends the latency they were streaming for and makes every miss slower than no
     * cache.
     *
     * **Two rules make it safe rather than merely useful, and neither is configurable.**
     *
     * The lookup, the guards and the verifier all run **before the first token is served**, never
     * during. A token already handed to the caller cannot be taken back, so a guard that fired halfway
     * through would have served a wrong answer to somebody who is already reading it. Everything that
     * decides *whether* to serve happens while this function suspends; collection only starts once the
     * decision is made.
     *
     * And a stream that fails partway is **not written**. The entry is stored only when the upstream
     * flow completes normally, so a truncated answer never becomes a cache entry — which would be
     * worse than no entry at all, because it would be served confidently to everyone who asked that
     * question afterwards and would look exactly like a complete one. Cancellation counts as failure
     * here for the same reason.
     *
     * **On a hit** the answer is replayed according to [replay], which defaults to the original chunk
     * boundaries with no delay. See [StreamReplay], including why no option reproduces the original
     * timing. An entry written by any other path has no boundaries and replays whole.
     *
     * **On a miss** the flow from [compute] is passed straight through, chunk by chunk, while being
     * accumulated. Empty chunks are forwarded but not recorded: they carry nothing to replay.
     *
     * The returned flow is cold: nothing is computed or streamed until it is collected, though the
     * embedding and the lookup are already done. Concurrent-miss coalescing and write-behind do not
     * apply to this path. If the embedder throws under [EmbedFailurePolicy.FALL_BACK_TO_COMPUTE], the
     * raw stream from [compute] is returned uncached.
     *
     * ```kotlin
     * cache.getOrPutStreaming(prompt) { llm.completeStreaming(it) }.collect { chunk -> print(chunk) }
     * ```
     */
    public suspend fun getOrPutStreaming(
        prompt: String,
        scope: String = DEFAULT_SCOPE,
        metadata: Map<String, String> = emptyMap(),
        compute: suspend (String) -> Flow<String>,
    ): Flow<String> = getOrPutStreaming(prompt, StreamReplay.AS_STREAMED, scope, metadata, compute)

    /**
     * The [getOrPutStreaming] that chooses how a hit is replayed.
     *
     * A separate overload rather than a parameter on the one above, and [replay] carries no default,
     * for the same two reasons: inserting anything ahead of a trailing lambda rebinds it for every
     * caller passing `scope` or `metadata` positionally, which is a source break `2.x` does not take;
     * and a default here would make `getOrPutStreaming(prompt) { … }` ambiguous between the two.
     */
    public suspend fun getOrPutStreaming(
        prompt: String,
        replay: StreamReplay,
        scope: String = DEFAULT_SCOPE,
        metadata: Map<String, String> = emptyMap(),
        compute: suspend (String) -> Flow<String>,
    ): Flow<String> {
        val embedStart = if (observed) TimeSource.Monotonic.markNow() else null
        val embedding = try {
            embed(prompt, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (embedFailurePolicy == EmbedFailurePolicy.FALL_BACK_TO_COMPUTE) {
                degraded(scope, listOf(prompt), DegradedOperation.GET_OR_PUT_STREAMING, e)
                return compute(prompt)
            }
            throw e
        }
        val embedNanos = embedStart?.elapsedNow()?.inWholeNanoseconds ?: 0L

        // Before the flow is even built, let alone collected. This is the "guards run before the first
        // token" rule in one line: nothing downstream can revisit the decision made here.
        val result = lookup(prompt, scope, embedding, embedNanos = embedNanos)
        if (result is CacheLookup.Hit) return replayed(result, replay)

        return flow {
            val assembled = StringBuilder()
            val lengths = ArrayList<Int>()
            compute(prompt).collect { chunk ->
                assembled.append(chunk)
                // A zero-length chunk is a keep-alive, not content. Forwarded so the caller sees the
                // provider's stream unaltered, unrecorded so replay does not emit nothing repeatedly.
                if (chunk.isNotEmpty()) lengths += chunk.length
                emit(chunk)
            }
            // Reached only when the upstream flow completed without throwing (and was not cancelled):
            // a partial stream must never be cached as if it were the whole answer.
            put(prompt, assembled.toString(), scope, metadata, embedding, chunkLengths = lengths)
        }
    }

    /**
     * The hit path of [getOrPutStreaming]: the stored answer, cut back into the chunks it arrived in.
     *
     * Falls back to a single element whenever there is nothing to cut by, which covers every entry
     * written by [put], [getOrPut] or [warm], and every store that does not persist the boundaries.
     * That is a graceful degradation on purpose: the text is always right, and the worst case is one
     * chunk instead of many.
     */
    private fun replayed(hit: CacheLookup.Hit, replay: StreamReplay): Flow<String> {
        if (replay == StreamReplay.WHOLE || hit.chunkLengths.isEmpty()) return flowOf(hit.response)
        val chunks = ArrayList<String>(hit.chunkLengths.size)
        var at = 0
        for (length in hit.chunkLengths) {
            chunks += hit.response.substring(at, at + length)
            at += length
        }
        return chunks.asFlow()
    }


    /**
     * The shadow-mode [getOrPut]: embed, search, judge every threshold, emit the report, then **always**
     * compute and write.
     *
     * Writing still happens because a shadow cache that never fills would report a miss for everything
     * and measure nothing. Serving never happens, which is the entire point: the team gets its own curve
     * with no risk of a false hit reaching a user while they are still deciding on a threshold.
     */
    private suspend fun shadowCompute(
        prompt: String,
        scope: String,
        metadata: Map<String, String>,
        tags: Set<String> = emptySet(),
        compute: suspend (String) -> String,
    ): String {
        val embedding = embed(prompt, scope)
        if (observed) emit(CacheEvent.Shadow(shadow(prompt, scope, embedding)))
        val response = compute(prompt)
        enqueueOrPut(buildEntry(prompt, response, scope, metadata, embedding, tags))
        return response
    }

    private suspend fun computeAndPut(
        prompt: String,
        scope: String,
        metadata: Map<String, String>,
        embedding: FloatArray,
        tags: Set<String> = emptySet(),
        compute: suspend (String) -> String,
    ): String {
        val response = compute(prompt)
        enqueueOrPut(buildEntry(prompt, response, scope, metadata, embedding, tags))
        return response
    }

    /**
     * Removes the entry [id], typically one reported by a [CacheLookup.Hit] that proved wrong.
     *
     * Also drops it from the exact-match layer, which would otherwise keep answering with the very
     * response the caller has just retracted.
     */
    public suspend fun invalidate(id: String): Boolean {
        exactCache?.removeEntry(id)
        return store.remove(id)
    }

    /** Removes every entry in [scope], or the whole cache when [scope] is `null`. */
    /**
     * Drops every entry carrying [tag], optionally narrowed to [scope], and returns how many went.
     *
     * A TTL is a guess about when knowledge might go stale; this is how you act on knowing that it just
     * did. Tag entries by the fact they depend on — a price list, a policy version — and drop exactly
     * those when it changes, instead of clearing a whole scope and paying to refill it.
     *
     * The exact-match layer is cleared wholesale rather than by tag: it holds no tag index, and dropping
     * everything is the safe direction. Its entries cost one lookup each to rebuild; keeping one that a
     * caller has just invalidated would serve a wrong answer.
     *
     * @throws UnsupportedOperationException if the configured [CacheStore] does not index tags.
     */
    public suspend fun invalidateByTag(tag: String, scope: String? = null): Int {
        val removed = store.invalidateByTag(tag, scope)
        if (removed > 0) exactCache?.clear(scope)
        return removed
    }

    public suspend fun clear(scope: String? = null) {
        exactCache?.clear(scope)
        store.clear(scope)
    }

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
        val hits = hitCount.load()
        val lookups = maxOf(lookupCount.load(), hits)
        return CacheStats(
            lookups = lookups,
            hits = hits,
            misses = lookups - hits,
            belowThreshold = belowThresholdCount.load(),
            guardRejections = guardRejectionCount.load(),
            verifierRejections = verifierRejectionCount.load(),
            writes = writeCount.load(),
            guardRejectionsByGuard = guardRejectionCountersByName.mapValues { it.value.load() },
            degradedLookups = degradedCount.load(),
            writesVetoed = writeVetoCount.load(),
            exactHits = exactHitCount.load(),
            embedderMismatches = embedderMismatchCount.load(),
        )
    }

    /**
     * Records that the embedder failed and the cache stepped aside, then hands the failure back so the
     * caller can run [compute] uncached.
     *
     * The counter moves whether or not anyone is listening — [CacheStats] is the floor of observability
     * and must answer "how often did the cache step aside" on its own. Only the event construction is
     * gated on [observed].
     */

    /**
     * The exact-match layer's answer for ([scope], [prompt]), or `null` when it has nothing.
     *
     * A fresh recall is served as a hit with similarity `1.0` and no timings, which is the honest
     * report: nothing was embedded and nothing was searched. A stale one is not served, it only hands
     * its embedding back so the caller can skip the network call and go through the ordinary path.
     */
    private suspend fun exactHit(prompt: String, scope: String, counted: Boolean): ExactCache.Recalled? {
        val recalled = exactCache?.get(scope, prompt) ?: return null
        if (!recalled.fresh) return recalled
        if (counted) {
            lookupCount.addAndFetch(1)
            hitCount.addAndFetch(1)
            exactHitCount.addAndFetch(1)
        }
        store.touch(recalled.entry.id)
        if (observed) {
            emit(
                CacheEvent.Hit(
                    scope, prompt, recalled.entry.prompt, 1.0, recalled.entry.id, EventTimings.NONE,
                ),
            )
        }
        return recalled
    }

    private suspend fun rememberExact(scope: String, prompt: String, entry: CacheEntry, embedding: FloatArray) {
        exactCache?.put(scope, prompt, entry, embedding)
    }


    private suspend fun shadow(prompt: String, scope: String, embedding: FloatArray): ShadowReport =
        shadowRun.report(prompt, scope, store.search(scope, embedding, candidates))


    /**
     * The text the cache actually keys and embeds for a turn in a conversation.
     *
     * With no [context] this is the prompt itself, so every existing caller is unaffected. With one, the
     * prior turns are folded in, which is what stops "what about the second one?" being answered from a
     * completely different exchange that happened to phrase its last turn the same way. Context is part
     * of the question, and a cache that keys only the last turn is silently answering a question nobody
     * asked.
     *
     * Folded into the *embedded text* rather than into the scope on purpose: two conversations that
     * differ only in phrasing should still be able to match, and the guards still read the whole thing.
     * Partitioning by scope would make only byte-identical histories comparable and throw the hit rate
     * away.
     */
    private fun keyed(prompt: String, context: List<String>): String =
        if (context.isEmpty()) prompt else context.joinToString("\n") + "\n" + prompt

    /**
     * The threshold in force for [scope]: what the traffic has justified, else the per-scope override,
     * else the global value.
     *
     * An adaptive recommendation outranks a configured per-scope value on purpose. Configuring both is
     * saying "start here, then learn"; the configured number is the starting point and the measurement
     * is what replaces it. It cannot leave the floor and ceiling the caller set on
     * [AdaptiveThresholds].
     */
    private fun thresholdFor(scope: String): Double =
        adaptiveThresholds?.recommendationFor(scope) ?: thresholds[scope] ?: threshold

    private fun degraded(
        scope: String,
        prompts: List<String>,
        operation: DegradedOperation,
        cause: Exception,
    ) {
        degradedCount.addAndFetch(1)
        if (observed) emit(CacheEvent.Degraded(scope, prompts, operation, embedFailurePolicy, cause))
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

    /**
     * Batch-embeds [prompts] in one [Embedder.embedAll] call and unit-normalizes each. Bypasses the
     * negative cache — the batch is the deduplication mechanism here — and insists the provider return
     * one vector per input, in order, since the whole batch path relies on that alignment.
     */
    private suspend fun embedAllNormalized(prompts: List<String>): List<FloatArray> {
        val raw = embedder.embedAll(prompts)
        require(raw.size == prompts.size) {
            "embedAll returned ${raw.size} vectors for ${prompts.size} prompts; an Embedder must " +
                "return one vector per input, in order"
        }
        return raw.map { Vectors.normalize(it) }
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
        if (counted) lookupCount.addAndFetch(1)

        // Timings are only ever measured on the observed, counted path; nanoTime is cheap but not free,
        // and the coalescing re-check must not double-count a stage it did not run.
        val measure = observed && counted
        val searchStart = if (measure) TimeSource.Monotonic.markNow() else null
        val found = store.search(scope, embedding, candidates)
        val searchNanos = searchStart?.elapsedNow()?.inWholeNanoseconds ?: 0L
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

        // One report per lookup, not one per candidate: after a model swap every candidate in the scope
        // mismatches, and five identical events per lookup would bury the one fact they all carry.
        var mismatchSeen = false

        for (scored in candidateOrder.considered(embedding, found, thresholdFor(scope))) {
            // Ahead of the guards, and ahead of anything treating this similarity as information. Two
            // embedding models do not share a vector space, so a score computed across them is not a
            // weak signal, it is not a signal — and a guard verdict on such a pair would be a real
            // answer to a question nobody asked. The loop continues rather than returning, so a store
            // part-way through a re-embedding still serves the entries that have been rewritten.
            if (scored.entry.embedder != embedderIdentity) {
                if (!mismatchSeen) {
                    mismatchSeen = true
                    if (counted) embedderMismatchCount.addAndFetch(1)
                    if (observed) {
                        emit(
                            CacheEvent.EmbedderMismatch(
                                scope, prompt, embedderIdentity, scored.entry.embedder, scored.entry.id,
                            ),
                        )
                    }
                }
                if (refusal == null) {
                    refusal = Refusal(
                        MissReason.EMBEDDER_MISMATCH,
                        scored,
                        "entry was written by embedder '${scored.entry.embedder}', " +
                            "this cache runs '$embedderIdentity'",
                        guardName = null,
                    )
                }
                continue
            }

            val rejection = guardChain.firstRejection(prompt, scored.entry)
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
            val verifierStart = if (timeVerifier) TimeSource.Monotonic.markNow() else null
            val verifierDetail = verifierRejects(prompt, scored.entry.prompt, scored.similarity)
            verifierStart?.let { verifierNanos += it.elapsedNow().inWholeNanoseconds }
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
            if (counted) belowThresholdCount.addAndFetch(1)
            rememberMiss(scope, prompt, embedding, counted)
            return miss(MissReason.BELOW_THRESHOLD, best, "best similarity ${best.similarity} < $threshold")
                .also { emitMiss(scope, prompt, it, embedNanos, searchNanos, verifierNanos, counted) }
        }

        if (counted) {
            when (refusal.reason) {
                MissReason.REJECTED_BY_GUARD -> {
                    guardRejectionCount.addAndFetch(1)
                    refusal.guardName?.let { guardRejectionCountersByName[it]?.addAndFetch(1) }
                }
                // Already counted the moment it was met, and counted per lookup rather than per
                // reported reason — a lookup that refused a stale entry and then served the next one
                // still met one.
                MissReason.EMBEDDER_MISMATCH -> Unit
                else -> verifierRejectionCount.addAndFetch(1)
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

    /**
     * The live entry [entry] makes redundant, or `null` when there is none.
     *
     * Two conditions, and the second is the one that makes this safe. Similarity alone decides which
     * entries are *candidates* for merging, exactly as it decides which are candidates for serving —
     * and it is just as wrong on its own here. `Convert 100 USD to EUR` and `Convert 250 USD to EUR`
     * embed at 0.99, so a similarity-only rule would delete one answer and leave the other to be served
     * for both questions: the false hit this library exists to prevent, arriving through the write path
     * instead of the read path. So the guards decide, the same guards, in both directions, and only a
     * pair they would have served for each other is merged.
     *
     * The new entry wins because it is the fresher answer to the same question.
     */
    private suspend fun nearDuplicateOf(entry: CacheEntry): CacheEntry? {
        val minimum = deduplicateWrites ?: return null
        return store.search(entry.scope, entry.embedding, candidates)
            .firstOrNull { scored ->
                scored.entry.id != entry.id &&
                    // Same reason the read path checks it: across two embedders this similarity is not
                    // a number about the prompts. Deduplicating on it would delete a live answer on the
                    // strength of a coincidence.
                    scored.entry.embedder == entry.embedder &&
                    scored.similarity >= minimum &&
                    guardChain.firstRejection(entry.prompt, scored.entry) == null &&
                    guardChain.firstRejection(scored.entry.prompt, entry) == null
            }
            ?.entry
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
        if (counted) hitCount.addAndFetch(1)
        val age = (clock.now() - scored.entry.createdAt)
        return CacheLookup.Hit(
            response = scored.entry.response,
            matchedPrompt = scored.entry.prompt,
            similarity = scored.similarity,
            entryId = scored.entry.id,
            age = age,
            metadata = scored.entry.metadata,
            chunkLengths = scored.entry.chunkLengths,
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
        tags: Set<String> = emptySet(),
        chunkLengths: List<Int> = emptyList(),
    ): String {
        val entry = buildEntry(prompt, response, scope, metadata, embedding, tags, chunkLengths)
        putEntry(entry)
        return entry.id
    }

    private fun buildEntry(
        prompt: String,
        response: String,
        scope: String,
        metadata: Map<String, String>,
        embedding: FloatArray,
        tags: Set<String> = emptySet(),
        chunkLengths: List<Int> = emptyList(),
    ): CacheEntry = CacheEntry(
        id = Ids.next(),
        scope = scope,
        prompt = prompt,
        response = response,
        embedding = embedding,
        createdAt = clock.now(),
        metadata = metadata,
        tags = tags,
        embedder = embedderIdentity,
        chunkLengths = chunkLengths,
    )

    /**
     * The actual store write shared by every write path: put, getOrPut, warm, and the write-behind
     * worker.
     *
     * [cachePolicy] is consulted here rather than at each call site precisely because this is the one
     * choke point every write goes through. A policy that can be routed around by one entry point is
     * not something an adopter can put in front of an auditor.
     */
    private suspend fun putEntry(entry: CacheEntry) {
        val verdict = cachePolicy?.evaluate(entry.prompt, entry.response, entry.scope)
        if (verdict is PolicyVerdict.Veto) {
            writeVetoCount.addAndFetch(1)
            if (observed) emit(CacheEvent.WriteVetoed(entry.scope, entry.prompt, verdict.reason))
            return
        }
        val replaced = nearDuplicateOf(entry)
        store.put(entry)
        // Removed after the write, not before: a crash between the two would otherwise lose an answer
        // the cache already had, and the worst case of this order is one duplicate that lives on.
        if (replaced != null && store.remove(replaced.id) && observed) {
            emit(CacheEvent.Eviction(replaced.scope, replaced.prompt, replaced.id, EvictionCause.NEAR_DUPLICATE))
        }
        writeCount.addAndFetch(1)
        // The prompt now resolves to this entry, so the exact layer can answer a repeat of it.
        rememberExact(entry.scope, entry.prompt, entry, entry.embedding)
        // The prompt is now answerable from the store, so its "recently missed" note is stale — drop it
        // so a later lookup embeds fresh rather than reusing a vector for a miss that no longer holds.
        negativeCache?.remove(entry.scope, entry.prompt)
        if (observed) emit(CacheEvent.Write(entry.scope, entry.prompt, entry.id))
    }

    /**
     * Routes a write through the write-behind queue when it is on, or straight to the store when it is
     * off. If the queue is full the write falls through synchronously rather than being dropped or
     * blocking the caller indefinitely, so no write is ever lost.
     */
    private suspend fun enqueueOrPut(entry: CacheEntry) {
        val channel = writeChannel ?: run { putEntry(entry); return }
        if (channel.trySend(entry).isFailure) putEntry(entry)
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
        val embeddings = embedAllNormalized(entries.map { it.prompt })
        val now = clock.now()
        val ids = ArrayList<String>(entries.size)
        for (i in entries.indices) {
            val warm = entries[i]
            val entry = CacheEntry(
                id = Ids.next(),
                scope = warm.scope,
                prompt = warm.prompt,
                response = warm.response,
                embedding = embeddings[i],
                createdAt = now,
                metadata = warm.metadata,
                embedder = embedderIdentity,
            )
            // Warming writes through synchronously even under write-behind: a preloaded cache you are
            // about to serve from should be durable before warm() returns, not eventually.
            putEntry(entry)
            ids += entry.id
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

        /** Pending write-behind writes buffered before a write falls through synchronously. */
        public const val DEFAULT_WRITE_BEHIND_CAPACITY: Int = 1_024
    }
}
