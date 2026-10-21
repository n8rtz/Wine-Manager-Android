package com.yourapp.wine.textlab

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.*

/** Apache-2.0 adaptation of upstream lm.py. Genuine backoff tables; no wine-specific vocabulary. */
internal class IndraLanguageModel private constructor(
    val order:Int,val bits:Int,val vocabSize:Int,private val keys:List<LongArray>,private val values:List<DoubleArray>,
    private val histories:List<LongArray>,private val backoffs:List<DoubleArray>
) {
    val maxLogp:Double=max(values.maxOf { it.maxOrNull() ?: Double.NEGATIVE_INFINITY },-ln(vocabSize.toDouble()))+
        backoffs.sumOf { max(0.0,it.maxOrNull() ?: 0.0) }
    fun logp(history:Long,token:Int):Double {
        fun h(n:Int)=history and ((1L shl (bits*n))-1)
        var longest=0
        for(n in 1 until order) { if(histories[n].binarySearch(h(n))<0) break;longest=n }
        var accumulated=0.0
        for(n in longest downTo 0) {
            val key=(h(n) shl bits) or (token+1).toLong()
            val p=keys[n].binarySearch(key)
            if(p>=0) return accumulated+values[n][p]
            val b=histories[n].binarySearch(h(n))
            if(b>=0) accumulated+=backoffs[n][b]
        }
        return accumulated-ln(vocabSize.toDouble())
    }
    companion object {
        /** Strict bounded NPY parser: no pickle, object arrays, path extraction, or executable data. */
        fun load(file:File,checkActive:()->Unit):IndraLanguageModel=ZipFile(file).use { zip ->
            var total=0L
            fun array(name:String):Pair<String,ByteBuffer> {
                checkActive()
                val entry=requireNotNull(zip.getEntry("$name.npy")) { "Missing LM table $name" }
                require(entry.size in 1..(128L*1024*1024)) { "LM table too large" }
                total+=entry.size;require(total<=256L*1024*1024) { "LM expansion exceeds budget" }
                return zip.getInputStream(entry).use { raw ->
                    val input=DataInputStream(raw);val magic=ByteArray(6);input.readFully(magic)
                    require(magic.contentEquals(byteArrayOf(0x93.toByte(),78,85,77,80,89)))
                    val major=input.readUnsignedByte();input.readUnsignedByte();require(major in 1..2)
                    val hsize=if(major==1) input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                        else Integer.reverseBytes(input.readInt())
                    require(hsize in 1..65536)
                    val header=ByteArray(hsize);input.readFully(header);val text=header.toString(Charsets.US_ASCII)
                    require(Regex("'fortran_order'\\s*:\\s*False").containsMatchIn(text))
                    val type=requireNotNull(Regex("'descr'\\s*:\\s*'([^']+)'").find(text)).groupValues[1]
                    require(type in setOf("<u8","<i8","<u4","<i4","<u2","<i2","|u1","|i1","<f2","<f4","<f8")) { "Unsupported LM dtype $type" }
                    val shape=requireNotNull(Regex("'shape'\\s*:\\s*\\(([^)]*)\\)").find(text)).groupValues[1]
                        .split(',').map(String::trim).filter(String::isNotEmpty)
                    require(shape.size<=1)
                    val count=shape.firstOrNull()?.toInt() ?: 1;require(count in 0..16_000_000)
                    val bytes=Math.multiplyExact(count,type.last().digitToInt())
                    require(bytes.toLong()+hsize+(if(major==1) 10 else 12)==entry.size)
                    val data=ByteArray(bytes);var offset=0
                    while(offset<bytes) { checkActive();val n=min(32768,bytes-offset);input.readFully(data,offset,n);offset+=n }
                    require(input.read()==-1)
                    type to ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                }
            }
            fun longs(name:String):LongArray {
                val (t,b)=array(name);require(t[1]=='u' || t[1]=='i')
                return LongArray(b.remaining()/t.last().digitToInt()) {
                    if(it%4096==0) checkActive()
                    when(t) {
                        "|u1" -> (b.get().toInt() and 255).toLong();"|i1" -> b.get().toLong()
                        "<u2" -> (b.short.toInt() and 65535).toLong();"<i2" -> b.short.toLong()
                        "<u4" -> b.int.toLong() and 0xffffffffL;"<i4" -> b.int.toLong();else -> b.long
                    }
                }
            }
            fun floats(name:String):DoubleArray {
                val (t,b)=array(name);require(t in setOf("<f2","<f4","<f8"))
                return DoubleArray(b.remaining()/t.last().digitToInt()) { if(it%4096==0) checkActive();
                    (when(t) { "<f4" -> b.float.toDouble();"<f8" -> b.double;else -> {
                        val h=b.short.toInt() and 65535;val e=(h ushr 10) and 31;val f=h and 1023
                        require(e!=31) { "Nonfinite half LM value" }
                        (if(h and 32768!=0) -1 else 1)*(if(e==0) f*2.0.pow(-24) else (1024+f)*2.0.pow(e-25))
                    } }).also { v -> require(v.isFinite()) } }
            }
            val order=longs("order").single().toInt();val size=longs("vocab_size").single().toInt()
            val bits=if(zip.getEntry("bits.npy")!=null) longs("bits").single().toInt() else 11
            require(order in 1..5 && bits in 1..20 && order*bits<=61 && size in 1..65536)
            val k=List(order) { longs("k$it") };val v=List(order) { floats("v$it") }
            val hk=List(order) { longs("hk$it") };val hb=List(order) { floats("hb$it") }
            for(n in 0 until order) {
                require(k[n].size==v[n].size && hk[n].size==hb[n].size)
                for(a in listOf(k[n],hk[n])) for(i in a.indices) {
                    if(i%4096==0) checkActive();require(a[i]>=0 && (i==0 || a[i]>a[i-1]))
                }
            }
            IndraLanguageModel(order,bits,size,k,v,hk,hb)
        }
    }
}

/** Prefix beam and upstream pruning, with cancellable scalar backoff lookups instead of NumPy batches. */
internal object IndraBeam {
    private const val NEG=Double.NEGATIVE_INFINITY
    private data class Entry(var blank:Double,var nonblank:Double,val score:Double,val history:Long)
    private fun lse(a:Double,b:Double):Double { if(a==NEG) return b;if(b==NEG) return a;val m=max(a,b);return m+ln(exp(a-m)+exp(b-m)) }
    fun decode(blank:FloatArray,top:FloatArray,ids:LongArray,steps:Int,k:Int,lm:IndraLanguageModel,
        checkActive:()->Unit,vocabularySize:Int=lm.vocabSize):List<Int> {
        require(steps in 0..4096 && k in 1..256 && blank.size>=steps && top.size>=steps*k && ids.size>=steps*k) { "LM tensor lengths: steps=$steps k=$k blank=${blank.size} top=${top.size} ids=${ids.size}" }
        require(vocabularySize in 1..65536) { "Invalid recognizer vocabulary size $vocabularySize" }
        val mask=(1L shl (lm.bits*(lm.order-1)))-1
        var beams=linkedMapOf(emptyList<Int>() to Entry(0.0,NEG,0.0,0))
        for(t in 0 until steps) {
            checkActive();val b0=blank[t].toDouble();require(!b0.isNaN() && b0<=.00001) { "Invalid blank log-probability at step $t: $b0" }
            if(b0>ln(.999)) {
                beams=LinkedHashMap(beams.mapValues { (_,e)->Entry(lse(e.blank,e.nonblank)+b0,NEG,e.score,e.history) });continue
            }
            val next=linkedMapOf<List<Int>,Entry>()
            fun add(p:List<Int>,b:Double,nb:Double,s:Double,h:Long) {
                val old=next[p]
                if(old==null) next[p]=Entry(b,nb,s,h) else { old.blank=lse(old.blank,b);old.nonblank=lse(old.nonblank,nb) }
            }
            val candidates=(0 until k).map { j ->
                val id=ids[t*k+j];val p=top[t*k+j].toDouble()
                // LM vocab_size is a backoff normalization count, not the recognizer token-ID bound.
                require(id in 0L..vocabularySize.toLong()) { "LM top token out of recognizer vocabulary: step=$t rank=$j id=$id vocabulary=$vocabularySize lm_count=${lm.vocabSize}" }
                require(!p.isNaN() && p<=.00001) { "Invalid top log-probability: step=$t rank=$j value=$p" }
                id.toInt() to p
            }.filter { it.first!=0 && it.second>=ln(.003) }
            for((p,e) in beams) {
                add(p,lse(e.blank,e.nonblank)+b0,NEG,e.score,e.history)
                candidates.filter { it.first==p.lastOrNull() }.forEach { (_,v)-> add(p,NEG,e.nonblank+v,e.score,e.history) }
            }
            val floor=if(beams.size>=8) next.values.minOf { lse(it.blank,it.nonblank)+it.score } else NEG
            val extensions=mutableListOf<Triple<List<Int>,Pair<Int,Double>,Entry>>()
            for((p,e) in beams) for((c,lp) in candidates) {
                val from=(if(c==p.lastOrNull()) e.blank else lse(e.blank,e.nonblank))+lp
                if(from==NEG) continue
                val q=p+c
                if(q in next) add(q,NEG,from,e.score,e.history)
                else if(from+e.score+1.5+.5*lm.maxLogp>=floor-1e-9) extensions+=Triple(p,c to from,e)
            }
            for((p,cv,e) in extensions) {
                checkActive();val(c,from)=cv
                add(p+c,NEG,from,e.score+.5*lm.logp(e.history,c)+1.5,((e.history shl lm.bits) or (c+1).toLong()) and mask)
            }
            beams=LinkedHashMap(next.entries.sortedByDescending { lse(it.value.blank,it.value.nonblank)+it.value.score }.take(8).associate { it.toPair() })
        }
        return beams.maxBy { lse(it.value.blank,it.value.nonblank)+it.value.score }.key
    }
}
