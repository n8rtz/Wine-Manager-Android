package com.yourapp.wine.textlab

/** Sigmoid probabilities may have float32 boundary roundoff. Never guess logits or apply a second sigmoid. */
internal object PaddleProbabilityMap {
    const val EPSILON=1e-5f
    data class Stats(val min:Float,val max:Float,val nonFinite:Int,val belowZero:Int,val aboveOne:Int,
        val outsideTolerance:Int,val firstInvalidIndex:Int,val firstInvalidValue:Float?) {
        fun fields():Map<String,String> = mapOf("probability_raw_min" to min.toString(),"probability_raw_max" to max.toString(),
            "probability_nonfinite_count" to nonFinite.toString(),"probability_below_zero_count" to belowZero.toString(),
            "probability_above_one_count" to aboveOne.toString(),"probability_outside_tolerance_count" to outsideTolerance.toString(),
            "probability_first_invalid_index" to firstInvalidIndex.toString(),
            "probability_first_invalid_value" to (firstInvalidValue?.toString() ?: ""),"probability_boundary_epsilon" to EPSILON.toString())
    }
    fun inspect(values:FloatArray,checkActive:()->Unit={}):Stats {
        require(values.isNotEmpty()) { "Empty detector probability map" }
        var min=Float.POSITIVE_INFINITY;var max=Float.NEGATIVE_INFINITY
        var nonFinite=0;var below=0;var above=0;var outside=0;var first=-1;var firstValue:Float?=null
        values.forEachIndexed { index,value ->
            if(index%4096==0) checkActive()
            if(!value.isFinite()) nonFinite++ else {
                min=minOf(min,value);max=maxOf(max,value)
                if(value<0f) below++
                if(value>1f) above++
            }
            if(!value.isFinite() || value < -EPSILON || value > 1f+EPSILON) {
                outside++
                if(first<0) { first=index;firstValue=value }
            }
        }
        return Stats(min,max,nonFinite,below,above,outside,first,firstValue)
    }
    fun validateAndClamp(values:FloatArray,checkActive:()->Unit={}):Stats {
        val stats=inspect(values,checkActive)
        require(stats.outsideTolerance==0) {
            "Invalid detector probability map: min=${stats.min}, max=${stats.max}, nonfinite=${stats.nonFinite}, "+
                "outsideTolerance=${stats.outsideTolerance}, firstInvalid[${stats.firstInvalidIndex}]=${stats.firstInvalidValue}; epsilon=$EPSILON. No logits conversion applied."
        }
        values.indices.forEach { index ->
            if(index%4096==0) checkActive()
            values[index]=values[index].coerceIn(0f,1f)
        }
        return stats
    }
}
