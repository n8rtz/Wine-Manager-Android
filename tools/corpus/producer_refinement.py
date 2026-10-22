#!/usr/bin/env python3
"""Second producer experiment: protect observed extents; audit generic headings and brand roles."""
import argparse
from pathlib import Path
from producer_iteration import prepare,collect as collect_shared
VARIANTS=['producer-refined-2','producer-refined-1','producer-title-2','producer-title-1']
def collect(root,out,sha):return collect_shared(root,out,sha,variants=VARIANTS)
if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('prepare');q.add_argument('path',type=Path)
    q=sub.add_parser('collect');q.add_argument('root',type=Path);q.add_argument('output',type=Path);q.add_argument('--sha',required=True)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.path)
    else:collect(a.root,a.output,a.sha)
