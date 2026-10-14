package com.yourapp.wine.kb

/** A cached character trie avoids compiling a regex for every KB place on every label line. */
class PlaceGazetteer(private val kb: WineKnowledgeBase) {
    private class Node { val children = mutableMapOf<Char, Node>(); val places = mutableListOf<Place>() }
    private var indexedPlaces: List<Place>? = null
    private var root = Node()

    /** No image coordinates: returns exact boundary-aware place matches from normalized line/whole-label text. */
    @Synchronized fun matches(normalizedText: String): List<Place> {
        val current = kb.places()
        if (current !== indexedPlaces) {
            val updated = Node()
            for (place in current) {
                var node = updated
                place.nameNorm.forEach { char -> node = node.children.getOrPut(char) { Node() } }
                node.places += place
            }
            root = updated; indexedPlaces = current
        }
        val found = mutableListOf<Place>()
        for (start in normalizedText.indices) {
            if (start > 0 && normalizedText[start - 1].isLetterOrDigit()) continue
            var node = root
            var index = start
            while (index < normalizedText.length) {
                node = node.children[normalizedText[index]] ?: break
                index++
                if (index == normalizedText.length || !normalizedText[index].isLetterOrDigit()) found += node.places
            }
        }
        return found.distinctBy { it.id }.sortedByDescending { it.nameNorm.length }
    }
}
