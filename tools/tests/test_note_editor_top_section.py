import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
EDITOR = ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/TastingNoteEditorScreen.kt"
DETAIL = ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/TastingNoteDetailScreen.kt"


class NoteEditorTopSectionTests(unittest.TestCase):
    def test_wine_search_only_when_creating_a_note(self):
        source = EDITOR.read_text()
        # The wine of an existing note can't be changed, so the search
        # dropdown is create-only; editing shows just the date field.
        self.assertIn("if (noteId == null) {\n                ExposedDropdownMenuBox(", source)
        self.assertEqual(1, source.count("ExposedDropdownMenuBox(\n                    expanded = wineMenuExpanded"))

    def test_no_location_or_price_at_the_top(self):
        source = EDITOR.read_text()
        top_block = source.split('if (entryMode == TastingNoteEntryMode.CELLAR && selectedDefinition != null) {', 1)[1].split(
            "OverallScoreSlider(", 1
        )[0]
        # The wine title stays; the location and price lines are gone.
        self.assertIn("selectedDefinition.maker", top_block)
        self.assertNotIn("joinToString", top_block)
        self.assertNotIn("Price:", top_block)
        self.assertNotIn('"Price: $selectedCellarPriceText"', source)

    def test_price_lives_with_the_value_section(self):
        source = EDITOR.read_text()
        self.assertIn("val displayPriceText = existingNote?.price", source)
        value_section = source.split('Text(\n                        "Value",', 1)[1].split("FilterChip(", 1)[0]
        self.assertIn('"Price"', value_section)
        self.assertIn("displayPriceText", value_section)
        # Styled like the rest of the card, not muted onSurfaceVariant.
        price_row = value_section.split('"Price"', 1)[1]
        self.assertNotIn("onSurfaceVariant", price_row)


class NoteDetailTopSectionTests(unittest.TestCase):
    def test_no_wine_search_field_at_the_top(self):
        source = DETAIL.read_text()
        self.assertNotIn('label = { Text("Wine") }', source)
        self.assertNotIn("Icons.Default.Search", source)
        # The wine title remains as a plain heading.
        self.assertIn("text = wineTitle", source)

    def test_no_location_or_price_at_the_top(self):
        source = DETAIL.read_text()
        top_block = source.split("text = wineTitle", 1)[1].split("OverallScoreSlider(", 1)[0]
        self.assertNotIn("location", top_block)
        self.assertNotIn("Price", top_block)

    def test_price_lives_with_the_value_section(self):
        source = DETAIL.read_text()
        value_section = source.split('Text("Value"', 1)[1].split("FilterChip(", 1)[0]
        self.assertIn('"Price"', value_section)
        self.assertIn("recordedPrice", value_section)
        price_row = value_section.split('"Price"', 1)[1]
        self.assertNotIn("onSurfaceVariant", price_row)


if __name__ == "__main__":
    unittest.main()
