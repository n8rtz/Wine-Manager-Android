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
    verify(M.merge(listOf(d("top",0.0,0.0,100.0),d("bottom",0.0,18.0,100.0))).size==2) // exact .8 height
    verify(M.merge(listOf(d("top",0.0,0.0,100.0),d("bottom",50.0,12.0,100.0))).size==2) // exactly 50%
    verify(M.merge(listOf(d("top",0.0,0.0,100.0),d("bottom",49.9,12.0,100.0))).size==1)
    verify(M.merge(listOf(d("long top",0.0,0.0,100.0),d("short",40.0,12.0,20.0))).size==1)
    val columns=listOf(d("left",0.0,0.0,40.0),d("right",200.0,0.0,40.0),d("below-left",0.0,15.0,40.0),d("below-right",200.0,15.0,40.0))
    verify(M.merge(columns).map { it.mergedText }==listOf("left below-left","right below-right"))
    val chain=listOf(d("one",0.0,0.0,30.0),d("two",40.0,0.0,30.0),d("three",80.0,0.0,50.0),d("four",0.0,15.0,100.0),d("five",0.0,30.0,100.0))
    verify(M.merge(chain).single().mergedText=="one two three four five")
    verify(M.merge(chain).flatMap { it.sourceIndices }.sorted()==chain.indices.toList())
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
    val proposals=TextLabSpatialPhrases.propose(tokens)
    verify(proposals.size==3)
    verify(proposals.single { it.engineId=="mlkit" && it.variantId=="V0" }.phrase.mergedText=="Domaine Test")
    verify(proposals.flatMap { it.sources }.toSet()==tokens.toSet())
    // Every input is preserved exactly once, including duplicate observations (no fabricated consensus).
    val duplicate=M.merge(listOf(name[0],name[0]))
    verify(duplicate.flatMap { it.sourceIndices }.sorted()==listOf(0,1))
    for(seed in 0..49) verify(M.merge(chain.shuffled(kotlin.random.Random(seed))).single().mergedText=="one two three four five")
    println("PASS: $checks actual Kotlin spatial merging / provenance / engine-pass isolation checks; host geometry fixtures, not Android OCR.")
}
