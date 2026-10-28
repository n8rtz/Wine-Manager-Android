# Step 1: spatial phrase merging

> Current Lab update: original OCR observations now feed guarded extraction unchanged. Spatial joins are alternatives for the producer resolver only, not replacement observations. See `lab-primary-producer-resolver.md`. Older replacement-adapter descriptions below are historical.

Implemented in `merge/SpatialPhraseMerger.kt`, with a Lab-only mapped-token adapter in `textlab/TextLabSpatialPhrases.kt` (debug source set).

## Contract

Input is a list of `Detection(text, Box(x1,y1,x2,y2))`. Output is a list of `Phrase(mergedText, boundingBoxes, sourceIndices)`. The corresponding JSON shape is:

```json
{
  "merged_text": "Domaine de la Romanée-Conti",
  "bounding_boxes": [[0, 0, 120, 10], [130, 0, 250, 10]]
}
```

The individual source boxes are retained in reading order, not replaced with a single enclosing rectangle. `sourceIndices` refer back to the original input list, allowing original text and engine confidence/provenance to remain available. No confidence score or extra engine vote is manufactured.

### Horizontal rules

- Sort fragments left-to-right, with deterministic geometry/text tie-breaks.
- For left-hand A and right-hand B, B's vertical midpoint must be within A's vertical span (inclusive).
- Gap `B.x1 - A.x2` must be **strictly less than** `2.5 * A.width / characterCount(A.text)`.
- Character count uses Unicode code points in the original text, including spaces; supplementary characters are not counted twice.
- Negative gaps allow overlapping boxes. Linked fragments form line components; comparisons use original fragment widths rather than a growing union box. A predicate sees the accumulated left/right text so semantically safe multi-token producer names can continue across words.
- The generic merger's horizontal predicate is permissive by default; the Lab adapter defaults both semantic predicates to **false** and enables joins only when its producer policy is available.

### Vertical rules

- Lines are ordered top-to-bottom, left-to-right for equal top positions.
- A lower line must have a later top and vertical midpoint than the upper line. Slight vertical overlap is allowed.
- Horizontal intersection must be **strictly greater than 50% of the shorter line's width**. This explicitly allows short centered continuations. It is not bounding-box area overlap or IoU.
- Vertical gap must be **strictly less than 0.8 times the upper line's height**.
- Competing links are resolved by nearest relative vertical gap, then horizontal center alignment and deterministic indices. A line has at most one predecessor and one successor; blocks are chains, not forks.
- Every vertical comparison uses the original line box, never the growing phrase envelope. This avoids a growing block absorbing unrelated nearby columns.
- The generic merger exposes a vertical height-ratio cap, a maximum component line count, and a caller-supplied semantic predicate. Defaults cap components at **two lines** with a height ratio of **1.45**; the Lab adapter uses the same numeric limits and disables vertical joins unless the producer-role policy approves them.

Merged text is left-to-right within each line, then top-to-bottom across lines, with standard spaces. Whitespace is normalized for output; accents, case and punctuation are retained. Blank-text detections are omitted. Invalid/degenerate/non-finite boxes are rejected. Input is bounded to 512 detections per call; oversized calls fail explicitly rather than silently dropping text. Work is quadratic within that bound.

## Semantic boundaries and evidence-graph joins

Spatial proposals are supplemental producer-lookup spans; they never replace or alter original OCR observations. The Lab producer policy rejects vintage/year, packaging/legal framing, broad geography, varietal/style, and upper-line wine-description spans. An appellation can remain part of a producer lookup only when the upper line has an explicit producer cue. The catalog's `appellation` role is distinct from broad `geography`: for example, `Chianti Classico` is an appellation, while `Willamette Valley` is geography.

`EvidenceGraphBuilder` independently enforces same-engine **and** same-variant/pass provenance for every multi-observation phrase (including connector joins); adapter grouping alone is not sufficient. Its optional wine-title proposal is also vertical, two-line-only, and limited to an identity-like upper title plus a varietal or appellation lower line. It rejects vintage, legal/packaging, and broad-geography boundaries. These joins create extraction hypotheses without changing source readings or manufacturing OCR support.

## Coordinate and engine boundaries

Use a single image and coordinate frame per call. Pixel coordinates or normalized coordinates work, but do not mix them or original/rectified frames. The debug adapter uses only `TokenRecord.bboxRect` (rectified normalized space), after the existing inverse mappings have been applied.

The adapter groups by **engine ID and variant/pass ID** before merging. ML Kit and Paddle readings are alternative observations, not words to concatenate. Duplicate observations within one stream remain distinct sources; deduplication/consensus is not performed by this stage. Original token objects (including original-frame boxes, raw text and scores) are attached to each proposal.

## Scope and limitations

The **Lab** benchmark option enables this module as a supplemental producer/linked-wine lookup path. Production v1.0 and Production v2.0 do not enable it. Spatial proposals never replace the observations sent to extraction, and a proposal is not an extra OCR vote. Source indices retain original token objects, boxes, raw text and scores; any temporary merged text is only a lookup span. The raw form anchor and evidence extractor continue to use unchanged original readings.

Lab executes/scores only this latest variant, not a separate v2.0 contender. The scorer uses `spatial_evidence_summary`, with `spatial_phrase` and `spatial_evidence_*` trace rows available in raw CSV. There is no metadata subtraction or residual-cleanup stage in this update.

**Update (2026-10-27, relabeled):** both benchmark spatial contenders run the current raw-preserving pipeline described in the 2026-10-25 update — original readings feed extraction unchanged and spatial phrases serve only as lookup spans for the producer/linked-wine resolvers. They differ only in producer data: **Prod v4.0** (current ID `production-v4.0-lwin-only`; the supplied pre-refresh capture retains `production-v3.0-lwin-only`, stage `spatial_lwin_evidence_summary`, candidate `spatial_lwin_lab`) resolves producers against the verified LWIN producer index only, while **Lab v5** (current ID `lab-v5-expanded-producers`; the supplied capture retains `lab-v4-expanded-producers`, stage `spatial_evidence_summary`, candidate `spatial_lab`) uses the Lab expanded producer index (the same LWIN subset plus source-tagged X-Wines/TTB/OpenStreetMap winery candidates; see `producer-source-expansion.md`). The historical replacement-readings behavior described below is NOT used by any current contender; a brief experiment restoring it as Prod v3.0 (2026-10-26, one build) scored 0/35 fully correct and was reverted the same day.

Geometry alone cannot distinguish a wrapped producer from a nearby cuvée, legal line or unrelated slogan. Preserve original readings alongside proposals; later semantic stages must be able to reject inappropriate joins. Future residual subtraction must remain a parallel candidate path, not erase evidence from the independent identity pipeline.

## Validation

`tools/run-spatial-phrase-checks.sh` compiles the actual merger and Lab adapter using Kotlin 2.2.10 / Java 21, with the repository's host Android geometry fixtures. The regressions cover horizontal/vertical geometry, bounded two-line output, height ratios, disabled-by-default semantic joins, source preservation and engine/pass grouping.

`tools/logic-checks/EvidenceArchitectureChecks.kt` adds positive bounded-title cases (`Inscription` + `Pinot Noir`; `Peppoli` + `Chianti Classico`) and negative vintage, geography, appellation-framing, cross-engine/pass and connector joins. It also exercises the producer-role policy on supplemental spans.

In this workspace, `python3 -m unittest discover -s tools/tests` passed **268 tests**, and `git diff --check` passed. The Kotlin spatial and full logic runners could not execute because Java/JDK, Kotlin and Android SDK tools are absent. No Android build, fresh OCR, baseline OCR or accuracy-impact measurement was run. Earlier counts of 85 merger/adapter checks and 12 extraction integration checks refer to a previous implementation and are not a result for these new guards.
