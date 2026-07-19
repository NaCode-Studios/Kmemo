package dev.kmemo.guard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Behaviour of each guard in isolation.
 *
 * [NearMissCorpusTest] measures the set as a whole; these tests pin down the individual rules, in
 * particular the ones that exist to stop a guard from over-rejecting. Those are the easy ones to
 * regress, because breaking them costs hit rate rather than correctness — nothing fails loudly.
 */
class GuardsTest {

    private fun assertRejects(guard: MatchGuard, a: String, b: String) {
        val verdict = guard.evaluate(a, b)
        assertIs<GuardVerdict.Reject>(verdict, "${guard.name} should have rejected:\n  $a\n  $b")
    }

    private fun assertAccepts(guard: MatchGuard, a: String, b: String) {
        val verdict = guard.evaluate(a, b)
        assertEquals(GuardVerdict.Accept, verdict, "${guard.name} should have accepted:\n  $a\n  $b")
    }

    // --- NumericGuard ------------------------------------------------------------------------

    @Test
    fun `numeric guard rejects a different amount`() {
        assertRejects(NumericGuard(), "Convert 100 USD to EUR", "Convert 250 USD to EUR")
    }

    @Test
    fun `numeric guard rejects a number added on one side only`() {
        assertRejects(NumericGuard(), "Explain how OAuth 2.0 works", "Explain how OAuth 2.0 works to a 5 year old")
    }

    @Test
    fun `numeric guard ignores thousands separators`() {
        assertAccepts(NumericGuard(), "What is 1,000 divided by 4?", "What is 1000 divided by 4?")
    }

    @Test
    fun `numeric guard ignores the order numbers appear in`() {
        assertAccepts(NumericGuard(), "Add 3 and 7", "Add 7 and 3")
    }

    // --- UnitGuard ---------------------------------------------------------------------------

    @Test
    fun `unit guard rejects a swapped unit`() {
        assertRejects(UnitGuard(), "Convert 50 km to miles", "Convert 50 km to meters")
    }

    @Test
    fun `unit guard rejects a swapped currency`() {
        assertRejects(UnitGuard(), "Convert 100 USD to EUR", "Convert 100 USD to GBP")
    }

    @Test
    fun `unit guard accepts a unit spelled out on one side only`() {
        assertAccepts(UnitGuard(), "375 f to c", "What is 375 degrees Fahrenheit in Celsius?")
    }

    @Test
    fun `unit guard accepts an incidental time word`() {
        assertAccepts(
            UnitGuard(),
            "How much water should an adult drink each day?",
            "What is the recommended daily water intake for adults?",
        )
    }

    // --- EntityGuard -------------------------------------------------------------------------

    @Test
    fun `entity guard rejects a swapped entity`() {
        assertRejects(EntityGuard(), "whats the capital of Australia", "whats the capital of Austria")
    }

    @Test
    fun `entity guard accepts an entity named on one side only`() {
        assertAccepts(
            EntityGuard(),
            "Can you list what each letter of SOLID means in object oriented design?",
            "What does the SOLID acronym stand for in OOP?",
        )
    }

    @Test
    fun `entity guard abstains when one prompt is all lowercase`() {
        assertAccepts(EntityGuard(), "how do i use docker", "How do I use Docker?")
    }

    @Test
    fun `entity guard does not treat the pronoun I as an entity`() {
        assertAccepts(EntityGuard(), "How do I renew my US passport?", "What is the process for renewing a US passport?")
    }

    @Test
    fun `entity guard ignores the capital that opens a sentence`() {
        assertEquals(setOf("docker"), Text.entityTokens("What is Docker?"))
    }

    // --- AntonymGuard ------------------------------------------------------------------------

    @Test
    fun `antonym guard rejects a flip`() {
        assertRejects(
            AntonymGuard(),
            "How do I enable two factor authentication on GitHub?",
            "How do I disable two factor authentication on GitHub?",
        )
    }

    @Test
    fun `antonym guard sees through an incidental repeat of the same word`() {
        // Both prompts contain "on" — the second one inside "format on save" — so testing mere
        // presence would find nothing wrong here.
        assertRejects(
            AntonymGuard(),
            "How do I turn on format on save in VS Code?",
            "How do I turn off format on save in VS Code?",
        )
    }

    @Test
    fun `antonym guard accepts a word whose opposite appears nowhere`() {
        assertAccepts(AntonymGuard(), "How do I run this before deploy?", "How do I run this prior to deploy?")
    }

    @Test
    fun `antonym guard accepts a prompt that contains both halves of a pair`() {
        assertAccepts(
            AntonymGuard(),
            "What are the pros and cons of microservices?",
            "What are the pros and cons of a microservice architecture?",
        )
    }

    // --- NegationGuard -----------------------------------------------------------------------

    @Test
    fun `negation guard rejects a one-sided negation`() {
        assertRejects(
            NegationGuard(),
            "Which foods should I eat before a long run?",
            "Which foods should I not eat before a long run?",
        )
    }

    @Test
    fun `negation guard catches contractions`() {
        assertRejects(NegationGuard(), "Why does my build work?", "Why doesn't my build work?")
    }

    @Test
    fun `negation guard accepts two differently worded negatives`() {
        assertAccepts(NegationGuard(), "How do I fix a no such file error?", "How do I fix file not found?")
    }

    // --- TemporalGuard -----------------------------------------------------------------------

    @Test
    fun `temporal guard rejects a different day`() {
        assertRejects(
            TemporalGuard(),
            "What is the weather in Chicago today?",
            "What is the weather in Chicago tomorrow?",
        )
    }

    @Test
    fun `temporal guard ignores relative words that are not about time`() {
        assertAccepts(TemporalGuard(), "How do I undo my last git commit?", "How do I revert a git commit?")
    }

    // --- ScopeGuard --------------------------------------------------------------------------

    @Test
    fun `scope guard rejects a different requested format`() {
        assertRejects(ScopeGuard(), "Write a haiku about the ocean", "Write a sonnet about the ocean")
    }

    @Test
    fun `scope guard rejects a swapped format`() {
        assertRejects(
            ScopeGuard(),
            "Give me a code example of a Python decorator",
            "Explain the theory behind Python decorators",
        )
    }

    @Test
    fun `scope guard accepts a format named on one side only`() {
        // An addition is not a swap: only one of these names a shape, and they are one question.
        assertAccepts(
            ScopeGuard(),
            "How do I rotate an SSH host key on a server?",
            "What are the steps to rotate an SSH host key on a server?",
        )
    }

    @Test
    fun `scope guard accepts prompts that ask for nothing in particular`() {
        assertAccepts(ScopeGuard(), "How do I reverse a list in Python?", "python list reverse")
    }

    // --- DirectionGuard ----------------------------------------------------------------------

    @Test
    fun `direction guard rejects a reversed comparison`() {
        assertRejects(
            DirectionGuard(),
            "Is Postgres better than MySQL for analytics workloads?",
            "Is MySQL better than Postgres for analytics workloads?",
        )
    }

    @Test
    fun `direction guard rejects a reversed conversion`() {
        assertRejects(DirectionGuard(), "Convert 500 EUR to USD", "Convert 500 USD to EUR")
    }

    @Test
    fun `direction guard accepts a reordering with no comparison in it`() {
        assertAccepts(
            DirectionGuard(),
            "In Python, how do I sort a dictionary by value?",
            "How do I sort a dictionary by value in Python?",
        )
    }

    @Test
    fun `direction guard accepts a reordering of a symmetric relation`() {
        // The same transformation as the reversed comparison above, and here it is harmless:
        // difference is symmetric, so no cue fires.
        assertAccepts(
            DirectionGuard(),
            "What is the difference between a thread and a process?",
            "What is the difference between a process and a thread?",
        )
    }

    @Test
    fun `direction guard stays out of it when the words differ`() {
        assertAccepts(DirectionGuard(), "how many miles is 50 km", "Convert 50 km to miles")
    }

    // --- LexicalDivergenceGuard --------------------------------------------------------------

    @Test
    fun `lexical divergence guard rejects prompts with nothing in common`() {
        assertRejects(
            LexicalDivergenceGuard(),
            "How do I reverse a linked list in Python without using recursion?",
            "Which pizza restaurant in Naples has the best reviews right now?",
        )
    }

    @Test
    fun `lexical divergence guard needs enough words on both sides before it judges`() {
        // Four content words each, nothing shared — and still no verdict, because a ratio over
        // sets this small says more about phrasing than about meaning.
        assertAccepts(
            LexicalDivergenceGuard(),
            "reverse a linked list",
            "best pizza in Naples",
        )
    }

    @Test
    fun `lexical divergence guard sees through typos`() {
        assertAccepts(
            LexicalDivergenceGuard(minTokens = 0),
            "How do I clear the npm cache?",
            "how do i clera the npm cahce",
        )
    }

    @Test
    fun `lexical divergence guard sees through inflections`() {
        assertAccepts(
            LexicalDivergenceGuard(minTokens = 0),
            "How do I use a Python decorator?",
            "How do Python decorators work?",
        )
    }

    @Test
    fun `lexical divergence guard abstains on prompts too short to judge`() {
        assertAccepts(LexicalDivergenceGuard(), "375 f to c", "What is 375 degrees Fahrenheit in Celsius?")
    }

    // --- LengthRatioGuard --------------------------------------------------------------------

    @Test
    fun `length ratio guard rejects wildly different lengths`() {
        assertRejects(
            LengthRatioGuard(maxRatio = 2.0),
            "python list reverse",
            "I have a list in Python and I would like to know the idiomatic way of reversing it in place",
        )
    }

    @Test
    fun `length ratio guard accepts comparable lengths`() {
        assertAccepts(LengthRatioGuard(), "How do I reverse a list?", "What is the way to reverse a list?")
    }

    // --- Text --------------------------------------------------------------------------------

    @Test
    fun `one typo covers substitution insertion deletion and transposition`() {
        assertTrue(Text.withinOneTypo("cache", "cacha"), "substitution")
        assertTrue(Text.withinOneTypo("cache", "cachee"), "insertion")
        assertTrue(Text.withinOneTypo("cache", "cach"), "deletion")
        assertTrue(Text.withinOneTypo("cache", "cahce"), "transposition")
        assertTrue(Text.withinOneTypo("clear", "clera"), "transposition at the end")
        assertTrue(Text.withinOneTypo("same", "same"), "identity")
    }

    @Test
    fun `two typos are too many, which is what keeps Austria and Australia apart`() {
        assertFalse(Text.withinOneTypo("austria", "australia"))
        assertFalse(Text.withinOneTypo("commit", "committed"))
        assertFalse(Text.withinOneTypo("abc", "cba"))
    }
}
