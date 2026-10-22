package com.yourapp.wine

import android.graphics.RectF
import com.yourapp.wine.extract.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.merge.*
import com.yourapp.wine.ocr.MergedToken

object LabelLayoutChecks {
    fun run() {
        fun line(text:String,y:Float,x:Float=.2f,w:Float=.6f,h:Float=.07f):LabelLine {
            val box=RectF(x,y,x+w,y+h)
            val token=LogicChecks.token(text,.83f,x=x,y=y,w=w,h=h)
            return LabelLine(text,listOf(MergedToken(text,listOf(token),.83f,box)),box)
        }
        val raw=line("UIGNERONS de l'Enclave",.8f)
        val repaired=LabelLayoutContext.repair(raw)
        check(repaired.text=="Vignerons de l'Enclave")
        check(repaired.tokens===raw.tokens && repaired.tokens.single().members.single().textRaw==raw.text)
        for(text in listOf("Vignerons de l'Enclave","Uignerons","Uignerons Hills","Domaine Uignerons de la Mer",
            "Côtes du Rhône","Domaine Cotes du Rhone Vilias","Cotes du Rhone Vilias Estate","Cotes du Rhone Violet"))
            check(LabelLayoutContext.repair(line(text,.1f)).text==text) { text }
        check(LabelLayoutContext.repair(line("COTES DU RHONE VILIAS",.5f)).text=="Côtes du Rhône Villages")
        val title=listOf(line("La Reserve",.1f),line("DE",.2f,x=.45f,w=.1f,h=.04f),line("L'Enclaue",.27f))
        val supported=LabelLayoutContext.titles(title+repaired).single()
        check(supported.value=="La Reserve de L'Enclave" && supported.state==UiState.YELLOW)
        check(supported.spelling?.editDistance==1)
        check(supported.sources.none { it in repaired.tokens }) // Producer support must not masquerade as title OCR.
        check(LabelLayoutContext.titles(title).single().value.endsWith("Enclaue")) // No dictionary guess.
        check(LabelLayoutContext.titles(title+repaired+line("Vignerons de l'Enclaue",.9f)).single().value.endsWith("Enclaue")) // Exact protected.
        check(LabelLayoutContext.titles(title+repaired+line("Vignerons de l'Encloue",.9f)).single().value.endsWith("Enclaue")) // Tie abstains.
        check(LabelLayoutContext.titles(title.take(2)+line("L'Enclaue",.8f)).isEmpty())
        check(LabelLayoutContext.titles(title.take(2)+line("L'Enclaue",.27f,x=.85f,w=.1f)).isEmpty())
        check(LabelLayoutContext.titles(title+line("Another Title",.09f)).isEmpty())
        val paragraph=listOf(line("hommage a la",.45f,x=.02f,w=.15f,h=.03f),
            line("communaute ancienne",.49f,x=.02f,w=.15f,h=.03f),line("au sud de la",.53f,x=.02f,w=.15f,h=.03f))
        check(LabelLayoutContext.paragraphLines(title+paragraph).containsAll(paragraph))
        val smallProducer=line("Vignerons de la Rive",.57f,x=.02f,w=.15f,h=.03f)
        check(smallProducer !in LabelLayoutContext.paragraphLines(title+paragraph+smallProducer))
        println("PASS: bounded role/suffix repair, immutable evidence, stacked title geometry, label-local repair exact/tie guards, paragraph and small producer protection")
    }
}
