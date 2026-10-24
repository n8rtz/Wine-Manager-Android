package com.yourapp.wine

import android.graphics.RectF
import com.yourapp.wine.extract.*
import com.yourapp.wine.merge.*
import com.yourapp.wine.ocr.MergedToken

object LabelEvidenceChecks {
    fun run() {
        fun line(text:String,y:Float,other:String?=null,sameEngine:Boolean=false):LabelLine {
            val record=LogicChecks.token(text,.9f,y=y,h=.07f,x=.2f,w=.6f)
            val members=listOf(record)+listOfNotNull(other?.let { LogicChecks.token(it,.45f,y=y,h=.07f,x=.2f,w=.6f,
                engine=if(sameEngine) "mlkit" else "paddle-v6-medium",variant="V1") })
            val token=MergedToken(text,members,.9f,record.bboxRect)
            return LabelLine(text,listOf(token),record.bboxRect)
        }
        val title=line("L'Enclaue",.27f,"L'Enclave")
        val maker=line("Vignerons de 'Eclave",.8f)
        val fixed=LabelAlternativeEvidence.repeatedTails(listOf(title,maker))
        check(fixed.lines.map { it.text }==listOf("L'Enclave","Vignerons de L'Enclave"))
        check(fixed.lines.zip(listOf(title,maker)).all { (a,b) -> a.tokens===b.tokens })
        check(fixed.lines.first().tokens.single().members.last().score==.45f)
        check(LabelAlternativeEvidence.repeatedTails(listOf(line("L'Enclaue",.27f,"L'Enclave",true),maker)).changes.isEmpty())
        check(LabelAlternativeEvidence.repeatedTails(listOf(title)).changes.isEmpty())
        check(LabelAlternativeEvidence.repeatedTails(listOf(title,line("Vignerons de l'Enclaue",.8f))).changes.isEmpty())
        check(LabelAlternativeEvidence.repeatedTails(listOf(title,maker,line("Vignerons de l'Enclaue",.9f))).changes.isEmpty())
        check(LabelAlternativeEvidence.repeatedTails(listOf(line("L'Enclove",.27f,"L'Enclave"),line("Vignerons de Encluve",.8f))).changes.isEmpty())
        check(LabelAlternativeEvidence.repeatedTails(listOf(title,line("Vignerons de 'Eclave",.28f))).changes.isEmpty())
        val heading=line("La Reserve",.1f)
        val connector=LogicChecks.token("DE",.54f,y=.2f,h=.04f,x=.45f,w=.1f)
        check(TokenMerger().merge(listOf(connector)).isEmpty()) // Ordinary singleton admission unchanged.
        val extra=LabelAlternativeEvidence.connectors(listOf(heading,title),listOf(connector))
        check(extra.size==1 && extra.single().tokens.single().vote==.54f)
        check(LabelLayoutContext.titles(listOf(heading,title)+extra).single().sources.any { connector in it.members })
        check(LabelAlternativeEvidence.connectors(listOf(heading),listOf(connector)).isEmpty())
        check(LabelAlternativeEvidence.connectors(listOf(heading,title),listOf(connector.copy(score=.39f))).isEmpty())
        check(LabelAlternativeEvidence.connectors(listOf(heading,title),listOf(connector.copy(textRaw="XYZ"))).isEmpty())
        check(LabelAlternativeEvidence.connectors(listOf(heading,title),listOf(connector.copy(bboxRect=RectF(.9f,.2f,1f,.24f)))).isEmpty())
        check(LabelAlternativeEvidence.connectors(listOf(heading,title),listOf(connector,connector.copy(textRaw="DU"))).isEmpty())
        check(LabelAlternativeEvidence.connectors(listOf(heading,title.copy(tokens=title.tokens.map { it.copy(vote=.65f) })),listOf(connector)).isEmpty())
        check(LabelTextRoles.isDamagedRhoneAppellation("COTES DU RHNNEVVIA"))
        check(IdentityRole.ORIGIN_SUSPECT in IdentityRoles.classify(line("COTES DU RHNNEVVIA",.5f),emptyList()).roles)
        for(text in listOf("Côtes du Rhône","Côtes du Rhône Villages","Domaine Cotes du Rhonne","Cotes du Rhonda Estate","Cotes du Other"))
            check(!LabelTextRoles.isDamagedRhoneAppellation(text)) { text }
        println("PASS: title-only weak connector admission, unchanged merge gate/scores, alternate-reading provenance, independent engines, exact/tie/geometry guards, damaged appellation exclusion")
    }
}
