import com.yourapp.wine.LogicChecks

fun main() {
    val matrix = com.yourapp.wine.geom.Matrix3(listOf(1.1, .1, -10.0, -.05, 1.2, 12.0, .0001, .0002, 1.0))
    val point = android.graphics.PointF(240f, 160f)
    val roundtrip = matrix.inverse().map(matrix.map(point))
    check(kotlin.math.abs(point.x - roundtrip.x) < .001f && kotlin.math.abs(point.y - roundtrip.y) < .001f)
    println("PASS: projective inverse mapping")
    listOf("NFKD" to LogicChecks::normalization, "union-find" to LogicChecks::merger,
        "rectified lines" to LogicChecks::lineGrouping, "vintage" to LogicChecks::vintage,
        "hierarchy conflict" to LogicChecks::hierarchy, "nested geography specificity" to LogicChecks::nestedGeographyUsesSpecificPlaceWithoutFalseConflict, "fill states" to LogicChecks::fillStates, "form policy" to LogicChecks::formPolicy, "rejected retake clears stale auto-fill" to LogicChecks::rejectedRetakeClearsOnlyAutomaticSuggestions, "extraction edges" to LogicChecks::extractionEdges, "index failure preserves partial" to LogicChecks::indexFailurePreservesPartial,
        "estimated vs measured confidence" to LogicChecks::estimatedScoresNeverPromoteWeakMeasurements).forEach { (name, test) ->
        test(); println("PASS: $name")
    }
    GeneratedFixtureChecks.run()
    deadlineChecks()
    bitmapOwnershipChecks()
    csvImportChecks()
    boundedImportChecks()

}
