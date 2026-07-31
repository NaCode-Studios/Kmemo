package dev.kmemo.guard

import java.util.Locale

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M12 — the language packs, *measured*: for each of Italian, Spanish, German and French, the curated
 * near-misses are caught and the paraphrases are kept.
 *
 * This is the same bar the English guards clear, at a smaller scale: the packs are conservative, so the
 * point is not parity with the heavily-tuned English list but that a shipped pack does not wave through
 * a near-miss it names a marker for, and does not reject an ordinary reworking. The guards run directly
 * on text — no embedder — since a pack is about lexical markers, not vectors.
 */
class LocalizedGuardsTest {

    // A near-miss slice: pairs that need different answers, each with the marker that should catch it.
    // A paraphrase slice: pairs that mean the same thing and must survive every guard.

    @Test
    fun `italian near-misses are caught and paraphrases are kept`() {
        val guards = MatchGuards.standard(Vocabularies.ITALIAN)

        assertAllRejected(
            guards,
            "Converti 100 dollari in euro" to "Converti 250 dollari in euro", // numeric
            "Converti 50 euro in dollari" to "Converti 50 euro in sterline", // unit / currency
            "Come abilitare la cache" to "Come disabilitare la cache", // antonym
            "Quali cibi mangiare prima di correre" to "Quali cibi non mangiare prima di correre", // negation
            "Che tempo fa oggi" to "Che tempo fa domani", // temporal
            "Qual è la capitale dell'Austria" to "Qual è la capitale dell'Australia", // entity
        )

        assertAllKept(
            guards,
            "Come si inverte una lista in Python" to "Come invertire una lista in Python",
            "Qual è la capitale della Francia" to "Dimmi la capitale della Francia",
            "Come posso ordinare un array" to "Come ordino un array",
        )
    }

    @Test
    fun `spanish near-misses are caught and paraphrases are kept`() {
        val guards = MatchGuards.standard(Vocabularies.SPANISH)

        assertAllRejected(
            guards,
            "Convierte 100 dólares a euros" to "Convierte 250 dólares a euros", // numeric
            "Convierte 50 euros a dólares" to "Convierte 50 euros a libras", // unit / currency
            "Cómo activar la caché" to "Cómo desactivar la caché", // antonym
            "Qué alimentos comer antes de correr" to "Qué alimentos no comer antes de correr", // negation
            "Qué tiempo hace hoy" to "Qué tiempo hace mañana", // temporal
        )

        assertAllKept(
            guards,
            "Cómo invertir una lista en Python" to "Cómo se invierte una lista en Python",
            "Cuál es la capital de Francia" to "Dime la capital de Francia",
        )
    }

    @Test
    fun `german near-misses are caught and paraphrases are kept`() {
        val guards = MatchGuards.standard(Vocabularies.GERMAN)

        assertAllRejected(
            guards,
            "Konvertiere 100 Dollar in Euro" to "Konvertiere 250 Dollar in Euro", // numeric
            "Wie kann ich den Cache aktivieren" to "Wie kann ich den Cache deaktivieren", // antonym
            "Welche Lebensmittel vor dem Laufen essen" to "Welche Lebensmittel nicht vor dem Laufen essen", // negation
            "Wie ist das Wetter heute" to "Wie ist das Wetter morgen", // temporal
        )

        assertAllKept(
            guards,
            "Wie kehre ich eine Liste in Python um" to "Wie kann ich eine Liste in Python umkehren",
            "Was ist die Hauptstadt von Frankreich" to "Nenne die Hauptstadt von Frankreich",
        )
    }

    @Test
    fun `french near-misses are caught and paraphrases are kept`() {
        val guards = MatchGuards.standard(Vocabularies.FRENCH)

        assertAllRejected(
            guards,
            "Convertis 100 dollars en euros" to "Convertis 250 dollars en euros", // numeric
            "Convertis 50 euros en dollars" to "Convertis 50 euros en livres", // unit / currency
            "Comment activer le cache" to "Comment désactiver le cache", // antonym
            "Quels aliments manger avant de courir" to "Quels aliments ne pas manger avant de courir", // negation
            "Quel temps fait-il demain" to "Quel temps fait-il hier", // temporal
        )

        assertAllKept(
            guards,
            "Comment inverser une liste en Python" to "Comment inverse-t-on une liste en Python",
            "Quelle est la capitale de la France" to "Donne la capitale de la France",
        )
    }

    @Test
    fun `standard(locale) resolves the pack by language code`() {
        // Same guard names as the vocabulary overload — the Locale path is just a lookup.
        assertEquals(
            MatchGuards.standard(Vocabularies.ITALIAN).map { it.name },
            MatchGuards.standard(Locale.ITALIAN).map { it.name },
        )
    }

    @Test
    fun `an unsupported locale fails loudly`() {
        val error = runCatching { MatchGuards.standard(Locale.JAPANESE) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected a clear error, got $error")
        assertTrue(error.message.orEmpty().contains("ja"), "the message should name the missing language")
    }

    private fun assertAllRejected(guards: List<MatchGuard>, vararg pairs: Pair<String, String>) {
        for ((query, cached) in pairs) {
            assertTrue(
                rejects(guards, query, cached),
                "expected a guard to reject this near-miss:\n  query:  $query\n  cached: $cached",
            )
        }
    }

    private fun assertAllKept(guards: List<MatchGuard>, vararg pairs: Pair<String, String>) {
        for ((query, cached) in pairs) {
            val firing = guards.firstOrNull { it.evaluate(query, cached) is GuardVerdict.Reject }
            assertTrue(
                firing == null,
                "guard '${firing?.name}' wrongly rejected this paraphrase:\n  query:  $query\n  cached: $cached",
            )
        }
    }

    private fun rejects(guards: List<MatchGuard>, query: String, cached: String): Boolean =
        guards.any { it.evaluate(query, cached) is GuardVerdict.Reject }
}
