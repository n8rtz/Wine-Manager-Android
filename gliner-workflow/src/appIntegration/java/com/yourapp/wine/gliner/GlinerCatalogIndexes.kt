package com.yourapp.wine.gliner

import com.yourapp.wine.kb.ReferenceWineCatalog

/** Ordinary bundled geography, never evaluation references. Shared with the saved-OCR runner. */
internal object GlinerCatalogIndexes {
    // Exact Italian appellations with one parent; same-spelling conflicts remain separate.
    val italianAppellations:Map<String,List<GlinerOrigin>> by lazy {
        GlinerGeography.indexOrigins(ReferenceWineCatalog.origins.filter {
            it.country=="Italy" && it.nameRole=="appellation" && !it.contextRequired && it.regions.size==1
        }.map { GlinerOrigin(it.country,it.regions.single(),it.name,it.aliases) })
    }
    // Common St./Saint abbreviation is normalized for any French appellation in the catalog;
    // names that map to multiple hierarchies remain ambiguous and are never selected.
    val frenchAppellations:Map<String,List<GlinerOrigin>> by lazy {
        val saint=Regex("(?i)\\bsaint\\b")
        GlinerGeography.indexOrigins(ReferenceWineCatalog.origins.filter {
            it.country=="France" && it.nameRole=="appellation" && !it.contextRequired && it.regions.size==1
        }.map { row ->
            val names=listOf(row.name)+row.aliases
            GlinerOrigin(row.country,row.regions.single(),row.name,names+names.map { saint.replace(it,"St") })
        })
    }
    // Even context-required AVAs are only used with a preselected US country and a
    // standalone corroborated origin directly below the selected grape. Ambiguity abstains.
    val usAvas:Map<String,List<GlinerOrigin>> by lazy {
        GlinerGeography.indexOrigins(ReferenceWineCatalog.origins.filter {
            it.country=="United States" && it.source=="ucd-ava" && it.regions.size==1
        }.map { GlinerOrigin(it.country,it.regions.single(),it.name,it.aliases) },minKeyLength=4)
    }
}
