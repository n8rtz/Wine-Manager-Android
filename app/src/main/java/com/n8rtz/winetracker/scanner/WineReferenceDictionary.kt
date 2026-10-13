package com.n8rtz.winetracker.scanner

import com.n8rtz.winetracker.data.local.WineDefinition
import java.text.Normalizer
import java.util.Locale

internal enum class WineTermKind {
    PRODUCER,
    WINE_NAME,
    GRAPE,
    APPELLATION,
    WINE_STYLE,
    COUNTRY,
    REGION,
    SUBREGION,
    WINE_TYPE,
    LABEL_MARKER
}

internal data class WineLexiconEntry(
    val canonical: String,
    val kind: WineTermKind,
    val aliases: List<String> = emptyList(),
    val country: String? = null,
    val region: String? = null,
    val subregion: String? = null,
    val wineType: String? = null,
    val maker: String? = null,
    val sourceWeight: Int = 0,
    val useAsName: Boolean = false
)

internal data class WineOcrToken(
    val text: String,
    val key: String,
    val start: Int,
    val end: Int,
    val lineIndex: Int
)

internal data class WineLexiconMatch(
    val startToken: Int,
    val endTokenExclusive: Int,
    val startOffset: Int,
    val endOffset: Int,
    val entry: WineLexiconEntry,
    val editDistance: Int,
    val similarity: Double
)

internal data class WineDictionaryCorrection(
    val correctedText: String,
    val tokens: List<WineOcrToken>,
    val matches: List<WineLexiconMatch>
)

/**
 * Local wine vocabulary used to repair plausible OCR errors without sending label text anywhere.
 * It combines broad wine terminology with every definition retained in the user's Room database.
 */
internal class WineReferenceDictionary private constructor(entries: List<WineLexiconEntry>) {
    private data class AliasCandidate(
        val entry: WineLexiconEntry,
        val key: String
    )

    private data class CandidateMatch(
        val candidate: AliasCandidate,
        val startToken: Int,
        val endTokenExclusive: Int,
        val distance: Int,
        val similarity: Double
    )

    private val candidatesByLength: Map<Int, List<AliasCandidate>> = entries
        .flatMap { entry ->
            (listOf(entry.canonical) + entry.aliases).mapNotNull { alias ->
                val key = normalizedKey(alias)
                if (key.length < MIN_TERM_LENGTH) null else AliasCandidate(entry, key)
            }
        }
        .distinctBy { candidate ->
            listOf(
                candidate.entry.kind.name,
                candidate.entry.canonical,
                candidate.key,
                candidate.entry.country.orEmpty(),
                candidate.entry.region.orEmpty(),
                candidate.entry.subregion.orEmpty(),
                candidate.entry.maker.orEmpty()
            ).joinToString("|")
        }
        .groupBy { it.key.length }
    private val candidateLengths = candidatesByLength.keys.sorted()

    fun correct(text: String): WineDictionaryCorrection {
        val tokens = TOKEN_REGEX.findAll(text).map { match ->
            WineOcrToken(
                text = match.value,
                key = normalizedKey(match.value),
                start = match.range.first,
                end = match.range.last + 1,
                lineIndex = text.substring(0, match.range.first).count { it == '\n' }
            )
        }.toList()

        if (tokens.isEmpty() || candidatesByLength.isEmpty()) {
            return WineDictionaryCorrection(text, tokens, emptyList())
        }

        val matches = mutableListOf<WineLexiconMatch>()
        var tokenIndex = 0
        while (tokenIndex < tokens.size) {
            val best = bestMatchAt(tokens, tokenIndex)
            if (best == null) {
                tokenIndex++
            } else {
                val start = tokens[best.startToken]
                val end = tokens[best.endTokenExclusive - 1]
                matches += WineLexiconMatch(
                    startToken = best.startToken,
                    endTokenExclusive = best.endTokenExclusive,
                    startOffset = start.start,
                    endOffset = end.end,
                    entry = best.candidate.entry,
                    editDistance = best.distance,
                    similarity = best.similarity
                )
                tokenIndex = best.endTokenExclusive
            }
        }

        return WineDictionaryCorrection(
            correctedText = applyMatches(text, matches),
            tokens = tokens,
            matches = matches
        )
    }

    private fun bestMatchAt(tokens: List<WineOcrToken>, startToken: Int): CandidateMatch? {
        var best: CandidateMatch? = null
        val lastTokenExclusive = minOf(tokens.size, startToken + MAX_PHRASE_TOKENS)
        var combinedKey = ""

        for (endTokenExclusive in (startToken + 1)..lastTokenExclusive) {
            combinedKey += tokens[endTokenExclusive - 1].key
            if (combinedKey.length < MIN_TERM_LENGTH) continue

            val candidateLengths = candidateLengthsToCheck(combinedKey.length)
            for (candidateLength in candidateLengths) {
                for (candidate in candidatesByLength[candidateLength].orEmpty()) {
                    val maxEdits = maximumEditDistance(candidate.key.length)
                    if (kotlin.math.abs(combinedKey.length - candidate.key.length) > maxEdits) continue
                    val distance = levenshteinDistance(combinedKey, candidate.key, maxEdits)
                    if (distance > maxEdits) continue
                    val similarity = 1.0 - distance.toDouble() / maxOf(combinedKey.length, candidate.key.length)
                    if (distance > 0 && similarity < MIN_FUZZY_SIMILARITY) continue

                    val match = CandidateMatch(
                        candidate = candidate,
                        startToken = startToken,
                        endTokenExclusive = endTokenExclusive,
                        distance = distance,
                        similarity = similarity
                    )
                    if (best == null || compareMatches(match, best) > 0) best = match
                }
            }
        }
        return best
    }

    private fun compareMatches(left: CandidateMatch, right: CandidateMatch): Int {
        compareValues(left.similarity, right.similarity).takeIf { it != 0 }?.let { return it }
        compareValues(kindPriority(left.candidate.entry.kind), kindPriority(right.candidate.entry.kind))
            .takeIf { it != 0 }?.let { return it }
        compareValues(left.endTokenExclusive - left.startToken, right.endTokenExclusive - right.startToken)
            .takeIf { it != 0 }?.let { return it }
        compareValues(left.candidate.entry.sourceWeight, right.candidate.entry.sourceWeight)
            .takeIf { it != 0 }?.let { return it }
        return compareValues(right.distance, left.distance)
    }

    private fun candidateLengthsToCheck(inputLength: Int): List<Int> = candidateLengths.filter { candidateLength ->
        kotlin.math.abs(inputLength - candidateLength) <= maximumEditDistance(candidateLength)
    }

    private fun applyMatches(text: String, matches: List<WineLexiconMatch>): String {
        if (matches.isEmpty()) return text
        return buildString {
            var cursor = 0
            matches.forEach { match ->
                if (match.startOffset < cursor) return@forEach
                append(text, cursor, match.startOffset)
                append(match.entry.canonical)
                cursor = match.endOffset
            }
            append(text, cursor, text.length)
        }
    }

    private fun kindPriority(kind: WineTermKind): Int = when (kind) {
        WineTermKind.WINE_NAME -> 100
        WineTermKind.PRODUCER -> 90
        WineTermKind.APPELLATION -> 80
        WineTermKind.GRAPE -> 75
        WineTermKind.WINE_STYLE -> 70
        WineTermKind.SUBREGION -> 55
        WineTermKind.REGION -> 45
        WineTermKind.COUNTRY -> 35
        WineTermKind.WINE_TYPE -> 25
        WineTermKind.LABEL_MARKER -> 15
    }

    companion object {
        private const val MIN_TERM_LENGTH = 3
        private const val MAX_PHRASE_TOKENS = 7
        private const val MIN_FUZZY_SIMILARITY = 0.76
        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        private val builtInDictionary by lazy { build(emptyList(), emptyMap()) }

        fun builtIn(): WineReferenceDictionary = builtInDictionary

        fun forCellar(
            definitions: List<WineDefinition>,
            locationData: Map<String, Map<String, List<String>>>
        ): WineReferenceDictionary = build(definitions, locationData)

        private fun build(
            definitions: List<WineDefinition>,
            locationData: Map<String, Map<String, List<String>>>
        ): WineReferenceDictionary {
            val entries = mutableListOf<WineLexiconEntry>()
            entries += grapeEntries()
            entries += styleEntries()
            entries += producerEntries()
            entries += appellationEntries()
            entries += labelMarkerEntries()
            entries += wineTypeEntries()

            locationData.forEach { (country, regions) ->
                entries += WineLexiconEntry(country, WineTermKind.COUNTRY, sourceWeight = 2)
                regions.forEach { (region, subregions) ->
                    entries += WineLexiconEntry(
                        canonical = region,
                        kind = WineTermKind.REGION,
                        country = country,
                        region = region,
                        sourceWeight = 2
                    )
                    subregions.forEach { subregion ->
                        entries += WineLexiconEntry(
                            canonical = subregion,
                            kind = WineTermKind.SUBREGION,
                            country = country,
                            region = region,
                            subregion = subregion,
                            sourceWeight = 2
                        )
                    }
                }
            }

            val distinctMakers = definitions
                .mapNotNull { it.maker.trim().takeIf(String::isNotEmpty) }
                .distinctBy { normalizedKey(it) }
            val uniqueFirstWords = distinctMakers
                .groupBy { normalizedKey(it.substringBefore(' ')) }
                .filter { (firstWord, makers) -> firstWord.length >= 5 && makers.size == 1 }
                .keys

            definitions.filter { it.name.trim().isNotEmpty() }
                .groupBy { normalizedKey(it.name.trim()) }
                .values
                .forEach { sameNameDefinitions ->
                    val exemplar = sameNameDefinitions.first()
                    fun uniqueValue(values: List<String?>): String? = values
                        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                        .distinctBy { normalizedKey(it) }
                        .singleOrNull()
                    entries += WineLexiconEntry(
                        canonical = exemplar.name.trim(),
                        kind = WineTermKind.WINE_NAME,
                        country = uniqueValue(sameNameDefinitions.map { it.country }),
                        region = uniqueValue(sameNameDefinitions.map { it.region }),
                        subregion = uniqueValue(sameNameDefinitions.map { it.subregion }),
                        wineType = uniqueValue(sameNameDefinitions.map { it.type }),
                        maker = uniqueValue(sameNameDefinitions.map { it.maker }),
                        sourceWeight = 20,
                        useAsName = true
                    )
                }

            distinctMakers.forEach { maker ->
                val makerAliases = maker.substringBefore(' ')
                    .takeIf { normalizedKey(it) in uniqueFirstWords }
                    ?.let { listOf(it) }
                    .orEmpty()
                entries += WineLexiconEntry(
                    canonical = maker,
                    kind = WineTermKind.PRODUCER,
                    aliases = makerAliases,
                    sourceWeight = 20
                )
            }

            definitions.forEach { definition ->
                definition.type.trim().takeIf(String::isNotEmpty)?.let { type ->
                    entries += WineLexiconEntry(
                        canonical = type,
                        kind = WineTermKind.WINE_TYPE,
                        wineType = type,
                        sourceWeight = 20
                    )
                }
                definition.country.trim().takeIf(String::isNotEmpty)?.let { country ->
                    entries += WineLexiconEntry(
                        canonical = country,
                        kind = WineTermKind.COUNTRY,
                        country = country,
                        sourceWeight = 20
                    )
                }
                definition.region?.trim()?.takeIf(String::isNotEmpty)?.let { region ->
                    entries += WineLexiconEntry(
                        canonical = region,
                        kind = WineTermKind.REGION,
                        country = definition.country,
                        region = region,
                        sourceWeight = 20
                    )
                }
                definition.subregion?.trim()?.takeIf(String::isNotEmpty)?.let { subregion ->
                    entries += WineLexiconEntry(
                        canonical = subregion,
                        kind = WineTermKind.SUBREGION,
                        country = definition.country,
                        region = definition.region,
                        subregion = subregion,
                        sourceWeight = 20
                    )
                }
            }

            return WineReferenceDictionary(entries.distinctBy { entry ->
                listOf(
                    entry.kind.name,
                    normalizedKey(entry.canonical),
                    entry.country.orEmpty(),
                    entry.region.orEmpty(),
                    entry.subregion.orEmpty(),
                    entry.maker.orEmpty()
                ).joinToString("|")
            })
        }

        private fun grapeEntries(): List<WineLexiconEntry> = buildList {
            addGrapes(RED_GRAPES, "Red")
            addGrapes(WHITE_GRAPES, "White")
            addGrapes(OTHER_GRAPES, null)
        }

        private fun MutableList<WineLexiconEntry>.addGrapes(source: String, type: String?) {
            source.trimIndent().lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
                val pieces = line.split('|', limit = 2)
                val canonical = pieces.first().trim()
                val aliases = pieces.getOrNull(1)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                add(
                    WineLexiconEntry(
                        canonical = canonical,
                        kind = WineTermKind.GRAPE,
                        aliases = aliases,
                        wineType = type,
                        useAsName = true
                    )
                )
            }
        }

        private fun styleEntries(): List<WineLexiconEntry> = WINE_STYLES.trimIndent()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val pieces = line.split('|', limit = 2)
                val canonical = pieces.first().trim()
                val aliases = pieces.getOrNull(1)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                WineLexiconEntry(
                    canonical = canonical,
                    kind = WineTermKind.WINE_STYLE,
                    aliases = aliases,
                    wineType = styleType(canonical),
                    useAsName = true
                )
            }
            .toList()

        private fun producerEntries(): List<WineLexiconEntry> = PRODUCERS.trimIndent()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val pieces = line.split('|', limit = 2)
                WineLexiconEntry(
                    canonical = pieces.first().trim(),
                    kind = WineTermKind.PRODUCER,
                    aliases = pieces.getOrNull(1)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                    sourceWeight = 5
                )
            }
            .toList()

        private fun appellationEntries(): List<WineLexiconEntry> = APPELLATIONS.trimIndent()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val pieces = line.split('|')
                val canonical = pieces[0].trim()
                val country = pieces.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
                val region = pieces.getOrNull(2)?.trim()?.takeIf(String::isNotEmpty)
                val subregion = pieces.getOrNull(3)?.trim()?.takeIf(String::isNotEmpty) ?: canonical
                val type = pieces.getOrNull(4)?.trim()?.takeIf(String::isNotEmpty)
                val aliases = pieces.getOrNull(5)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                WineLexiconEntry(
                    canonical = canonical,
                    kind = WineTermKind.APPELLATION,
                    aliases = aliases,
                    country = country,
                    region = region,
                    subregion = subregion,
                    wineType = type,
                    sourceWeight = 10,
                    useAsName = true
                )
            }
            .toList()

        private fun labelMarkerEntries(): List<WineLexiconEntry> = LABEL_MARKERS.trimIndent()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { line ->
                val pieces = line.split('|', limit = 2)
                WineLexiconEntry(
                    canonical = pieces.first().trim(),
                    kind = WineTermKind.LABEL_MARKER,
                    aliases = pieces.getOrNull(1)?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                )
            }
            .toList()

        private fun wineTypeEntries(): List<WineLexiconEntry> = listOf(
            WineLexiconEntry("Red", WineTermKind.WINE_TYPE, aliases = listOf("Red Wine"), wineType = "Red"),
            WineLexiconEntry("White", WineTermKind.WINE_TYPE, aliases = listOf("White Wine"), wineType = "White"),
            WineLexiconEntry("Rose", WineTermKind.WINE_TYPE, aliases = listOf("Rosé", "Rose Wine"), wineType = "Rose"),
            WineLexiconEntry("Sparkling", WineTermKind.WINE_TYPE, aliases = listOf("Sparkling Wine"), wineType = "Sparkling"),
            WineLexiconEntry("Dessert", WineTermKind.WINE_TYPE, aliases = listOf("Dessert Wine", "Sweet Wine"), wineType = "Dessert")
        )

        private fun styleType(style: String): String? = when (normalizedKey(style)) {
            "champagne", "prosecco", "cava", "cremant", "franciacorta", "sekt", "sparkling" -> "Sparkling"
            "rose", "rosewine" -> "Rose"
            "sauternes", "icewine", "tokajiaszu", "port", "sherry", "madeira", "marsala", "vinsanto" -> "Dessert"
            else -> null
        }

        private fun maximumEditDistance(length: Int): Int = when {
            length <= 4 -> 0
            length <= 7 -> 1
            length <= 12 -> 2
            else -> (length / 6).coerceAtMost(4)
        }

        private fun levenshteinDistance(left: String, right: String, cutoff: Int): Int {
            if (kotlin.math.abs(left.length - right.length) > cutoff) return cutoff + 1
            var previous = IntArray(right.length + 1) { it }
            var current = IntArray(right.length + 1)
            for (leftIndex in left.indices) {
                current[0] = leftIndex + 1
                var rowMinimum = current[0]
                for (rightIndex in right.indices) {
                    val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                    current[rightIndex + 1] = minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        previous[rightIndex] + substitutionCost
                    )
                    rowMinimum = minOf(rowMinimum, current[rightIndex + 1])
                }
                if (rowMinimum > cutoff) return cutoff + 1
                val temporary = previous
                previous = current
                current = temporary
            }
            return previous[right.length]
        }

        private fun normalizedKey(value: String): String = Normalizer
            .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .filter { it.isLetterOrDigit() }

        private val RED_GRAPES = """
            Aglianico
            Alicante Bouschet|Garnacha Tintorera;Alicante
            Alvarelhão
            Tempranillo|Tinta Roriz;Aragonez;Tinta de Toro
            Aramon
            Baga
            Barbera
            Blauburger
            Blaufränkisch|Lemberger;Kékfrankos;Frankovka
            Bobal
            Bonarda|Charbono
            Brachetto
            Brancellao
            Cabernet Franc
            Cabernet Sauvignon
            Caladoc
            Canaiolo Nero
            Carignan|Cariñena;Mazuelo;Carignane
            Carménère|Carmenere
            Cinsault|Cinsaut
            Colorino
            Corvina
            Corvinone
            Counoise
            Dolcetto
            Dornfelder
            Duras
            Durif|Petite Sirah;Petite Syrah
            Feteasca Neagra|Fetească Neagră
            Fer Servadou|Braucol
            Frappato
            Freisa
            Fumin
            Gamay
            Garnacha|Grenache;Grenache Noir;Garnacha Tinta
            Graciano|Tinta Miuda
            Grand Noir de la Calmette
            Grignolino
            Kadarka
            Lagrein
            Lambrusco
            Listán Negro|Listan Negro
            Malbec|Cot
            Mavrud
            Mencía|Mencia
            Merlot
            Molinara
            Mondeuse Noire
            Montepulciano
            Mourvèdre|Mourvedre;Monastrell;Mataro
            Nebbiolo
            Nerello Cappuccio
            Nerello Mascalese
            Nero d'Avola|Nero d Avola;Calabrese
            Nero di Troia|Uva di Troia
            Nielluccio|Niellucciu
            País|Pais;Listan Prieto
            Petit Verdot
            Pinot Meunier
            Pinot Noir|Pinot Nero;Spätburgunder;Spatburgunder
            Plavac Mali
            Poulsard|Ploussard
            Primitivo|Zinfandel
            Refosco dal Peduncolo Rosso|Refosco
            Rondinella
            Sagrantino
            Sangiovese
            Schiava|Vernatsch;Trollinger
            Sciacarello|Sciaccarellu
            St. Laurent|Sankt Laurent;Saint Laurent
            Susumaniello
            Syrah|Shiraz;Sirah
            Tannat
            Teroldego
            Tinta Barroca
            Tinta Cão|Tinta Cao
            Tinta Francisca
            Touriga Franca
            Touriga Nacional
            Trincadeira|Tinta Amarela
            Trousseau|Bastardo
            Vranac
            Xinomavro
            Zweigelt|Blauer Zweigelt;Rotburger
            Abouriou
            Acolon
            Agiorgitiko
            Aleatico
            Alibernet
            Ancellotta
            Arinarnoa
            Baga
            Black Muscat|Moscato Nero
            Boğazkere|Bogazkere
            Bonda
            Cabernet Dorsa
            Cabernet Mitos
            Castelao|Castelão;Periquita
            Catawba
            Cesanese
            Chambourcin
            Charbono
            Corinto Nero
            Croatina|Bonarda Piemontese
            Crljenak Kaštelanski|Tribidrag
            Dobričić|Dobricic
            Espadeiro
            Früburgunder|Pinot Noir Précoce
            Gaglioppo
            Gamza
            Grignolino
            Heroldrebe
            Kotsifali
            Limnio
            Mandilaria
            Maratheftiko
            Marzemino
            Maturana Tinta
            Mavrotragano
            Mazuelo
            Melnik
            Misket Varnenski
            Négrette|Negrette
            Ner d'Avola
            Negroamaro
            Norton
            Oseleta
            Pelaverga
            Petit Bouschet
            Pineau d'Aunis|Chenin Noir
            Pinotage
            Plavac Mali
            Rara Neagra|Rară Neagră
            Regent
            Rondo
            Rufete
            Saperavi
            Sercial|Esgana Cão
            Sercialinho
            Souvignier Gris
            Tannat
            Tinta Negra|Negramoll
            Tinta Roriz
            Vaccarèse|Vaccarese
            Vespolina
            Vranac
            Xinomavro
        """

        private val WHITE_GRAPES = """
            Albariño|Alvarinho
            Albarín Blanco|Albarin Blanco
            Aligoté|Aligote
            Arinto
            Arneis
            Assyrtiko|Asyrtiko
            Auxerrois
            Bacchus
            Bical
            Catarratto
            Chardonnay
            Chasselas|Fendant;Gutedel
            Chenin Blanc|Pineau de la Loire
            Clairette
            Cortese
            Encruzado
            Fiano
            Fernão Pires|Maria Gomes
            Feteasca Alba|Fetească Albă
            Feteasca Regala|Fetească Regală
            Furmint
            Friulano|Tocai Friulano
            Garganega
            Gewürztraminer|Gewurztraminer;Traminer Aromatico
            Glera
            Godello
            Greco
            Grüner Veltliner|Gruner Veltliner;Veltliner
            Hondarrabi Zuri|Hondarribi Zuri
            Hárslevelű|Harslevelu
            Huxelrebe
            Inzolia|Ansonica
            Jacquère|Jacquere
            Kerner
            Koshu
            Loureiro
            Macabeo|Viura
            Malagousia|Malagouzia
            Malvasia Bianca
            Marsanne
            Melon de Bourgogne|Melon
            Misket
            Moschofilero
            Müller-Thurgau|Mueller-Thurgau
            Muscadelle
            Muscat|Moscato;Moscatel;Muscat Blanc
            Muscat Blanc à Petits Grains|Muscat à petits grains
            Muscat Ottonel
            Palomino
            Parellada
            Pedro Ximénez|Pedro Jimenez;PX
            Petit Manseng
            Picpoul Blanc|Piquepoul Blanc
            Pinot Blanc|Pinot Bianco;Weissburgunder
            Pinot Gris|Pinot Grigio;Grauburgunder
            Riesling|Rhine Riesling
            Rkatsiteli
            Roussanne
            Sauvignon Blanc|Fumé Blanc;Fume Blanc
            Savagnin|Naturé
            Scheurebe
            Sémillon|Semillon
            Silvaner|Sylvaner
            Solaris
            Souvignier Gris
            Sylvaner
            Tămâioasă Românească|Tamaioasa Romaneasca
            Torrontés|Torrontes
            Trebbiano|Ugni Blanc;Procanico
            Verdejo
            Verdelho
            Vermentino|Rolle;Favorita
            Viognier
            Welschriesling|Olaszrizling;Riesling Italico
            Xarel·lo|Xarel-lo;Pansa Blanca
            Zibibbo
            Albillo
            Arneis
            Bouvier
            Bual|Boal
            Cercial
            Colombard
            Duras Blanc
            Garganega
            Grillo
            Grüner Silvaner|Grüner Sylvaner
            Hárslevelű
            Hibernal
            Jacquez Blanc
            Kanzler
            Klevner
            Malvasia Fina
            Marsanne Blanche
            Mauzac
            Mtsvane|Kakhuri Mtsvane
            Ortrugo
            Pálava|Palava
            Pansa Rosada
            Picolit
            Pignoletto
            Pinot Auxerrois
            Pinot Meunier Blanc
            Ribolla Gialla|Rebula
            Roter Veltliner
            Sacy
            Saperavi Blanc
            Sercial
            Souvignier Gris
            Tressallier
            Verdejo Blanco
            Verdiso
            Viosinho
            Viura
            Welschriesling
            Xynisteri
            Zéta|Zeta
        """

        private val OTHER_GRAPES = """
            Cabernet Blanc
            Chambourcin
            Frontenac
            Marquette
            Muscat Hamburg
            Niagara
            Seyval Blanc
            Vidal Blanc
            Vignoles
            Villard Blanc
            Villard Noir
        """

        private val WINE_STYLES = """
            Amarone della Valpolicella|Amarone
            Beaujolais Nouveau
            Bordeaux Blend|Meritage
            Cava
            Champagne
            Chianti Classico
            Crémant|Cremant
            Franciacorta
            Ice Wine|Eiswein;Vin de glace
            Lambrusco
            Madeira
            Marsala
            Moscato d'Asti|Moscato d Asti
            Passito
            Port|Porto;Port Wine
            Prosecco
            Sauternes
            Sekt
            Sherry|Jerez;Xérès;Xeres
            Tokaji Aszú|Tokaji;Tokay
            Vin Santo
            Vinho Verde
            Orange Wine
            Pét-Nat|Pet Nat;Pétillant Naturel
            Crémant de Bourgogne
            Crémant d'Alsace
            Crémant de Loire
            Crémant de Limoux
            Brut Nature
            Blanc de Blancs
            Blanc de Noirs
            Rosé|Rose Wine
        """

        private val PRODUCERS = """
            Kirkland Signature|Kirkland;Kirkland Wines;Costco
            Château Ste. Michelle|Chateau Ste Michelle;Ste Michelle;Chateau St Michelle
            Robert Mondavi|Mondavi
            Bogle Vineyards|Bogle
            Kendall-Jackson|Kendall Jackson;KJ
            La Crema
            Duckhorn Vineyards|Duckhorn
            Caymus Vineyards|Caymus
            Silver Oak Cellars|Silver Oak
            Opus One
            Domaine de la Romanée-Conti|DRC;Domaine de la Romanee Conti
            Louis Jadot|Maison Louis Jadot
            Joseph Drouhin|Maison Joseph Drouhin
            Bouchard Père & Fils|Bouchard Pere et Fils
            E. Guigal|Guigal
            M. Chapoutier|Chapoutier
            Antinori|Marchesi Antinori
            Frescobaldi|Marchesi de' Frescobaldi
            Castello Banfi|Banfi
            Gaja
            Concha y Toro|Concha Y Toro;Casillero del Diablo
            Catena Zapata|Catena
            Penfolds
            Cloudy Bay
            Oyster Bay
            Kim Crawford
            Santa Margherita
            Ruffino
            Masi
            Meiomi
            Barefoot Cellars|Barefoot
            19 Crimes
            Josh Cellars|Josh Wine
            Decoy|Duckhorn Decoy
            Orin Swift|Orin Swift Cellars
            Rombauer Vineyards|Rombauer
            Heitz Cellar|Heitz
            Stag's Leap Wine Cellars|Stags Leap
            Schrader Cellars|Schrader
            Kistler Vineyards|Kistler
            Ridge Vineyards|Ridge
            Frog's Leap Winery|Frogs Leap
            Chateau Montelena|Château Montelena
            Silver Palm|Silver Palm Wines
            Chateau Ste. Michelle|Chateau Ste Michelle
            Veuve Clicquot|Veuve
            Moët & Chandon|Moet Chandon;Moet
            Dom Pérignon|Dom Perignon
            Taittinger
            Bollinger
            Louis Roederer
            Krug
            Ruinart
            Nicolas Feuillatte
            La Marca
            Mionetto
            J. Lohr
            St. Francis Winery|Saint Francis Winery
            Eberle Winery|Eberle
            Bonterra Organic Estates|Bonterra
            Bread & Butter Wines|Bread and Butter
            Prisoner Wine Company|The Prisoner
            Justin Vineyards & Winery|Justin
            Francis Ford Coppola Winery|Coppola
            Columbia Crest
            Chateau Ste. Michelle|Chateau Ste Michelle
            Ruffino
            Villa Maria
            Cloudy Bay Vineyards
            Torbreck Vintners|Torbreck
            Yalumba
            Henschke
            Leeuwin Estate
            Vasse Felix
            Catena Alta
            Montes|Montes Alpha
            Santa Rita|Santa Rita Estates
            Viña Concha y Toro|Vina Concha y Toro
            Bodega Catena Zapata
        """

        private val LABEL_MARKERS = """
            Origine Controllata|Denominazione di Origine Controllata;D.O.C.;DOC
            Origine Controllata e Garantita|Denominazione di Origine Controllata e Garantita;D.O.C.G.;DOCG
            Denominación de Origen Calificada|Denominacion de Origen Calificada;DOCa;DOCA
            Denominación de Origen|Denominacion de Origen;D.O.;DO
            Appellation d'Origine Contrôlée|Appellation d Origine Controlee;A.O.C.;AOC
            Appellation d'Origine Protégée|Appellation d Origine Protegee;A.O.P.;AOP
            Denominação de Origem Controlada|Denominacao de Origem Controlada
            Indicazione Geografica Tipica|I.G.T.;IGT
            Denominazione di Origine Protetta|DOP;PDO;Denominação de Origem Protegida;Protected Designation of Origin
            Protected Geographical Indication|Indicación Geográfica Protegida;Indication Géographique Protégée;IGP;PGI
            American Viticultural Area|AVA
            Vin de Qualité Supérieure|VDQS
            Vino de Pago
            Vin de Pays
            Vino da Tavola
            Vin de France
            Vin de Table
        """

        private val APPELLATIONS = """
            Brunello di Montalcino|Italy|Tuscany|Brunello di Montalcino|Red|Brunello Montalcino;Brunello di Montalcinu
            Rosso di Montalcino|Italy|Tuscany|Rosso di Montalcino|Red
            Barolo|Italy|Piedmont|Barolo|Red
            Barbaresco|Italy|Piedmont|Barbaresco|Red
            Chianti Classico|Italy|Tuscany|Chianti Classico|Red
            Chianti|Italy|Tuscany|Chianti|Red
            Vino Nobile di Montepulciano|Italy|Tuscany|Vino Nobile di Montepulciano|Red
            Bolgheri|Italy|Tuscany|Bolgheri|Red
            Amarone della Valpolicella|Italy|Veneto|Amarone della Valpolicella|Red
            Valpolicella Ripasso|Italy|Veneto|Valpolicella Ripasso|Red
            Valpolicella|Italy|Veneto|Valpolicella|Red
            Prosecco|Italy|Veneto|Prosecco|Sparkling
            Soave|Italy|Veneto|Soave|White
            Gavi|Italy|Piedmont|Gavi|White
            Barbera d'Alba|Italy|Piedmont|Barbera d'Alba|Red
            Barbera d'Asti|Italy|Piedmont|Barbera d'Asti|Red
            Franciacorta|Italy|Lombardy|Franciacorta|Sparkling
            Etna Rosso|Italy|Sicily|Etna Rosso|Red
            Cerasuolo di Vittoria|Italy|Sicily|Cerasuolo di Vittoria|Red
            Aglianico del Vulture|Italy|Basilicata|Aglianico del Vulture|Red
            Taurasi|Italy|Campania|Taurasi|Red
            Sagrantino di Montefalco|Italy|Umbria|Sagrantino di Montefalco|Red
            Vernaccia di San Gimignano|Italy|Tuscany|Vernaccia di San Gimignano|White
            Chablis|France|Burgundy|Chablis|White
            Gevrey-Chambertin|France|Burgundy|Gevrey-Chambertin|Red
            Chambolle-Musigny|France|Burgundy|Chambolle-Musigny|Red
            Vosne-Romanée|France|Burgundy|Vosne-Romanée|Red
            Meursault|France|Burgundy|Meursault|White
            Puligny-Montrachet|France|Burgundy|Puligny-Montrachet|White
            Pouilly-Fuissé|France|Burgundy|Pouilly-Fuissé|White
            Côte de Nuits|France|Burgundy|Côte de Nuits|Red
            Côte de Beaune|France|Burgundy|Côte de Beaune|Red
            Pauillac|France|Bordeaux|Pauillac|Red
            Margaux|France|Bordeaux|Margaux|Red
            Saint-Émilion|France|Bordeaux|Saint-Émilion|Red|St Emilion;St. Emilion
            Pomerol|France|Bordeaux|Pomerol|Red
            Saint-Estèphe|France|Bordeaux|Saint-Estèphe|Red|St Estephe;Saint Estephe
            Saint-Julien|France|Bordeaux|Saint-Julien|Red|St Julien
            Pessac-Léognan|France|Bordeaux|Pessac-Léognan|Red
            Sauternes|France|Bordeaux|Sauternes|Dessert
            Haut-Médoc|France|Bordeaux|Haut-Médoc|Red
            Médoc|France|Bordeaux|Médoc|Red|Medoc
            Graves|France|Bordeaux|Graves|Red
            Châteauneuf-du-Pape|France|Rhone|Châteauneuf-du-Pape|Red|Chateauneuf du Pape;Chateauneuf-du-Pape
            Côte-Rôtie|France|Rhone|Côte-Rôtie|Red|Cote Rotie
            Hermitage|France|Rhone|Hermitage|Red
            Crozes-Hermitage|France|Rhone|Crozes-Hermitage|Red|Crozes Hermitage
            Gigondas|France|Rhone|Gigondas|Red
            Condrieu|France|Rhone|Condrieu|White
            Sancerre|France|Loire|Sancerre|White
            Pouilly-Fumé|France|Loire|Pouilly-Fumé|White|Pouilly Fume
            Vouvray|France|Loire|Vouvray|White
            Chinon|France|Loire|Chinon|Red
            Champagne|France|Champagne|Champagne|Sparkling
            Beaujolais|France|Beaujolais|Beaujolais|Red
            Morgon|France|Beaujolais|Morgon|Red
            Fleurie|France|Beaujolais|Fleurie|Red
            Côtes du Rhône|France|Rhone|Côtes du Rhône|Red|Cotes du Rhone
            Bandol|France|Provence|Bandol|Red
            Muscadet|France|Loire|Muscadet|White
            Rioja|Spain|Rioja|Rioja|Red
            Ribera del Duero|Spain|Castilla y Leon|Ribera del Duero|Red
            Priorat|Spain|Catalonia|Priorat|Red
            Rías Baixas|Spain|Galicia|Rías Baixas|White|Rias Baixas
            Rueda|Spain|Castilla y Leon|Rueda|White
            Toro|Spain|Castilla y Leon|Toro|Red
            Cava|Spain|Catalonia|Cava|Sparkling
            Jerez-Xérès-Sherry|Spain|Andalusia|Jerez-Xérès-Sherry|Dessert|Jerez;Xeres;Sherry
            Penedès|Spain|Catalonia|Penedès|White|Penedes
            Navarra|Spain|Navarra|Navarra|Red
            Ribera del Guadiana|Spain|Extremadura|Ribera del Guadiana|Red
            Douro|Portugal|Douro|Douro|Red
            Porto|Portugal|Douro|Porto|Dessert|Port
            Vinho Verde|Portugal|Minho|Vinho Verde|White
            Dão|Portugal|Dao|Dão|Red|Dao
            Alentejo|Portugal|Alentejo|Alentejo|Red
            Madeira|Portugal|Madeira|Madeira|Dessert
            Mosel|Germany|Mosel|Mosel|White
            Rheingau|Germany|Rheingau|Rheingau|White
            Rheinhessen|Germany|Rheinhessen|Rheinhessen|White
            Pfalz|Germany|Pfalz|Pfalz|White
            Nahe|Germany|Nahe|Nahe|White
            Santorini|Greece|Aegean Islands|Santorini|White
            Nemea|Greece|Peloponnese|Nemea|Red
            Kakheti|Georgia|Kakheti|Kakheti|Red
            Napa Valley|California|North Coast|Napa Valley|Red|Napa
            Sonoma Coast|California|North Coast|Sonoma Coast|Red
            Alexander Valley|California|North Coast|Alexander Valley|Red
            Russian River Valley|California|North Coast|Russian River Valley|Red
            Oakville|California|North Coast|Oakville|Red
            Rutherford|California|North Coast|Rutherford|Red
            Paso Robles|California|Central Coast|Paso Robles|Red
            Santa Barbara|California|Central Coast|Santa Barbara|Red
            Santa Ynez Valley|California|Central Coast|Santa Ynez Valley|Red
            Willamette Valley|Oregon|Willamette Valley|Willamette Valley|Red
            Dundee Hills|Oregon|Willamette Valley|Dundee Hills|Red
            Walla Walla Valley|Washington|Columbia Valley|Walla Walla Valley|Red
            Red Mountain|Washington|Columbia Valley|Red Mountain|Red
            Barossa Valley|Australia|South Australia|Barossa Valley|Red
            McLaren Vale|Australia|South Australia|McLaren Vale|Red
            Margaret River|Australia|Western Australia|Margaret River|Red
            Coonawarra|Australia|South Australia|Coonawarra|Red
            Yarra Valley|Australia|Victoria|Yarra Valley|Red
            Hunter Valley|Australia|New South Wales|Hunter Valley|Red
            Marlborough|New Zealand|Marlborough|Marlborough|White
            Central Otago|New Zealand|Central Otago|Central Otago|Red
            Hawke's Bay|New Zealand|Hawke's Bay|Hawke's Bay|Red
            Stellenbosch|South Africa|Coastal Region|Stellenbosch|Red
            Swartland|South Africa|Coastal Region|Swartland|Red
            Constantia|South Africa|Coastal Region|Constantia|White
            Colchagua Valley|Chile|Central Valley|Colchagua Valley|Red
            Maipo Valley|Chile|Central Valley|Maipo Valley|Red
            Casablanca Valley|Chile|Aconcagua|Casablanca Valley|White
            Uco Valley|Argentina|Mendoza|Uco Valley|Red
            Mendoza|Argentina|Mendoza|Mendoza|Red
        """
    }
}
