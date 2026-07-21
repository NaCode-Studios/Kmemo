package dev.kmemo.langchain4j

import dev.kmemo.SemanticCache
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import kotlinx.coroutines.runBlocking

/**
 * A LangChain4j [ChatModel] that puts a [SemanticCache] in front of another model.
 *
 * LangChain4j ships no semantic cache, so this is the drop-in: wrap the model you already have, and
 * a repeated-in-meaning request is served from the cache — with kmemo's guards stopping the answer for
 * `250 USD` from being served to a request about `100`.
 *
 * ```kotlin
 * val model: ChatModel = CachingChatModel(openAiChatModel, cache)
 * val answer = model.chat("What is the capital of France?")
 * ```
 *
 * The cache key is the **whole conversation** — every message, in order — not just the last one, so a
 * question asked after a different exchange is a different key and cannot be served the earlier answer.
 * A request whose messages are not plain text (a tool result, a multi-part user message) is passed
 * straight through, uncached, rather than keyed on something lossy. Anything that changes what a correct
 * answer looks like — the model, temperature, the system prompt if it is not already in the messages —
 * belongs in the [scope].
 *
 * On a **hit** the wrapped model is not called; the cached text comes back as an [AiMessage]. On a
 * **miss** the delegate runs and its assistant reply is cached. The cache is a `suspend` API and
 * `ChatModel` is blocking, so lookups run inside `runBlocking` on the calling thread — which on a hit
 * skips the model call entirely, the whole point.
 *
 * @param delegate the model to cache in front of.
 * @param cache the semantic cache to serve from.
 * @param scope derives the cache scope from a request; constant [SemanticCache.DEFAULT_SCOPE] by default.
 */
public class CachingChatModel @JvmOverloads constructor(
    private val delegate: ChatModel,
    private val cache: SemanticCache,
    private val scope: (ChatRequest) -> String = { SemanticCache.DEFAULT_SCOPE },
) : ChatModel {

    override fun chat(request: ChatRequest): ChatResponse {
        val query = cacheKey(request.messages()) ?: return delegate.chat(request)
        val scopeKey = scope(request)
        return runBlocking {
            val cached = cache.get(query, scopeKey)
            if (cached != null) {
                ChatResponse.builder().aiMessage(AiMessage.from(cached)).build()
            } else {
                val response = delegate.chat(request)
                response.aiMessage()?.text()?.let { text -> cache.put(query, text, scopeKey) }
                response
            }
        }
    }

    /**
     * Renders the conversation into a stable cache key, or `null` when it holds a message this wrapper
     * will not cache on (a tool result, a multi-part message) — in which case the request is passed
     * through uncached rather than keyed on a lossy summary.
     */
    private fun cacheKey(messages: List<ChatMessage>): String? {
        if (messages.isEmpty()) return null
        val builder = StringBuilder()
        for (message in messages) {
            val text = when (message) {
                is UserMessage -> if (message.hasSingleText()) message.singleText() else return null
                is SystemMessage -> message.text()
                is AiMessage -> message.text() ?: return null
                else -> return null
            }
            builder.append(message.type().name).append(": ").append(text).append('\n')
        }
        return builder.toString()
    }
}
