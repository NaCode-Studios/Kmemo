package dev.kmemo.guard

/**
 * The complete set of word lists the standard guards read from, bundled so a whole language can be
 * swapped in one object.
 *
 * Every guard already takes its markers as a constructor parameter, so adapting kmemo to another
 * language was always *possible* — this makes it *one line*. [MatchGuards.standard] takes a
 * `GuardVocabulary`, and [dev.kmemo.guard.Vocabularies] ships curated ones for the highest-traffic
 * languages. Build your own from a language's traffic when a shipped pack does not fit; the same
 * conservative rule applies as for English — a marker earns its place only if it will not reject a
 * genuine paraphrase.
 *
 * @param stopwords function words removed before comparison; read by most guards.
 * @param sentenceOpeners words that may open a sentence without naming anything (the entity guard).
 * @param nonEntityCapitals words capitalized by grammar, not reference (the entity guard).
 * @param negationMarkers words that negate (the negation guard).
 * @param antonyms symmetric pairs that flip an answer (the antonym guard).
 * @param temporalMarkers absolute time references (the temporal guard).
 * @param scopeMarkers words describing the shape of the answer — format, length, depth (the scope guard).
 * @param directionalCues cues that make argument order significant — comparisons, conversions (the direction guard).
 * @param units unit and currency tokens mapped to a canonical [MeasurementUnit] (the unit & substitution guards).
 * @param qualifierOpeners words that begin a clause narrowing a question rather than framing it (the
 *   sub-span guard). **Empty by default, and empty is a working answer**: with no openers the guard
 *   never fires, which is the safe direction for a language whose markers nobody has measured. Only the
 *   English pack fills it, because it is the only one with a corpus behind it. Fill it from a
 *   language's real traffic, not from a dictionary.
 */
public data class GuardVocabulary(
    public val stopwords: Set<String>,
    public val sentenceOpeners: Set<String>,
    public val nonEntityCapitals: Set<String>,
    public val negationMarkers: Set<String>,
    public val antonyms: Set<Pair<String, String>>,
    public val temporalMarkers: Set<String>,
    public val scopeMarkers: Set<String>,
    public val directionalCues: Set<String>,
    public val units: Map<String, MeasurementUnit>,
    public val qualifierOpeners: Set<String> = emptySet(),
) {
    public companion object {

        /**
         * The English pack — the historical default, drawn from [Vocabulary]. [MatchGuards.standard]
         * with no argument uses exactly this, so nothing changes for existing callers.
         */
        public val ENGLISH: GuardVocabulary = GuardVocabulary(
            stopwords = Vocabulary.STOPWORDS,
            sentenceOpeners = Vocabulary.SENTENCE_OPENERS,
            nonEntityCapitals = Vocabulary.NON_ENTITY_CAPITALS,
            negationMarkers = Vocabulary.NEGATION_MARKERS,
            antonyms = Vocabulary.ANTONYMS,
            temporalMarkers = Vocabulary.TEMPORAL_MARKERS,
            scopeMarkers = Vocabulary.SCOPE_MARKERS,
            directionalCues = Vocabulary.DIRECTIONAL_CUES,
            units = Vocabulary.UNITS,
            qualifierOpeners = Vocabulary.QUALIFIER_OPENERS,
        )
    }
}
