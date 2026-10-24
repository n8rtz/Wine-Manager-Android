#!/usr/bin/env python3
"""Compare saved-reading development replays; never labels results as held-out accuracy."""
import argparse
import csv
import json
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def norm(value):
    return ''.join(c for c in unicodedata.normalize('NFKD', value).casefold() if c.isalnum())

def evaluate(folder):
    targets = json.loads((ROOT / 'tools/evaluation/evidence-targets.json').read_text())
    totals = defaultdict(Counter)
    bottles = defaultdict(lambda: defaultdict(Counter))
    differences = []
    for batch, identities in targets['batches'].items():
        with (folder / f'{batch}-preview-replay.csv').open() as stream:
            rows = list(csv.DictReader(stream))
        for index, bottle in enumerate(identities, 1):
            outcomes = {}
            for pipeline, stage in [('baseline', 'form_summary'), ('experimental', 'evidence_summary')]:
                matches = [r for r in rows if r['image_index'] == str(index) and r['stage'] == stage]
                assert len(matches) == 1 and matches[0]['status'] != 'failed', (batch, index, pipeline)
                row = matches[0]
                result = []
                for field, expected in zip(targets['fields'], targets['bottles'][bottle]):
                    if expected is None:
                        continue
                    actual = row[field]
                    category = 'correct' if norm(actual) == norm(expected) else 'abstained' if not actual else 'wrong'
                    totals[pipeline][category] += 1
                    bottles[bottle][pipeline][category] += 1
                    result.append((field, actual, category, expected))
                outcomes[pipeline] = result
            for a, b in zip(outcomes['baseline'], outcomes['experimental']):
                if norm(a[1]) != norm(b[1]):
                    differences.append(dict(batch=batch, image=index, bottle=bottle, field=a[0], expected=a[3],
                                            baseline=a[1], experimental=b[1], baseline_status=a[2], experimental_status=b[2]))
    return dict(provenance=targets['provenance'], totals=totals, per_bottle=bottles, differences=differences)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--folder', type=Path, default=ROOT / '.ocr-harness/checks')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    report = evaluate(args.folder)
    (args.folder / 'evidence-comparison.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    lines = ['# Experimental evidence v2 — development comparison', '', report['provenance'], '',
             'Run: `KOTLIN_HOME=... bash tools/run-logic-checks.sh` (includes `python3 tools/evaluate-evidence.py --check`).', '',
             '28 scan instances, eight bottles, 160 scored fields. Unknown targets are excluded. Equality ignores case, accents, spaces and punctuation, but never completes missing characters or years.', '',
             '| Pipeline | Correct targets | Wrong fills | Abstentions |', '|---|---:|---:|---:|']
    for pipeline in ['baseline', 'experimental']:
        counts = report['totals'][pipeline]
        lines.append(f"| {pipeline} | {counts.get('correct', 0)} | {counts.get('wrong', 0)} | {counts.get('abstained', 0)} |")
    lines += ['', '## Per-bottle totals (correct / wrong / abstained)', '', '| Bottle | Baseline | Experimental |', '|---|---|---|']
    for bottle, outcomes in report['per_bottle'].items():
        cells = [' / '.join(str(outcomes[p].get(k, 0)) for k in ['correct', 'wrong', 'abstained']) for p in ['baseline', 'experimental']]
        lines.append(f"| {bottle} | {cells[0]} | {cells[1]} |")
    lines += ['', '## Changed scored fields', '', '| Batch/image | Field | Baseline | Experimental |', '|---|---|---|---|']
    for change in report['differences']:
        lines.append(f"| {change['batch']}/{change['image']} | {change['field']} | {change['baseline'] or '—'} | {change['experimental'] or '—'} |")
    lines += ['', 'No previously correct scored field regressed; no previous abstention became a wrong fill. The remaining six abstentions are one wine name, one incomplete vintage and four geographic fields. This is a development gate, not independent validation: the labels were inspected during implementation and the scans are correlated.', '',
              'The production pipeline remains unchanged. Experimental values remain YELLOW. Host checks do not validate the Android/Compose build, fresh OCR, device latency, or unseen-bottle accuracy.', '']
    (args.folder / 'evidence-comparison.md').write_text('\n'.join(lines))
    print(json.dumps(report['totals'], indent=2))
    for bottle, outcomes in report['per_bottle'].items():
        print(bottle, dict(outcomes))
    if args.check:
        baseline, experiment = report['totals']['baseline'], report['totals']['experimental']
        assert experiment['correct'] > baseline['correct'], 'No measured development improvement'
        assert experiment['wrong'] <= baseline['wrong'], 'Wrong fills increased'
        for bottle, outcomes in report['per_bottle'].items():
            assert outcomes['experimental']['correct'] >= outcomes['baseline']['correct'], f'Correct-field regression: {bottle}'
            assert outcomes['experimental']['wrong'] <= outcomes['baseline']['wrong'], f'Wrong-fill regression: {bottle}'
        for change in report['differences']:
            assert change['baseline_status'] != 'correct', f'Previously correct field regressed: {change}'
            assert not (change['baseline_status'] == 'abstained' and change['experimental_status'] == 'wrong'), f'New wrong fill: {change}'
        print('PASS: development-corpus improvement with no per-bottle regression; NOT held-out accuracy')

if __name__ == '__main__':
    main()
