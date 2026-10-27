package com.yourapp.wine.kb

/** A cached character trie avoids compiling a regex for every KB place on every label line. */
class PlaceGazetteer(private val kb: WineKnowledgeBase) {
    private class Node { val children = mutableMapOf<Char, Node>(); val places = mutableListOf<Place>() }
    private var indexedPlaces: List<Place>? = null
    private var root = Node()

    /** No image coordinates: boundary-aware known place forms tolerate OCR word breaks, not changed letters. */
    @Synchronized fun matches(normalizedText: String): List<Place> =
        matches(normalizedText, emptySet(), emptySet(), false)

    @Synchronized fun matches(
        normalizedText: String,
        contextCountryCodes: Set<String>,
        contextRegionsNormalized: Set<String>,
        standaloneOrFramed: Boolean,
    ): List<Place> {
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
        data class Match(val place:Place,val start:Int,val end:Int)
        val found = mutableListOf<Match>()
        for (start in normalizedText.indices) {
            if (!normalizedText[start].isLetterOrDigit() || (start > 0 && normalizedText[start - 1].isLetterOrDigit())) continue
            var node = root
            var index = start
            while (index < normalizedText.length) {
                val char = normalizedText[index]
                if (!char.isLetterOrDigit()) {
                    if (char.isWhitespace() || char in "'-’.!") { index++; continue } else break
                }
                node = node.children[char] ?: break
                index++
                if (index == normalizedText.length || !normalizedText[index].isLetterOrDigit()) {
                    val fullPhrase = standaloneOrFramed && start == 0 && index == normalizedText.length
                    found += node.places.filter {
                        ReferenceWineCatalog.mayMatch(it, normalizedText, contextCountryCodes, contextRegionsNormalized, fullPhrase)
                    }.map { Match(it,start,index) }
                }
            }
        }
        // Nested names inside one longer appellation are not separate contradictory origin claims.
        return found.filter { hit -> found.none { other -> other.start <= hit.start && other.end >= hit.end &&
            other.end-other.start > hit.end-hit.start } }.map { it.place }
            .distinctBy { it.id }.sortedByDescending { it.nameNorm.length }
    }
}
