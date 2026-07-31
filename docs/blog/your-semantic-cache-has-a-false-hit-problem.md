# Your semantic cache has a false-hit problem

*A write-up on why a semantic LLM cache needs guards rather than just a threshold, with the honest numbers
behind kmemo. Draft for a blog post / Kotlin Weekly / r/Kotlin.*

---

Caching LLM calls by meaning instead of by exact bytes is an obvious win: two users ask the same
question worded differently, and you pay for the model once. The usual recipe is three lines: embed
the prompt, find the nearest cached prompt, and if the cosine similarity clears some threshold, serve
its answer.

That recipe has a failure mode most people discover in production.

## The failure mode is not a miss

The cost of a semantic cache getting it wrong is **asymmetric**. A wrong *rejection*, missing a hit
you could have served, costs you one API call. A wrong *acceptance*, serving a cached answer to a
question it does not answer, costs you a **wrong answer** shipped to a user, with your logo on it.

And the wrong acceptances are not rare edge cases. Consider:

```
Convert 100 USD to EUR
Convert 250 USD to EUR
```

Every mainstream embedding model places these at around **0.99** cosine similarity. They are, by every
signal a vector gives you, the same question. There is no threshold that accepts real paraphrases and
rejects that pair, because on the similarity axis that pair is *closer together* than most genuine
paraphrases are. Turn the threshold up until the near-miss is rejected and you have rejected most of
your real hits too.

The same shape shows up everywhere once you look:

- `What is the capital of Austria` vs `…of Australia`: two letters, one continent.
- `How do I enable 2FA` vs `How do I disable 2FA`: one flipped verb, the opposite answer.
- `boiling point of ethanol` vs `…of methanol`: one letter, a different chemical.
- `deworm a puppy` vs `deworm an adult dog`: same words, a different dose.

A similarity threshold cannot see any of these. It is reading the wrong signal.

## Guards: read the text, not just the vector

kmemo's answer is to treat similarity as the **first** filter, not the only one. A candidate that
clears the threshold is then read *as text* by a chain of small, cheap **guards**, each looking for
concrete evidence that the two questions must have different answers:

- a **numeric** guard (`100` ≠ `250`),
- a **unit** guard (`km` ≠ `miles`, `USD` ≠ `GBP`),
- an **entity** guard (`Austria` ≠ `Australia`),
- an **antonym** guard (`enable` ↔ `disable`),
- a **negation** guard (`should` vs `should not`),
- a **temporal** guard (`today` vs `tomorrow`),

…and a few more. A guard never *accepts*. It can only veto. If any guard finds a reason the answers
must differ, the lookup misses and you call the model. The design rule is strict: **a guard may never
reject a genuine paraphrase.** A marker earns its place in a guard only if adding it will not turn a
real hit into a miss. When the lexical guards cannot see the difference, as the ethanol/methanol case
needs world knowledge, an optional `Verifier` (a cheap model call) is the last line.

## The honest part: measure it, don't assert it

Anyone can claim their cache is safe. The only way to know is a labelled corpus with a **blind
validation split**, near-miss pairs and paraphrase pairs the guards were never tuned against, and
then reporting the numbers, including where the guards fail.

On kmemo's blind validation split:

- **near misses rejected: 67%.** Two thirds of the dangerous pairs are caught by lexical guards alone,
  before any model call.
- **paraphrases kept: 88%.** The guards leave the large majority of genuine hits intact.

Neither number is 100%, and the write-up says so. The remaining third of near misses are the
world-knowledge cases the `Verifier` exists to cover; the 12% of paraphrases lost are the price of
never shipping a wrong answer, and each one costs a single API call. That is the asymmetry the whole
library is built around, stated as a reproducible figure rather than a slogan.

## What it looks like

```kotlin
val cache = SemanticCache(embedder)               // your embedder; kmemo ships none

val answer = cache.getOrPut(prompt) { llm.complete(it) }
```

That is the whole hot path. `getOrPut` embeds once, checks the threshold, runs the guards, and either
serves a cached answer or calls your model and caches the result. Everything else, the store (Redis,
pgvector, in-memory), metrics, the Spring AI advisor and the LangChain4j wrapper, is opt-in around that
core, which depends on nothing but Kotlin coroutines.

Try the near-miss for yourself:

```bash
./gradlew :examples:run
```

## Takeaway

If you are caching LLM calls by similarity alone, you are shipping wrong answers at whatever rate your
traffic contains near-misses, and you have no number for that rate. A semantic cache's job is not to
maximize hit rate; it is to maximize hit rate **subject to never serving a wrong answer**. That is a
different, harder, and much more useful problem, and it is worth measuring honestly.

*kmemo is Apache-2.0, on Maven Central. Guards, corpus, and numbers are all in the open.*
