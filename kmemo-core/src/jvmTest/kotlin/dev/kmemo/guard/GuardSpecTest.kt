package dev.kmemo.guard

import dev.kmemo.guard.tck.ConformanceVectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M44: the specification's generated halves, and the check that they still describe this code.
 *
 * `spec/guards/SPEC.md` states each guard's rule in prose somebody can implement from without
 * reading Kotlin. Prose about tokenization is exactly where two implementations diverge quietly, so
 * two machine-readable files travel with it and both are generated from the shipped guards:
 *
 * - `spec/guards/vectors.json`, a decided verdict per guard per pair. A reimplementation that
 *   disagrees fails a build instead of returning a wrong answer eighteen months later.
 * - `spec/vocabulary/en.json`, the English pack as data. M44 insists the packs are data rather than
 *   rules, and a conformant implementation supplying its own Italian markers must not fail
 *   conformance for English, so the markers live outside the specification's prose.
 *
 * They are committed and checked, the way the public API dumps are. Regenerate with
 * `./gradlew :kmemo-core:jvmTest --tests '*GuardSpecTest*' -PupdateGuardSpec=true` and read the diff.
 */
class GuardSpecTest {

    @Test
    fun `the committed vectors still describe the shipped guards`() {
        val generated = vectorsJson()
        val committed = File(SPEC_DIR, "guards/vectors.json")
        assertMatches(committed, generated)
    }

    @Test
    fun `the committed vocabulary still describes the shipped pack`() {
        val generated = vocabularyJson(GuardVocabulary.ENGLISH)
        val committed = File(SPEC_DIR, "vocabulary/en.json")
        assertMatches(committed, generated)
    }

    /**
     * The vectors reproduce on the artifact a third party downloads, not on a private copy.
     *
     * `kmemo-guard-tck` ships the file and the checker; this asserts that running the checker over
     * the shipped guards finds nothing, which is the same call an outside implementation makes.
     */
    @Test
    fun `every shipped guard conforms to the shipped vectors`() {
        val vectors = ConformanceVectors.parse(File(SPEC_DIR, "guards/vectors.json").readText())
        for (guard in MatchGuards.standard()) {
            val mismatches = ConformanceVectors.check(guard, vectors)
            assertTrue(
                mismatches.isEmpty(),
                "${guard.name} disagreed with ${mismatches.size} of its vectors: " +
                    mismatches.take(5).joinToString("; "),
            )
        }
    }

    /** Every guard in the chain is covered, so a new one cannot arrive without vectors. */
    @Test
    fun `every guard in the standard chain has vectors`() {
        val vectors = ConformanceVectors.parse(File(SPEC_DIR, "guards/vectors.json").readText())
        val covered = vectors.map { it.guard }.toSet()
        assertEquals(
            MatchGuards.standard().map { it.name }.toSet(),
            covered,
            "the vector file must cover exactly the standard chain",
        )
    }

    /**
     * Both directions of every pair are vectors, because a cache asks both.
     *
     * Either prompt could be the one already stored when the other arrives, so a rule that is
     * directional has to say so in the file rather than in a footnote. `sub-span` is the shipped
     * guard for which the two directions differ.
     */
    @Test
    fun `the vectors carry both directions of every pair`() {
        val vectors = ConformanceVectors.parse(File(SPEC_DIR, "guards/vectors.json").readText())
        val seen = vectors.map { Triple(it.guard, it.query, it.candidate) }.toSet()
        val missing = vectors.filterNot { Triple(it.guard, it.candidate, it.query) in seen }
        assertTrue(
            missing.isEmpty(),
            "${missing.size} vectors have no reversed twin, first: ${missing.firstOrNull()}",
        )
    }

    /**
     * Every guard has at least one vector it rejects, or the file proves nothing about it.
     *
     * A vector set of accepts alone is passed by a guard that never fires, which is the failure mode
     * the whole TCK exists to catch. The rejecting cases are what pin the rule.
     */
    @Test
    fun `every guard has a vector it rejects`() {
        val vectors = ConformanceVectors.parse(File(SPEC_DIR, "guards/vectors.json").readText())
        val silent = MatchGuards.standard()
            .map { it.name }
            .filter { name -> vectors.none { it.guard == name && it.reject } }
        assertTrue(
            silent.isEmpty(),
            "no vector rejects for: $silent. Add a pair that exercises the rule; a file of accepts " +
                "is passed by a guard that does nothing.",
        )
    }

    private fun assertMatches(committed: File, generated: String) {
        val out = File("build/spec/${committed.name}")
        out.parentFile.mkdirs()
        out.writeText(generated)

        if (System.getProperty(UPDATE_PROPERTY).toBoolean()) {
            committed.parentFile.mkdirs()
            committed.writeText(generated)
            println("rewrote ${committed.path}; read the diff before committing it")
            return
        }

        assertTrue(committed.isFile, "${committed.path} is missing. Regenerate with -PupdateGuardSpec=true.")
        assertEquals(
            committed.readText(),
            generated,
            "${committed.path} no longer describes the shipped guards. If the change was meant, " +
                "regenerate with -PupdateGuardSpec=true and read the diff: it is a change to a " +
                "published specification, not to a test fixture.",
        )
    }

    private fun vectorsJson(): String {
        val guards = MatchGuards.standard()
        val document = buildJsonObject {
            put("about", ABOUT_VECTORS)
            put("specification", "spec/guards/SPEC.md")
            put("vocabulary", "spec/vocabulary/en.json")
            putJsonArray("vectors") {
                for (guard in guards) {
                    for ((a, b) in VECTOR_PAIRS) {
                        for ((query, candidate) in listOf(a to b, b to a)) {
                            addJsonObject {
                                put("guard", guard.name)
                                put("query", query)
                                put("candidate", candidate)
                                val verdict = guard.evaluate(query, candidate)
                                put("reject", verdict is GuardVerdict.Reject)
                                put("reason", (verdict as? GuardVerdict.Reject)?.reason)
                            }
                        }
                    }
                }
            }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), document) + "\n"
    }

    private fun vocabularyJson(vocabulary: GuardVocabulary): String {
        val document = buildJsonObject {
            put("about", ABOUT_VOCABULARY)
            put("language", "en")
            putJsonArray("stopwords") { vocabulary.stopwords.sorted().forEach { add(it) } }
            putJsonArray("sentenceOpeners") { vocabulary.sentenceOpeners.sorted().forEach { add(it) } }
            putJsonArray("nonEntityCapitals") { vocabulary.nonEntityCapitals.sorted().forEach { add(it) } }
            putJsonArray("negationMarkers") { vocabulary.negationMarkers.sorted().forEach { add(it) } }
            putJsonArray("temporalMarkers") { vocabulary.temporalMarkers.sorted().forEach { add(it) } }
            putJsonArray("scopeMarkers") { vocabulary.scopeMarkers.sorted().forEach { add(it) } }
            putJsonArray("directionalCues") { vocabulary.directionalCues.sorted().forEach { add(it) } }
            putJsonArray("qualifierOpeners") { vocabulary.qualifierOpeners.sorted().forEach { add(it) } }
            putJsonArray("antonyms") {
                vocabulary.antonyms
                    .map { listOf(it.first, it.second).sorted() }
                    .sortedWith(compareBy({ it[0] }, { it[1] }))
                    .forEach { pair -> addJsonArray { pair.forEach { add(it) } } }
            }
            putJsonObject("units") {
                for (token in vocabulary.units.keys.sorted()) {
                    val unit = vocabulary.units.getValue(token)
                    putJsonObject(token) {
                        put("canonical", unit.canonical)
                        put("dimension", unit.dimension)
                    }
                }
            }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), document) + "\n"
    }

    private companion object {
        private val PRETTY = Json { prettyPrint = true }
        private val SPEC_DIR = File("../spec")
        private const val UPDATE_PROPERTY = "kmemo.updateGuardSpec"

        private const val ABOUT_VECTORS =
            "Conformance vectors for the guards described in spec/guards/SPEC.md. Generated from " +
                "the reference implementation and committed, so a change to a rule arrives as a diff " +
                "somebody approves. `reject` is normative; `reason` is prose for a human reading a " +
                "miss and is never compared. Every pair appears in both directions, because either " +
                "prompt could be the one already cached when the other arrives."

        private const val ABOUT_VOCABULARY =
            "The English marker sets the guards in spec/guards/SPEC.md read from. They are data, not " +
                "rules: an implementation that supplies its own markers for another language is " +
                "still conformant, and the vectors are stated against this pack so that two " +
                "implementations reading the same data must reach the same verdicts."

        /**
         * The pairs the vectors are stated against.
         *
         * Chosen to pin each rule and each edge that a reimplementation gets wrong first: the
         * comma-as-decimal-point rule, the acronym expansion test, the rotation test, the framing
         * clause, the length floors, and the tokenizer's treatment of typos, inflections and
         * sentence boundaries. Every guard is evaluated against every pair, so most vectors are
         * accepts, which is the half of a rule that is easiest to get wrong and hardest to notice.
         */
        private val VECTOR_PAIRS: List<Pair<String, String>> = listOf(
            // numeric: magnitude, grouping commas, decimal commas, one-sided numbers
            "Convert 100 USD to EUR" to "Convert 250 USD to EUR",
            "Convert 1,000 USD to EUR" to "Convert 1000 USD to EUR",
            "Convert 3,5 km to miles" to "Convert 35 km to miles",
            "Convert 3,5 kg to pounds" to "Convert 5,3 kg to pounds",
            "Explain OAuth 2.0" to "Explain OAuth 2.0 to a 5 year old",
            "what is the port for postgres" to "what is the port for mysql",
            // unit: dimension, canonical spelling, one-sided naming
            "Convert 50 km to miles" to "Convert 50 km to meters",
            "375 f to c" to "What is 375 degrees Fahrenheit in Celsius?",
            "250 euros in British pounds" to "250 EUR in GBP",
            "how many km is 50 kilometers" to "how many kilometers is 50 km",
            // temporal
            "What is the weather in Chicago today?" to "What is the weather in Chicago tomorrow?",
            "how do I see the current branch" to "how do I see the current working directory",
            // negation, including the contraction test and the one-synonym tolerance
            "which foods should I eat before a run" to "which foods should I not eat before a run",
            "foods you should eat while pregnant" to "foods you should not eat during pregnancy",
            "why can't I connect to the VPN" to "why is my connection to the VPN failing",
            // antonym: the flip, the incidental repeat, the absent opposite
            "How do I enable two factor authentication on GitHub?" to
                "How do I disable two factor authentication on GitHub?",
            "How do I turn on format on save in VS Code?" to "How do I turn off format on save in VS Code?",
            "Run this before deploy" to "run this prior to deploy",
            // entity: capitalization, the opening word, acronym expansion, addition versus swap
            "What is the capital of Australia" to "what is the capital of Austria",
            "What does GDPR require of a data processor" to
                "What does the General Data Protection Regulation require of a data processor",
            "What does SOLID stand for in OOP?" to
                "what does each letter of SOLID mean in object oriented design?",
            "I am planning a holiday. Austria is where I want to go." to
                "I am planning a holiday. Australia is where I want to go.",
            "How do I center a div in CSS? Show me an example." to
                "How do I center a div in CSS? Give me an example.",
            "Compare Python vs. Java performance" to "Compare Python vs. Ruby performance",
            // substitution: the floor, the order, typos and inflections, the unit agreement
            "sales tax in oregon" to "sales tax in washington",
            "define recursion" to "explain recursion",
            "can dogs eat grapes" to "can dogs eat apples",
            "How do I merge two hashes in Ruby?" to "How do I combine two hashes into one in Ruby?",
            "how do i organise my imports" to "how do i organize my imports",
            "how do i raed a csv in pandas" to "how do i read a csv in pandas",
            // scope: format and depth, superset, one-sided
            "Write a haiku about the ocean" to "Write a sonnet about the ocean",
            "Give me an overview and an example of a Python decorator" to
                "Give me an example of a Python decorator",
            "how do I rotate an SSH host key" to "what are the steps to rotate an SSH host key",
            // direction: the swap, the rotation, the symmetric coordinator, the unrelated alternative
            "Is Postgres better than MySQL?" to "Is MySQL better than Postgres?",
            "In Python, how do I sort a dictionary by value?" to
                "How do I sort a dictionary by value in Python?",
            "Which is better, Redis or Memcached?" to "Which is better, Memcached or Redis?",
            "convert dollars to euros or pounds" to "convert euros to dollars or pounds",
            "cheapest month to fly from london to tokyo" to "cheapest month to fly from tokyo to london",
            // sub-span: the qualifier, the span rule, the framing clause, the preamble
            "How do I deploy a Rails app" to "How do I deploy a Rails app on Heroku",
            "How do I parse an ISO 8601 timestamp in Java" to
                "I am working on a REST client and need to parse an ISO 8601 timestamp in Java",
            "how do I compress a folder" to "I am on Ubuntu and I want to compress a folder",
            "how do I install pandas" to "how do I install pandas for my exam tomorrow",
            // lexical divergence: the backstop, and the short prompts it must not judge
            "how do I kill a process on a port" to
                "Hi, could you please tell me how to kill a process on a port? Thanks!",
            "how do i undo my last git commit" to
                "I committed by mistake in git, how do I take that commit back?",
            // The one case lexical-divergence exists for, and the one no corpus here contains: two
            // prompts sharing nothing, which only ever meet when an embedder proposes one for the
            // other. It has to be in the vectors or the rule is stated and never exercised.
            "how do I rotate an SSH host key on ubuntu" to
                "what temperature should I bake sourdough bread at in a dutch oven",
            // tokenizer edges every reimplementation meets
            "" to "",
            "?" to "?",
            "İstanbul ÄÖÜ ßß" to "istanbul äöü ss",
            "🙂 emoji and a ZWJ 👨‍👩‍👧" to "emoji and a ZWJ",
            "Country: Austria. Give me the capital." to "Country: Australia. Give me the capital.",
            "what is 375 degrees f in c" to "what is 375 degrees f in c",
        )
    }
}
