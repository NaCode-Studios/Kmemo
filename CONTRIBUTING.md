# Contributing to Kmemo

Thanks for your interest in contributing.

## Getting started

- JDK 17+ is required. No external services are needed; the whole suite runs offline.
- Build and test everything: `./gradlew build`.

## Pull requests

- Keep changes focused and covered by tests.
- Follow the existing style (`.editorconfig`: 4-space indentation, 120-column lines, no wildcard
  imports). The build runs with `allWarningsAsErrors`, so a warning fails CI.
- If you change the public API, run `./gradlew apiDump` and commit the updated `*.api` and `*.klib.api` files — CI
  runs `apiCheck` and will fail on an untracked API change.
- Add an entry under `[Unreleased]` in `CHANGELOG.md`.

## Changing the guards

The guards are the reason this library exists, so they have a stricter bar than the rest of the code.

- **Every guard change must be measured against the corpus**, not argued for. Run
  `./gradlew :kmemo-core:jvmTest --tests '*CorpusTest*'` and include the before/after numbers in
  the pull request.
- **A guard may never reject a genuine paraphrase.** The corpus carries 38 pairs that must stay
  cacheable precisely so that a guard which rejects everything cannot look perfect. That count is
  asserted; if your change drops one, the change is wrong, not the corpus.
- **Reject on positive evidence, abstain otherwise.** A guard that guesses is worse than no guard.
  The costs are asymmetric: a wrong rejection costs one API call, a wrong acceptance costs a wrong
  answer.
- **New near misses are welcome.** If you have found a pair that slips through, adding it to
  `kmemo-core/src/jvmTest/resources/near-miss-corpus.json` is a useful contribution on its own, even
  without a fix.

## Commit messages

- Use a clear, imperative subject line (for example, "Reject unit swaps by canonical name").

## Reporting issues

- For a false hit or a false rejection, include both prompts, the similarity if you have it, and
  which embedding model produced it. That pair is usually the fix.
- Otherwise include a minimal reproduction and the observed vs expected behavior.

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
