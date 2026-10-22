package com.yourapp.wine.benchmark

import android.graphics.PointF
import com.yourapp.wine.LogicChecks
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import kotlin.math.abs

object BenchmarkChecks {
    fun recipesAreBoundedAndGroundTruthDoesNotChooseThem() {
        val recipes=BenchmarkPlan.cases
        check(recipes.size==1 && recipes.single().id=="baseline")
    }
    fun scoringDoesNotRewardConfidenceOrInventMissingText() {
        val records=listOf(LogicChecks.token("BLACKSTONE",.95f),LogicChecks.token("California",.99f,y=.7f))
        val fill=CellarFieldPresentation.apply(FillEngine(FixtureKnowledgeBase()).fill(records,2026))
        val expected=mapOf(FieldId.PRODUCER to "Blackstone",FieldId.WINE_NAME to "Merlot",FieldId.COUNTRY to "California",FieldId.VINTAGE to "")
        val missing=BenchmarkScoring.score(expected,fill,records)
        check(missing.exactFields==2 && missing.checkedFields==3 && missing.rawNameSimilarity!!<1.0)
        val correct=records+LogicChecks.token("MERLOT",.4f,y=.5f)
        val scored=BenchmarkScoring.score(expected,fill,correct)
        check(scored.rawNameSimilarity==1.0 && scored.exactFields==2) // Weak raw OCR NOT promoted into a fill.
        check(fill.fields.getValue(FieldId.WINE_NAME).value.isEmpty())
        check(BenchmarkScoring.score(emptyMap(),fill,records).rawNameSimilarity==null)
        check(BenchmarkScoring.distance("reciente","ecientl")==2)
    }
}
