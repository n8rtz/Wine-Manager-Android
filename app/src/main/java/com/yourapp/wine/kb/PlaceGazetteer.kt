package com.yourapp.wine.kb

/** A cached character trie avoids compiling a regex for every KB place on every label line. */
class PlaceGazetteer(private val kb: WineKnowledgeBase) {
    private class Node { val children = mutableMapOf<Char, Node>(); val places = mutableListOf<Place>() }
    private var indexedPlaces: List<Place>? = null
    private var root = Node()

    /** No image coordinates: boundary-aware known place forms tolerate OCR word breaks, not changed letters. */
    @Synchronized fun matches(normalizedText: String): List<Place> {
        val current = kb.places()
        if (current !== indexedPlaces) {
            val updated = Node()
            for (place in current) {
                var node = updated
                // Ignore OCR-inserted word breaks only inside verified geographic forms.
                for (form in (listOf(place.name) + BundledOrigins.spellingForms(place)).distinct()) {
                    node = updated
                    com.yourapp.wine.merge.TextNorm.normalize(form).filter(Char::isLetterOrDigit)
                        .forEach { char -> node = node.children.getOrPut(char) { Node() } }
                    node.places += place
                }
            }
            root = updated; indexedPlaces = current
        }
        val found = mutableListOf<Place>()
        for (start in normalizedText.indices) {
            if (!normalizedText[start].isLetterOrDigit() || (start > 0 && normalizedText[start - 1].isLetterOrDigit())) continue
            var node = root
            var index = start
            while (index < normalizedText.length) {
                val char = normalizedText[index]
                if (!char.isLetterOrDigit()) {
                    if (char.isWhitespace() || char in "'-’.") { index++; continue } else break
                }
                node = node.children[char] ?: break
                index++
                if (index == normalizedText.length || !normalizedText[index].isLetterOrDigit()) found += node.places
            }
        }
        return found.distinctBy { it.id }.sortedByDescending { it.nameNorm.length }
    }
}
