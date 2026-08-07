package dev.kmemo.fixtures

/**
 * The four committed and fetched splits run from 19 to 214 characters. This is how the guards are
 * measured past that.
 *
 * **What it is.** Every pair keeps its two prompts and its label, and both sides are wrapped in the
 * *same* retrieved-context envelope: a fixed instruction, a block of passages, and the original prompt
 * as the question. The envelope is byte-identical on the two sides, so the only difference between the
 * long prompts is still exactly the difference between the short ones, and the pair's label carries
 * over unchanged. What changes is how much text that difference is buried in.
 *
 * That shape is not an arbitrary way to make a prompt longer. It is the commonest long prompt in
 * production: retrieval-augmented generation puts a short question at the end of a wall of retrieved
 * passages, and it is the audience M28 named as running guards nobody had measured for them.
 *
 * **What it is not.** It is derived, not independent. It contains no near miss anybody wrote and no
 * paraphrase anybody judged: it inherits every label from its source. So it can say whether a guard's
 * behaviour changes when the same evidence is diluted, and it cannot be quoted as a fifth measurement
 * of how well the guards do. Reports print it under its own heading for that reason.
 *
 * **The passages are real text, not filler anybody wrote.** They are the `a` sides of other pairs in
 * the same source split, taken in index order from just after the pair being wrapped. Real sentences
 * from the same distribution, deterministic, and — because both sides get the identical block — unable
 * to introduce a difference of their own for a guard to find.
 */
object LongPromptCorpus {

    private const val INSTRUCTION =
        "Use only the context below to answer the question. If the context does not contain the " +
            "answer, say that you do not know."

    /**
     * [source] with every pair wrapped in an envelope of at least [targetChars] characters.
     *
     * @param name what the derived split is called in a report.
     * @param targetChars how long each prompt should be, in characters. Passages are added whole, so
     *   the result overshoots rather than cutting a sentence in half.
     */
    fun derive(source: List<CorpusPair>, name: String, targetChars: Int): List<CorpusPair> {
        require(source.size >= 2) { "need at least two pairs to draw passages from, had ${source.size}" }
        require(targetChars > 0) { "targetChars must be positive, was $targetChars" }
        return source.mapIndexed { index, pair ->
            val passages = passagesFor(source, index, targetChars)
            CorpusPair(
                a = envelope(passages, pair.a),
                b = envelope(passages, pair.b),
                shouldMatch = pair.shouldMatch,
                category = pair.category,
            )
        }
    }

    /** The derived splits the reports use: a short RAG prompt, a medium one, and a long one. */
    fun ladder(source: List<CorpusPair>, sourceName: String): List<Pair<String, List<CorpusPair>>> =
        listOf(512, 1024, 2048).map { target ->
            "$sourceName+rag$target" to derive(source, "$sourceName+rag$target", target)
        }

    private fun envelope(passages: List<String>, question: String): String =
        buildString {
            append(INSTRUCTION)
            append("\n\nContext:\n")
            for (passage in passages) {
                append("- ")
                append(passage)
                append('\n')
            }
            append("\nQuestion: ")
            append(question)
        }

    /**
     * Passages drawn from [source] starting just after [index], enough to reach [targetChars].
     *
     * Wraps around the end of the list and skips the pair being wrapped, so a prompt never quotes
     * itself as its own retrieved context — which would hand [dev.kmemo.guard.SubSpanGuard] a
     * containment that the source pair never had.
     */
    private fun passagesFor(source: List<CorpusPair>, index: Int, targetChars: Int): List<String> {
        val passages = ArrayList<String>()
        var length = INSTRUCTION.length + source[index].a.length
        var step = 1
        while (length < targetChars && step <= source.size) {
            val candidate = source[(index + step) % source.size]
            step++
            if (candidate === source[index]) continue
            passages += candidate.a
            length += candidate.a.length + 3
        }
        return passages
    }
}
