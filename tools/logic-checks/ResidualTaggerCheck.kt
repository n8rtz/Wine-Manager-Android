package com.yourapp.wine.textlab.residual

/** Executes actual production-bound debug logic on a JVM; only the FTS lookup is a declared fixture. */
fun main() {
    val tagger=ResidualTagger(listOf("Cabernet Sauvignon","Merlot","Riesling","Melon"),
        listOf("Grand Cru","Premier Cru","Reserva","Gran Reserva","Réserve","Riserva"),listOf("AVA Grace","19 Crimes"))
    var count=0
    fun run(raw:String):ResidualTagger.Result {
        val result=tagger.tag(raw,2026,{ s ->
            when(ResidualTagger.normalize(s)) {
                "pauillac","napa valley" -> ResidualTagger.Geography(true,true,true,s,"geo-1","exact")
                "pauilac" -> ResidualTagger.Geography(true,false,true,"Pauillac","geo-1","fuzzy")
                "prosecco" -> ResidualTagger.Geography(false,false,true,reason="ambiguous")
                else -> null
            }
        })
        count++
        check(result.raw==raw)
        for(t in result.tags) check(t.span.text(raw).isNotEmpty())
        val removed=result.tags.filter { it.subtract }
        for((i,a) in removed.withIndex()) for(b in removed.drop(i+1)) check(!a.span.overlaps(b.span))
        for(c in result.candidates) { check(c.text==c.span.text(raw));check(removed.none { it.span.overlaps(c.span) }) }
        return result
    }
    fun texts(raw:String)=run(raw).candidates.map { it.text }
    check(texts("Asterline | Napa Valley | Cabernet Sauvignon | 2020 | 750ml | 13.5% alc/vol")==listOf("Asterline"))
    check(texts("Pauillac").isEmpty())
    check(texts("Appellation Pauilac Contrôlée").isEmpty())
    check(texts("Pauilac")==listOf("Pauilac")) // Fuzzy geography alone is not safe subtraction.
    check(run("Pauilac").tags.any { it.kind==ResidualTagger.Kind.POSSIBLE_GEOGRAPHY && !it.subtract })
    check(texts("Prosecco")==listOf("Prosecco"))
    check(texts("Château Margaux 2019")==listOf("Château Margaux"))
    check(texts("Domaine du Riesling 2019")==listOf("Domaine du Riesling"))
    check(texts("Cuvée Cabernet Sauvignon")==listOf("Cuvée Cabernet Sauvignon"))
    check(texts("Benoît Melon")==listOf("Benoît Melon"))
    check(texts("Riesling & Sons")==listOf("Riesling & Sons"))
    check(texts("Merlot 2020").isEmpty())
    check(texts("Merlot Riesling 2020").isEmpty())
    check(texts("Merlot & Riesling").isEmpty())
    check(texts("Cabernet Sauvignon Gran Reserva 2020").isEmpty())
    val qualified=run("Cuvée des Astres Grand Cru")
    check(qualified.candidates.single().text=="Cuvée des Astres")
    check(qualified.candidates.single().qualifiers.single().text(qualified.raw)=="Grand Cru")
    check(texts("Domaine de la Réserve 2020")==listOf("Domaine de la Réserve"))
    check(texts("La Réserve")==listOf("La Réserve"))
    check(texts("Cuvée La Réserve")==listOf("Cuvée La Réserve"))
    check(texts("Imported by Acme, Bordeaux, France").isEmpty())
    check(texts("Bottled by Acme").isEmpty())
    check(texts("Mis en bouteille par Domaine des Astres").isEmpty())
    check(texts("Contains Sulfites").isEmpty())
    check(texts("Cuvée des Astres Contains Sulfites")==listOf("Cuvée des Astres"))
    check(texts("Produced by Asterline")==listOf("Asterline"))
    check(texts("Produced and bottled by Asterline")==listOf("Asterline"))
    check(texts("Estate Bottled").isEmpty())
    check(texts("Grand Cru DOCG 750ml 13.5% alc/vol").isEmpty())
    check(texts("750ml").isEmpty());check(texts("13,5% vol").isEmpty());check(texts("13.5% ABV").isEmpty())
    check(texts("Established 1865").isEmpty())
    check(run("Founded 2019").tags.any { it.kind==ResidualTagger.Kind.HISTORICAL_YEAR })
    check(run("2019").tags.single().kind==ResidualTagger.Kind.VINTAGE_CANDIDATE)
    check(run("2099").tags.single().kind==ResidualTagger.Kind.NUMBER_NOT_VINTAGE)
    check(texts("19 Crimes")==listOf("19 Crimes"))
    check(texts("AVA Grace")==listOf("AVA Grace"))
    check(texts("GB")==listOf("GB"))
    check(texts("ChâteauMerlot")==listOf("ChâteauMerlot")) // No substring dictionary deletion.
    check(texts("12345 | du | .").isEmpty())
    check(texts("Astral Cabernet Sauvignon des Étoiles")==listOf("Astral","des Étoiles")) // No new joined name.
    check(texts("Roche\u0301 des Astres Réserve")==listOf("Roche\u0301 des Astres")) // Original Unicode offsets.
    check(texts("").isEmpty())
    check(run("🥂 Asterline | 2020").candidates.single().text=="Asterline")
    check(run("Asterline 2020").candidates.single().warnings.isNotEmpty())
    check(run("Merlot").candidates.isEmpty())
    check(runCatching { tagger.tag("x".repeat(513),2026,{ null }) }.isFailure)
    check(runCatching { tagger.tag((1..17).joinToString("|") { "Unknownname$it" },2026,{ null }) }.isFailure)
    var callbacks=0
    check(runCatching { tagger.tag("Asterline",2026,{ null }) { check(++callbacks<2) { "deadline" } } }.isFailure)
    val real=ResidualTagger(
        com.yourapp.wine.kb.BundledGrapes.terms.flatMap { listOf(it.name)+it.aliases },
        com.yourapp.wine.kb.GeneratedWineKnowledge.labelTerms.map { it.split('|') }.filter { it[0]=="suffix" }.map { it[1] },
        com.yourapp.wine.kb.BundledProducerNames.terms.flatMap { listOf(it.name)+it.aliases })
    for(grape in listOf("Cabernet Sauvignon","Riesling","Chardonnay","Merlot")) {
        val r=real.tag("$grape 2020",2026,{ null })
        check(r.candidates.isEmpty()) { "Bundled grape failed: $grape" }
        check(r.tags.any { it.kind==ResidualTagger.Kind.GRAPE })
    }
    check(real.tag("Cuvée des Astres Grand Cru",2026,{ null }).candidates.single().text=="Cuvée des Astres")
    check(real.tag("19 Crimes",2026,{ null }).candidates.single().text=="19 Crimes")
    println("PASS: bundled grape, qualifier and producer vocabulary checks")
    println("PASS: $count residual cases plus character/query/deadline guards; actual Kotlin on JVM, fixture FTS only")
}
