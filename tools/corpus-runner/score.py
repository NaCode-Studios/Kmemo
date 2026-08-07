"""Scores a cache against a near-miss corpus, outside the JVM.

    python3 score.py --vectors --corpus ../../kmemo-core/src/jvmTest/resources/validation-corpus.json

Two things it does, and the second is the point.

**It checks the conformance vectors.** Every verdict in spec/guards/vectors.json is reproduced by the
Python rules in kmemo_guards.py, which were written from spec/guards/SPEC.md. A specification nobody
has implemented is a specification whose gaps nobody has found.

**It scores a corpus.** The false-hit rate and retention as spec/corpus/METRIC.md defines them, with
the Wilson interval, evaluating every pair in both directions. Run against this repository's own
splits it reproduces the published figures to the pair, which is what makes them figures about the
rules rather than about the implementation.

Nothing here imports anything from the JVM and there is no dependency to install.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import kmemo_guards as kg

HERE = Path(__file__).resolve().parent
SPEC = HERE.parent.parent / "spec"

Z_95 = 1.959964


def wilson95(successes: int, trials: int) -> tuple[float, float]:
    if trials == 0:
        return 0.0, 1.0
    n = float(trials)
    p = successes / n
    z2 = Z_95 * Z_95
    denominator = 1.0 + z2 / n
    centre = (p + z2 / (2 * n)) / denominator
    spread = Z_95 / denominator * math.sqrt(p * (1 - p) / n + z2 / (4 * n * n))
    return max(0.0, centre - spread), min(1.0, centre + spread)


def check_vectors(vocabulary: kg.Vocabulary) -> int:
    document = json.loads((SPEC / "guards" / "vectors.json").read_text(encoding="utf-8"))
    rules = dict(kg.STANDARD)
    failures = 0
    per_guard: dict[str, int] = {}
    for vector in document["vectors"]:
        rule = rules[vector["guard"]]
        actual = rule(vector["query"], vector["candidate"], vocabulary)
        per_guard[vector["guard"]] = per_guard.get(vector["guard"], 0) + 1
        if actual != vector["reject"]:
            failures += 1
            print(
                f"  MISMATCH {vector['guard']}: expected "
                f"{'reject' if vector['reject'] else 'accept'}, got "
                f"{'reject' if actual else 'accept'}\n"
                f"    query:     {vector['query']!r}\n"
                f"    candidate: {vector['candidate']!r}"
            )
    total = sum(per_guard.values())
    print(f"conformance vectors: {total - failures}/{total} reproduced across {len(per_guard)} rules")
    return failures


def score_corpus(path: Path, vocabulary: kg.Vocabulary, chain) -> dict:
    document = json.loads(path.read_text(encoding="utf-8"))
    pairs = document["pairs"]
    standing = document.get("standing", "in-sample")

    near = [p for p in pairs if not p["shouldMatch"]]
    para = [p for p in pairs if p["shouldMatch"]]
    caught = sum(1 for p in near if kg.rejects(chain, p["a"], p["b"], vocabulary))
    kept = sum(1 for p in para if not kg.rejects(chain, p["a"], p["b"], vocabulary))

    catch_low, catch_high = wilson95(caught, len(near))
    kept_low, kept_high = wilson95(kept, len(para))
    return {
        "corpus": path.stem,
        "standing": standing,
        "pairs": len(pairs),
        "nearMisses": len(near),
        "paraphrases": len(para),
        "caught": caught,
        "kept": kept,
        "falseHitRate": (len(near) - caught) / len(near) if near else 0.0,
        "catchRate": caught / len(near) if near else 0.0,
        "catchRateLow": catch_low,
        "catchRateHigh": catch_high,
        "retention": kept / len(para) if para else 0.0,
        "retentionLow": kept_low,
        "retentionHigh": kept_high,
    }


def print_score(score: dict) -> None:
    print(
        f"{score['corpus']:<28} ({score['standing']}) "
        f"near misses caught {score['caught']}/{score['nearMisses']} "
        f"({100 * score['catchRate']:.1f}% "
        f"[{100 * score['catchRateLow']:.1f}, {100 * score['catchRateHigh']:.1f}]), "
        f"paraphrases kept {score['kept']}/{score['paraphrases']} "
        f"({100 * score['retention']:.1f}% "
        f"[{100 * score['retentionLow']:.1f}, {100 * score['retentionHigh']:.1f}])"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", action="append", type=Path, default=[],
                        help="a corpus file conforming to spec/corpus/SCHEMA.json; repeatable")
    parser.add_argument("--vocabulary", type=Path, default=SPEC / "vocabulary" / "en.json")
    parser.add_argument("--vectors", action="store_true", help="check the conformance vectors")
    parser.add_argument("--chain", choices=["standard", "short-questions"], default="standard")
    parser.add_argument("--json", type=Path, help="write the scores here as JSON")
    args = parser.parse_args()

    vocabulary = kg.Vocabulary.load(args.vocabulary)
    chain = kg.STANDARD if args.chain == "standard" else kg.SHORT_QUESTIONS

    failures = check_vectors(vocabulary) if args.vectors else 0

    scores = []
    for path in args.corpus:
        score = score_corpus(path, vocabulary, chain)
        scores.append(score)
        print_score(score)

    if args.json and scores:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps({"chain": args.chain, "corpora": scores}, indent=2) + "\n",
                             encoding="utf-8")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
