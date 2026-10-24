# Residual identity-candidate discovery (experimental)

This implements the **tag-and-subtract stage**, not a new identity predictor.
It does not claim that remaining text is a producer or wine name. Marketing copy,
personal names, missed administrative text and OCR debris can remain.

## Use and isolation

The OCR Text Lab has a separate **Find residual producer/cuvée candidates** switch,
default off. It can run without the six-field gazetteer comparison. Both options
share one bundled-SQLite handle when enabled. No new engine, OCR pass, catalogue or
runtime dependency is added. Original form summaries, raw readings, scores, boxes
and manual intake values remain unchanged. Candidate text appears in the copied
result summary and CSV. Candidates are explicitly **unassigned**, not GREEN/YELLOW
field predictions. This is step one toward a later producer-vs-cuvée selector.

## What is tagged

- **Geography:** FTS-derived exact, whole-fragment aliases can be subtracted.
  Fuzzy matches need explicit appellation/classification wording; otherwise they
  remain advisory `POSSIBLE_GEOGRAPHY`. Ambiguous matches are not deleted.
  No arbitrary substring search strips a place out of an estate/cuvée name.
- **Grapes:** exact whole-word matches against the app's bundled names and aliases.
  Multiword varieties and isolated grapes/blends can be subtracted. An isolated
  surname-like grape inside a longer name is retained with a collision tag.
- **Years:** four digits in 1900..current year are only `VINTAGE_CANDIDATE`.
  Founding/establishment dates get `HISTORICAL_YEAR`; other four-digit numbers are
  not vintages. `19 Crimes` remains intact. No year is assigned to a form here.
- **Packaging:** volume and alcohol expressions are removed as identity candidates.
- **Legal text:** importer/bottler/warning clauses own their following text, including
  comma-separated addresses. Their company names must not become residual producers.
  Explicit maker wording is retained as provenance and separated from the name.
- **Designations:** Grand Cru, Reserva, Riserva and other existing qualifiers are
  separate from packaging/legal notices. Their raw spans remain available alongside
  candidates for later name reconstruction. A same-segment qualifier is **not an
  established attachment**. Proper-name extents such as `Domaine de la Réserve` and
  article titles such as `La Réserve` are protected.

Producer/cuvée cues and known producer names protect against destructive grape or
geography subtraction. Thus `Château Margaux`, `Domaine du Riesling`, and `Cuvée
Cabernet Sauvignon` are not stripped into incomplete names. This is deliberately
conservative: a producer followed by a place with no clear boundary may retain the
place in its residual text. Resolving that requires ownership evidence, not more
aggressive deletion. Grape/style tags also remain available because grapes can
legitimately serve as the wine-name field even though they are not distinctive
producer/cuvée identity candidates.

## Evidence contract

`ResidualTagger` is pure Kotlin. Every tag has a kind, subtraction flag, reason,
matched vocabulary/entity ID and original **UTF-16 start/end-exclusive offsets**.
Every candidate is an unchanged contiguous slice of the original OCR string.
Never concatenate fragments across a removed span into a new fabricated name.

The journal adapter exports observation index, engine, preprocessing pass, original
and rectified source boxes. A fragment's exported box is explicitly the **whole
source observation**, not an invented character-level box. It never promotes a
recognizer score or treats repeated engine passes as independent agreement.

Limits: 512 characters per line, 128 observations, 16 lookup attempts per line,
256 unique cached FTS queries per stage, 60-second diagnostic budget. Missing DB,
cap excess or deadline failure exports an explicit failed summary and no candidate
set. Cancellation propagates. No partial successful subtraction is published.

## Executed tests (no Actions or Android run)

`tools/run-residual-checks.sh` compiles and executes the actual tagger using genuine
**Kotlin 2.2.10 / JDK 21**. It passed **46 contrastive cases**, query/character/deadline
guards, and six additional checks using bundled grape/qualifier/producer vocabulary.
The geography lookup in these core tests is an explicit fixture, not proof of
Android SQLite integration. Unlike a Python reimplementation, these tests execute
the same Kotlin core the lab adapter calls.

To repeat where those toolchains are installed:

```
JAVA_HOME=/path/to/jdk KOTLIN_HOME=/path/to/kotlin bash tools/run-residual-checks.sh
bash tools/run-host-checks.sh
```

The script does not download tools, invoke Actions, run OCR or require an Android
SDK. During development the genuine compiler came from npm `kotlin-compiler@2.2.10`
and JDK from PyPI `jdk4py==21.0.8.2`, stored only in the workspace's disposable cache.
They are not app dependencies or repository assets.

`ResidualTaggingDeviceTest` is authored but **not executed**, honoring the request
not to run Android yet. It checks real FTS/journal wiring, baseline preservation,
source offsets, idempotence and fail-closed behavior. Actual Android compilation,
CSV/UI behavior and residual recall on the photo corpus remain unverified.

## Next measurement before identity assignment

Compare residual candidate availability against the saved producer/wine failures:
correct name preserved, correct name recovered, correct name partially erased,
new noise retained, and ambiguous role. Evaluate candidate precision as well as
coverage. Then add a separate selector for producer versus cuvée using explicit
role wording, spatial relationships and independent-engine evidence. No new
accuracy or complete-form improvement is claimed from these synthetic tests.
