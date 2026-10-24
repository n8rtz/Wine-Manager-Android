# Producer gazetteer data quality — measured, and what it does not buy

Follow-up to `lab-linked-wine-gain-analysis.md`. The question was whether cleaning the
primary producer export (de-duplicating keys, dropping unusable rows) would unlock matches
the matcher currently refuses.

## What is actually in the asset

`app/src/debug/assets/producer-gazetteer-v1/producers.sqlite`, 33 160 rows:

| problem | rows | detail |
|---|---|---|
| duplicate normalized keys | 431 keys, 975 rows | 544 rows are redundant. Worst: `tussock jumper` ×11, `90 cellars` ×9, `the wine society` ×9, `bernard magrez` ×7 |
| duplicated keys sharing one name | 340 of 431 | pure duplicates |
| duplicated keys with different names | 91 | hyphen/spacing variants: `biondi santi` / `biondi-santi`, `cave saint pierre` / `caves saint-pierre` |
| keys shorter than 3 characters | 12 | `j`, `v`, `b3`, `bi`, `fe`, `io`, `jr`, `k5`, … |
| generic label words used as keys | 2 | `chateau`, `the` |

The `support` and `wine_producers` tables inside this database are empty, so nothing local
references a dropped row. (The wine side lives in `wines.sqlite`.)

## Why duplicates are harmful

`ProducerResolver` groups matches by **entity id**, not by key. A duplicated key therefore
produces several entities for one name, and `viable.size != 1` reads as
`ambiguous producer identities; unchanged` — a false abstention. Concretely, the shipped SQL
`SELECT id,name,key FROM producers WHERE key=? ORDER BY id LIMIT ?` returns 11 rows for
`tussock jumper`.

## Measured effect of the cleaning rule

Rule: keep one producer id per normalized key, drop keys under 3 characters, drop the two
generic keys. Modelled as a runtime predicate and replayed over the owner's 168-photo export
(`tools/host-checks/producer_resolver_replay.py`, `Index(ASSET, clean=True)`), with the exact
path made faithful to the shipped SQL (one row per duplicated id):

| | as shipped | cleaned |
|---|---|---|
| `ambiguous producer identities` | 5 (V0) / 6 (V1, V2) | **4 / 5** |
| `different existing producer protected` | 4 | 5 |
| every other decision | — | unchanged |
| **end-to-end producer outcomes** | 2 missing→correct, 2 wrong→wrong | **identical** |

So cleaning removes one or two spurious ambiguity abstentions and changes **no scored
outcome on any of the 168 photos**.

Cost: the `p.id = (SELECT MIN(p2.id) …)` predicate is a correlated subquery over 33 160 rows.
Measured against the packaged index, the fuzzy FTS query goes from **17.6 ms to 27.2 ms** —
about 70 ms per image at roughly seven eligible spans. That is inside the 500 ms shared
deadline, but it is being spent on nothing the scoreboard can see.

**Recommendation: do not put this in the query path.** De-duplicating belongs in the asset
build, where it is a one-time `GROUP BY key` over the LWIN export and costs nothing per
lookup. There is no asset-build script in this repository — `producer-gazetteer-v1/` is
committed as-is — so the fix is either a build step or a one-off regeneration, not a change to
`ProducerGazetteer.lookup`.

## The relaxation that does unlock images is a net loss

For completeness: the rule that actually attacks the 107-photo "no engine matched anything"
wall is a token-overlap match (accept when the fraction of shared words reaches a threshold,
instead of requiring whole-string edit distance ≤1/≤2). Threshold 0.75 moves 33 more photos
from "no engine matched" to "at least one engine matched".

Measured end-to-end on the same export:

| threshold | gained | lost |
|---|---|---|
| 0.70 – 0.75 | 2 missing→correct | **4 missing→wrong**, 2 wrong→wrong |
| 0.80 | 2 missing→correct | 2 wrong→wrong (no change from baseline) |

The four losses are generic-phrase collisions — `Grand Vin de Stellenbosch` matched against
labels whose producers are `Moulin du Paradis` and `Château Abelyce`, `Baron De La Charriere`
against `Baron de la Varière`. That is the failure you flagged: a phrase that appears on many
labels matching a real-sounding producer entry. Loosening the gate past 0.80 buys nothing.

## Where the wall actually is

107 of 168 photos have no engine reading within the matcher's tolerance of *any* primary-LWIN
producer key. The readings are not close-but-noisy; they are missing words —
`E D'AUGUSTIN FLORENT` for `la cave d augustin florent`, `CRU DU BEAUJOLAIS` for a producer
name — which is a drop of one or two words, i.e. edit distance ≥3. No gate threshold short of
the token-overlap rule tolerates that, and the token-overlap rule costs four wrong values for
every two it gains.

The honest conclusion is unchanged from the main report: the producer/wine gazetteer's ceiling
is 99/160 complete, and the gap to >80% is region, subregion and country, which this feature
does not touch.
