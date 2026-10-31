package com.yourapp.wine.gliner

/** User judgement is kept separate from inference and applied only when displaying/exporting review. */
data class GlinerFieldReview(
    val confirmed:Boolean=false,
    val corrected:String="",
    val expectedBlank:Boolean=false,
) {
    val reviewed:Boolean get() = confirmed || expectedBlank || corrected.isNotBlank()
    val verdict:String get() = when { confirmed -> "correct"; reviewed -> "incorrect"; else -> "unreviewed" }
}

data class GlinerReviewCase(
    val succeeded:Boolean,
    val matchesCorpus:Boolean,
    val fields:Map<String,GlinerFieldReview>,
)

data class GlinerReviewSummary(
    val processed:Int, val fullyReviewed:Int, val allConfirmed:Int,
    val reviewedFields:Int, val confirmedFields:Int, val corpusMatches:Int,
)

/** Counts only completed, non-identical-to-corpus images; unchecked empty fields are unreviewed. */
fun summarizeGlinerReviews(cases:List<GlinerReviewCase>,fields:List<String>):GlinerReviewSummary {
    val usable=cases.filter { it.succeeded && !it.matchesCorpus }
    val reviewed=usable.filter { row -> fields.all { row.fields[it]?.reviewed==true } }
    return GlinerReviewSummary(
        processed=usable.size, fullyReviewed=reviewed.size,
        allConfirmed=reviewed.count { row -> fields.all { row.fields[it]?.confirmed==true } },
        reviewedFields=usable.sumOf { row -> fields.count { row.fields[it]?.reviewed==true } },
        confirmedFields=usable.sumOf { row -> fields.count { row.fields[it]?.confirmed==true } },
        corpusMatches=cases.count { it.matchesCorpus },
    )
}
