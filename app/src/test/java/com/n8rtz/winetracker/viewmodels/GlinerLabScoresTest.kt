package com.n8rtz.winetracker.viewmodels

import com.yourapp.wine.gliner.GlinerFieldResult
import com.yourapp.wine.gliner.GlinerLabImageResult
import com.yourapp.wine.gliner.GlinerLabSample
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import org.junit.Assert.assertEquals
import org.junit.Test

class GlinerLabScoresTest {
    private fun row(image:String, unresolved:String?=null, failure:Boolean=false):GlinerLabRow {
        val targets=PhoneCorpusMath.fields.associateWith { field ->
            PhoneCorpusMath.Target(if(field==unresolved) "unresolved" else "expected_value", setOf("value"), "value")
        }
        val sample=GlinerLabSample(image,1,image,"",true,targets)
        val fields=PhoneCorpusMath.fields.map { field ->
            GlinerFieldResult(field,field,"value","value","",when {
                failure -> "execution_failed"
                field==unresolved -> "unscored_filled"
                else -> "correct_value"
            })
        }
        val result=GlinerLabImageResult(
            image=image,reviewNumber=1,status=if(failure) "error" else "complete",message="",
            ocrLines=emptyList(),observations=emptyList(),laneTexts=emptyMap(),residualTexts=emptyList(),
            fields=emptyMap(),fieldStates=emptyMap(),fieldResults=fields,eligible=unresolved==null,
            errorCount=if(failure) 6 else if(unresolved!=null) 1 else 0,
            wordCount=0,spanCount=0,layout="",outputShape="",modelSignature="",int4=true,
            ocrMs=0,glinerMs=0,postMs=0,totalMs=0,notes="",
        )
        return GlinerLabRow(0,sample,result=result)
    }

    @Test fun unresolvedFieldsDoNotCountAsErrorsInFieldTable() {
        val aggregate=aggregateGlinerRows(listOf(row("one"),row("two",unresolved="producer")),false)
        assertEquals(2,aggregate.attempted)
        assertEquals(1,aggregate.eligible)
        assertEquals(1,aggregate.complete)
        assertEquals(1,aggregate.fieldTotal["producer"])
        assertEquals(1,aggregate.fieldCorrect["producer"])
        assertEquals(2,aggregate.fieldTotal["wine_name"])
    }

    @Test fun failedLabelsCountInMultiErrorAndSingleRetestKeepsOtherRows() {
        val existing=listOf(row("one"),row("two",failure=true))
        val before=aggregateGlinerRows(existing,false)
        assertEquals(1,before.failed)
        assertEquals(1,before.multiError)
        val after=aggregateGlinerRows(existing.map { if(it.sample.image=="two") row("two") else it },false)
        assertEquals(2,after.attempted)
        assertEquals(2,after.complete)
        assertEquals(0,after.failed)
    }
}
