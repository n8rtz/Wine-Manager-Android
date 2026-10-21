package com.yourapp.wine.textlab

/** Pinned upstream v1.1.0 base-script models, NOT the full multilingual/document pipeline. */
internal object IndraSpec {
    const val ID="indra_latin"
    const val ENHANCED_ID="indra_enhanced"
    const val ENHANCED_TITLE="5 · Indra enhanced: orientation + adaptive + LM"
    const val LM_SHA="6c0fb618a363a0acdd05d4f5b0edd9e8146d2d990541b87f6130bbfe17cea58d"
    const val ORIENT_SHA="20eaf670f55a7cf9facfe1bdd6c7b8e40be75bbf4d48a3df43f66aa3bbd874fd"
    const val TITLE="4 · Indra v1.1 Latin detector + recognizer (raw)"
    const val REVISION="v1.1.0"
    const val DET_SHA="f2bd4cba23e9c328cf7ec824634e423e191a2c6aacbbf35c62bd79dd70f62f59"
    const val REC_SHA="475972a2710be4a3cbfeb2bc7f3624ffd563de07de42476fc7a290d4e1ece484"
    const val META_SHA="4ce4c94b46f5d644033c02e718322014ff4081270f385038c571912b56696339"
    val metadata=mapOf("detector" to "Indra-v1.1.0-det","recognizer" to "Indra-v1.1.0-base-rec",
        "detector_sha256" to DET_SHA,"recognizer_sha256" to REC_SHA,"dictionary_sha256" to META_SHA,
        "detector_bytes" to "389948","recognizer_bytes" to "5042161","dictionary_entries" to "1697",
        "model_source" to "https://huggingface.co/datalab-to/indra-ocr/tree/v1.1.0",
        "model_license" to "Datalab modified OpenRAIL-M; see bundled indra-v1/LICENSE",
        "model_license_url" to "https://huggingface.co/datalab-to/indra-ocr/blob/v1.1.0/LICENSE",
        "indra_code_revision" to "0e96b93c6810f17cf8352acbb4487cc9e45b66d5; modified bounded Kotlin raw profile",
        "max_contour_candidates" to "not_applicable",
        "indra_configuration" to "Latin/base; greedy CTC; no LM, script routing, orientation, deskew, adaptive detection, layout or math; upright crops required",
        "detector_max_edge" to "1024","recognizer_max_width" to "1024","max_lines" to "32",
        "indra_rec_height" to "32","indra_rec_window" to "3","indra_min_confidence" to "0.5",
        "indra_core_threshold" to "0.5","indra_max_components" to "4096",
        "indra_language_model" to "disabled","indra_cpu_arena" to "disabled")
    val enhancedMetadata=metadata+mapOf("indra_configuration" to "base recognizer + upstream LM beam; orientation, expanded-canvas deskew, adaptive detector; no script routing/layout/math",
        "detector_max_edge" to "2048","recognizer_max_width" to "4096","max_lines" to "256",
        "indra_language_model" to "enabled for 0.5 <= greedy posterior < 0.9; alpha=0.5 beta=1.5 beam=8 floor=0.003",
        "indra_lm_sha256" to LM_SHA,"indra_orientation_sha256" to ORIENT_SHA,
        "indra_profile_version" to "enhanced-base-1",
        "indra_confidence_semantics" to "uncalibrated greedy posterior, NOT a posterior for LM-rescored text",
        "indra_lm_bytes" to "15695957","indra_orientation_bytes" to "371802")
}

internal data class TextLabChoice(val id:String,val title:String)
internal object TextLabChoices {
    val all=PaddleCandidates.all.map { TextLabChoice(it.id,it.title) }+TextLabChoice(IndraSpec.ID,IndraSpec.TITLE)+TextLabChoice(IndraSpec.ENHANCED_ID,IndraSpec.ENHANCED_TITLE)
    fun selected(ids:Set<String>)=all.filter { it.id in ids }
}
