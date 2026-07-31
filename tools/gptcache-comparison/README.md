# Scoring Kmemo's corpora with GPTCache

`compare.py` hands GPTCache's own similarity evaluator the same labelled prompt pairs Kmemo's guards
are measured on, and records precision, recall, F1 and false-hit rate. Its output,
`kmemo-core/src/test/resources/gptcache-comparison.json`, is the fourth row of the table in the
project README.

## Running it

Python 3.11 is required, and the pins in `requirements.txt` are not advisory — see the comments in
that file for which library imposes which constraint.

```bash
python3.11 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python compare.py
```

The first run downloads an ONNX model from the Hugging Face hub, so it needs network access.

**Two known advisories come with the pins, and neither has a fix this harness can take.**
GHSA-fgcw-684q-jj6r and GHSA-29pf-2h5f-8g72 are arbitrary code execution while loading a model, and
both are fixed only in transformers 5, which removed the tokenizer method GPTCache's evaluator calls.
The exposure is bounded by what this script does: it loads one model, the one GPTCache itself names, and
only when you run it. Nothing here is installed by CI and no Python package here reaches a consumer of
the library. Do not point this environment at a model you did not choose. `ci.yml` allows those two
advisories by id and nothing else, so a third one still fails the build.

## Why this is a script and not a test

GPTCache is a Python package that fetches a model on first use; CI here is a JVM build. Putting this in
CI would mean a Python toolchain, a model download and a dependency set that has already broken once,
for a number that only changes when a corpus does.

So the script is committed and runnable, its output is committed, and CI enforces the *link* between
them: each recorded row carries the SHA-256 of the corpus file it was measured against, and
`ComparativeBenchmarkTest` recomputes it. Grow a corpus without re-running this and the build fails,
which is the only way a committed measurement can be stopped from quietly becoming a lie.

## What it refuses to do

**`OnnxModelEvaluation.evaluation` wraps its whole body in `except Exception: return 0`.** A missing
model file, a tokenizer method that moved, a bad tensor shape — every internal failure comes back as a
similarity of zero, which reads exactly like GPTCache confidently refusing the pair. A harness that
calls the documented entry point and writes down what it gets would report a false-hit rate of 0.000
and a recall of 0.000, conclude that GPTCache refuses everything, and be completely wrong in Kmemo's
favour.

Nothing here trusts a score until the evaluator has proved it works:

- `verify_evaluator` calls `inference` directly, outside the `try`, so a broken install raises.
- It then requires the evaluator to serve a trivially restated sentence and refuse two unrelated ones,
  using GPTCache's own threshold. A model that loads but scores noise stops the run.
- Any `0.0` produced during the run is re-scored through `inference`. A real zero survives; a
  swallowed exception raises.

The gate is **mechanical, never semantic**. It asks whether the evaluator functions, not whether it
agrees with Kmemo about a hard pair — a gate built from disputed pairs would fail GPTCache for
disagreeing, and disagreement is the thing being measured.

## What is deliberately not compared

**GPTCache's default evaluator.** `SearchDistanceEvaluation` scores the vector distance the retrieval
step already produced, which is the threshold-only baseline under another name; it is already in the
table, measured on the JVM side. Running it here would measure the embedding model.

**Anything with a clock in it.** A JVM figure against a Python figure compares runtimes while appearing
to compare caches. Latency and throughput live in `kmemo-benchmarks`, across Kmemo's own
configurations.

**The retrieval step.** Both sides are handed the same candidate pair and asked only whether to serve
it. That controls for the embedder more tightly than matching embedding models would, and it is the
reason the two columns are comparable at all.
