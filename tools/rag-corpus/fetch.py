"""Fetches the retrieval corpus: SQuAD v1.1 dev, as documents, questions and labelled answers.

Run it from this directory once the environment in README.md exists:

    .venv/bin/python fetch.py

It writes ../../build/rag-corpus/squad-dev.json and prints the SHA-256 of the source and the converted
file. `RagPipelineTest` in the examples module reads the converted one.

## Why a fetch and not a vendored file

The corpus is somebody else's work under somebody else's licence, so it stays theirs. That is the same
reason the external guard split is fetched, and it has the same cost: the corpus is absent on a machine
that has never run this, and the test says so out loud rather than passing quietly.

## What the shape is for

Each record is one question: the paragraph it was asked about, the question, and the answer marked
inside that paragraph. A retrieval-augmented pipeline is exactly that with the retrieval labelled, so
whether a generated answer was right is a lookup rather than a judgement, and no model has to be trusted
or paid to produce the measurement.
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import sys

import requests

SOURCE = "https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v1.1.json"
OUT = pathlib.Path(__file__).resolve().parents[2] / "build" / "rag-corpus" / "squad-dev.json"

# Enough paragraphs for retrieval to be a real choice and few enough that the example runs in seconds.
# Taken as a prefix rather than a sample, so the file is identical on every machine.
MAX_DOCUMENTS = 400


def main() -> int:
    print(f"fetching {SOURCE}")
    response = requests.get(SOURCE, timeout=120)
    response.raise_for_status()
    raw = response.content
    print(f"  {len(raw):,} bytes, sha256 {hashlib.sha256(raw).hexdigest()}")

    payload = json.loads(raw)
    documents: list[dict[str, str]] = []
    questions: list[dict[str, str]] = []

    for article in payload["data"]:
        for paragraph in article["paragraphs"]:
            if len(documents) >= MAX_DOCUMENTS:
                break
            document_id = f"d{len(documents)}"
            documents.append(
                {"id": document_id, "title": article["title"], "text": paragraph["context"]}
            )
            for qa in paragraph["qas"]:
                answers = qa.get("answers") or []
                if not answers:
                    continue
                questions.append(
                    {
                        "id": qa["id"],
                        "documentId": document_id,
                        "question": qa["question"],
                        "answer": answers[0]["text"],
                    }
                )
        if len(documents) >= MAX_DOCUMENTS:
            break

    converted = {
        "source": SOURCE,
        "licence": "CC BY-SA 4.0",
        "documents": documents,
        "questions": questions,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(converted, ensure_ascii=False, indent=2)
    OUT.write_text(text, encoding="utf-8")
    print(f"  wrote {OUT}: {len(documents):,} documents, {len(questions):,} questions")
    print(f"  converted sha256 {hashlib.sha256(text.encode('utf-8')).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
