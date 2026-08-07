"""The guard rules of spec/guards/SPEC.md, in Python, from the specification.

This module exists to answer one question with a fact instead of a claim: can somebody who has never
read this repository's Kotlin implement its rules from the written specification and get the same
answers? Nothing here imports from the JVM, nothing here was translated line by line, and the marker
sets are read from spec/vocabulary/en.json rather than restated.

Run `score.py` to check it. It reproduces the published near-miss and paraphrase counts for
MatchGuards.standard() on every split, and passes every conformance vector.

Sections follow SPEC.md in order: the shared operations first, then one function per rule.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

# --- the shared operations ------------------------------------------------------------------------

# Unicode letters and digits, which is `[^\W_]` in Python once the underscore is excluded.
TOKEN = re.compile(r"[^\W_]+", re.UNICODE)
NUMBER = re.compile(r"\d+(?:[.,]\d+)*")
GROUPING_COMMA = re.compile(r",(\d{3})(?!\d)")
CLAUSE_PUNCTUATION = re.compile(r"[,;:]|\s[-—]\s")
SENTENCE_END = frozenset(".?!")

MIN_FUZZY_LENGTH = 5
MAX_SUFFIX_GROWTH = 3
MIN_REARRANGEMENT_LENGTH = 4
MIN_ACRONYM = 3
MAX_ACRONYM = 6

# Implementation-defined data, listed in SPEC.md because the vocabulary pack does not carry it.
ABBREVIATIONS = frozenset(
    "vs etc eg ie mr mrs ms dr prof st jr sr inc ltd co fig vol no approx al".split()
)
SYMMETRIC_COORDINATORS = frozenset("or vs versus between".split())
FRAMING_MARKERS = frozenset(
    "i me my mine we us our ours or either otherwise whatever anything any".split()
)


def tokens(text: str) -> list[str]:
    """Every run of letters or digits, lowercased, in order."""
    return [m.group(0).lower() for m in TOKEN.finditer(text)]


def content_tokens(text: str, stopwords: frozenset[str]) -> list[str]:
    """tokens(), minus stopwords, with duplicates dropped and the first occurrence winning."""
    seen: dict[str, None] = {}
    for token in tokens(text):
        if token not in stopwords:
            seen.setdefault(token, None)
    return list(seen)


def within_one_typo(a: str, b: str) -> bool:
    """One insertion, deletion, substitution, or a swap of two adjacent characters."""
    if a == b:
        return True
    if abs(len(a) - len(b)) > 1:
        return False

    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    same_length = len(shorter) == len(longer)

    i = 0
    while i < len(shorter) and shorter[i] == longer[i]:
        i += 1
    if i == len(shorter):
        return True

    if (
        same_length
        and i + 1 < len(shorter)
        and shorter[i] == longer[i + 1]
        and shorter[i + 1] == longer[i]
    ):
        return shorter[i + 2:] == longer[i + 2:]

    rest = i + 1 if same_length else i
    return shorter[rest:] == longer[i + 1:]


def _is_rearrangement(a: str, b: str) -> bool:
    if len(a) != len(b) or len(a) < MIN_REARRANGEMENT_LENGTH:
        return False
    if not any(c.isalpha() for c in a):
        return False
    return sorted(a) == sorted(b)


def same_word(a: str, b: str) -> bool:
    """The same word written differently: a typo, a transposition, a variant, an inflection."""
    if a == b:
        return True
    if len(a) < MIN_FUZZY_LENGTH or len(b) < MIN_FUZZY_LENGTH:
        return _is_rearrangement(a, b) or (len(a) != len(b) and within_one_typo(a, b))
    if within_one_typo(a, b):
        return True
    if _is_rearrangement(a, b):
        return True
    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    return len(longer) - len(shorter) <= MAX_SUFFIX_GROWTH and longer.startswith(shorter)


def _opens_sentence(text: str, matches: list[re.Match[str]], index: int) -> bool:
    previous = matches[index - 1]
    between = text[previous.end():matches[index].start()]
    terminator = next((c for c in between if c in SENTENCE_END), None)
    if terminator is None:
        return False
    if terminator != ".":
        return True
    previous_token = previous.group(0).lower()
    return len(previous_token) > 1 and previous_token not in ABBREVIATIONS


def entity_tokens(text: str, sentence_openers: frozenset[str], non_entity_capitals: frozenset[str]):
    """Capitalized tokens that plausibly name something, lowercased, in order, deduplicated."""
    matches = list(TOKEN.finditer(text))
    result: dict[str, None] = {}
    for index, match in enumerate(matches):
        token = match.group(0)
        if index == 0:
            continue
        if len(token) < 2:
            continue
        if not token[0].isupper():
            continue
        lowered = token.lower()
        if lowered in non_entity_capitals:
            continue
        if lowered in sentence_openers and _opens_sentence(text, matches, index):
            continue
        result.setdefault(lowered, None)
    return list(result)


def differs_only_by(a, b, ignored, stopwords, tolerance=0) -> bool:
    """Whether two prompts say the same thing apart from the words a rule is judging."""
    left = [t for t in content_tokens(a, stopwords) if t not in ignored]
    right = [t for t in content_tokens(b, stopwords) if t not in ignored]
    if len(left) != len(right):
        return False
    return sum(1 for i in range(len(left)) if not same_word(left[i], right[i])) <= tolerance


# --- the vocabulary -------------------------------------------------------------------------------


@dataclass(frozen=True)
class Vocabulary:
    stopwords: frozenset[str]
    sentence_openers: frozenset[str]
    non_entity_capitals: frozenset[str]
    negation_markers: frozenset[str]
    temporal_markers: frozenset[str]
    scope_markers: frozenset[str]
    directional_cues: frozenset[str]
    qualifier_openers: frozenset[str]
    antonyms: dict[str, frozenset[str]]
    units: dict[str, tuple[str, str]]

    @staticmethod
    def load(path: Path) -> "Vocabulary":
        raw = json.loads(path.read_text(encoding="utf-8"))
        opposites: dict[str, set[str]] = {}
        for left, right in raw["antonyms"]:
            opposites.setdefault(left, set()).add(right)
            opposites.setdefault(right, set()).add(left)
        return Vocabulary(
            stopwords=frozenset(raw["stopwords"]),
            sentence_openers=frozenset(raw["sentenceOpeners"]),
            non_entity_capitals=frozenset(raw["nonEntityCapitals"]),
            negation_markers=frozenset(raw["negationMarkers"]),
            temporal_markers=frozenset(raw["temporalMarkers"]),
            scope_markers=frozenset(raw["scopeMarkers"]),
            directional_cues=frozenset(raw["directionalCues"]),
            qualifier_openers=frozenset(raw["qualifierOpeners"]),
            antonyms={k: frozenset(v) for k, v in opposites.items()},
            units={k: (v["canonical"], v["dimension"]) for k, v in raw["units"].items()},
        )


# --- the rules ------------------------------------------------------------------------------------
#
# Each takes (query, candidate, vocabulary) and returns True to reject.


def numeric(query: str, candidate: str, _v: Vocabulary) -> bool:
    return _numbers(query) != _numbers(candidate)


def _numbers(text: str) -> list[str]:
    return sorted(
        GROUPING_COMMA.sub(r"\1", m.group(0)).replace(",", ".") for m in NUMBER.finditer(text)
    )


def unit(query: str, candidate: str, v: Vocabulary) -> bool:
    left = _units_in(query, v)
    right = _units_in(candidate, v)
    only_left = [u for u in left if u not in right]
    only_right = [u for u in right if u not in left]
    if not only_left or not only_right:
        return False
    swapped = [u for u in only_left if any(o[1] == u[1] for o in only_right)]
    return bool(swapped)


def _units_in(text: str, v: Vocabulary) -> list[tuple[str, str]]:
    found: dict[tuple[str, str], None] = {}
    for token in tokens(text):
        hit = v.units.get(token)
        if hit is not None:
            found.setdefault(hit, None)
    return list(found)


def temporal(query: str, candidate: str, v: Vocabulary) -> bool:
    left = {t for t in tokens(query) if t in v.temporal_markers}
    right = {t for t in tokens(candidate) if t in v.temporal_markers}
    if left == right:
        return False
    return differs_only_by(query, candidate, v.temporal_markers, v.stopwords, 0)


def negation(query: str, candidate: str, v: Vocabulary) -> bool:
    if _is_negated(query, v) == _is_negated(candidate, v):
        return False
    return differs_only_by(query, candidate, v.negation_markers, v.stopwords, 1)


def _is_negated(text: str, v: Vocabulary) -> bool:
    if "n't" in text.lower():
        return True
    return any(t in v.negation_markers for t in tokens(text))


def antonym(query: str, candidate: str, v: Vocabulary) -> bool:
    left = _antonym_counts(query, v)
    right = _antonym_counts(candidate, v)
    for word, count in left.items():
        opposites = v.antonyms.get(word)
        if opposites is None:
            continue
        if count <= right.get(word, 0):
            continue
        if any(right.get(o, 0) > left.get(o, 0) for o in opposites):
            return True
    return False


def _antonym_counts(text: str, v: Vocabulary) -> dict[str, int]:
    counts: dict[str, int] = {}
    for token in tokens(text):
        if token in v.antonyms:
            counts[token] = counts.get(token, 0) + 1
    return counts


def entity(query: str, candidate: str, v: Vocabulary) -> bool:
    left = entity_tokens(query, v.sentence_openers, v.non_entity_capitals)
    right = entity_tokens(candidate, v.sentence_openers, v.non_entity_capitals)
    if not left or not right:
        return False
    only_left = _not_spelled_out_in([e for e in left if e not in right], candidate, v)
    only_right = _not_spelled_out_in([e for e in right if e not in left], query, v)
    return bool(only_left) and bool(only_right)


def _not_spelled_out_in(entities: list[str], other: str, v: Vocabulary) -> list[str]:
    if not entities:
        return entities
    other_tokens = tokens(other)
    return [e for e in entities if not _is_spelled_out_by(e, other_tokens, v)]


def _is_spelled_out_by(acronym: str, other_tokens: list[str], v: Vocabulary) -> bool:
    if not MIN_ACRONYM <= len(acronym) <= MAX_ACRONYM:
        return False
    if len(other_tokens) < len(acronym):
        return False
    for start in range(len(other_tokens) - len(acronym) + 1):
        run = other_tokens[start:start + len(acronym)]
        if any(t in v.stopwords for t in run):
            continue
        if all(run[i][:1] == acronym[i] for i in range(len(acronym))):
            return True
    return False


def substitution(query: str, candidate: str, v: Vocabulary, min_tokens=4, head_min_tokens=None,
                 max_tokens=None) -> bool:
    left = content_tokens(query, v.stopwords)
    right = content_tokens(candidate, v.stopwords)
    if len(left) != len(right):
        return False
    if len(left) < min_tokens:
        return False
    if max_tokens is not None and len(left) > max_tokens:
        return False

    substituted = -1
    for index in range(len(left)):
        if _is_same_term(left[index], right[index], v):
            continue
        if substituted >= 0:
            return False
        substituted = index
    if substituted < 0:
        return False
    head_floor = min_tokens if head_min_tokens is None else head_min_tokens
    if substituted == 0 and len(left) < head_floor:
        return False
    return True


def _is_same_term(a: str, b: str, v: Vocabulary) -> bool:
    if same_word(a, b):
        return True
    left = v.units.get(a)
    return left is not None and left == v.units.get(b)


def scope(query: str, candidate: str, v: Vocabulary) -> bool:
    left = {t for t in tokens(query) if t in v.scope_markers}
    right = {t for t in tokens(candidate) if t in v.scope_markers}
    if left == right or not left or not right:
        return False
    return not (left >= right or right >= left)


def direction(query: str, candidate: str, v: Vocabulary) -> bool:
    if not _has_cue(query, v) and not _has_cue(candidate, v):
        return False
    left = content_tokens(query, v.stopwords)
    right = content_tokens(candidate, v.stopwords)
    if left == right:
        return False
    if set(left) != set(right):
        return False
    if _is_rotation_of(left, right, v):
        return False
    if _is_symmetric_selection(query, left, right):
        return False
    return True


def _has_cue(text: str, v: Vocabulary) -> bool:
    return any(t in v.directional_cues for t in tokens(text))


def _is_rotation_of(a: list[str], b: list[str], v: Vocabulary) -> bool:
    if len(a) != len(b) or len(a) < 2:
        return False
    if len(a) == 2:
        return any(t in v.directional_cues for t in a)
    doubled = a + a
    return any(doubled[i:i + len(b)] == b for i in range(len(a) + 1))


def _is_symmetric_selection(text: str, left: list[str], right: list[str]) -> bool:
    raw = tokens(text)
    if "than" in raw:
        return False
    first = next((i for i in range(len(left)) if left[i] != right[i]), None)
    if first is None:
        return False
    x, y = left[first], right[first]
    for i in range(len(raw) - 2):
        if {raw[i], raw[i + 2]} == {x, y} and raw[i + 1] in SYMMETRIC_COORDINATORS:
            return True
    return False


@dataclass
class _Span:
    opener: str | None
    tokens: list[str]
    framing: bool


def sub_span(query: str, candidate: str, v: Vocabulary) -> bool:
    left = content_tokens(query, v.stopwords)
    right = content_tokens(candidate, v.stopwords)

    if _contains_all(left, right) and len(left) > len(right):
        longer, shorter_tokens = query, right
    elif _contains_all(right, left) and len(right) > len(left):
        longer, shorter_tokens = candidate, left
    else:
        return False

    spans = _spans(longer, v)
    added = [
        i for i, span in enumerate(spans)
        if any(not any(same_word(s, t) for s in shorter_tokens) for t in span.tokens)
    ]
    if len(added) != 1:
        return False
    span = spans[added[0]]
    if span.opener is None:
        return False
    if added[0] != len(spans) - 1:
        return False
    if span.framing:
        return False
    return True


def _contains_all(superset: list[str], subset: list[str]) -> bool:
    return all(any(same_word(s, t) for s in superset) for t in subset)


def _spans(text: str, v: Vocabulary) -> list[_Span]:
    result: list[_Span] = []
    opener: str | None = None
    current: list[str] = []
    framing = False

    def flush() -> None:
        nonlocal opener, current, framing
        if current or opener is not None:
            result.append(_Span(opener, list(current), framing))
        opener, current, framing = None, [], False

    for chunk in CLAUSE_PUNCTUATION.split(text):
        for token in tokens(chunk):
            if token in v.qualifier_openers:
                flush()
                opener = token
            else:
                if token in FRAMING_MARKERS:
                    framing = True
                if token not in v.stopwords:
                    current.append(token)
        flush()
    return [s for s in result if s.tokens]


def lexical_divergence(query: str, candidate: str, v: Vocabulary, min_overlap=0.25,
                       min_tokens=5) -> bool:
    left = content_tokens(query, v.stopwords)
    right = content_tokens(candidate, v.stopwords)
    if len(left) < min_tokens or len(right) < min_tokens:
        return False
    available = list(right)
    shared = 0
    for token in left:
        index = next(
            (i for i, t in enumerate(available) if t == token or same_word(t, token)), None
        )
        if index is not None:
            available.pop(index)
            shared += 1
    union = len(left) + len(right) - shared
    if union == 0:
        return False
    return shared / union < min_overlap


# --- the chain ------------------------------------------------------------------------------------

STANDARD = [
    ("numeric", numeric),
    ("unit", unit),
    ("temporal", temporal),
    ("negation", negation),
    ("antonym", antonym),
    ("entity", entity),
    ("substitution", substitution),
    ("scope", scope),
    ("direction", direction),
    ("sub-span", sub_span),
    ("lexical-divergence", lexical_divergence),
]

SHORT_QUESTIONS = [
    (name, (lambda q, c, v: substitution(q, c, v, min_tokens=3, head_min_tokens=4))
     if name == "substitution" else rule)
    for name, rule in STANDARD
]


def rejects(chain, query: str, candidate: str, v: Vocabulary) -> bool:
    """Whether any rule in the chain rejects, in either direction. See spec/corpus/METRIC.md."""
    return any(
        rule(query, candidate, v) or rule(candidate, query, v) for _, rule in chain
    )
