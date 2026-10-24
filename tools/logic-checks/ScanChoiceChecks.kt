import android.graphics.RectF
import com.yourapp.wine.fill.*
import com.yourapp.wine.ui.*
import com.yourapp.wine.ocr.*
import com.yourapp.wine.extract.*
import com.yourapp.wine.kb.*

fun main() {
    var checks=0
    fun verify(ok:Boolean) { checks++;check(ok) { "Choice check $checks failed" } }
    fun candidate(field:FieldId,value:String,state:UiState=UiState.YELLOW,raw:String=value):FieldCandidate {
        val b=RectF(.1f,.1f,.8f,.2f)
        val r=TokenRecord(raw,raw,.95f,b,b,emptyList(),"mlkit","V0",true)
        return FieldCandidate(field,value,state,"supported alternative",listOf(MergedToken(raw,listOf(r),.95f,b)))
    }
    val empty=FieldId.entries.associateWith { FieldCandidate(it,"",UiState.RED,"Unknown",emptyList()) }
    val blank=FieldId.entries.associateWith { "" }
    val a=candidate(FieldId.PRODUCER,"Domaine des Astres")
    val b=candidate(FieldId.PRODUCER,"Domaine des Aurores")
    val result=FillResult(empty,null,fallbackCandidates=mapOf(FieldId.PRODUCER to listOf(a,b)))
    val form=ScanFormState().apply(result,blank,2026)
    verify(form.fields.getValue(FieldId.PRODUCER).value.isBlank())
    verify(form.fields.getValue(FieldId.PRODUCER).choices.map { it.value }==listOf(a.value,b.value))
    verify(!form.hasPendingConfirmation)
    val chosen=form.selectChoice(FieldId.PRODUCER,a.value)
    val reviewed=chosen.fields.getValue(FieldId.PRODUCER)
    verify(reviewed.value==a.value && reviewed.edited && reviewed.confirmed && reviewed.state==UiState.YELLOW)
    verify(reviewed.choices.isEmpty())
    verify(form.selectChoice(FieldId.PRODUCER,"not offered")==form)
    verify(chosen.selectChoice(FieldId.PRODUCER,b.value)==chosen)
    val retake=result.copy(fields=empty+(FieldId.PRODUCER to b.copy(state=UiState.GREEN)))
    verify(chosen.apply(retake,chosen.fields.mapValues { it.value.value },2026).fields.getValue(FieldId.PRODUCER).value==a.value)
    val dismissed=form.dismissChoices(FieldId.PRODUCER)
    verify(dismissed.fields.getValue(FieldId.PRODUCER).intentionallyBlank)
    verify(dismissed.apply(result,blank,2026).fields.getValue(FieldId.PRODUCER).choices.isEmpty())
    verify(form.edit(FieldId.PRODUCER,"Manual").fields.getValue(FieldId.PRODUCER).choices.isEmpty())
    // Good selected values remain filled: both GREEN and editable YELLOW, regardless of alternatives.
    for(state in listOf(UiState.GREEN,UiState.YELLOW)) {
        val filled=ScanFormState().apply(result.copy(fields=empty+(FieldId.PRODUCER to a.copy(state=state))),blank,2026).fields.getValue(FieldId.PRODUCER)
        verify(filled.value==a.value && filled.choices.isEmpty())
        verify(filled.confirmed==(state==UiState.GREEN))
    }
    verify(ScanFormState().apply(FillResult(empty,null,alternatives=mapOf(FieldId.PRODUCER to listOf(a,b))),blank,2026).fields.getValue(FieldId.PRODUCER).choices.isEmpty())
    verify(ScanFormState().apply(result.copy(intentionalBlankFields=setOf(FieldId.PRODUCER)),blank,2026).fields.getValue(FieldId.PRODUCER).choices.isEmpty())
    fun options(list:List<FieldCandidate>)=ScanChoicePolicy.choices(FieldId.PRODUCER,result.copy(fallbackCandidates=mapOf(FieldId.PRODUCER to list)),blank,2026)
    verify(options(listOf(a)).isEmpty())
    verify(options(listOf(a,a.copy(value="DOMAINE DES ASTRES"))).isEmpty())
    verify(options(listOf(a,b,candidate(FieldId.PRODUCER,"Astrel"),candidate(FieldId.PRODUCER,"Fourth"))).size==3)
    verify(options(listOf(a,b.copy(state=UiState.RED))).isEmpty())
    verify(options(listOf(a,b.copy(sources=emptyList()))).isEmpty())
    verify(options(listOf(a,candidate(FieldId.PRODUCER,"750ml"))).isEmpty())
    verify(options(listOf(a,candidate(FieldId.PRODUCER,"Importer Name",raw="Imported by Importer Name"))).isEmpty())
    verify(options(listOf(a,b.copy(field=FieldId.WINE_NAME))).isEmpty())
    // Region-only reading must not produce a catalogue list of subregions.
    val s1=candidate(FieldId.SUBREGION,"Oakville",raw="Napa Valley")
    val s2=candidate(FieldId.SUBREGION,"Rutherford",raw="Napa Valley")
    val geo=result.copy(fallbackCandidates=mapOf(FieldId.SUBREGION to listOf(s1,s2)))
    verify(ScanChoicePolicy.choices(FieldId.SUBREGION,geo,blank+(FieldId.REGION to "Napa Valley"),2026).isEmpty())
    val state=candidate(FieldId.REGION,"California",UiState.GREEN)
    val us=candidate(FieldId.COUNTRY,"United States",UiState.GREEN)
    val onlyState=CellarFieldPresentation.apply(FillResult(empty+(FieldId.REGION to state)+(FieldId.COUNTRY to us),null))
    verify(onlyState.fields.getValue(FieldId.COUNTRY).value=="California")
    verify(onlyState.fields.getValue(FieldId.REGION).value.isBlank() && onlyState.fields.getValue(FieldId.SUBREGION).value.isBlank())
    verify(ScanFormState().apply(onlyState,blank,2026).fields.getValue(FieldId.SUBREGION).choices.isEmpty())
    val valley=CellarFieldPresentation.apply(FillResult(empty+(FieldId.COUNTRY to us)+(FieldId.REGION to state)+(FieldId.SUBREGION to candidate(FieldId.SUBREGION,"Napa Valley")),null))
    verify(FieldId.SUBREGION in valley.intentionalBlankFields && FieldId.REGION !in valley.intentionalBlankFields)
    // Actual joint selector: ties expose only supported rivals, a clear winner still selects normally.
    val tie=IdentityAssignment.select(listOf(IdentityOption(a,90.0),IdentityOption(b,90.0)),emptyList())
    verify(tie.fields.getValue(FieldId.PRODUCER).single().value.isBlank())
    verify(tie.fallbackCandidates.getValue(FieldId.PRODUCER).size==2)
    val winner=IdentityAssignment.select(listOf(IdentityOption(a,95.0),IdentityOption(b,50.0)),emptyList())
    verify(winner.fields.getValue(FieldId.PRODUCER).single().value==a.value)
    verify(winner.fallbackCandidates[FieldId.PRODUCER].isNullOrEmpty())
    // Editing a related field invalidates stale choices, even while the dependent field is RED.
    val wine=ReviewedField(state=UiState.RED,choices=listOf(ScanChoicePolicy.Choice("First",""),ScanChoicePolicy.Choice("Second","")))
    verify(form.copy(fields=form.fields+(FieldId.WINE_NAME to wine)).edit(FieldId.PRODUCER,a.value).fields.getValue(FieldId.WINE_NAME).choices.isEmpty())
    val v=result.copy(fallbackCandidates=mapOf(FieldId.VINTAGE to listOf(candidate(FieldId.VINTAGE,"2019"),candidate(FieldId.VINTAGE,"2020"))))
    verify(ScanChoicePolicy.choices(FieldId.VINTAGE,v,blank,2026).size==2)
    verify(ScanChoicePolicy.choices(FieldId.VINTAGE,v.copy(fallbackCandidates=mapOf(FieldId.VINTAGE to listOf(candidate(FieldId.VINTAGE,"2019",raw="Founded 2019"),candidate(FieldId.VINTAGE,"2099")))),blank,2026).isEmpty())
    // Compile and exercise the real parser path, not only invented option lists.
    val engine=FillEngine(FixtureKnowledgeBase())
    val production=engine.fill(a.sources.flatMap { it.members },2026)
    verify(production.fields.keys==FieldId.entries.toSet())
    verify(ScanStats.rows(result,2026).size==6)
    verify(ScanStats.rows(result,2026).first { it[0]=="producer" }[3]=="2")
    verify(ScanStats.text(result,123L,2026).contains("Filled: 0/6"))
    verify(ScanStats.text(result,123L,2026).contains("not accuracy"))
    verify(ScanStats.rows(retake,2026).first { it[0]=="producer" }[1]==b.value)
    println("PASS: $checks actual Kotlin assignment/policy/form-state checks; host geometry fixtures, not Android UI")
}
