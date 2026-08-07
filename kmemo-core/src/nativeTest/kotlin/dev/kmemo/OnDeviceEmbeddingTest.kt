package dev.kmemo

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * M41: what an embedding would cost if it ran on the device, measured on a native target.
 *
 * [Embedder]'s documentation names four places to get an implementation. Three are network providers
 * and the fourth, a local ONNX model, runs only on the JVM. The consequence was never written down: on
 * the native targets and on wasm an embedder is always a network call, so a cache that exists to avoid
 * a round trip to a model needs a round trip to an embedding API before it can decide whether to serve.
 * The latency argument disappears entirely and only the token-cost argument survives.
 *
 * ### What this measures, and what it does not
 *
 * It runs the **arithmetic** of one forward pass of a small sentence transformer, at the shape of
 * `all-MiniLM-L6-v2`: 6 layers, hidden size 384, 4 attention heads, feed-forward 1,536, over a
 * 32-token sequence. Weights are random and the outputs are meaningless; every multiply and add a real
 * forward pass would do is done, in ordinary Kotlin, on the target's own CPU.
 *
 * It is not a model, so it says nothing about accuracy, and it is not an inference runtime, so it says
 * nothing about what ONNX Runtime or an Accelerate-backed kernel would achieve on the same hardware.
 * What it establishes is the floor: whether the arithmetic is even in the right order of magnitude for
 * a device, in the one language that compiles to every target this library publishes.
 *
 * The number it prints and the decision that follows are in the README under on-device embedding.
 */
class OnDeviceEmbeddingTest {

    @Test
    fun `measure one forward pass at MiniLM shape`() {
        val model = MiniLmShape()

        // One untimed pass first: on a native target this pays for the first page faults on the weight
        // arrays, and timing those would measure the allocator rather than the arithmetic.
        model.forward()

        var fastest = Double.MAX_VALUE
        repeat(RUNS) {
            val start = TimeSource.Monotonic.markNow()
            model.forward()
            fastest = minOf(fastest, start.elapsedNow().inWholeMicroseconds / 1_000.0)
        }

        val flops = model.flops
        println()
        println("On-device embedding, arithmetic only, at all-MiniLM-L6-v2 shape")
        println("  sequence            $TOKENS tokens")
        println("  parameters          ${model.parameters / 1_000_000} million")
        println("  work per call       ${flops / 1_000_000} MFLOP")
        println("  fastest of $RUNS      ${fastest.toInt()} ms")
        println("  throughput          ${(flops / 1_000.0 / fastest).toInt()} MFLOP/s")
        println("  encoder weights     ${model.parameters * 4 / 1_000_000} MB fp32, " +
            "${model.parameters / 1_000_000} MB int8")
        println("  plus the vocabulary table, $VOCABULARY x $HIDDEN, which is a lookup rather than")
        println("  arithmetic: ${VOCABULARY.toLong() * HIDDEN * 4 / 1_000_000} MB fp32 on top.")
        println()

        assertTrue(fastest > 0.0, "the pass has to take measurable time or it did not run")
    }

    /**
     * The multiply-accumulate work of one transformer encoder forward pass, and nothing else.
     *
     * Attention scores, softmax and layer norms are left out deliberately: they are a low single-digit
     * share of the arithmetic at this shape, and including them would trade a number anybody can check
     * against the parameter count for one nobody can.
     */
    private class MiniLmShape {

        private val weights = FloatArray(HIDDEN * FEED_FORWARD) { (it % 17) / 17.0f - 0.5f }
        private val input = FloatArray(TOKENS * HIDDEN) { (it % 13) / 13.0f - 0.5f }
        private val output = FloatArray(TOKENS * FEED_FORWARD)

        /** Weight count of the encoder stack, which is what a phone would have to carry. */
        val parameters: Long = LAYERS.toLong() * (4L * HIDDEN * HIDDEN + 2L * HIDDEN * FEED_FORWARD)

        /** Two floating point operations per weight per token: one multiply, one add. */
        val flops: Long = 2L * parameters * TOKENS

        fun forward() {
            repeat(LAYERS) {
                // Four projections of hidden x hidden (query, key, value, output) and two of
                // hidden x feed-forward. Run as six passes over the same buffers, because what is being
                // measured is the multiply-accumulate throughput and not the memory layout of a real
                // implementation.
                repeat(4) { matmul(HIDDEN) }
                repeat(2) { matmul(FEED_FORWARD) }
            }
        }

        private fun matmul(columns: Int) {
            for (token in 0 until TOKENS) {
                val rowOffset = token * HIDDEN
                for (column in 0 until columns) {
                    var sum = 0.0f
                    val weightOffset = column * HIDDEN
                    for (index in 0 until HIDDEN) {
                        sum += input[rowOffset + index] * weights[weightOffset + index]
                    }
                    output[token * columns + column] = max(0.0f, sum)
                }
            }
        }
    }

    private companion object {
        private const val LAYERS = 6
        private const val HIDDEN = 384
        private const val FEED_FORWARD = 1_536
        private const val TOKENS = 32
        private const val VOCABULARY = 30_522
        private const val RUNS = 3
    }
}
