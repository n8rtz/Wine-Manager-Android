"""Lossless reader/converter for Wine Lab normalized ZIP archives (format version 1)."""
import csv
import hashlib
import io
import json
import tempfile
import zipfile

# Detailed legacy diagnostic cells can exceed csv's small default field limit.
csv.field_size_limit(64 * 1024 * 1024)
from pathlib import Path

RUN_KEYS = {'recent_rank', 'run_id', 'date', 'time', 'build_number', 'build_commit', 'lab_modules', 'corpus_key'}
IMAGE_KEYS = {'image', 'image_sha256'}
COMMON_KEYS = {'candidate', 'post_processing_version', 'spatial_source_count', 'spatial_reading_count',
               'spatial_filter_dropped', 'spatial_merged_groups'}
CANDIDATE_KEYS = {'phrase_id', 'candidate_values', 'source_observations', 'base_rank'}
SCORE_COLUMNS = ['row_type', 'r', 'i', 'review_number', 'source_filename', 'ocr_ref', 'field', 'recognized', 'expected', 'reference_status', 'outcome',
                 'execution_status', 'correct', 'incorrect', 'attempted', 'eligible', 'complete', 'failed', 'total_correct']


def encode(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':'))


def verify(archive):
    manifest = json.loads(archive.read('manifest.json'))
    if manifest.get('format') != 'wine-lab-suite' or manifest.get('version') != 1:
        raise ValueError('Unsupported suite archive format')
    for entry in manifest['files']:
        digest, count = hashlib.sha256(), 0
        with archive.open(entry['name']) as source:
            while block := source.read(65536):
                digest.update(block)
                count += len(block)
        if count != entry['bytes'] or digest.hexdigest() != entry['sha256']:
            raise ValueError(f"Integrity check failed: {entry['name']}")
    return manifest


def table(archive, name):
    if name not in archive.namelist():
        return {}
    with archive.open(name) as source:
        return {row['id']: row['v'] for row in (json.loads(line) for line in source)}


def read_rows(path, scores_only=False):
    if not zipfile.is_zipfile(path):
        with open(path, encoding='utf-8-sig', newline='') as source:
            yield from csv.DictReader(source)
        return
    with zipfile.ZipFile(path) as archive:
        verify(archive)
        runs, images = table(archive, 'runs.jsonl'), table(archive, 'images.jsonl')
        if scores_only:
            with archive.open('scores.csv') as source:
                for row in csv.DictReader(io.TextIOWrapper(source, encoding='utf-8', newline='')):
                    r, i = int(row.pop('r')), int(row.pop('i'))
                    yield runs.get(r, {}) | images.get(i, {}) | row
            return
        objects = table(archive, 'objects.jsonl')
        common, candidates = table(archive, 'common.jsonl'), table(archive, 'candidates.jsonl')
        with archive.open('events.jsonl') as source:
            for line in source:
                event = json.loads(line)
                row = runs.get(event['r'], {}) | images.get(event['i'], {}) | objects[event['p']]
                if 'd' in event:
                    diagnostic = (objects[event['d']] | common.get(event.get('m'), {}) |
                                  candidates.get(event.get('c'), {}) | event.get('o', {}))
                    row['diagnostic_json'] = encode(diagnostic)
                yield row


def pack_csv(paths, target):
    """Convert existing big CSV exports too; same dictionary schema as the Android writer."""
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        streams, ids, counts = {}, {}, {}

        def stream(name):
            if name not in streams:
                streams[name] = (root / name).open('w', encoding='utf-8', newline='')
            return streams[name]

        def line(name, value):
            stream(name).write(encode(value) + '\n')
            counts[name] = counts.get(name, 0) + 1

        def intern(name, value):
            digest = hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                              separators=(',', ':')).encode()).hexdigest()
            registry = ids.setdefault(name, {})
            if digest not in registry:
                registry[digest] = len(registry) + 1
                line(name, {'id': registry[digest], 'v': value})
            return registry[digest]

        scores = csv.DictWriter(stream('scores.csv'), fieldnames=SCORE_COLUMNS, lineterminator='\n')
        scores.writeheader()
        try:
            for path in paths:
                for row in read_rows(path):
                    # Empty CSV cells are absent optional fields in the source Android row maps.
                    payload = {k: v for k, v in row.items() if v != ''}
                    run = {k: payload.pop(k) for k in RUN_KEYS if k in payload}
                    image = {k: payload.pop(k) for k in IMAGE_KEYS if k in payload}
                    event = {'r': intern('runs.jsonl', run) if run else 0,
                             'i': intern('images.jsonl', image) if image else 0}
                    if encoded := payload.pop('diagnostic_json', ''):
                        diagnostic = json.loads(encoded)
                        for keys, name, code in [(COMMON_KEYS, 'common.jsonl', 'm'),
                                                 (CANDIDATE_KEYS, 'candidates.jsonl', 'c')]:
                            content = {k: diagnostic.pop(k) for k in keys if k in diagnostic}
                            if content:
                                event[code] = intern(name, content)
                        occurrence = {k: diagnostic.pop(k) for k in ['sequence', 'event_at_ms'] if k in diagnostic}
                        if occurrence:
                            event['o'] = occurrence
                        event['d'] = intern('objects.jsonl', diagnostic)
                    event['p'] = intern('objects.jsonl', payload)
                    line('events.jsonl', event)
                    if row['row_type'] in {'run_summary', 'parameter_summary', 'wine_field'}:
                        scores.writerow({k: event[k] if k in {'r', 'i'} else row.get(k, '') for k in SCORE_COLUMNS})
                        counts['scores.csv'] = counts.get('scores.csv', 0) + 1
        finally:
            for output in streams.values():
                output.close()
        files = [{'name': p.name, 'bytes': p.stat().st_size, 'records': counts.get(p.name, 0),
                  'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(root.iterdir())]
        (root / 'manifest.json').write_text(encode({'format': 'wine-lab-suite', 'version': 1,
            'files': files, 'dictionary_counts': {k: len(v) for k, v in ids.items()}}), encoding='utf-8')
        with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for p in sorted(root.iterdir()):
                archive.write(p, p.name)


def unpack_csv(path, target):
    # Discover columns without retaining all diagnostic rows in RAM.
    columns = dict.fromkeys(key for row in read_rows(path) for key in row)
    with open(target, 'w', encoding='utf-8', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=list(columns), lineterminator='\n')
        writer.writeheader()
        writer.writerows(read_rows(path))


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    pack = sub.add_parser('pack');pack.add_argument('csv', nargs='+');pack.add_argument('--output', required=True)
    unpack = sub.add_parser('unpack');unpack.add_argument('zip');unpack.add_argument('--output', required=True)
    check = sub.add_parser('verify');check.add_argument('zip')
    args = parser.parse_args()
    if args.command == 'pack':
        pack_csv(args.csv, args.output)
    elif args.command == 'unpack':
        unpack_csv(args.zip, args.output)
    else:
        with zipfile.ZipFile(args.zip) as archive:
            print(json.dumps(verify(archive), indent=2))


if __name__ == '__main__':
    main()
