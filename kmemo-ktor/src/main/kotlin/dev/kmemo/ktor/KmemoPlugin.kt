package dev.kmemo.ktor

import dev.kmemo.SemanticCache
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey

/** Configuration for the [Kmemo] plugin. */
public class KmemoConfig {
    /** The cache to make available to route handlers. Required. */
    public var cache: SemanticCache? = null
}

private val KmemoCacheKey = AttributeKey<SemanticCache>("dev.kmemo.SemanticCache")

/**
 * A Ktor server plugin that makes a [SemanticCache] available to every route handler.
 *
 * kmemo is coroutine-native, so there is nothing to bridge — this plugin just installs your cache
 * where handlers can reach it, and adds a [getOrPut][ApplicationCall.getOrPut] convenience so a
 * handler caches an LLM call in one line, guards and all.
 *
 * ```kotlin
 * install(Kmemo) { cache = SemanticCache(embedder) }
 *
 * routing {
 *     post("/chat") {
 *         val prompt = call.receiveText()
 *         val answer = call.getOrPut(prompt) { llm.complete(it) }
 *         call.respondText(answer)
 *     }
 * }
 * ```
 */
public val Kmemo: ApplicationPlugin<KmemoConfig> = createApplicationPlugin("Kmemo", ::KmemoConfig) {
    val cache = requireNotNull(pluginConfig.cache) {
        "the Kmemo plugin needs a cache: install(Kmemo) { cache = SemanticCache(...) }"
    }
    application.attributes.put(KmemoCacheKey, cache)
}

/** The [SemanticCache] installed by the [Kmemo] plugin. Throws if the plugin was not installed. */
public val Application.kmemoCache: SemanticCache
    get() = attributes[KmemoCacheKey]

/** The [SemanticCache] installed by the [Kmemo] plugin. Throws if the plugin was not installed. */
public val ApplicationCall.kmemoCache: SemanticCache
    get() = application.attributes[KmemoCacheKey]

/**
 * Returns the cached answer to [prompt], or runs [compute] and caches it — the installed cache's
 * [SemanticCache.getOrPut], reachable straight off the call.
 */
public suspend fun ApplicationCall.getOrPut(
    prompt: String,
    scope: String = SemanticCache.DEFAULT_SCOPE,
    metadata: Map<String, String> = emptyMap(),
    compute: suspend (String) -> String,
): String = kmemoCache.getOrPut(prompt, scope, metadata, compute)
