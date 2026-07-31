package dev.kmemo

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block] and captures its outcome as a [Result], the coroutine-safe way.
 *
 * Kotlin's own [runCatching] catches [CancellationException] too, which quietly breaks structured
 * concurrency: a cancelled coroutine looks like a failed one, and the cancellation never propagates.
 * This is the same convenience without that bug — a [CancellationException] is always re-thrown, so
 * cancellation still works, and so are `Error`s, which are not yours to swallow.
 *
 * The exception / `null` style stays the primary one across kmemo (`get` returns `null`, `getOrPut`
 * throws); reach for this only when you specifically want a `Result` at a call site:
 *
 * ```kotlin
 * val answer: Result<String> = catching { cache.getOrPut(prompt) { llm.complete(it) } }
 * answer.getOrElse { "sorry, something went wrong" }
 * ```
 *
 * @return [Result.success] with [block]'s value, or [Result.failure] with any [Exception] it threw
 *   (except [CancellationException], which propagates).
 */
public inline fun <T> catching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
