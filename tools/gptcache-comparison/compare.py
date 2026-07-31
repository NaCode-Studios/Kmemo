"""Scores kmemo's blind corpora with GPTCache's own similarity evaluator.

Run it from this directory once the environment in README.md exists:

    .venv/bin/python compare.py

It writes ../../kmemo-core/src/test/resources/gptcache-comparison.json, which
ComparativeBenchmarkTest reads to render the third row of the comparison table. The corpus digests
in that file are checked by the test, so a corpus that changed without this being re-run fails the
build rather than publishing a stale number.

## The trap this script exists to defuse

`OnnxModelEvaluation.evaluation` wraps its whole body in `except Exception: return 0`. Every internal
failure — a missing model file, a tokenizer API that moved, a bad tensor shape — is reported as a
similarity of zero, which reads as "GPTCache confidently refused this pair". A harness that calls the
documented entry point and records what comes back gets 0.000 for every pair and concludes GPTCache
refuses everything: a false-hit rate of 0.000 and a recall of 0.000. That flatters kmemo and is
completely false.

So nothing here trusts a score until the evaluator has been shown to work:

1. `verify_evaluator` runs `inference` directly, outside the try/except, so a broken install raises.
2. It then checks the evaluator serves a trivial restatement and refuses a completely unrelated
   sentence. A model that loads but scores noise fails here.
3. Every 0.0 produced during the run is re-scored through `inference`. A genuine zero survives;
   a swallowed exception raises.

The gate is deliberately **mechanical, not semantic**. It asks whether the evaluator works at all,
never whether it agrees with kmemo about a hard pair — a gate built from disputed pairs would reject
GPTCache for disagreeing rather than for being broken, and disagreement is the thing being measured.
"""

from __future__ import annotations

import hashlib
import json
import platform
import sys
from datetime import date
from pathlib import Path

from gptcache.similarity_evaluation.onnx import OnnxModelEvaluation

HERE = Path(__file__).resolve().parent
RESOURCES = HERE.parent.parent / "kmemo-core" / "src" / "jvmTest" / "resources"
CORPORA = {"held-out": "held-out-corpus.json", "validation": "validation-corpus.json"}

# GPTCache's own decision rule, from gptcache/adapter/adapter.py:
#   rank_threshold = (max_rank - min_rank) * similarity_threshold * cache_factor
#   ... clamped into [min_rank, max_rank], then serve when rank_threshold <= rank.
# similarity_threshold defaults to 0.8 in gptcache.config.Config and cache_factor to 1.0. Using
# GPTCache's own rule rather than one of our choosing is the whole point: the comparison is between
# two caches as they ship, not between two caches as we would have configured them.
SIMILARITY_THRESHOLD = 0.8
CACHE_FACTOR = 1.0

# The two pairs the evaluator has to get right before any of its other answers are believed. Both are
# chosen to be beyond argument: one sentence restated with the clause order moved, and two sentences
# with nothing whatever in common. Neither is from the corpora, so passing this gate leaks nothing
# about the measurement, and neither is a pair kmemo and GPTCache could reasonably disagree about.
TRIVIAL_RESTATEMENT = ("How do I reverse a list in Python?", "In Python, how can I reverse a list?")
UNRELATED = ("How do I reverse a list in Python?", "What is the capital of France?")


class EncodePlusShim:
    """Restores the one tokenizer method GPTCache calls and transformers 5 removed.

    `OnnxModelEvaluation.inference` calls `tokenizer.encode_plus(a, b, padding="longest")`.
    `encode_plus` was removed in transformers 5, and calling the tokenizer directly is the documented
    replacement, so this forwards one to the other. It also asks for `token_type_ids`, which the ALBERT
    tokenizer no longer returns by default and which the ONNX model requires as an input.

    The alternative was pinning transformers back to 4.57.6, where `encode_plus` still exists. That
    version carries two high-severity advisories with no fix below 5, so it would have meant allowing
    them in CI for the sake of a method name. Six lines of shim buys a harness that runs on current
    libraries with nothing allowed.
    """

    def __init__(self, tokenizer):
        self._tokenizer = tokenizer

    def __getattr__(self, name):
        return getattr(self._tokenizer, name)

    def encode_plus(self, text_a, text_b, **kwargs):
        return self._tokenizer(text_a, text_b, return_token_type_ids=True, **kwargs)


def verify_evaluator(evaluator: OnnxModelEvaluation, threshold: float) -> dict[str, float]:
    """Refuses to continue unless the evaluator demonstrably works. See the module docstring."""
    # Straight through `inference`, so a broken install raises instead of returning 0.
    restatement = evaluator.inference(TRIVIAL_RESTATEMENT[0], [TRIVIAL_RESTATEMENT[1]])
    unrelated = evaluator.inference(UNRELATED[0], [UNRELATED[1]])

    for label, score in (("restatement", restatement), ("unrelated pair", unrelated)):
        if not isinstance(score, float) or score != score:  # NaN is never equal to itself
            sys.exit(f"the evaluator returned {score!r} for the {label}; refusing to measure")

    if restatement < threshold:
        sys.exit(
            f"the evaluator scored a trivial restatement at {restatement:.4f}, below its own serving "
            f"threshold of {threshold:.4f}. It would refuse everything, and a false-hit rate of 0.000 "
            "measured that way says nothing about GPTCache. Refusing to measure."
        )
    if unrelated >= threshold:
        sys.exit(
            f"the evaluator scored two unrelated sentences at {unrelated:.4f}, at or above its own "
            f"serving threshold of {threshold:.4f}. It would serve everything. Refusing to measure."
        )
    return {"trivialRestatement": restatement, "unrelated": unrelated}


def rank_threshold(evaluator: OnnxModelEvaluation) -> float:
    minimum, maximum = evaluator.range()
    threshold = (maximum - minimum) * SIMILARITY_THRESHOLD * CACHE_FACTOR
    return min(max(threshold, minimum), maximum)


def score_pair(evaluator: OnnxModelEvaluation, query: str, cached: str) -> float:
    """The documented entry point, with the swallowed-exception case flushed out."""
    score = evaluator.evaluation({"question": query}, {"question": cached})
    if score == 0:
        # Either a genuine zero or a swallowed exception. `inference` tells them apart by raising.
        confirmed = evaluator.inference(query, [cached])
        if confirmed != 0:
            sys.exit(
                "evaluation() returned 0 where inference() returned "
                f"{confirmed:.4f} for:\n  {query}\n  {cached}\n"
                "That is the swallowed-exception path. Every number from this run is suspect."
            )
    return float(score)


def measure(evaluator: OnnxModelEvaluation, pairs: list[dict], threshold: float) -> dict:
    """Precision, recall, F1 and false-hit rate, scored exactly as ComparativeBenchmarkTest does.

    "Serve this cached answer" is the positive prediction, and one direction per pair — `a` arriving
    as the query against `b` already in the cache — because that is the convention the Kotlin side
    uses. A comparison that scored two directions here and one there would not be a comparison.
    """
    served_paraphrases = served_near_misses = 0
    paraphrases = near_misses = 0
    for pair in pairs:
        served = score_pair(evaluator, pair["a"], pair["b"]) >= threshold
        if pair["shouldMatch"]:
            paraphrases += 1
            served_paraphrases += served
        else:
            near_misses += 1
            served_near_misses += served

    served = served_paraphrases + served_near_misses
    precision = 1.0 if served == 0 else served_paraphrases / served
    recall = served_paraphrases / paraphrases
    f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
    return {
        "precision": round(precision, 4),
        "recall": round(recall, 4),
        "f1": round(f1, 4),
        "falseHitRate": round(served_near_misses / near_misses, 4),
        "nearMissesServed": served_near_misses,
        "nearMisses": near_misses,
        "paraphrasesServed": served_paraphrases,
        "paraphrases": paraphrases,
    }


def versions() -> dict[str, str]:
    import gptcache
    import numpy
    import onnxruntime
    import transformers

    return {
        "python": platform.python_version(),
        "gptcache": gptcache.__version__,
        "transformers": transformers.__version__,
        "onnxruntime": onnxruntime.__version__,
        "numpy": numpy.__version__,
    }


def main() -> None:
    evaluator = OnnxModelEvaluation()
    evaluator.tokenizer = EncodePlusShim(evaluator.tokenizer)
    threshold = rank_threshold(evaluator)
    gate = verify_evaluator(evaluator, threshold)
    print(f"evaluator verified at threshold {threshold:.4f}: trivial restatement "
          f"{gate['trivialRestatement']:.4f}, unrelated {gate['unrelated']:.4f}")

    corpora = []
    for name, filename in CORPORA.items():
        raw = (RESOURCES / filename).read_bytes()
        pairs = json.loads(raw)["pairs"]
        result = measure(evaluator, pairs, threshold)
        print(f"{name}: {result}")
        corpora.append({
            "corpus": name,
            "corpusSha256": hashlib.sha256(raw).hexdigest(),
            **result,
        })

    payload = {
        "measuredOn": date.today().isoformat(),
        "note": (
            "Measured out of band: GPTCache is a Python package and CI is a JVM build. "
            "Reproduce with tools/gptcache-comparison/README.md."
        ),
        "evaluator": "gptcache.similarity_evaluation.onnx.OnnxModelEvaluation",
        "model": "GPTCache/albert-duplicate-onnx",
        "tokenizer": "albert-base-v2",
        "decisionRule": (
            f"serve when score >= (max - min) * {SIMILARITY_THRESHOLD} * {CACHE_FACTOR}, "
            "GPTCache's own rule with its own defaults"
        ),
        "rankThreshold": threshold,
        "sanityCheck": gate,
        "versions": versions(),
        "corpora": corpora,
    }
    destination = RESOURCES / "gptcache-comparison.json"
    destination.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {destination}")


if __name__ == "__main__":
    main()
