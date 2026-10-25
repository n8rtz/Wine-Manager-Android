#!/usr/bin/env python3
"""Explicit private-test APK bundle. Never fetches sources or alters frozen references.
Supply the pinned source archive; Pillow 13 reproduces the previously evaluated JPEGs.
Image rights remain unresolved: this bundle is debug-only, not public/release distribution.
"""
import argparse,csv,hashlib,io,json,sys,zipfile
from pathlib import Path
from PIL import Image,ImageOps
sys.path.insert(0,str(Path(__file__).resolve().parent))
from rescore_expected_blanks import disposition
from score_public_corpus import normalize
ROOT=Path(__file__).resolve().parents[2]
REF=ROOT/'tools/evaluation/public-corpus-reference-v1/reference.json'
ADJUDICATIONS=ROOT/'app/src/debug/assets/phone-corpus-v1/reference-adjudications-v1.json'
DEST=ROOT/'app/src/debug/assets/phone-corpus-v1'
REV='eb2c14ba4f9a1b6efaa476696439754a9abde8ce'
def bundle(archive):
    assert hashlib.sha256(REF.read_bytes()).hexdigest()==(REF.parent/'reference.sha256').read_text().split()[0]
    refs=json.loads(REF.read_text())['images'];assert len(refs)==168
    overrides=json.loads(ADJUDICATIONS.read_text())['adjudications']
    overrides_by_key={(x['image'],x['field']):x for x in overrides}
    DEST.mkdir(parents=True,exist_ok=True);(DEST/'images').mkdir(exist_ok=True)
    images=[];targets=[]
    with zipfile.ZipFile(archive) as z:
        prefix=f'wine_label_reader_toolkit-{REV}/'
        for item in refs:
            raw=z.read(prefix+item['source_path'])
            assert hashlib.sha1(f'blob {len(raw)}\0'.encode()+raw).hexdigest()==Path(item['image']).stem
            im=ImageOps.exif_transpose(Image.open(io.BytesIO(raw))).convert('RGB');im.thumbnail((1600,1600),Image.Resampling.LANCZOS)
            out=io.BytesIO();im.save(out,format='JPEG',quality=88,optimize=True);data=out.getvalue()
            assert hashlib.sha256(data).hexdigest()==item['image_sha256'],f"Byte mismatch: {item['image']} — do not change reference hashes"
            (DEST/'images'/item['image']).write_bytes(data)
            images.append(dict(image=item['image'],sha256=item['image_sha256']))
            fields={}
            for f,source in item['fields'].items():
                override=overrides_by_key.get((item['image'],f))
                if override:
                    assert override['review_number']==item['review_number']
                    assert source['value']==override['imported_target']
                    assert [normalize(s) for s in source['accepted']]==override['imported_accepted']
                    fields[f]=dict(status=override['status'],accepted=override['accepted'],target=override['target'])
                else:
                    fields[f]=dict(status=disposition(item,f)[0],accepted=[normalize(s) for s in source['accepted']],target=source['value'] or '')
            targets.append(dict(image=item['image'],review_number=item['review_number'],fields=fields))
    assert set(overrides_by_key)=={(x['image'],x['field']) for x in overrides}
    baseline={}
    with (ROOT/'tools/evaluation/blank-aware-rescore-v1/paired-fields.csv').open(encoding='utf-8-sig') as f:
        for r in csv.DictReader(f):baseline.setdefault(r['image'],{})[r['field']]=r['baseline_output']
    (DEST/'inputs.json').write_text(json.dumps(dict(version=1,images=images),indent=2)+'\n')
    (DEST/'scoring.json').write_text(json.dumps(dict(reference_sha256=hashlib.sha256(REF.read_bytes()).hexdigest(),note='Frozen development references plus provisional blank overlay; eight partly unresolved forms. Not held-out accuracy.',images=targets,baseline=baseline),ensure_ascii=False,indent=2)+'\n')
    manifest=dict(count=len(images),bytes=sum((DEST/'images'/x['image']).stat().st_size for x in images),revision=REV,source_repo='AntoninLeroy/wine_label_reader_toolkit',rights='No declared image license; explicitly requested private test APK inclusion only; do not redistribute',inputs_sha256=hashlib.sha256((DEST/'inputs.json').read_bytes()).hexdigest(),scoring_sha256=hashlib.sha256((DEST/'scoring.json').read_bytes()).hexdigest())
    (DEST/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest,indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('archive',type=Path);a=p.parse_args();bundle(a.archive)
