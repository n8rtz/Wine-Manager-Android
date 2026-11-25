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

    def test_text_hierarchy_draws_eye_to_producer_and_name(self):
        s = CARD.read_text()
        # Primary tier, in order: producer (titleLarge + Bold) then wine
        # name (titleMedium + SemiBold), both before any muted text.
        self.assertLess(s.index("typography.titleLarge"), s.index("typography.titleMedium"))
        producer_block = s.split("valid(producer)?.let {", 1)[1].split("valid(wineName)", 1)[0]
        self.assertIn("FontWeight.Bold", producer_block)
        name_block = s.split("valid(wineName)?.let {", 1)[1].split("Spacer(Modifier.height(3.dp))", 1)[0]
        self.assertIn("FontWeight.SemiBold", name_block)
        # Secondary tier stays small/italic/muted, and a breathing gap
        # separates the primary block from the details.
        self.assertIn("FontStyle.Italic", s)
        self.assertIn("Spacer(Modifier.height(3.dp))", s)
        vintage_block = s.split("valid(vintage)?.let {", 1)[1].split("val place", 1)[0]
        self.assertIn("color = MaterialTheme.colorScheme.onSurfaceVariant", vintage_block)
        # Geography lines (place + country) are italic too, like vintage:
        # exactly three italic secondary lines on the card.
        self.assertEqual(s.count("copy(fontStyle = FontStyle.Italic)"), 3)

    def test_cellar_count_line_anchors_to_card_bottom(self):
        s = CARD.read_text()
        # The metadata line is NOT part of the flowing text column; it is
        # rendered after the header row (the card's bottom row, above the
        # optional date row), so it sits in the same spot regardless of
        # which lines above it are populated.
        text_col = s.split("verticalArrangement = Arrangement.spacedBy(2.dp)", 1)[1].split("if (trailingAction", 1)[0]
        self.assertNotIn("text = compactMetadata", text_col)
        bottom = s.split("if (compactMetadata.isNotBlank())", 1)[1].split("if (!dateAdded.isNullOrBlank())", 1)[0]
        self.assertIn("text = compactMetadata", bottom)
        self.assertIn("padding(start = 17.dp", bottom)


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
        # Header row is top-anchored and the trailing stack is
        # end-anchored: children right-align to the card edge, so the
        # icon's spot is the same with no rating, a single score, or a
        # wide multi-review range (which grows leftward from the same
        # right edge rather than shifting the icon or clipping).
        self.assertIn("verticalAlignment = Alignment.Top", s)
        self.assertIn("horizontalAlignment = Alignment.End", s)
        self.assertNotIn("wrapContentWidth", s)
        self.assertNotIn("Modifier.width(52.dp)", s)


if __name__ == "__main__":
    unittest.main()
