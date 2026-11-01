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
    // Even context-required AVAs are only used with a preselected US country and a
    // standalone corroborated origin directly below the selected grape. Ambiguity abstains.
    val usAvas:Map<String,List<GlinerOrigin>> by lazy {
        GlinerGeography.indexOrigins(ReferenceWineCatalog.origins.filter {
            it.country=="United States" && it.source=="ucd-ava" && it.regions.size==1
        }.map { GlinerOrigin(it.country,it.regions.single(),it.name,it.aliases) },minKeyLength=4)
    }
}
