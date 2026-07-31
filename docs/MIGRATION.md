# Migrating from 1.x to 2.0

`2.0` moves `kmemo-core` off the JVM. Everything else in this document follows from that.

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
