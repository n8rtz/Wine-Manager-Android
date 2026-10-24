# Experimental evidence v2 — development comparison

Development targets from user-confirmed intake conventions and supplied photos/conversation; not independently adjudicated or held-out. Null fields are unknown/unscored. Repeat scans count separately; per-bottle summaries must accompany totals.

Run: `KOTLIN_HOME=... bash tools/run-logic-checks.sh` (includes `python3 tools/evaluate-evidence.py --check`).

28 scan instances, eight bottles, 160 scored fields. Unknown targets are excluded. Equality ignores case, accents, spaces and punctuation, but never completes missing characters or years.

| Pipeline | Correct targets | Wrong fills | Abstentions |
|---|---:|---:|---:|
| baseline | 147 | 4 | 9 |
| experimental | 154 | 0 | 6 |

## Per-bottle totals (correct / wrong / abstained)

| Bottle | Baseline | Experimental |
|---|---|---|
| napa | 19 / 0 / 1 | 19 / 0 / 1 |
| brunello | 30 / 0 / 0 | 30 / 0 / 0 |
| riscal | 24 / 0 / 0 | 24 / 0 / 0 |
| alexander | 18 / 0 / 0 | 18 / 0 / 0 |
| blackstone | 20 / 0 / 0 | 20 / 0 / 0 |
| enclave | 21 / 2 / 7 | 26 / 0 / 4 |
| altos | 9 / 2 / 1 | 11 / 0 / 1 |
| olarra | 6 / 0 / 0 | 6 / 0 / 0 |

## Changed scored fields

| Batch/image | Field | Baseline | Experimental |
|---|---|---|---|
| form/6 | country | — | France |
| form/6 | region | — | Rhône |
| architecture/4 | producer | ALTOSde | ALTOS de VALDOSO |
| architecture/4 | wine_name | VALDOSO Crianza | Crianza |
| architecture/5 | producer | — | Vignerons de L'ÊNCLavE |
| architecture/5 | wine_name | LA Reserve LA de L'ENCLAUE | LA Reserve DE L'ENCLAvé |
| architecture/5 | subregion | Côtes du Rhône | Côtes du Rhône Villages |

No previously correct scored field regressed; no previous abstention became a wrong fill. The remaining six abstentions are one wine name, one incomplete vintage and four geographic fields. This is a development gate, not independent validation: the labels were inspected during implementation and the scans are correlated.

The production pipeline remains unchanged. Experimental values remain YELLOW. Host checks do not validate the Android/Compose build, fresh OCR, device latency, or unseen-bottle accuracy.
