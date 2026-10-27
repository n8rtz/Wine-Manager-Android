package com.yourapp.wine.kb

import com.yourapp.wine.merge.TextNorm

/** Read-only geographic supplement; imported records/positive IDs and the SQLite file are never rewritten. */
class SupplementedWineKnowledgeBase(private val base: WineKnowledgeBase) : WineKnowledgeBase by base {
    private var indexed: List<Place>? = null
    private var snapshot: List<Place> = emptyList()

    /** No coordinates: merge existing curated origins into a cached immutable view, including on already-installed databases. */
    @Synchronized override fun places(): List<Place> {
        val current = base.places()
        if (current === indexed) return snapshot
        val rows = current.toMutableList()
        val occupied = rows.map { it.id }.toMutableSet()
        val byName = rows.groupBy { it.countryCode to it.nameNorm }.mapValues { it.value.toMutableList() }.toMutableMap()
        val byCountry = rows.filter { it.type == "country" }.groupBy { it.countryCode }
            .mapValues { it.value.toMutableList() }.toMutableMap()
        var virtualId = Long.MIN_VALUE + 1024
        val added = mutableSetOf<Long>()
        fun add(name: String, type: String, parent: Long?, code: String): Place? {
            val cleanName = TextNorm.scrub(name)
            val key = code to TextNorm.normalize(cleanName)
            val matches = byName[key].orEmpty().filter {
                if (type == "country") it.type == "country" else it.type != "country" }
            if (matches.isNotEmpty()) return matches.singleOrNull() // Never choose an ambiguous imported place.
            while (virtualId in occupied) virtualId++
            val place = Place(virtualId++, cleanName, type, parent, code)
            occupied += place.id; added += place.id; rows += place
            byName.getOrPut(key) { mutableListOf() } += place
            if (type == "country") byCountry.getOrPut(code) { mutableListOf() } += place
            return place
        }
        for (term in BundledOrigins.terms) {
            val code = BundledOrigins.countryCode(term.country) ?: continue
            val countries = byCountry[code].orEmpty()
            val country: Place = (if (countries.isEmpty()) add(term.country, "country", null, code) else countries.singleOrNull())
                ?: continue
            val reference = ReferenceWineCatalog.named(term.name, code)
            // Walk the authored containing chain (broad region first, then subregions). A blank
            // path keeps the country-only behaviour: no region is invented.
            val path = reference?.path.orEmpty().ifEmpty { listOfNotNull(term.region.takeIf(String::isNotBlank)) }
            var region: Place = country
            var level = 0
            while (level < path.size) {
                val next = add(path[level], if (level == 0) "region" else "subregion", region.id, code)
                    ?: run { region = country; break }
                region = next
                level++
            }
            val type = if (reference?.nameRole == "geography") {
                if (path.isEmpty() && reference.regions.isEmpty()) "region" else "subregion"
            } else "appellation"
            // A row that names its own containing place reuses it instead of adding a child.
            if (TextNorm.normalize(term.name) != region.nameNorm) add(term.name, type, region.id, code)
        }
        // The DOCG/DOC names remain appellations; the cellar displays their shared town separately.
        byName["IT" to TextNorm.normalize("Tuscany")]?.singleOrNull()?.let { tuscany ->
            add("Montalcino","subregion",tuscany.id,"IT")
        }
        // Explicit, real containment omitted by the old flat list. Only virtual children may be linked;
        // an imported place's chosen hierarchy always wins and can still produce a YELLOW conflict.
        for ((childName, parentName) in containment) {
            val childNorm = TextNorm.normalize(childName)
            val children = rows.filter { it.id in added && it.nameNorm == childNorm }
            for (child in children) {
                val parent = byName[child.countryCode to TextNorm.normalize(parentName)]?.singleOrNull()
                if (parent != null && parent.id != child.id) {
                    val i = rows.indexOfFirst { it.id == child.id }
                    rows[i] = child.copy(parentId = parent.id)
                }
            }
        }
        snapshot = rows.toList(); indexed = current
        return snapshot
    }

    private companion object {
        val containment = mapOf(
            "Brunello di Montalcino" to "Montalcino", "Rosso di Montalcino" to "Montalcino",
            "Alexander Valley" to "Sonoma County", "Russian River Valley" to "Sonoma County",
            "Oakville" to "Napa Valley", "Rutherford" to "Napa Valley",
            "Dundee Hills" to "Willamette Valley", "Cotes du Rhone Villages" to "Cotes du Rhone",
            "Haut-Medoc" to "Medoc",
        )
    }
}
