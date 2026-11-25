"""Contracts for the standardized 6-line wine-card layout and the fixed
add-to-rack icon anchor (same spot regardless of review count/score)."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"
CARD = APP / "ui/components/WineCard.kt"


class CardLayoutTests(unittest.TestCase):
    def test_standardized_six_line_layout(self):
        s = CARD.read_text()
        # One field per line, in order: producer -, name, (vintage),
        # subregion/region, country, metadata ("Cellar: X").
        for token in (
            "producer: String", "wineName: String", "vintage: String",
            "subregion: String? = null", "region: String? = null", "country: String? = null",
            'text = "$it -"', 'text = "($it)"',
            'listOfNotNull(valid(subregion), valid(region)).joinToString(", ")',
        ):
            self.assertIn(token, s)
        # Blank fields omit their line: no fixed spacers between lines,
        # just a tight 2.dp arrangement so no large white gaps appear.
        self.assertIn("verticalArrangement = Arrangement.spacedBy(2.dp)", s)
        self.assertNotIn("Spacer(Modifier.height(3.dp))", s)

    def test_call_sites_pass_structured_fields(self):
        for rel in (
            "ui/screens/CellarScreen.kt",
            "ui/screens/CellarRackViews.kt",
            "ui/screens/TastingNotesScreen.kt",
            "ui/screens/RankingsScreen.kt",
        ):
            src = (APP / rel).read_text()
            self.assertIn("producer = ", src, rel)
            self.assertIn("wineName = ", src, rel)
            self.assertIn("vintage = ", src, rel)
            self.assertNotIn('title = "${', src, rel)

    def test_icon_anchor_does_not_depend_on_rating(self):
        s = CARD.read_text()
        # Header row is top-anchored and the trailing column has a fixed
        # width, so the icon's spot is the same with no rating, a single
        # score, or a wide multi-review range.
        self.assertIn("verticalAlignment = Alignment.Top", s)
        self.assertIn("Modifier.width(52.dp).offset(y = 6.dp)", s)
        # The rating box may overflow the fixed column width (centred,
        # unbounded) instead of widening the column and shifting the icon.
        self.assertIn("wrapContentWidth(Alignment.CenterHorizontally, unbounded = true)", s)


if __name__ == "__main__":
    unittest.main()
