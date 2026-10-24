# Partial-exact matching — is it allowed, and does it help?

Follow-up to `lab-linked-wine-gain-analysis.md` and `producer-gazetteer-data-quality.md`.

## The rule: whole-string edit distance only

`ProducerResolver.resolve` accepts a candidate on exactly one condition:

```kotlin
val d = distance(q, e.key)
if (d <= if (q.length >= 20) 2 else 1) matches += Match(...)
```

`normalize()` lowercases, strips accents and punctuation, and collapses whitespace — so
`Blackstone` and `blackstone` are the same string. But there is **no prefix, substring or
partial-exact rule anywhere**. The only fuzzy tolerance is that edit-distance gate, and
`distance()` short-circuits to `3` whenever `abs(len(a) - len(b)) > 2`:

| OCR span | gazetteer key | what happens |
|---|---|---|
| `blackstone` | `blackstone winery` | length diff 8 → `distance()` returns 3 → gate is ≤1 → **rejected** |
| `drouhin` | `drouhin vaudon` | length diff 5 → returns 3 → **rejected** |
| `château amelisse` | `chateau amelisse` | distance 0 after normalize → **accepted** |

## The example never reaches the gate

`eligible()` requires `words.size in 2..8`. A lone `Blackstone` is one word, so it is
rejected before the matcher runs at all. Measured over the owner's 168-image export (3 683
OCR spans total):

| | spans |
|---|---|
| reach the matcher gate | **1 230** (33%) |
| killed by `eligible()` | **2 453** (67%) |

Breakdown of the 2 453 (a span can hit several rules):

| reason | count |
|---|---|
| single word | 1 316 |
| normalized length < 8 | 1 200 |
| contains a digit | 963 |
| noise word (`imported`, `distributed`, …) | 657 |
| no content word ≥4 chars outside the generic list | 534 |
| more than 8 words | 284 |
| longer than 96 chars | 96 |
| contains `%` or newline | 58 |

So the biggest gate in the producer path is not the distance gate — it is the span
eligibility filter, and its largest single cause is spans that are one word long.

## Would a partial rule recover them? Measured, no

Four partial rules were modelled and replayed over all 168 photos, with the baseline being
the adopted skip-removal fix (which alone yields 2 missing→correct, 0 regressions):

| rule | spans newly accepted | FTS retrieval returns them | images reaching a 2-engine decision | moved outcomes |
|---|---|---|---|---|
| prefix, trailing words must be filler | 12 | 12 | 8 | 2 missing→correct, **1 missing→wrong**, 2 wrong→wrong |
| prefix, any trailing words | 24 | 24 | 13 | 2 missing→correct, **2 missing→wrong** |
| query words as a contiguous run anywhere | 92 | 92 | 49 | 2 missing→correct, **1 missing→wrong** |
| all query words present, any order | 95 | 95 | 50 | 2 missing→correct, **1 missing→wrong** |

Three things worth taking from this:

1. **Retrieval is not the blocker.** The FTS trigram index returns 100% of the spans each
   rule would newly accept (12/12, 24/24, 92/92, 95/95). A partial rule would need no new
   index, no new SQL — only a second predicate on the candidates already returned.
2. **Every variant is a precision regression.** The best case gains the same 2 corrections the
   skip-removal fix already gets and adds at least one new wrong value. The looser rules add
   a lot of noise the downstream guards have to absorb: `ambiguous producer identities` rises
   5 → 8 → 17 → 17, and `insufficient separation` 1 → 4 → 4 → 5.
3. **Looser rules buy coverage, not correctness.** `contains all` lifts images with a two-engine
   decision from 34 to 50, but every one of those extra decisions is absorbed by the
   separation or existing-producer guards, so the scoreboard does not move.

## The 1 316 lone single-word spans, if that rule were relaxed

296 of them would prefix-match a producer key. The sample is not encouraging:

| span | would match |
|---|---|
| `DROUHIN` | `drouhin`, `drouhin vaudon` — a genuine 1-char OCR miss |
| `OREGON` | `oregon solidarity`, `oregon territory` — a **region**, not a producer |
| `ESTATE` | `estate de wetshof`, `estate manolesakis` — a generic filler word |
| `R`, `B` | `r b cellars`, `b cellars` — abbreviations |

One of four is a real rescue; the rest are place names and filler colliding with producer
entries. That is the same failure mode as the token-overlap gate measured in the previous
report, and it is why the 2-8 word bound exists.

## Conclusion

Partial-exact matching is not implemented and is not the lever. The "Blackstone" pattern is
lost, but it is lost **upstream of the matcher** — in `eligible()`'s word-count bound — and a
partial-match rule added at the gate would not recover it. What the measurement does show is
that the gate itself is worth a closer look, because it discards 67% of all OCR spans before
any matching happens.
