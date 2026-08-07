package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.ExternalCorpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.QqpCorpus
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import dev.kmemo.guard.tck.ScoreInterval
import java.util.Locale
import kotlin.math.exp
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M47: whether anything reads structure at a cost between a regular expression and a transformer.
 *
 * M34 measured a boundary and named what would have to come next. Reaching 40% rejection on
 * adversarial pairs costs a ninth of the genuine paraphrases, because a lexical chain has no way to
 * tell a reversed relation from a reordered clause without reading the sentence. The sentence that
 * closed it was specific: what would have to come next is something that reads structure rather than
 * tokens, at a cost between a regular expression and a transformer, and nothing in the repository was
 * that.
 *
 * Both ends of that range are known and both are wrong for this. A regular expression over tokens is
 * what the eleven guards already are, and the measurement says where it stops. A transformer is what
 * GPTCache brings and what the comparison table already prices: better precision on near misses, half
 * the genuine paraphrases refused, and an inference on every candidate. The interesting question is
 * whether anything lives in between, and it had never been asked here.
 *
 * ### The two candidates, and why these two
 *
 * **`slot-order` reads argument roles.** A relation word splits a prompt into what is on each side of
 * it: `from` and `to` in a conversion, `than` in a comparison, `in` in a scoping phrase. Two prompts
 * with the same relation and its arguments swapped are a reversal; two prompts with a phrase moved
 * from one end to the other are not. That distinction is what `WordOrderGuard` approximates with
 * capitalization and `DirectionGuard` with a rotation test, and it is what a part-of-speech tagger
 * would give properly. This is the cheap approximation of a tagger: the closed class of relation
 * words is small, fixed and language-specific, which is exactly what a vocabulary pack is for.
 *
 * **`learned` is a linear classifier over cheap features.** Eight numbers per pair, none of them
 * needing a model file, weights fitted by full-batch gradient descent **on the tuned split alone**,
 * which is the split that exists to be fitted. It is the middle of the range by construction: no
 * inference, no weights to download, no dependency, and a decision boundary a chain of rules cannot
 * express.
 *
 * A learned classifier puts a model inside a library whose whole argument is that it has none. That
 * is a decision rather than an implementation detail, and this test exists to price it before anybody
 * takes it.
 *
 * ### What is published either way
 *
 * Catch rate, false rejection rate and per-candidate latency for both, on every split, whether or not
 * anything beats the lexical chain. A negative result closes this milestone as well as a positive one:
 * the boundary M34 drew stops being a sentence somebody will try to disprove by accident.
 */
class StructureReadingTest {

    /**
     * The finding, asserted so that it cannot quietly stop being true.
     *
     * `slot-order` adds nothing the chain does not already catch. It is not wrong, it is redundant:
     * `direction` reaches the same reversals first, and the cases it would add are ones no corpus here
     * contains. A structure reader whose whole contribution is zero on 6,964 blind pairs has answered
     * the question it was built to answer.
     */
    @Test
    fun `the role-reading candidate adds nothing the chain has not already caught`() {
        val corpus = QqpCorpus.corpus() ?: return
        val chain = MatchGuards.standard()
        val added = corpus.nearMisses.count { rejects(chain + SlotOrderGuard(), it) } -
            corpus.nearMisses.count { rejects(chain, it) }
        assertTrue(
            added <= SLOT_ORDER_BUDGET,
            "slot-order now adds $added catches on the question split. It was measured at zero, and a " +
                "structure reader that has started contributing is a result to publish rather than an " +
                "assertion to raise.",
        )
        val cost = corpus.paraphrases.count { rejects(listOf(SlotOrderGuard()), it) }
        assertTrue(cost == 0, "slot-order rejected $cost paraphrases; it was measured at none")
    }

    /**
     * The other finding, and the one that decides the milestone.
     *
     * A cheap learned classifier is not a cheaper transformer, it is a worse one. It refuses far more
     * genuine paraphrases than the cross-encoder this project already priced, which keeps 45% on
     * held-out and 69% on validation. Being cheaper than something that lands outside the useful
     * region is not an argument for shipping it.
     */
    @Test
    fun `the learned candidate refuses more paraphrases than the transformer it would replace`() {
        val corpus = QqpCorpus.corpus() ?: return
        val learned = LearnedGuard(fitOnTuned())
        val kept = corpus.paraphrases.count { !rejects(listOf(learned), it) }
        val retention = kept.toDouble() / corpus.paraphrases.size
        assertTrue(
            retention < TRANSFORMER_RETENTION,
            "the fitted classifier now keeps ${format(retention)} of paraphrases, past the " +
                "$TRANSFORMER_RETENTION a cross-encoder manages. That would make it a candidate to " +
                "ship rather than a negative result to publish, and this assertion is the wrong place " +
                "to record it.",
        )
    }

    /** Neither ships, and `standard()` is untouched. The assertion that keeps the finding honest. */
    @Test
    fun `neither candidate is in any shipped chain`() {
        for (chain in listOf(
            MatchGuards.standard(), MatchGuards.strict(), MatchGuards.longPrompts(),
            MatchGuards.prose(), MatchGuards.shortQuestions(), MatchGuards.responseAware(),
        )) {
            val names = chain.map { it.name }
            assertTrue("slot-order" !in names, "slot-order shipped without its measurement changing")
            assertTrue("learned" !in names, "a fitted classifier shipped inside a library that has none")
        }
    }

    /**
     * The result, printed for every split and published in `docs/MEASUREMENTS.md`.
     *
     * Read the `+chain` rows rather than the standalone ones. A candidate is only useful if it adds to
     * what the chain already catches, and a standalone catch rate says nothing about that.
     */
    @Test
    fun `print the structure-reading comparison`() {
        val weights = fitOnTuned()
        val corpora = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS) +
            listOfNotNull(QqpCorpus.corpus(), ExternalCorpus.corpus())

        println()
        println("Fitted on the tuned split only, by full-batch gradient descent, deterministic:")
        println("  " + FEATURES.indices.joinToString(", ") { "${FEATURES[it]}=${format(weights[it])}" })
        println("  bias=${format(weights.last())}")
        println()

        for (corpus in corpora) {
            println("${corpus.name} (${corpus.standing.name.lowercase().replace('_', '-')})")
            printRow(corpus, "chain", MatchGuards.standard())
            for (candidate in candidates(weights)) {
                printRow(corpus, "${candidate.name} alone", listOf(candidate))
                printRow(corpus, "chain + ${candidate.name}", MatchGuards.standard() + candidate)
            }
            println()
        }

        println("Per-candidate cost, warmed, over the committed splits:")
        val pairs = (TUNED_CORPUS.pairs + HELD_OUT_CORPUS.pairs + VALIDATION_CORPUS.pairs)
        printCost("the lexical chain", MatchGuards.standard(), pairs)
        for (candidate in candidates(weights)) printCost(candidate.name, listOf(candidate), pairs)
        println()
    }

    private fun printRow(corpus: Corpus, label: String, chain: List<MatchGuard>) {
        val caught = corpus.nearMisses.count { rejects(chain, it) }
        val kept = corpus.paraphrases.count { !rejects(chain, it) }
        val interval = ScoreInterval.wilson95(caught, corpus.nearMisses.size)
        println(
            String.format(
                Locale.ROOT,
                "  %-26s caught %4d/%-4d (%4.1f%% ±%.1f), kept %4d/%-4d (%4.1f%%)",
                label,
                caught, corpus.nearMisses.size, 100.0 * caught / corpus.nearMisses.size,
                interval.halfWidthPoints,
                kept, corpus.paraphrases.size, 100.0 * kept / corpus.paraphrases.size,
            ),
        )
    }

    private fun printCost(label: String, chain: List<MatchGuard>, pairs: List<CorpusPair>) {
        repeat(2) { evaluateAll(chain, pairs) }
        val best = (1..5).minOf { measureNanoTime { evaluateAll(chain, pairs) } }
        println(
            String.format(
                Locale.ROOT,
                "  %-26s %6.0f ns",
                label,
                best.toDouble() / (pairs.size * 2),
            ),
        )
    }

    private fun evaluateAll(chain: List<MatchGuard>, pairs: List<CorpusPair>) {
        var sink = 0
        for (pair in pairs) {
            for (guard in chain) {
                if (guard.evaluate(pair.a, pair.b) is GuardVerdict.Reject) sink++
                if (guard.evaluate(pair.b, pair.a) is GuardVerdict.Reject) sink++
            }
        }
        check(sink >= 0)
    }

    private fun candidates(weights: DoubleArray): List<MatchGuard> =
        listOf(SlotOrderGuard(), LearnedGuard(weights))

    private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
        it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
            it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    // ---- the learned candidate -----------------------------------------------------------------

    /**
     * The weights, fitted on the tuned split and on nothing else.
     *
     * Deterministic: full batch, fixed learning rate, fixed iteration count, weights starting at zero.
     * A fit with a random seed in it would produce a different classifier on a different machine and
     * every figure below it would be unreproducible, which is the whole problem this repository's
     * corpus discipline exists to avoid.
     */
    private fun fitOnTuned(): DoubleArray {
        val rows = TUNED_CORPUS.pairs.map { featuresOf(it.a, it.b) to if (it.shouldMatch) 0.0 else 1.0 }
        // Class weights, because the split is 64% near misses by construction. A corpus of realistic
        // traffic would be almost all paraphrases and would measure nothing, so every corpus here is
        // built out of hard pairs, and a fit that inherits that prior learns to reject. Balancing is
        // the minimum needed for the result below to be about the features rather than about the
        // corpus's shape.
        val nearMisses = rows.count { it.second == 1.0 }.coerceAtLeast(1)
        val paraphrases = rows.size - nearMisses
        val weightOf = { label: Double ->
            if (label == 1.0) rows.size / (2.0 * nearMisses) else rows.size / (2.0 * paraphrases.coerceAtLeast(1))
        }
        val weights = DoubleArray(FEATURES.size + 1)
        repeat(ITERATIONS) {
            val gradient = DoubleArray(weights.size)
            for ((features, label) in rows) {
                val error = (sigmoid(score(features, weights)) - label) * weightOf(label)
                for (i in features.indices) gradient[i] += error * features[i]
                gradient[weights.size - 1] += error
            }
            for (i in weights.indices) weights[i] -= LEARNING_RATE * gradient[i] / rows.size
        }
        return weights
    }

    private fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z))

    private fun score(features: DoubleArray, weights: DoubleArray): Double {
        var total = weights[weights.size - 1]
        for (i in features.indices) total += weights[i] * features[i]
        return total
    }

    /**
     * Eight numbers per pair, all of them cheap and none of them needing a model.
     *
     * Deliberately features a chain of rules cannot combine: a rule sees one of these at a time and
     * has to decide, where a weighted sum can trade a weak signal against another weak signal.
     */
    private fun featuresOf(a: String, b: String): DoubleArray {
        val stopwords = Vocabulary.STOPWORDS
        val left = Text.contentTokens(a, stopwords)
        val right = Text.contentTokens(b, stopwords)
        val longer = maxOf(left.size, right.size).coerceAtLeast(1)
        val shared = left.count { token -> right.any { Text.isSameWord(it, token) } }
        val aligned = if (left.size == right.size) {
            left.indices.count { !Text.isSameWord(left[it], right[it]) }
        } else {
            -1
        }
        val firstDifference = if (aligned > 0) {
            left.indices.first { !Text.isSameWord(left[it], right[it]) }.toDouble() / longer
        } else {
            1.0
        }
        return doubleArrayOf(
            if (aligned >= 0) aligned.toDouble() / longer else 1.0,
            1.0 - shared.toDouble() / longer,
            (left.size - right.size).toDouble() / longer,
            longer.toDouble() / MAX_EXPECTED_TOKENS,
            firstDifference,
            if (left.toSet() == right.toSet() && left != right) 1.0 else 0.0,
            if (Text.tokens(a).any { it in Vocabulary.DIRECTIONAL_CUES }) 1.0 else 0.0,
            if (Text.entityTokens(a) != Text.entityTokens(b)) 1.0 else 0.0,
        )
    }

    /**
     * The classifier as a guard, so it is measured by exactly the same harness as everything else.
     *
     * The threshold is 0.5, which is where a logistic classifier's own decision boundary sits, and it
     * is not tuned: moving it would be fitting a second parameter on whichever split showed the
     * improvement.
     */
    private inner class LearnedGuard(private val weights: DoubleArray) : MatchGuard {
        override val name: String get() = "learned"

        override fun evaluate(query: String, candidate: String): GuardVerdict {
            val probability = sigmoid(score(featuresOf(query, candidate), weights))
            if (probability < DECISION_BOUNDARY) return GuardVerdict.Accept
            return GuardVerdict.Reject(
                "a fitted classifier scores this pair at ${format(probability)}, past $DECISION_BOUNDARY",
            )
        }
    }

    private companion object {
        private val FEATURES = listOf(
            "alignedDifferences", "divergence", "lengthGap", "length",
            "firstDifferencePosition", "sameSetDifferentOrder", "hasCue", "entitiesDiffer",
        )
        private const val ITERATIONS = 4_000
        private const val LEARNING_RATE = 2.0
        private const val DECISION_BOUNDARY = 0.5
        private const val MAX_EXPECTED_TOKENS = 20.0

        /** Catches slot-order may add before the negative result needs re-reading. */
        private const val SLOT_ORDER_BUDGET = 5

        /**
         * What GPTCache's cross-encoder keeps on the worse of the two written splits, from
         * `docs/MEASUREMENTS.md`. A cheap classifier that keeps less than a transformer is not a
         * cheaper transformer.
         */
        private const val TRANSFORMER_RETENTION = 0.45
    }
}

/**
 * Rejects a pair whose arguments are swapped around the same relation word.
 *
 * The cheap approximation of a part-of-speech tagger, and the one M34 pointed at. A relation word
 * splits a prompt into what sits on each side of it, and a question's meaning usually turns on which
 * side a term is on: `from London to Tokyo` and `from Tokyo to London` are one relation with its
 * arguments exchanged.
 *
 * It reads structure rather than tokens in the only sense that matters here: it asks where a word is
 * relative to a relation, not whether the two prompts contain the same words. That is why it can
 * separate a reversal from a fronted phrase, which no set comparison can, and why it does not need
 * capitalization, which is what `WordOrderGuard` leans on and what real lowercase traffic does not
 * have.
 *
 * **Not shipped in any preset.** `StructureReadingTest` publishes what it does on every split.
 */
internal class SlotOrderGuard(
    private val relations: Set<String> = RELATIONS,
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
) : MatchGuard {

    override val name: String get() = "slot-order"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val left = slotsOf(query) ?: return GuardVerdict.Accept
        val right = slotsOf(candidate) ?: return GuardVerdict.Accept
        if (left.relation != right.relation) return GuardVerdict.Accept
        if (left.before.isEmpty() || left.after.isEmpty()) return GuardVerdict.Accept
        if (left.before == right.before && left.after == right.after) return GuardVerdict.Accept
        // A swap and nothing else: what was on one side of the relation is now on the other, both ways.
        if (left.before != right.after || left.after != right.before) return GuardVerdict.Accept

        return GuardVerdict.Reject(
            "the arguments of '${left.relation}' are exchanged: " +
                "${left.before} and ${left.after} against ${right.before} and ${right.after}",
        )
    }

    private class Slots(val relation: String, val before: List<String>, val after: List<String>)

    /**
     * The prompt split at its **last** relation word, or `null` when it has none or has several
     * different ones.
     *
     * The last, because a question puts its subject first and its relation late: in `cheapest month
     * to fly from london to tokyo` the split that carries the meaning is the final `to`. Several
     * different relations mean a sentence this cannot read, and the guard says nothing rather than
     * guessing, which is the same asymmetry every guard here is built on.
     */
    private fun slotsOf(text: String): Slots? {
        val tokens = Text.tokens(text)
        val positions = tokens.indices.filter { tokens[it] in relations }
        if (positions.isEmpty()) return null
        val used = positions.map { tokens[it] }.toSet()
        if (used.size > 1) return null
        val split = positions.last()
        return Slots(
            relation = tokens[split],
            before = tokens.take(split).filterNot { it in stopwords },
            after = tokens.drop(split + 1).filterNot { it in stopwords },
        )
    }

    private companion object {
        /**
         * The closed class this reads. Small, fixed, and language-specific, which is what makes it a
         * vocabulary rather than a rule.
         */
        private val RELATIONS = setOf("to", "than", "into", "versus", "vs", "over", "against", "before", "after")
    }
}
