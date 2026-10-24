import csv
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('summary',Path(__file__).resolve().parents[1]/'corpus/summarize_android.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AndroidCorpusSummaryTests(unittest.TestCase):
    def test_missing_execution_is_not_success_or_accuracy(self):
        with tempfile.TemporaryDirectory() as directory:
            result=module.summarize(directory)
            self.assertEqual(result['lab_images_with_rows'],0)
            self.assertIsNone(result['expected_distinct_images'])
            self.assertIsNone(result['accuracy'])

    def test_partial_and_failed_reports_remain_visible(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'results').mkdir()
            (root/'selected-images.txt').write_text('a.jpg\nb.jpg\n')
            with (root/'results/a.csv').open('w',newline='') as file:
                writer=csv.DictWriter(file,fieldnames=['image_name','candidate','stage','status','raw_text'])
                writer.writeheader()
                writer.writerows([
                    dict(image_name='a.jpg',candidate='candidate_3',stage='recognition',status='complete',raw_text='Label, text'),
                    dict(image_name='a.jpg',candidate='candidate_3',stage='candidate_summary',status='timed_out')])
            (root/'results/a-production.json').write_text(json.dumps(dict(deadlineCancelled=True,peakWorkingBitmaps=2,workingBitmapsAfter=0)))
            result=module.summarize(root)
            self.assertEqual(result['expected_distinct_images'],2)
            self.assertEqual(result['lab_images_with_rows'],1)
            self.assertEqual(result['production_deadlines'],1)
            self.assertEqual(result['lab_summary_statuses']['candidate_3 / candidate_summary / timed_out'],1)
            self.assertEqual(result['lab_recognized_lines'],{'candidate_3':1})
            self.assertIsNone(result['accuracy'])
