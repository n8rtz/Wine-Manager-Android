import com.yourapp.wine.gazetteer.*

fun main() {
    var checks=0
    fun verify(value:Boolean) { checks++;check(value) { "Linked wine check $checks failed" } }
    fun entity(id:String,name:String)=ProducerResolver.Entity(id,name,ProducerResolver.normalize(name))
    val estate=entity("a","Domaine Example")
    val other=entity("b","Domaine Exampla")
    fun span(text:String,engine:String,id:Int)=ProducerResolver.Span(text,engine,setOf(id))
    val producers=listOf(span("Domaine Example","mlkit",1),span("Domaine Example","paddle",2))
    val wineSpans=listOf(span("Moonlight Cuvee","mlkit",3),span("Moonlight Cuvee","paddle",4))
    val wine=LinkedWineResolver.Wine("wine-1","Moonlight Cuvee","moonlight cuvee","a","[\"1234567\"]")
    fun index(ps:List<ProducerResolver.Entity> = listOf(estate),ws:List<LinkedWineResolver.Wine> = listOf(wine))=object:LinkedWineResolver.Index {
        override val version="fixture";override val primaryVerified=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=ps.take(limit)
        override fun lookupWines(key:String,producerIds:Set<String>,limit:Int,checkBudget:()->Unit)=ws.filter { it.producerId in producerIds }.take(limit)
    }
    val idx=index();val pd=ProducerResolver(idx).resolve("",producers)
    fun resolve(w:String="",spans:List<ProducerResolver.Span> = producers+wineSpans)=LinkedWineResolver(idx).resolve("",w,pd,spans)
    val accepted=resolve();verify(accepted.wine==wine.name && accepted.producer==estate.name)
    verify(accepted.wineId==wine.id && accepted.lwin7==wine.lwin7)
    verify(resolve(spans=producers).wine.isBlank()) // producer alone never invents a wine
    verify(LinkedWineResolver(idx).resolve("","",ProducerResolver.Decision("","no producer",emptyList()),wineSpans).wineId==null)
    verify(resolve(spans=producers+wineSpans.take(1)).wineId==null)
    verify(resolve(spans=producers+listOf(wineSpans[0],wineSpans[0].copy(observationIds=setOf(5)))).wineId==null)
    verify(resolve(spans=producers+listOf(wineSpans[0],wineSpans[1].copy(observationIds=setOf(3)))).wineId==null)
    verify(resolve(spans=producers+wineSpans.map { it.copy(observationIds=setOf(1,2)) }).wineId==null)
    verify(resolve("Different Good Wine").wine=="Different Good Wine")
    verify(LinkedWineResolver(idx).resolve("Different Producer","",pd,producers+wineSpans).wineId==null)
    verify(resolve("Moonlight Cuvee").wine=="Moonlight Cuvee")
    val rival=wine.copy(id="wine-2",name="Moonlight Cuvea",key="moonlight cuvea")
    verify(LinkedWineResolver(index(ws=listOf(wine,rival))).resolve("","",pd,producers+wineSpans).wineId==null)
    verify(LinkedWineResolver(index(ws=listOf(wine.copy(producerId="b")))).resolve("","",pd,producers+wineSpans).wineId==null)
    verify(LinkedWineResolver(idx).resolve("","Keep",pd,producers+wineSpans) { throw ProducerResolver.BudgetExceeded() }.wine=="Keep")
    verify(!LinkedWineResolver.eligible("Imported by Moonlight Cuvee"))
    verify(!LinkedWineResolver.eligible("Moonlight Cuvee 2019"))
    verify(LinkedWineResolver.eligible("Bin 389"))
    verify(!LinkedWineResolver.eligible("Grand Cru"))
    verify(!LinkedWineResolver.eligible("750ml"))
    verify(!LinkedWineResolver.eligible("750 ml"))
    verify(!LinkedWineResolver.eligible("EAN 1234567890123"))
    val ambiguous=index(ps=listOf(estate,other))
    val amb=ProducerResolver(ambiguous).resolve("",producers)
    verify(amb.acceptedId==null)
    val disambiguated=LinkedWineResolver(ambiguous).resolve("","",amb,producers+wineSpans)
    verify(disambiguated.producerId=="a" && disambiguated.wineId==wine.id)
    val shared=index(ps=listOf(estate,other),ws=listOf(wine,wine.copy(id="wine-3",producerId="b")))
    verify(LinkedWineResolver(shared).resolve("","",amb,producers+wineSpans).wineId==null)
    val single=index(ps=listOf(entity("a","Example")))
    val singleSpans=listOf(span("Example","mlkit",1),span("Example","paddle",2))+wineSpans
    verify(LinkedWineResolver(single).resolve("Example","",ProducerResolver.Decision("Example","unchanged",emptyList()),singleSpans).wineId==wine.id)
    verify(LinkedWineResolver(index(ws=List(33) { wine.copy(id=it.toString()) })).resolve("","",pd,producers+wineSpans).wineId==null)
    verify(resolve(spans=List(129) { producers[0] }).wineId==null)
    println("PASS: $checks producer-linked wine resolver checks (actual Kotlin, fixture data).")
}
