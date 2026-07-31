package dev.kmemo.guard

import java.util.Locale

/**
 * `java.util.Locale` conveniences for the guard vocabularies.
 *
 * `Locale` is a JVM type, and the guards themselves know nothing about it — they read an ISO 639
 * language code, which every platform can produce. These two exist so JVM callers who already hold a
 * `Locale` do not have to unpack it, and they live apart from the rest because they are the only part
 * of the guard layer that cannot leave the JVM.
 *
 * They are extension functions rather than members, which is what lets the objects they extend stay
 * platform-neutral. That costs an import:
 *
 * ```kotlin
 * import dev.kmemo.guard.standard
 *
 * val cache = SemanticCache(embedder, guards = MatchGuards.standard(Locale.ITALIAN))
 * ```
 */
public fun MatchGuards.standard(locale: Locale): List<MatchGuard> =
    standard(Vocabularies.forLocale(locale))

/**
 * The pack for [locale]'s language.
 *
 * Matched on the language alone, so `Locale("it", "CH")` and [Locale.ITALIAN] both resolve to
 * [Vocabularies.ITALIAN].
 *
 * @throws IllegalArgumentException if no pack ships for the language — see [Vocabularies.forLanguage].
 */
public fun Vocabularies.forLocale(locale: Locale): GuardVocabulary = forLanguage(locale.language)
