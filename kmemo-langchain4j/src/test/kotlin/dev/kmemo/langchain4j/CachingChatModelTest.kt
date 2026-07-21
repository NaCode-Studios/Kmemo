package dev.kmemo.langchain4j

import dev.kmemo.Embedder
import dev.kmemo.SemanticCache
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class CachingChatModelTest {

    private val embedder = Embedder { text ->
        val vector = FloatArray(64)
        val tokens = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) vector[0] = 1.0f
        for (token in tokens) vector[Math.floorMod(token.hashCode(), 64)] += 1.0f
        vector
    }

    @Test
    fun `a hit is served without calling the delegate model`() {
        val delegate = CountingModel("Paris")
        val model = CachingChatModel(delegate, SemanticCache(embedder))

        val first = model.chat("What is the capital of France?")
        val second = model.chat("What is the capital of France?")

        assertEquals(1, delegate.calls, "the second, identical request must be served from cache")
        assertEquals("Paris", first)
        assertEquals("Paris", second)
    }

    @Test
    fun `a numeric near-miss is not served from cache`() {
        val delegate = CountingModel("about 92 EUR")
        val model = CachingChatModel(delegate, SemanticCache(embedder, threshold = 0.5))

        model.chat("Convert 100 USD to EUR")
        model.chat("Convert 250 USD to EUR")

        assertEquals(2, delegate.calls, "the numeric guard must stop the near-miss from hitting")
    }

    private class CountingModel(private val reply: String) : ChatModel {
        var calls: Int = 0
            private set

        override fun chat(request: ChatRequest): ChatResponse {
            calls++
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build()
        }
    }
}
