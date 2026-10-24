#!/usr/bin/env python3
"""Experiment: per-engine OCR scrubbing + a cross-engine similarity rule.

Runs the owner's 168-photo Lab export through three matcher variants and reports
what each one buys. Nothing here touches the app; it is a host-side model of the
resolver built on the readings the Lab run actually recorded.

Variants
  V0  current: `eligible()` rejects any span containing a digit, no scrubbing,
      and a producer decision needs two engines converging on the same entity.
  V1  + scrubbing: digits that sit inside a mixed alphanumeric token are treated
      as misread letters (1->i/l, 0->o, 3->b, 4->a, 5->s, 8->b, 9->g, 7->t), and
      the digits are also tried deleted. The digit rejection is dropped, because
      scrubbing is the fix.
  V2  + cross-engine similarity: a producer decision no longer needs both engines
      to land on the same entity. One engine matching entity X is enough if the
      other engine's best reading is within two edits of it.

Usage (from the repository root):
  python3 tools/host-checks/producer_ocr_scrub_experiment.py <scored.csv> <raw.csv> [...]
"""
import csv
import collections
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from producer_resolver_replay import (ASSET, LAB_PIPELINE, SAVED_V2, Index,
                                      MAX_FUZZY_CANDIDATES, MAX_SPANS, distance,
                                      eligible, load_raw, normalize, spans_for)

# OCR confusions that are not real on a wine label.
DIGIT_LETTERS = {'0': ('o',), '1': ('i', 'l'), '2': ('z',), '3': ('b',), '4': ('a',),
                 '5': ('s',), '6': ('g',), '7': ('t',), '8': ('b',), '9': ('g',)}


def _mixed_tokens(q):
    return [t for t in q.split(' ') if any(c.isdigit() for c in t) and any(c.isalpha() for c in t)]


def scrub_variants(text):
    """Candidate scrubbed forms for one raw OCR span, best first."""
    q = normalize(text)
    out = [q]
    mixed = _mixed_tokens(q)
    if not mixed:
        return out
    out.append(re.sub(r'\d', '', q).strip())          # digits were inserted noise
    for token in mixed:
        for index, ch in enumerate(token):
            for letter in DIGIT_LETTERS.get(ch, ()):   # digits were misread letters
                out.append(q.replace(token, token[:index] + letter + token[index + 1:], 1))
    return [o for o in out if o]


def _resolve(index, existing, spans, scrub, similarity):
    if len(spans) > MAX_SPANS:
        return existing, "span limit; abstained"
    matches = []
    for text, engine, observation_ids in spans:
        if not engine or not observation_ids:
            continue
        if scrub:
            if len(text) > 96 or '%' in text or '\n' in text:
                continue
            q = normalize(text)
            if len(q) < 8 or not (2 <= len(q.split(' ')) <= 8):
                continue
            if not any(len(w) >= 4 for w in q.split(' ')):
                continue
        else:
            if not eligible(text):
                continue
            q = normalize(text)
        forms = scrub_variants(q) if scrub else [q]
        seen_here = set()
        for variant in forms:
            gate = 2 if len(variant) >= 20 else 1
            for entity_id, name, key in index.lookup(variant, MAX_FUZZY_CANDIDATES):
                if entity_id in seen_here or len(key) > 96:
                    continue
                if distance(variant, key) <= gate:
                    seen_here.add(entity_id)
                    matches.append((entity_id, name, key, variant, engine, observation_ids))
    groups = collections.defaultdict(list)
    for m in matches:
        groups[m[0]].append(m)
    viable = {}
    for entity_id, rows in groups.items():
        for a in rows:
            for b in rows:
                if a[4] != b[4] and not (a[5] & b[5]):
                    viable[entity_id] = rows
                    break
            if entity_id in viable:
                break
    if not viable and similarity and matches:
        # One engine landing on entity X is enough when the other engine's own
        # reading of the same label is close to X. The other engine need not have
        # matched anything: that is the whole point of the rule.
        ranked = sorted(groups.items(),
                        key=lambda kv: (min(distance(m[3], m[2]) for m in kv[1]),
                                        -len({m[4] for m in kv[1]}), kv[0]))
        entity_id, rows = ranked[0]
        key = rows[0][2]
        best = min(distance(m[3], m[2]) for m in rows)
        seen_engines = {m[4] for m in rows}
        rival_close = any(min(distance(m[3], m[2]) for m in rival_rows) <= best + 1
                          for rival_id, rival_rows in groups.items() if rival_id != entity_id)
        blocked = False
        if existing:
            old = normalize(existing)
            blocked = old != key and (not any(normalize(m[3]) == old for m in rows)
                                      or distance(old, key) > 1)
        if not rival_close and not blocked:
            other = [s for s in spans if s[1] not in seen_engines]
            for text, engine, _ in other:
                for variant in (scrub_variants(text) if scrub else [normalize(text)]):
                    if distance(variant, key) <= 2:
                        return rows[0][1], "one engine matched; other engine reading similar"
    if not viable:
        return existing, "no independently supported producer; unchanged"
    if len(viable) != 1:
        return existing, "ambiguous producer identities; unchanged"
    entity_id, winner = next(iter(viable.items()))
    best = min(distance(m[3], m[2]) for m in winner)
    if any(other != entity_id and min(distance(m[3], m[2]) for m in rows) <= best + 1
           for other, rows in groups.items()):
        return existing, "insufficient separation from competing producer; unchanged"
    entity = winner[0]
    if existing:
        old = normalize(existing)
        if old == entity[2]:
            return existing, "existing producer retained"
        if all(normalize(m[3]) != old for m in winner) or distance(old, entity[2]) > 1:
            return existing, "different existing producer protected"
    return entity[1], "primary producer/brand name supported by two engines; confirm manually"


def resolve_v0(index, existing, spans):
    return _resolve(index, existing, spans, scrub=False, similarity=False)


def resolve_v1(index, existing, spans):
    return _resolve(index, existing, spans, scrub=True, similarity=False)


def resolve_v2(index, existing, spans):
    return _resolve(index, existing, spans, scrub=True, similarity=True)


def main(argv):
    scored_path, raw_parts = argv[1], argv[2:]
    by_image = load_raw(raw_parts)
    index = Index(ASSET)
    with open(SAVED_V2) as handle:
        saved = json.load(handle)['predictions']

    lab_producer, v2_producer, expected = {}, {}, {}
    with open(scored_path, newline='', encoding='utf-8-sig') as handle:
        for row in csv.DictReader(handle):
            if row['row_type'] != 'field' or row['field'] != 'producer':
                continue
            if row['pipeline'] == LAB_PIPELINE:
                lab_producer[row['image']] = row['recognized']
            elif row['pipeline'].startswith('Saved Production v2.0'):
                v2_producer[row['image']] = row['recognized']
            expected[row['image']] = (row['reference_status'], row['expected'])

    spans = {image: spans_for(rows) for image, rows in by_image.items()}

    def same(a, b):
        return normalize(a).replace(" ", "") == normalize(b).replace(" ", "")

    def outcome(value, ref_status, ref_value):
        if ref_status == 'expected_blank':
            return 'correct_blank' if value == '' else 'unsupported_fill'
        if ref_status == 'unresolved':
            return 'unresolved'
        return ('correct_value' if same(value, ref_value)
                else ('missing_value' if value == '' else 'wrong_value'))

    for name, fn in [("V0 current", resolve_v0), ("V1 + scrub", resolve_v1),
                     ("V2 + scrub + similarity", resolve_v2)]:
        decisions = collections.Counter()
        moved = collections.Counter()
        for image in by_image:
            before = lab_producer.get(image, '')
            after, reason = fn(index, v2_producer.get(image, ''), spans[image])
            decisions[reason.split(';')[0]] += 1
            if same(before, after):
                continue
            ref_status, ref_value = expected.get(image, ('unresolved', ''))
            moved[outcome(before, ref_status, ref_value) + ' -> '
                  + outcome(after, ref_status, ref_value)] += 1
        print("\n%s" % name)
        print("    decisions : %s" % dict(sorted(decisions.items())))
        print("    moved     : %s" % (dict(sorted(moved.items())) or '{}'))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
