package com.yourapp.wine

import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.ui.ScanFormState

/** Actual report text/scores with rounded rectified bounds; not an OCR simulation. */
object LabelNameChecks {
    fun awardDoesNotDisplacePrintedNameAndStyle() {
        fun line(text:String,score:Float,x:Float,y:Float,w:Float,h:Float) = LogicChecks.token(text,score,x=x,y=y,w=w,h=h)
        val evidence = listOf(
            line("Decanter",.833f,.627f,.167f,.141f,.035f),
            line("95",.887f,.639f,.208f,.130f,.065f),
            line("ENCE",.658f,.523f,.212f,.046f,.049f),
            line("AWAR",.666f,.562f,.268f,.071f,.041f),
            line("ECIENTL",.648f,.105f,.467f,.812f,.093f),
            line("GRAN RESERVA",.827f,.255f,.575f,.468f,.055f),
            line("BODEGAS",.683f,.128f,.651f,.296f,.075f),
            line("OLARRA",.690f,.486f,.665f,.318f,.061f),
            line("RIOJA",.773f,.304f,.773f,.376f,.049f),
            line("CALIFICADA",.727f,.386f,.849f,.214f,.014f))
        val fill=FillEngine(FixtureKnowledgeBase()).fill(evidence,2026)
        check(fill.fields.getValue(FieldId.PRODUCER).value=="Bodegas Olarra") { fill.fields }
        val name=fill.fields.getValue(FieldId.WINE_NAME)
        check(name.value=="Reciente Gran Reserva" && name.state==UiState.YELLOW) { fill.fields }
        check(name.sources.any { it.canonical=="ECIENTL" })
        check("correction" in name.reason)
        val other=FillEngine(FixtureKnowledgeBase()).fill(evidence.filterNot { it.textRaw in setOf("BODEGAS","OLARRA") }+
            line("Domaine Other",.95f,.1f,.65f,.6f,.06f),2026)
        check(other.fields.getValue(FieldId.WINE_NAME).value!="Reciente Gran Reserva")
    }

    fun fragmentsNeverInventMerlotAndPresentationUsesState() {
        val evidence=listOf(LogicChecks.token("BLACKSTONE",.773f,y=.135f,h=.197f),
            LogicChecks.token("I",.625f,x=.231f,y=.364f,w=.013f,h=.025f),
            LogicChecks.token("R",.711f,x=.531f,y=.363f,w=.028f,h=.025f),
            LogicChecks.token("Y",.809f,x=.625f,y=.355f,w=.025f,h=.025f),
            LogicChecks.token("CALIFORNIA",.794f,y=.664f,h=.031f))
        val validated=FillEngine(FixtureKnowledgeBase()).fill(evidence,2026)
        check(validated.fields.getValue(FieldId.WINE_NAME).state==UiState.RED) { validated.fields }
        val display=CellarFieldPresentation.apply(validated)
        check(display.fields.getValue(FieldId.PRODUCER).value=="Blackstone")
        check(display.fields.getValue(FieldId.COUNTRY).value=="California")
        check(display.fields.getValue(FieldId.REGION).value.isEmpty())
        val readName=CellarFieldPresentation.apply(FillEngine(FixtureKnowledgeBase()).fill(evidence+
            LogicChecks.token("Merlot",.82f,y=.56f,h=.06f),2026))
        check(readName.fields.getValue(FieldId.WINE_NAME).value=="Merlot")
        val form=ScanFormState().edit(FieldId.COUNTRY,"Oregon").apply(display, mapOf(FieldId.COUNTRY to "Oregon"))
        check(form.fields.getValue(FieldId.COUNTRY).value=="Oregon")
        val conflict=validated.copy(fields=validated.fields+(FieldId.COUNTRY to validated.fields.getValue(FieldId.COUNTRY)
            .copy(value="France",state=UiState.YELLOW,reason="hierarchy conflict")))
        check(CellarFieldPresentation.apply(conflict).fields.getValue(FieldId.COUNTRY).value=="France")
    }
}
