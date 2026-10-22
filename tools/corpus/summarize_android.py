#!/usr/bin/env python3
"""Summarize real execution coverage, NOT accuracy against unreviewed corpus labels."""
import argparse
from collections import Counter
import csv
import json
from pathlib import Path


def summarize(root):
    root = Path(root)
    rows = []
    for path in sorted((root/'results').glob('*.csv')):
        with path.open(newline='', encoding='utf-8') as source:
            rows.extend(csv.DictReader(source))
    statuses = Counter()
    images = set()
    engines_with_text = Counter()
    for row in rows:
        images.add(row.get('image_name', ''))
        stage = row.get('stage', '')
        if stage in {'summary', 'candidate_summary', 'image_summary', 'form_summary', 'evidence_summary'}:
            statuses[' / '.join(row.get(k, '') for k in ('candidate', 'stage', 'status'))] += 1
        if stage == 'recognition' and row.get('raw_text', '').strip():
            engines_with_text[row.get('candidate', '')] += 1
    images.discard('')
    production = [json.loads(p.read_text()) for p in sorted((root/'results').glob('*-production.json'))]
    selected = root/'selected-images.txt'
    expected = len(selected.read_text().splitlines()) if selected.exists() else None
    return dict(expected_distinct_images=expected, lab_images_with_rows=len(images),
        production_reports=len(production), production_deadlines=sum(bool(r['deadlineCancelled']) for r in production),
        production_bitmap_violations=sum(r['peakWorkingBitmaps'] > 2 or r['workingBitmapsAfter'] != 0 for r in production),
        lab_summary_statuses=dict(sorted(statuses.items())), lab_recognized_lines=dict(sorted(engines_with_text.items())),
        accuracy=None, warning='Execution coverage only. Unreviewed labels are not ground truth; emulator timing is not phone performance.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    args = parser.parse_args()
    result = json.dumps(summarize(args.results), indent=2) + '\n'
    args.results.mkdir(parents=True, exist_ok=True)
    (args.results/'corpus-summary.json').write_text(result)
    print(result, end='')
