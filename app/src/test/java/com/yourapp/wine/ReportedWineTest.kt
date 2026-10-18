package com.yourapp.wine

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Device-report text/geometry regressions; no emulator or native OCR accuracy claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ReportedWineTest {
    @Test fun referenceCatalogProvenance() = ReferenceCatalogChecks.catalogAndProvenance()
    @Test fun ambiguousAndMultistateOrigins() = ReferenceCatalogChecks.ambiguousAndMultiregionOrigins()
    @Test fun referenceTermsDoNotInventFacts() = ReferenceCatalogChecks.labelTermsNeverInventFacts()
    @Test fun designationSuffixes() = AppellationContextChecks.designationsFollowNamesWithoutBecomingProducers()
    @Test fun originContext() = AppellationContextChecks.namedOriginsAreStrongButGenericDesignationsAreNotCountries()
    @Test fun guardedSpellingRepair() = AppellationContextChecks.spellingNeedsIndependentCompatibleEvidenceAndStaysYellow()
    @Test fun appellationIsNotAMaker() = AdministrativeLabelChecks.rhoneAppellationIsNotAMakerAndLegalDebrisIsNotAName()
    @Test fun legalTextDoesNotEraseRealBrands() = AdministrativeLabelChecks.narrowAdministrativeRulesPreserveRealIdentities()
    @Test fun cuveePlusStyleDoesNotInventProducer() = AdministrativeLabelChecks.reserveHeadingIsNotAnUnsupportedProducer()
    @Test fun awardStickerIsNotTheWineName() = LabelNameChecks.awardDoesNotDisplacePrintedNameAndStyle()
    @Test fun fragmentedWineryIsNotACuvee() = LabelNameChecks.fragmentsNeverInventMerlotAndPresentationUsesState()
    @Test fun kirklandFieldsUseTheActualSupportedEvidence() = ReportedWineChecks.kirklandReportDoesNotSwapProducerAndVariety()
    @Test fun exactWineWordBreaksCanBeRepairedButMissingLettersCannot() = ReportedWineChecks.wordBreakRepairDoesNotGuessLettersOrUnknownNames()
    @Test fun installedGazetteersReceiveReadOnlySupplements() = ReportedWineChecks.geographicSupplementIsCachedReadOnlyAndWorksOnExistingSnapshots()
    @Test fun importedHierarchyAndExplicitCountryConflictsArePreserved() = ReportedWineChecks.importedHierarchyWinsAndConflictsStayReviewable()
    @Test fun wordBreaksAreNotFalseDisagreement() = ReportedWineChecks.spacingIsNotDisagreementButDifferentLettersStillAre()
    @Test fun knownNamesCannotPromoteBlackstonesWeakReads() = ReportedWineChecks.blackstoneWeakTextDoesNotBecomeAFieldJustBecauseANameIsKnown()
    @Test fun oddHeightAspectRescueHasAnExactInverse() = ReportedWineChecks.aspectInverseHandlesOddDimensionsAndOriginalCoordinates()
    @Test fun callbackPolygonsUseBaseLabelPixels() = ReportedWineChecks.ensemblePublishesBaseLabelCoordinatesNotVariantPixels()
    @Test fun anExplicitPrintedWineStyleCanNameTheWine() = ReportedWineChecks.printedAppellationStyleCanBeTheOnlyWineName()
}
