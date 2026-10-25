"""Relaxing `eligible()` for single-word spans, and measuring it.

`eligible()` requires `words.size in 2..8`, so a lone `drouhin` or `blackstone` never reaches
the matcher. For single words the edit-distance gate is already strict: `oregon` vs
`oregon solidarity` is distance 10, rejected. So allowing one-word spans back in is a recall
fix whose precision risk is confined to two one-word producer names within distance 1.

This measures the threshold for that relaxation and whether adding the guarded partial rule
on top of it helps or hurts.
"""
import collections
import csv
import re
import sys

sys.path.insert(0, 'tools/host-checks')
from producer_resolver_replay import (ASSET, LAB_PIPELINE, Index,
                                      MAX_FUZZY_CANDIDATES, MAX_SPANS,
                                      distance, eligible, load_raw, normalize,
                                      spans_for)
from producer_partial_match_experiment import pred_prefix_filler

NOISE = re.compile(
    r'\b(?:imported|importer|imports|distributed|distributor|bottled|bottler|embouteille|'
    r'mis en bouteille|produced by|product of|produit de|appellation|controlee|protegee|'
    r'alcohol|alc|vol|contains|sulfites|sulphites|government|warning|llc|inc|corp|ltd|www|http)\b')
GENERIC = {"chateau", "domaine", "domaines", "estate", "winery", "wine", "wines",
           "vineyard", "vineyards", "cellar", "cellars", "bodega", "bodegas", "maison",
           "de", "du", "des", "la", "le", "les", "the", "and", "et", "of", "family",
           "reserve", "reserva", "grand", "cru"}


def eligible_v2(text, min_single=5):
    """Same as the shipped `eligible()`, but a single word may pass if long enough."""
    if len(text) > 96 or any(c.isdigit() for c in text) or '%' in text or '\n' in text:
        return False
    q = normalize(text)
    words = q.split(' ')
    if not words[0]:
        return False
    if len(words) == 1:
        if len(words[0]) < min_single:
            return False
        return words[0] not in GENERIC and not NOISE.search(q)
    return (len(q) >= 8 and len(words) <= 8 and not NOISE.search(q)
            and any(len(w) >= 4 and w not in GENERIC for w in words))


def resolve_v2(index, existing, spans, elig, partial=None):
    if len(spans) > MAX_SPANS:
        return existing, "span limit; abstained", []
    matches = []
    for text, engine, obs in spans:
        if not engine or not obs or not elig(text):
            continue
        q = normalize(text)
        gate = 2 if len(q) >= 20 else 1
        seen = set()
        for eid, name, key in index.lookup(q, MAX_FUZZY_CANDIDATES):
            if eid in seen or len(key) > 96:
                continue
            ok = distance(q, key) <= gate if partial is None else (
                distance(q, key) <= gate or partial(q, key))
            if ok:
                seen.add(eid)
                matches.append((eid, name, key, q, engine, obs))
    groups = collections.defaultdict(list)
    for m in matches:
        groups[m[0]].append(m)
    viable = {}
    for eid, rows in groups.items():
        for a in rows:
            for b in rows:
                if a[4] != b[4] and not (a[5] & b[5]):
                    viable[eid] = rows
                    break
            if eid in viable:
                break
    if not viable:
        return existing, "no independently supported producer; unchanged", matches
    if len(viable) != 1:
        return existing, "ambiguous producer identities; unchanged", matches
    (eid, rows), = viable.items()
    best = min(distance(m[3], m[2]) for m in rows)
    if any(oid != eid and min(distance(m[3], m[2]) for m in r) <= best + 1
           for oid, r in groups.items()):
        return existing, "insufficient separation from competing producer; unchanged", matches
    if existing:
        old = normalize(existing)
        if old == rows[0][2]:
            return existing, "existing producer retained", matches
        if not any(normalize(m[3]) == old for m in rows) or distance(old, rows[0][2]) > 1:
            return existing, "different existing producer protected", matches
    return rows[0][1], "primary producer/brand name supported by two engines; confirm manually", matches, eid


def main():
    raw = load_raw(['/tmp/arena-coding-uploads/wine-168-raw-lab-f9a8891dc192-part-01.csv',
                    '/tmp/arena-coding-uploads/wine-168-raw-lab-f9a8891dc192-part-02.csv'])
    lab, exp = {}, {}
    for r in csv.DictReader(open('/tmp/arena-coding-uploads/wine-168-lab-f9a8891dc192.csv',
                                 newline='', encoding='utf-8-sig')):
        if r['row_type'] != 'field' or r['field'] != 'producer':
            continue
        if r['pipeline'] == LAB_PIPELINE:
            lab[r['image']] = r['recognized']
        exp[r['image']] = (r['reference_status'], r['expected'])
    spans = {i: spans_for(r) for i, r in raw.items()}
    index = Index(ASSET)

    def same(a, b):
        return normalize(a).replace(' ', '') == normalize(b).replace(' ', '')

    def outcome(v, rs, rv):
        if rs == 'expected_blank':
            return 'correct_blank' if v == '' else 'unsupported_fill'
        if rs == 'unresolved':
            return 'unresolved'
        return 'correct_value' if same(v, rv) else ('missing_value' if v == '' else 'wrong_value')

    def run(label, elig, partial):
        moved, dec = collections.Counter(), collections.Counter()
        for i in raw:
            before = lab.get(i, '')
            after, reason = resolve_v2(index, before, spans[i], elig, partial)[:2]
            dec[reason.split(';')[0]] += 1
            if same(before, after):
                continue
            rs, rv = exp.get(i, ('unresolved', ''))
            moved[outcome(before, rs, rv) + ' -> ' + outcome(after, rs, rv)] += 1
        print('%-34s %s' % (label, dict(sorted(moved.items())) or '{}'))
        print('%-34s decisions: %s' % ('', dict(sorted(dec.items()))))

    reached = sum(1 for sp in spans.values() for t, e, o in sp
                  if e and o and eligible(t))
    reached_v2 = sum(1 for sp in spans.values() for t, e, o in sp
                     if e and o and eligible_v2(t, 5))
    print('spans reaching the gate: shipped %d -> relaxed %d' % (reached, reached_v2))

    for ms in (4, 5, 6, 8):
        n = sum(1 for sp in spans.values() for t, e, o in sp if e and o and eligible_v2(t, ms))
        print('\n-- min_single=%d (%d spans reach the gate) --' % (ms, n))
        run('  exact gate only', lambda t, m=ms: eligible_v2(t, m), None)
        run('  + prefix/filler partial', lambda t, m=ms: eligible_v2(t, m), pred_prefix_filler)


if __name__ == '__main__':
    main()
