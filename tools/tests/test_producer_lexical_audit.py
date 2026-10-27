import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/producer-sources/audit_lexical.py"
SPEC = importlib.util.spec_from_file_location("producer_lexical_audit", SCRIPT)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class ProducerLexicalAuditBoundaryTests(unittest.TestCase):
    def test_retired_reference_hash_is_rejected_even_if_moved(self):
        with self.assertRaisesRegex(ValueError, "retired 168-image reference"):
            AUDIT.require_nonretired_reference(AUDIT.RETIRED_REFERENCE_SHA256)

    def test_reference_must_be_explicit_and_is_recorded(self):
        with tempfile.TemporaryDirectory() as temp:
            reference = Path(temp) / "scrubbed-reference.json"
            reference.write_text(json.dumps({"images": []}))
            result = AUDIT.audit(temp, reference)
            self.assertEqual(result["reference_path"], str(reference.resolve()))
            self.assertEqual(result["layers"], {})


if __name__ == "__main__":
    unittest.main()
