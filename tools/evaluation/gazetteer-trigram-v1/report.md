# Trigram resolver lexical benchmark

- Positive top-1: 98.1% (429 cases).
- Standard positives: 100.0%.
- Synthetic dropped/swapped-character cases: 420, recall 98.1%.
- Negative false matches: 0/14.
- Acceptance: True.
- Similarity distribution: {'min': 0.8333333333333334, 'median': 0.9166666666666666, 'max': 1.0}.
- BM25 distribution: {'min': -215.95913207577672, 'median': -55.798670438170916, 'max': -5.486623979284784}.

Synthetic lexical development benchmark, not real OCR accuracy or calibrated confidence. Château Margaux is not an appellation and is rejected. Ambiguous multi-region identities abstain.

Thresholds: at most 0/1/2 edits for names of length <5/<9/otherwise, similarity ≥0.82, runner-up margin ≥0.06. BM25 shortlists 64 records, followed by bounded adjacent-transposition/edit reranking. No universal BM25 confidence cutoff.

Android execution and actual saved-OCR/full-form evaluation are separate, not established by this host benchmark.
