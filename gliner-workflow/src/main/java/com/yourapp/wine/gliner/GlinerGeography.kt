package com.yourapp.wine.gliner

/** A small source-backed hierarchy overlay. This is ordinary geographic knowledge, not test answers. */
data class GlinerOrigin(val country:String,val region:String,val subregion:String,val forms:List<String>)

object GlinerGeography {
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
