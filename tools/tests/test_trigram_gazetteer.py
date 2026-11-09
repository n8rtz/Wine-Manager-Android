import json,sqlite3,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tools/gazetteer'))
import build_sqlite as build
from gazetteer_resolver import Resolver,clean,distance
from verify_trigram_search import STANDARD

class TrigramGazetteerTests(unittest.TestCase):
    def test_source_integrity_and_deterministic_build(self):
        provenance=json.loads((ROOT/'data/gazetteer/provenance.json').read_text())
        self.assertEqual(build.digest(build.DEFAULT_SOURCE),provenance['source_sha256'])
        self.assertEqual(build.digest(build.DEFAULT_DB),provenance['database_sha256'])
        with tempfile.TemporaryDirectory() as d:
            result=build.build(dest=Path(d)/'db.sqlite')
            self.assertEqual(result['records'],3244)
            self.assertEqual(result['levels'],{'region':230,'appellation':2989,'sub_region':25})
            self.assertEqual(result['database_sha256'],provenance['database_sha256'])
    def test_standard_queries_and_negative_roles(self):
        r=Resolver()
        try:
            for q,expected in STANDARD:
                with self.subTest(query=q):self.assertEqual((r.resolve(q)['matched_entity'] or {}).get('id'),expected)
            for q in ['Pauillac Bordeaux','x'*161,'13.5% ALC BY VOL','750 ml','Domaine de Pauillac']:
                self.assertIsNone(r.resolve(q)['matched_entity'])
        finally:r.close()
    def test_transpositions_and_abbreviation_normalization(self):
        self.assertEqual(distance('pauillca','pauillac'),1)
        self.assertEqual(build.normalize('St. Julien'),'saint julien')
        self.assertEqual(build.normalize('Mt. Étna'),'mount etna')
        self.assertEqual(clean('Appellation Pauilac Contrôlée'),'pauilac')
    def test_fts_and_alias_schema(self):
        with sqlite3.connect(build.DEFAULT_DB) as db:
            ddl=db.execute("SELECT sql FROM sqlite_master WHERE name='appellations_fts'").fetchone()[0]
            self.assertIn("tokenize='trigram'",ddl)
            self.assertIn('country UNINDEXED',ddl)
            db.execute("INSERT INTO appellations_fts(appellations_fts) VALUES('integrity-check')")
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0],'ok')
            aliases=json.loads(db.execute('SELECT aliases_json FROM entities WHERE id=300').fetchone()[0])
            self.assertIn('cotes du rhone',aliases);self.assertIn('cotes-du-rhone',aliases)
    def test_opt_in_preparser_preserves_baseline_and_raw_evidence(self):
        base=ROOT/'app/src/main/java/com/yourapp/wine/textlab'
        for f in ['TextLabBatch.kt']:self.assertIn('compareGazetteer:Boolean=false',(base/f).read_text())
        s=(base/'TextLabGazetteerPreview.kt').read_text()
        self.assertLess(s.index('records.map { resolver.resolve'),s.index('engine.fill(remaining'))
        self.assertIn('matches[i].accepted==null',s)
        self.assertIn('original_box',s);self.assertIn('rectified_box',s)
        self.assertNotIn('UiState.GREEN',s)
        production_roots=[ROOT/'app/src/main/java/com/yourapp/wine/capture',ROOT/'app/src/main/java/com/yourapp/wine/fill']
        for root in production_roots:
            for p in root.rglob('*.kt'):self.assertNotIn('TrigramGazetteer',p.read_text())
    def test_measured_host_benchmark_passes_and_is_labeled_synthetic(self):
        p=ROOT/'tools/evaluation/gazetteer-trigram-v1'
        summary=json.loads((p/'scores.json').read_text());rows=json.loads((p/'cases.json').read_text())
        positives=[r for r in rows if r['expected_id'] is not None]
        self.assertAlmostEqual(summary['top1_recall'],sum(r['correct'] for r in positives)/len(positives))
        self.assertGreaterEqual(summary['top1_recall'],.9);self.assertEqual(summary['false_positives'],0)
        self.assertIn('not real OCR',summary['note'])
    def test_android_collector_requires_native_parity_and_routing(self):
        sys.path.insert(0,str(ROOT/'tools/corpus'))
        import gazetteer_validation as suite
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);suite.prepare(root/'input.json');cases=json.loads(suite.SOURCE.read_text())
            positives=[c for c in cases if c['expected_id'] is not None]
            report=dict(source_commit='b'*12,cases=len(cases),mismatches=[],positive_cases=len(positives),negative_cases=len(cases)-len(positives),correct=sum(c['expected_id']==c['host_id'] for c in positives),false_positives=0,synthetic_routing_passed=True,ocr_executions=0,corpus_baseline_executions=0,elapsed_ms=[1]*len(cases))
            (root/'validation.json').write_text(json.dumps(report));suite.collect(root,root/'ok','b'*40)
            report['mismatches']=['wrong result'];(root/'validation.json').write_text(json.dumps(report))
            with self.assertRaises(AssertionError):suite.collect(root,root/'bad','b'*40)
    def test_builder_rejects_cycles(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);data=json.loads(build.DEFAULT_SOURCE.read_text());data['rows'][0]['parent_id']=data['rows'][0]['id']
            (root/'bad.json').write_text(json.dumps(data))
            with self.assertRaisesRegex(AssertionError,'Hierarchy cycle'):build.build(root/'bad.json',root/'bad.sqlite')
            self.assertFalse((root/'bad.sqlite').exists())
