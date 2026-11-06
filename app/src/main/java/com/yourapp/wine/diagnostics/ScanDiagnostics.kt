package com.yourapp.wine.diagnostics

/** No coordinates: the input is local in every case; selecting a photo never uploads it. */
enum class ScanInputSource { CAMERA, PHOTO_LIBRARY, FILE }

enum class ScanStage { CAPTURE, IMPORT, INITIALIZE, WAIT_FOR_SLOT, NATIVE_LOAD, DECODE, QUALITY, LOCALIZE, WARP, OCR, GLINER, FILL }

data class ScanStageTiming(val stage: ScanStage, val ms: Long)
data class ScanFailure(val stage: ScanStage, val type: String)
data class ScanEngineStatus(val id: String, val available: Boolean, val reason: String? = null)
data class ScanQualityStats(val state: String, val laplacianVariance: Double, val meanLuminance: Double)

/** No boxes: pixel-space provenance is explicit; only mapped TokenRecords carry original + rectified boxes. */
data class ScanReadSample(val text: String, val score: Float, val estimated: Boolean,
    val engine: String, val variant: String, val inputSpace: String, val mapped: Boolean?)

/** Immutable, bounded scalar/text diagnostics only; never retains a Bitmap, Mat, Uri, photo path or Throwable. */
data class ScanDiagnostics(
    val source: ScanInputSource = ScanInputSource.FILE,
    val pipeline: String = "legacy",
    val budgetMs: Long = com.yourapp.wine.ocr.ScanDeadline.BUDGET_MS,
    val inputBytes: Long = 0,
    val importMs: Long = 0,
    val workW: Int = 0,
    val workH: Int = 0,
    val nativeReady: Boolean? = null,
    val nativeVersion: String? = null,
    val kbImplementation: String? = null,
    val quality: ScanQualityStats? = null,
    val quadSource: String = "not_run",
    val lastStage: ScanStage = ScanStage.INITIALIZE,
    val deadlineStage: ScanStage? = null,
    val stages: List<ScanStageTiming> = emptyList(),
    val failures: List<ScanFailure> = emptyList(),
    val engines: List<ScanEngineStatus> = emptyList(),
    val reads: List<ScanReadSample> = emptyList(),
    val totalReadings: Int = 0,
    val mappingReceived: Int = 0,
    val mappingRejected: Int = 0,
    val recoveredProbeReadings: Int = 0,
)

/** No unique device identifiers, account data, location, installed-app inventory, or filesystem paths. */
data class ScanEnvironment(
    val appVersion: String = "unknown",
    val appVersionCode: Int = 0,
    val buildBranch: String = "unknown",
    val buildCommit: String = "unknown",
    val workingTree: String = "unknown",
    val packagedAbis: String = "unknown",
    val androidSdk: Int = 0,
    val androidRelease: String = "unknown",
    val manufacturer: String = "unknown",
    val model: String = "unknown",
    val hardware: String = "unknown",
    val deviceAbis: List<String> = emptyList(),
)
