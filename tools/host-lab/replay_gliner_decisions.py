#!/usr/bin/env python3
"""Replay the actual Kotlin decision policy over a phone JSON; targets only score its output.

Compile the runner with Kotlin/JRE and the Android geometry stand-ins, for example:
  kotlinc tools/logic-checks/AndroidGeometry.kt \
    gliner-workflow/src/main/java/com/yourapp/wine/gliner/{GlinerDocument,GlinerLanes,GlinerEngines,GlinerFieldDecision}.kt \
    app/src/main/java/com/yourapp/wine/merge/TextNorm.kt \
    app/src/main/java/com/yourapp/wine/kb/{WineTerms,BundledGrapes,BundledWineStyles}.kt \
    tools/host-lab/GlinerDecisionReplay.kt -include-runtime -d /tmp/gliner-replay.jar
  python3 tools/host-lab/replay_gliner_decisions.py export.json --runner-jar /tmp/gliner-replay.jar

Older exports lack per-entity scores. For those, only a single lane span matching its top probe
can be reconstructed (probe scores are rounded); the report labels such replays approximate.
"""
import argparse
import base64
import json
import pathlib
import re
import subprocess
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIELDS = ('producer', 'wine_name', 'country', 'region', 'subregion', 'vintage')
LANES = {'producer': ('PRODUCER', 'producer'), 'wine_name': ('WINE_NAME', 'wine name'),
         'appellation': ('APPELLATION', 'appellation')}


def enc(s):
    return base64.urlsafe_b64encode(str(s).encode()).decode().rstrip('=')


def dec(s):
    return base64.urlsafe_b64decode(s + '=' * (-len(s) % 4)).decode()


def norm(s):
    s = unicodedata.normalize('NFKD', s.lower().replace('ς', 'σ').replace('&', ' et '))
    return ''.join(c for c in s if c.isalnum())


def hints(row):
    if 'lane_hints' in row:
        return [(LANES[h['lane']][0], h['score'], h['text']) for h in row['lane_hints']]
    found = []
    for lane, (enum, prefix) in LANES.items():
        text = row['lanes'].get(lane, '')
        if not text or ' | ' in text:
            continue
        for probe in row.get('probes', []):
            m = re.fullmatch(re.escape(prefix) + r' ([0-9.]+) (.*)', probe)
            if m and norm(m.group(2)) == norm(text):
                found.append((enum, float(m.group(1)), text))
    return found


def outcome(target, value):
    if target['status'] == 'unresolved':
        return 'unscored'
    if target['status'] == 'expected_blank':
        return 'correct_blank' if not value.strip() else 'unsupported_fill'
    if not value.strip():
        return 'missing_value'
    return 'correct_value' if norm(value) in target['accepted'] else 'wrong_value'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('export', type=pathlib.Path)
    parser.add_argument('--runner-jar', required=True, type=pathlib.Path)
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    data = json.loads(args.export.read_text())
    refs = {r['image']: r['fields'] for r in json.loads(
        (ROOT / 'app/src/main/assets/wine-app-labels-v1/scoring.json').read_text())['images']}
    rows = [row for row in data['images'] if row.get('status') == 'complete' and row.get('base_fields') and row.get('raw_readings')]
    if not rows:
        raise ValueError('No complete rows with base fields and OCR readings to replay')
    print(f"Replaying {len(rows)}/{len(data['images'])} exported rows with usable base/OCR evidence.")
    protocol = []
    for row in rows:
        fields = ','.join(enc(row['base_fields'].get(f, '')) for f in FIELDS)
        lane_hints = ';'.join(f'{lane},{score},{enc(text)}' for lane, score, text in hints(row))
        readings = ';'.join(','.join([enc(r['engine']), enc(r['text'])] + list(map(str, r['box'])))
                            for r in row.get('decision_readings', row['raw_readings']))
        protocol.append('\t'.join((fields, lane_hints, readings, str(row['review_number']))))
    proc = subprocess.run([args.java, '-jar', str(args.runner_jar)], input='\n'.join(protocol)+'\n',
                          text=True, capture_output=True, check=True)
    results = {}
    for line in proc.stdout.splitlines():
        number, field_data, change_data = line.split('\t')
        results[int(number)] = (dict(zip(FIELDS, map(dec, field_data.split(',')))),
                                [dec(c) for c in change_data.split(',') if c])
    print('WARNING: approximate legacy replay; missing/rounded lane hints or scrubbed readings.'
          if any('lane_hints' not in r or 'decision_readings' not in r for r in rows)
          else 'Exported decision readings and lane scores used with the current vocabulary; OCR and inference are not rerun.')
    gain = loss = before = after = 0
    current_complete = new_complete = current_gain = current_loss = 0
    field_gains = {f: 0 for f in FIELDS}
    field_losses = {f: 0 for f in FIELDS}
    current_field_gains = {f: 0 for f in FIELDS}
    current_field_losses = {f: 0 for f in FIELDS}
    excluded_field_gains = {f: 0 for f in FIELDS}
    excluded_field_losses = {f: 0 for f in FIELDS}
    for row in rows:
        final, changes = results[row['review_number']]
        targets = refs[row['image']]
        # Verify host scoring against the original device for every resolved base field.
        for f in FIELDS:
            actual = outcome(targets[f], row['base_fields'].get(f, ''))
            recorded = row['base_outcomes'].get(f)
            if recorded and not recorded.startswith('unscored') and actual != recorded:
                raise ValueError(f"Base scoring mismatch for #{row['review_number']} {f}: {actual} vs {recorded}")
            final_actual = outcome(targets[f], row['fields'].get(f, ''))
            final_recorded = row.get('outcomes', {}).get(f)
            if final_recorded and not final_recorded.startswith('unscored') and final_actual != final_recorded:
                raise ValueError(f"Final scoring mismatch for #{row['review_number']} {f}: {final_actual} vs {final_recorded}")
        eligible = row['eligible'] and row['status'] == 'complete'
        base_ok = eligible and all(outcome(targets[f], row['base_fields'].get(f, '')).startswith('correct_') for f in FIELDS)
        new_ok = eligible and all(outcome(targets[f], final.get(f, '')).startswith('correct_') for f in FIELDS)
        before += base_ok
        after += new_ok
        gain += eligible and not base_ok and new_ok
        loss += eligible and base_ok and not new_ok
        current_ok = eligible and all(outcome(targets[f], row['fields'].get(f, '')).startswith('correct_') for f in FIELDS)
        current_complete += current_ok
        new_complete += new_ok
        current_gain += eligible and not current_ok and new_ok
        current_loss += eligible and current_ok and not new_ok
        for f in FIELDS:
            if not eligible:
                # Excluded images don't enter the headline score, but check resolved fields for
                # regressions rather than letting the exclusion hide harmful policy changes.
                if targets[f]['status'] != 'unresolved':
                    current = outcome(targets[f], row['fields'].get(f, '')).startswith('correct_')
                    now = outcome(targets[f], final.get(f, '')).startswith('correct_')
                    excluded_field_gains[f] += not current and now
                    excluded_field_losses[f] += current and not now
                continue
            was = outcome(targets[f], row['base_fields'].get(f, '')).startswith('correct_')
            now = outcome(targets[f], final.get(f, '')).startswith('correct_')
            field_gains[f] += not was and now
            field_losses[f] += was and not now
            current = outcome(targets[f], row['fields'].get(f, '')).startswith('correct_')
            current_field_gains[f] += not current and now
            current_field_losses[f] += current and not now
            if current != now:
                print(f"  #{row['review_number']} {f}: {'improved' if now else 'regressed'} relative to exported final")
        if changes:
            print(f"#{row['review_number']} {'GAIN' if new_ok and not base_ok else 'LOSS' if base_ok and not new_ok else 'partial/noneligible'}: " + ' | '.join(changes))
    print(f'eligible base complete {before}; replay complete {after}; gains {gain}; losses {loss}')
    print('field gains:', field_gains, 'field losses:', field_losses)
    print(f'exported final complete {current_complete}; replay complete {new_complete}; new gains {current_gain}; new losses {current_loss}')
    print('field gains vs exported final:', current_field_gains, 'field losses:', current_field_losses)
    print('excluded resolved-field gains vs exported final:', excluded_field_gains, 'field losses:', excluded_field_losses)


if __name__ == '__main__':
    main()
