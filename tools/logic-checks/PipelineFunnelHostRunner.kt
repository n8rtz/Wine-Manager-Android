package com.yourapp.wine.phonecorpus

/** Mode constants for compiling the actual scoreboard without native OCR/Android dependencies. */
internal object PhoneCorpusRunner {
    enum class Mode(val pipelineId: String) {
        PRODUCTION_V1("production-v1.0-15s"), PRODUCTION_V2("production-v2.0-guarded"), LAB("lab-linked-wine-v1")
    }
}
