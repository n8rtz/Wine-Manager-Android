#!/usr/bin/env python3
"""Deterministic lexical benchmark; synthetic OCR corruption, not real-photo accuracy."""
import argparse,collections,json,statistics,time
from pathlib import Path
from build_sqlite import ROOT,DEFAULT_SOURCE,normalize,digest,DEFAULT_DB
from gazetteer_resolver import Resolver
STANDARD=[('Pauilac',36),('Pauillca',36),('Barolo Risrva',578),('Cotes du Rhone',300),('St Julien',37),
 ('Chatau Margux',None),('Château Margaux',None),('13.5% ALC BY VOL',None),('CONTAINS SULFITES',None),
 ('750ml',None),('Government warning',None),('Bottled in Bordeaux',None),('Merlot',None),
 ('Prosecco',None),('Jumilla',None),('Snake River Valley',None),('Napa Valley',2024),('Oakville',2122),('California',2018),
 ('Appellation Pauilac Controlee',36),('" OR * NOT (',None),('ab',None),('',None)]
def cases():
    result=[dict(query=q,expected_id=e,kind='standard' if e else 'negative') for q,e in STANDARD]
    rows=json.loads(DEFAULT_SOURCE.read_text())['rows'];counts=collections.Counter(normalize(r['appellation_name']) for r in rows)
    for r in rows:
        n=normalize(r['appellation_name'])
        if r['id']%13!=0 or not 6<=len(n)<=60 or counts[n]!=1:continue
        positions=[i for i,c in enumerate(n) if c.isalpha()];i=positions[len(positions)//2]
        result.append(dict(query=n[:i]+n[i+1:],expected_id=r['id'],kind='synthetic_drop',canonical=r['appellation_name']))
        if i+1<len(n) and n[i+1].isalpha() and n[i]!=n[i+1]:
            result.append(dict(query=n[:i]+n[i+1]+n[i]+n[i+2:],expected_id=r['id'],kind='synthetic_swap',canonical=r['appellation_name']))
    return result
def benchmark(output):
    resolver=Resolver();results=[];start=time.monotonic()
    try:
        for c in cases():
            r=resolver.resolve(c['query']);actual=(r['matched_entity'] or {}).get('id')
            results.append(dict(**c,actual_id=actual,correct=actual==c['expected_id'],resolution=r))
    finally:resolver.close()
    positive=[r for r in results if r['expected_id'] is not None];negative=[r for r in results if r['expected_id'] is None]
    scores=[r['resolution']['confidence_score'] for r in results if r['resolution']['confidence_score'] is not None]
    ranks=[r['resolution']['bm25_rank'] for r in results if r['resolution']['bm25_rank'] is not None]
    standard=[r for r in positive if r['kind']=='standard'];synthetic=[r for r in positive if r['kind'].startswith('synthetic_')]
    summary=dict(database_sha256=digest(DEFAULT_DB),cases=len(results),positive_cases=len(positive),top1_recall=sum(r['correct'] for r in positive)/len(positive),
      standard_recall=sum(r['correct'] for r in standard)/len(standard),synthetic_cases=len(synthetic),synthetic_recall=sum(r['correct'] for r in synthetic)/len(synthetic),
      negative_cases=len(negative),false_positives=sum(not r['correct'] for r in negative),elapsed_seconds=time.monotonic()-start,
      accepted_similarity=dict(min=min(scores),median=statistics.median(scores),max=max(scores)),accepted_bm25=dict(min=min(ranks),median=statistics.median(ranks),max=max(ranks)),
      note='Synthetic lexical development benchmark, not real OCR accuracy or calibrated confidence. Château Margaux is not an appellation and is rejected. Ambiguous multi-region identities abstain.')
    summary['passes_lexical_target']=summary['top1_recall']>=.9 and summary['false_positives']==0
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    (output/'scores.json').write_text(json.dumps(summary,indent=2)+'\n');(output/'cases.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
    # Kotlin device test compares actual decisions against this host oracle, in addition to accuracy scoring.
    fixtures=[dict(query=r['query'],expected_id=r['expected_id'],host_id=r['actual_id'],host_reason=r['resolution']['reason']) for r in results]
    (output/'device-input.json').write_text(json.dumps(fixtures,ensure_ascii=False,indent=2)+'\n')
    (output/'report.md').write_text(f"# Trigram resolver lexical benchmark\n\n- Positive top-1: {summary['top1_recall']:.1%} ({len(positive)} cases).\n- Standard positives: {summary['standard_recall']:.1%}.\n- Synthetic dropped/swapped-character cases: {len(synthetic)}, recall {summary['synthetic_recall']:.1%}.\n- Negative false matches: {summary['false_positives']}/{len(negative)}.\n- Acceptance: {summary['passes_lexical_target']}.\n- Similarity distribution: {summary['accepted_similarity']}.\n- BM25 distribution: {summary['accepted_bm25']}.\n\n{summary['note']}\n\nThresholds: at most 0/1/2 edits for names of length <5/<9/otherwise, similarity ≥0.82, runner-up margin ≥0.06. BM25 shortlists 64 records, followed by bounded adjacent-transposition/edit reranking. No universal BM25 confidence cutoff.\n\nAndroid execution and actual saved-OCR/full-form evaluation are separate, not established by this host benchmark.\n")
    print(json.dumps(summary,indent=2));return summary
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=ROOT/'tools/evaluation/gazetteer-trigram-v1');args=p.parse_args();benchmark(args.output)
