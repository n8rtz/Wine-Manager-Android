package com.yourapp.wine

import com.yourapp.wine.extract.*
import com.yourapp.wine.merge.*
import com.yourapp.wine.ocr.MergedToken
import com.yourapp.wine.fill.*

object WrappedBrandChecks {
    fun run() {
        fun line(text:String,y:Float,x:Float=.2f,w:Float=.6f):LabelLine {
            val record=LogicChecks.token(text,.9f,y=y,x=x,w=w,h=.12f)
            return LabelLine(text,listOf(MergedToken(text,listOf(record),.9f,record.bboxRect)),record.bboxRect)
        }
        // Synthetic names exercise the assembly independently of the reported bottle.
        val first=line("COLINAS de",.2f)
        val last=line("LUNAVISTA",.35f)
        val style=line("CRIANZA",.52f)
        val brand=WrappedBrandContext.candidates(listOf(first,last,style)).single()
        check(brand.candidate.value=="COLINAS de LUNAVISTA" && brand.candidate.state==UiState.YELLOW)
        check(brand.designations.single().value=="Crianza")
        check(WrappedBrandContext.candidates(listOf(first,last)).isEmpty())
        check(WrappedBrandContext.candidates(listOf(first,line("LUNAVISTA",.8f),style)).isEmpty())
        check(WrappedBrandContext.candidates(listOf(first,line("LUNAVISTA",.35f,x=.85f,w=.15f),style)).isEmpty())
        check(WrappedBrandContext.candidates(listOf(first,last,line("OTRONOMBRE",.35f),style)).isEmpty())
        check(WrappedBrandContext.candidates(listOf(first,last,style,line("RESERVA",.54f))).isEmpty())
        check(WrappedBrandContext.candidates(listOf(line("La Reserve de",.2f),last,style)).isEmpty())
        check(WrappedBrandContext.candidates(listOf(first,line("Ribera del Duero",.35f),style)).isEmpty())
        check(WrappedBrandContext.candidates(listOf(first,last,style,line("Bodegas Otro",.8f))).isEmpty())
        val glued=line("COLINASde",.2f)
        val prefix=glued.tokens.single().members.single().copy(textRaw="COLINAS",textNorm="colinas",engineId="paddle-v6-medium")
        val joined=glued.copy(tokens=listOf(glued.tokens.single().copy(members=glued.tokens.single().members+prefix)))
        val connector=line("de",.2f,x=.65f,w=.07f)
        val connectorToken=connector.tokens.single().copy(members=connector.tokens.single().members.map { it.copy(engineId="paddle-v6-medium") })
        val duplicate=joined.copy(text="COLINASde de",tokens=joined.tokens+connectorToken)
        val fixed=WrappedBrandContext.reconcile(duplicate)
        check(fixed.text=="COLINAS de" && fixed.tokens===duplicate.tokens)
        check(fixed.tokens.first().members.first().textRaw=="COLINASde" && fixed.tokens.first().members.first().score==.9f)
        check(WrappedBrandContext.reconcile(duplicate.copy(tokens=glued.tokens+connectorToken)).text==duplicate.text)
        check(WrappedBrandContext.reconcile(duplicate.copy(tokens=duplicate.tokens.map { it.copy(members=it.members.map { m -> m.copy(engineId="mlkit") }) })).text==duplicate.text)
        check(LabelLayoutContext.repair(line("COTES DU RHONE VIILAS",.4f)).text=="Côtes du Rhône Villages")
        check(LabelLayoutContext.repair(line("COTES DU RHONE VIOLET",.4f)).text=="COTES DU RHONE VIOLET")
        println("PASS: wrapped brand assembly, split/joined engine reconciliation, immutable sources, missing/tied/misaligned/style/geography negative controls")
    }
}
