# Production-engine Text Lab

Only bundled ML Kit Latin OCR and PP-OCRv6 Medium detector + Medium recognizer remain.
`candidate_3` is retained as the stable Medium CSV identifier so historical exports
remain understandable. There are no other selectable model profiles, model downloads,
or alternate preprocessing recipes. The separate OCR benchmark now runs baseline only.

Select images, review each independent crop, enable Medium, and run the batch. ML Kit
is recorded separately as the reference. Production model pins and dictionary metadata
live in `app/src/main/assets/text-lab-paddle-v1/production-models.json`.
The same two ONNX models are packaged in both debug and release builds. Their Apache
license and pinned dictionary remain included. The prebuilt minimal OpenCV phone build
path is unchanged. Old model names survive only in the exact-file upgrade cleanup.
Private model-cache cleanup runs on scanner service initialization; user CSVs, photos
and cellar data are not deleted. Old generated build directories are no longer wired
into packaging; a Gradle clean can reclaim their build-machine disk space.

## Diagnostics and field table

Every image keeps its own metadata, crop coordinates, original/base inverse mappings,
raw readings, model hashes, timing, failures and partial/unrun summaries. Native engines
run sequentially. Text Lab retains its 60-second candidate budget; production remains
one shared 15-second budget. These timings are not end-to-end shutter latency.

The six-field table shows producer, wine name, country, region, subregion and vintage.
Copy OCR result includes the table; `field_summary` CSV rows include each value, reason,
state, input-line count and number of suggestions. Unknown values are blank in CSV and
an em dash on screen. OCR completion and field extraction outcome are separate statuses.

The table is a **per-engine diagnostic**, not combined production autofill or ground
truth. It retains accepted OCR lines without the production singleton confidence gate;
it does not raise their scores, fuse their votes or borrow another engine's values.
Shared local field extraction/validation rules run off the UI thread, then all nonblank
values are capped to YELLOW. U.S. state-as-Country display conventions remain in effect.
Production confidence gates and validation are unchanged. Extraction time is outside
OCR inference timings. Existing CSVs are retained, not silently rewritten.

## What was removed

Indra raw/enhanced/12-pass engines, orientation/LM assets and decoder code; v5 Mobile
and v6 Small profiles/downloads/dictionary; unused Paddle Lite and NER integration hooks;
29 alternative image-treatment recipes and their shear/thinning/contrast implementation.
History remains available in Git, not packaged in new APKs.
