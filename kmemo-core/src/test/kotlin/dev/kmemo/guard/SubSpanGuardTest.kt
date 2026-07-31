package dev.kmemo.guard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard for the one near miss that is an addition rather than a substitution.
 *
 * Every other guard looks for a word that changed. Here nothing changed: one prompt is the other with a
 * clause on the end, and that clause is what decides the answer. Overlap is perfect, so
 * [LexicalDivergenceGuard] sees nothing, and the two embed close for the same reason.
 *
 * Each abstention below is a paraphrase an earlier, looser version of this guard refused.
 */
class SubSpanGuardTest {

    private val guard = SubSpanGuard()

    @Test
    fun `a trailing qualifier the other prompt does not have is a rejection`() {
        val verdict = guard.evaluate(
            "How do I deploy a Rails app?",
            "How do I deploy a Rails app on Heroku?",
        )
        assertTrue(verdict is GuardVerdict.Reject, "expected a rejection, got $verdict")
        assertTrue("heroku" in verdict.reason.lowercase(), "the reason must name it: ${verdict.reason}")
    }

    @Test
    fun `it fires whichever prompt is the longer one`() {
        assertTrue(
            guard.evaluate(
                "How do I set up continuous integration without Docker?",
                "How do I set up continuous integration?",
            ) is GuardVerdict.Reject,
        )
    }

    @Test
    fun `two prompts that were merely reworded are left alone`() {
        assertEquals(
            GuardVerdict.Accept,
            guard.evaluate(
                "How do I stop my SSH session from timing out?",
                "My connection to the server keeps dropping after a few minutes of sitting idle, " +
                    "what setting fixes that?",
            ),
        )
    }

    /** Framing is spread across a sentence; a condition is local. */
    @Test
    fun `an explanation of why someone is asking is not a qualifier`() {
        assertEquals(
            GuardVerdict.Accept,
            guard.evaluate(
                "How do I parse a CSV file in Python?",
                "I am writing a small report script and need to parse a CSV file in Python, how?",
            ),
        )
    }

    /** A condition is attached to the end of a question; a preamble is put in front of it. */
    @Test
    fun `a leading clause is a preamble, not a condition`() {
        assertEquals(
            GuardVerdict.Accept,
            guard.evaluate(
                "How do I compress a folder on Linux?",
                "I am on Ubuntu and I want to compress a folder on Linux, how do I do it?",
            ),
        )
    }

    @Test
    fun `a clause about the asker rather than the question is not a condition`() {
        assertEquals(
            GuardVerdict.Accept,
            guard.evaluate(
                "What is the difference between TCP and UDP?",
                "For my exam tomorrow, what is the difference between TCP and UDP?",
            ),
        )
    }

    /** A clause whose whole content is that it declines to narrow anything. */
    @Test
    fun `a hedged qualifier narrows nothing`() {
        assertEquals(
            GuardVerdict.Accept,
            guard.evaluate(
                "How do I install Postgres on macOS?",
                "How do I install Postgres on macOS, using Homebrew or otherwise?",
            ),
        )
    }

    @Test
    fun `identical prompts have no addition to find`() {
        assertEquals(
            GuardVerdict.Accept,
            guard.evaluate("How do I deploy a Rails app?", "How do I deploy a Rails app?"),
        )
    }

    /**
     * A pack that has not filled its openers disables the guard rather than guessing with another
     * language's markers. Abstaining costs an API call; guessing costs a wrong answer.
     */
    @Test
    fun `a vocabulary with no qualifier openers never fires`() {
        val silent = SubSpanGuard(qualifierOpeners = emptySet())
        assertEquals(
            GuardVerdict.Accept,
            silent.evaluate("How do I deploy a Rails app?", "How do I deploy a Rails app on Heroku?"),
        )
    }
}
