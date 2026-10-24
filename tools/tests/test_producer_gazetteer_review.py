import hashlib,importlib.util,json,unittest,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
REVIEW=ROOT/'tools/producer-gazetteer-review'
class ProducerGazetteerReviewChecks(unittest.TestCase):
    def test_original_archive_and_manifest(self):
        data=(REVIEW/'source.zip').read_bytes();m=json.loads((REVIEW/'manifest.json').read_text())
        self.assertEqual(len(data),m['archive_bytes'])
        self.assertEqual(hashlib.sha256(data).hexdigest(),m['archive_sha256'])
        self.assertEqual(hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest(),m['source_blob_sha1'])
        with zipfile.ZipFile(REVIEW/'source.zip') as archive:
            self.assertNotIn('pipeline/data/ttb_cola_registry_raw.tsv.gz',archive.namelist())
            self.assertFalse(any(n.endswith(('.sqlite','.db')) for n in archive.namelist()))
    def test_actual_offline_audit_not_seeded_ui_claims(self):
        spec=importlib.util.spec_from_file_location('producer_review_audit',REVIEW/'audit.py')
        audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
        result=audit.audit();saved=json.loads((REVIEW/'audit.json').read_text())
        for field in ('source_blob_sha1','zip_sha256','actual_producers','actual_fts_rows','curated_rows','countries','lexical_reference_coverage'):
            self.assertEqual(result[field],saved[field])
        self.assertEqual(result['actual_producers'],128)
        self.assertEqual(result['lexical_reference_coverage']['matched_photos'],14)
        self.assertEqual(len(result['lexical_reference_coverage']['distinct_reference_names']),4)
        self.assertFalse(result['live_downloads_enabled'])
        probes={p['query']:p['top_results'] for p in result['search_probes']}
        self.assertEqual(probes['Cristal'][0]['name'],'Champagne Louis Roederer')
        self.assertEqual(probes['Margaux'][0]['name'],'Château Margaux')
        self.assertTrue(probes['PRODUCT OF FRANCE']) # demonstrates need for our abstention layer
        self.assertEqual(probes['zzqxv zzqxv'],[])
    def test_not_packaged_or_enabled_in_app(self):
        for source_set in ('main','debug'):
            self.assertFalse((ROOT/f'app/src/{source_set}/assets/producer-gazetteer-v1').exists())
        runner=(ROOT/'app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt').read_text()
        self.assertNotIn('producer-gazetteer-review',runner)
