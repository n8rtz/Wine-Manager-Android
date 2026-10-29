# Host Lab replay harness

Iterate **Lab v5 post-OCR logic on the host in ~90 seconds** instead of a
phone build/run/export cycle. The phone is kept for final verification only.

## What it runs (and what it does not)

- **Real Kotlin, real code:** `tools/run-host-lab-replay.sh` compiles the same
  Lab source set as `tools/run-spatial-preview-checks.sh`
  (`merge/`, `extract/`, `fill/`, `textlab/evidence/`, `TextLabSpatialPreview`,
  `ProducerResolver`, `LinkedWineResolver`, …) plus
  `tools/logic-checks/HostLabReplay.kt`, and replays every packaged
  cached-OCR journal (`app/src/main/assets/wine-app-labels-v1/ocr-raw-cache-v1.jsonl.gz`,
  124 labels) through `TextLabFormPreview` + `TextLabSpatialPreview` with
  `ProducerResolver.Policy.singleReading` — the exact benchmark Lab path.
- **Real reference data:** producer/wine gazetteer lookups go to
  `tools/host-lab/sqlite_bridge.py`, which mirrors
  `ProducerGazetteer.lookup/lookupWines` SQL-for-SQL against the packaged
  `producers-expanded.sqlite` + `wines.sqlite`. The JVM cannot spawn child
  processes in this sandbox, so the shell driver starts the bridge as a
  localhost socket server; on a normal workstation the same script also
  works over stdin/stdout.
- **Same knowledge base:** the runner uses `FixtureKnowledgeBase`, the exact
  snapshot `SqliteWineKnowledgeBase.open` seeds the phone KB from (the
  extractors wrap it in `SupplementedWineKnowledgeBase` on both sides).
  One known divergence risk remains: the phone `findProducer` shortlists via
  SQLite FTS5 while the fixture shortlists in memory. On the current corpus
  and baseline module set the measured effect is zero (see parity below).
- **Not validated here:** ML Kit / Paddle OCR itself, gazetteer setup/lookup
  budgets (the host passes no lookup deadline, so the phone's 2 producer /
  5 wine budget-blocked labels are not reproduced as blocked), Android
  SQLite asset loading, UI, and fresh-OCR behaviour. Those stay with the
  owner's final phone verification.

## Parity gate (mandatory before trusting any variant)

A host run is only meaningful if the **unmodified** pipeline first
reproduces the phone baseline from `docs/lab-v5-124-diagnostic-a709ad6.md`:

```sh
bash tools/run-host-lab.sh            # baseline module set, tag "baseline"
```

Expected: `Complete 53/122 · ≤1 error 111/122`, per-field
producer 96, wine_name 72, country 124, region 124, subregion 122,
vintage 123, and `Parity gate: PASS`. The first harness run also matched
the phone export **label-for-label: 0 differing fields out of 124×6**.
If parity ever FAILs, fix the harness, not the pipeline.

## Iterating

```sh
# One module-set variant per run; each run is prepare + compile + replay + score.
bash tools/run-host-lab.sh none control-none     # example control (49/122, net −13)

# Per-label gains/losses against any earlier run:
BASELINE_OUTPUT=tools/host-lab/out/baseline/output.tsv \
  bash tools/run-host-lab.sh "<module+keys>" my-variant
```

Module keys are `LabPostOcrModules` signature keys (e.g.
`blank_subregion_fuzzy+vintage_recovery+final_producer_near_match+producer_role_particle+spatial_semantic_guards`
is the phone baseline set; `none` is the pre-improvement control).
Code changes to the Lab Kotlin sources are picked up automatically —
the harness always compiles the current tree. Outputs land in
`tools/host-lab/out/<tag>/` (`input.tsv`, `output.tsv`, `report.md`),
which is git-ignored. Set `DEBUG_ROWS=/abs/path/debug.tsv` to also
dump every journal row (base64 fields) for the run -- used to trace
why a module abstained on a given label.

## Toolchain (pinned, no upgrades)

Defaults match the other host scripts: JDK 21 at
`.ocr-harness/tools/jdk4py/java-runtime` and Kotlin 2.2.10 at
`.ocr-harness/tools/kotlin/package` (both git-ignored), or point
`JAVA_HOME` / `KOTLIN_HOME` at any JDK 21 / Kotlin 2.2.10 install.
In the Arena sandbox these were provisioned from allowed hosts only:
`jdk4py==21.0.8.2` (PyPI wheel) and the npm `kotlin-compiler@2.2.10`
tarball, which is the full JVM distribution.

## Known pre-existing failure (not caused by this harness)

`bash tools/run-spatial-preview-checks.sh` at `a709ad6` fails check 29
(bottle-filter fixture: expected Lab `spatial_source_count=6 /
spatial_filter_dropped=6`, actual `8 / 4`) on unmodified app sources.
Logged as Bug-028 in `_refs/BACKLOG.md`; do not "fix" it as part of a
Lab accuracy iteration without the owner's go-ahead.
