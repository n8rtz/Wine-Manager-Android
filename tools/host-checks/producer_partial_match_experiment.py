"""Is the producer gate exact-only, and what would a partial-exact rule add?

The shipped gate (ProducerResolver.resolve) is whole-string edit distance <=1, or <=2 when the
query is >=20 chars, plus `distance()` short-circuiting to 3 whenever the lengths differ by
more than 2. There is no prefix, substring or partial-exact rule.

Measured against the owner's 168-photo export and the packaged producer index: how many spans
each partial rule would newly accept, whether the FTS retrieval returns them, and what it does
end-to-end to the scoreboard.
"""
import collections
import csv
import sys

sys.path.insert(0, 'tools/host-checks')
from producer_resolver_replay import (ASSET, LAB_PIPELINE, Index,
                                      MAX_FUZZY_CANDIDATES, MAX_SPANS,
                                      distance, eligible, load_raw, normalize,
                                      spans_for)

FILLER = {'winery', 'wineries', 'wines', 'wine', 'vineyard', 'vineyards', 'cellar',
          'cellars', 'estate', 'estates', 'domaine', 'domaines', 'chateau', 'chateaux',
          'cru', 'grand', 'reserve', 'reserva', 'family', 'the', 'de', 'du', 'des',
          'la', 'le', 'les', 'and', 'et', 'of', 'selection', 'selección', 'bottlers'}


def _w(s):
    return s.split(' ')


def pred_prefix_filler(variant, key):
    """`blackstone` -> `blackstone winery`. Trailing words must all be filler."""
    v, k = _w(variant), _w(key)
    return len(v) <= len(k) and k[:len(v)] == v and all(x in FILLER for x in k[len(v):])


def pred_prefix_any(variant, key):
    v, k = _w(variant), _w(key)
    return len(v) <= len(k) and k[:len(v)] == v


def pred_contains_any(variant, key):
    """Query words appear as one contiguous run anywhere in the key."""
    v, k = _w(variant), _w(key)
    return any(k[i:i + len(v)] == v for i in range(len(k) - len(v) + 1))


def pred_contains_all(variant, key):
    v, k = set(_w(variant)), set(_w(key))
    return len(v) >= 2 and v <= k


PARTIAL_RULES = [('prefix+filler', pred_prefix_filler),
                 ('prefix any', pred_prefix_any),
                 ('contains run', pred_contains_any),
                 ('contains all', pred_contains_all)]


def resolve_partial(index, existing, spans, partial):
    """_resolve with scrub=False/similarity=False, with a pluggable acceptance gate."""
    if len(spans) > MAX_SPANS:
        return existing, "span limit; abstained", []
    matches = []
    for text, engine, obs in spans:
        if not engine or not obs or not eligible(text):
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


def build_sets(keys):
    """Word-prefix / contiguous-run / word-membership sets over every key."""
    pref_any, pref_filler, runs = (collections.defaultdict(set) for _ in range(3))
    word2keys = collections.defaultdict(set)
    for k in keys:
        w = k.split(' ')
        for n in range(1, len(w) + 1):
            t = tuple(w[:n])
            pref_any[n].add(t)
            if all(x in FILLER for x in w[n:]):
                pref_filler[n].add(t)
        for n in range(1, len(w) + 1):
            for i in range(len(w) - n + 1):
                runs[n].add(tuple(w[i:i + n]))
        for x in set(w):
            word2keys[x].add(k)
    return pref_any, pref_filler, runs, word2keys


def full_scan(pred, q, pref_any, pref_filler, runs, word2keys):
    """Would any key in the whole asset satisfy this rule for this query?"""
    qw = _w(q)
    if pred is pred_prefix_filler:
        return tuple(qw) in pref_filler[len(qw)]
    if pred is pred_prefix_any:
        return tuple(qw) in pref_any[len(qw)]
    if pred is pred_contains_any:
        return tuple(qw) in runs[len(qw)]
    sets = [word2keys[x] for x in set(qw)]
    return len(set(qw)) >= 2 and all(sets) and set.intersection(*sets)


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
    keys = list(index.exact.keys())
    pref_any, pref_filler, runs, word2keys = build_sets(keys)

    # Spans the shipped gate already accepts, plus spans dropped before matching.
    eligible_spans = total_spans = dropped_eligible = 0
    for sp in spans.values():
        for text, engine, obs in sp:
            if not engine or not obs:
                continue
            total_spans += 1
            if not eligible(text):
                dropped_eligible += 1
            else:
                eligible_spans += 1
    print('spans: %d total, %d reach the gate, %d dropped by eligible()'
          % (total_spans, eligible_spans, dropped_eligible))

    print('\nrule              newly accepted   retrieval returns   images with a 2-engine decision')
    for name, pred in PARTIAL_RULES:
        newly = retrieved = imgs = 0
        images = set()
        for i, sp in spans.items():
            for text, engine, obs in sp:
                if not engine or not obs or not eligible(text):
                    continue
                q = normalize(text)
                gate = 2 if len(q) >= 20 else 1
                hits = index.lookup(q, MAX_FUZZY_CANDIDATES)
                cur = any(distance(q, h[2]) <= gate for h in hits)
                if cur:
                    continue
                if any(pred(q, h[2]) for h in hits):
                    retrieved += 1
                    images.add(i)
                if full_scan(pred, q, pref_any, pref_filler, runs, word2keys):
                    newly += 1
        imgs = len(images)
        print('%-16s %6d             %6d                %6d' % (name, newly, retrieved, imgs))

    def same(a, b):
        return normalize(a).replace(' ', '') == normalize(b).replace(' ', '')

    def outcome(v, rs, rv):
        if rs == 'expected_blank':
            return 'correct_blank' if v == '' else 'unsupported_fill'
        if rs == 'unresolved':
            return 'unresolved'
        return 'correct_value' if same(v, rv) else ('missing_value' if v == '' else 'wrong_value')

    for name, pred in PARTIAL_RULES:
        moved, dec = collections.Counter(), collections.Counter()
        for i in raw:
            before = lab.get(i, '')
            after, reason = resolve_partial(index, before, spans[i], pred)[:2]
            dec[reason.split(';')[0]] += 1
            if same(before, after):
                continue
            rs, rv = exp.get(i, ('unresolved', ''))
            moved[outcome(before, rs, rv) + ' -> ' + outcome(after, rs, rv)] += 1
        print('\n== %s ==' % name)
        print('   moved: %s' % (dict(sorted(moved.items())) or '{}'))
        print('   decisions: %s' % dict(sorted(dec.items())))


if __name__ == '__main__':
    main()
