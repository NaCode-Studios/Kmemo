package dev.kmemo.slf4j

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kmemo.Embedder
import dev.kmemo.SemanticCache
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Slf4jCacheListenerTest {

    private val embedder = Embedder { text ->
        val vector = FloatArray(64)
        val tokens = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) vector[0] = 1.0f
        for (token in tokens) vector[Math.floorMod(token.hashCode(), 64)] += 1.0f
        vector
    }

    @Test
    fun `a hit is logged with structured fields and the prompt redacted by default`() = runTest {
        val (logger, appender) = capturing("test.hit")
        val cache = SemanticCache(embedder, listeners = listOf(Slf4jCacheListener(logger = logger)))
        cache.put("How do I reverse a list?", "use reversed()")

        cache.lookup("How do I reverse a list?")

        val hit = appender.list.single { it.kv("event") == "hit" }
        assertEquals(SemanticCache.DEFAULT_SCOPE, hit.kv("scope"))
        assertNotNull(hit.kv("similarity"))
        // Redaction on by default: the length is logged, the text is not.
        assertNotNull(hit.kv("promptChars"))
        assertNull(hit.kv("prompt"), "prompt text must be withheld when redaction is on")
    }

    @Test
    fun `prompt text is logged when redaction is turned off`() = runTest {
        val (logger, appender) = capturing("test.noredact")
        val listener = Slf4jCacheListener(logger = logger, redactPrompts = false)
        val cache = SemanticCache(embedder, listeners = listOf(listener))
        cache.put("How do I reverse a list?", "use reversed()")

        cache.lookup("How do I reverse a list?")

        val hit = appender.list.single { it.kv("event") == "hit" }
        assertEquals("How do I reverse a list?", hit.kv("prompt"))
    }

    @Test
    fun `a guard rejection logs the reason and the guard`() = runTest {
        val (logger, appender) = capturing("test.guard")
        val cache = SemanticCache(
            embedder,
            threshold = 0.5,
            listeners = listOf(Slf4jCacheListener(logger = logger)),
        )
        cache.put("Convert 100 USD to EUR", "about 92 EUR")

        cache.lookup("Convert 250 USD to EUR")

        val miss = appender.list.single { it.kv("event") == "miss" }
        assertEquals("REJECTED_BY_GUARD", miss.kv("reason"))
        assertEquals("numeric", miss.kv("guard"))
    }

    @Test
    fun `nothing is logged when the level is disabled`() = runTest {
        val (logger, appender) = capturing("test.disabled", enabled = LogbackLevel.INFO)
        val cache = SemanticCache(
            embedder,
            listeners = listOf(Slf4jCacheListener(logger = logger, level = Level.DEBUG)),
        )
        cache.put("How do I reverse a list?", "use reversed()")

        cache.lookup("How do I reverse a list?")

        assertTrue(appender.list.isEmpty(), "DEBUG events must not be built when only INFO is enabled")
    }

    @Test
    fun `a correlation id is attached when supplied`() = runTest {
        val (logger, appender) = capturing("test.correlation")
        val listener = Slf4jCacheListener(logger = logger, correlationId = { "req-42" })
        val cache = SemanticCache(embedder, listeners = listOf(listener))
        cache.put("How do I reverse a list?", "use reversed()")

        cache.lookup("How do I reverse a list?")

        assertEquals("req-42", appender.list.first().kv("correlationId"))
    }

    private fun ILoggingEvent.kv(key: String): String? =
        keyValuePairs?.firstOrNull { it.key == key }?.value?.toString()

    private fun capturing(
        name: String,
        enabled: LogbackLevel = LogbackLevel.TRACE,
    ): Pair<org.slf4j.Logger, ListAppender<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(name) as LogbackLogger
        logger.level = enabled
        logger.isAdditive = false // keep events off the root console appender
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.detachAndStopAllAppenders()
        logger.addAppender(appender)
        return logger to appender
    }
}
