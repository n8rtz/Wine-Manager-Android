package com.yourapp.wine.kb

/** Existing source-authored wine vocabulary shared with the legacy parser; not a world-catalog download. */
object BundledProducerNames {
    val terms: List<WineTerm> by lazy { parseWineTerms(source) }

    private val exact by lazy { terms.flatMap { term -> (listOf(term.name) + term.aliases).map { com.yourapp.wine.merge.TextNorm.normalize(it) to term.name } }.groupBy({ it.first }, { it.second }) }
    private val compact by lazy { exact.entries.groupBy({ it.key.filter(Char::isLetterOrDigit) }, { it.value }).mapValues { it.value.flatten().distinct() } }
    /** No coordinates: complete known alias letters only (spaces/punctuation may differ), never a spelling/origin guess. */
    fun exactName(text: String): String? {
        val normalized = com.yourapp.wine.merge.TextNorm.normalize(text)
        return (exact[normalized] ?: compact[normalized.filter(Char::isLetterOrDigit)])?.distinct()?.singleOrNull()
    }

    val source = """
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
        Blackstone Winery|Blackstone
        Bodegas Olarra|Olarra
        Conde de Basalu
        Los Haroldos
        Los Riscos
        Juan Gil
    """

}
