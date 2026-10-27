package com.yourapp.wine.phonecorpus

/** Mode constants for compiling the actual scoreboard without native OCR/Android dependencies. */
internal object PhoneCorpusRunner {
    enum class Mode(val pipelineId: String) {
        LAB("lab-v4-expanded-producers"), PRODUCTION_V3("production-v3.0-lwin-only"), PRODUCTION_V2("production-v2.0-guarded"), PRODUCTION_V1("production-v1.0-15s");
        val usesSpatialEvidence:Boolean get()=this==PRODUCTION_V3 || this==LAB
    }
}
