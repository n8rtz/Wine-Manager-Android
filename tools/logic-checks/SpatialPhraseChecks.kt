import com.yourapp.wine.merge.SpatialPhraseMerger as M
import com.yourapp.wine.textlab.TextLabSpatialPhrases
import com.yourapp.wine.ocr.TokenRecord
import android.graphics.RectF

fun main() {
    var checks=0
    fun verify(ok:Boolean) { checks++;check(ok) { "Spatial check $checks failed" } }
    fun d(text:String,x:Double,y:Double,w:Double,h:Double=10.0)=M.Detection(text,M.Box(x,y,x+w,y+h))
    val name=listOf(d("Domaine de la",0.0,0.0,120.0),d("Romanée-Conti",130.0,0.0,120.0))
    val phrase=M.merge(name).single()
    verify(phrase.mergedText=="Domaine de la Romanée-Conti")
    verify(phrase.boundingBoxes==name.map { it.bbox })
    verify(phrase.sourceIndices==listOf(0,1))
    verify(M.merge(name.reversed()).single().mergedText==phrase.mergedText)
    verify(M.merge(name.reversed()).single().sourceIndices==listOf(1,0))
    verify(M.merge(listOf(d("abcd",0.0,0.0,40.0),d("efgh",65.0,0.0,40.0))).size==2) // exact 2.5 threshold
    verify(M.merge(listOf(d("abcd",0.0,0.0,40.0),d("efgh",64.99,0.0,40.0))).size==1)
    verify(M.merge(listOf(d("abcd",0.0,0.0,40.0),d("efgh",35.0,0.0,40.0))).single().mergedText=="abcd efgh")
    val stacked=listOf(d("Domaine de la",0.0,0.0,120.0),d("Romanée-Conti",10.0,17.0,100.0))
    verify(M.merge(stacked).single().mergedText==phrase.mergedText)
    verify(M.merge(stacked.reversed()).single().mergedText==phrase.mergedText)
    verify(M.merge(stacked,allowVerticalJoin={ _,_ -> false }).size==2)
    val threeLines=listOf(d("one",0.0,0.0,100.0),d("two",0.0,12.0,100.0),d("three",0.0,24.0,100.0))
    val bounded=M.merge(threeLines,maxVerticalLines=2)
    verify(bounded.all { it.sourceIndices.size<=2 })
    verify(bounded.flatMap { it.sourceIndices }.sorted()==threeLines.indices.toList())
    verify(M.merge(listOf(d("short",0.0,0.0,100.0,10.0),d("tall",0.0,10.0,100.0,20.0)),maxVerticalHeightRatio=1.45).size==2)
    verify(M.merge(listOf(d("top",0.0,0.0,100.0),d("bottom",0.0,18.0,100.0))).size==2) // exact .8 height
    verify(M.merge(listOf(d("top",0.0,0.0,100.0),d("bottom",50.0,12.0,100.0))).size==2) // exactly 50%
    verify(M.merge(listOf(d("top",0.0,0.0,100.0),d("bottom",49.9,12.0,100.0))).size==1)
    verify(M.merge(listOf(d("long top",0.0,0.0,100.0),d("short",40.0,12.0,20.0))).size==1)
    val columns=listOf(d("left",0.0,0.0,40.0),d("right",200.0,0.0,40.0),d("below-left",0.0,15.0,40.0),d("below-right",200.0,15.0,40.0))
    verify(M.merge(columns).map { it.mergedText }==listOf("left below-left","right below-right"))
    val chain=listOf(d("one",0.0,0.0,30.0),d("two",40.0,0.0,30.0),d("three",80.0,0.0,50.0),d("four",0.0,15.0,100.0),d("five",0.0,30.0,100.0))
    val boundedChain=M.merge(chain)
    verify(boundedChain.map { it.mergedText }==listOf("one two three","four five"))
    verify(boundedChain.all { it.sourceIndices.isNotEmpty() })
    verify(M.merge(chain,maxVerticalLines=5).single().mergedText=="one two three four five")
    verify(boundedChain.flatMap { it.sourceIndices }.sorted()==chain.indices.toList())
    verify(M.merge(emptyList()).isEmpty())
    verify(M.merge(listOf(d(" \t ",0.0,0.0,10.0))).isEmpty())
    verify(M.merge(listOf(d(" Domaine\n de  la ",0.0,0.0,100.0))).single().mergedText=="Domaine de la")
    verify(runCatching { M.Box(0.0,0.0,0.0,10.0) }.isFailure)
    verify(runCatching { M.Box(Double.NaN,0.0,10.0,10.0) }.isFailure)
    verify(runCatching { M.Box(0.0,0.0,Double.POSITIVE_INFINITY,10.0) }.isFailure)
    verify(runCatching { M.merge(List(513) { d("x",0.0,0.0,10.0) }) }.isFailure)
    verify(M.merge(listOf(d("🍷",0.0,0.0,10.0),d("Estate",34.0,0.0,60.0))).size==1) // one code point, not two UTF-16 chars
    val scaled=name.map { it.copy(bbox=M.Box(it.bbox.x1/1000,it.bbox.y1/1000,it.bbox.x2/1000,it.bbox.y2/1000)) }
    verify(M.merge(scaled).single().mergedText==phrase.mergedText)
    fun token(text:String,engine:String,pass:String,box:RectF)=TokenRecord(text,text,.8f,box,box,emptyList(),engine,pass)
    val tokens=listOf(token("Domaine","mlkit","V0",RectF(0f,0f,.2f,.1f)),token("Test","mlkit","V0",RectF(.21f,0f,.3f,.1f)),
        token("Domaine","paddle-v6-medium","V0",RectF(0f,0f,.2f,.1f)),token("Domaine","mlkit","V1",RectF(0f,0f,.2f,.1f)))
    val defaultDisabled=TextLabSpatialPhrases.propose(tokens)
    verify(defaultDisabled.size==4 && defaultDisabled.all { it.phrase.sourceIndices.size==1 })
    val proposals=TextLabSpatialPhrases.propose(tokens,allowHorizontalJoin={ _,_ -> true })
    verify(proposals.size==3)
    verify(proposals.single { it.engineId=="mlkit" && it.variantId=="V0" }.phrase.mergedText=="Domaine Test")
    verify(proposals.flatMap { it.sources }.toSet()==tokens.toSet())
    val stitched=TextLabSpatialPhrases.readings(proposals)
    verify(stitched.size==3)
    val joined=stitched.single { it.engineId=="mlkit" && it.variantId=="V0" }
    verify(joined.textRaw=="Domaine Test" && joined.scoreIsEstimated && joined.score==.8f)
    verify(joined.bboxRect.left==0f && joined.bboxRect.right==.3f)
    verify(joined.bboxOrig.right==.3f && joined.polyOrig.isEmpty())
    verify(stitched.single { it.engineId=="paddle-v6-medium" }===tokens[2])
    val stackedTokens=listOf(
        token("Domaine","mlkit","V0",RectF(.1f,.2f,.7f,.26f)),
        token("de la Romanée-Conti","mlkit","V0",RectF(.1f,.27f,.7f,.33f)),
        token("Cuvée Speciale","mlkit","V0",RectF(.1f,.34f,.7f,.40f)),
    )
    val verticalDisabled=TextLabSpatialPhrases.propose(stackedTokens)
    verify(verticalDisabled.all { it.phrase.sourceIndices.size==1 })
    val verticalBounded=TextLabSpatialPhrases.propose(stackedTokens,allowVerticalJoin={ _,_ -> true })
    verify(verticalBounded.all { it.phrase.sourceIndices.size<=2 })
    verify(verticalBounded.flatMap { it.phrase.sourceIndices }.sorted()==stackedTokens.indices.toList())
    verify(verticalBounded.all { it.sources.all { source -> source.engineId=="mlkit" && source.variantId=="V0" } })
    // Every input is preserved exactly once, including duplicate observations (no fabricated consensus).
    val duplicate=M.merge(listOf(name[0],name[0]))
    verify(duplicate.flatMap { it.sourceIndices }.sorted()==listOf(0,1))
    for(seed in 0..49) verify(M.merge(chain.shuffled(kotlin.random.Random(seed)),maxVerticalLines=5).single().mergedText=="one two three four five")
    println("PASS: $checks actual Kotlin spatial merging / provenance / engine-pass isolation checks; host geometry fixtures, not Android OCR.")
}
