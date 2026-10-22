# Saved-OCR identity-role iteration

Real Android/Kotlin replay of all 168 frozen lab OCR inputs. Production untouched. Only the revised experimental extractor executes. Baseline and v2 outputs are reused from the frozen run, not re-executed. The revised extractor has a 60-second maximum per image (historical v2 used two seconds), so budget changes are disclosed alongside assignment changes. This is development-set feedback, not held-out accuracy.

| Extractor | Correct | Wrong | Abstained | Six fields correct / eligible |
|---|---:|---:|---:|---:|
| Lab baseline | 597 | 100 | 197 | 23 / 103 |
| Experimental v2 | 566 | 121 | 207 | 8 / 103 |
| Experimental v3 | 599 | 84 | 211 | 19 / 103 |

894 known targets; 114 unknown targets excluded. Single-agent visual reference and aliases remain unchanged. See scores.json for per-field and duplicate-sensitivity results. Traces distinguish filtering, ownership conflicts, bounded search and competing finalists.
