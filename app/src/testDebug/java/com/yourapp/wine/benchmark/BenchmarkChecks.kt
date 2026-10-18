package com.yourapp.wine.benchmark

import android.graphics.PointF
import com.yourapp.wine.LogicChecks
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import kotlin.math.abs

object BenchmarkChecks {
    fun recipesAreBoundedAndGroundTruthDoesNotChooseThem() {
        val recipes=BenchmarkPlan.cases
        check(recipes.size==30 && recipes.map { it.id }.distinct().size==30)
        check(recipes.first().id=="baseline")
        check(recipes.count { it.thin }==2)
        check(recipes.filter { it.thin }.all { it.window>0 })
        check(recipes.all { it.window in listOf(0,25,51) && abs(it.shear)<=.24 })
    }
    fun shearRetainsCornersAndMapsBack() {
        for(slope in listOf(-.24,-.12,.12,.24)) {
            val matrix=BenchmarkGeometry.shear(1280,1805,slope)
            for(p in listOf(PointF(0f,0f),PointF(1280f,0f),PointF(1280f,1805f),PointF(0f,1805f),PointF(600f,900f))) {
                val variant=matrix.map(p)
                check(variant.x>=-.001 && variant.x<=1280.001 && variant.y>=0 && variant.y<=1805)
                val back=matrix.inverse().map(variant)
                check(abs(back.x-p.x)<.001 && abs(back.y-p.y)<.001)
            }
        }
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
