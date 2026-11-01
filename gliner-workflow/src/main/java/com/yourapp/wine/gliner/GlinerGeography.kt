package com.yourapp.wine.gliner

/** A small source-backed hierarchy overlay. This is ordinary geographic knowledge, not test answers. */
data class GlinerOrigin(val country:String,val region:String,val subregion:String,val forms:List<String>)
data class GlinerAdminRegion(val country:String,val region:String,val forms:List<String>)

object GlinerGeography {
    /** Exact aliases only; ambiguous same-spelling places remain separate and must abstain. */
    fun indexOrigins(origins:List<GlinerOrigin>):Map<String,List<GlinerOrigin>> = origins.flatMap { origin ->
        (origin.forms+origin.subregion).map { GlinerDocument.normalize(it) to origin }
    }.filter { it.first.length>=5 }.groupBy({ it.first }, { it.second }).mapValues { (_,values) ->
        values.distinctBy { Triple(GlinerDocument.normalize(it.country),GlinerDocument.normalize(it.region),
            GlinerDocument.normalize(it.subregion)) }
    }

    // Italian regional geographical indications: administrative regions and native/English
    // spellings. Used only with an independently corroborated geographical-indication cue.
    // https://italianwinecentral.com/italian-wine-regions/
    val italianRegions=listOf(
        GlinerAdminRegion("Italy","Puglia",listOf("Puglia","Apulia")),
        GlinerAdminRegion("Italy","Tuscany",listOf("Toscana","Tuscany")),
        GlinerAdminRegion("Italy","Piedmont",listOf("Piemonte","Piedmont")),
        GlinerAdminRegion("Italy","Veneto",listOf("Veneto")),
        GlinerAdminRegion("Italy","Lombardy",listOf("Lombardia","Lombardy")),
        GlinerAdminRegion("Italy","Sicily",listOf("Sicilia","Sicily")),
        GlinerAdminRegion("Italy","Sardinia",listOf("Sardegna","Sardinia")),
        GlinerAdminRegion("Italy","Abruzzo",listOf("Abruzzo")),
        GlinerAdminRegion("Italy","Basilicata",listOf("Basilicata")),
        GlinerAdminRegion("Italy","Calabria",listOf("Calabria")),
        GlinerAdminRegion("Italy","Campania",listOf("Campania")),
        GlinerAdminRegion("Italy","Lazio",listOf("Lazio")),
        GlinerAdminRegion("Italy","Liguria",listOf("Liguria")),
        GlinerAdminRegion("Italy","Marche",listOf("Marche")),
        GlinerAdminRegion("Italy","Molise",listOf("Molise")),
        GlinerAdminRegion("Italy","Umbria",listOf("Umbria")),
        GlinerAdminRegion("Italy","Emilia-Romagna",listOf("Emilia-Romagna")),
        GlinerAdminRegion("Italy","Friuli-Venezia Giulia",listOf("Friuli-Venezia Giulia")),
        GlinerAdminRegion("Italy","Trentino-Alto Adige",listOf("Trentino-Alto Adige")),
        GlinerAdminRegion("Italy","Valle d'Aosta",listOf("Valle d'Aosta")),
    )
    // Austrian broad wine-growing regions. https://www.austrianwine.com/our-wine/wine-growing-regions
    val austrianRegions=listOf(
        GlinerAdminRegion("Austria","Niederösterreich",listOf("Niederösterreich","Lower Austria")),
        GlinerAdminRegion("Austria","Burgenland",listOf("Burgenland")),
        GlinerAdminRegion("Austria","Steiermark",listOf("Steiermark","Styria")),
        GlinerAdminRegion("Austria","Wien",listOf("Wien","Vienna")),
    )
    // Chilean DOs in the Central Valley; https://www.cellartours.com/chile/wine-regions/central-valley
    // Australian GIs: https://www.agw.org.au/industry-support/geographical-indications/
    // US AVA nesting: https://www.ttb.gov/regulated-commodities/beverage-alcohol/wine/american-viticultural-areas
    val origins=listOf(
        GlinerOrigin("Chile","Central Valley","Colchagua Valley",listOf("Colchagua Valley","Valle de Colchagua")),
        GlinerOrigin("Chile","Central Valley","Cachapoal Valley",listOf("Cachapoal Valley","Valle de Cachapoal")),
        GlinerOrigin("Chile","Central Valley","Maule Valley",listOf("Maule Valley","Valle del Maule")),
        GlinerOrigin("Chile","Central Valley","Maipo Valley",listOf("Maipo Valley","Valle del Maipo")),
        GlinerOrigin("Chile","Central Valley","Curicó Valley",listOf("Curicó Valley","Valle de Curicó")),
        GlinerOrigin("Australia","South Australia","Clare Valley",listOf("Clare Valley")),
        GlinerOrigin("Australia","South Australia","Barossa Valley",listOf("Barossa Valley")),
        GlinerOrigin("Australia","South Australia","McLaren Vale",listOf("McLaren Vale")),
        GlinerOrigin("California","Sonoma County","Dry Creek Valley",listOf("Dry Creek Valley")),
        GlinerOrigin("California","Sonoma County","Alexander Valley",listOf("Alexander Valley")),
        GlinerOrigin("California","Napa Valley","Stags Leap District",listOf("Stags Leap District")),
        GlinerOrigin("Washington","Columbia Valley","Horse Heaven Hills",listOf("Horse Heaven Hills")),
        GlinerOrigin("Washington","Columbia Valley","Red Mountain",listOf("Red Mountain")),
    )
}
