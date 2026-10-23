# Step 1: spatial phrase merging

Implemented in `merge/SpatialPhraseMerger.kt`, with an opt-in mapped-token adapter in `textlab/TextLabSpatialPhrases.kt` (debug source set).

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
- Negative gaps allow overlapping boxes. Linked fragments form line components; comparisons use original fragment widths rather than a growing union box.

### Vertical rules

- Lines are ordered top-to-bottom, left-to-right for equal top positions.
- A lower line must have a later top and vertical midpoint than the upper line. Slight vertical overlap is allowed.
- Horizontal intersection must be **strictly greater than 50% of the shorter line's width**. This explicitly allows short centered continuations. It is not bounding-box area overlap or IoU.
- Vertical gap must be **strictly less than 0.8 times the upper line's height**.
- Competing links are resolved by nearest relative vertical gap, then horizontal center alignment and deterministic indices. A line has at most one predecessor and one successor; blocks are chains, not forks.
- Every vertical comparison uses the original line box, never the growing phrase envelope. This avoids a growing block absorbing unrelated nearby columns.

Merged text is left-to-right within each line, then top-to-bottom across lines, with standard spaces. Whitespace is normalized for output; accents, case and punctuation are retained. Blank-text detections are omitted. Invalid/degenerate/non-finite boxes are rejected. Input is bounded to 512 detections per call; oversized calls fail explicitly rather than silently dropping text. Work is quadratic within that bound.

## Coordinate and engine boundaries

Use a single image and coordinate frame per call. Pixel coordinates or normalized coordinates work, but do not mix them or original/rectified frames. The debug adapter uses only `TokenRecord.bboxRect` (rectified normalized space), after the existing inverse mappings have been applied.

The adapter groups by **engine ID and variant/pass ID** before merging. ML Kit and Paddle readings are alternative observations, not words to concatenate. Duplicate observations within one stream remain distinct sources; deduplication/consensus is not performed by this stage. Original token objects (including original-frame boxes, raw text and scores) are attached to each proposal.

## Scope and limitations

This is a geometry-only proposal module for the new Lab work. It is **not automatically enabled** in the existing production or guarded-producer pipelines, and it has not yet been added to benchmark output. There is no metadata subtraction, residual cleanup or producer/name assignment in Step 1.

Geometry alone cannot distinguish a wrapped producer from a nearby cuvée, legal line or unrelated slogan. Preserve original readings alongside proposals; later semantic stages must be able to reject inappropriate joins. Future residual subtraction must remain a parallel candidate path, not erase evidence from the independent identity pipeline.

## Validation

`tools/run-spatial-phrase-checks.sh` compiles the actual merger and debug adapter using Kotlin 2.2.10 / Java 21, with the repository's host Android geometry fixtures. Checks cover the supplied name example, horizontal/vertical strict boundaries, overlap, short continuations, separate columns, multi-line chaining, shuffled input, Unicode, whitespace, invalid boxes, bounds, source preservation and engine/pass separation.

These tests are not Android/Compose compilation, fresh OCR or evidence of accuracy improvement on the 168 images. No baseline OCR was rerun.
