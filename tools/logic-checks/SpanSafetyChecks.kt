import android.graphics.RectF
import com.yourapp.wine.gazetteer.ProducerResolver
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import com.yourapp.wine.textlab.evidence.*

/** Synthetic safety cases only; no corpus targets, native OCR, or measured accuracy claims. */
fun main() {
    fun token(text:String,score:Float)=TokenRecord(text,TextNorm.normalize(text),score,
        RectF(.1f,.2f,.9f,.3f),RectF(.1f,.2f,.9f,.3f),emptyList(),"mlkit","base")
    var checks=0
    fun verify(ok:Boolean) { checks++;check(ok) { "Terminal safety check $checks failed" } }
    fun entity(id:String,name:String,support:Set<String> = emptySet())=ProducerResolver.Entity(id,name,ProducerResolver.normalize(name),support)
    fun index(rows:List<ProducerResolver.Entity>,verified:Boolean=true)=object:ProducerResolver.Index {
        override val version="fixture";override val primaryVerified=verified
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=rows.take(limit)
    }
    val tied=ProducerResolver(index(listOf(entity("one","Domaine Abca"),entity("two","Domaine Abcb"))))
    // General terminal safety: unknown OCR-backed makers must not be replaced by unsupported neighbors.
    val supplemental=entity("supplemental","Domaine Meadow",setOf("osm"))
    val supplementalOnly=object:ProducerResolver.Index {
        override val version="terminal-fixture";override val primaryVerified=false;override val labSupplemental=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(supplemental)
    }
    val terminalSafety=ProducerResolver(supplementalOnly)
    val one=ProducerResolver.Match(supplemental,"Domaine Meadows","paddle",setOf(10),1)
    var rejection=""
    verify(terminalSafety.finalNearMatch("Domaine Meadows",supportingMatches=listOf(one),onRejected={ rejection=it })==null)
    verify(rejection=="insufficient_supplemental_support")
    verify(terminalSafety.finalNearMatch("Domaine Meadows",supportingMatches=listOf(one,one.copy(engine="mlkit")))==null)
    verify(terminalSafety.finalNearMatch("Domaine Meadows",supportingMatches=listOf(one,
        one.copy(engine="mlkit",observationIds=setOf(11))))?.entity?.id==supplemental.id)
    verify(terminalSafety.finalNearMatch("Domaine Meadows",supportingMatches=listOf(one,
        one.copy(engine="paddle-v6-medium",observationIds=setOf(11))))==null)
    verify(terminalSafety.finalNearMatch("Domaine Meadows",protectExact=true,observedExact=listOf("Domaine Meadows"),
        supportingMatches=listOf(one,one.copy(engine="mlkit",observationIds=setOf(11))))==null)
    verify(tied.finalNearMatch("Domaine Abcc")==null) // default also abstains, no FTS-order identity selection
    val lwinTerminal=ProducerResolver(index(listOf(supplemental.copy(support=setOf("lwin"))),verified=false))
    // Index must be verified primary or explicitly Lab supplemental.
    verify(lwinTerminal.finalNearMatch("Domaine Meadows")==null)
    val primaryTerminal=ProducerResolver(index(listOf(supplemental.copy(support=setOf("lwin")))))
    verify(primaryTerminal.finalNearMatch("Domaine Meadows")?.entity?.id==supplemental.id)
    verify(runCatching { terminalSafety.finalNearMatch("Domaine Meadows",checkBudget={ throw ProducerResolver.BudgetExceeded() }) }.isFailure)
    verify(terminalSafety.finalNearMatch("Domaine Meadows",supportingMatches=listOf(one.copy(distance=0)))?.entity?.id==supplemental.id)
    // Do not silently discard an unsupported tied identity and then choose another from FTS.
    val rival=supplemental.copy(id="rival",support=setOf("lwin"))
    val collision=object:ProducerResolver.Index {
        override val version="collision";override val primaryVerified=false;override val labSupplemental=true
        override fun lookup(key:String,limit:Int,checkBudget:()->Unit)=listOf(supplemental,rival)
    }
    verify(ProducerResolver(collision).finalNearMatch("Domaine Meadows")==null)
    val kb=FixtureKnowledgeBase()
    // Span discovery keeps founding-date context, even with numeric OCR spacing and moderate confidence.
    for(recovery in listOf(false,true)) for(confidence in listOf(.99f,.50f)) {
        val spans=EvidenceExtractor(kb,modules=LabPostOcrModules.none.copy(
            candidateIsolation=true,vintageSpans=true,vintageRecovery=recovery))
        for(line in listOf("Founded in 1954","Fondee en 1 954","Since 1954","Established 1954")) {
            val result=spans.extract(listOf(token(line,confidence)),2026,exactGeographyFastPath=true)
            check(result.hypotheses.none { FieldId.VINTAGE in it.values }) { "Founding date became vintage: $line" }
            check(result.fill.fields.getValue(FieldId.VINTAGE).value.isBlank())
        }
    }
    val spanEngine=EvidenceExtractor(kb,modules=LabPostOcrModules.none.copy(
        candidateIsolation=true,vintageSpans=true,vintageRecovery=true))
    val harvest=spanEngine.extract(listOf(token("Harvest 2020",.99f)),2026)
    check(harvest.hypotheses.any { it.values[FieldId.VINTAGE]=="2020" && it.sharesVintageSource })
    val namedYear=listOf(token("Barolo 2020",.99f))
    val withoutSpans=EvidenceExtractor(kb,modules=LabPostOcrModules.none.copy(
        candidateIsolation=true,vintageRecovery=true)).extract(namedYear,2026)
    val withSpans=spanEngine.extract(namedYear,2026)
    check(withSpans.fill.fields.getValue(FieldId.WINE_NAME).value==withoutSpans.fill.fields.getValue(FieldId.WINE_NAME).value)
    check(withSpans.fill.fields.getValue(FieldId.WINE_NAME).value.isNotBlank())
    check(withSpans.fill.fields.getValue(FieldId.VINTAGE).value=="2020")
    check(withSpans.hypotheses.any { it.values[FieldId.VINTAGE]=="2020" &&
        it.phrase.construction.startsWith("candidate-span:") && it.sharesVintageSource })
    val bareYear=spanEngine.extract(listOf(token("2020",.99f)),2026)
    check(bareYear.hypotheses.filter { FieldId.VINTAGE in it.values }.all { !it.sharesVintageSource })
    // Solver compatibility is provenance, not a reason-string convention or score tie-break.
    val source=token("Cuvee Horizon 2020",.99f)
    val provenanceGraph=EvidenceGraphBuilder.build(listOf(source))
    val provenanceOriginal=provenanceGraph.phrases.first { it.observations==setOf(0) }
    val yearSpan=provenanceOriginal.copy(id=provenanceGraph.phrases.size,text="2020",construction="candidate-span:VINTAGE")
    val name=Hypothesis(mapOf(FieldId.WINE_NAME to "Cuvee Horizon"),provenanceOriginal,70.0,"title")
    val embedded=Hypothesis(mapOf(FieldId.VINTAGE to "2020"),provenanceOriginal,52.0,"renamed diagnostic text",sharesVintageSource=true)
    val span=Hypothesis(mapOf(FieldId.VINTAGE to "2020"),yearSpan,55.0,"complete observed vintage",sharesVintageSource=true)
    val view=com.yourapp.wine.kb.PlaceHierarchy(kb).snapshot()
    val withSpan=EvidenceSolver.solve(listOf(name,embedded,span),view,provenanceGraph,{}).first()
    check(withSpan.any { it.values[FieldId.WINE_NAME]=="Cuvee Horizon" } && withSpan.any { it.values[FieldId.VINTAGE]=="2020" })
    val standalone=span.copy(sharesVintageSource=false,reason="embedded observed vintage")
    val conflict=EvidenceSolver.solve(listOf(name,standalone),view,provenanceGraph,{}).first()
    check(conflict.size==1) // merely writing the old reason must never bypass ownership
    println("PASS: $checks terminal producer safety checks")
    println("PASS: founding context, harvest/bare-year controls, provenance-based ownership and duplicate-span stability")
}
