package com.yourapp.wine.textlab

import android.graphics.RectF
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import java.io.File

/** Host-only regression using text/geometry from the reported blank-table batch. */
object TextLabFieldChecks {
    fun run() {
        val records=File("app/src/testDebug/resources/textlab/table-regression.tsv").readLines().map { line ->
            val (text,coords)=line.split('\t');val v=coords.split(' ').map(String::toFloat)
            val box=RectF(v[0],v[1],v[2],v[3])
            TokenRecord(text,TextNorm.normalize(text),.5f,box,box,emptyList(),"mlkit_reference","single",true)
        }
        check(records.size==11)
        val kb=FixtureKnowledgeBase()
        check(FillEngine(kb).fill(records,2026).fields.values.all { it.value.isBlank() })
        val extractor=TextLabFieldExtractor(kb)
        val result=extractor.extract(records,2026)
        check(result.fields.getValue(FieldId.VINTAGE).value=="2023")
        check(result.fields.getValue(FieldId.COUNTRY).value=="Spain")
        check(result.fields.values.none { it.state==UiState.GREEN })
        check(result.fields.values.flatMap { it.sources }.flatMap { it.members }.all { it.score==.5f && it.scoreIsEstimated })
        check(extractor.extract(emptyList(),2026).fields.values.all { it.value.isBlank() && it.state==UiState.RED })
        check(runCatching { extractor.extract(records+records.first().copy(variantId="p02"),2026) }.exceptionOrNull() is IllegalArgumentException)
        val journal=TextLabJournal(setOf("candidate_3"),"host",true)
        for(status in listOf("suggestions","no_fields","no_readings","failed")) {
            journal.add("candidate_3","field_summary",status,mapOf("ocr_status" to "complete","field_values_found" to "2"))
        }
        val statuses=TextLabWineTable.rows(listOf(journal)).map { it[2] }
        check(statuses==listOf("OCR: complete / 2 fields suggested","OCR: complete / no fields identified",
            "OCR: complete / no accepted OCR lines","OCR: complete / extraction failed"))
        println("PASS: uploaded OCR -> lab fields; no score inflation/GREEN; production gate unchanged; empty and mixed-pass guards")
    }
}
