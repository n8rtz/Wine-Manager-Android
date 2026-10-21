package com.yourapp.wine.textlab

import kotlin.math.*
import com.yourapp.wine.geom.Matrix3

/** Adapted from datalab-to/indra det_post.py/vocab.py (Apache-2.0). Bounded phone profile. */
internal object IndraMath {
    fun turnMatrix(k:Int,w:Int,h:Int):Matrix3=when(k) {
        0 -> Matrix3.scale(1.0,1.0)
        1 -> Matrix3(listOf(0.0,-1.0,h.toDouble(),1.0,0.0,0.0,0.0,0.0,1.0))
        2 -> Matrix3(listOf(-1.0,0.0,w.toDouble(),0.0,-1.0,h.toDouble(),0.0,0.0,1.0))
        3 -> Matrix3(listOf(0.0,1.0,0.0,-1.0,0.0,w.toDouble(),0.0,0.0,1.0))
        else -> error("Unsupported turn $k")
    }

    data class Box(val left:Float,val top:Float,val right:Float,val bottom:Float,val score:Float)
    data class Regions(val boxes:List<Box>,val components:Int,val truncated:Boolean)
    data class Reading(val raw:String,val confidence:Float) { val accepted get()=confidence>=.5f }
    fun decode(ids:LongArray,confidence:FloatArray,length:Int,vocab:List<String>):Reading {
        require(length in 0..ids.size && length<=confidence.size) { "Greedy length $length exceeds ids=${ids.size} confidence=${confidence.size}" }
        val tokens=mutableListOf<String>();var sum=0.0;var previous=-1L
        for(i in 0 until length) {
            val id=ids[i];val p=confidence[i]
            require(id in 0L..vocab.size.toLong() && p.isFinite() && p in 0f..1f) { "Invalid greedy step=$i id=$id vocabulary=${vocab.size} posterior=$p" }
            if(id!=0L && id!=previous) { tokens+=vocab[id.toInt()-1];sum+=p }
            previous=id
        }
        return Reading(tokensText(tokens),if(tokens.isEmpty()) 0f else (sum/tokens.size).toFloat())
    }
    fun tokensText(tokens:List<String>):String=buildString {
        tokens.forEachIndexed { i,t ->
            append(t)
            if(t.length>2 && t.startsWith("\\") && t.drop(1).all(Char::isLetter) &&
                tokens.getOrNull(i+1)?.firstOrNull()?.isLetter()==true) append(' ')
        }
    }
    /** Four-connected core components; output distances are in detector INPUT pixels, stride=2. */
    fun regions(out:FloatArray,w:Int,h:Int,scale:Double,baseW:Int,baseH:Int,maxLines:Int=32,checkActive:()->Unit):Regions {
        require(w in 1..1024 && h in 1..1024 && scale>0 && scale.isFinite())
        val n=w*h;require(out.size>=3*n)
        for(i in 0 until 3*n) { if(i%4096==0) checkActive();require(out[i].isFinite()) }
        val seen=BooleanArray(n);val queue=IntArray(n);val boxes=mutableListOf<Box>();var components=0;var capped=false
        for(seed in 0 until n) {
            if(seed%4096==0) checkActive()
            if(seen[seed] || out[seed]<=0f) continue // sigmoid(logit)>0.5
            if(++components>4096) { capped=true;break }
            var head=0;var tail=1;queue[0]=seed;seen[seed]=true
            var minX=w;var maxX=0;var top=0.0;var bottom=0.0;var score=0.0
            fun enqueue(p:Int) { if(!seen[p] && out[p]>0f) { seen[p]=true;queue[tail++]=p } }
            while(head<tail) {
                if(head%4096==0) checkActive()
                val p=queue[head++];val x=p%w;val y=p/w
                minX=min(minX,x);maxX=max(maxX,x)
                val cy=(y+.5)*2;top+=cy-out[n+p];bottom+=cy+out[2*n+p]
                score+=1.0/(1.0+exp(-out[p].toDouble()))
                if(x>0) enqueue(p-1);if(x+1<w) enqueue(p+1)
                if(y>0) enqueue(p-w);if(y+1<h) enqueue(p+w)
            }
            if(tail<2) continue
            val y0=top/tail;val y1=bottom/tail;val ex=.15*max(1.0,y1-y0)
            val box=Box(((minX*2-ex)/scale).coerceIn(0.0,baseW.toDouble()).toFloat(),
                (y0/scale).coerceIn(0.0,baseH.toDouble()).toFloat(),
                (((maxX+1)*2+ex)/scale).coerceIn(0.0,baseW.toDouble()).toFloat(),
                (y1/scale).coerceIn(0.0,baseH.toDouble()).toFloat(),(score/tail).toFloat())
            if(box.right>box.left && box.bottom>box.top) boxes+=box
        }
        return Regions(boxes.sortedByDescending { it.score }.take(maxLines).sortedWith(compareBy({it.top},{it.left})),
            components,capped || boxes.size>maxLines)
    }
    fun percentile(hist:IntArray,count:Int,q:Double):Double {
        require(count>0 && hist.size==256)
        val x=q*(count-1);val low=floor(x).toInt();val high=ceil(x).toInt()
        fun at(index:Int):Int { var sum=0;for(i in hist.indices) { sum+=hist[i];if(sum>index) return i };error("Bad histogram") }
        return at(low)+(at(high)-at(low))*(x-low)
    }
    fun dark(hist:IntArray,count:Int):Boolean {
        if(count==0) return false
        val lo=percentile(hist,count,.02);val mid=percentile(hist,count,.5);val hi=percentile(hist,count,.98)
        return hi-mid>2*(mid-lo)+8
    }
}
