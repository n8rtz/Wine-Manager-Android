# Local scanner data and optional models

## Small fixture, not a world database

`FixtureKnowledgeBase.snapshot` contains **27 places, 10 producers, 5 cuvées**, including France/Bordeaux/Pauillac/Margaux, Piedmont/Barolo, California/Napa, and real example wineries. It is intentionally small source-authored reference data, not a generated binary SQLite or a claim of global coverage. `SqliteWineKnowledgeBase.open()` creates/seeds a separate `wine-label-kb.sqlite` using the genuine AndroidX bundled SQLite driver/FTS5. Existing inventory Room schema/version is untouched.

The separate DB has:

```
place(id, name, name_norm, type, parent_id, country_code)
producer(id, name, name_norm, country_code, region_id nullable)
producer_alias(producer_id, alias_norm)
cuvee(producer_id, name, name_norm)
producer_fts(name_norm, aliases)  -- FTS5
```

Normalization is NFKD, stripping combining marks, Locale.ROOT lowercase, collapsed whitespace. Names retain their original display spelling. Producer/cuvée aliases are local facts, not extra OCR-engine votes. Place matching uses a cached boundary-aware trie; producer matching uses real FTS5 top-25 prefix shortlists then bounded edit distance.

## `importOpenWines(csvOrJson: InputStream)`

The name is an **adapter contract**, not a claim that an unspecified OpenWines dump matches these columns. No dump is required to compile. Convert a different upstream schema to the explicit `wine-kb/v1` interchange below before calling it. No network lookup or automatic dump download is performed.

Call `SqliteWineKnowledgeBase.open(context).use { it.importOpenWines(input) }` on `Dispatchers.Default`/a worker, before starting scanner services. Main-thread open/import is rejected. Do not import concurrently with recognition. The same KB handle refreshes its place cache on commit; importing through a separate handle requires reinitializing scanner services/restarting the app before relying on its cached gazetteer.

The production importer **streams UTF-8 rows** through strict CSV / Android `JsonReader` adapters into a disposable private cache SQLite file. It never calls `readText()`/`readBytes()` on the dump or builds a dump-sized object/JSON tree. The source stream remains caller-owned, even on failure. Arbitrary JSON section order and CSV row order are supported; duplicate place/producer IDs and alias references to producers absent from the **same batch** are rejected. Cuvées may reference an existing/imported producer. `OpenWinesAdapter.read()` remains a small test/tool convenience with a hard 10,000-record snapshot cap; the live DB importer uses `stream()` instead.

Only after complete parsing does a single live-DB transaction attach/copy staged rows, upsert stable IDs, normalize names/aliases, rebuild FTS and validate foreign keys, country-code shape, parent type/country consistency and cycles. A late schema/parse error never touches live data. A failed hierarchy/FK/storage-budget check rolls back data **and** FTS; the place-cache snapshot is published only after commit. Temporary files are deleted on normal success/failure (as with any cache file, an OS/process kill can leave a stale file for cache eviction). A recognition lookup waits at most 20ms for this handle's import lock before degrading to editable YELLOW evidence; this is protection, not permission to import during a scan.

Bounds are enforced before dump-sized allocations: input ≤80MiB; CSV row / encoded JSON string ≤8192 characters; flat JSON depth ≤4, at most 12 scalar fields per row; display names/aliases ≤512 non-control characters; IDs are positive signed 64-bit decimal integers (JSON integer numbers or digit strings), never floats/exponents. Missing or JSON-null optional IDs are allowed; malformed **nonblank** numeric cells/values are rejected rather than silently treated as null. UTF-8 BOM and CRLF CSV are supported; malformed UTF-8 is rejected.

The disposable staging DB must be <80MiB, and resulting live SQLite logical pages + installed OCR/NER asset bytes must be <80MiB. SQLite page caches are bounded to 2MiB per connection. Import requires **additional transient private disk space** for staging and the live DB's rollback journal; this is not a claim of an 80MiB peak import-storage limit. The bundled corpus remains small; throughput/heap/lookup latency for a world-scale user-supplied corpus has not been measured on Android.

### JSON

```json
{
  "schema": "wine-kb/v1",
  "places": [
    {"id": 1, "name": "France", "type": "country", "parent_id": null, "country_code": "FR"},
    {"id": 2, "name": "Bordeaux", "type": "region", "parent_id": 1, "country_code": "FR"},
    {"id": 4, "name": "Margaux", "type": "appellation", "parent_id": 2, "country_code": "FR"}
  ],
  "producers": [
    {"id": 1, "name": "Château Margaux", "country_code": "FR", "region_id": 2}
  ],
  "producer_aliases": [
    {"producer_id": 1, "alias_norm": "chateau margaux"}
  ],
  "cuvees": [
    {"producer_id": 1, "name": "Pavillon Rouge"}
  ]
}
```

These examples intentionally upsert the existing fixture IDs. Reuse IDs for the same natural place/producer; map an upstream ID scheme before import instead of creating parallel duplicate France/Bordeaux hierarchies. Give genuinely new records their own managed ID namespace and do not accidentally replace unrelated fixture/custom IDs. `type` is `country`, `region`, `subregion` or `appellation`. A country has no parent; a region has a country parent; subregion/appellation descendants have a region/subregion/appellation parent in the same country. `producer.region_id` refers to a **region**, not an arbitrary appellation. ISO codes are uppercase two-letter codes. Only provide producer origin metadata that is actually valid for your data; a headquarters country is not automatically every wine's origin.

### CSV

UTF-8, header names exactly as below; irrelevant cells blank. Single-line fields support quoted commas and doubled quotes. Multiline CSV fields must be flattened by the upstream adapter.

```csv
record_type,id,name,type,parent_id,country_code,region_id,producer_id,alias_norm
place,1,France,country,,FR,,,
place,2,Bordeaux,region,1,FR,,,
place,4,Margaux,appellation,2,FR,,,
producer,1,Château Margaux,,,FR,2,,
alias,,,,,,,1,chateau margaux
cuvee,,Pavillon Rouge,,,,,1,
```

`record_type` is `place`, `producer`, `alias` or `cuvee`. `name_norm` is computed, not trusted from external data.

## Optional real Paddle Lite

Default: **absent and NotAvailable**, so phases 1–3 run ML Kit only. No `.nb`, dictionary, custom JNI or model weights are invented. Supplying named files alone does not create a usable OCR engine: register a genuine `RealPaddleBridgeFactory` in the app-scoped service construction after integrating a real licensed Paddle Lite runtime and compatible real models.

Optional manifest (`assets/ocr/manifest.json`, bounded 16KiB):

```json
{
  "schema": "paddle-lite/v1",
  "family": "PP-OCRv4-mobile",
  "det": "det.nb",
  "cls": "cls.nb",
  "rec": "rec.nb",
  "dictionary": "dictionary.txt"
}
```

`PP-OCRv5-mobile` is also accepted. Names are explicit real filenames, not downloadable placeholders. Presence checks require actual nonempty files. A real `assets/ocr/rec_custom.<tag>.nb`, if supplied, replaces **recognition only**; detector remains the mobile detector. Multiple custom files choose the lexically first one; ship only the intended model. All supplied OCR/NER files count against the combined asset budget, including invalid/unused files. No first-run optional downloader is implemented; adding one also needs a genuine cache-file locator instead of pretending APK assets are writable.

Bridge requirements:

1. Input is the rectified-label bitmap, never the full camera scene.
2. Real detection → invertible line crop → orientation/classification → real recognition with the correct dictionary.
3. Return one `RawToken` per line with **rectified-label pixel** corners and actual recognition score; invert every intermediate crop/rotation before returning.
4. Honor the passed absolute deadline and shared `NativeEngineGate`. Sequential native engines; do not overlap after coroutine timeout while native code still owns inputs.
5. Load models once app-scoped and own buffers across native async calls. No fake `.nb` decoder or synthetic predictions.

`PaddleEngineFactory` logs why a bridge/model is unavailable; no error prevents the default ML Kit path unless installed assets themselves violate the explicit storage budget.

## Optional NER

`NerService` and `NoNerService` are interface-only/disabled by default. There is no DistilBERT, TFLite dependency, tokenizer emulation, hidden download or required model file. A future real implementation requires actual `assets/ner/model.tflite` and a compatible, specified `tokenizer.json` declaring tokenizer type, vocabulary, normalization, special tokens and sequence length. Its outputs are only extra candidates to pass through the same validator, never authoritative field values. Merely placing a binary in assets does not enable a fake service.

## Label test data

`testdata/labels/{margaux,barolo,napa}.png` are three reproducibly generated printed label bitmaps. Each accompanying JSON has expected producer/vintage/region and all six field states, plus **declared synthetic tokens/confidences and both coordinate-space boxes**. These are not measured OCR results, photos from a device, training samples or fine-tuning data.

`tools/generate-label-fixtures.py` regenerates them with Pillow and DejaVu fonts. Tests can use expected tokens for deterministic logic; real ML Kit/device tests must separately read the PNGs. No fake models, binary knowledge dump or required optional assets are checked into Git.
