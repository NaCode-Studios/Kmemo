# What a verifier catches that the guards do not

The guards reject most near misses on the blind corpora and let the rest through. `Verifier` is the
seam that exists for exactly those, and until now its catch rate on them was unmeasured — the README
said so, which was honest but left the number a reader most wants missing.

`measure.py` runs a named reference verifier over every lookup the guards still serve and records what
it decided about each one. `VerifierCatchRateTest` turns those verdicts into the published rate.

## Running it

```bash
python3.11 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python measure.py
```

The first run downloads the model, so it needs network access.

## The reference implementation, named

`Verifier` is caller-supplied, so a catch rate is only interpretable against a named implementation and
a named model. This one is `sentence_transformers.CrossEncoder` over
**`cross-encoder/quora-distilroberta-base`**, a duplicate-question classifier, serving when the
duplicate probability reaches 0.5. Both names travel with every number it produces.

A cross-encoder rather than a language model is a deliberate choice and not only a cheaper one: it is
free, so anyone can rerun this, and the shape is already the one this project treats as
verifier-shaped. Your own verifier will behave differently, and that is the point of the seam. What
this measures is the *shape* of the answer — how much of the residual is reachable at all by a model
that reads the two prompts — not what your model will do.

## It records verdicts, never a rate

The population moves. Improve a prompt-side guard and the residual shrinks, which would leave a
recorded percentage quietly describing a set that no longer exists. So this file contains no
percentage: it contains what the reference verifier decided about each lookup, and the JVM side
intersects that with the residual it recomputes on the day it runs.

It is also **not a CI floor**, deliberately. A gate that spends a model call on every build is a gate
that gets deleted, and a floor on a number produced by somebody else's model would be a floor on the
wrong thing. The build checks that the verdicts still describe the current corpus and that every
residual lookup has one; it does not check that the rate is good.

## The paraphrases are scored too

A verifier that refuses everything has a perfect catch rate. The harness scores the paraphrase lookups
the guards keep for the same reason the guard measurements do: the catch rate is worth nothing without
the hit rate it costs beside it.
