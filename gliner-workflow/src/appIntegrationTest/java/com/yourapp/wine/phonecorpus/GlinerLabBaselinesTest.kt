package com.yourapp.wine.phonecorpus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlinerLabBaselinesTest {
    private fun rows(image:String, wrong:String?=null, unresolved:String?=null, failed:Boolean=false) =
        PhoneCorpusMath.fields.map { field ->
            mapOf("image" to image,"field" to field,
                "reference_status" to if(field==unresolved) "unresolved" else "expected_value",
                "outcome" to when {
                    failed -> "execution_failed"
                    field==unresolved -> "unscored_filled"
                    field==wrong -> "wrong_value"
                    else -> "correct_value"
                },
                "form_status" to if(failed) "execution_failed" else "complete")
        }

    @Test fun baselinesAdvanceOnTheExactCompletedPhotoSubset() {
        val lab=mapOf("a" to rows("a"), "b" to rows("b",wrong="producer"), "c" to rows("c",failed=true))
        val prod=mapOf("a" to rows("a",wrong="wine_name"), "b" to rows("b"), "c" to rows("c"))
        val state=WineAppLabelsState(comparisonFieldRows=mapOf(PhoneCorpusRunner.Mode.LAB to lab,PhoneCorpusRunner.Mode.PRODUCTION_V6 to prod))
        val initial=glinerLabBaselines(state,emptySet())
        assertEquals(listOf("Lab v7","3","3","1/3","1","1","1"),initial[0].summary)
        assertEquals(listOf("Prod v6.0","3","3","2/3","1","0","0"),initial[1].summary)
        assertEquals(3,initial[0].available)
        val first=glinerLabBaselines(state,setOf("b"))
        assertEquals(listOf("Lab v7","1","1","0/1","1","0","0"),first[0].summary)
        assertEquals(listOf("Prod v6.0","1","1","1/1","0","0","0"),first[1].summary)
        assertEquals(WineAppLabelsBuildLog.FieldScore(0,1),first[0].fields["producer"])
        val later=glinerLabBaselines(state,setOf("b","c"))
        assertEquals(listOf("Lab v7","2","2","0/2","1","1","1"),later[0].summary)
        assertEquals(listOf("Prod v6.0","2","2","2/2","0","0","0"),later[1].summary)
        assertEquals(0,later[0].missing)
        val noOverlap=glinerLabBaselines(state,setOf("not-saved"))
        assertNull(noOverlap[0].summary)
        assertTrue(noOverlap[0].fields.isEmpty())
        assertEquals(1,noOverlap[0].missing)
        assertEquals(3,noOverlap[0].available)
    }

    @Test fun incompleteReferencesAndMissingBaselinesAreNotMisrepresented() {
        val lab=mapOf("a" to rows("a",unresolved="producer"), "bad" to rows("bad").dropLast(1))
        val state=WineAppLabelsState(comparisonFieldRows=mapOf(PhoneCorpusRunner.Mode.LAB to lab))
        val scores=glinerLabBaselines(state,setOf("a","bad","missing"))
        assertEquals(listOf("Lab v7","1","0","0/0","0","0","0"),scores[0].summary)
        assertEquals(WineAppLabelsBuildLog.FieldScore(0,0),scores[0].fields["producer"])
        assertEquals(2,scores[0].missing)
        assertNull(scores[1].summary)
        assertEquals(3,scores[1].missing)
        assertTrue(scores[1].fields.isEmpty())
    }
}
