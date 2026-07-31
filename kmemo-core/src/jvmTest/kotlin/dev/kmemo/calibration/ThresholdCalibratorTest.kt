package dev.kmemo.calibration

import dev.kmemo.fixtures.ConceptEmbedder
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.guard.MatchGuards
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThresholdCalibratorTest {

    @Test
    fun `a perfectly separable set gets a threshold that costs nothing`() = runTest {
        val pairs = listOf(
            PromptPair("How do I reverse a list in Python?", "How do I reverse a list in Python?", shouldMatch = true),
            PromptPair("What is the capital of France?", "What is the capital of France?", shouldMatch = true),
            PromptPair("How do I bake sourdough?", "Explain quantum tunnelling", shouldMatch = false),
        )

        val report = ThresholdCalibrator(ConceptEmbedder(), MatchGuards.none()).calibrate(pairs)

        assertEquals(0, report.recommended.falsePositives)
        assertEquals(0, report.recommended.falseNegatives)
        assertEquals(1.0, report.recommended.recall, 1e-9)
        assertNotNull(report.safest)
    }

    /**
     * The whole argument for the guard layer, expressed as a number.
     *
     * The same corpus, the same embedder, the same threshold sweep — the only difference is whether
     * guards run. If they were decoration, both runs would produce the same false-hit rate.
     */
    @Test
    fun `guards buy false-hit rate that no threshold can`() = runTest {
        val pairs = TUNED_CORPUS.asPromptPairs()
        val embedder = ConceptEmbedder()

        val withoutGuards = ThresholdCalibrator(embedder, MatchGuards.none()).calibrate(pairs)
        val withGuards = ThresholdCalibrator(embedder, MatchGuards.standard()).calibrate(pairs)

        println()
        println("--- similarity only ---")
        println(withoutGuards.summary())
        println("--- similarity + standard guards ---")
        println(withGuards.summary())

        val bestAchievableWithoutGuards = withoutGuards.outcomes.minOf { it.falseHitRate }
        val achievedWithGuards = withGuards.recommended.falseHitRate

        assertTrue(
            achievedWithGuards < bestAchievableWithoutGuards,
            "guards should beat the best false-hit rate any threshold reaches on its own " +
                "($achievedWithGuards vs $bestAchievableWithoutGuards)",
        )
    }

    @Test
    fun `the recommendation stays inside the false-hit budget when one is reachable`() = runTest {
        val report = ThresholdCalibrator(ConceptEmbedder(), MatchGuards.standard())
            .calibrate(TUNED_CORPUS.asPromptPairs(), maxFalseHitRate = 0.05)

        assertTrue(
            report.recommended.falseHitRate <= 0.05,
            "recommended ${report.recommended.threshold} has false-hit rate ${report.recommended.falseHitRate}",
        )
    }

    @Test
    fun `a lower threshold never returns fewer hits`() = runTest {
        val report = ThresholdCalibrator(ConceptEmbedder(), MatchGuards.none())
            .calibrate(TUNED_CORPUS.asPromptPairs())

        report.outcomes.zipWithNext { lower, higher ->
            assertTrue(
                higher.truePositives <= lower.truePositives,
                "raising the threshold from ${lower.threshold} to ${higher.threshold} added hits",
            )
        }
    }

    @Test
    fun `the sweep covers the requested range`() = runTest {
        val report = ThresholdCalibrator(ConceptEmbedder())
            .calibrate(
                listOf(PromptPair("a prompt", "a prompt", shouldMatch = true)),
                range = 0.90..0.95,
                step = 0.01,
            )

        assertEquals(6, report.outcomes.size)
        assertEquals(0.90, report.outcomes.first().threshold, 1e-9)
        assertEquals(0.95, report.outcomes.last().threshold, 1e-9)
    }

    @Test
    fun `nonsense arguments are refused`() = runTest {
        val calibrator = ThresholdCalibrator(ConceptEmbedder())
        val pairs = listOf(PromptPair("a", "b", shouldMatch = true))

        assertFailsWith<IllegalArgumentException> { calibrator.calibrate(emptyList()) }
        assertFailsWith<IllegalArgumentException> { calibrator.calibrate(pairs, step = 0.0) }
        assertFailsWith<IllegalArgumentException> { calibrator.calibrate(pairs, maxFalseHitRate = 2.0) }
    }
}
