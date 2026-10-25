import csv
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / "tools/evaluation/wine-name-audit-v1"

class WineNameAuditTests(unittest.TestCase):
    def test_reproducible_queue_covers_all_labels_and_keeps_review_pending(self):
        subprocess.run([sys.executable, str(AUDIT / "build.py")], cwd=ROOT, check=True, capture_output=True, text=True)
        with (AUDIT / "wine-name-review.csv").open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual(len(rows), 168)
        self.assertEqual(len({r["image"] for r in rows}), 168)
        self.assertEqual({r["audit_status"] for r in rows}, {"PENDING_VISUAL_REVIEW"})
        self.assertTrue(all(r["reviewer_decision"] == "" and r["reviewer_notes"] == "" for r in rows))
        summary = json.loads((AUDIT / "summary.json").read_text())
        self.assertEqual(summary["saved_v2_outcomes"], {
            "correct_value": 77, "wrong_value": 44, "missing_value": 36,
            "correct_blank": 5, "unsupported_fill": 3,
            "unscored_blank": 1, "unscored_filled": 2,
        })
        self.assertIn("not a fresh independent image adjudication", summary["purpose"])

if __name__ == "__main__": unittest.main()
