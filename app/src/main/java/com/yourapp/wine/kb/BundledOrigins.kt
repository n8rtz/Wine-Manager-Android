package com.yourapp.wine.kb

/** Legacy vocabulary plus a generated reference overlay; the legacy source string remains parser-compatible. */
object BundledOrigins {
    private val legacyTerms: List<WineOriginTerm> by lazy { source.trimIndent().lineSequence().map(String::trim).filter(String::isNotEmpty).map { line ->
        val parts = line.split('|')
        WineOriginTerm(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts.getOrNull(5)?.split(';')?.map(String::trim).orEmpty())
    }.toList() }

    private val legacyIndex by lazy { legacyTerms.associateBy { it.country to com.yourapp.wine.merge.TextNorm.normalize(it.name) } }
    fun legacy(name: String, country: String): WineOriginTerm? = legacyIndex[country to com.yourapp.wine.merge.TextNorm.normalize(name)]

    // Catalog overrides may remove an unjustified single region (e.g. cross-state AVAs or Prosecco).
    val terms: List<WineOriginTerm> by lazy {
        (ReferenceWineCatalog.terms.map { term -> term.copy(aliases =
            (term.aliases + legacy(term.name, term.country)?.aliases.orEmpty()).distinct()) } + legacyTerms).distinctBy { it.country to com.yourapp.wine.merge.TextNorm.normalize(it.name) }
    }

    private val countryCodes = mapOf("France" to "FR", "Italy" to "IT", "Spain" to "ES", "Portugal" to "PT",
        "Germany" to "DE", "Greece" to "GR", "Georgia" to "GE", "United States" to "US",
        "Australia" to "AU", "New Zealand" to "NZ", "South Africa" to "ZA", "Chile" to "CL", "Argentina" to "AR") + ReferenceWineCatalog.countryCodes
    /** No coordinates: ISO codes for the countries already present in the authored table. */
    fun countryCode(name: String): String? = countryCodes[name]
    private val aliases by lazy {
        val result = mutableMapOf<Pair<String,String>, MutableSet<String>>()
        fun add(code: String, forms: List<String>) {
            forms.filter(String::isNotBlank).forEach { form -> result.getOrPut(code to com.yourapp.wine.merge.TextNorm.normalize(form)) { linkedSetOf() }.addAll(forms) }
        }
        terms.forEach { term -> countryCode(term.country)?.let { code ->
            add(code, listOf(term.country)); add(code, listOf(term.region)); add(code, listOf(term.name) + term.aliases)
        } }
        result.mapValues { it.value.toList() }
    }
    /** No coordinates: only catalogued forms for this name/country; unknown imported places remain untouched. */
    fun spellingForms(place: Place): List<String> = if (place.type == "country") {
        countryCodes.filterValues { it == place.countryCode }.keys.toList()+countryLabelForms[place.countryCode].orEmpty()
    } else aliases[place.countryCode to place.nameNorm].orEmpty()

    // Exact native-language country names only; no demonym/country inference or fuzzy matching.
    private val countryLabelForms=mapOf(
        "ES" to listOf("España","Espagne"), "IT" to listOf("Italia","Italie"),
        "DE" to listOf("Deutschland","Allemagne"), "FR" to listOf("Francia"),
        "AT" to listOf("Österreich","Autriche"), "PT" to listOf("Portugal")
    )

    val source = """
        Brunello di Montalcino|Italy|Tuscany|Brunello di Montalcino|Red|Brunello Montalcino;Brunello di Montalcinu;Brunello di
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
        Côtes du Rhône Villages|France|Rhone|Côtes du Rhône Villages|Red|Cotes du Rhone Villages
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
        Jumilla|Spain|Murcia|Jumilla|Red
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
        Napa Valley|United States|California|Napa Valley|-|Napa
        Sonoma Coast|United States|California|Sonoma Coast|-
        Alexander Valley|United States|California|Alexander Valley|-
        Russian River Valley|United States|California|Russian River Valley|-
        Oakville|United States|California|Oakville|-
        Rutherford|United States|California|Rutherford|-
        Paso Robles|United States|California|Paso Robles|-
        Santa Barbara|United States|California|Santa Barbara|-
        Santa Ynez Valley|United States|California|Santa Ynez Valley|-
        Willamette Valley|United States|Oregon|Willamette Valley|-
        Dundee Hills|United States|Oregon|Dundee Hills|-
        Walla Walla Valley|United States|Washington|Walla Walla Valley|-
        Red Mountain|United States|Washington|Red Mountain|-
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
        Uco Valley|Argentina|Mendoza|Uco Valley|Red|Valle de Uco
        Mendoza|Argentina|Mendoza|Mendoza|Red
        Lodi|United States|California|Lodi|-
        Sonoma County|United States|California|Sonoma County|-
    """

}
