# The guard rules

A guard reads two prompts and decides whether one's cached answer may be served in answer to the
other. It returns **reject** with a reason, or **accept**, which is not a claim that the match is good
but only that this guard found no evidence against it.

Every rule below is a rule about text. None of them is a rule about Kotlin, and this document exists
so that a cache written in Python, Go or TypeScript can implement them from the rule rather than from
somebody else's source. `vectors.json` beside this file carries a decided verdict per guard per pair,
so a disagreement fails a build instead of surfacing as a wrong answer eighteen months later.
`../vocabulary/en.json` carries the markers, because the markers are data: an implementation that
supplies its own Italian negations is still conformant.

## What conformance means

An implementation is conformant for a guard when it reproduces that guard's **verdicts** on every
vector naming it, reading the marker sets from a vocabulary file of the shape `en.json` has.

Three things are deliberately outside conformance.

**The reason string.** It is prose for a human reading a miss. Two conformant implementations may
word it differently or write it in another language.

**The tokenizer.** Nothing here requires a particular splitter, a particular data structure or a
particular internal organisation. Where a rule depends on a specific tokenization, this document
states the tokenization as part of the rule rather than leaving it to be discovered.

**The markers.** They are per-language data. An implementation shipping only Italian markers is
conformant for Italian and simply has no English vectors to run.

## The shared operations

Six operations appear in more than one rule. They are stated once here.

### `tokens(text)`

Every maximal run of Unicode letters or digits, lowercased, in order, with everything else acting as
a separator. Regular-expression form: `[\p{L}\p{N}]+`.

`"Convert 100 USD to EUR"` gives `[convert, 100, usd, to, eur]`.

### `contentTokens(text, stopwords)`

`tokens(text)` with the stopwords removed and **with duplicates dropped, first occurrence winning**.

The deduplication is load-bearing and is the detail a reimplementation gets wrong first. `"format on
save on windows"` gives `[format, save, windows]` once `on` is a stopword, and a prompt that repeats
a content word contributes it once. Rules that compare positions compare these lists, so a repeated
word does not shift the alignment.

### `sameWord(a, b)`

Whether two tokens are the same word written differently: a typo, a transposition, a spelling
variant, or an inflection. Not whether they are synonyms.

1. Equal strings are the same word.
2. If either token is shorter than **5** characters, they are the same word only when one is a
   rearrangement of the other (see 4) or when their lengths differ and they are within one typo (see
   3). Substitution is withheld at this length because it is the whole difference between `cat` and
   `cut`.
3. **Within one typo**: lengths differ by at most one, and one of a single insertion, a single
   deletion, a single substitution, or a swap of two adjacent characters turns one into the other.
   Transpositions count as one edit rather than two, because they are the commonest way people
   mistype.
4. **A rearrangement**: equal lengths of at least **4** characters, at least one letter present, and
   the same multiset of characters.
5. **An inflection**: the longer token starts with the shorter one and exceeds it by at most **3**
   characters. That covers `-s`, `-ed` and `-ing` and does not reach a different word.

The one-edit cap is deliberate. At two edits `Austria` and `Australia` collapse into one token and
every rule built on this waves through the swap it exists to catch.

### `entityTokens(text, sentenceOpeners, nonEntityCapitals)`

The capitalized tokens that plausibly name something, lowercased, as a set.

Walk the tokens of `text` in order and keep a token when all of these hold:

- it is not the first token of the text;
- it is at least 2 characters long;
- its first character is uppercase;
- its lowercase form is not in `nonEntityCapitals`;
- it is **not** both in `sentenceOpeners` and opening a sentence.

A token opens a sentence when the text between it and the previous token contains `.`, `?` or `!`.
A `.` does not end a sentence when the previous token is a single letter or is one of the
abbreviations listed under [Implementation-defined data](#implementation-defined-data). A `:` or `;`
never ends a sentence.

Two failed approaches are recorded because both are the obvious ones. Treating `:` and `;` as
boundaries loses `Java` in `"Compare Python vs. Java"` and `Austria` in `"Country: Austria. Give me
the capital."`, which is the one field that varies between two templated prompts. Exempting only the
very first token of the text turns the opening word of every later sentence into an entity, so
`"…in CSS? Show me an example."` and `"…in CSS? Give me an example."` read as an entity swap.

### `differsOnlyBy(a, b, ignored, stopwords, tolerance)`

Whether two prompts say the same thing apart from a set of words a rule is judging.

Take `contentTokens` of each side, drop every token in `ignored`, and require the two lists to have
equal length and to differ, by `sameWord`, in at most `tolerance` positions.

This is what stops a marker rule firing on two unrelated prompts. A negation is evidence when it is
the only difference and is incidental when the prompts are worded differently throughout.

### `rejectsEitherWay(guard, a, b)`

Every rate this specification defines evaluates a pair **in both directions**, because either prompt
could be the one already in the cache when the other arrives. See `../corpus/METRIC.md`.

## The rules

### `numeric`

Reject when the two prompts do not contain the same numbers.

Extract every match of `\d+(?:[.,]\d+)*`. Normalise each: first remove any comma followed by exactly
three digits not followed by a fourth, which is a grouping separator, then read any comma that
remains as a decimal point. Sort the resulting strings and compare the two lists. Reject when they
differ, including when one side has a number the other does not.

Both halves of the comma rule are load-bearing and each covers a false hit the other lets through.
Dropping every comma turns `3,5` into `35`. Splitting on the comma turns `3,5` into `3` and `5`,
which as an unordered multiset is indistinguishable from `5,3`. Only parsing produces one number that
differs from both.

### `unit`

Reject when each prompt names a unit of measure that the other does not, and the two named units
measure the same kind of quantity.

Collect the units of each side by looking up every token in the unit table. Take the units present on
one side and absent from the other, in both directions. Accept when either of those is empty, which
is the addition case rather than the substitution case: `"375 f to c"` and `"What is 375 degrees
Fahrenheit in Celsius?"` name the same two units, one of them spelled out. Otherwise reject only when
some unit exclusive to one side shares its dimension with a unit exclusive to the other. A mass
appearing where a currency appears is two spellings of one question, not a swap.

Units are compared by canonical name, so `km` and `kilometers` are one unit.

### `temporal`

Reject when the two prompts pin the question to different moments.

Collect the tokens in the temporal marker set on each side. Accept when the two sets are equal.
Accept also when `differsOnlyBy(query, candidate, markers, stopwords, 0)` is false, which is the
condition that keeps `current` meaning a date in `"the current CEO"` and a fixed technical term in
`"the current branch"`. Otherwise reject.

Years are digits and belong to `numeric`. This rule covers the words that carry a date without
carrying a digit.

### `negation`

Reject when one prompt is negated and the other is not, and that is the only difference.

A prompt is negated when its text contains `n't` case-insensitively, or when any of its tokens is in
the negation marker set. Accept when the two sides agree. Otherwise reject only when
`differsOnlyBy(query, candidate, markers, stopwords, 1)`.

The tolerance of one is deliberate and it is the difference between catching and missing the pairs
that matter: `"foods you should eat while pregnant"` against `"foods you should not eat during
pregnancy"` differs by a negation and one synonym. Two or more differences mean the prompts were
written independently and the negation is incidental.

### `antonym`

Reject when one prompt uses a word more often than the other while the other uses that word's
opposite more often.

Build a symmetric map from the antonym pairs, so each word points at its opposites. Count, per side,
the occurrences of every token that appears in that map. Then for each word the query uses strictly
more often than the candidate, reject if any of its opposites is used strictly more often by the
candidate than by the query.

Counting rather than testing membership does two things. `"Run this before deploy"` and `"run this
prior to deploy"` differ by `before`, and nothing anywhere says `after`, so there is no flip. And
`"turn on format on save"` against `"turn off format on save"` still flips, even though both contain
`on`, because the query uses `on` twice to the candidate's once.

### `entity`

Reject when each prompt names something the other does not.

Take `entityTokens` of each side. Accept when either is empty. Take the entities exclusive to each
side, then drop from each set any entity the other prompt spells out in full. Accept when either
remaining set is empty; otherwise reject.

An entity of between **3** and **6** characters is spelled out by the other prompt when some run of
that many consecutive tokens, none of them a stopword, has exactly those initials in order. Both
bounds are load-bearing. At two letters `US` is spelled out by *use software* and every `US`/`UK` swap
is waved through. Requiring content words stops `API` finding *a programming interface*.

### `substitution`

Reject when the two prompts have the same content words in the same order and differ in exactly one
position.

Take `contentTokens` of each side. Accept when the lengths differ, when the length is below the floor
(**4**), or when a maximum is configured and the length exceeds it. Walk the two lists in step; a
position counts as differing when the two tokens are neither `sameWord` nor two spellings of one unit.
Accept when the number of differing positions is not exactly one; otherwise reject.

The floor is a crossover between the evidence in the agreeing part, which grows with length, and the
risk that the one differing position is a synonym rather than a swap, which does not. Four is
measured rather than derived. An optional variant applies a separate, higher floor when the differing
position is the first content word, which is where a question keeps its verb.

The unit clause keeps this rule consistent with `unit`, which already knows `utc` and `gmt` are one
offset. Without it the two rules would disagree about the same two tokens.

### `scope`

Reject when the two prompts ask for different shapes of answer.

Collect the tokens in the scope marker set on each side. Accept when the sets are equal, when either
is empty, or when one contains the other. Otherwise reject.

The containment clause is the addition case again: asking for `"an overview and an example"` still
wants the example. A depth request only one side spells out, such as `"how does HTTPS work"` against
`"how does HTTPS work at the packet level"`, is residue this rule does not cover.

### `direction`

Reject when the same words appear in an order that reverses the question.

Accept when neither prompt contains a directional cue. Take `contentTokens` of each side and accept
when the two lists are equal, when their sets differ, when one is a rotation of the other, or when
the two swapped terms are listed as alternatives. Otherwise reject.

**A rotation** is `b` equal to some window of `a + a` of length `|b|`. At exactly two tokens every
permutation is a rotation, so at that size the pair counts as a rotation only when one of the two
tokens is itself a cue: in `"how do I migrate in Rails"` the cue is one of the two content tokens, so
the other cannot be its counterpart.

**Listed as alternatives** means: the text contains no `than`, and, taking the first position at
which the two content-token lists differ and calling those two tokens `x` and `y`, the raw token list
contains `x`, a coordinator, `y` in three consecutive positions, in either order. The coordinators are
`or`, `vs`, `versus`, `between`. Adjacency is required: an `or` elsewhere in the prompt has nothing to
do with the swap, and `"convert dollars to euros or pounds"` against `"convert euros to dollars or
pounds"` is a reversed conversion with an unrelated alternative attached.

### `sub-span`

Reject when one prompt is the other plus a clause that narrows the question.

Take `contentTokens` of each side. One side is the longer when every token of the shorter has a
`sameWord` counterpart in it and it has strictly more tokens; accept when neither side is.

Split the longer prompt into spans. A span ends at `,`, `;`, `:` or at ` - ` surrounded by spaces, and
also ends whenever a qualifier opener is met, which begins a new span carrying that opener. A span
keeps its non-stopword tokens and remembers whether any of its tokens, stopwords included, was a
framing marker. Spans with no tokens are dropped.

Now find the spans holding a token with no `sameWord` counterpart among the shorter side's tokens.
Accept unless there is exactly one. Accept when it has no opener, when it is not the last span, or
when it is framing. Otherwise reject.

Each condition demands that the addition be the answer-bearing kind rather than merely present. The
last-span rule separates a condition attached to a question from a preamble put in front of it. The
framing rule separates a clause about the asker from a clause about the answer.

### `lexical-divergence`

Reject when the two prompts share too few content words.

Take `contentTokens` of each side and accept when either has fewer than **5**. Count the shared
tokens by greedy one-to-one pairing: walk the query's tokens, and for each, remove the first
candidate token that is equal or `sameWord`, counting one. Compute Jaccard as shared over
`|query| + |candidate| - shared`, accept when the denominator is zero, and reject when the ratio is
below **0.25**.

This is the backstop under the specialised rules, for the swaps nobody wrote a rule for. It fires
almost never, and the reason is in the corpora rather than in the rule: two prompts sharing nothing
are not a near miss anybody writes down, they are two unrelated questions, and they only ever reach a
guard because an embedder proposed one for the other.

## Implementation-defined data

Three lists are read by the rules above and are **not** in the vocabulary pack. They are hard-coded in
the reference implementation, which means a non-English implementation cannot replace them without
forking the rule. That is a finding about the guards rather than about this format, and it is recorded
here rather than papered over.

**Abbreviations** whose trailing period does not end a sentence, read by `entityTokens`:

```
vs etc eg ie mr mrs ms dr prof st jr sr inc ltd co fig vol no approx al
```

**Symmetric coordinators**, read by `direction`:

```
or vs versus between
```

**Framing markers**, read by `sub-span`:

```
i me my mine we us our ours or either otherwise whatever anything any
```

The first list is tokenizer data and belongs with the tokenizer. The other two are language markers
and belong in the vocabulary pack; that they are not there is a gap, and moving them is a change to a
public type rather than to a rule.

## Parameters

Every threshold a rule refers to, in one place, with the value the reference implementation ships.

| Rule | Parameter | Value |
| --- | --- | --- |
| `sameWord` | shortest token allowed to fuzzy-match | 5 |
| `sameWord` | longest suffix an inflection may add | 3 |
| `sameWord` | shortest rearrangement | 4 |
| `entity` | shortest acronym tested for expansion | 3 |
| `entity` | longest acronym tested for expansion | 6 |
| `substitution` | content words needed | 4 |
| `substitution` | content words needed for a head difference, when the variant is used | 4, against 3 elsewhere |
| `substitution` | maximum content words, when bounded | 12 |
| `negation` | positions of difference tolerated | 1 |
| `temporal` | positions of difference tolerated | 0 |
| `lexical-divergence` | content words needed | 5 |
| `lexical-divergence` | minimum overlap | 0.25 |
