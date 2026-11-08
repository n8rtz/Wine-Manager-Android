package com.yourapp.wine.phonecorpus

/** Mode constants for compiling the actual scoreboard without native OCR/Android dependencies. */
internal object PhoneCorpusRunner {
    enum class Mode(val title: String, val pipelineId: String) {
        LAB("Lab v5", "lab-v5-expanded-producers"),
        PRODUCTION_V4("Prod v4.0", "production-v4.0-lwin-only"),
        PRODUCTION_V2("Prod v2.0", "production-v2.0-guarded"),
        PRODUCTION_V1("Prod v1.0", "production-v1.0-15s");

        val acceptedPipelineIds: Set<String>
            get() = setOf(pipelineId) + legacyPipelineIds.filterValues { it == this }.keys

        val usesSpatialEvidence: Boolean get() = this == PRODUCTION_V4 || this == LAB

        companion object {
            private val legacyPipelineIds = mapOf(
                "lab-v4-expanded-producers" to LAB,
                "lab-v4-lwin-only" to LAB,
                "production-v3.0-lwin-only" to PRODUCTION_V4,
                "production-v3.0-spatial-merged" to PRODUCTION_V4,
            )

            fun fromPipelineId(id: String): Mode? = entries.firstOrNull { it.pipelineId == id } ?: legacyPipelineIds[id]
        }
    }
}
