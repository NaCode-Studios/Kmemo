package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.ExternalCorpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.QqpCorpus
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import dev.kmemo.guard.tck.ScoreInterval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M45: the figures in the published documents, regenerated and checked against what they say.
 *
 * Every number in `README.md` and `docs/MEASUREMENTS.md` used to be reproducible by running a
 * `gradlew` command printed beside it, which is more than most projects do and is not the same as
 * being reproducible. It also left the prose unguarded: the corpus floors catch a guard whose rate
 * slips, and nothing at all catches a sentence that stopped being true three releases ago.
 *
 * This computes each figure it covers, renders the exact line the document must carry, and asserts
 * that the document carries it. A measurement that moves therefore breaks the build until somebody
 * edits the prose, which is the only mechanism that keeps a number and a sentence in step.
 *
 * The figures are also written to `docs/figures.json`, and `tools/figures/check.py` re-runs the
 * document half of this check with no Gradle and no Kotlin, from that file alone. The corpus figures
 * go further: `tools/corpus-runner` recomputes them from the published specification, so a sceptic
 * can reproduce them without this repository's source tree at all.
 *
 * **What it does not cover is named rather than implied.** The verifier, admission, RAG, GPTCache and
 * on-device figures come from tests that need a fetched dataset, a Python harness or a native target,
 * so each is registered here with the command that produces it and no rendered claim. Extending the
 * cover means giving those tests somewhere to write their numbers, which is a change to them.
 */
class PublishedFiguresTest {

    @Test
    fun `the documents say what the measurements say`() {
        val figures = figures()
        val documents = DOCUMENTS.associateWith { File("../$it").readText() }

        val wrong = figures.filter { it.claim != null }.filterNot { figure ->
            figure.documents.all { name -> documents.getValue(name).contains(figure.claim!!) }
        }
        assertTrue(
            wrong.isEmpty(),
            "these measurements no longer match the published documents:\n" +
                wrong.joinToString("\n") { "  ${it.id} expects: ${it.claim}" } +
                "\n\nThe measurement is right and the prose is stale. Edit the documents.",
        )
    }

    @Test
    fun `the committed figure file still describes the measurements`() {
        val generated = figuresJson(figures())
        val committed = File("../docs/figures.json")
        val out = File("build/spec/figures.json")
        out.parentFile.mkdirs()
        out.writeText(generated)

        if (System.getProperty(UPDATE_PROPERTY).toBoolean()) {
            committed.writeText(generated)
            println("rewrote ${committed.path}; read the diff before committing it")
            return
        }
        assertTrue(committed.isFile, "${committed.path} is missing. Regenerate with -PupdateGuardSpec=true.")
        assertEquals(
            committed.readText(),
            generated,
            "docs/figures.json no longer describes the measurements. Regenerate with " +
                "-PupdateGuardSpec=true and update any prose the diff touches.",
        )
    }

    /** Every figure names the command that produces it, so a reader can run it rather than trust it. */
    @Test
    fun `every figure names its command`() {
        val missing = figures().filter { it.command.isBlank() }
        assertTrue(missing.isEmpty(), "figures with no command: ${missing.map { it.id }}")
    }

    private class Figure(
        val id: String,
        val command: String,
        val reproducibleOutsideTheJvm: Boolean,
        /** The exact line the documents must carry, or `null` for a figure this cannot render yet. */
        val claim: String? = null,
        val documents: List<String> = emptyList(),
        val note: String? = null,
    )

    private fun figures(): List<Figure> {
        val result = mutableListOf<Figure>()
        val chain = MatchGuards.standard()

        for (corpus in corpora()) {
            val caught = corpus.nearMisses.count { rejects(chain, it) }
            val kept = corpus.paraphrases.count { !rejects(chain, it) }
            result += Figure(
                id = "corpus.${corpus.name}",
                command = CORPUS_COMMAND,
                reproducibleOutsideTheJvm = true,
                claim = corpusRow(corpus, caught, kept),
                documents = DOCUMENTS,
            )
        }

        val qqp = QqpCorpus.corpus()
        if (qqp != null) {
            val head = MatchGuards.shortQuestions()
            result += Figure(
                id = "preset.shortQuestions.qqp",
                command = FLOOR_COMMAND,
                reproducibleOutsideTheJvm = true,
                claim = presetRow(qqp, head),
                documents = listOf("docs/MEASUREMENTS.md"),
            )
        }

        for ((id, command) in UNCOVERED) {
            result += Figure(
                id = id,
                command = command,
                reproducibleOutsideTheJvm = false,
                note = "measured by its own test; not rendered as a claim, so drift in the prose " +
                    "around it is not caught here",
            )
        }
        return result
    }

    private fun corpora(): List<Corpus> =
        listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS) +
            listOfNotNull(QqpCorpus.corpus(), ExternalCorpus.corpus())

    /**
     * One row of the corpus table, exactly as the documents must carry it.
     *
     * The tuned split prints words rather than percentages, because a rate measured on the split the
     * guards were fitted against is not a rate anybody should read.
     */
    private fun corpusRow(corpus: Corpus, caught: Int, kept: Int): String {
        val standing = corpus.standing.name.lowercase().replace('_', '-')
        if (standing == "in-sample") {
            return "| ${corpus.name} | in-sample, not evidence | in-sample, not evidence |"
        }
        val catchInterval = ScoreInterval.wilson95(caught, corpus.nearMisses.size)
        val keptInterval = ScoreInterval.wilson95(kept, corpus.paraphrases.size)
        return String.format(
            Locale.ROOT,
            "| %s | %.0f%% ±%.0f (%d/%d) | %.0f%% ±%.0f (%d/%d) |",
            corpus.name,
            100.0 * caught / corpus.nearMisses.size, catchInterval.halfWidthPoints,
            caught, corpus.nearMisses.size,
            100.0 * kept / corpus.paraphrases.size, keptInterval.halfWidthPoints,
            kept, corpus.paraphrases.size,
        )
    }

    private fun presetRow(corpus: Corpus, preset: List<MatchGuard>): String {
        val standardCaught = corpus.nearMisses.count { rejects(MatchGuards.standard(), it) }
        val standardKept = corpus.paraphrases.count { !rejects(MatchGuards.standard(), it) }
        val presetCaught = corpus.nearMisses.count { rejects(preset, it) }
        val presetKept = corpus.paraphrases.count { !rejects(preset, it) }
        return String.format(
            Locale.ROOT,
            "| %s | %d → %d | %d → %d |",
            corpus.name, standardCaught, presetCaught, standardKept, presetKept,
        )
    }

    private fun figuresJson(figures: List<Figure>): String {
        val document = buildJsonObject {
            put("about", ABOUT)
            put("generatedBy", "PublishedFiguresTest")
            putJsonArray("documents") { DOCUMENTS.forEach { add(it) } }
            putJsonArray("figures") {
                for (figure in figures) {
                    addJsonObject {
                        put("id", figure.id)
                        put("command", figure.command)
                        put("reproducibleOutsideTheJvm", figure.reproducibleOutsideTheJvm)
                        put("claim", figure.claim)
                        putJsonArray("documents") { figure.documents.forEach { add(it) } }
                        put("note", figure.note)
                    }
                }
            }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), document) + "\n"
    }

    private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
        it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
            it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
    }

    private companion object {
        private val PRETTY = Json { prettyPrint = true }
        private const val UPDATE_PROPERTY = "kmemo.updateGuardSpec"
        private val DOCUMENTS = listOf("README.md", "docs/MEASUREMENTS.md")

        private const val CORPUS_COMMAND =
            "./gradlew :kmemo-core:jvmTest --tests '*CorpusTest*' --tests '*QqpCorpusTest*' " +
                "--tests '*ExternalCorpusTest*'"
        private const val FLOOR_COMMAND =
            "./gradlew :kmemo-core:jvmTest --tests '*SubstitutionFloorTest*'"

        private const val ABOUT =
            "Every figure the published documents carry, with the command that produces it and, " +
                "where this build can render it, the exact line the document must contain. " +
                "tools/figures/check.py re-runs the document half with no Gradle and no Kotlin; " +
                "tools/corpus-runner recomputes the corpus figures from the published specification " +
                "with no JVM at all."

        /**
         * The figures this check registers and does not render.
         *
         * Each needs something this test cannot reach: a fetched dataset, a Python harness, a native
         * target, or a model. Listing them is the honest half of the coverage claim, and the reason
         * each is here rather than covered is a change to the test that owns it.
         */
        private val UNCOVERED = listOf(
            "verifier.catchRate" to
                "./gradlew :kmemo-core:jvmTest --tests '*VerifierCatchRateTest*'",
            "verifier.cost" to "./gradlew :kmemo-core:jvmTest --tests '*VerifierCostTest*'",
            "comparison.gptcache" to
                "./gradlew :kmemo-core:jvmTest --tests '*ComparativeBenchmarkTest*'",
            "register.preset" to
                "./gradlew :kmemo-core:jvmTest --tests '*GuardRegisterTest*' --tests '*RegisterPresetTest*'",
            "length.buckets" to "./gradlew :kmemo-core:jvmTest --tests '*GuardLengthTest*'",
            "paws.target" to "./gradlew :kmemo-core:jvmTest --tests '*PawsTargetTest*'",
            "admission.workload" to "./gradlew :kmemo-core:jvmTest --tests '*AdmissionWorkloadTest*'",
            "rag.pipeline" to "./gradlew :examples:test --tests '*RagPipelineTest*'",
            "ondevice.embedding" to "./gradlew :kmemo-core:macosArm64Test",
            "guards.chainCost" to "./gradlew :kmemo-core:jvmTest --tests '*GuardChainCostTest*'",
        )
    }
}
