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


def read_many(paths, scores_only=False):
    paths = [Path(p) for p in paths]
    documents = []
    legacy = []
    for path in paths:
        if path.suffix.lower() == '.json':
            documents.append((path, json.loads(path.read_text(encoding='utf-8'))))
        else:
            legacy.append(path)
    supplied = {hashlib.sha256(path.read_bytes()).hexdigest(): path for path, _ in documents}
    packages = {}
    for path, document in documents:
        if document.get('version') != 1:
            raise ValueError('Unsupported JSON suite version')
        package = packages.setdefault(document['package_id'], {'parts': {}, 'manifest': None})
        if document.get('format') == 'wine-lab-suite-json-manifest':
            package['manifest'] = document
            for entry in document['files']:
                if Path(entry['name']).name != entry['name']:
                    raise ValueError('Unsafe manifest filename')
                file = path.parent / entry['name']
                if not file.is_file():
                    file = supplied.get(entry['sha256'], file)
                if not file.is_file() and file.suffix == '.csv':
                    continue  # scores convenience CSV is optional; JSON events contain the complete data
                if not file.is_file():
                    raise ValueError(f"Missing package file: {entry['name']}; provide all saved files together")
                if file.stat().st_size != entry['bytes'] or hashlib.sha256(file.read_bytes()).hexdigest() != entry['sha256']:
                    raise ValueError(f"Integrity check failed: {entry['name']}")
                if file.suffix == '.json':
                    part = json.loads(file.read_text(encoding='utf-8'))
                    package['parts'][part['part']] = part
        elif document.get('format') == 'wine-lab-suite-json':
            package['parts'][document['part']] = document
        else:
            raise ValueError('Not a supported suite JSON file')
    for package_id, package in packages.items():
        parts = package['parts']
        if package['manifest'] is None:
            raise ValueError('Include the JSON manifest to verify that no final parts are missing')
        expected = package['manifest']['parts']
        if set(parts) != set(range(1, expected+1)):
            raise ValueError('Missing numbered JSON parts')
        tables = {code: {} for code in range(5)}
        events = []
        for number in sorted(parts):
            part = parts[number]
            if part['package_id'] != package_id:
                raise ValueError('Mixed JSON packages')
            for code, record in part['records']:
                if code == 5:
                    events.append(record)
                elif code in tables:
                    if record['id'] in tables[code]:
                        raise ValueError('Duplicate dictionary ID')
                    tables[code][record['id']] = record['v']
                else:
                    raise ValueError('Unknown table code')
        for event in events:
            row = tables[0].get(event['r'], {}) | tables[1].get(event['i'], {}) | tables[4][event['p']]
            if scores_only and row.get('row_type') not in {'run_summary', 'parameter_summary', 'wine_field'}:
                continue
            if 'd' in event:
                row['diagnostic_json'] = encode(tables[4][event['d']] | tables[3].get(event.get('m'), {}) |
                                                tables[2].get(event.get('c'), {}) | event.get('o', {}))
            if scores_only:
                for key in SCORE_COLUMNS:
                    if key not in {'r', 'i'}:
                        row.setdefault(key, '')
            yield row
    for path in legacy:
        # A package's scores CSV is only a convenience view, not another set of run results.
        if packages and path.name.endswith('-scores.csv'):
            continue
        yield from read_rows(path, scores_only=scores_only)


def write_json_package(source_zip, prefix, max_bytes=20_000_000):
    prefix = Path(prefix)
    package_id = __import__('uuid').uuid4().hex
    names = ['runs.jsonl', 'images.jsonl', 'candidates.jsonl', 'common.jsonl', 'objects.jsonl', 'events.jsonl']
    outputs, records, size = [], [], 0

    def flush():
        nonlocal records, size
        path = prefix.with_name(prefix.stem + f'-part-{len(outputs)+1}.json')
        path.write_text(encode(dict(format='wine-lab-suite-json', version=1, package_id=package_id,
                                   part=len(outputs)+1, records=records)), encoding='utf-8')
        outputs.append(path); records, size = [], 0

    with zipfile.ZipFile(source_zip) as archive:
        original = verify(archive)
        for code, name in enumerate(names):
            if name not in archive.namelist():
                continue
            with archive.open(name) as source:
                for line in source:
                    item = [code, json.loads(line)]
                    length = len(encode(item).encode('utf-8')) + 1
                    if length+512 > max_bytes:
                        raise ValueError('Single record exceeds JSON part limit')
                    if records and size+length+512 > max_bytes:
                        flush()
                    records.append(item);size += length
        if records:
            flush()
        scores = prefix.with_name(prefix.stem+'-scores.csv')
        scores.write_bytes(archive.read('scores.csv'))
    manifest = prefix.with_name(prefix.stem+'-manifest.json')
    manifest.write_text(encode(dict(format='wine-lab-suite-json-manifest', version=1, package_id=package_id,
        parts=len(outputs), tables=names, dictionary_counts=original['dictionary_counts'],
        files=[dict(name=p.name, bytes=p.stat().st_size, sha256=hashlib.sha256(p.read_bytes()).hexdigest())
               for p in outputs+[scores]])), encoding='utf-8')
    return [manifest, scores]+outputs


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
    json_pack = sub.add_parser('pack-json');json_pack.add_argument('csv', nargs='+');json_pack.add_argument('--output', required=True)
    unpack = sub.add_parser('unpack');unpack.add_argument('zip');unpack.add_argument('--output', required=True)
    check = sub.add_parser('verify');check.add_argument('zip')
    args = parser.parse_args()
    if args.command == 'pack':
        pack_csv(args.csv, args.output)
    elif args.command == 'pack-json':
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary)/'intermediate.zip'
            pack_csv(args.csv, archive)
            for path in write_json_package(archive, args.output):
                print(path)
    elif args.command == 'unpack':
        unpack_csv(args.zip, args.output)
    else:
        with zipfile.ZipFile(args.zip) as archive:
            print(json.dumps(verify(archive), indent=2))


if __name__ == '__main__':
    main()
