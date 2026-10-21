package com.yourapp.wine.textlab

import org.junit.Assert.*
import org.junit.Test

class TextLabWineTableTest {
    @Test fun passesAndGreedyDiagnosticsNeverMix() {
        val journal=TextLabJournal(setOf("candidate_3"),"test",true)
        journal.add("candidate_3","recognition",values=mapOf("pass_id" to "p01","raw_text" to "2019"))
        journal.add("candidate_3","recognition",values=mapOf("pass_id" to "p02","raw_text" to "2014"))
        journal.add("candidate_3","greedy_recognition",values=mapOf("pass_id" to "p01","raw_text" to "2018"))
        journal.add("mlkit_reference","recognition",values=mapOf("raw_text" to "2020"))
        assertEquals(listOf("2019"),TextLabWineTable.readings(journal,"candidate_3","p01").map { it["raw_text"] })
        assertEquals(2,TextLabWineTable.groups(journal).size)
        assertTrue(TextLabWineTable.readings(journal,"candidate_3","p12").isEmpty())
    }
    @Test fun unknownAndFailedValuesRemainVisibleWithImageIdentity() {
        val batch=TextLabBatch(listOf(TextLabImageKey("one","one.jpg"),TextLabImageKey("two","two.jpg")),setOf("candidate_3"),"test",true)
        batch.journals[0].add("candidate_3","field_summary",values=mapOf("vintage" to "2019","ocr_status" to "complete"))
        batch.journals[1].add("candidate_3","field_summary","failed",values=mapOf("ocr_status" to "not_run_cancelled"))
        val rows=TextLabWineTable.rows(batch.journals)
        assertEquals("2019",rows[0].last())
        assertEquals("—",rows[1].last())
        assertTrue(rows[1][0].contains("two.jpg"))
        assertTrue(rows[1][2].contains("extraction failed"))
        val csv=java.io.StringWriter().also(batch::writeCsv).toString()
        assertTrue(csv.contains("field_summary"))
        assertTrue(csv.contains("vintage"))
        assertTrue(batch.summary().startsWith("COMBINED SCANNING FORM PREVIEW"))
    }
}
