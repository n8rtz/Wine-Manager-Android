"""Source contracts for compact cellar-rack navigation and assignment controls."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"


class CellarRacksUiTests(unittest.TestCase):
    def test_rack_overview_has_no_redundant_heading_or_intro(self):
        source = (APP / "ui/screens/CellarRackViews.kt").read_text()
        self.assertNotIn('Text("Wine racks"', source)
        self.assertNotIn("Group bottles by how you plan to enjoy or age them.", source)
        self.assertIn("LazyVerticalGrid(", source)

    def test_compact_rack_action_uses_rack_and_plus_glyphs_and_assigns_a_rack(self):
        source = (APP / "ui/screens/CellarRackViews.kt").read_text()
        self.assertIn("fun RackQuickAddButton(", source)
        self.assertIn("Icons.Default.Inventory2", source)
        self.assertIn("Icons.Default.Add", source)
        self.assertIn('else "Add to rack"', source)
        self.assertIn('text = { Text("No rack") }', source)
        self.assertIn("cellarRackNames.forEach", source)
        self.assertIn("onAssign(rack)", source)

    def test_quick_rack_action_sits_above_the_expand_caret_on_cellar_cards(self):
        card = (APP / "ui/components/WineCard.kt").read_text()
        cellar = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn("trailingAction: @Composable (() -> Unit)? = null", card)
        self.assertLess(card.index("trailingAction?.invoke()"), card.index("Icons.Default.ExpandLess"))
        self.assertIn("trailingAction = {", cellar)
        self.assertIn("RackQuickAddButton(", cellar)


if __name__ == "__main__":
    unittest.main()
