# GLiNER lane lab (new structure, built on Lab v7)

Settings → Scanner testing → **GLiNER lane lab — PP-OCRv6 ➔ GLiNER-Small INT4**.

This screen starts a structurally different reader next to Lab v7, not a new set of modules inside it:

```
[PP-OCRv6 Medium det+rec] ➔ [GLiNER-Small (ONNX, INT4)] ➔ [existing Lab v7 post-processing]
```

Lab v7 keeps one long rule stack deciding what a line *means* while it also decides what the line
*is*. The new screen splits those jobs. OCR only reads text. GLiNER-Small is the contextual brain:
it is given the whole label as one word sequence and asked for three concepts, so scrambled
fragments (`CHATEAU`, `MARGAUX`, a crest's `2015`) are grouped by meaning rather than by geometry
and heuristics:

| Lane | Prompt sent to GLiNER | Becomes |
| --- | --- | --- |
| `PRODUCER` | `wine producer or winery name` | producer observations |
| `WINE_NAME` | `wine name or cuvee` | wine-name observations |
| `APPELLATION` | `wine appellation, region or country` | appellation observations for the existing geography hierarchy |
| residual | — (words no lane claimed) | one observation per OCR line, for the vintage normalizer and any geography the lanes did not take |

Country, region, subregion and vintage are deliberately **not** asked from GLiNER. Those four keep
their existing owners: the place hierarchy, the producer gazetteer and the year normalizer, which
already perform better than a zero-shot span model on label geography.

## Why three lanes and not six

GLiNER is a zero-shot span labeller. Its useful contribution on a wine label is deciding which
fragments belong together and which of three coarse roles they play. Asking it for six narrow
fields (country vs region vs subregion) invites near-duplicate prompts, cross-lane leakage and a
second, weaker geography resolver competing with the one the app already ships. One unambiguous
prompt per lane also makes the flat-NER decoding rule meaningful: no word may be accepted for two
lanes, so a producer name cannot also become the wine name.

## Stage contracts

### Stage 1 — PP-OCRv6 Medium (`GlinerOcr.kt`)

The same pinned `v6-medium-det.onnx` + `v6-medium-rec.onnx` + `ppocrv6_dict.utf8` the production
scanner and Lab v7 use, with the same DB thresholds and preprocessing. Output: line text, the
rectified-label box and the mean CTC posterior. No ML Kit reference pass, no auto-zoom and no
spatial phrases run in this stage.

### Stage 2 — GLiNER-Small INT4 (`GlinerOnnxModel.kt`, `GlinerTokenizer.kt`, `GlinerDocument.kt`)

`GlinerDocument` puts detector-ordered lines into reading bands (top to bottom, left to right inside
a band) and flattens them to words. Each word keeps its OCR line and an interpolated box.

The graph contract is the export GLiNER's own `export_to_onnx()` produces for the uni-encoder span
model (`urchade/gliner_small-v2.1`):

| Input | Meaning |
| --- | --- |
| `input_ids` | `[CLS]`, then `ent_token, label, ent_token, label, ent_token, label, sep_token`, then label words, then `[SEP]` |
| `attention_mask` | all ones |
| `words_mask` | 1-based word index on the first subtoken of each text word, 0 on prompt/continuation subtokens |
| `text_lengths` | number of text words |
| `span_idx` | every `(start, end)` word span with `end-start+1 <= max_width`, shorter spans first |
| `span_mask` | all valid |

Prompt markers (`ent_token`, `sep_token`), `max_width` and `max_len` come from the bundle's
`gliner_config.json`; the tokenizer is the Hugging Face `tokenizer.json` from the same export
(Unigram/SentencePiece, BPE and WordPiece are supported).

The output layout changed between GLiNER releases, so it is detected from the real tensor shape and
reported on screen instead of being assumed:

* `[spans, classes]`, `[1, spans, classes]`, `[1, classes, spans]`, `[1, spans, classes, 1]` → span logits
* `[1, words, classes, 3]` → token start / end / inside logits

Unknown shapes fail closed with the actual shape and the expected span/class counts in the message.
Scores are sigmoid probabilities; accepted spans are ordered by score and a span is dropped when any
of its words is already used (flat NER).

### Stage 3 — existing Lab v7 post-processing (`GlinerLabRunner.kt`)

No new extraction rules were written. Lane fragments and residual lines become `TokenRecord`s and
are handed to `TextLabSpatialPreview.append(..., recordsOverride = ...)`, i.e. the same contender
that produces Lab v7's `spatial_evidence_summary`:

* production `FillEngine` anchor (`form_summary`) computed from the lane observations,
* `EvidenceExtractor` + `EvidenceAnchorHybrid` + `ProducerRepair`,
* expanded producer gazetteer and linked-wine resolver,
* the integrated Lab v7 module set (`LabPostOcrModules.none.withIntegrated()`),
* `ProducerResolver.Policy.singleReading`.

Only one thing changed in the existing pipeline: `recordsOverride` (a nullable parameter that
leaves every other caller byte-identical). The experiment is the *input readings*, not the rules.

Record conventions:

* every observation uses engine id `paddle-gliner-v1` — lane text and residual text are two views of
  one OCR pass and must never count as two supporting engines;
* `variantId` is `lane:producer`, `lane:wine_name`, `lane:appellation` or `residual`;
* the score is the mean CTC posterior of the contributing OCR lines, and `scoreIsEstimated` is always
  true, so no lane observation can establish a GREEN field;
* the box is the union of the contributing OCR line boxes, so existing spatial joins still work.

## Model bundle and INT4

Weights are **not** in this repository. The bundle folder (assets or app-private) holds:

| File | Role |
| --- | --- |
| `gliner_small_int4.onnx` | INT4 weight-only quantized export (`MatMulNBits`, block 32, symmetric) |
| `tokenizer.json` | Hugging Face fast tokenizer from the same export |
| `gliner_config.json` | `ent_token`, `sep_token`, `max_width`, `max_len` |

INT4 is weight-only quantization: activations stay float32, which ONNX Runtime 1.21 executes on
CPU. That keeps GLiNER-Small's DeBERTa-v3-small backbone at roughly a quarter of its fp32 weight
footprint, which matters next to the two PP-OCRv6 models and the producer gazetteers already
installed. The app does not trust a file name: it scans the graph for `MatMulNBits` and shows
"INT4 not confirmed" when the node is absent.

`model-manifest.json` is the single source of truth for names, roles, bytes and SHA-256 pins. Pins
are `null` in this repository on purpose: a hash that was never computed from a real artifact is
worse than no hash. The build task refuses to download an unpinned bundle.

### Path A — bake it into the APK (opt-in)

```bash
pip install "gliner[onnx]" onnx onnxruntime transformers torch
python3 tools/fetch-gliner-small-int4.py --out app/src/main/assets/gliner-lab-v1 --pin
./gradlew :app:assembleDebug -PglinerLabAssets=true
```

`tools/fetch-gliner-small-int4.py` exports with GLiNER's own `export_to_onnx()`, quantizes with
`MatMul4BitsQuantizer`, refuses to pin a graph without `MatMulNBits`, runs a smoke inference and
writes bytes/SHA-256 (plus optional URLs) into the manifest. Without `--pin` and without the Gradle
property, nothing is downloaded and the assets stay absent.

### Path B — import on the phone (no rebuild)

```bash
python3 tools/fetch-gliner-small-int4.py --device-dir ~/Downloads/gliner-bundle --pin
```

Copy the three files to the phone, open the screen and use **Import model files**. Only the three
declared names are accepted; ONNX/JSON sanity checks run before an atomic rename into
`noBackupFilesDir/gliner-lab-v1`. Pre-flight a bundle on a desktop with:

```bash
python3 tools/inspect-gliner-bundle.py ~/Downloads/gliner-bundle
```

## Dataset and scoring

The screen reads `assets/wine-app-labels-v1` — the same 124 review-numbered labels, `inputs.json`
hashes, `evaluation-set.json` inclusion list and `scoring.json` accepted values as the Lab v7
screen. Scoring uses `PhoneCorpusMath.outcome` and the same target semantics, so "fully correct"
on this screen means exactly what it means in the Lab v7 table. Reference values are applied only
after the pipeline has produced predictions.

Every photo re-runs OCR; nothing is replayed from the Lab v7 OCR cache. The model session, the
knowledge base and the producer gazetteer stay warm for the length of one run.

## Known differences from the Lab v7 screen

* No ML Kit reference pass, so any existing "two independent engines" rule sees one family per
  observation (the lane and residual variants share `paddle-gliner-v1`).
* No auto-zoom retry on tiny labels and no bottle-filter zoom pass.
* Lane text is a *group* of OCR words: `EvidenceExtractor` sees fewer, longer observations, and the
  word budget (`maxWords`, default 320) truncates the tail of very dense labels.
* Scores are OCR posteriors, not calibrated confidences.
* Timings differ: GLiNER adds one encoder pass per photo (plus spans), while OCR runs once.

## Validation status

The models cannot be fetched in the hosted sandbox (Hugging Face is unreachable and the bundle is
not in Git), so this branch ships the pipeline, the screen, the provisioning tools and structural
guards only. What was checked here:

* `python3 -m unittest tools/tests/test_gliner_lab` — 21 tests. Three of them build a synthetic
  ONNX graph with the six documented inputs, one `MatMulNBits` node and a `[1, spans, classes]`
  output, then run `tools/inspect-gliner-bundle.py` end to end: the compatible bundle is accepted,
  a graph without `MatMulNBits` is refused, and a class count that is not the three lanes is
  refused. They skip when `onnx`/`onnxruntime`/`tokenizers` are not installed.
* The twelve new Kotlin files were type-checked with `kotlinc` against stubs of the Android,
  Compose, ONNX Runtime and repository APIs. That caught three real errors before any device build:
  `List<Int>.toLongArray()` does not exist, `OnnxTensor.getFloatBuffer()` must be called explicitly
  and can return null, and `AndroidViewModel.getApplication()` is generic, so its result cannot be
  used as an `Application`.
* Full `tools/tests` discovery: 317 tests, the same 15 pre-existing failures as unmodified HEAD.

Still unverified, and therefore listed in `_refs/THINGS_TO_CHECK.md`: the real bundle's output
layout, prompt token ids from the real `tokenizer.json`, INT4 confirmation on the phone, lane
preview text, per-field scores against the Lab v7 baseline, and the CSV export.
