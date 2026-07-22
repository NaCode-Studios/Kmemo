package dev.kmemo.guard

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariants of the shared [Text] tokenizer the guards read through. If tokenization stopped being
 * lowercase or started leaking punctuation, every guard that compares tokens would misfire in ways
 * no single example test would catch.
 */
class TextPropertyTest {

    @Test
    fun `every token is lowercase and alphanumeric`() = runTest {
        checkAll(Arb.string()) { text ->
            for (token in Text.tokens(text)) {
                assertEquals(token.lowercase(), token, "token '$token' was not lowercase")
                assertTrue(token.all { it.isLetterOrDigit() }, "token '$token' held a non-alphanumeric char")
                assertTrue(token.isNotEmpty(), "an empty token slipped through")
            }
        }
    }

    @Test
    fun `content tokens are a duplicate-free subset of all tokens`() = runTest {
        checkAll(Arb.string()) { text ->
            val all = Text.tokens(text)
            val content = Text.contentTokens(text, stopwords = emptySet())
            assertTrue(content.all { it in all }, "a content token was not among the tokens")
            assertEquals(content.size, content.toSet().size, "content tokens contained a duplicate")
        }
    }

    @Test
    fun `isSameWord is reflexive and symmetric`() = runTest {
        checkAll(Arb.string(), Arb.string()) { first, second ->
            val a = Text.tokens(first).firstOrNull() ?: "word"
            val b = Text.tokens(second).firstOrNull() ?: "other"
            assertTrue(Text.isSameWord(a, a), "isSameWord must be reflexive for '$a'")
            assertEquals(
                Text.isSameWord(a, b),
                Text.isSameWord(b, a),
                "isSameWord must be symmetric for '$a' and '$b'",
            )
        }
    }

    @Test
    fun `withinOneTypo is reflexive and symmetric`() = runTest {
        checkAll(Arb.string(), Arb.string()) { first, second ->
            val a = Text.tokens(first).firstOrNull() ?: "word"
            val b = Text.tokens(second).firstOrNull() ?: "other"
            assertTrue(Text.withinOneTypo(a, a), "withinOneTypo must be reflexive for '$a'")
            assertEquals(
                Text.withinOneTypo(a, b),
                Text.withinOneTypo(b, a),
                "withinOneTypo must be symmetric for '$a' and '$b'",
            )
        }
    }
}
