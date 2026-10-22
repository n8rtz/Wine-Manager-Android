#!/usr/bin/env python3
"""Validate and export one completed Android corpus artifact, never input photos or answers."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

STAGES = {'recognition', 'summary', 'candidate_summary', 'pass_summary', 'field_summary', 'form_summary', 'evidence_summary', 'image_summary', 'error'}
FIELDS = ['producer', 'wine_name', 'country', 'region', 'subregion', 'vintage']
COLUMNS = ['image_name', 'image_id', 'batch_id', 'build', 'evaluation_runtime', 'input_sha256',
           'candidate', 'stage', 'status', 'sequence', 'pass_id', 'region_id', 'raw_text', 'mlkit_confidence', 'ctc_posterior',
           'confidence_estimated', 'original_box_normalized', 'base_box_normalized', 'base_polygon', 'original_polygon',
           'detector', 'detector_sha256', 'recognizer', 'recognizer_sha256', 'dictionary_sha256',
           'elapsed_ms', 'work_ms', 'post_processing_version', 'details', 'evidence_details']
for field in FIELDS:
    COLUMNS += [field, f'{field}_state', f'{field}_reason', f'{field}_alternatives']


def export(root, output, run_id, source_commit):
    root, output = Path(root), Path(output)
    selected = [Path(line).name for line in (root/'selected-images.txt').read_text().splitlines()]
    if not selected or len(selected) != len(set(selected)):
        raise ValueError('Missing or duplicate selected images')
    selected = set(selected)
    manifest = json.loads((root/'acquisition-manifest.json').read_text())
    known = {Path(r['file']).name: r['image_sha256'] for r in manifest['records']}
    hashes = {}
    for line in (root/'input-hashes.tsv').read_text().splitlines():
        name, host, device = line.split('\t')
        if name in hashes or host != device or known.get(name) != host:
            raise ValueError('Duplicate or mismatched input hashes')
        hashes[name] = host
    if set(hashes) != selected:
        raise ValueError('Staging coverage differs from selected images')
    production = {}
    for path in (root/'results').glob('*-production.json'):
        report = json.loads(path.read_text())
        name = report['image']
        if name in production or report['build'] != source_commit[:12]:
            raise ValueError('Duplicate production image or wrong build')
        production[name] = report
    rows, lab, artifacts = [], {}, {}
    for path in sorted((root/'results').glob('*.csv')):
        artifacts[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        with path.open(newline='', encoding='utf-8') as stream:
            for row in csv.DictReader(stream):
                name = row['image_name']
                if name not in selected or row['build'] != source_commit[:12]:
                    raise ValueError('Unexpected lab image or wrong build')
                if row.get('input_sha256') and row['input_sha256'] != hashes[name]:
                    raise ValueError('OCR input differs from verified staged bytes')
                lab.setdefault(name, []).append(row)
                if row['stage'] in STAGES:
                    rows.append(row)
    if set(production) != selected or set(lab) != selected:
        raise ValueError('Not every selected image has production and lab reports')
    missing_summaries = {}
    for name, image_rows in lab.items():
        missing = [stage for stage in ('image_summary', 'form_summary', 'evidence_summary')
                   if sum(r['stage'] == stage for r in image_rows) != 1]
        if missing:
            missing_summaries[name] = missing
    summary = json.loads((root/'corpus-summary.json').read_text())
    audit = dict(run_id=run_id, source_commit=source_commit, verified_inputs=len(hashes),
                 production_images=len(production), lab_images=len(lab),
                 missing_or_duplicate_summaries=missing_summaries,
                 summary=summary, raw_csv_sha256=artifacts,
                 note='Execution results, not verified field accuracy. Native x86 CI, not phone timing. Full traces remain in the source run artifact.')
    output.mkdir(parents=True, exist_ok=True)
    (output/'audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2)+'\n')
    (output/'production.json').write_text(json.dumps(production, ensure_ascii=False, indent=2)+'\n')
    with (output/'ocr-comparison.csv').open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=COLUMNS, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(rows)
    total = sum(p.stat().st_size for p in output.iterdir() if p.is_file())
    if total > 24*1024*1024:
        raise ValueError('Export exceeds 24 MiB repository evidence budget; retain as artifact instead')
    print(json.dumps({k: v for k, v in audit.items() if k != 'raw_csv_sha256'}, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('root', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--run-id', required=True)
    p.add_argument('--source-commit', required=True)
    a = p.parse_args()
    export(a.root, a.output, a.run_id, a.source_commit)
