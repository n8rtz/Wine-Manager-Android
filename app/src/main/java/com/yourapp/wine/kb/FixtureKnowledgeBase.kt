package com.yourapp.wine.kb

import com.yourapp.wine.merge.TextNorm

/** Source-authored test fixture, not an OpenWines dump or a claim of worldwide coverage. */
class FixtureKnowledgeBase(private val data: KnowledgeSnapshot = snapshot) : WineKnowledgeBase {
    /** No coordinates: fixture places are also used to seed the real bundled-SQLite database. */
    override fun places(): List<Place> = data.places

    /** No coordinates: in-memory fixture shortlist for tests/degraded fallback only; normal production uses real FTS5. */
    override fun findProducer(text: String): List<ProducerHit> {
        val words = TextNorm.normalize(text).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }.map { it.take(4) }
        val shortlist = data.producers.filter { p ->
            (listOf(p.nameNorm) + p.aliases.map(TextNorm::normalize)).any { name ->
                name.split(' ').any { term -> words.any { term.startsWith(it) } }
            }
        }
        return KbMatching.rank(text, shortlist)
    }

    override fun findProducerFuzzy(text:String):List<ProducerHit> {
        val prefixes=KbMatching.fuzzyPrefixes(text)
        return KbMatching.rank(text,data.producers.filter { p -> (listOf(p.nameNorm)+p.aliases.map(TextNorm::normalize)).any { name ->
            name.split(' ').any { word -> prefixes.any(word::startsWith) }
        } })
    }
    override fun findCuvees(producerId:Long,text:String):List<CuveeHit> = data.cuvees.filter { it.producerId==producerId }.mapNotNull {
        TextNorm.editDistance(TextNorm.normalize(text),TextNorm.normalize(it.name))?.let { distance -> CuveeHit(it,distance) }
    }.sortedBy { it.editDistance }.take(8)

    /** No coordinates: returns only a fixture cuvée for the supplied producer. */
    override fun findCuvee(producerId: Long, text: String): CuveeHit? = data.cuvees
        .filter { it.producerId == producerId }.mapNotNull { cuvee ->
            TextNorm.editDistance(TextNorm.normalize(text), TextNorm.normalize(cuvee.name))?.let { CuveeHit(cuvee, it) }
        }.minByOrNull { it.editDistance }

    companion object {
        val snapshot = KnowledgeSnapshot(
            places = listOf(
                Place(1, "France", "country", null, "FR"),
                Place(2, "Bordeaux", "region", 1, "FR"),
                Place(3, "Pauillac", "appellation", 2, "FR"),
                Place(4, "Margaux", "appellation", 2, "FR"),
                Place(5, "Saint-Émilion", "appellation", 2, "FR"),
                Place(6, "Médoc", "subregion", 2, "FR"),
                Place(7, "Burgundy", "region", 1, "FR"),
                Place(8, "Chablis", "appellation", 7, "FR"),
                Place(9, "Champagne", "region", 1, "FR"),
                Place(10, "Rhône", "region", 1, "FR"),
                Place(11, "Châteauneuf-du-Pape", "appellation", 10, "FR"),
                Place(12, "Italy", "country", null, "IT"),
                Place(13, "Piedmont", "region", 12, "IT"),
                Place(14, "Barolo", "appellation", 13, "IT"),
                Place(15, "Barbaresco", "appellation", 13, "IT"),
                Place(16, "Tuscany", "region", 12, "IT"),
                Place(17, "Chianti Classico", "appellation", 16, "IT"),
                Place(18, "United States", "country", null, "US"),
                Place(19, "California", "region", 18, "US"),
                Place(20, "Napa Valley", "subregion", 19, "US"),
                Place(21, "Oakville", "appellation", 20, "US"),
                Place(22, "Sonoma County", "subregion", 19, "US"),
                Place(23, "Spain", "country", null, "ES"),
                Place(24, "Rioja", "region", 23, "ES"),
                Place(25, "Ribera del Duero", "region", 23, "ES"),
                Place(26, "Portugal", "country", null, "PT"),
                Place(27, "Douro", "region", 26, "PT"),
            ),
            producers = listOf(
                Producer(1, "Château Margaux", "FR", 2, listOf("chateau margaux")),
                Producer(2, "Château Latour", "FR", 2),
                Producer(3, "Château Lafite Rothschild", "FR", 2),
                Producer(4, "Vietti", "IT", 13),
                Producer(5, "Gaja", "IT", 13),
                Producer(6, "Robert Mondavi", "US", 19, listOf("robert mondavi winery")),
                Producer(7, "Opus One", "US", 19),
                Producer(8, "Marqués de Riscal", "ES", 24),
                Producer(9, "Antinori", "IT", 16),
                Producer(10, "Louis Jadot", "FR", 7),
            ),
            cuvees = listOf(Cuvee(1, "Pavillon Rouge"), Cuvee(1, "Pavillon Blanc"),
                Cuvee(4, "Castiglione"), Cuvee(6, "Reserve Cabernet Sauvignon"), Cuvee(9, "Tignanello")),
        )
    }
}
