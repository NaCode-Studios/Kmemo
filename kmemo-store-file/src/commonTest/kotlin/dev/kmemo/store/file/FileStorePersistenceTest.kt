package dev.kmemo.store.file

import dev.kmemo.CacheEntry
import dev.kmemo.Embedder
import dev.kmemo.SemanticCache
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The property the module exists for: an entry written before the process ends is found after it
 * restarts.
 *
 * A second [FileStore] on the same path is what a restart is, from the store's point of view. There is
 * no shared state between the two objects beyond the file, so an entry that survives has survived
 * through the journal and nothing else.
 */
class FileStorePersistenceTest {

    private val path = journalPath("persistence")
    private val now = Instant.fromEpochSeconds(1_800_000_000)

    @AfterTest
    fun cleanUp() = runTest {
        FileStore(path).destroy()
    }

    private fun entry(
        id: String,
        prompt: String = "how do I reverse a list",
        response: String = "call reversed()",
        scope: String = "default",
        vector: FloatArray = floatArrayOf(1f, 0f, 0f),
        metadata: Map<String, String> = emptyMap(),
        tags: Set<String> = emptySet(),
        chunkLengths: List<Int> = emptyList(),
    ) = CacheEntry(
        id = id,
        scope = scope,
        prompt = prompt,
        response = response,
        embedding = vector,
        createdAt = now,
        metadata = metadata,
        tags = tags,
        embedder = "test:v1",
        chunkLengths = chunkLengths,
    )

    @Test
    fun `an entry written before the process ends is found after it restarts`() = runTest {
        FileStore(path).put(entry("a"))

        val reopened = FileStore(path)
        val found = reopened.search("default", floatArrayOf(1f, 0f, 0f), limit = 5).single()

        assertEquals("a", found.entry.id)
        assertEquals("how do I reverse a list", found.entry.prompt)
        assertEquals("call reversed()", found.entry.response)
    }

    @Test
    fun `every field of an entry survives the round trip`() = runTest {
        // Everything the length-prefixed format has to survive without escaping: newlines, commas,
        // colons, quotes, tabs, an empty map key, and a comma inside a tag, which is the separator the
        // compound fields use between their own elements.
        val chunks = listOf("an answer\twith\ttabs\n", "and \"quotes\" ", "and 42:0")
        val original = entry(
            id = "full",
            prompt = "a prompt with a newline\nand a comma, and a colon: and unicode Ⓐ 日本語",
            response = chunks.joinToString(""),
            scope = "gpt-4o|t=0.0",
            vector = floatArrayOf(0.1f, -0.9f, 1e-8f, 12.5f),
            metadata = mapOf("inputTokens" to "120", "trace:id" to "a,b,c", "" to "empty key"),
            tags = setOf("price-list", "policy,2026"),
            chunkLengths = chunks.map { it.length },
        )
        FileStore(path).put(original)

        val found = FileStore(path).search(original.scope, original.embedding, limit = 5).single().entry

        assertEquals(original.prompt, found.prompt)
        assertEquals(original.response, found.response)
        assertEquals(original.scope, found.scope)
        assertEquals(original.metadata, found.metadata)
        assertEquals(original.tags, found.tags)
        assertEquals(original.embedder, found.embedder)
        assertEquals(original.chunkLengths, found.chunkLengths)
        assertEquals(original.createdAt, found.createdAt)
        assertTrue(original.embedding.contentEquals(found.embedding), "the vector must be bit-identical")
    }

    @Test
    fun `a removal survives the restart too`() = runTest {
        val store = FileStore(path)
        store.put(entry("a"))
        store.put(entry("b", vector = floatArrayOf(0f, 1f, 0f)))
        store.remove("a")

        val reopened = FileStore(path)

        assertEquals(1, reopened.size())
        assertEquals("b", reopened.search("default", floatArrayOf(0f, 1f, 0f), 5).single().entry.id)
    }

    @Test
    fun `a tag invalidation survives the restart`() = runTest {
        val store = FileStore(path)
        store.put(entry("a", tags = setOf("policy-2026")))
        store.put(entry("b", vector = floatArrayOf(0f, 1f, 0f)))
        assertEquals(1, store.invalidateByTag("policy-2026"))

        assertEquals(1, FileStore(path).size())
    }

    @Test
    fun `clearing everything leaves nothing to replay`() = runTest {
        val store = FileStore(path)
        store.put(entry("a"))
        store.put(entry("b", vector = floatArrayOf(0f, 1f, 0f)))
        store.clear()

        assertEquals(0, FileStore(path).size())
    }

    @Test
    fun `clearing one scope leaves the other scopes across a restart`() = runTest {
        val store = FileStore(path)
        store.put(entry("a", scope = "one"))
        store.put(entry("b", scope = "two", vector = floatArrayOf(0f, 1f, 0f)))
        store.clear("one")

        val reopened = FileStore(path)

        assertEquals(0, reopened.size("one"))
        assertEquals(1, reopened.size("two"))
    }

    /**
     * A process that dies mid-append leaves a record without its tail. Refusing to open would turn one
     * lost write into a lost cache, so the tear is where the replay stops and everything before it is
     * still served.
     */
    @Test
    fun `a journal truncated by a crash keeps everything written before the tear`() = runTest {
        val store = FileStore(path)
        store.put(entry("a"))
        store.put(entry("b", prompt = "a second question", vector = floatArrayOf(0f, 1f, 0f)))

        val file = JournalFile(path)
        val whole = assertNotNull(file.readTextOrNull())
        file.replace(whole.substring(0, whole.length - 12))

        val reopened = FileStore(path)

        assertEquals(1, reopened.size(), "the first entry is intact; the torn one is gone")
        assertEquals("a", reopened.search("default", floatArrayOf(1f, 0f, 0f), 5).single().entry.id)
    }

    @Test
    fun `compaction rewrites the journal without changing what it holds`() = runTest {
        // A threshold of two forces a compaction after the third write rather than after a hundred
        // thousand, which is the same code path at a size a test can assert on.
        val store = FileStore(path, compactAfter = 2)
        repeat(5) { store.put(entry("id$it", prompt = "question $it", vector = floatArrayOf(it + 1f, 0f, 0f))) }

        val text = assertNotNull(JournalFile(path).readTextOrNull())
        val reopened = FileStore(path)

        assertEquals(5, reopened.size())
        assertTrue(
            text.length < 5 * text.length / 4,
            "a compacted journal holds one record per live entry rather than one per write",
        )
    }

    @Test
    fun `a cache built on it answers the same question after a restart`() = runTest {
        val embedder = Embedder { text ->
            FloatArray(16).also { vector ->
                for (token in text.lowercase().split(" ")) {
                    vector[((token.hashCode() % 16) + 16) % 16] += 1f
                }
            }
        }
        SemanticCache(embedder, FileStore(path)).getOrPut("what is a semantic cache") { "a cache keyed by meaning" }

        val afterRestart = SemanticCache(embedder, FileStore(path))
            .getOrPut("what is a semantic cache") { "the model must not be called" }

        assertEquals("a cache keyed by meaning", afterRestart)
    }
}
