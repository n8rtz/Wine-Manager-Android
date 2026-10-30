package com.yourapp.wine.phonecorpus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlinerLabBaselinesTest {
    private fun board(rows:List<List<String>>) = PhoneCorpusScoreboard.Board(
        headers=emptyList(), rows=rows, text="", csv=emptyList(), details=emptyList(),
        funnel=emptyList(), matrix=emptyList(),
    )

    @Test fun usesRealLabAndProdRowsAndTheirOwnFieldScores() {
        val lab=listOf("Lab v7","124","121","81/121","34","6","0")
        val best=listOf("Best build","124","121","99/121","0","0","0")
        val prod=listOf("Prod v6.0","124","121","77/121","30","14","0")
        val labFields=mapOf("producer" to WineAppLabelsBuildLog.FieldScore(102,21))
        val prodFields=mapOf("producer" to WineAppLabelsBuildLog.FieldScore(100,23))
        val state=WineAppLabelsState(board=board(listOf(lab,best,prod)),currentBuildFieldScores=labFields,prodV6FieldScores=prodFields)
        val scores=glinerLabBaselines(state)
        assertEquals(lab,scores[0].summary)
        assertEquals(prod,scores[1].summary)
        assertEquals(labFields,scores[0].fields)
        assertEquals(prodFields,scores[1].fields)
    }

    @Test fun missingOrUnattemptedBaselinesRemainUnavailable() {
        val state=WineAppLabelsState(
            board=board(listOf(listOf("Lab v7","0","0","0/0","0","0","0"))),
            currentBuildFieldScores=mapOf("producer" to WineAppLabelsBuildLog.FieldScore(0,0)),
        )
        glinerLabBaselines(state).forEach { baseline ->
            assertNull(baseline.summary)
            assertTrue(baseline.fields.isEmpty())
        }
    }
}
