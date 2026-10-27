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
    verify(resolver.resolve("",spans()).value==primary.name)
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
    // Lab v4 single-reading policy (owner, 2026-10-27): the engine quorum is the only difference.
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
    println("PASS: $checks conservative producer resolver checks (actual Kotlin; fixture data, no accuracy claim).")
}
