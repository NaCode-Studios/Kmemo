package dev.kmemo

import dev.kmemo.fixtures.ConceptEmbedder
import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.store.EncryptedStore
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M33: prompts a regulated team is allowed to store.
 *
 * A clinical, legal or financial deployment cannot put a database full of verbatim user questions in
 * front of an auditor, and the only answers this library used to offer were to veto the write, which
 * means not caching, or to encrypt the whole database at rest, which protects nothing from anyone who
 * can read the database. So the cache did not reach the buyers whose wrong answers cost the most,
 * which is an odd place for a library whose entire argument is about not serving wrong answers.
 *
 * The four properties: nothing readable is persisted, the guards decide exactly as they did before,
 * the read path pays one decryption per candidate and the count is knowable, and a deterministic
 * cipher is refused rather than documented against.
 */
class EncryptedStoreTest {

    /**
     * A toy cipher: a rotating shift with a random nonce, base-ten encoded. It is not cryptography and
     * is not offered as any, which is the point of the seam. What it is, is randomized and reversible,
     * which is the whole of the contract [EncryptedStore] depends on.
     */
    private class ToyCipher(override val identity: String = "toy/v1") : EntryCipher {
        var encryptions = 0
            private set
        var decryptions = 0
            private set
        private var nonce = 0

        override suspend fun encrypt(plaintext: String, scope: String): String {
            encryptions++
            val shift = ++nonce
            val shifted = plaintext.map { (it.code + shift).toChar() }.joinToString("")
            return "$shift:$shifted"
        }

        override suspend fun decrypt(ciphertext: String, scope: String): String {
            decryptions++
            val split = ciphertext.indexOf(':')
            val shift = ciphertext.substring(0, split).toInt()
            return ciphertext.substring(split + 1).map { (it.code - shift).toChar() }.joinToString("")
        }
    }

    /** The cipher the store must refuse: same plaintext in, same ciphertext out. */
    private class DeterministicCipher : EntryCipher {
        override suspend fun encrypt(plaintext: String, scope: String): String = plaintext.reversed()
        override suspend fun decrypt(ciphertext: String, scope: String): String = ciphertext.reversed()
    }

    private val prompt = "What is the maximum paediatric dose of ibuprofen?"
    private val response = "10 mg per kilogram, every six to eight hours."

    @Test
    fun `the delegate holds no readable prompt and no readable response`() = runTest {
        val inner = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), EncryptedStore(inner, ToyCipher()))
        cache.put(prompt, response)

        val stored = inner.search("default", HashingEmbedder().embed(prompt), 1).single().entry
        assertFalse(stored.prompt.contains("ibuprofen"), "the stored prompt is ${stored.prompt}")
        assertFalse(stored.response.contains("kilogram"), "the stored response is ${stored.response}")
    }

    @Test
    fun `and the cache still serves the plaintext back`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), EncryptedStore(InMemoryStore(), ToyCipher()))
        cache.put(prompt, response)

        val hit = cache.lookup(prompt)

        assertTrue(hit is CacheLookup.Hit)
        assertEquals(response, hit.response)
        assertEquals(prompt, hit.matchedPrompt, "a hit can still say what it matched")
    }

    /**
     * The exit criterion that matters most. Encryption is worth nothing if it costs the guards their
     * verdicts, because the guards are what stops the cache serving a wrong answer.
     */
    @Test
    fun `the guard chain reaches the same verdicts it does without a cipher`() = runTest {
        for (store in listOf(InMemoryStore(), EncryptedStore(InMemoryStore(), ToyCipher()))) {
            val cache = SemanticCache(ConceptEmbedder(), store)
            cache.put("Convert 100 USD to EUR", "about 92 EUR")

            val refused = cache.lookup("Convert 250 USD to EUR")

            assertTrue(refused is CacheLookup.Miss, "the numeric guard refuses this pair either way")
            assertEquals(MissReason.REJECTED_BY_GUARD, refused.reason)
            assertEquals("numeric", refused.detail?.substringBefore(":"))
        }
    }

    /**
     * The cost, as a count. The duration is a property of the cipher a caller supplies, so multiplying
     * is arithmetic this library cannot do for them; the count is the part it can state exactly.
     */
    @Test
    fun `the read path decrypts once per candidate rather than once per lookup`() = runTest {
        val cipher = ToyCipher()
        val cache = SemanticCache(
            HashingEmbedder(),
            EncryptedStore(InMemoryStore(), cipher),
            threshold = 0.0,
            candidates = 3,
        )
        cache.put("first question about lists", "one")
        cache.put("second question about lists", "two")
        cache.put("third question about lists", "three")

        val before = cipher.decryptions
        cache.lookup("a question about lists")

        assertEquals(
            6,
            cipher.decryptions - before,
            "three candidates, each with a prompt and a response, and the guards need every prompt",
        )
    }

    @Test
    fun `a deterministic cipher is refused rather than documented against`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), EncryptedStore(InMemoryStore(), DeterministicCipher()))

        val failure = assertFailsWith<IllegalArgumentException> { cache.put(prompt, response) }

        assertTrue(failure.message.orEmpty().contains("leaks equality"))
    }

    @Test
    fun `chunk boundaries survive the round trip`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), EncryptedStore(InMemoryStore(), ToyCipher()))
        val chunks = listOf("10 mg ", "per ", "kilogram.")
        cache.getOrPutStreaming(prompt) { flowOf(*chunks.toTypedArray()) }.toList()
        cache.getOrPutStreaming(prompt) { flowOf(*chunks.toTypedArray()) }.toList()

        val replayed = cache.getOrPutStreaming(prompt) { error("must not be called") }.toList()

        assertEquals(chunks, replayed, "an encrypted store replays the chunks the caller saw")
    }

    @Test
    fun `tags still invalidate because tags are not user input`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), EncryptedStore(InMemoryStore(), ToyCipher()))
        cache.getOrPut("what does the policy say", emptyList(), setOf("policy-2026")) { "it says this" }

        assertEquals(1, cache.invalidateByTag("policy-2026"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `a response containing newlines survives the envelope`() = runTest {
        val multiline = "line one\nline two\nline three"
        val cache = SemanticCache(HashingEmbedder(), EncryptedStore(InMemoryStore(), ToyCipher()))
        cache.put(prompt, multiline)

        assertEquals(multiline, (cache.lookup(prompt) as CacheLookup.Hit).response)
    }
}
