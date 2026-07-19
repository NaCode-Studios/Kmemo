package dev.kmemo.guard

/** Tokenization helpers shared by the built-in guards. */
internal object Text {

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** Capitalized by grammar rather than by reference. English has exactly one that matters. */
    private val NON_ENTITY_CAPITALS = setOf("i")

    /** A colon or semicolon never ends a sentence, so neither belongs here. */
    private val SENTENCE_END = setOf('.', '?', '!')

    /** Words whose trailing period abbreviates rather than terminates. */
    private val ABBREVIATIONS = setOf(
        "vs", "etc", "eg", "ie", "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr",
        "inc", "ltd", "co", "fig", "vol", "no", "approx", "al",
    )

    /** All word tokens, lowercased, in order. */
    fun tokens(text: String): List<String> =
        TOKEN.findAll(text).map { it.value.lowercase() }.toList()

    /** Tokens with [stopwords] removed, in order, duplicates dropped (first occurrence wins). */
    fun contentTokens(text: String, stopwords: Set<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (token in tokens(text)) {
            if (token !in stopwords) seen.add(token)
        }
        return seen.toList()
    }

    /**
     * Tokens that look like named entities: capitalized, minus the words that are capitalized purely
     * by grammar. `France`, `GitHub` and `OAuth` qualify in "what is the capital of France"; the
     * leading `What` does not.
     *
     * Deciding which capitals are grammar is the whole difficulty, and both obvious answers are
     * wrong.
     *
     * Treating `.` `:` `;` as sentence boundaries disables the guard on exactly the prompts it
     * exists for: "Compare Python vs. Java" loses `Java` to the abbreviation period, and a templated
     * "Country: Austria. Give me the capital." loses `Austria` to the colon — the one field that
     * varies between two prompts.
     *
     * Exempting only the very first token of the prompt goes wrong in the other direction: the
     * opening word of every *later* sentence becomes an entity, so "…in CSS? Show me an example."
     * and "…in CSS? Give me an example." look like an entity swap and a genuine paraphrase is
     * refused.
     *
     * So: only `.` `?` `!` end a sentence, never `:` or `;`; and a period does not end one when the
     * word before it is an abbreviation or a single letter, which covers `vs.`, `e.g.` and initials.
     *
     * Returned lowercased, so `GitHub` and `Github` are the same entity.
     */
    fun entityTokens(text: String): Set<String> {
        val matches = TOKEN.findAll(text).toList()
        val result = LinkedHashSet<String>()
        for ((index, match) in matches.withIndex()) {
            val token = match.value
            if (index == 0) continue
            if (token.length < 2) continue
            if (!token.first().isUpperCase()) continue
            if (token.lowercase() in NON_ENTITY_CAPITALS) continue
            if (opensSentence(text, matches, index)) continue
            result.add(token.lowercase())
        }
        return result
    }

    private fun opensSentence(text: String, matches: List<MatchResult>, index: Int): Boolean {
        val previous = matches[index - 1]
        val between = text.substring(previous.range.last + 1, matches[index].range.first)
        val terminator = between.firstOrNull { it in SENTENCE_END } ?: return false
        if (terminator != '.') return true

        val previousToken = previous.value.lowercase()
        return previousToken.length > 1 && previousToken !in ABBREVIATIONS
    }

    /**
     * Whether [a] and [b] are one typo apart: a single insertion, deletion, substitution, or a swap
     * of two adjacent characters.
     *
     * Transpositions are included because they are the most common way people mistype — `clera` for
     * `clear`, `cahce` for `cache` — and plain edit distance scores them as two changes, which would
     * put ordinary typos out of reach.
     *
     * Deliberately capped at one edit. At two, `Austria` and `Australia` collapse into the same
     * token and the guard built on top of this waves through the exact swap it exists to catch.
     */
    fun withinOneTypo(a: String, b: String): Boolean {
        if (a == b) return true
        val lengthDelta = a.length - b.length
        if (lengthDelta > 1 || lengthDelta < -1) return false

        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        val sameLength = shorter.length == longer.length

        var i = 0
        while (i < shorter.length && shorter[i] == longer[i]) i++
        if (i == shorter.length) return true

        if (sameLength &&
            i + 1 < shorter.length &&
            shorter[i] == longer[i + 1] &&
            shorter[i + 1] == longer[i]
        ) {
            // Adjacent swap: skip both characters and require the rest to be identical.
            return shorter.regionMatches(i + 2, longer, i + 2, shorter.length - i - 2)
        }

        // Substitution keeps both cursors aligned; an insertion advances only the longer one.
        val shorterRest = if (sameLength) i + 1 else i
        return shorter.regionMatches(shorterRest, longer, i + 1, shorter.length - shorterRest)
    }
}
