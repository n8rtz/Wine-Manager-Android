#!/usr/bin/env python3
"""Host-side replay of the Lab producer resolver.

This is the offline half of the producer/wine evidence check. It re-implements
`ProducerResolver` and the FTS lookup in `ProducerGazetteer` exactly (normalize,
`eligible`, exact-key lookup, trigram `OR` match, bm25 order, the edit-distance
gate, the two-engine/disjoint-observation rule, the separation rule and the
existing-producer protection) and runs it over the readings that a Lab run
actually recorded for each image.

It exists because the matcher cannot be exercised on the device without a
cellar install, and because the interesting question is not "does the code
run" but "what would the code have decided on the evidence that was there".

Inputs (both produced by a Lab run):
  scored   the Phone Lab scoreboard CSV (`wine-<n>-lab-<build>.csv`)
  raw      the Phone Lab raw trace CSV (one or more `-part-*.csv` parts)

Both are read straight from the export; nothing here needs the app.

Usage (from the repository root):
  python3 tools/host-checks/producer_resolver_replay.py <scored.csv> <raw.csv> [...]
"""
import csv
import collections
import json
import re
import sqlite3
import sys
import unicodedata

ASSET = "app/src/main/assets/producer-gazetteer-v1/producers.sqlite"
SAVED_V2 = "app/src/debug/assets/phone-corpus-v1/saved-production-v2.json"
LAB_PIPELINE = "lab-linked-wine-v1"
V2_PIPELINE = "Saved Production v2.0 · same attempted photos"
NOISE = re.compile(
    r"\b(?:imported|importer|imports|distributed|distributor|bottled|bottler|embouteille|"
    r"mis en bouteille|produced by|product of|produit de|appellation|controlee|protegee|"
    r"alcohol|alc|vol|contains|sulfites|sulphites|government|warning|llc|inc|corp|ltd|"
    r"www|http)\b")
GENERIC = {"chateau", "domaine", "domaines", "estate", "winery", "wine", "wines",
           "vineyard", "vineyards", "cellar", "cellars", "bodega", "bodegas", "maison",
           "de", "du", "des", "la", "le", "les", "the", "and", "et", "of", "family",
           "reserve", "reserva", "grand", "cru"}
MAX_SPANS = 128
MAX_HITS = 32
MAX_FUZZY_CANDIDATES = 200

# Rows in the primary producer export that cannot be a match target. Measured against
# the packaged asset: 431 keys carry more than one producer id (975 rows, 544 of them
# redundant), 12 keys are shorter than three characters, and two keys are generic label
# words. A duplicated key makes the resolver see several entities for one name, which
# reads as "ambiguous producer identities" and abstains.
GENERIC_PRODUCER_KEYS = ("chateau", "the")
MIN_KEY_LENGTH = 3


def clean_predicate():
    return (" AND length(p.key)>=%d AND p.key NOT IN (%s)"
            " AND p.id=(SELECT MIN(p2.id) FROM producers p2 WHERE p2.key=p.key)"
            % (MIN_KEY_LENGTH, ", ".join("'%s'" % k for k in GENERIC_PRODUCER_KEYS)))


def normalize(text):
    text = unicodedata.normalize("NFKD", text.lower().replace("\u2019", "'"))
    text = re.sub(r"[\u0300-\u036f]+", "", text)
    text = re.sub(r"[^\w]+", " ", text).strip()
    return re.sub(r"\s+", " ", text)


def eligible(text):
    if len(text) > 96 or any(c.isdigit() for c in text) or '%' in text or '\n' in text:
        return False
    q = normalize(text)
    words = q.split(' ')
    return (len(q) >= 8 and 2 <= len(words) <= 8 and not NOISE.search(q)
            and any(len(w) >= 4 and w not in GENERIC for w in words))


def distance(a, b, allow=2):
    if abs(len(a) - len(b)) > allow:
        return allow + 1
    previous = list(range(len(b) + 1))
    for i in range(len(a)):
        current = [i + 1] + [0] * len(b)
        for j in range(len(b)):
            current[j + 1] = min(current[j] + 1, previous[j + 1] + 1,
                                 previous[j] + (0 if a[i] == b[j] else 1))
        previous = current
    return previous[-1]


def passes(q, key, word_boundary_tolerant):
    """`ProducerResolver`'s edit gate."""
    d = distance(q, key)
    if d <= (2 if len(q) >= 20 else 1):
        return True
    if word_boundary_tolerant:
        # Rejected variant: treat a word boundary as noise and retry on the
        # space-stripped forms. Measured on the owner's export it moves one more
        # image to correct and one to a wrong value, so it is not adopted.
        return distance(q.replace(" ", ""), key.replace(" ", ""), 2) <= 2
    return False


class Index:
    """The packaged producer index, reached the way `ProducerGazetteer` reaches it."""

    def __init__(self, path, clean=False):
        self.db = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
        self.clean = clean
        self.predicate = clean_predicate() if clean else ""
        self.exact = {}
        rows = self.db.execute("SELECT key,name,id FROM producers").fetchall()
        if clean:
            lowest = {}
            for key, name, entity_id in rows:
                if len(key) < MIN_KEY_LENGTH or key in GENERIC_PRODUCER_KEYS:
                    continue
                if key not in lowest or entity_id < lowest[key]:
                    lowest[key] = entity_id
            names = {entity_id: name for _, name, entity_id in rows}
            self.exact = {key: (entity_id, names[entity_id])
                          for key, entity_id in lowest.items()}
        else:
            # Faithful to `SELECT id,name,key FROM producers WHERE key=? ORDER BY id LIMIT ?`:
            # a duplicated key returns one row per producer id.
            for key, name, entity_id in rows:
                self.exact.setdefault(key, []).append((entity_id, name))
        self._fts = {}

    def _matcher(self, q):
        trigrams = []
        seen = set()
        for i in range(len(q) - 2):
            t = q[i:i + 3]
            if any(c.isspace() for c in t) or t in seen:
                continue
            seen.add(t)
            trigrams.append('"%s"' % t)
            if len(trigrams) == 24:
                break
        return " OR ".join(trigrams) if trigrams else None

    def lookup(self, q, limit):
        hit = self.exact.get(q)
        if hit is not None:
            rows = hit if isinstance(hit, list) else [hit]
            return [(entity_id, name, q) for entity_id, name in rows][:limit]
        matcher = self._matcher(q)
        if matcher is None:
            return []
        sql = ("SELECT p.id,p.name,p.key FROM names JOIN producers p ON names.id=p.id "
               "WHERE names MATCH ?{pred} ORDER BY bm25(names),p.id LIMIT ?").format(
                   pred=self.predicate)
        if matcher not in self._fts:
            self._fts[matcher] = self.db.execute(sql, (matcher, limit)).fetchall()
        return self._fts[matcher]


def resolve(index, existing, spans, candidate_limit, word_boundary_tolerant, skip_on_saturation):
    """`ProducerResolver.resolve`, with the two knobs under test."""
    if len(spans) > MAX_SPANS:
        return existing, "span limit; abstained", []
    matches = []
    for text, engine, observation_ids in spans:
        if not engine or not observation_ids or not eligible(text):
            continue
        q = normalize(text)
        hits = index.lookup(q, candidate_limit)
        if skip_on_saturation and len(hits) > MAX_HITS:
            continue
        seen = set()
        for entity_id, name, key in hits:
            if entity_id in seen or len(key) > 96:
                continue
            seen.add(entity_id)
            if passes(q, key, word_boundary_tolerant):
                matches.append((entity_id, name, key, text, engine, observation_ids))
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
    if not viable:
        return existing, "no independently supported producer; unchanged", matches
    if len(viable) != 1:
        return existing, "ambiguous producer identities; unchanged", matches
    entity_id, winner = next(iter(viable.items()))
    best = min(distance(normalize(m[3]), m[2]) for m in winner)
    if any(other != entity_id and min(distance(normalize(m[3]), m[2]) for m in rows) <= best + 1
           for other, rows in groups.items()):
        return existing, "insufficient separation from competing producer; unchanged", matches
    entity = winner[0]
    if existing:
        old = normalize(existing)
        if old == entity[2]:
            return existing, "existing producer retained", matches
        if all(normalize(m[3]) != old for m in winner) or distance(old, entity[2]) > 1:
            return existing, "different existing producer protected", matches
    return entity[1], "primary producer/brand name supported by two engines; confirm manually", matches


def load_raw(parts):
    by_image = collections.defaultdict(list)
    for part in parts:
        with open(part, newline='', encoding='utf-8-sig') as handle:
            for row in csv.DictReader(handle):
                if row['row_type'] == 'trace':
                    by_image[row['image']].append(row)
    return by_image


def spans_for(rows):
    """`TextLabFormPreview.records` plus the merged phrases `TextLabSpatialPreview` adds.

    Recognition readings are the `original` list; ML Kit rows come first, then Paddle,
    and each reading owns one observation id. Merged phrases are the extra spans the
    spatial stage contributes, one per multi-source group.
    """
    readings = [(row['raw_text'], row['candidate'])
                for row in rows if row['stage'] == 'recognition' and row['raw_text']]
    merged = [(row['raw_text'], row['source_engine'])
              for row in rows if row['stage'] == 'spatial_phrase' and row['raw_text']]
    per_engine = collections.Counter(engine for _, engine in readings)
    base, running = {}, 0
    for engine in sorted(per_engine):
        base[engine] = running
        running += per_engine[engine]
    seen = collections.Counter()
    out = []
    for text, engine in readings:
        out.append((text, engine, {base[engine] + seen[engine]}))
        seen[engine] += 1
    for index, (text, engine) in enumerate(merged):
        out.append((text, engine, {running + index}))
    return out


def same(a, b):
    """The scoreboard's comparison: case-folded, word boundaries ignored."""
    return normalize(a).replace(" ", "") == normalize(b).replace(" ", "")


def outcome(value, ref_status, ref_value):
    if ref_status == 'expected_blank':
        return 'correct_blank' if value == '' else 'unsupported_fill'
    if ref_status == 'unresolved':
        return 'unresolved'
    return ('correct_value' if same(value, ref_value)
            else ('missing_value' if value == '' else 'wrong_value'))


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

    configs = [
        ("current  fetch LIMIT 33, drop the span when the window is full",
         dict(candidate_limit=MAX_HITS + 1, word_boundary_tolerant=False,
              skip_on_saturation=True)),
        ("fixed    fetch LIMIT 200, keep the span",
         dict(candidate_limit=MAX_FUZZY_CANDIDATES, word_boundary_tolerant=False,
              skip_on_saturation=False)),
    ]

    print("images in raw export        : %d" % len(by_image))
    print("Lab export producer outcomes: %s" % dict(sorted(collections.Counter(
        outcome(lab_producer.get(i, ''), *expected.get(i, ('unresolved', '')))
        for i in by_image).items())))

    for name, cfg in configs:
        moved = collections.Counter()
        for image in by_image:
            before = lab_producer.get(image, '')
            after, _, _ = resolve(index, v2_producer.get(image, ''), spans[image], **cfg)
            if same(before, after):
                continue
            ref_status, ref_value = expected.get(image, ('unresolved', ''))
            moved[outcome(before, ref_status, ref_value) + ' -> '
                  + outcome(after, ref_status, ref_value)] += 1
        print("\n%s\n    producer outcomes that move: %s"
              % (name, dict(sorted(moved.items())) or '{}'))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
