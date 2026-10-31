package com.yourapp.wine

import com.yourapp.wine.gliner.GlinerAuditReference
import com.yourapp.wine.gliner.GlinerAuditRow
import com.yourapp.wine.gliner.GlinerAuditStore
import com.yourapp.wine.gliner.auditFailedFields
import com.yourapp.wine.gliner.scoreGlinerAudit
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlinerAuditScoreTest {
    private val targets=PhoneCorpusMath.fields.associateWith { field ->
        if(field=="subregion") GlinerAuditStore.target("expected_blank","")
        else GlinerAuditStore.target("expected_value",field)
    }
    private fun row(id:String,values:Map<String,String>, refs:Map<String,PhoneCorpusMath.Target> = targets,
                    status:String="complete",excluded:Boolean=false):GlinerAuditRow {
        val reference=GlinerAuditReference(id,"hash",id,refs)
        return GlinerAuditRow(reference,status,"",values,GlinerAuditStore.compare(reference,values,status!="complete"),excluded,JSONObject())
    }
    private val values=PhoneCorpusMath.fields.associateWith { if(it=="subregion") "" else it }

    @Test fun scoringAndFailureTableHandleBlanksUnresolvedFailuresAndExcludedImages() {
        val good=row("good",values)
        val one=row("one",values+("producer" to "wrong"))
        val two=row("two",values+mapOf("wine_name" to "", "subregion" to "unsupported"))
        val partial=row("partial",values,targets+("vintage" to GlinerAuditStore.target("unresolved","")))
        val failed=row("failed",emptyMap(),status="failed")
        val excluded=row("excluded",values,excluded=true)
        val score=scoreGlinerAudit(listOf(good,one,two,partial,failed,excluded))
        assertEquals(6,score.attempted)
        assertEquals(1,score.excluded)
        assertEquals(1,score.failed)
        assertEquals(3,score.eligible)
        assertEquals(1,score.complete)
        assertEquals(1,score.oneError)
        assertEquals(1,score.multiError)
        assertEquals(4,score.fieldTotal.getValue("producer"))
        assertEquals(3,score.fieldCorrect.getValue("producer"))
        assertEquals(3,score.fieldTotal.getValue("vintage"))
        assertEquals(listOf("wine_name","subregion"),auditFailedFields(two).map { it.first })
        val blank=auditFailedFields(two).last().second
        assertEquals("expected_blank",blank["reference_status"])
        assertEquals("unsupported",blank["recognized"])
        assertTrue(auditFailedFields(partial).isEmpty())
        assertTrue(auditFailedFields(failed).isEmpty())
    }
}
