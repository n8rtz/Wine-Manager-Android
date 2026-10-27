#!/usr/bin/env python3
"""Score frozen Android outputs against separately frozen visual references.

No recognition KB, network, OCR engine, fuzzy match, or reference mutation.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

FIELDS = ('producer', 'wine_name', 'country', 'region', 'subregion', 'vintage')
PIPELINES = ('production', 'lab_baseline', 'experimental')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def normalize(value):
    text = unicodedata.normalize('NFKD', str(value).casefold().replace('&', ' et '))
    return ''.join(c for c in text if c.isalnum() and not unicodedata.combining(c))


def outcome(target, value):
    if target['status'] == 'unknown':
        return 'unscored_filled' if value.strip() else 'unscored_blank'
    if not value.strip():
        return 'abstained'
    return 'correct' if normalize(value) in {normalize(v) for v in target['accepted']} else 'wrong'


def load(reference_dir, run_dir):
    ref_path = reference_dir / 'reference.json'
    expected_hash = (reference_dir / 'reference.sha256').read_text().split()[0]
    if digest(ref_path) != expected_hash:
        raise ValueError('Reference hash mismatch; do not silently change frozen targets')
    ref = json.loads(ref_path.read_text())
    images = ref['images']
    expected = {i['image'] for i in images}
    if len(expected) != 168 or len(images) != 168:
        raise ValueError('Expected 168 unique references')
    hashes = {i['image']: i['image_sha256'] for i in images}
    for item in images:
        if set(item['fields']) != set(FIELDS):
            raise ValueError('Incomplete target schema')
        for t in item['fields'].values():
            if t['status'] == 'known':
                if not t['value'] or t['value'] not in t['accepted']:
                    raise ValueError('Known target needs nonempty accepted canonical value')
            elif t['status'] != 'unknown' or t['value'] is not None or t['accepted']:
                raise ValueError('Unknown must have no value or accepted aliases')
    prod = json.loads((run_dir / 'production.json').read_text())
    if set(prod) != expected:
        raise ValueError('Production inventory mismatch')
    predictions = {p: {} for p in PIPELINES}
    for name, report in prod.items():
        predictions['production'][name] = {
            f: (report['fields'][f.upper()]['value'], report['fields'][f.upper()]['state']) for f in FIELDS}
    with (run_dir / 'ocr-comparison.csv').open(newline='') as f:
        for row in csv.DictReader(f):
            pipeline = {('production_combined', 'form_summary'): 'lab_baseline',
                        ('experimental_evidence', 'evidence_summary'): 'experimental'}.get((row['candidate'], row['stage']))
            if not pipeline:
                continue
            name = row['image_name']
            if name not in expected or name in predictions[pipeline]:
                raise ValueError('Unexpected or duplicate final summary')
            if row['input_sha256'] != hashes[name]:
                raise ValueError('Input hash mismatch')
            predictions[pipeline][name] = {f: (row[f], row[f + '_state']) for f in FIELDS}
    for pipeline, entries in predictions.items():
        if set(entries) != expected:
            raise ValueError(f'{pipeline}: missing final summaries')
        for fields in entries.values():
            for value, state in fields.values():
                if not isinstance(value, str) or state not in ('RED', 'YELLOW', 'GREEN'):
                    raise ValueError('Invalid output value/state')
                if state == 'RED' and value.strip():
                    raise ValueError('Unexpected filled RED field: inspect display contract')
    return ref, predictions, prod


def summarize(rows):
    counts = Counter(r['outcome'] for r in rows)
    result = {k: counts[k] for k in ('correct', 'wrong', 'abstained', 'unscored_filled', 'unscored_blank')}
    result['scored'] = sum(result[k] for k in ('correct', 'wrong', 'abstained'))
    result['unscored'] = result['unscored_filled'] + result['unscored_blank']
    result['total'] = len(rows)
    result['filled'] = result['correct'] + result['wrong'] + result['unscored_filled']
    result['correct_over_scored_pct'] = round(100 * result['correct'] / result['scored'], 2) if result['scored'] else None
    checked_fills = result['correct'] + result['wrong']
    result['correct_over_scored_fills_pct'] = round(100 * result['correct'] / checked_fills, 2) if checked_fills else None
    return result


def image_summary(rows):
    by_image = defaultdict(list)
    for row in rows:
        by_image[row['image']].append(row['outcome'])
    assert all(len(v) == 6 for v in by_image.values())
    eligible = [v for v in by_image.values() if not any(s.startswith('unscored') for s in v)]
    return {
        'images': len(by_image), 'six_field_eligible': len(eligible),
        'six_field_ineligible': len(by_image) - len(eligible),
        'six_field_fully_correct': sum(all(s == 'correct' for s in v) for v in eligible),
        'all_known_fields_correct_not_full_six': sum(
            any(s == 'correct' for s in v) and all(s == 'correct' or s.startswith('unscored') for s in v)
            for v in by_image.values()),
        'images_with_scored_wrong_fill': sum('wrong' in v for v in by_image.values()),
        'images_with_scored_abstention': sum('abstained' in v for v in by_image.values()),
    }


def score(ref, predictions, production):
    rows = []
    group_members = defaultdict(list)
    for item in ref['images']:
        group_members[item['identity_group']].append(item['review_number'])
        for pipeline in PIPELINES:
            for field in FIELDS:
                target = item['fields'][field]
                value, state = predictions[pipeline][item['image']][field]
                rows.append({
                    'review_number': item['review_number'], 'image': item['image'],
                    'identity_group': item['identity_group'], 'pipeline': pipeline, 'field': field,
                    'reference_status': target['status'], 'target': target['value'] or '',
                    'accepted': json.dumps(target['accepted'], ensure_ascii=False), 'basis': target['basis'],
                    'output': value, 'state': state, 'outcome': outcome(target, value),
                    'production_deadline_cancelled': production[item['image']]['deadlineCancelled'],
                })
    first = {min(v) for v in group_members.values()}
    result = {'images': len(ref['images']), 'identity_groups': len(group_members),
              'repeated_identity_groups': {g: ns for g, ns in group_members.items() if len(ns) > 1},
              'pipelines': {}}
    for pipeline in PIPELINES:
        selected = [r for r in rows if r['pipeline'] == pipeline]
        representatives = [r for r in selected if r['review_number'] in first]
        p = {'all_fields': summarize(selected), 'per_field': {f: summarize([r for r in selected if r['field'] == f]) for f in FIELDS},
             'image_results': image_summary(selected),
             'one_photo_per_identity': {'selection': 'lowest review number (source hash order); fixed independently of outputs',
                                        'all_fields': summarize(representatives), 'image_results': image_summary(representatives)},
             'by_output_state': {s: summarize([r for r in selected if r['state'] == s]) for s in ('GREEN', 'YELLOW', 'RED')},
             'production_deadline_strata': {str(v).lower(): summarize([r for r in selected if r['production_deadline_cancelled'] == v]) for v in (False, True)}}
        repeats = []
        for g, ns in group_members.items():
            if len(ns) < 2:
                continue
            gr = [r for r in selected if r['identity_group'] == g]
            stable = sum(len({(r['target'] if r['outcome'] == 'correct' else normalize(r['output'])) for r in gr if r['field'] == f}) == 1 for f in FIELDS)
            eligible = all(not r['outcome'].startswith('unscored') for r in gr)
            repeats.append({'identity_group': g, 'review_numbers': ns, 'stable_fields_of_six': stable,
                            'six_field_eligible': eligible, 'every_photo_six_fields_correct': eligible and all(r['outcome'] == 'correct' for r in gr)})
        p['repeat_consistency'] = repeats
        result['pipelines'][pipeline] = p
    return result, rows


def write_report(result, output):
    lines = ['# Public-label corpus: visual-reference scoring', '',
             '**Provisional, single-agent visual review—not human-adjudicated ground truth.**', '',
             'All 168 photos were visually reviewed. An owner-requested producer-role correction now treats Club des Sommeliers as label branding rather than an established producer (reviews 14 and 108); the A. Kolb aliases no longer include Club des Sommeliers (reviews 16 and 66). Actual producers remain unknown where the photos do not establish them. Saved OCR outputs were not changed; scores below are recomputed against this corrected reference.', '',
             '## Overall results', '',
             '| Pipeline | Correct | Wrong fills | Abstained | Scored | Unscored | Correct / scored | Correct / scored fills |',
             '|---|---:|---:|---:|---:|---:|---:|---:|']
    for name, p in result['pipelines'].items():
        s = p['all_fields']
        lines.append(f"| {name} | {s['correct']} | {s['wrong']} | {s['abstained']} | {s['scored']} | {s['unscored']} | {s['correct_over_scored_pct']}% | {s['correct_over_scored_fills_pct']}% |")
    lines += ['', f"Wrong means a nonblank output fails the label-supported aliases. Abstained means blank despite a known target. The {result['pipelines']['production']['all_fields']['unscored']} unknown targets are excluded from correctness denominators: their filled values are **unverified**, not automatically wrong or correct. GREEN/YELLOW suggestions are both evaluated; this is field correctness, not automatic-acceptance accuracy.", '',
              '## Per-field results', '', '| Field | Known / 168 | Production C / W / A | Lab baseline C / W / A | Experimental C / W / A |', '|---|---:|---:|---:|---:|']
    for f in FIELDS:
        data = [result['pipelines'][n]['per_field'][f] for n in PIPELINES]
        lines.append(f"| {f} | {data[0]['scored']} | " + ' | '.join(f"{s['correct']} / {s['wrong']} / {s['abstained']}" for s in data) + ' |')
    lines += ['', 'C = correct; W = wrong fill; A = abstained. Unscorable per field is 168 minus Known.', '', '## Whole-image correctness', '',
              '| Pipeline | All six correct / six-known eligible | Ineligible images | All known fields correct (includes partial references) | Images with ≥1 scored wrong fill |', '|---|---:|---:|---:|---:|']
    for name,p in result['pipelines'].items():
        s=p['image_results']
        lines.append(f"| {name} | {s['six_field_fully_correct']} / {s['six_field_eligible']} | {s['six_field_ineligible']} | {s['all_known_fields_correct_not_full_six']} / {s['images']} | {s['images_with_scored_wrong_fill']} |")
    lines += ['', '**Full correctness requires all six reference fields known and all six outputs correct.** Partial-reference successes do not certify a complete bottle entry, because unscored fields could contain errors. Empty unknowns earn no credit.', '',
              '## Unknown fields and fill counts', '', '| Pipeline | Filled unknowns (unverified) | Blank unknowns | All filled fields / 1008 |', '|---|---:|---:|---:|']
    for name,p in result['pipelines'].items():
        s=p['all_fields'];lines.append(f"| {name} | {s['unscored_filled']} | {s['unscored_blank']} | {s['filled']} |")
    lines += ['', '## Duplicate sensitivity', '',
              f"The 168 byte-distinct photos form **{result['identity_groups']} reviewed visible identity groups**, including **{len(result['repeated_identity_groups'])} repeated groups**. These are not proven physical bottle IDs. Different vintages are separate. Repeated undated labels cannot prove vintage equivalence.", '',
              'One representative per identity is selected by lowest review number (source-hash order), independently of output quality:', '',
              '| Pipeline | Correct / wrong / abstained | Scored | Correct / scored | All six correct / eligible |', '|---|---:|---:|---:|---:|']
    for name,p in result['pipelines'].items():
        s=p['one_photo_per_identity']['all_fields'];i=p['one_photo_per_identity']['image_results']
        lines.append(f"| {name} | {s['correct']} / {s['wrong']} / {s['abstained']} | {s['scored']} | {s['correct_over_scored_pct']}% | {i['six_field_fully_correct']} / {i['six_field_eligible']} |")
    lines += ['', 'This is a duplicate-sensitivity check, not an independent bottle-level benchmark or train/test split. `scores.json` also includes repeated-group output stability (which may be consistently wrong), confidence-state strata, and strata defined by the production deadline flag.', '',
              '## Main finding', '',
              f"The lab baseline leads this comparison. Relative to it, experimental produces **{result['pipelines']['lab_baseline']['all_fields']['correct']-result['pipelines']['experimental']['all_fields']['correct']} fewer correct fields and {result['pipelines']['experimental']['all_fields']['wrong']-result['pipelines']['lab_baseline']['all_fields']['wrong']} more wrong fills**. The updated per-field totals are shown above. This does not support promoting experimental over the baseline. The one-photo-per-identity check preserves the same ordering.", '',
              'Producer and wine-name assignment are the dominant remaining weaknesses, not country recognition. The next engineering step should preserve these frozen outputs, add decision-neutral role/composition traces, and address assignment failures without changing production or using these references as a recognition lookup.', '',
              '## Interpretation and limitations', '',
              '- Geography may be inferred from a visible appellation; it is not all literal label transcription. Country stores the US state per the app convention. Origin is not inferred merely from estate reputation or bottler addresses.',
              '- Producer versus brand/cuvée roles, generic range wording, and geographic display levels involve judgment. The frozen reference lists accepted aliases and review notes. Matching is exact after case/accent/punctuation normalization, not fuzzy or substring matching. Alternative assignments not listed may deserve adjudication; these results are diagnostic, not final expert-certified truth.',
              '- The same agent built the tooling and reviewed the photos. Detailed saved predictions were not consulted during annotation, but aggregate fill counts were already known. This is not an independently blinded expert study.',
              '- The corpus is heavily French/Bordeaux-weighted, repeats estates and labels, and is not a representative or held-out worldwide benchmark. All 168 images are retained, including one with uncertain product category.',
              '- Production used its normal 15-second budget; the lab has its separate 60-second diagnostic budget. 95 production scans reached the deadline. Comparisons therefore conflate available OCR evidence/budget with post-processing behavior. Emulator timings are not phone performance.',
              '- The previous 639 / 724 / 713 fill counts are reproduced, but fills are not correctness. No new OCR run, model, or production change was needed.', '',
              '## Reproduction and review files', '',
              '```sh', 'python3 tools/corpus/score_public_corpus.py', 'python3 -m unittest discover -s tools/tests', '```', '',
              '- Reference: `tools/evaluation/public-corpus-reference-v1/reference.json` plus SHA-256 freeze; `readings.tsv` is the reviewable transcription with notes and pinned source-photo links in JSON.',
              '- Per-output decisions: `tools/evaluation/public-corpus-scoring-v1/field-results.csv` (3,024 rows).',
              '- Counts, duplicate groups, stability, and input hashes: `tools/evaluation/public-corpus-scoring-v1/scores.json`.',
              '- Frozen Android evidence: `tools/evaluation/public-corpus-run-39716483937/`.',
              '- Photos remain outside Git. Any future reference correction must be versioned and reported; do not tune targets to predictions.', '']
    output.write_text('\n'.join(lines))


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, default=root/'tools/evaluation/public-corpus-reference-v1')
    parser.add_argument('--run', type=Path, default=root/'tools/evaluation/public-corpus-run-39716483937')
    parser.add_argument('--output', type=Path, default=root/'tools/evaluation/public-corpus-scoring-v1')
    parser.add_argument('--report', type=Path, default=root/'docs/public-label-corpus-accuracy-v1.md')
    args = parser.parse_args()
    ref,pred,prod = load(args.reference,args.run)
    result,rows = score(ref,pred,prod)
    result['provenance'] = {str(p.relative_to(root)) if p.is_relative_to(root) else str(p): digest(p) for p in
                            (args.reference/'reference.json', args.run/'production.json', args.run/'ocr-comparison.csv', Path(__file__))}
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'scores.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    with (args.output/'field-results.csv').open('w',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=list(rows[0]),lineterminator="\n");writer.writeheader();writer.writerows(rows)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    write_report(result,args.report)
    for name,p in result['pipelines'].items():
        print(name,p['all_fields'],p['image_results'])


if __name__ == '__main__':
    main()
