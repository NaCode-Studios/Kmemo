# Threat model

A semantic cache holds two things a security review cares about: what people asked, and what they were
told. `EntryCipher` shipped so a regulated deployment could cache at all, and the document a reviewer
actually needs was distributed across the KDoc of four types with the assembly left to the person
deciding whether to trust the library.

This is that document. **The section worth reading is [Residual disclosure](#residual-disclosure),**
which states what is still exposed after every mitigation here is switched on. A threat model that
lists mitigations without naming what remains is a diagram with marketing in it.

Nothing below is a promise of security. It is a statement of what the library protects, what it does
not, and where the line is, so that somebody who has to make that judgement can make it.

## Assets

| Asset | Where it lives | Why it matters |
| --- | --- | --- |
| **The prompt** | `CacheEntry.prompt`, in every store, verbatim | User input. Routinely carries names, account numbers, medical and legal detail. Kept verbatim because the guards re-read it on every hit, which is the whole mechanism. |
| **The response** | `CacheEntry.response`, in every store | Whatever the model returned, including anything the prompt caused it to reveal. |
| **The embedding** | `CacheEntry.embedding`, in every store | A lossy representation of the prompt, and the only thing the store can search on. |
| **Tags and metadata** | `CacheEntry.tags`, `CacheEntry.metadata` | Caller-supplied. Meant to be labels about a source of truth rather than about a request. |
| **The scope key** | Every read and write | Model, temperature, system prompt, language. Reveals deployment shape rather than user data. |
| **The tenant id** | `TenantedCache`, in the key space | Identifies the customer a request belongs to. |
| **The exact-match table** | Process memory, when the exact-match layer is on | Plaintext prompts and responses, unencrypted, by construction. |

## Trust boundaries

```
caller process                          store process / database              provider
┌────────────────────────────┐          ┌──────────────────────────┐       ┌───────────┐
│ SemanticCache              │          │ prompts (ciphertext with │       │ embedding │
│  exact-match layer (plain) │ ── I ──► │   EntryCipher, else not) │       │ endpoint  │
│  guards (plaintext)        │          │ responses (same)         │       │           │
│  EntryCipher.encrypt       │          │ embeddings (always plain)│       │ model     │
│  CachePolicy               │          │ tags, metadata (plain)   │       │ endpoint  │
└──────────┬─────────────────┘          └──────────────────────────┘       └─────┬─────┘
           │                                                                     │
           └──────────────────────────── II ─────────────────────────────────────┘
```

**Boundary I, the process and its store.** Everything the store holds is on the other side of it. This
is the boundary `EntryCipher` exists for.

**Boundary II, the process and the providers.** Every prompt reaching `getOrPut` crosses it at least
once, to the embedding provider, before any cache decision has been made. Nothing in this library
prevents that, and the ordering is the reason: see [The `CachePolicy` ordering](#the-cachepolicy-ordering).

Inside the caller's process there is no boundary. The cache holds plaintext, the guards read plaintext,
and a library cannot defend a process against itself.

## Adversaries

**A. Someone who can read the store.** A leaked backup, an over-broad database grant, a compromised
Redis, a support engineer with production access. The commonest real case, and the one the library has
an answer for.

**B. Someone who can read the store and has the embedding model.** Adversary A, plus access to the same
embedding endpoint. Strictly stronger, and the interesting one, because the embeddings cannot be
encrypted.

**C. A tenant of a multi-tenant deployment.** Can issue prompts and read the answers they get back.
Wants another tenant's answers.

**D. Someone who can observe timing.** Can measure how long a lookup took and whether a model call
happened. A cache is an oracle by construction.

**E. Someone inside the caller's process.** Out of scope. Given code execution in the process, the
cache is plaintext, and no seam in a library changes that.

## What the library offers

| Mitigation | Against | What it does |
| --- | --- | --- |
| `EntryCipher` + `EncryptedStore` | A, B | Encrypts prompt and response before they reach any store. The seam only: kmemo ships no cryptography, so the key, the algorithm and their lifecycle are yours. |
| The randomized-encryption check | A, B | `EncryptedStore` verifies on its first write that encrypting the same plaintext twice differs, and refuses to run otherwise. Deterministic encryption leaks equality, and two rows with the same ciphertext are two people who asked the same question. |
| `EntryCipher.identity` | operational | Names the key and algorithm in force, so a rotation is loud rather than silent. |
| `forTenant` + `requireTenant` | C | Partitions the whole key space, including the exact-match fast path. `requireTenant` refuses a call that did not come through a view, so isolation does not depend on nobody forgetting a parameter. |
| `CachePolicy` | A, B | Vetoes a write entirely, so a prompt that must never be persisted is not. Read the ordering caveat below. |
| Scope keys | correctness | Keep one model's answer from serving another model's caller. Not a security control. |
| `kmemo-slf4j` prompt redaction | A | Redacts prompts in logs by default, because logs are a store nobody thinks of as one. |

## Residual disclosure

**With `EntryCipher` configured, `requireTenant` on, a `CachePolicy` in place and the exact-match layer
off**, this is what an adversary still gets.

### The embedding is in the clear, and cannot be otherwise

The store finds an entry by comparing vectors, so the vector has to be readable by whatever performs
the search. Encrypting it would mean the cache could not find anything.

An embedding is a lossy representation of the prompt, not a hash. Adversary B, holding the database and
the same embedding model, can embed a guess and compare it against every row, and a match tells them
that prompt is in the cache. On a bounded question space, which is what most deployments have, that is
an enumeration attack rather than an inversion problem. Published work on embedding inversion also
recovers a usable share of the original text from vectors alone, without the database owner's
cooperation.

**This is the disclosure that decides whether to cache.** A deployment whose threat model includes
adversary B should not be caching those prompts, and `CachePolicy` is how to say so per prompt.

### Tags and metadata pass through in the clear

`EntryCipher` covers the prompt and the response and nothing else. Tags are indexed, which is what
makes `invalidateByTag` a query rather than a scan, and an encrypted tag cannot be indexed. Metadata is
opaque payload the cache never reads.

Neither is user input unless a caller makes it so, and a caller who puts a user identifier in a tag has
put it in the store unencrypted.

### The exact-match layer holds plaintext in memory

It is an in-process table of prompts and responses and it is not encrypted, because encrypting a
process's own memory from itself achieves nothing. It is opt-in and off by default. Adversary E reads
it; nobody else does.

### The scope key and the tenant id are structural

They are part of the key, so the store sees them. They reveal how a deployment is shaped and which
tenant a row belongs to, not what anybody asked.

### The cache is a timing oracle

A hit returns without a model call and a miss does not, and the difference is two orders of magnitude.
Adversary D learns whether a semantically similar prompt has been asked recently. Within a tenant that
is usually acceptable; across tenants it is why `forTenant` partitions the exact-match path as well,
because that path used to skip similarity, the guards and the verifier alike.

Nothing here removes the oracle. A cache that took constant time would not be a cache.

### The `CachePolicy` ordering

**A `CachePolicy` runs before the write and not before the embedding.** A prompt that must never be
persisted has still been sent to an embedding provider by the time the policy sees it, and on
`getOrPut` it has been sent to the model provider too.

This is a real gap and it is a consequence of the shape rather than an oversight: the cache cannot
decide anything about a prompt until it has a vector for it. A deployment that must not send certain
prompts to a provider has to filter them before calling the cache, in its own code, where the prompt
still is.

### A verifier is a model call

`Verifier` is an optional check that sends both prompts somewhere. If it is a hosted model, the cached
prompt and the incoming one both leave the process, which is a disclosure the guards never make. The
guards are pure and local by design.

### What the guards read

They read plaintext, in-process, on every candidate. On an encrypted store this means the entry is
decrypted before the guards see it, so the plaintext exists in memory during a lookup. There is no way
around it: judging whether two prompts mean the same thing requires reading them.

## What this model does not cover

- **Availability.** Nothing here defends against a store filled up deliberately. `AdmissionPolicy` and
  TTLs are cost controls rather than security controls.
- **Poisoning.** Anyone who can write to the store can plant a response that will be served. The cache
  trusts its store completely.
- **The provider side.** What an embedding or model provider retains is between the caller and that
  provider.
- **Correctness as a security property.** A false hit is a wrong answer, and whether a wrong answer is
  a security problem depends on the domain. The measurements are in
  [MEASUREMENTS.md](MEASUREMENTS.md) and the guards are what lowers that rate.

## Reviewing a deployment

Six questions, in the order they change the answer.

1. **Do these prompts belong in a store at all?** If adversary B is in scope, the answer is often no,
   and `CachePolicy` is how to exclude them.
2. **Is `EntryCipher` configured, and is its encryption randomized?** `EncryptedStore` refuses to run
   otherwise, which is the check rather than the promise.
3. **Is the exact-match layer on?** It holds plaintext in memory, and it is off by default.
4. **Is more than one customer served from one cache?** If so, `requireTenant` and `forTenant`, or the
   isolation depends on nobody forgetting a parameter.
5. **Is anything user-derived in a tag or in metadata?** Those are not encrypted.
6. **Is a hosted `Verifier` configured?** If so, prompts leave the process on the read path.
