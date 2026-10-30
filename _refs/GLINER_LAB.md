# GLiNER lane lab (new structure, built on Lab v7)

Settings → Scanner testing → **GLiNER lane lab — ML Kit + PP-OCRv6 ➔ GLiNER-Small INT4**.

This screen starts a structurally different reader next to Lab v7, not a new set of modules inside it:

```
[ML Kit Latin + PP-OCRv6 Medium det+rec] ➔ [fuse] ➔ [GLiNER-Small (ONNX, INT4)] ➔ [existing Lab v7 post-processing]
```

Lab v7 keeps one long rule stack deciding what a line *means* while it also decides what the line
*is*. The new screen splits those jobs. OCR only reads text, with both engines the scanner already
ships (ML Kit Latin and PP-OCRv6) reading the same pixels. GLiNER-Small is the contextual brain:
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

## The device screen

Settings > Scanner testing > GLiNER lane lab is a results page: a model card, a run card and one
card per label. Every string sits in a `SelectionContainer`, and **Copy all results** puts the whole
run (statuses, probes, lanes, fields, OCR, notes) on the clipboard so a problem can be pasted into a
chat instead of screenshotted. **Save JSON** and **Save CSV** export the same data through the
system document picker, like the Lab v7 device-photo page.

A run continues when the screen is left, another app is opened or the screen is switched off: the
work lives in a process-scoped `GlinerLabSession` and `GlinerLabRunService` keeps the process alive
with a foreground notification and a partial wake lock, mirroring the Lab v7 dataset run.

The per-label detail shows **probes**: the best span per lane *before* the threshold, in the form
`producer 0.684 chateau margaux`. If a run leaves every field blank, the probes say whether the graph
scored the right words below the threshold (lower the threshold) or scored nothing at all (the feed
or the bundle is wrong).

## What the real graph actually expects

Guessing the input convention cost most of the integration time, so it is recorded here. Captured
from the reference implementation running on our own export:

| Input | Convention |
| --- | --- |
| prompt | a list of **words**: `<<ENT>> label words <<ENT>> ... <<SEP>>`, then the label words |
| `<<ENT>>` / `<<SEP>>` | tokenized as **text** (5 SentencePiece pieces), *not* the added-token ids 128002/128003 that appear in `tokenizer.json`; feeding the added ids gives random embeddings and every lane collapses to ~0.04 |
| `input_ids` | the whole word sequence wrapped once in `[CLS]` / `[SEP]`; never per label |
| `words_mask` | 1-based over the text words only, set on the first subtoken of each word, 0 on prompt, specials and continuation subtokens |
| `text_lengths` | the number of text words |
| `span_idx` | `(words * max_width, 2)`, every start with offsets `0..max_width-1`, `end = start + offset`, unclipped; invalid spans are masked |
| `span_mask` | `end < words` |
| `logits` | `(1, words, max_width, lanes)` |

With that feed the INT4 graph returns `Chateau Margaux` for PRODUCER at 0.684 on the smoke label,
against 0.615 from the reference PyTorch model, so the quantization is sound. `tools/gliner_feeds.py`
is the shared implementation and `tools/compare-gliner-onnx.py` re-derives the truth from PyTorch if
a future export changes it.

## Which GLiNER checkpoint fits this pipeline

Stage 2 is a span/uni-encoder contract: six tensors in (`input_ids`, `attention_mask`,
`words_mask`, `text_lengths`, `span_idx`, `span_mask`), one float span-logit tensor out, words
masked 1-based, spans enumerated as `(start, end)` with `width <= max_width`.

Default is `urchade/gliner_small-v2.1`: it is the smallest checkpoint that speaks the contract
above, and the host run showed the model is not the current bottleneck. Anything bigger has to earn
its bytes with a measured label gain, not with a newer version number.

| Candidate | Fits this stage 2? | Notes |
| --- | --- | --- |
| `urchade/gliner_small-v2.1` | **yes**, shipped default | uni-encoder span model, Apache-2.0, the contract above, smallest INT4 bundle |
| `gliner-community/gliner_small-v2.5` | **yes**, A/B only | same span family (markerV0, `max_width` 12), newer and multilingual, so its embedding table and tokenizer are larger; run `tools/inspect-gliner-bundle.py` on the export and score it against v2.1 before switching |
| `fastino/gliner2.5-small-v1` | **no** | GLiNER2.5 is a *boundary* architecture: start/end + inside scores with sparse start/end pairing and a reranker, 4096-word context, exported as several graphs (encoder, classifier, boundary heads, scorers, records, relations). It needs a different decoder, a multi-graph bundle and a newer ONNX Runtime than the pinned 1.21.1 |

GLiNER2.5 is the better model in the abstract (74M, DeBERTa-v3-xsmall, no span-width limit), but on
a wine label the text is 20–40 words, so the boundary architecture buys nothing here while costing a
stage-2 rewrite. Revisit it only if the lab ever needs long-context or multi-task extraction.

Producing and hosting the bundle: see [GLINER_MODEL_UPLOAD.md](GLINER_MODEL_UPLOAD.md).

To try the v2.5 span model instead of v2.1:

```bash
python3 tools/fetch-gliner-small-int4.py --model gliner-community/gliner_small-v2.5 --out bundle --pin
python3 tools/inspect-gliner-bundle.py bundle      # must print the six inputs, INT4 and a span layout
```

## Why three lanes and not six

GLiNER is a zero-shot span labeller. Its useful contribution on a wine label is deciding which
fragments belong together and which of three coarse roles they play. Asking it for six narrow
fields (country vs region vs subregion) invites near-duplicate prompts, cross-lane leakage and a
second, weaker geography resolver competing with the one the app already ships. One unambiguous
prompt per lane also makes the flat-NER decoding rule meaningful: no word may be accepted for two
lanes, so a producer name cannot also become the wine name.

## Stage contracts

### Stage 1 — ML Kit Latin + PP-OCRv6 Medium (`GlinerOcr.kt`, `GlinerFusion.kt`)

Both engines the production scanner already runs, over the same decoded label:

* **ML Kit Latin** — the same bundled recognizer the scanner holds (`MlKitOcrEngine`, one
  app-scoped client), line granularity, so a whole-line ML Kit box never swallows a Paddle word.
* **PP-OCRv6 Medium** — the pinned `v6-medium-det.onnx` + `v6-medium-rec.onnx` +
  `ppocrv6_dict.utf8`, same DB thresholds and preprocessing, CTC posterior per line.

The two reads are launched together (`coroutineScope { async(ML Kit) … async(PP-OCRv6) }`) on one
bitmap and awaited as a pair. The app-wide `NativeEngineGate` (a one-lease semaphore, exactly what
the production ensemble takes) still serialises the native calls, so the phone never holds two OCR
sessions at once — the same behaviour as the existing scanner, without serialising the *staging*:
ML Kit starts while the Paddle detector initialises, and a slow engine no longer delays the other's
setup.

`GlinerFusion` then turns the two reading lists into one word sequence for GLiNER:

* two readings become one line when the normalized texts match at (almost) the same place, or when
  the boxes overlap strongly and the spellings are close;
* everything else is kept, including duplicated or garbled fragments — that is the evidence the
  contextual stage exists to resolve, so no score floor is applied here;
* each fused line keeps **every** engine that read it with that engine's own measured score, and its
  display posterior is `1 - Π(1 - score)`.

No auto-zoom and no spatial phrases run in this stage; stage 3 owns all interpretation.

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

* one observation becomes **one record per engine** that read its lines (`mlkit`,
  `paddle-v6-medium`), so a fragment both engines saw is genuine two-engine support;
* `variantId` is `lane:producer`, `lane:wine_name`, `lane:appellation` or `residual`;
* the score is that engine's **weakest** contributing line posterior, and `scoreIsEstimated` is
  false because it is a measurement, not an invented value;
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

* Stage 1 keeps both engines, so the existing two-engine rules see the same ensemble as
  production. Lane and residual observations still name the engine that read the underlying lines
  (`mlkit`, `paddle-v6-medium`), and each observation's score is the weakest contributing line for
  that engine — a lane is never more certain than its worst line. The lane variant
  (`lane:producer`, `lane:wine_name`, `lane:appellation`, `residual`) stays on the record for audit.
* No auto-zoom retry on tiny labels and no bottle-filter zoom pass.
* Lane text is a *group* of OCR words: `EvidenceExtractor` sees fewer, longer observations, and the
  word budget (`maxWords`, default 320) truncates the tail of very dense labels.
* Scores are the engines' own posteriors (measured), not calibrated confidences; a two-engine fused
  line reports its combined posterior for display and the per-engine values for evidence.
* Timings differ: OCR is now two engines per photo (the ensemble the scanner already pays for) plus
  one GLiNER encoder pass, so a label costs more than a single Paddle pass.

## Host measurement (2026-10-30)

`bash tools/run-host-gliner-lane.sh` replays the real stage 1b and stage 3 over the packaged cached
OCR and compares against the Lab v7 host baseline on the same 121 eligible labels. First results,
with stage 2 standing in for the unavailable weights:

| Run | Complete |
| --- | ---: |
| Lab v7 baseline | 81/121 |
| fusion, no lanes | 79/121 |
| perfect lanes (replace residual) | 73/121 |
| perfect lanes (additive) | 76/121 |

Read `_refs/GLINER_LAB_HOST_RUN.md` before drawing conclusions: a perfect labeller currently does
not beat Lab v7 through the existing post-processing, because grouped wine-name records decide
differently from the per-line readings those rules were built on.

## Validation status

The models cannot be fetched in the hosted sandbox (Hugging Face is unreachable and the bundle is
not in Git), so this branch ships the pipeline, the screen, the provisioning tools and structural
guards only. What was checked here:

* `python3 -m unittest tools/tests/test_gliner_lab` — 25 tests. Three of them build a synthetic
  ONNX graph with the six documented inputs, one `MatMulNBits` node and a `[1, spans, classes]`
  output, then run `tools/inspect-gliner-bundle.py` end to end: the compatible bundle is accepted,
  a graph without `MatMulNBits` is refused, and a class count that is not the three lanes is
  refused. They skip when `onnx`/`onnxruntime`/`tokenizers` are not installed.
* The twelve new Kotlin files were type-checked with `kotlinc` against stubs of the Android,
  Compose, ONNX Runtime and repository APIs. That caught three real errors before any device build:
  `List<Int>.toLongArray()` does not exist, `OnnxTensor.getFloatBuffer()` must be called explicitly
  and can return null, and `AndroidViewModel.getApplication()` is generic, so its result cannot be
  used as an `Application`.
* Full `tools/tests` discovery: 320 tests, the same 15 pre-existing failures as unmodified HEAD.

Still unverified, and therefore listed in `_refs/THINGS_TO_CHECK.md`: the real bundle's output
layout, prompt token ids from the real `tokenizer.json`, INT4 confirmation on the phone, lane
preview text, per-field scores against the Lab v7 baseline, and the CSV export.
