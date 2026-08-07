# The false-hit rate

Semantic caches are reported on hit rate. Hit rate is the number that looks best exactly when a cache
is at its most dangerous, because the cheapest way to raise it is to serve more of the candidates a
similarity threshold proposed, and the candidates a threshold proposed are the ones most likely to
mean something else. A cache that answers a question nobody asked has not saved a call, it has
returned a wrong answer quickly.

This document defines the measurement that says so, precisely enough to implement from the definition
rather than from anybody's code.

## The data

A **corpus** is a set of labelled pairs conforming to `SCHEMA.json`. Each pair holds two prompts and
one boolean:

- a **near miss** is a pair whose two prompts need different answers. Serving one's answer for the
  other is a wrong answer.
- a **paraphrase** is a pair whose two prompts need the same answer. Refusing it costs one model call,
  which is the call that would have been made with no cache at all.

The two are not interchangeable and no single number should combine them. Every rate below is
reported as a pair of rates, always.

## The system under test

A **decider** is anything that, given two prompts, returns serve or refuse. A guard chain, a
cross-encoder, a threshold over an embedding, a language model asked to judge: the metric does not
care, and that is the point of defining it here rather than inside one implementation.

## Both directions

**Every pair is evaluated twice, with the two prompts in each order, and the pair counts as refused
when the decider refuses either way.**

This is not a detail. In a cache, either prompt could be the one already stored when the other
arrives, so a decider that refuses one way round protects the pair, and costs the hit, just the same.
Measuring one direction reports half of what the decider does, and which half depends on the order
somebody happened to write the file in.

A decider whose two directions disagree is legitimate. `sub-span` in the reference implementation is
one, because "the same question plus a narrowing clause" is a relationship with a direction. Report
the count of disagreements; do not assert on it.

## The rates

Let `N` be the near misses in the corpus and `P` the paraphrases.

```
false-hit rate  = |{ near misses the decider serves }|   / |N|
catch rate      = 1 - false-hit rate
retention       = |{ paraphrases the decider serves }|   / |P|
```

**The false-hit rate is the headline and retention is its inseparable companion.** A decider that
refuses everything has a false-hit rate of zero and a retention of zero, and is a cache that has
turned into an expensive proxy. A decider that serves everything has a retention of one and a
false-hit rate of one, which is what a similarity threshold alone produces and what most "add a
semantic cache" tutorials build.

Precision, recall and F1 over the serve decision may be reported as well. They are not the headline,
because both of their errors land in one number and the two errors have different units: one is an
API call and the other is a wrong answer to a person.

## The interval

**Every rate is published with the range its sample supports.** Use Wilson's score interval at 95%:

```
centre = (p + z²/2n) / (1 + z²/n)
spread = z/(1 + z²/n) · sqrt( p(1-p)/n + z²/4n² )
```

with `p` the observed rate, `n` the number of pairs of that kind, and `z = 1.959964`. Clamp to
`[0, 1]`.

Wilson rather than the normal approximation, which is wrong exactly where these numbers live: it
produces bounds outside `[0, 1]` near the edges and collapses to zero width when a decider catches
everything or nothing, which is the case where the sample says least.

A hundred near misses support an interval about nine points wide in each direction. A five-point
improvement measured on a hundred pairs is not a measurement. Around a rate near 68%, telling five
points from noise at 95% takes roughly **1,340** pairs of the kind being measured.

## The discipline, which travels with the data

A corpus published without this is a corpus somebody will fit against and then quote, and the number
they quote will describe the fitting.

**A corpus has a standing and the standing is part of the number.** `in-sample` means the decider was
built with these pairs in view. `blind` means no failure from it has been read and the measuring side
cannot add to it. `retired` is the state in between, and it is the one that gets forgotten: a split
whose failures somebody has read is spent, because there is no way to un-see them and every
subsequent change has had the opportunity to be aimed at it.

**A blind split is never fitted against, not once.** Not its parameters, not its thresholds, not the
choice of which rule to write next. Reading which pairs it fails on while changing a decider is the
prohibited act, and it is usually committed by a report rather than by a person: a test that prints
the residual performs it on behalf of everybody who runs the suite.

**A report may print counts and category distributions from a blind split, and not its pairs.** A
count and a distribution answer what a reader wants and guide nothing, because nobody can aim a rule
at a category without seeing the pairs. Put the pairs behind a flag named for what passing it costs.

**A floor only ever moves up.** Record the measurement as a regression floor and never lower it to
make a build pass; lowering it erases the regression it exists to catch.

**A retired split keeps its floor and loses its voice.** It stays in the reports and stays a gate. It
stops being the number to quote.

**Say which of these a published figure is.** A rate with no standing beside it should be read as
`in-sample`.

## Reporting a decider

The minimum that makes a figure comparable:

- the corpus, its standing, and its size split into near misses and paraphrases;
- false-hit rate and retention, each with its 95% interval;
- whether both directions were evaluated, which under this definition they must be;
- for a chain of rules, each rule's contribution **inside the chain** as well as in isolation. In
  isolation says what a rule would do alone; inside the chain says what removing it would cost, and
  the two differ by more than anybody expects.
