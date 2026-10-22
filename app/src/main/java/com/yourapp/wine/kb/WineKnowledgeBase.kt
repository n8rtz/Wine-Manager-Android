package com.yourapp.wine.kb

import com.yourapp.wine.merge.TextNorm
import java.io.InputStream

/** No coordinates: a deliberately small hierarchical gazetteer record. */
data class Place(val id: Long, val name: String, val type: String, val parentId: Long?, val countryCode: String) {
    val nameNorm: String get() = TextNorm.normalize(name)
}

/** No coordinates: producer identities retain display accents; aliases are matching-only. */
data class Producer(val id: Long, val name: String, val countryCode: String, val regionId: Long?, val aliases: List<String> = emptyList()) {
    val nameNorm: String get() = TextNorm.normalize(name)
}

data class Cuvee(val producerId: Long, val name: String)
data class ProducerHit(val producer: Producer, val matchedNorm: String, val editDistance: Int?)
data class CuveeHit(val cuvee: Cuvee, val editDistance: Int)
data class KnowledgeSnapshot(val places: List<Place>, val producers: List<Producer>, val cuvees: List<Cuvee>)

interface WineKnowledgeBase {
    /** No coordinates: returns the local gazetteer, not a remote or worldwide wine catalog. */
    fun places(): List<Place>
    /** No coordinates: FTS shortlist of at most 25 identities, reranked with bounded edit distance. */
    fun findProducer(text: String): List<ProducerHit>
    /** Bounded typo-tolerant shortlist; lexical acceptance remains the caller's responsibility. */
    fun findProducerFuzzy(text:String):List<ProducerHit> = findProducer(text)
    /** No coordinates: matches only cuvées belonging to this producer, at edit distance at most two. */
    fun findCuvee(producerId: Long, text: String): CuveeHit?
    /** Preserve competing cuvee matches, scoped to one confirmed producer. */
    fun findCuvees(producerId:Long,text:String):List<CuveeHit> = listOfNotNull(findCuvee(producerId,text))
}

interface OpenWinesImporter {
    /** No coordinates: imports the documented wine-kb/v1 JSON or CSV adapter schema transactionally. */
    fun importOpenWines(csvOrJson: InputStream)
}

object KbMatching {
    /** One FTS query, capped prefix expansion for a missing first letter; not a database-wide fuzzy scan. */
    fun fuzzyPrefixes(text:String):List<String> {
        val words=Regex("[\\p{L}\\p{N}]{3,}").findAll(TextNorm.normalize(text)).map { it.value }.take(6).toList()
        return (words.map { it.take(4) }+words.filter { it.length>=5 }.take(2).flatMap { word ->
            ('a'..'z').flatMap { listOf(it+word.take(3),it+word.drop(1).take(3)) }+word.drop(1).take(4)
        }).distinct().take(120)
    }
    /** No coordinates: reranks the real FTS shortlist; aliases use the same NFKD matching policy. */
    fun rank(query: String, shortlist: List<Producer>): List<ProducerHit> {
        val normalized = TextNorm.normalize(query)
        return shortlist.take(25).map { producer ->
            val matches = (listOf(producer.nameNorm) + producer.aliases.map(TextNorm::normalize)).map { norm ->
                ProducerHit(producer, norm, TextNorm.editDistance(normalized, norm))
            }
            matches.minWith(compareBy<ProducerHit> { it.editDistance ?: Int.MAX_VALUE }.thenBy { it.matchedNorm.length })
        }.sortedWith(compareBy<ProducerHit> { it.editDistance ?: Int.MAX_VALUE }.thenBy { it.producer.id })
    }
}
