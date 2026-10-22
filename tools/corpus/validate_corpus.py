#!/usr/bin/env python3
"""Validate acquisition integrity and reject premature evaluation/holdout claims. No downloads."""
import argparse
import hashlib
import json
from pathlib import Path

FIELDS={'producer','wine_name','country','region','subregion','vintage'}

def validate(folder, require_evaluation_ready=False):
    folder=Path(folder).resolve()
    manifest=json.loads((folder/'manifest.json').read_text())
    seen=set(); hashes={}; groups={}; reviewed=0
    for row in manifest['records']:
        if row['id'] in seen: raise ValueError('Duplicate record id')
        seen.add(row['id'])
        image=(folder/row['file']).resolve()
        if folder not in image.parents: raise ValueError('Image path escapes corpus')
        if hashlib.sha256(image.read_bytes()).hexdigest()!=row['image_sha256']:
            raise ValueError(f"Image hash mismatch: {row['id']}")
        if set(row['fields'])!=FIELDS: raise ValueError('Six explicit fields required')
        if any(v is not None and not isinstance(v,str) for v in row['fields'].values()):
            raise ValueError('Unknown fields must be null, not guessed data')
        split=row['split']
        if split not in ('unassigned','development','validation','holdout'): raise ValueError('Unknown split')
        if split!='unassigned':
            for key,store in [(row['source_sha256'],hashes),(row['bottle_group'],groups)]:
                if not key: raise ValueError('Bottle group required before splitting')
                if key in store and store[key]!=split: raise ValueError('Same bottle/image leaks across splits')
                store[key]=split
        if row['annotation_status']=='reviewed': reviewed+=1
        if require_evaluation_ready:
            if row['license_status']!='cleared': raise ValueError('Source rights not cleared')
            if row['annotation_status']!='reviewed' or not row['bottle_group'] or split=='unassigned':
                raise ValueError('Evaluation needs reviewed annotations and bottle-level split')
    return dict(records=len(seen),reviewed=reviewed,split_bottle_groups=len(groups))

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('folder',type=Path)
    p.add_argument('--require-evaluation-ready',action='store_true')
    a=p.parse_args()
    print(json.dumps(validate(a.folder,a.require_evaluation_ready),indent=2))
