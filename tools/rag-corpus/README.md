# The retrieval corpus: a pipeline somebody else assembled

Every other number this repository publishes comes from a benchmark it wrote for itself. The hit rate,
the false-hit rate and the guard costs are all measured against corpora chosen to exercise the guards.
None of them answers the question a reader arrives with: on a retrieval-augmented pipeline, how much does
this actually remove, and what does it get wrong.

`fetch.py` downloads the corpus that question needs.

## Running it

```bash
python3.11 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python fetch.py
```

It writes `../../build/rag-corpus/squad-dev.json`, which `RagPipelineTest` in the examples module reads.
Without it that test skips with a sentence saying so; in CI, which passes `-PragCorpusRequired=true`, it
fails instead, for the reason the external corpus fails there: a measurement nobody notices has stopped
running is not a measurement.

## Why SQuAD

SQuAD v1.1 is paragraphs from Wikipedia with questions asked about them and the answer marked inside the
paragraph. That shape is a retrieval-augmented pipeline with the retrieval already labelled: the
paragraph is the document, the question is the query, and the answer span is what a correct generation
would have produced. Nobody has to trust a model to know whether an answer was right.

It also contains, by construction, the failure this measurement exists to find. Several paragraphs are
asked near-identical questions, because "in what year was it founded" is a question about many things.
A cache keyed on the question serves one paragraph's answer to another paragraph's question, and the
retrieved context is the only thing that ever distinguished them.

The **dev** split is used, never train, and it is fetched rather than vendored so the licence stays with
the dataset. SQuAD is CC BY-SA 4.0, from Stanford, published in 2016.

## What it deliberately does not include

A model. The generation step returns the labelled answer span for the paragraph that was retrieved, so
the pipeline is deterministic and every number it produces is about the cache rather than about somebody's
sampling temperature. What is being measured is how many generations the cache removed and how many of
its hits were wrong, and neither of those needs a model to be real.
