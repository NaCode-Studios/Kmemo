package dev.kmemo.springai

import dev.kmemo.SemanticCache
import kotlinx.coroutines.runBlocking
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.CallAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import reactor.core.publisher.Flux

/**
 * A caching [org.springframework.ai.chat.client.advisor.api.Advisor] for Spring AI's `ChatClient`,
 * backed by a [SemanticCache].
 *
 * Spring AI has the advisor seam but ships no semantic cache, so this is the one-line win: register the
 * advisor and repeated-in-meaning prompts are served from the cache — with kmemo's guards keeping the
 * exchange rate for `250 USD` from being served to someone who asked about `100`.
 *
 * ```kotlin
 * val chatClient = ChatClient.builder(chatModel)
 *     .defaultAdvisors(KmemoAdvisor(cache))
 *     .build()
 * ```
 *
 * The prompt's rendered text ([org.springframework.ai.chat.prompt.Prompt.getContents]) is the cache
 * key. On a **hit** the advisor short-circuits the chain and returns the cached answer without calling
 * the model; on a **miss** it calls the model through the chain, caches the assistant's reply text, and
 * returns the model's real response untouched (metadata intact). Anything that changes what a correct
 * answer looks like — the model, temperature, the system prompt — belongs in the [scope], exactly as it
 * does for [SemanticCache].
 *
 * Only the blocking `call()` path is cached. `stream()` calls pass straight through — a streamed
 * response is not cached here — so the advisor is safe to register on a client used both ways.
 *
 * The cache is a `suspend` API and the advisor SPI is blocking, so lookups run inside `runBlocking` on
 * the calling request thread. That thread pays the embedding call on a miss; on a hit it pays one
 * embedding and skips the model entirely, which is the whole point.
 *
 * @param cache the semantic cache to serve from.
 * @param order the advisor's order in the chain; keep it early so a hit avoids the work behind it.
 * @param scope derives the cache scope from a request; constant [SemanticCache.DEFAULT_SCOPE] by default.
 */
public class KmemoAdvisor @JvmOverloads constructor(
    private val cache: SemanticCache,
    private val order: Int = 0,
    private val scope: (ChatClientRequest) -> String = { SemanticCache.DEFAULT_SCOPE },
) : CallAdvisor, StreamAdvisor {

    override fun getName(): String = "kmemo"

    override fun getOrder(): Int = order

    override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse {
        val query = request.prompt().contents
        val scopeKey = scope(request)
        return runBlocking {
            val cached = cache.get(query, scopeKey)
            if (cached != null) {
                cachedResponse(cached, request)
            } else {
                val downstream = chain.nextCall(request)
                downstream.chatResponse()?.result?.output?.text?.let { text ->
                    cache.put(query, text, scopeKey)
                }
                downstream
            }
        }
    }

    /** Streaming is not cached; the request passes straight through the chain. */
    override fun adviseStream(
        request: ChatClientRequest,
        chain: StreamAdvisorChain,
    ): Flux<ChatClientResponse> = chain.nextStream(request)

    private fun cachedResponse(text: String, request: ChatClientRequest): ChatClientResponse {
        val chatResponse = ChatResponse(listOf(Generation(AssistantMessage(text))))
        // Spring AI 2.0 marks the response context non-null-valued, while request.context() is nullable-
        // valued; the builder's context(Map<String, ? extends Object>) accepts it without an unchecked cast.
        return ChatClientResponse.builder()
            .chatResponse(chatResponse)
            .context(request.context())
            .build()
    }
}
