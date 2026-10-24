# Why "Lab · producer + wine evidence" barely moved the numbers

Scope: the Phone Lab `lab-linked-wine-v1` run (`wine-168-lab-f9a8891dc192.csv`, 168 attempted
photos, 160 fully scorable) against the target of **>80% of forms completely correct and <5%
with more than one error**.

## 1. Demonstrated reason the gain is small

### The wiring is correct — the resolver does run and does reach the export

`PhoneCorpusRunner.Mode.LAB` sets `summaryStage = "spatial_evidence_summary"`, which is exactly
the row `TextLabSpatialPreview.append` writes after it mutates `producer` / `wine_name`. The raw
trace shows one `spatial_evidence_summary` row per image, no `producer_gazetteer_status` of
`unavailable`, no `BudgetExceeded`, and `producer_state = YELLOW` on the images it accepted. So
the asset opened on device, the resolver executed, and its decision is what the export scores.

### Lab is not identical to v2.0, but the delta is one field

Per-field outcomes for the Lab pipeline over all 168 photos:

| field | correct | wrong | missing |
|---|---|---|---|
| producer | 85 | 33 | 47 |
| wine_name | 77 | 44 | 36 |
| country | 142 | 0 | 15 |
| region | 116 | 0 | 37 |
| subregion | 74 | 3 | 35 |
| vintage | 145 | 0 | 5 |

Saved production v2.0 differs only on producer (76 correct / 42 missing). `wine_name`, `country`,
`region`, `subregion` and `vintage` are bit-identical. At image level that is
Complete 34 → 34, One error 43 → 48, 2+ errors 83 → 78: five photos moved from "two or more
errors" to "one error", and none reached "complete".

### The producer gate fires on 9 of 168 photos

`producer_gazetteer_status` is `suggestion` on 9 photos and `unchanged` on 159. `wine_name`
changes on 1 photo. Reason census for the 159 abstentions: 141 × `no independently supported
producer; unchanged`, 9 × `existing producer retained`, 4 × `different existing producer
protected`, 4 × `insufficient separation from competing producer; unchanged`,
1 × `ambiguous producer identities; unchanged`.

The wine side never gets a chance: `LinkedWineResolver` needs a producer candidate, and with no
producer candidate it returns `no independently observed producer for wine link` on 149 of 168
photos. The packaged wine index (101 326 names, 154 405 producer links) was consulted 3 times and
produced 1 accepted pair.

### The measured blocker: a rule that discards evidence before any check can run

`ProducerResolver` fetched `MAX_HITS+1 = 33` rows and then ran

```kotlin
// A saturated retrieval is not evidence of uniqueness.
if(hits.size > MAX_HITS) continue
```

A trigram-`OR` FTS query against a 33 160-key producer index is never short. Replaying the 168
photos' own recorded readings through the packaged index:

| | eligible spans | spans with a surviving candidate | spans dropped (window full) |
|---|---|---|---|
| current rule (fetch 33, drop when full) | 1 230 | 83 | **1 147 (93.3%)** |
| fixed rule (fetch 200, keep the span) | 1 230 | 122 | 0 |

So the fuzzy branch was dead code: it could only ever contribute an exact primary-key hit. That is
consistent with the candidate rows in the raw trace — all 84 `producer_gazetteer_candidate` rows
carry `edit_distance = 0`.

Wider retrieval is *not* the binding constraint once the drop is removed (fetch 33 → 200 → 4 000
changes nothing), and relaxing the edit gate is worth almost nothing either: 8 of 141
previously-abstaining photos become two-engine-viable at the current `≤1`/`≤2` threshold, 10 at a
uniform `≤2`. The binding constraint is that for **113 of the 141** abstentions neither engine
produces *any* reading within the matcher's tolerance of any primary-LWIN producer key, and for 19
more only one engine does — which the two-engine/disjoint-evidence rule correctly refuses.

### Why the target is out of reach from this feature

Even if producer and wine were always right, the complete-correct ceiling on these 160 photos is
**99 (62%)**, not >80%. The remaining bad fields are region 39, subregion 48, country 18 — none of
which this feature touches.

## 2. The fix

Replace the `MAX_HITS+1` fetch plus the `hits.size > MAX_HITS` drop with a bounded candidate fetch
that the existing precision rules actually see. `MAX_FUZZY_CANDIDATES = 200` replaces
`MAX_HITS+1`; the edit-distance gate, the two-engine/disjoint-observation rule, the separation rule,
the existing-producer protection and the ambiguity abstention all still run on every returned
candidate, unchanged. The same defect and the same change are applied to the linked-wine lookup
(`LinkedWineResolver`, `ProducerGazetteer.lookupWines`), which had the identical
`LIMIT 33` + `>32 → continue` pair.

Rejected after measurement, not adopted:

* **Raising the bound further.** 200 → 4 000 changes no outcome.
* **Treating a word boundary as OCR noise** (retry the edit gate on the space-stripped forms, so
  `lacave d augustin florent` matches `la cave d augustin florent`). On this export it moves one
  extra photo to correct and one to a wrong value (`'' → 'Hermitage'` against the reference
  `L'Héritage de Carillan`). Net zero, and it loosens the precision gate, so it stays out.

## 3. Tests actually executed

`tools/host-checks/producer_resolver_replay.py` is the executable half of this analysis. It
re-implements `ProducerResolver` and the `ProducerGazetteer` lookup exactly (normalize, `eligible`,
exact-key lookup, trigram `OR`, bm25 order, edit gate, two-engine/disjoint rule, separation,
existing-producer protection) and replays the readings the Lab run actually recorded, from the
owner's own two CSV exports, against the packaged `producers.sqlite`. Executed here:

```
python3 tools/host-checks/producer_resolver_replay.py \
    wine-168-lab-f9a8891dc192.csv wine-168-raw-lab-f9a8891dc192-part-01.csv \
    wine-168-raw-lab-f9a8891dc192-part-02.csv
```

* Current replay reproduces the Lab export's producer value on **167 of 168** photos.
* Current rule: 1 147 of 1 230 eligible spans dropped, 83 with a surviving candidate.
* Fixed rule: 0 spans dropped, 122 with a surviving candidate, images with a producer decision
  19 → 29.
* End-to-end, the fixed rule changes the exported producer on 4 photos: two go blank → correct
  (`Roche Mazet`), two normalise `DOMAINE ALLARY` → `Domaine Alary` (the primary LWIN spelling;
  both were already scored wrong against the longer reference `Domaine Allary Haut-Brion`).

Regression test added at `app/src/testDebug/java/com/yourapp/wine/gazetteer/ProducerResolverFuzzyLookupTest.kt`
covering: the fetch asks for the full bound; a full window no longer drops the span; two-engine
disjoint evidence is still required; overlapping observations are not independent support; an
unrelated existing producer is still protected; two viable identities still abstain.

## 4. Unverified Android/device behaviour

* **The Kotlin changes have not been compiled or run.** This checkout has no JDK, no `kotlinc` and
  no Android SDK, and `app/build.gradle.kts` carries a commented-out Kotlin Android plugin
  (`// This is the cause of the duplicate extension error`), so the module's buildability is
  unverified. The fix is a two-line behavioural change plus two `require` bounds; the arithmetic
  and the SQL are unchanged.
* **Cost.** `MAX_FUZZY_CANDIDATES = 200` replaces a 33-row fetch. The edit gate short-circuits on
  length difference, so only rows within ±2 characters of the span reach the full
  Levenshtein; on this export the mean fetch is ~74 rows per span. It is within the shared 500 ms
  deadline on the evidence available, but that is an estimate, not a device measurement.
* **The replay is a model, not the app.** It reproduces 167/168 exported producer values, so one
  photo differs for reasons not modelled (merged-phrase observation ids, `TextLabFormPreview`
  filtering). Absolute counts from the replay are ±1 photo.
* **Device-side confirmation is still required** before this ships: the yield check on the
  owner's photos, and confirmation that the 500 ms shared deadline still holds with the wider
  fetch.

## 5. Did measured recognition change?

Measured on the owner's export, offline: under this replay's comparison rule the Lab scores
producer correct 77, missing 47, wrong 41; with the fix applied it is correct 79, missing 45,
wrong 41 (the two `Roche Mazet` photos). `wine_name` is unchanged, and country/region/subregion/
vintage are untouched by this feature. The scoreboard's own comparison is slightly looser and
scores the Lab at 85 correct. The expected effect on the scoreboard is at most Complete 34 → 36 of
160 and no change to the 2+-error count. That is real but small, and it does not move the feature
toward the >80% / <5% target: the ceiling for this feature is 99/160 complete, and the remaining
gap is region, subregion and country, which are filled by the OCR/KB pipeline rather than by the
producer/wine gazetteer.

## Related finding: the OCR Text Lab cannot run this feature at all

`TextLabViewModel.run()` builds `TextLabBatch` with nine positional arguments, so `spatialPhrases`
— the tenth, defaulted — is always `false`, and `TextLabState` has no `spatialPhrases` field or
setter. Only `PhoneCorpusRunner` (`Mode.LAB`) enables it. The OCR Text Lab therefore never opens
the producer gazetteer; its `compareGazetteer` switch sets `gazetteer_enabled` metadata but the
actual resolution is gated on `spatialPhrases`. This did not cause the owner's result (they used the
phone-corpus Lab), but it means the feature is unreachable from the Lab entry point that the UI
presents.
