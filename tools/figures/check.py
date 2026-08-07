"""Checks that the published documents still say what the measurements said.

    python3 check.py

No Gradle, no Kotlin, no JVM. It reads `docs/figures.json`, which the build regenerates from the
tests that compute each number, and asserts that every rendered claim still appears in the document
that is supposed to carry it. A measurement that moves therefore fails here until somebody edits the
prose.

That is the cheap half of reproducibility and it is the half nothing else does. The corpus floors
catch a guard whose rate slips; nothing catches a sentence in a README that stopped being true three
releases ago.

The expensive half lives in `tools/corpus-runner`, which recomputes the corpus figures themselves
from the published specification, with no part of this repository's source involved.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
FIGURES = ROOT / "docs" / "figures.json"


def main() -> int:
    document = json.loads(FIGURES.read_text(encoding="utf-8"))
    figures = document["figures"]
    texts = {name: (ROOT / name).read_text(encoding="utf-8") for name in document["documents"]}

    rendered = [f for f in figures if f.get("claim")]
    failures = []
    for figure in rendered:
        for name in figure["documents"]:
            if figure["claim"] not in texts[name]:
                failures.append((figure["id"], name, figure["claim"]))

    for figure_id, name, claim in failures:
        print(f"  {name} no longer carries {figure_id}:\n    {claim}")

    covered = len(rendered)
    registered = len(figures) - covered
    print(
        f"{covered - len({f[0] for f in failures})}/{covered} rendered figures still match; "
        f"{registered} more are registered with their command and not rendered"
    )
    if failures:
        print(
            "\nThe measurement is right and the prose is stale. Edit the document, or regenerate "
            "docs/figures.json if the measurement was meant to move."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
