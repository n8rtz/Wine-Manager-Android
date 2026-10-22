package com.yourapp.wine.textlab.evidence

/** Debug-only search ablations. Default preserves the shipped experimental v3 search. */
internal data class EvidenceSearchConfig(
    val id:String="v3-default",val candidateLimit:Int=16,val beamLimit:Int=64,
    val diversify:Boolean=false,val exhaustiveMaxCombinations:Long?=null
) {
    init {
        require(candidateLimit in 1..384 && beamLimit in 1..1024)
        require(exhaustiveMaxCombinations==null || exhaustiveMaxCombinations in 1L..20_000L)
    }
    companion object {
        val experiments=listOf(
            EvidenceSearchConfig("candidates-64-64",64,64),
            EvidenceSearchConfig("beam-16-256",16,256),
            EvidenceSearchConfig("wide-64-256",64,256),
            EvidenceSearchConfig("diverse-16-64",16,64,true),
            EvidenceSearchConfig("diverse-64-256",64,256,true),
            EvidenceSearchConfig("exhaustive-small",exhaustiveMaxCombinations=20_000))
    }
}
internal class SearchSpaceTooLargeException(val combinations:Long):Exception("Exhaustive search skipped: $combinations possible combinations exceeds cap")
