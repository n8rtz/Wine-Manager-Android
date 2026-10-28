import com.yourapp.wine.gazetteer.ProducerResolver

fun main() {
    var checks=0
    fun verify(ok:Boolean) { checks++;check(ok) { "Producer resolver check $checks failed" } }
    fun entity(id:String,name:String,support:Set<String> = emptySet())=ProducerResolver.Entity(id,name,ProducerResolver.normalize(name),support)
    val primary=entity("estate","Château Example",setOf("ttb:permit","osm:node/1"))
    fun index(rows:List<ProducerResolver.Entity>,verified:Boolean=true)=object:ProducerResolver.Index {
        override val version="fixture";override val primaryVerified=verified
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=rows.take(limit)
    }
    fun spans(text:String="Chateau Example")=listOf(ProducerResolver.Span(text,"mlkit",setOf(1)),ProducerResolver.Span(text,"paddle",setOf(2),true))
    val resolver=ProducerResolver(index(listOf(primary)))
    verify(resolver.resolve("",spans()).value=="Chateau Example")
    verify(resolver.resolve("Existing Good Producer",spans()).value=="Existing Good Producer")
    verify(resolver.resolve("",listOf(spans()[0],spans()[1].copy(observationIds=setOf(1)))).acceptedId==null)
    verify(resolver.resolve("",spans().take(1)).value.isBlank()) // secondary source counts are not votes
    verify(resolver.resolve("",listOf(spans()[0],spans()[0].copy(observationIds=setOf(1,3)))).acceptedId==null)
    verify(resolver.resolve("Chateau Example",spans()).value=="Chateau Example") // no unnecessary reformat
    verify(resolver.resolve("Chateau Exampke",spans("Chateau Exampke")).acceptedId=="estate")
    verify(ProducerResolver(index(listOf(primary),false)).resolve("Original",spans()).value=="Original")
    verify(ProducerResolver(index(listOf(primary,primary.copy(id="other-country")))).resolve("",spans()).acceptedId==null)
    verify(resolver.resolve("",spans("Imported by Chateau Example")).acceptedId==null)
    verify(resolver.resolve("",spans("2020 Chateau Example")).acceptedId==null)
    verify(resolver.resolve("",spans("Margaux")).acceptedId==null)
    verify(resolver.resolve("",spans("Domaine Estate")).acceptedId==null)
    verify(resolver.resolve("",spans("Chateau Example Appellation Medoc Controlee")).acceptedId==null)
    verify(resolver.resolve("keep",spans()) { throw ProducerResolver.BudgetExceeded() }.value=="keep")
    verify(resolver.resolve("keep",List(129) { spans()[0] }).acceptedId==null)
    verify(ProducerResolver(index(List(33) { primary.copy(id=it.toString()) })).resolve("",spans()).acceptedId==null)
    verify(ProducerResolver.normalize("Château d’Example")=="chateau d example")
    // Lab v4 single-reading policy (owner, 2026-10-27): verified primary names can use one reading, with source-aware safeguards.
    val lab=ProducerResolver.Policy.singleReading
    val oneEngine=spans().take(1)
    verify(resolver.resolve("",oneEngine).acceptedId==null) // Prod keeps two engines
    verify(resolver.resolve("",oneEngine,policy=lab).acceptedId=="estate")
    verify(resolver.resolve("",oneEngine,policy=lab).reason.contains("one engine reading under the lab single-reading policy"))
    verify(resolver.resolve("",spans(),policy=lab).acceptedId=="estate")
    verify(resolver.resolve("",spans()).reason=="primary LWIN producer/brand name supported by two engines; confirm manually")
    verify(resolver.resolve("",spans(),policy=lab).reason==resolver.resolve("",spans()).reason) // same wording when two engines did agree
    verify(ProducerResolver(index(listOf(primary,ProducerResolver.Entity("rival","Château Examplar",
        ProducerResolver.normalize("Château Examplar"))))).resolve("",oneEngine,policy=lab).acceptedId==null) // ambiguity still blocks
    verify(resolver.resolve("",spans("Imported by Chateau Example"),policy=lab).acceptedId==null) // eligibility unchanged
    verify(resolver.resolve("",List(129) { spans()[0] },policy=lab).acceptedId==null) // span bound unchanged
    val frHomonym=primary.copy(id="estate-fr",countryGroup="France")
    val clHomonym=primary.copy(id="estate-cl",countryGroup="Chile")
    val homonyms=ProducerResolver(index(listOf(frHomonym,clHomonym)))
    verify(homonyms.resolve("",oneEngine,policy=lab).acceptedId==null)
    verify(homonyms.resolve("",oneEngine,policy=lab.withCountryContext("France")).acceptedId=="estate-fr")
    verify(homonyms.resolve("",oneEngine,policy=lab.withCountryContext("Argentina")).acceptedId==null)
    // Lab v4 d=0 exact-match fast-path: skips past noisy existing guesses and d>=1 rivals while preserving guards.
    val labFast=lab.withExactMatchFastPath { it.equals("Saint Julien",ignoreCase=true) }
    val francette=ProducerResolver.Entity("la-francette","La Francette","la francette",setOf("lwin"))
    val francetteIndex=object:ProducerResolver.Index {
        override val version="fixture";override val primaryVerified=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(francette)
    }
    val trancette=ProducerResolver(francetteIndex).resolve("",listOf(ProducerResolver.Span("Trancette","paddle",setOf(1))),policy=labFast)
    verify(ProducerResolver.distance("trancette","la francette")==4 && trancette.acceptedId==null)
    val finalNearIndex=object:ProducerResolver.Index {
        override val version="fixture-exp";override val primaryVerified=false;override val labSupplemental=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(
            ProducerResolver.Entity("la-francette","La Francette","la francette",setOf("xwines")),
            ProducerResolver.Entity("grangette","Grangette","grangette",setOf("lwin")),
        ).take(limit)
    }
    val canonical=ProducerResolver(finalNearIndex).finalNearMatch("Trancette")
    verify(canonical!=null && canonical.entity.id=="la-francette" && canonical.distance==1)
    verify(resolver.resolve("SIGNATURE SERIES NoisyGuess",oneEngine,policy=labFast).value=="Chateau Example")
    verify(ProducerResolver(index(listOf(primary,ProducerResolver.Entity("rival","Château Examplar",
        ProducerResolver.normalize("Château Examplar"))))).resolve("NoisyGuess",oneEngine,policy=labFast).acceptedId=="estate")
    verify(homonyms.resolve("MOUTONCADET",oneEngine,policy=labFast.withCountryContext("France")).acceptedId=="estate-fr")
    val osmNeighbor=ProducerResolver.Entity("osm-neighbor","Chateau des Rochers","chateau des rochers",setOf("osm"))
    val supplementalIndex=object:ProducerResolver.Index {
        override val version="fixture-exp";override val primaryVerified=false;override val labSupplemental=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(osmNeighbor)
    }
    val observedRoches=listOf(ProducerResolver.Span("CHATEAU DES ROCHES","paddle",setOf(1)))
    val nearNeighbor=ProducerResolver(supplementalIndex).resolve("CHATEAU DES ROCHES",observedRoches,policy=labFast)
    verify(nearNeighbor.value=="CHATEAU DES ROCHES" && nearNeighbor.acceptedId==null)
    verify(nearNeighbor.reason.contains("single-engine fuzzy supplemental candidate"))
    val independentlyRead=listOf(ProducerResolver.Span("CHATEAU DES ROCHES","paddle",setOf(1)),
        ProducerResolver.Span("CHATEAU DES ROCHES","mlkit",setOf(2)))
    verify(ProducerResolver(supplementalIndex).resolve("",independentlyRead,policy=labFast).acceptedId=="osm-neighbor")
    val singleBrand=ProducerResolver.Entity("kaiken","Kaiken","kaiken",setOf("lwin"),"Argentina")
    val singleIdx=object:ProducerResolver.Index {
        override val version="fixture-exp";override val primaryVerified=false;override val labSupplemental=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=
            listOf(singleBrand,ProducerResolver.Entity("supp-1","Bousquet","bousquet",setOf("ttb"))).filter { it.key==key }
    }
    verify(ProducerResolver(singleIdx).resolve("ultra",listOf(ProducerResolver.Span("KAIKEN","paddle",setOf(1))),policy=labFast).value=="Kaiken")
    verify(ProducerResolver(singleIdx).resolve("",listOf(ProducerResolver.Span("Bousquet","paddle",setOf(1))),policy=labFast).acceptedId==null) // supplemental single-word blocked
    verify(ProducerResolver(index(listOf(ProducerResolver.Entity("bousquet-lwin","Bousquet","bousquet")))).resolve(
        "CHATEAU DU BOUSQUET",
        listOf(ProducerResolver.Span("Bousquet","paddle",setOf(1)),ProducerResolver.Span("CHATEAU DU BOUSQUET","paddle",setOf(2))),
        policy=labFast,
    ).value=="CHATEAU DU BOUSQUET") // estate-prefixed existing + fragment guard
    verify(ProducerResolver(index(listOf(ProducerResolver.Entity("gv","Gran Reserva","gran reserva")))).resolve(
        "",listOf(ProducerResolver.Span("Gran Reserva","paddle",setOf(1))),policy=labFast).acceptedId==null)
    verify(ProducerResolver(index(listOf(ProducerResolver.Entity("sj","Saint Julien","saint julien")))).resolve(
        "",listOf(ProducerResolver.Span("Saint Julien","paddle",setOf(1))),policy=labFast).acceptedId==null)
    val secondExact=ProducerResolver.Entity("vigneron","Franck Despagne","franck despagne")
    val twoExact=ProducerResolver(index(listOf(primary,secondExact)))
    val bothSpans=listOf(ProducerResolver.Span("Chateau Example","paddle",setOf(1)),ProducerResolver.Span("Franck Despagne","paddle",setOf(2)))
    verify(twoExact.resolve("Chateau Example",bothSpans,policy=labFast).value=="Chateau Example")
    verify(twoExact.resolve("",bothSpans,policy=labFast).acceptedId==null)
    println("PASS: $checks conservative producer resolver checks (actual Kotlin; fixture data, no accuracy claim).")
}
