#!/usr/bin/env python3
"""Compare two host policy jars on saved, hash-matched personal-audit OCR (no inference).

Use the runner compilation command in replay_gliner_decisions.py for each policy version.
Reference images are not needed: only the phone export and owner-reviewed bundled hashes/targets.
Exact normalized scoring is conservative; the personal audit has no accepted-alias list.
"""
import argparse
import json
import pathlib
import subprocess
from replay_gliner_decisions import FIELDS, dec, enc, hints, norm

ROOT = pathlib.Path(__file__).resolve().parents[2]


def run(java, jar, rows):
    protocol = []
    for i, row in enumerate(rows):
        fields = ','.join(enc(row['base_fields'].get(f, '')) for f in FIELDS)
        lane_hints = ';'.join(f'{lane},{score},{enc(text)}' for lane, score, text in hints(row))
        readings = ';'.join(','.join([enc(r['engine']), enc(r['text'])] + list(map(str, r['box'])))
                            for r in row['decision_readings'])
        protocol.append('\t'.join((fields, lane_hints, readings, str(i))))
    result = subprocess.run([java, '-jar', str(jar)], input='\n'.join(protocol)+'\n',
                            text=True, capture_output=True, check=True)
    out = {}
    for line in result.stdout.splitlines():
        index, field_data, _ = line.split('\t')
        out[int(index)] = dict(zip(FIELDS, map(dec, field_data.split(','))))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('export', type=pathlib.Path)
    parser.add_argument('--before', required=True, type=pathlib.Path)
    parser.add_argument('--after', required=True, type=pathlib.Path)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    data = json.loads(args.export.read_text())
    references = {r['sha256']: r for r in json.loads((ROOT / 'gliner-workflow/src/main/assets/'
        'gliner-personal-audit-v1/references.json').read_text())['audit_references']}
    rows = [r for r in data.get('custom_images', []) if r.get('status') == 'complete' and
            r.get('sha256') in references and r.get('base_fields') and r.get('decision_readings')]
    if len({r['sha256'] for r in rows}) != len(rows):
        raise ValueError('Duplicate personal-audit image hashes')
    if not rows:
        raise ValueError('No hash-matched completed personal-audit rows with decision readings')
    a, b = run(args.java, args.before, rows), run(args.java, args.after, rows)
    gains = losses = complete_a = complete_b = 0
    for i, row in enumerate(rows):
        refs = references[row['sha256']]['references']
        def correct(f, values):
            target = refs[f]
            value = values.get(f, '').strip()
            if target['expected_status'] == 'expected_blank':
                return not value
            if target['expected_status'] == 'expected_value':
                return bool(value) and norm(value) == norm(target['expected'])
            return None
        before = [correct(f, a[i]) for f in FIELDS]
        after = [correct(f, b[i]) for f in FIELDS]
        complete_a += all(v is True for v in before)
        complete_b += all(v is True for v in after)
        for j, f in enumerate(FIELDS):
            if before[j] is None or after[j] is None:
                continue
            gains += not before[j] and after[j]
            losses += before[j] and not after[j]
            if before[j] != after[j]:
                print(f"{references[row['sha256']]['source_filename']} {f}: "
                      f"{a[i][f]!r} -> {b[i][f]!r} ({'gain' if after[j] else 'loss'})")
    print(f'{len(rows)} hash-matched personal audit photos; exact-normalized complete '
          f'{complete_a} -> {complete_b}; field gains {gains}, losses {losses}.')
    print('Saved base/OCR and original retained hints; approximate for missing candidates; not a fresh run or unseen holdout.')


if __name__ == '__main__':
    main()
