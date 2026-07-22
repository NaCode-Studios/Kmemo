package dev.kmemo.springai

import dev.kmemo.Embedder
import dev.kmemo.SemanticCache
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.CallAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import kotlin.test.Test
import kotlin.test.assertEquals

class KmemoAdvisorTest {

    private val embedder = Embedder { text ->
        val vector = FloatArray(64)
        val tokens = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) vector[0] = 1.0f
        for (token in tokens) vector[Math.floorMod(token.hashCode(), 64)] += 1.0f
        vector
    }

    @Test
    fun `a hit is served without calling the model`() {
        val advisor = KmemoAdvisor(SemanticCache(embedder))
        val chain = FakeChain(response("Paris"))
        val request = request("What is the capital of France?")

        val first = advisor.adviseCall(request, chain)
        val second = advisor.adviseCall(request, chain)

        assertEquals(1, chain.calls, "the second, identical prompt must be served from cache")
        assertEquals("Paris", first.chatResponse()?.result?.output?.text)
        assertEquals("Paris", second.chatResponse()?.result?.output?.text)
    }

    @Test
    fun `a numeric near-miss is not served from cache`() {
        val advisor = KmemoAdvisor(SemanticCache(embedder, threshold = 0.5))
        val chain = FakeChain(response("about 92 EUR"))

        advisor.adviseCall(request("Convert 100 USD to EUR"), chain)
        advisor.adviseCall(request("Convert 250 USD to EUR"), chain)

        assertEquals(2, chain.calls, "the numeric guard must stop the near-miss from hitting")
    }

    private fun request(text: String): ChatClientRequest =
        ChatClientRequest(Prompt(text), emptyMap<String, Any>())

    private fun response(text: String): ChatClientResponse =
        ChatClientResponse(ChatResponse(listOf(Generation(AssistantMessage(text)))), emptyMap<String, Any>())

    private class FakeChain(private val response: ChatClientResponse) : CallAdvisorChain {
        var calls: Int = 0
            private set

        override fun nextCall(request: ChatClientRequest): ChatClientResponse {
            calls++
            return response
        }

        override fun getCallAdvisors(): List<CallAdvisor> = emptyList()

        // Spring AI 2.0 added copy() to the chain SPI; this test double never forks, so it returns itself.
        override fun copy(advisor: CallAdvisor): CallAdvisorChain = this
    }
}
