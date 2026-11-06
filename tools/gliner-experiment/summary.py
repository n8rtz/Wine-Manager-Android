#!/usr/bin/env python3
"""Render a completed or failed experiment as a compact self-explanatory report."""
import json
import pathlib
import sys

p=pathlib.Path(sys.argv[1])
if not p.exists():
    print('# GLiNER saved-OCR experiment\n\n**Failed before the scoring report was written.** Inspect workflow steps for the error; no result can be inferred.')
    raise SystemExit(0)
r=json.loads(p.read_text())
print('# Automated GLiNER saved-OCR comparison\n')
print('193 attempted, 188 eligible development images; OCR and references frozen. No new OCR or Android build.\n')
print('**Control parity:** '+('verified against all saved phone output fields.' if r['control_verified'] else 'FAILED — diagnostic only; do not interpret gains as verified.'))
print(f"\nModel load {r['load_seconds']:.2f}s; total wall time {r['wall_seconds']:.1f}s, host CPU only (not phone latency).")
for group,t in r['inference_latency_seconds'].items():
    if t['first'] is not None: print(f"- {group}: first {t['first']:.3f}s, median {t['median']:.3f}s, p90 {t['p90']:.3f}s per model invocation.")
print('\n| Configuration | Complete | Complete gains / losses vs control | Correct fields | Field gains / losses | Failures |')
print('|---|---:|---:|---:|---:|---:|')
for name in ['control','low_threshold','lane_overlap','six_labels']:
    fail=len(r['failures'][name]);v=r['variants'].get(name)
    if not v:
        print(f'| {name} | not scored | — | — | — | {fail} |');continue
    l=v['labels'];f=v['fields'];n=sum(x.get('correct',0) for x in f.values());gain=sum(x.get('gain',0) for x in f.values());loss=sum(x.get('loss',0) for x in f.values())
    print(f"| {name} | {l['complete']}/{l['eligible']} | +{l.get('complete_gain',0)} / -{l.get('complete_loss',0)} | {n}/1128 | +{gain} / -{loss} | {fail} |")
print('\n| Configuration | Field | Correct / 188 | Gained / lost | Wrong | Missing | Unsupported fill |')
print('|---|---|---:|---:|---:|---:|---:|')
for name,v in r['variants'].items():
    for f,x in v['fields'].items():
        print(f"| {name} | {f} | {x.get('correct',0)} | +{x.get('gain',0)} / -{x.get('loss',0)} | {x.get('wrong_value',0)} | {x.get('missing_value',0)} | {x.get('unsupported_fill',0)} |")
print('\nThe six-label run sends only its first three lane results into the existing hybrid policy. Its additional country, region and vintage spans are diagnostic only; they do not fill these fields.')
if 'six_extra_exact_span_recall' in r:
    print('\n| Extra lane | Exact accepted span found (expected-value cases) | Blank cases with any predicted span |')
    print('|---|---:|---:|')
    for f,x in r['six_extra_exact_span_recall'].items(): print(f"| {f} | {x['exact_span_found']}/{x['expected_value_cases']} | {x['blank_with_any_span']}/{x['expected_blank_cases']} |")
for name,failures in r['failures'].items():
    if failures: print(f"\n**{name} failures:** {len(failures)}; first: {failures[0]['image']}: {failures[0]['error']}")
print('\nThis is development data, not a held-out test. Differences are not proof of generalization. Full paired changes and hashes are in the JSON artifact.')
