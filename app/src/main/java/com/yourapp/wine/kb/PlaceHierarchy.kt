package com.yourapp.wine.kb

/** Shared immutable indexes refresh only when a committed local gazetteer snapshot changes. */
class PlaceHierarchy(private val kb: WineKnowledgeBase) {
    private var indexed: List<Place>? = null
    private var current = View(emptyList())

    /** No coordinates: returns name/ID indexes for a single committed hierarchy snapshot. */
    @Synchronized fun snapshot(): View {
        val places = kb.places()
        if (places !== indexed) { current = View(places); indexed = places }
        return current
    }

    class View internal constructor(places: List<Place>) {
        val byId: Map<Long, Place> = places.associateBy { it.id }
        val byName: Map<String, List<Place>> = places.groupBy { it.nameNorm }

        /** No coordinates: walks a place to its country, terminating safely on malformed/cyclic test input. */
        fun ancestors(place: Place): List<Place> {
            val path = mutableListOf<Place>()
            val seen = mutableSetOf<Long>()
            var node: Place? = place
            while (node != null && seen.add(node.id)) {
                path += node
                node = node.parentId?.let(byId::get)
            }
            return path
        }

        /** No coordinates: ancestor paths plus explicit multi-region reference membership; incomplete country-only knowledge cannot disprove a region. */
        fun compatible(first: Place, second: Place): Boolean = first.countryCode == second.countryCode &&
            (ancestors(first).any { it.id == second.id } || ancestors(second).any { it.id == first.id } ||
                referenceRegionCompatible(first, second) || referenceRegionCompatible(second, first))

        private fun referenceRegionCompatible(origin: Place, region: Place): Boolean {
            if(region.type != "region") return false
            val row=ReferenceWineCatalog.origin(origin) ?: return false
            return if(row.regions.size > 1) row.regions.any { com.yourapp.wine.merge.TextNorm.normalize(it)==region.nameNorm }
            // Country-only reference knowledge cannot assert a contradiction with a printed region.
            else row.regions.isEmpty() && origin.type == "appellation" && ancestors(origin).drop(1).all { it.type == "country" }
        }
    }
}
