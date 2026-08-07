# Semantic-cache attributes: a proposed convention

OpenTelemetry's value is not that a library can emit telemetry. It is that two libraries doing the
same thing emit **comparable** telemetry, and that only happens when the names are agreed rather than
invented per project.

There is no semantic convention for a semantic cache. This document proposes one, with the argument
for each name, and `kmemo-otel` emits exactly it. The constants are also in
[`Conventions`](../kmemo-otel/src/main/kotlin/dev/kmemo/otel/KmemoTelemetry.kt), so a caller writing
their own exporter on a platform that module does not reach uses the same names rather than a second
set.

Nothing here is ratified by anybody. It is a proposal from the project with the attributes already in
hand, published so that disagreeing with it is possible.

## The namespace: `gen_ai.cache.*`

Not `db.cache.*` and not `cache.*`.

**A semantic cache is not a database cache.** A database cache is keyed by equality and its hit is a
fact. This one decides by meaning, its hit is a judgement, and its characteristic failure is serving
an answer to a question nobody asked. An operator reading `db.*` attributes would reasonably assume
the first shape.

**It belongs beside the model call it exists to avoid.** What it saves is a token bill and what it
risks is a wrong answer to a person, both of which are `gen_ai.*` concerns. The GenAI conventions are
where the rest of that story is already being written, and a cache in front of a model call is part of
that story rather than a neighbour to it.

**A bare `cache.*` namespace would be too broad to ever ratify.** It would have to cover HTTP caches,
object caches and CDN caches, which share almost none of these attributes.

## The attributes

| Attribute | Type | Where | The argument |
| --- | --- | --- | --- |
| `gen_ai.cache.system` | string | both | Which implementation produced this. Constant per library, and the join key when a service runs two caches from different vendors. |
| `gen_ai.cache.hit` | boolean | both | The one attribute every cache needs. A boolean rather than folding into the outcome, because hit rate is the number everybody computes first. |
| `gen_ai.cache.outcome` | string | both | `hit`, `miss`, `degraded`, `embedder_mismatch`, `written`, `vetoed`. A cache has states that are neither a hit nor a miss, and a schema with only two of them forces them into the wrong bucket. |
| `gen_ai.cache.miss.reason` | string | both | **The attribute that makes a semantic cache tunable.** A hit rate of 4% has opposite fixes for a threshold miss and a rejected candidate, and a counter that does not split them is a dashboard nobody can act on. |
| `gen_ai.cache.guard` | string | both | Which check refused the candidate. Low cardinality by construction: a check chain is a fixed list. It turns "the cache is rejecting things" into "the numeric check is rejecting things", which is a different conversation. |
| `gen_ai.cache.stage` | string | both | `embed`, `search`, `verify`. A lookup's latency is three latencies, and one of them is a model call. A single duration hides which. |
| `gen_ai.cache.similarity` | double | span | The score the decision was taken at. Continuous, so it is a span attribute and never a metric dimension. |
| `gen_ai.cache.scope` | string | span | The partition the lookup ran in. Caller-defined and routinely unbounded. |
| `gen_ai.cache.entry.id` | string | span | Which stored entry was served. Unbounded by definition, and the thing an operator needs to invalidate one bad answer. |
| `gen_ai.cache.embedder.expected` | string | metric | The embedding identity the cache is running. |
| `gen_ai.cache.embedder.found` | string | metric | The identity recorded on a refused entry. The pair is the whole diagnosis of a hit rate that fell to zero after a model swap, and neither half means anything alone. |
| `gen_ai.cache.veto.reason` | string | metric | Why a write was not made. Caller-defined and meant to be bounded, because it is a policy's own vocabulary. |
| `gen_ai.cache.eviction.cause` | string | metric | Evicted for room, or dropped past its TTL. Two different capacity conversations. |
| `gen_ai.cache.degraded.operation` | string | metric | Which entry point ran uncached after an embedding failure. The one failure that otherwise leaves no trace: it is not a miss, because no lookup happened. |
| `gen_ai.cache.currency` | string | metric | The unit a saving is in. A number without its unit is not a measurement, and no library can know the caller's currency. |

## The instruments

| Instrument | Type | Unit | Attributes |
| --- | --- | --- | --- |
| `gen_ai.cache.lookups` | counter | `{lookup}` | `outcome`, `miss.reason`, `guard`, `embedder.*`, `degraded.operation` |
| `gen_ai.cache.writes` | counter | `{entry}` | `outcome`, `veto.reason` |
| `gen_ai.cache.evictions` | counter | `{entry}` | `eviction.cause` |
| `gen_ai.cache.duration` | histogram | `s` | `stage` |
| `gen_ai.cache.saved` | histogram | `{currency}` | `currency` |

Seconds rather than milliseconds, because that is what OpenTelemetry's own conventions use. A
histogram rather than a counter for the saving, because the distribution is the interesting part: a
cache whose hits are all cheap answers saves less than its hit rate suggests.

## The one design decision worth arguing with

**Scope and entry id are on spans and never on metrics.** Both are caller-defined and routinely
unbounded, one per tenant or per model version, and an unbounded metric dimension is how a
Prometheus head block or a metrics bill blows up.

The cost is real: a team wanting per-scope hit rate cannot get it from these metrics. The answer
offered instead is a deliberately bounded exporter of their own, over the same event stream, where
they choose which scopes are worth a series. That is a worse default for them and a better one for
everybody who would otherwise discover the cardinality problem in production.

## The spans

One span named `cache.lookup`, with a child named `cache.lookup <stage>` for each stage that ran.

They are recorded **after** the lookup, with explicit timestamps taken from the durations the cache
already measured. That is not a workaround: the listener runs inline on the calling coroutine, so the
lookup lands under whatever span the caller was already in, and a verifier call, which is a model call
inside somebody's request, appears in their trace as one.

The parent's duration is the sum of its timed stages, which is a **lower bound** on the real lookup.
The check chain and the store's own bookkeeping are not timed, so they are not claimed. A span that
overstated a duration would be worse than one that understates it, because the number a reader takes
from a trace is the one they act on.

## Where this is JVM-only, and why

`kmemo-otel` runs on the JVM alone. There is no OpenTelemetry API on Maven Central that a Kotlin
Multiplatform module can depend on, so an adapter for the other nine targets cannot be written today
whatever anybody wants.

That is why this document exists separately from the module. `CacheListener` is multiplatform and
carries every attribute above, so an exporter written for iOS, for a native binary or for a Wasm
worker can emit the same names, and telemetry from those platforms joins with telemetry from the JVM
rather than sitting beside it under different keys.
