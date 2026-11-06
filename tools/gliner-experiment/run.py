#!/usr/bin/env python3
"""Frozen OCR, real INT4 inference, paired six-field evaluation; never trains on targets."""
import argparse
import base64
import gzip
import hashlib
import json
import math
import pathlib
import subprocess
import sys
import time
from collections import Counter

import numpy as np
import onnxruntime as ort

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/host-lab'))
from replay_gliner_decisions import FIELDS, LANES, enc, dec, norm, outcome

EVIDENCE = ROOT / 'tools/gliner-experiment/evidence-v10.json.gz'
REFERENCES = ROOT / 'tools/gliner-experiment/references-v10.json.gz'
SHA = {'gliner_small_int4.onnx': 'a4d97745ba14a2b5f3606b30fa46a4c9a78e7b78a5f8b6c9e9fb35078d7c4a6d',
       'tokenizer.json': '1731c431e5e851a4a73487b5bbfbef265b29ab64de190c402b4e6df9ce2cb0ab',
       'gliner_config.json': '74fa245ddcaf2ad5ec1c611d197b829a352ada033d14c797564a67ffe2902365'}
THREE = ('wine producer or winery name', 'wine name or cuvee', 'wine appellation, region or country')
SIX = ('wine producer or winery name', 'wine name or cuvee', 'wine appellation, region or country',
       'country where the wine was made', 'wine region', 'wine vintage year')
# Thresholds and prompts are fixed before scoring. The six-label variant preserves the existing
# three roles, and the new labels are evaluated separately; no geography/year policy is invented.
VARIANTS = {'control': ('three', .5, False), 'low_threshold': ('three', .35, False),
            'lane_overlap': ('three', .5, True), 'six_labels': ('six', .5, True)}


def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()


def nested_type(node, typ):
    if isinstance(node, dict):
        if node.get('type') == typ: return node
        for x in node.values():
            r = nested_type(x, typ)
            if r: return r
    if isinstance(node, list):
        for x in node:
            r = nested_type(x, typ)
            if r: return r
    return None


class Tokenizer:
    """Port of GlinerTokenizer's Unigram DP, NOT HF's general-purpose encoder."""
    def __init__(self, path):
        data = json.loads(path.read_text())
        model = data['model']
        if model['type'] != 'Unigram': raise ValueError('Experiment supports the pinned Unigram model only')
        self.vocab = {x[0]: (i, float(x[1])) for i, x in enumerate(model['vocab'])}
        self.added = {x['content']: x['id'] for x in data.get('added_tokens', [])}
        self.unk = model.get('unk_id', self.id('[UNK]') or self.id('<unk>') or 0)
        meta = nested_type(data.get('pre_tokenizer'), 'Metaspace')
        self.meta = meta.get('replacement', '▁') if meta else ''
        self.lower = bool(nested_type(data.get('normalizer'), 'Lowercase') or nested_type(data.get('pre_tokenizer'), 'Lowercase'))

    def id(self, piece):
        if piece in self.added: return self.added[piece]
        return self.vocab.get(piece, (None,))[0]

    def word(self, word):
        if not word: return [self.unk]
        if word in self.added: return [self.added[word]]
        if self.lower: word = word.lower()
        surface = self.meta + word.replace(' ', self.meta) if self.meta else word
        # Android loops over UTF-16 code units. Represent them explicitly for identical boundaries.
        u = surface.encode('utf-16-le', 'surrogatepass')
        chars = [u[i:i+2] for i in range(0, len(u), 2)]
        n = len(chars); best = [-math.inf]*(n+1); back = [None]*(n+1); best[0] = 0.
        for end in range(1, n+1):
            for start in range(end-1, max(-1,end-65), -1):
                if best[start] == -math.inf: continue
                piece = b''.join(chars[start:end]).decode('utf-16-le', 'surrogatepass')
                v = self.vocab.get(piece)
                if v is not None and best[start] + v[1] > best[end]:
                    best[end] = best[start] + v[1]; back[end] = (start, v[0])
        if back[n] is None: return [self.unk]
        ids=[]
        while n:
            if back[n] is None: return [self.unk]
            n, tok = back[n]; ids.append(tok)
        return ids[::-1]


def prepare_documents(rows, jar, java):
    lines=[]
    for row in rows:
        raw=';'.join(','.join([enc(r['engine']), enc(r['text']), str(r['score'])] + list(map(str,r['box']))) for r in row['raw_readings'])
        lines.append(str(row['review_number'])+'\t'+raw)
    p=subprocess.run([java, '-jar', str(jar)], input='\n'.join(lines)+'\n', text=True, capture_output=True, check=True)
    result={}
    for line in p.stdout.splitlines():
        number, values=line.split('\t')
        result[int(number)]=[tuple(map(dec, x.split(','))) for x in values.split(';') if x]
    if len(result)!=len(rows): raise ValueError('Document preparation returned a different row count')
    return result


def verify_tokenizer(documents, tokenizer, path, host, java):
    # Exercise every word and prompt that can enter this experiment using shipping Kotlin code.
    words=sorted({w[1] for doc in documents.values() for w in doc} | set(THREE) | set(SIX))
    cp=str(host/'tokenizer.jar')+':'+str(host/'json.jar')
    p=subprocess.run([java,'-cp',cp,'com.yourapp.wine.gliner.TokenizerParityKt',str(path)],
                     input='\n'.join(enc(w) for w in words)+'\n',text=True,capture_output=True,check=True)
    lines=p.stdout.splitlines()
    if len(lines)!=len(words): raise ValueError('Tokenizer parity probe returned an unexpected row count')
    mismatches=[w for w,line in zip(words,lines) if tokenizer.word(w)!=list(map(int,line.split(',')))]
    if mismatches: raise ValueError(f'Python tokenizer differs from Android for {len(mismatches)} words: {mismatches[:10]}')
    return len(words)


def make_feed(words, prompts, tokenizer, config, inputs):
    width=min(12,max(1,min(32,int(config.get('max_width',12)))))
    words=words[:min(320,max(32,min(1024,int(config.get('max_len',384)))))]
    if not words: raise ValueError('No usable words')
    ent=tokenizer.id(config.get('ent_token') or '<<'); sep=tokenizer.id(config.get('sep_token') or '>>')
    if ent is None or sep is None: raise ValueError('Missing added ENT/SEP prompt markers')
    prompt=[]
    for phrase in prompts: prompt += [ent] + tokenizer.word(phrase)
    prompt += [sep]
    ids=[]; mask=[]
    cls=tokenizer.id('[CLS]'); cls=cls if cls is not None else tokenizer.id('<s>')
    eos=tokenizer.id('[SEP]'); eos=eos if eos is not None else tokenizer.id('</s>')
    if cls is not None: ids.append(cls);mask.append(0)
    ids+=prompt;mask += [0]*len(prompt)
    for i, (_, normalized) in enumerate(words):
        pieces=tokenizer.word(normalized); ids+=pieces; mask += [i+1]+[0]*(len(pieces)-1)
    if eos is not None: ids.append(eos);mask.append(0)
    spans=np.array([[start,start+offset] for start in range(len(words)) for offset in range(width)])
    vals={'input_ids': [ids], 'attention_mask': [[1]*len(ids)], 'words_mask': [mask],
          'text_lengths': [[len(words)]], 'span_idx': [spans],
          'span_mask': [[int(e<len(words)) for _,e in spans]]}
    types={'tensor(int64)': np.int64, 'tensor(int32)': np.int32, 'tensor(bool)': np.bool_, 'tensor(float)': np.float32}
    feed={}
    for inp in inputs:
        if inp.name not in vals or inp.type not in types: raise ValueError(f'Unsupported graph input {inp.name}: {inp.type}')
        feed[inp.name]=np.asarray(vals[inp.name],dtype=types[inp.type])
    return words,width,feed


def decode_logits(logits, words, width, labels, threshold, per_lane):
    n=len(words); c=len(labels); a=np.asarray(logits); shape=a.shape
    # The deployed INT4 export uses [1,words,width,classes]. Fail closed on unexpected layouts.
    if shape != (1,n,width,c): raise ValueError(f'Unexpected graph output {shape}; expected {(1,n,width,c)}')
    candidates=[]
    for label in range(c):
        for start in range(n):
            for off in range(min(width,n-start)):
                logit=float(a[0,start,off,label])
                if not math.isfinite(logit): raise ValueError('Non-finite model logit')
                score=float(np.float32(1/(1+math.exp(-max(-80,min(80,logit))))))
                if score >= threshold: candidates.append((score,start,start+off,label))
    candidates.sort(key=lambda x:(-x[0],x[1],x[2]))
    used=[set() for _ in labels] if per_lane else [set()]
    result=[]
    for score,start,end,label in candidates:
        occupied=used[label if per_lane else 0]; indices=set(range(start,end+1))
        if occupied.intersection(indices): continue
        occupied.update(indices)
        result.append((label,score,' '.join(w[0] for w in words[start:end+1]).strip()))
    return result


def run_policy(rows, hint_map, jar, java):
    lines=[]
    for row in rows:
        fields=','.join(enc(row['base_fields'].get(f,'')) for f in FIELDS)
        hints=';'.join(f'{LANES[key][0]},{score},{enc(text)}' for key,score,text in hint_map[row['review_number']])
        readings=';'.join(','.join([enc(r['engine']),enc(r['text'])]+list(map(str,r['box']))) for r in row['decision_readings'])
        lines.append('\t'.join((fields,hints,readings,str(row['review_number']))))
    p=subprocess.run([java,'-jar',str(jar)],input='\n'.join(lines)+'\n',text=True,capture_output=True,check=True)
    result={}
    for line in p.stdout.splitlines():
        number, data, _=line.split('\t'); result[int(number)]=dict(zip(FIELDS,map(dec,data.split(','))))
    if len(result)!=len(rows): raise ValueError('Decision policy returned a different row count')
    return result


def score(rows, predictions, refs, control):
    counts=Counter(); fields={f:Counter() for f in FIELDS}; changes=[]
    for row in rows:
        if not row['eligible']: continue
        number=row['review_number']; target=refs[row['image']]; predicted=predictions[number]; baseline=control[number]
        ok=[]; old=[]
        for f in FIELDS:
            a=outcome(target[f],predicted.get(f,'')); b=outcome(target[f],baseline.get(f,''))
            if a=='unscored' or b=='unscored': raise ValueError(f'Eligible image {number} has unscored field {f}')
            good=a.startswith('correct_'); prior=b.startswith('correct_');ok.append(good);old.append(prior)
            fields[f]['correct']+=good; fields[f]['gain']+=good and not prior; fields[f]['loss']+=prior and not good
            fields[f][a]+=1
            if good != prior: changes.append({'image':row['image'],'number':number,'field':f,'control':baseline.get(f,''),'actual':predicted.get(f,''),'outcome':a,'change':'gain' if good else 'loss'})
        counts['eligible']+=1;counts['complete']+=all(ok);counts['complete_gain']+=all(ok) and not all(old);counts['complete_loss']+=all(old) and not all(ok)
    return {'labels':dict(counts), 'fields':{k:dict(v) for k,v in fields.items()}, 'changes':changes}


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--bundle',type=pathlib.Path,required=True);ap.add_argument('--host',type=pathlib.Path,required=True);ap.add_argument('--java',default='java');ap.add_argument('--output',type=pathlib.Path,required=True)
    args=ap.parse_args(); start=time.perf_counter()
    for file, expected in SHA.items():
        actual=sha(args.bundle/file)
        if actual!=expected: raise ValueError(f'{file} SHA-256 mismatch: {actual}, expected {expected}')
    with gzip.open(EVIDENCE,'rt') as f: evidence=json.load(f)
    rows=evidence['images']; assert len(rows)==193 and sum(r['eligible'] for r in rows)==188
    docs=prepare_documents(rows,args.host/'documents.jar',args.java)
    mismatches=[r['review_number'] for r in rows if len(docs[r['review_number']]) != r['words']]
    if mismatches: raise ValueError(f'Reconstructed OCR word counts differ from phone: {mismatches}')
    tokenizer=Tokenizer(args.bundle/'tokenizer.json');config=json.loads((args.bundle/'gliner_config.json').read_text())
    parity_words=verify_tokenizer(docs,tokenizer,args.bundle/'tokenizer.json',args.host,args.java)
    t=time.perf_counter();session=ort.InferenceSession(str(args.bundle/'gliner_small_int4.onnx'),providers=['CPUExecutionProvider']); load=time.perf_counter()-t
    hints={v:{} for v in VARIANTS}; failures={v:[] for v in VARIANTS}; times={v:[] for v in ('three','six')}; extra={}
    for row in rows:
        number=row['review_number']; doc=docs[number]
        for group,prompts in [('three',THREE),('six',SIX)]:
            try:
                words,width,feed=make_feed(doc,prompts,tokenizer,config,session.get_inputs()); t=time.perf_counter()
                logits=session.run(None,feed)[0]; times[group].append(time.perf_counter()-t)
                for variant,(grp,threshold,per_lane) in VARIANTS.items():
                    if grp!=group: continue
                    decoded=decode_logits(logits,words,width,prompts,threshold,per_lane)
                    hints[variant][number]=[(('producer','wine_name','appellation')[label],score,text) for label,score,text in decoded if label<3]
                    if variant=='six_labels': extra[number]=decoded
            except Exception as e:
                for variant,(grp,_,_) in VARIANTS.items():
                    if grp==group: failures[variant].append({'image':row['image'],'error':str(e)})
        print(f'processed {number}',flush=True)
    # No targets or exported final answers were used by the inference or policy process.
    predictions={}
    for v in VARIANTS:
        if not failures[v]: predictions[v]=run_policy(rows,hints[v],args.host/'decision.jar',args.java)
    with gzip.open(REFERENCES,'rt') as f: refs={r['image']:r['fields'] for r in json.load(f)['images']}
    phone={r['review_number']:r['fields'] for r in rows}
    report={'evidence_sha256':sha(EVIDENCE),'reference_sha256':sha(REFERENCES),'model_sha256':SHA['gliner_small_int4.onnx'],
            'source_export_sha256':evidence['source_sha256'],'tokenizer_parity_words':parity_words,'load_seconds':load,'inference_seconds':{k:sum(v) for k,v in times.items()},
            'inference_latency_seconds':{k:{'first':v[0] if v else None,'median':float(np.median(v)) if v else None,'p90':float(np.percentile(v,90)) if v else None} for k,v in times.items()},
            'failures':failures,'variants':{},'notes':['Development set only; independent validation required.', 'Six-label extra outputs are measured as span recall, not used to overwrite geography or vintage.', 'Host timing is not phone single-scan latency.']}
    report['phone']=score(rows,phone,refs,phone)
    if 'control' in predictions:
        control=predictions['control'];report['control_phone_field_differences']=[{'number':r['review_number'],'field':f,'phone':r['fields'].get(f,''),'control':control[r['review_number']].get(f,'')} for r in rows for f in FIELDS if control[r['review_number']].get(f,'')!=r['fields'].get(f,'')]
        for v,results in predictions.items(): report['variants'][v]=score(rows,results,refs,control)
        report['control_verified'] = not report['control_phone_field_differences']
    else: report['control_verified']=False
    if 'six_labels' in predictions:
        recall={}
        for label,field in [(3,'country'),(4,'region'),(5,'vintage')]:
            eligible=[r for r in rows if r['eligible'] and refs[r['image']][field]['status']=='expected_value']
            blanks=[r for r in rows if r['eligible'] and refs[r['image']][field]['status']=='expected_blank']
            recall[field]={'expected_value_cases':len(eligible),'exact_span_found':sum(any(norm(text) in refs[r['image']][field]['accepted'] for l,_,text in extra[r['review_number']] if l==label) for r in eligible),
                           'expected_blank_cases':len(blanks),'blank_with_any_span':sum(any(l==label for l,_,_ in extra[r['review_number']]) for r in blanks)}
        report['six_extra_exact_span_recall']=recall
    report['wall_seconds']=time.perf_counter()-start
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps({k:report[k] for k in ['control_verified','load_seconds','inference_seconds','wall_seconds']},indent=2))
    if failures['control'] or not report['control_verified']: raise SystemExit('Control failed or did not reproduce phone fields; results are diagnostic only')
    if any(failures.values()): raise SystemExit('At least one experiment failed; see report')

if __name__=='__main__': main()
