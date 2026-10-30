## Corpus composition (why "multilingual" is a weak argument here)

The 121 eligible labels are almost entirely French already: France 101, Spain 5, California 5,
Italy 5, Oregon 3, Argentina 2, Texas 1, Portugal 1, Chile 1. So the English-only
`urchade/gliner_small-v2.1` is being measured on a ~96% non-English-language corpus and still
reaches 81/121 complete.

Wine labels carry proper nouns (`Château Latour`, `Barolo`, `Rioja`); the lane names in the prompt
are ours, in English, and stage 2 only has to group and classify spans, not understand the
language. Where language actually costs us:

1. **Tokenisation of accents/diacritics** — a multilingual subword vocab splits accented producer
   names into fewer tokens, so word-span boundaries are cleaner. Plausible, unmeasured.
2. **Stage 1** — PP-OCRv6 / ML Kit Latin script already cover accented text; nothing to change.
3. **Stage 3/4** — producer and appellation coverage is a gazetteer question, not a language-model
   question; a multilingual checkpoint adds no producer names.

Conclusion: treat v2.5 as an A/B hypothesis to test on the French slice *after* the stage-3 record
fix, not as a reason to ship a bigger bundle now.

# GLiNER lane lab — host replay over the 121 eligible labels

First measured comparison of the new architecture against Lab v7, run entirely on the host over the
packaged cached OCR for the 124 labels (`app/src/main/assets/wine-app-labels-v1/ocr-raw-cache-v1.jsonl.gz`).

**Read this first:** the GLiNER-Small INT4 weights are not in this repository and could not be
downloaded here, so stage 2 is a stand-in. The numbers below bound the architecture; they are not
what the phone will score once the real bundle is installed.

## What ran

| Stage | In this run |
| --- | --- |
| 1 (OCR) | **cached readings** from the packaged journals — the same ML Kit + PP-OCRv6 rows Lab v7 replays, not fresh pixels |
| 1b (fusion) | **real** `GlinerFusion.fuse` — both engines' readings, one line when text+place agree |
| 2 (GLiNER) | **stand-in**: `none` (no lanes at all) or `oracle` (reference answers matched to word spans = a perfect labeller) |
| 3 (post-processing) | **real and unchanged** — `GlinerLaneRecords.records` → `TextLabFormPreview.append` + `TextLabSpatialPreview.append(recordsOverride=…)` with the Lab v7 module set and `Policy.singleReading` |

Baseline for the same 121 eligible labels, produced by the existing harness in this same sandbox:
`bash tools/run-host-lab.sh` → **81/121 complete, 115/121 ≤1 error, parity gate PASS**.

## Results

| Run | Complete (6/6) | ≤1 error | producer | wine_name | country | region | subregion | vintage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Lab v7 (baseline) | **81/121** | 115 | 102 | 98 | 124 | 124 | 122 | 123 |
| lanes `none` (fusion only) | 79/121 | 115 | 101 | 96 | 124 | 124 | 123 | 123 |
| `oracle` (perfect lanes, replace residual) | 73/121 | 115 | **103** | 88 | 124 | 124 | 123 | 123 |
| `oracle-additive` (perfect lanes, keep residual) | 76/121 | 115 | **103** | 91 | 124 | 124 | 123 | 123 |
| `oracle-all` (every accepted synonym as its own span) | 72/121 | 115 | 103 | 87 | 124 | 124 | 123 | 123 |

Per-label diffs against the baseline (gains/losses) are in each run's `report.md` under
`tools/host-lab/out/gliner-*`.

## What the numbers say

1. **The fusion + re-grouping plumbing is sound.** With no lanes at all, the same evidence through
   `GlinerFusion` + `GlinerLaneRecords` scores 79/121, two labels from Lab v7, and ≤1-error is
   unchanged at 115. Nothing in the record bridge collapses the pipeline.
2. **Grouping helps producer and does not touch geography.** Producer goes 102 → 103 correct in
   every lane mode; country/region/subregion/vintage are unchanged because the lanes never ask for
   them and the residual still carries them.
3. **Wine name is the casualty, and it is a plumbing problem, not a model problem.** A perfect
   labeller costs 7–11 wine-name labels (98 → 87…91). The lane turns the wine name into one grouped
   observation with a union box across lines; the existing wine-name rules were built on per-line
   readings with their variant ids, and they decide differently when the text arrives as a lane
   group. Keeping the residual (`-additive`) recovers about half of it.
4. **Therefore the ceiling of this architecture as plumbed is below Lab v7** (76/121 vs 81/121) even
   with a labeller that cannot make a mistake. Buying the INT4 bundle would not change that on its
   own.

## What to try next (before spending space on weights)

These are stage-3 changes, testable with the same harness in ~70 s per run:

* **Lane as a hint on the raw records, not as a replacement record.** Keep one record per fused
  line, tag the words a lane claimed (`variantId="lane:producer"` on the existing record, or a
  lane field), and let the existing rules see the same evidence with better labels. This removes
  the "two competing copies of the wine name" effect entirely.
* **Keep the line boxes inside a lane.** A lane fragment that spans three lines should become three
  observations that share a group id, not one tall union box.
* **Only lane the text the rules cannot reach.** Producer benefited; wine name and appellation are
  already handled reasonably by the existing path — a lane that adds evidence for producer alone
  would be a smaller, safer change.
* **Fix the fusion loss first** (`none` = 79 vs 81): the lane path drops the OCR `variantId` and
  therefore any variant-sensitive rule. Carrying `variantId` through `GlinerFusion` should make
  `none` reproduce the baseline exactly, which is the precondition for trusting any lane delta.

## Caveats

* Stage 2 is a stand-in. The oracle is an upper bound *given the OCR text*: 23 labels have no
  producer match and 27 no wine-name match in their OCR, so even the oracle sends no such lane.
* The host replay feeds cached readings, so OCR quality, timing and budgets are not validated.
* Host stand-ins (`tools/logic-checks/Android*`, `tools/host-lab/sqlite_bridge.py`) replace Android
  classes and the SQLite gazetteer; the Lab v7 baseline is reproduced by the same stand-ins, so the
  comparison is apples to apples.
* The lane records currently set `variantId` to `lane:*`/`residual` and drop the original OCR
  variant id. That is one of the concrete differences this run exposes.

## Reproduce

```sh
bash tools/run-host-lab.sh                              # Lab v7 baseline (parity gate)
bash tools/run-host-gliner-lane.sh none                 # fusion only
bash tools/run-host-gliner-lane.sh oracle               # perfect lanes, replacing residual
bash tools/run-host-gliner-lane.sh oracle-additive      # perfect lanes, residual kept
bash tools/run-host-gliner-lane.sh oracle-all           # every accepted synonym as a span
```

Each run writes `output.tsv`, `stats.tsv`, `report.md` (with per-label gains/losses) under
`tools/host-lab/out/gliner-<tag>/`.
