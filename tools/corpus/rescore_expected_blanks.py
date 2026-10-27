#!/usr/bin/env python3
"""Re-score frozen outputs with an explicit, notes-based expected-blank overlay.
No OCR/extractor execution, no edits to the v1 reference, no blanket unknown->blank.
Optional Excel export requires openpyxl. Overlay judgments remain provisional.
"""
import csv
import json
from collections import Counter,defaultdict
from pathlib import Path
from score_public_corpus import normalize,digest
ROOT=Path(__file__).resolve().parents[2]
E=ROOT/'tools/evaluation'
OUT=E/'blank-aware-rescore-v1'
REF=E/'public-corpus-reference-v1/reference.json'
DIAG=E/'comprehensive-diagnostics-v1/field-diagnostics.csv'
HYBRID=E/'anchor-iteration-39834118734/field-results.csv'
# Review-note adjudications, declared independently of prediction values.
# These annotations establish a regional designation but no supported finer origin.
REGIONAL_ONLY={10,16,17,20,38,42,44,50,53,58,59,61,63,65,66,70,72,77,84,85,86,93,99,106,111,118,125,126,128,129,130,134,135,138,139,141,146,152,154,156,158}
# Notes explicitly rule out a visible appellation/cuvee and borrowing from estate reputation.
NO_ORIGIN_OR_WINE={39,90,95,110,121,124,131,149}
# Vin de France / France-only: no supported finer geographic origin.
COUNTRY_ONLY={12,60,117,153}
NO_YEAR={12,16,30,43,54,57,60,66,67,70,72,111,114,117,126,153}
UNRESOLVED={4,8,9,19,38,101,109}

def disposition(item,field):
    target=item['fields'][field];n=item['review_number']
    if target['status']=='known':return 'expected_value','Original known value and geographic inference policy unchanged.'
    if target['status']=='unknown' and field=='producer' and n in (14,108):
        return 'unresolved','Club des Sommeliers is visible branding and the named entity is a bottler; the actual producer is not established by the photo.'
    if field=='subregion' and n in REGIONAL_ONLY:
        return 'expected_blank','Recorded review establishes regional origin only; no supported finer origin. Do not use a brand, bottler address, or vineyard name as an unsupported subregion.'
    if n in NO_ORIGIN_OR_WINE and field in ('wine_name','country','region','subregion'):
        return 'expected_blank','Recorded review reports no separate supported appellation/origin/wine name in this photo; do not infer from estate identity alone.'
    if n in COUNTRY_ONLY and field in ('region','subregion'):
        return 'expected_blank','Recorded review supports country only, not a narrower origin.'
    if n in NO_YEAR and field=='vintage':
        return 'expected_blank','Recorded review says no visible vintage; founding dates are not vintages.'
    if n==125 and field=='wine_name':
        return 'unresolved','Reference notes say exact appellation/cuvee absent but generic Bordeaux is visible; wine-name fallback policy needs adjudication.'
    assert n in UNRESOLVED,(n,field)
    return 'unresolved','Recorded review describes cropped/ambiguous/unreadable information or uncertain product category; absence is not established.'

def classify(status,target,output):
    if status=='unresolved':return 'unscored_filled' if output.strip() else 'unscored_blank'
    if status=='expected_blank':return 'unsupported_fill' if output.strip() else 'correct_blank'
    if not output.strip():return 'missing_value'
    return 'correct_value' if normalize(output) in {normalize(v) for v in target['accepted']} else 'wrong_value'

def write_csv(path,rows):
    with path.open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]),lineterminator="\n");w.writeheader();w.writerows(rows)

def run():
    OUT.mkdir(exist_ok=True)
    assert digest(REF)==(REF.parent/'reference.sha256').read_text().split()[0], 'Frozen reference changed'
    reference=json.loads(REF.read_text());items=reference['images']
    # Freeze dispositions from reference/notes BEFORE opening saved predictions.
    overlay=[];policy={}
    for item in items:
        for field,t in item['fields'].items():
            status,reason=disposition(item,field);policy[item['image'],field]=status
            overlay.append(dict(review_number=item['review_number'],image=item['image'],field=field,original_status=t['status'],new_status=status,target=t['value'] or '',basis=t['basis'],decision_reason=reason,original_review_notes=item['notes']))
    assert sum(r['new_status']=='expected_value' for r in overlay)==892
    write_csv(OUT/'reference-overlay.csv',overlay)
    diag={(r['image'],r['field']):r for r in csv.DictReader(DIAG.open())}
    hy={(r['image'],r['field']):r for r in csv.DictReader(HYBRID.open()) if r['variant']=='anchors-role-guard'}
    systems=('baseline','previous_v3','guarded_hybrid');fields=[];labels=[]
    for item in items:
        im=item['image'];label=dict(review_number=item['review_number'],image=im,identity_group=item['identity_group'])
        statuses=[policy[im,f] for f in item['fields']]
        label['expected_values']=statuses.count('expected_value');label['expected_blanks']=statuses.count('expected_blank');label['unresolved']=statuses.count('unresolved');label['fully_scorable']=not label['unresolved']
        per=defaultdict(list)
        for field,target in item['fields'].items():
            d=diag[im,field];status=policy[im,field]
            row=dict(review_number=item['review_number'],image=im,field=field,new_reference_status=status,target=target['value'] or '',reference_basis=target['basis'],review_notes=item['notes'])
            for name,value in zip(systems,[d['baseline'],d['v3'],hy[im,field]['output']]):
                result=classify(status,target,value);row[name+'_output']=value;row[name+'_outcome']=result;per[name].append(result)
            fields.append(row)
        for name in systems:
            c=Counter(per[name]);correct=c['correct_value']+c['correct_blank'];errors=c['wrong_value']+c['unsupported_fill']+c['missing_value']
            label[name+'_correct_slots']=correct;label[name+'_missing_values']=c['missing_value'];label[name+'_wrong_values']=c['wrong_value'];label[name+'_unsupported_fills']=c['unsupported_fill'];label[name+'_correct_blanks']=c['correct_blank']
            label[name+'_complete']=label['fully_scorable'] and correct==6
            label[name+'_all_scorable_correct']=errors==0
        labels.append(label)
    write_csv(OUT/'paired-fields.csv',fields);write_csv(OUT/'paired-labels.csv',labels)
    counts=Counter(r['new_status'] for r in overlay);eligible=[r for r in labels if r['fully_scorable']]
    summary=dict(policy='Expected values retain original aliases and geographic inference. Expected blanks earn credit; unsupported fills are errors. Unresolved remains unscored.',
        qualification='Provisional visual-reference correction at the owner\'s request: Club des Sommeliers is treated as branding, not as the producer; actual producers are left unresolved when not established. Saved predictions were not rerun.',
        provenance={str(p.relative_to(ROOT)):digest(p) for p in [REF,DIAG,HYBRID]},implementation_sha256=digest(Path(__file__)),
        reference_states=dict(counts),images=168,fully_scorable_labels=len(eligible),partly_unresolved_labels=168-len(eligible),systems={})
    field_totals=[]
    for name in systems:
        c=Counter(r[name+'_outcome'] for r in fields)
        full={r['image'] for r in eligible if r[name+'_complete']};base={r['image'] for r in eligible if r['baseline_complete']}
        summary['systems'][name]=dict(outcomes=dict(c),correct_total=c['correct_value']+c['correct_blank'],wrong_total=c['wrong_value']+c['unsupported_fill'],missing_values=c['missing_value'],complete=len(full),complete_gained_vs_baseline=sorted(full-base),complete_lost_vs_baseline=sorted(base-full),
            correct_slot_distribution={str(n):sum(r[name+'_correct_slots']==n for r in eligible) for n in range(7)})
        for field in items[0]['fields']:
            cc=Counter(r[name+'_outcome'] for r in fields if r['field']==field)
            field_totals.append(dict(system=name,field=field,**{k:cc[k] for k in ['correct_value','correct_blank','wrong_value','unsupported_fill','missing_value','unscored_filled','unscored_blank']}))
    write_csv(OUT/'field-totals.csv',field_totals)
    (OUT/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    lines=['# Blank-aware rescoring of frozen outputs','',summary['policy'],'',summary['qualification'],'',
        f"Reference slots: {counts['expected_value']} expected values, {counts['expected_blank']} expected blanks, {counts['unresolved']} unresolved. Fully scorable forms: {len(eligible)}/168.",'',
        '| System | Complete forms | Correct values | Correct blanks | Wrong values | Unsupported fills | Missing required values |',
        '|---|---:|---:|---:|---:|---:|---:|']
    for name,s in summary['systems'].items():
        c=Counter(s['outcomes']);lines.append(f"| {name} | {s['complete']}/{len(eligible)} | {c['correct_value']} | {c['correct_blank']} | {c['wrong_value']} | {c['unsupported_fill']} | {c['missing_value']} |")
    lines+=['','## Correct slots per fully scorable form (includes correct blanks)','', '| Correct slots | Baseline | Previous v3 | Guarded hybrid |','|---|---:|---:|---:|']
    for n in range(6,-1,-1):lines.append('| '+str(n)+' | '+' | '.join(str(summary['systems'][name]['correct_slot_distribution'][str(n)]) for name in systems)+' |')
    lines+=['','## Interpretation','',
        'A correctly blank region/subregion now contributes toward a completely correct form. Geographic inference from supported appellations is still required. Do not compare these counts directly with the old 103-photo denominator as if model quality improved: only the scoring changed.',
        'Producer identity is now unresolved for reviews 14 and 108: Club des Sommeliers is label branding, while the named Cave de Tain and Maison Delor are bottlers; no actual producer is established. Existing no-value producer references for reviews 9/38/109, unreadable/uncertain fields in 4/8/19/101, and the wine-name policy ambiguity in 125 remain unresolved. See reference-overlay.csv for every decision and original review notes.',
        'Unknown is not automatically converted to expected blank. The inferred St. Helena subregion in review 150 remains an expected value, not a blank, under the unchanged geographic policy.',
        'This is a development-set scoring correction, not held-out validation or a new production promotion. Frozen baseline/v3/hybrid outputs are unchanged.','']
    (OUT/'report.md').write_text('\n'.join(lines))
    try:
        from openpyxl import Workbook,load_workbook
        from openpyxl.styles import Font,PatternFill
        from openpyxl.utils import get_column_letter
    except ImportError:
        print('CSV/JSON/Markdown written; install openpyxl for Excel export.')
    else:
        wb=Workbook();wb.remove(wb.active)
        sheets={'Read me':[{'note':line} for line in lines if line], 'Summary':[dict(system=name,complete=s['complete'],eligible=len(eligible),correct_total=s['correct_total'],wrong_total=s['wrong_total'],missing_values=s['missing_values'],**s['outcomes']) for name,s in summary['systems'].items()], 'Label distribution':[dict(correct_slots=n,**{name:summary['systems'][name]['correct_slot_distribution'][str(n)] for name in systems}) for n in range(6,-1,-1)],'Field totals':field_totals,'Labels':labels,'Fields':fields,'Reference decisions':overlay}
        for name,rows in sheets.items():
            ws=wb.create_sheet(name);headers=list(dict.fromkeys(k for r in rows for k in r));ws.append(headers)
            for row in rows:
                ws.append([row.get(k,0) for k in headers])
                for cell in ws[ws.max_row]:
                    if isinstance(cell.value,str):cell.data_type='s'
            ws.freeze_panes='A2';ws.auto_filter.ref=ws.dimensions
            for cell in ws[1]:cell.font=Font(bold=True,color='FFFFFF');cell.fill=PatternFill('solid',fgColor='23495B')
            for n,head in enumerate(headers,1):ws.column_dimensions[get_column_letter(n)].width=min(65,max(18,len(head)+3))
        wb.save(OUT/'blank-aware-comparison.xlsx')
        check=load_workbook(OUT/'blank-aware-comparison.xlsx',read_only=True);assert check['Fields'].max_row==1009 and check['Labels'].max_row==169
    print(json.dumps(summary,indent=2))
    return summary

if __name__=='__main__':run()
