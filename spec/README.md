# The near-miss specification

Every published figure about false cache hits in this ecosystem is a figure a project produced about
itself, and the alternative most projects report is a hit rate, which is the number that looks best
exactly when a cache is at its most dangerous. This directory is the attempt to make the other
measurement reproducible by somebody who owes this project nothing.

Four documents, two of them machine-readable, and a runner outside the JVM that proves they are
enough.

| | |
| --- | --- |
| [`corpus/SCHEMA.json`](corpus/SCHEMA.json) | What a labelled corpus of prompt pairs looks like. JSON Schema 2020-12. |
| [`corpus/METRIC.md`](corpus/METRIC.md) | The false-hit rate, the both-directions rule, the confidence interval, and the discipline that has to travel with the data. |
| [`guards/SPEC.md`](guards/SPEC.md) | Eleven rules about text, stated so they can be implemented without reading this repository's Kotlin. |
| [`guards/vectors.json`](guards/vectors.json) | A decided verdict per rule per pair. A disagreement fails a build instead of surfacing as a wrong answer later. |
| [`vocabulary/en.json`](vocabulary/en.json) | The English markers the rules read. Data, not rules: an implementation supplying its own is still conformant. |

## Why this is separate from the library

A library is judged on what it does. A standard is judged on what other people can do with it, and
the thing worth standardising here is the metric and the data rather than the Kotlin. Until `2.3.0`
the corpora lived in a test resource directory in a shape this project invented, read by a class that
exists nowhere else, and a Python cache or a competitor who wanted to be measured on the same axis
would have had to reimplement the loader, the pair semantics and the both-directions rule from
reading the tests.

## The proof that it is enough

[`../tools/corpus-runner`](../tools/corpus-runner) implements the eleven rules in Python, from
`guards/SPEC.md`, reading the markers from `vocabulary/en.json`. It reproduces every conformance
vector and every corpus figure this project publishes, to the pair, with no JVM involved:

```bash
cd tools/corpus-runner
python3 score.py --vectors \
  --corpus ../../kmemo-core/src/jvmTest/resources/validation-corpus.json
```

A specification nobody has implemented is a specification whose gaps nobody has found. Writing that
runner is what surfaced the three lists under
[Implementation-defined data](guards/SPEC.md#implementation-defined-data), which the rules read and
the vocabulary pack does not carry.

## Versioning

The whole directory ships as one archive, `kmemo-corpus-<version>.zip`, built by
`./gradlew corpusBundle` and attached to each GitHub Release. It carries a manifest with a SHA-256 per
file, so a figure quoted against a version names bytes rather than a directory.

The two fetched splits are not in the archive and cannot be: they are somebody else's data under
somebody else's licence. The scripts that reproduce them are, under `fetch/`, each pinned to a
dataset revision whose bytes they verify.

## Conformance

An implementation is conformant for a rule when it reproduces that rule's verdicts on every vector
naming it. Reason strings, tokenizers and internal structure are outside conformance; the markers are
per-language data. `guards/SPEC.md` states all of that precisely, along with the rules whose
description could not avoid naming a tokenization.

## The rule that travels with the data

`corpus/METRIC.md` is not optional reading. A corpus published without its discipline is a corpus
somebody will fit against and then quote, and a split whose failures have been read is spent whether
or not anybody says so. The `standing` field in the schema exists to make that fact part of the
number rather than part of the folklore.
