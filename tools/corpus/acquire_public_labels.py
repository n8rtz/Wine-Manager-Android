#!/usr/bin/env python3
"""Acquire pinned public candidate photos OUTSIDE the repository. No expected fields inferred.
Requires Pillow and authenticated gh; image rights remain unresolved for this source.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
from pathlib import Path
import subprocess
from PIL import Image, ImageOps

REPO = 'AntoninLeroy/wine_label_reader_toolkit'
REVISION = 'eb2c14ba4f9a1b6efaa476696439754a9abde8ce'
ROOT = Path(__file__).resolve().parents[2]

def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path]))

def blob(sha):
    obj = api(f'repos/{REPO}/git/blobs/{sha}')
    assert obj['encoding'] == 'base64'
    data = base64.b64decode(obj['content'])
    actual = hashlib.sha1(f'blob {len(data)}\0'.encode()+data).hexdigest()
    if actual != sha:
        raise ValueError('Git blob checksum mismatch')
    return data

def dhash(image):
    im = image.convert('L').resize((9, 8))
    p = [im.getpixel((x,y)) for y in range(8) for x in range(9)]
    return f"{sum((p[y*9+x] > p[y*9+x+1]) << (y*8+x) for y in range(8) for x in range(8)):016x}"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--private-review-unlicensed', action='store_true', help='Explicitly acknowledge unresolved image rights; no redistribution/app inclusion')
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents:
        parser.error('Images must be outside the project repository')
    if not args.private_review_unlicensed:
        parser.error('Source has no declared license. Explicit private-review acknowledgement required.')
    output.mkdir(parents=True, exist_ok=True)
    (output/'images').mkdir(exist_ok=True)
    tree = api(f'repos/{REPO}/git/trees/{REVISION}?recursive=1')
    (output/'source-tree.json').write_text(json.dumps(tree, indent=2)+'\n')
    entries = [x for x in tree['tree'] if x['type']=='blob' and x['path'].lower().endswith(('.jpg','.jpeg','.png')) and
               x['path'].rsplit('/',1)[0] in ('img/train_and_valid/X','img/to_read')]
    entries.sort(key=lambda x: x['path'])
    def acquire(entry):
        sha = entry['sha']
        record_id = sha+'-'+hashlib.sha256(entry['path'].encode()).hexdigest()[:8]
        dest = output/'images'/f'{sha}.jpg'
        metadata = output/'images'/f'{sha}.json'
        if metadata.exists() and dest.exists():
            old = json.loads(metadata.read_text())
            if hashlib.sha256(dest.read_bytes()).hexdigest()==old['image_sha256']:
                return dict(old,id=record_id,source_path=entry['path'],
                    source_url=f"https://github.com/{REPO}/blob/{REVISION}/{entry['path']}")
        data = blob(sha)
        im = ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert('RGB')
        width, height = im.size
        im.thumbnail((1600,1600), Image.Resampling.LANCZOS)
        im.save(dest, quality=88, optimize=True) # no EXIF, no originals retained
        row = dict(id=record_id, source_repo=REPO, source_revision=REVISION, source_path=entry['path'],
            source_url=f"https://github.com/{REPO}/blob/{REVISION}/{entry['path']}",
            source_blob_sha1=sha, source_sha256=hashlib.sha256(data).hexdigest(), original_dimensions=[width,height],
            file=f'images/{sha}.jpg', dimensions=list(im.size), image_sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),
            dhash=dhash(im), transform='EXIF transpose; RGB; max edge 1600, no upscaling; JPEG quality 88; EXIF removed',
            license_status='no_license_declared; private source review only; not cleared for distribution',
            annotation_status='unreviewed', bottle_group=None, split='unassigned',
            fields={k:None for k in ['producer','wine_name','country','region','subregion','vintage']})
        metadata.write_text(json.dumps(row, ensure_ascii=False, indent=2)+'\n')
        return row
    with ThreadPoolExecutor(max_workers=4) as pool:
        rows = list(pool.map(acquire, entries))
    (output/'manifest.json').write_text(json.dumps(dict(schema_version=1, source=REPO, revision=REVISION,
        warning='Public availability is not a redistribution license. No OCR/field ground truth supplied. Masks and copied X_train/X_valid are excluded.',
        records=rows), ensure_ascii=False, indent=2)+'\n')
    # Exact-byte duplicates are measurable; perceptual pairs are REVIEW CANDIDATES, not proven same wines.
    exact={}
    for r in rows: exact.setdefault(r['source_sha256'],[]).append(r['id'])
    near=[]
    for i,a in enumerate(rows):
        for b in rows[i+1:]:
            distance=(int(a['dhash'],16)^int(b['dhash'],16)).bit_count()
            if distance<=6: near.append(dict(a=a['id'],b=b['id'],distance=distance))
    report=dict(candidate_photos=len(rows),exact_unique_images=len(exact),
        exact_duplicate_groups=[v for v in exact.values() if len(v)>1],near_duplicate_review_pairs=near,
        bytes=sum((output/r['file']).stat().st_size for r in rows),
        small_originals=sum(min(r['original_dimensions'])<400 for r in rows),
        annotated=0,reviewed_bottle_groups=0,held_out_ready=False,rights_cleared=False)
    (output/'audit.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('near_duplicate_review_pairs','exact_duplicate_groups')},indent=2))
    print('Possible near-duplicate pairs:',len(near))

if __name__=='__main__': main()
