"""Spatial-contender budgets: setup and lookups must not share one slice, and the cached
database copy must not be re-hashed on every open.

Both were measured sources of the "resolver completed within budget" blocked rows: the
resolvers were handed the leftovers of a 500 ms slice that also covered a 34-50 MB hash
pass, so they threw BudgetExceeded before finishing their lookups.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXTRACTION = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabWineExtraction.kt'
BUDGETS = ROOT / 'app/src/main/java/com/yourapp/wine/textlab/TextLabJournal.kt'
GAZETTEER = ROOT / 'app/src/main/java/com/yourapp/wine/gazetteer/ProducerGazetteer.kt'


class ResolverBudgetTests(unittest.TestCase):
    def test_setup_and_lookup_have_separate_budgets(self):
        budgets = BUDGETS.read_text()
        self.assertIn('const val GAZETTEER_SETUP_MS = 500L', budgets)
        self.assertIn('const val GAZETTEER_LOOKUP_MS = 2_000L', budgets)
        extraction = EXTRACTION.read_text()
        self.assertIn('setupDeadline=android.os.SystemClock.elapsedRealtime()+TextLabBudgets.GAZETTEER_SETUP_MS', extraction)
        self.assertIn('lookupDeadline=android.os.SystemClock.elapsedRealtime()+TextLabBudgets.GAZETTEER_LOOKUP_MS', extraction)
        # The lookup window must not be derived from what setup left over.
        self.assertNotIn('lookupDeadline=now+remaining', extraction)
        self.assertNotRegex(extraction, r'val remaining=\(deadline-')

    def test_the_cached_copy_is_not_rehashed_on_every_open(self):
        gazetteer = GAZETTEER.read_text()
        self.assertIn('private fun stampMatches(target: File, hash: String): Boolean', gazetteer)
        self.assertIn('if (!stampMatches(target, hash)) {', gazetteer)
        self.assertIn('writeStamp(target, hash)', gazetteer)
        # No hash pass on the hot path: sha() may only verify the freshly copied temporary file.
        hot = gazetteer.split('private fun load(', 1)[1]
        self.assertNotIn('sha(target, checkBudget)', hot)
        self.assertIn('check(sha(temporary, checkBudget) == hash)', hot)
        # The manifest record counts are still checked against the opened database every time.
        for fragment in ('SELECT COUNT(*) FROM producers', 'SELECT COUNT(*) FROM wine_names',
                         'SELECT COUNT(*) FROM wine_links'):
            self.assertTrue(fragment in gazetteer, fragment)


if __name__ == '__main__':
    unittest.main()
