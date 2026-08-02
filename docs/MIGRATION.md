# Migration guide

One section per hop, newest first. Nothing here is a break you can hit by upgrading alone: every item
says who it affects, and most readers affected by none of them change a version number and stop.

# Migrating from 2.0 to 2.1

`2.1` gives the embedding model a name and makes a cache entry record which model wrote it. If you
declare nothing, nothing changes. If you declare one, read the second half of this section before you
deploy it, because it decides what happens to the entries already in your store.

## Recompile

`2.1` is source compatible with `2.0` and not binary compatible with it. `CacheEntry` and `CacheStats`
each gained a parameter with a default, which moves a constructor signature even where no source moves.
[STABILITY.md](../STABILITY.md) names that as a minor-version boundary. Rebuild; edit nothing.

## Declaring an identity is opt-in, and undeclared is an identity

`Embedder` gained an `identity`, and its default is `Embedder.UNDECLARED`. A cache whose embedder
declares nothing writes `undeclared` entries and reads `undeclared` entries, which is where every `2.0`
deployment already is, so its behaviour is unchanged to the lookup.

Declare one when the model is worth naming:

```kotlin
val embedder = object : Embedder {
    override val identity = "openai:text-embedding-3-small:1536"
    override suspend fun embed(text: String) = openAi.embed(text)
}
```

From then on a lookup refuses any entry written under a different identity, reporting
`MissReason.EMBEDDER_MISMATCH` and emitting `CacheEvent.EmbedderMismatch`, rather than scoring a vector
from one model against a vector from another. Two models do not share a space, so that score was never a
similarity. The dimension check already in the code only catches the version of this mistake where the
sizes differ, which is the version nobody makes.

## The two ways out of a store written under a different identity

This is the part to plan. The moment a cache declares an identity, everything already in its store
carries a different one, `undeclared` at the very least, and is refused. That is correct, because
nothing recorded what produced those vectors, but it means a cold cache unless you pick one of these.

**Re-embed.** Read the entries out, embed each prompt with the new model, write them back. The cache
warms fully and the old entries drop out as they expire or are evicted. It costs one embedding call per
entry, once, and it is the right answer when the answers are still good and only the vectors are stale.
`warm(...)` takes the pairs directly:

```kotlin
cache.warm(existing.map { WarmEntry(prompt = it.prompt, response = it.response, scope = it.scope) })
```

**A separate scope.** Put the model in the scope string, `"gpt-4o|embed=3-small|v3"`, and a new model
is a new partition that fills on its own while the old one ages out untouched. Nothing is re-embedded
and nothing is refused, because the two never meet. This is the cheaper answer and the one to reach for
when the responses would have to be recomputed anyway.

Both work. The one thing that does not work is lowering the threshold: the score across two embedders is
not a weak signal about the prompts, it is not a signal about the prompts, so accepting more of it
accepts noise.

## `MissReason` has a fifth value, and `CacheEvent` an eighth member

`MissReason.EMBEDDER_MISMATCH` and `CacheEvent.EmbedderMismatch`. An exhaustive `when` over either, with
no `else` branch, stops compiling until it handles the new case.

**Affects you if** you match on `CacheLookup.Miss.reason` or on `CacheEvent`. The `kmemo-micrometer` and
`kmemo-slf4j` adapters already do; upgrade them together.

## Stores persist the identity, and old rows read as undeclared

`PostgresStore` adds an `embedder` column and `RedisStore` an `embedder` hash field, both on next start
and both idempotent. Rows and hashes written before `2.1` have neither, and are read as `undeclared`.
That is the honest record, since nothing captured what embedded them. A custom `CacheStore` must round-trip
`CacheEntry.embedder` to stay conformant; the shared TCK covers it.

# Migrating from 1.x to 2.0

`2.0` moves `kmemo-core` off the JVM. Everything else in this section follows from that.

Most callers change nothing but a version number and a coordinate. The five items below are the whole
list of breaks, and each one says who it affects.

## 1. Recompile

`2.0` is not binary compatible with `1.x`. Several constructors gained parameters with defaults, and
`GuardVocabulary` gained a field, so signatures moved even where the source did not. Code compiled
against `1.x` will not link against `2.0`.

Nothing to edit. Rebuild.

## 2. Maven users depend on `kmemo-core-jvm`

Gradle reads the module metadata and picks the right variant on its own, so a Gradle build changes only
the version:

```kotlin
implementation("io.github.nacode-studios:kmemo-core:2.0.0")
```

Maven does not read that metadata. A Maven build must name the JVM artifact:

```xml
<dependency>
  <groupId>io.github.nacode-studios</groupId>
  <artifactId>kmemo-core-jvm</artifactId>
  <version>2.0.0</version>
</dependency>
```

The adapters (`kmemo-store-redis`, `kmemo-spring-boot-starter`, and the rest) are JVM-only and keep
their coordinates exactly as they were.

## 3. `CacheEntry.createdAt` is a `kotlin.time.Instant`

`java.time` does not exist outside the JVM, so the core uses the standard library's own instant and
clock. `kotlin.time.Instant` and `kotlin.time.Clock` are stable as of Kotlin 2.4, which is why this
costs no new dependency.

**Affects you if** you implement `CacheStore` yourself, construct `CacheEntry` directly, or pass a
custom `clock` to `SemanticCache`, `InMemoryStore` or `CachingVerifier`.

On the JVM the two types convert in one call each, and that is all a store adapter needs at the edge
where it talks to a driver:

```kotlin
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

statement.setObject(1, entry.createdAt.toJavaInstant())
val createdAt = resultSet.getTimestamp("created_at").toInstant().toKotlinInstant()
```

A custom clock changes shape slightly, because `kotlin.time.Clock` is an interface with one method:

```kotlin
// 1.x
class FixedClock(private val now: java.time.Instant) : java.time.Clock() {
    override fun instant() = now
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: ZoneId) = this
}

// 2.0
class FixedClock(private val now: kotlin.time.Instant) : kotlin.time.Clock {
    override fun now() = now
}
```

## 4. `MatchGuards.standard(Locale)` needs an import

`java.util.Locale` is the one part of the guard layer that cannot leave the JVM, so the two functions
that take one are now extensions living in the JVM source set. The call site is unchanged and needs an
import:

```kotlin
import dev.kmemo.guard.standard   // for MatchGuards.standard(Locale)
import dev.kmemo.guard.forLocale  // for Vocabularies.forLocale(Locale)

val cache = SemanticCache(embedder, guards = MatchGuards.standard(Locale.ITALIAN))
```

There is also a new member taking an ISO 639 code, which works on every platform and needs no import:

```kotlin
val cache = SemanticCache(embedder, guards = MatchGuards.standard("it"))
```

## 5. `EvictionCause` has a fourth value

`NEAR_DUPLICATE` is reported when `deduplicateWrites` replaces an entry a new one duplicates. An
exhaustive `when` over `EvictionCause` with no `else` branch stops compiling until it handles the new
case.

**Affects you if** you match on `CacheEvent.Eviction.cause`.

## The behaviour change worth knowing about

`MatchGuards.standard()` has an eleventh guard. `SubSpanGuard` catches the near miss where one prompt is
the other plus a clause that narrows the question, which no other guard sees because word overlap is
perfect. It is measured at zero false rejections across all three corpora, so it should not cost you a
hit that `1.x` served, but it will reject some matches `1.x` accepted.

On the blind validation split it moves the false-hit rate from 0.333 to 0.324. If you need the old
behaviour exactly, build the list yourself from the guards you want.

## What does not change

The `Embedder`, `CacheStore`, `Verifier` and `MatchGuard` seams keep their shapes. `getOrPut`, `lookup`,
`get`, `put`, `warm`, `explain` and `invalidate` keep their signatures. Every store adapter and every
framework integration keeps its coordinates and its API. Scopes, thresholds, the event stream and the
metrics names are untouched.

Everything `2.0` adds is opt-in and defaulted off: reranking, quantized retrieval, write deduplication,
adaptive thresholds, the response-aware guard, the exact-match layer and shadow mode. A `1.x`
configuration that compiles against `2.0` behaves the way it did, apart from the eleventh guard above.
