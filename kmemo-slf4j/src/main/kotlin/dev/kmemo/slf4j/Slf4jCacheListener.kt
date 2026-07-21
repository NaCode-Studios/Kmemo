package dev.kmemo.slf4j

import dev.kmemo.CacheEvent
import dev.kmemo.CacheListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * A [CacheListener] that writes one structured SLF4J line per cache event.
 *
 * ```kotlin
 * val cache = SemanticCache(embedder, listeners = listOf(Slf4jCacheListener()))
 * ```
 *
 * Each event becomes a log record with key/value fields — `scope`, `similarity`, `reason`, `guard`,
 * the stage latencies — so a structured backend (Logback with a JSON encoder, ELK, Loki) can index
 * them and a plain console still prints them readably. Everything logs at a single configurable
 * [level] (default `DEBUG`, since hits and misses are per-request and noisy), and the whole method
 * short-circuits when that level is disabled, so a listener left on in production costs almost nothing.
 *
 * ### Prompt redaction is on by default
 *
 * Prompts are user input and routinely carry PII, so by default the prompt **text is never logged** —
 * only its length, as `promptChars`. That keeps the line useful for spotting a hot-scope or a
 * latency spike without turning your log store into a copy of every question your users asked. Set
 * [redactPrompts] to `false` in a development or trusted environment to log the (length-capped) text
 * under the `prompt` field.
 *
 * ### Correlation
 *
 * Pass [correlationId] to stamp each line with a request/trace id — for example `{ MDC.get("traceId") }`
 * to pull whatever your web framework already put in the SLF4J MDC. It is read once per event and, when
 * non-null, added as a `correlationId` field. (MDC values your logging config already renders are not
 * duplicated here.)
 *
 * Thread-safe as long as the [logger] is, which every SLF4J logger is.
 *
 * @param logger the SLF4J logger to write to; defaults to one named `dev.kmemo.cache`.
 * @param level the level every event is logged at.
 * @param redactPrompts whether to withhold prompt text (on by default); `false` logs it, length-capped.
 * @param maxPromptChars when prompts are not redacted, the longest prefix logged before truncation.
 * @param correlationId supplies a per-event correlation id, or `null` for none.
 */
public class Slf4jCacheListener @JvmOverloads constructor(
    private val logger: Logger = LoggerFactory.getLogger("dev.kmemo.cache"),
    private val level: Level = Level.DEBUG,
    private val redactPrompts: Boolean = true,
    private val maxPromptChars: Int = DEFAULT_MAX_PROMPT_CHARS,
    private val correlationId: () -> String? = { null },
) : CacheListener {

    init {
        require(maxPromptChars > 0) { "maxPromptChars must be positive, was $maxPromptChars" }
    }

    override fun onEvent(event: CacheEvent) {
        // One check, then nothing is built if the level is off — the cheap-when-idle guarantee.
        if (!logger.isEnabledForLevel(level)) return

        val builder = logger.atLevel(level)
            .addKeyValue("scope", event.scope)
        when (event) {
            is CacheEvent.Hit -> builder
                .addKeyValue("event", "hit")
                .addKeyValue("similarity", event.similarity)
                .addKeyValue("entryId", event.entryId)
                .addKeyValue("embedMs", millis(event.timings.embedNanos))
                .addKeyValue("searchMs", millis(event.timings.searchNanos))
                .addKeyValue("verifyMs", millis(event.timings.verifierNanos))
                .promptOf(event.prompt)

            is CacheEvent.Miss -> builder
                .addKeyValue("event", "miss")
                .addKeyValue("reason", event.reason)
                .addKeyValue("bestSimilarity", event.bestSimilarity)
                .apply { event.guardName?.let { addKeyValue("guard", it) } }
                .addKeyValue("embedMs", millis(event.timings.embedNanos))
                .addKeyValue("searchMs", millis(event.timings.searchNanos))
                .addKeyValue("verifyMs", millis(event.timings.verifierNanos))
                .promptOf(event.prompt)

            is CacheEvent.Write -> builder
                .addKeyValue("event", "write")
                .addKeyValue("entryId", event.entryId)
                .promptOf(event.prompt)

            is CacheEvent.Eviction -> builder
                .addKeyValue("event", "eviction")
                .addKeyValue("cause", event.cause)
                .promptOf(event.prompt)
        }
        correlationId()?.let { builder.addKeyValue("correlationId", it) }
        builder.log(MESSAGE)
    }

    /** Adds the prompt as text or as a length, per [redactPrompts]. Returns the builder for chaining. */
    private fun org.slf4j.spi.LoggingEventBuilder.promptOf(
        prompt: String,
    ): org.slf4j.spi.LoggingEventBuilder =
        if (redactPrompts) {
            addKeyValue("promptChars", prompt.length)
        } else {
            addKeyValue(
                "prompt",
                if (prompt.length > maxPromptChars) prompt.take(maxPromptChars) + "…" else prompt,
            )
        }

    private fun millis(nanos: Long): Long = nanos / NANOS_PER_MILLI

    public companion object {
        /** Default cap on logged prompt length when redaction is off. */
        public const val DEFAULT_MAX_PROMPT_CHARS: Int = 200

        private const val MESSAGE = "kmemo cache event"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
