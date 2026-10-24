package com.yourapp.wine.benchmark

/** Only the production preprocessing/engine stack remains selectable. */
data class Treatment(val id:String) {
    val description:String get()="Production V0/V1/conditional V2 with ML Kit + PP-OCRv6 Medium"
}
object BenchmarkPlan { val cases=listOf(Treatment("baseline")) }
