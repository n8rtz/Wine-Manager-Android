package com.yourapp.wine.textlab

internal data class PaddleModel(val name:String,val asset:String,val sha:String,val bytes:Long)
internal data class PaddleDictionary(val asset:String,val sha:String,val bytes:Long,val entries:Int)
internal data class PaddleCandidate(val id:String,val title:String,val detector:PaddleModel,val recognizer:PaddleModel,
    val dictionary:PaddleDictionary,val threshold:Float,val boxThreshold:Float,val unclip:Double)

internal object PaddleCandidates {
    private val v5dict=PaddleDictionary("ppocrv5_dict.utf8","d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b",74012,18383)
    private val v6dict=PaddleDictionary("ppocrv6_dict.utf8","b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",74947,18708)
    private val mediumRec=PaddleModel("PP-OCRv6_medium_rec","v6-medium-rec.onnx","9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",76554979)
    val all=listOf(
        PaddleCandidate("candidate_1","1 · v5 mobile detector + recognizer",
            PaddleModel("PP-OCRv5_mobile_det","detection.onnx","1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039",4826518),
            PaddleModel("PP-OCRv5_mobile_rec","inference.onnx","243a0f06d826761323e9045e9b113ab2c191c3aa50565585e628300b8eda0224",16562373),v5dict,.3f,.6f,1.5),
        PaddleCandidate("candidate_2","2 · v6 Small detector + Medium recognizer",
            PaddleModel("PP-OCRv6_small_det","v6-small-det.onnx","d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",9880512),mediumRec,v6dict,.2f,.45f,1.4),
        PaddleCandidate("candidate_3","3 · v6 Medium detector + recognizer",
            PaddleModel("PP-OCRv6_medium_det","v6-medium-det.onnx","eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1",62032837),mediumRec,v6dict,.2f,.45f,1.4)
    )
    fun selected(ids:Set<String>)=all.filter { it.id in ids }
}

internal class CandidateBudgetExpired:Exception("Candidate time budget reached")
