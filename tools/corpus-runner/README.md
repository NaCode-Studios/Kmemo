# The runner: the specification, implemented, outside the JVM

`../../spec` states a metric, a schema and eleven rules about text. A specification nobody has
implemented is a specification whose gaps nobody has found, so this is the second implementation.

It is Python, it has no dependencies, it imports nothing from this repository's Kotlin, and it reads
its markers from `spec/vocabulary/en.json` rather than restating them. It reproduces every
conformance vector and every corpus figure this project publishes, to the pair.

## Running it

```bash
python3 score.py --vectors \
  --corpus ../../kmemo-core/src/jvmTest/resources/near-miss-corpus.json \
  --corpus ../../kmemo-core/src/jvmTest/resources/held-out-corpus.json \
  --corpus ../../kmemo-core/src/jvmTest/resources/validation-corpus.json
```

```
conformance vectors: 1122/1122 reproduced across 11 rules
near-miss-corpus   (in-sample) near misses caught 76/83 (91.6% [83.6, 95.9]), paraphrases kept 46/46 (100.0% [92.3, 100.0])
held-out-corpus    (retired)   near misses caught 61/86 (70.9% [60.6, 79.5]), paraphrases kept 37/42 (88.1% [75.0, 94.8])
validation-corpus  (retired)   near misses caught 69/102 (67.6% [58.1, 75.9]), paraphrases kept 45/51 (88.2% [76.6, 94.5])
```

Add the fetched splits once their scripts have been run:

```bash
python3 score.py \
  --corpus ../../build/qqp-corpus/qqp-questions.json \
  --corpus ../../build/external-corpus/paws-wiki-test.json
```

`--chain short-questions` scores the preset instead of the default one. `--json <path>` writes the
scores as data.

## Scoring your own cache

`score.py` scores the rules in `kmemo_guards.py`. To score something else, keep `score_corpus` and
replace the chain: a decider is anything that takes two prompts and returns serve or refuse, and
`spec/corpus/METRIC.md` defines the rest, including the rule that every pair is evaluated in both
directions.

Your own corpus goes in the same shape. `spec/corpus/SCHEMA.json` is the schema, and the `standing`
field is the part most worth filling in honestly.

## What writing this found

Two things, and both are in the specification because of it rather than in spite of it.

**Three lists the rules read are not in the vocabulary pack.** The abbreviations whose trailing period
does not end a sentence, the coordinators that make a comparison symmetric, and the markers that make
a clause about the asker. A non-English implementation cannot replace them without forking the rule.
They are listed under
[Implementation-defined data](../../spec/guards/SPEC.md#implementation-defined-data).

**The deduplication in `contentTokens` is load-bearing and was nowhere in prose.** Content words are
compared with duplicates dropped and the first occurrence winning, so a prompt repeating a word does
not shift the alignment every position-comparing rule depends on. An implementation that keeps the
duplicates passes most vectors and fails the ones that matter.

## What it is not

It is not a cache. There is no store, no embedder and no lookup path here: the runner scores the
decision a cache makes about a candidate pair, which is the part `spec/` defines. The library that
does the rest is in the repository this file sits in.
