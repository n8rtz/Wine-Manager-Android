package com.yourapp.wine.textlab.evidence

/** Diagnostic only: never read by ranking or selection. Bounded rows, complete reason counters. */
internal class EvidenceTrace(private val limit:Int=512) {
    val rows=mutableListOf<String>()
    val counts=linkedMapOf<String,Int>()
    var droppedRows:Int=0
        private set
    fun count(reason:String,amount:Int) { counts[reason]=(counts[reason] ?: 0)+amount }
    fun add(reason:String,details:()->String) {
        count(reason,1)
        if(rows.size<limit) rows+="$reason; ${details()}" else droppedRows++
    }
    fun add(reason:String,details:String="") {
        counts[reason]=(counts[reason] ?: 0)+1
        if(rows.size<limit) rows+="$reason; $details" else droppedRows++
    }
}
