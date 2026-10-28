#!/usr/bin/env python3
"""Compare compact recent-build/suite CSV exports without images or raw OCR."""
import argparse
import csv
from collections import defaultdict
from pathlib import Path


def analyze(paths, control):
    rows = []
    for path in paths:
        with open(path, encoding='utf-8-sig', newline='') as stream:
            rows.extend(csv.DictReader(stream))
    summaries = {r['run_id']: r for r in rows if r['row_type'] == 'run_summary'}
    if not summaries:
        raise ValueError('No run summaries in input')
    controls = [r for r in summaries.values() if r['recent_rank'] == str(control)]
    if len(controls) != 1:
        raise ValueError('Control rank must identify exactly one run')
    base = controls[0]
    fields = defaultdict(dict)
    for row in rows:
        if row['row_type'] == 'wine_field':
            key = (row['image'], row['field'])
            previous = fields[row['run_id']].get(key)
            if previous and previous != row:
                raise ValueError('Conflicting field rows for one run')
            fields[row['run_id']][key] = row
    correct = lambda row: row['outcome'] in ('correct_value', 'correct_blank')
    missing = {'results_unavailable', 'not_attempted'}
    for run_id, summary in summaries.items():
        if summary['corpus_key'] != base['corpus_key']:
            raise ValueError('Cannot compare different corpus revisions')
        if set(fields[run_id]) != set(fields[base['run_id']]):
            raise ValueError('Runs have different field coverage')
        if any(r['outcome'] in missing for r in fields[run_id].values()):
            raise ValueError('Incomplete or unavailable run results; finish the run before comparison')
        for key, row in fields[run_id].items():
            ref = fields[base['run_id']][key]
            if (row['expected'], row['reference_status']) != (ref['expected'], ref['reference_status']):
                raise ValueError('Reference values differ between runs')
    lines = ['# Automated Lab comparison', '', f"Control: {base['run_id']}",
             f"Configuration: `{base['lab_modules']}`", '',
             '| Rank | Correct | Gains | Losses | Net | Changed outputs | Fully correct (logged) |',
             '|---|---:|---:|---:|---:|---:|---:|']
    details = []
    for run_id, run in sorted(summaries.items(), key=lambda item: int(item[1]['recent_rank'])):
        current = fields[run_id]
        reference = fields[base['run_id']]
        gained = sum(correct(v) and not correct(reference[k]) for k, v in current.items())
        lost = sum(not correct(v) and correct(reference[k]) for k, v in current.items())
        total = sum(correct(v) for v in current.values())
        changed = [(k, v) for k, v in current.items() if v['recognized'] != reference[k]['recognized'] or v['outcome'] != reference[k]['outcome']]
        lines.append(f"| {run['recent_rank']} | {total} | {gained} | {lost} | {gained-lost:+} | {len(changed)} | {run['complete']} |")
        details += ['', f"## Run {run['recent_rank']}", f"`{run['lab_modules']}`", '']
        if total != int(run['total_correct']):
            details.append('WARNING: saved total does not match field outcomes; references or saved scores may have changed.')
        for k, v in sorted(changed, key=lambda kv: (int(kv[1]['review_number']), kv[1]['field'])):
            before = reference[k]
            effect = 'GAIN' if correct(v) and not correct(before) else 'LOSS' if correct(before) and not correct(v) else 'CHANGED'
            details.append(f"- {effect}: #{v['review_number']} {v['field']}: {before['recognized']!r} → {v['recognized']!r}; expected {v['expected']!r}; {before['outcome']} → {v['outcome']}")
    return '\n'.join(lines + details) + '\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv', nargs='+', help='One export or all split parts')
    parser.add_argument('--control', type=int, default=1, help='Control recent_rank; suite control is 1')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        args.output.write_text(analyze(args.csv, args.control), encoding='utf-8')
    except (ValueError, KeyError) as error:
        parser.error(str(error))
