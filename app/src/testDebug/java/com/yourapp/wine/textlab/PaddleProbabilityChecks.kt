package com.yourapp.wine.textlab

object PaddleProbabilityChecks {
    fun run() {
        val valid=floatArrayOf(0f,.2f,.45f,.6f,1f)
        val copy=valid.copyOf()
        check(PaddleProbabilityMap.validateAndClamp(valid).outsideTolerance==0)
        check(valid.contentEquals(copy))
        val roundoff=floatArrayOf(-1e-7f,1f+1e-7f,.25f)
        val stats=PaddleProbabilityMap.validateAndClamp(roundoff)
        check(stats.belowZero==1 && stats.aboveOne==1 && stats.min<0 && stats.max>1)
        check(roundoff.contentEquals(floatArrayOf(0f,1f,.25f)))
        for(bad in listOf(-.01f,1.01f,-10f,10f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            val input=floatArrayOf(.5f,bad)
            val observed=PaddleProbabilityMap.inspect(input)
            check(observed.outsideTolerance==1 && observed.firstInvalidIndex==1)
            try { PaddleProbabilityMap.validateAndClamp(input);error("Invalid map must fail") }
            catch(expected:IllegalArgumentException) { check(expected.message!!.contains("firstInvalid[1]")) }
            check(input[1].toRawBits()==bad.toRawBits()) // No silent logits conversion or partial repair.
        }
        try { PaddleProbabilityMap.inspect(FloatArray(100)) { throw InterruptedException() };error("Must cancel") }
        catch(_:InterruptedException) { }
    }
}
