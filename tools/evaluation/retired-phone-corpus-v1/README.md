# Retired 168-photo corpus inputs (offline only)

The 168-photo `phone-corpus-v1` benchmark and its debug-only screen were retired on 2026-10-26;
the packaged copies of these files (and the 31 MB of photos) were removed from the APK. They are
kept here, outside the app, because the historical analyses below still need to be reproducible:

- `scoring.json` — the frozen 168-photo reference used by `tools/evaluation/wine-name-audit-v1/build.py`.
- `reference-adjudications-v1.json` — the single user-confirmed wine-name correction (review #63).
- `saved-production-v2.json` — the frozen Production v2.0 predictions of the owner's full phone run
  on build `28bce9e81cc3` (pipeline `lab-guarded-producer`), reproduced from
  `tools/evaluation/phone-full-run-28bce9e/`.

Nothing in `app/` may read this folder: the live benchmark is `app/src/main/assets/wine-app-labels-v1/`
(125 photos), and the Prod v1.0/v2.0/v3.0 comparators on that screen are captured once on the device
and frozen there. Do not re-package these files or the photos into an APK.
