# Repacked second package — offline review, not app data

See `docs/producer-gazetteer-rezip-review.md`. `audit.json` contains measured build results, per-file hashes, the source URL and pinned Git blob. `audit.py --archive <zip>` reproduces the offline audit from that exact input. The original ZIP and bulk permit data stay external; no data asset is enabled or installed in Android.

The full rebuild was executed during review. Lightweight host tests validate the saved report's accounting/provenance and demonstrate unsafe exact-alias matches. The former 168-image reference is retired and is not used for current source evaluation; no lexical coverage score is reported. Tests do not rerun the ~1-minute external-data build or claim source authenticity, Android compatibility or OCR accuracy.
