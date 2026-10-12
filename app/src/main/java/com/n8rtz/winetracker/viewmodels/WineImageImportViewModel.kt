package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import retrofit2.converter.gson.GsonConverterFactory
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.WineImageAssetWithDefinition
import com.n8rtz.winetracker.data.repository.OfflineImageRemovalResult
import com.n8rtz.winetracker.data.repository.WineImageImportRepository
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.image.OpenFoodFactsWineImageProvider
import com.n8rtz.winetracker.image.PreparedWineImage
import com.n8rtz.winetracker.image.WineImageCandidate
import com.n8rtz.winetracker.image.WineImageFileStore
import com.n8rtz.winetracker.image.WineImageKind
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

sealed class WineImageImportState {
    object Idle : WineImageImportState()
    object LoadingQueue : WineImageImportState()

    data class BatchBudgetWarning(
        val wineCount: Int,
        val storedBytes: Long,
        val estimatedBytes: Long,
        val budgetBytes: Long
    ) : WineImageImportState()

    data class Searching(
        val wine: WineDefinition,
        val position: Int,
        val total: Int
    ) : WineImageImportState()

    data class SearchError(
        val wine: WineDefinition,
        val position: Int,
        val total: Int,
        val message: String
    ) : WineImageImportState()

    data class Reviewing(
        val wine: WineDefinition,
        val candidates: List<WineImageCandidate>,
        val position: Int,
        val total: Int
    ) : WineImageImportState()

    data class Preparing(
        val wine: WineDefinition,
        val candidate: WineImageCandidate,
        val kind: WineImageKind,
        val position: Int,
        val total: Int
    ) : WineImageImportState()

    data class ImageBudgetWarning(
        val wine: WineDefinition,
        val candidate: WineImageCandidate,
        val kind: WineImageKind,
        val preparedBytes: Long,
        val storedBytes: Long,
        val budgetBytes: Long,
        val position: Int,
        val total: Int
    ) : WineImageImportState()

    data class Saving(
        val wine: WineDefinition,
        val position: Int,
        val total: Int
    ) : WineImageImportState()

    data class ImportError(
        val wine: WineDefinition,
        val candidate: WineImageCandidate,
        val kind: WineImageKind,
        val position: Int,
        val total: Int,
        val message: String
    ) : WineImageImportState()

    data class Finished(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val cancelled: Boolean = false
    ) : WineImageImportState()
}

data class WineImageStorageStatus(
    val usedBytes: Long = 0L,
    val softBudgetBytes: Long = WineImageFileStore.DEFAULT_SOFT_BUDGET_BYTES
)

data class OfflineImageManagementState(
    val isLoading: Boolean = false,
    val images: List<WineImageAssetWithDefinition> = emptyList(),
    val removingDefinitionId: Long? = null,
    val message: String? = null,
    val error: String? = null
)

/** Reviewed image importer plus explicit management of already-approved local copies. */
class WineImageImportViewModel(
    application: Application,
    wineRepository: WineRepository
) : AndroidViewModel(application) {
    private val fileStore = WineImageFileStore(application)
    private val importRepository = WineImageImportRepository(wineRepository.database, fileStore)
    private val api by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "WineManagerAndroid/1.0")
                    .build()
                chain.proceed(request)
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://world.openfoodfacts.org/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenFoodFactsApi::class.java)
    }
    private val provider by lazy { OpenFoodFactsWineImageProvider(api) }
    private val preferences = application.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<WineImageImportState>(WineImageImportState.Idle)
    val state: StateFlow<WineImageImportState> = _state.asStateFlow()

    private val _storageStatus = MutableStateFlow(
        WineImageStorageStatus(
            softBudgetBytes = preferences.getLong(
                SOFT_BUDGET_KEY,
                WineImageFileStore.DEFAULT_SOFT_BUDGET_BYTES
            ).coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)
        )
    )
    val storageStatus: StateFlow<WineImageStorageStatus> = _storageStatus.asStateFlow()

    private val _offlineImageManagement = MutableStateFlow(OfflineImageManagementState())
    val offlineImageManagement: StateFlow<OfflineImageManagementState> = _offlineImageManagement.asStateFlow()

    private var offlineImagesObserverJob: Job? = null
    private var queue: List<WineDefinition> = emptyList()
    private var currentIndex = 0
    private var importedCount = 0
    private var skippedCount = 0
    private var failedCount = 0
    private var pendingPreparedImage: PreparedWineImage? = null
    private var operationJob: Job? = null
    private var startupCleanupJob: Job? = null

    init {
        startupCleanupJob = viewModelScope.launch {
            runCatching { importRepository.cleanupOrphanFiles() }
            refreshStorageStatus()
        }
    }

    fun setSoftBudgetMiB(value: Int) {
        val bounded = value.coerceIn(MIN_BUDGET_MIB, MAX_BUDGET_MIB)
        val bytes = bounded.toLong() * MEBIBYTE
        preferences.edit().putLong(SOFT_BUDGET_KEY, bytes).apply()
        _storageStatus.value = _storageStatus.value.copy(softBudgetBytes = bytes)
    }

    fun refreshStorageStatus() {
        viewModelScope.launch {
            val used = runCatching { importRepository.getStoredBytes() }.getOrDefault(0L)
            _storageStatus.value = _storageStatus.value.copy(usedBytes = used)
        }
    }

    fun observeOfflineImages() {
        if (offlineImagesObserverJob?.isActive == true) return
        _offlineImageManagement.value = _offlineImageManagement.value.copy(
            isLoading = true,
            message = null,
            error = null
        )
        offlineImagesObserverJob = viewModelScope.launch {
            try {
                startupCleanupJob?.join()
                refreshStorageStatus()
                importRepository.observeStoredImages().collect { images ->
                    _offlineImageManagement.value = _offlineImageManagement.value.copy(
                        isLoading = false,
                        images = images,
                        error = null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _offlineImageManagement.value = _offlineImageManagement.value.copy(
                    isLoading = false,
                    error = error.message ?: "Could not load offline wine images."
                )
            }
        }
    }

    fun removeOfflineCopy(image: WineImageAssetWithDefinition) {
        val relativePath = image.imageAsset.relativePath ?: return
        if (_offlineImageManagement.value.removingDefinitionId != null) return

        _offlineImageManagement.value = _offlineImageManagement.value.copy(
            removingDefinitionId = image.imageAsset.definitionId,
            message = null,
            error = null
        )
        viewModelScope.launch {
            try {
                startupCleanupJob?.join()
                when (importRepository.removeOfflineCopy(image.imageAsset.definitionId, relativePath)) {
                    OfflineImageRemovalResult.REMOVED -> {
                        _offlineImageManagement.value = _offlineImageManagement.value.copy(
                            message = "Offline copy removed. Wine and source details were kept."
                        )
                    }
                    OfflineImageRemovalResult.ALREADY_CHANGED -> {
                        _offlineImageManagement.value = _offlineImageManagement.value.copy(
                            message = "This image was already removed or changed. The list is up to date."
                        )
                    }
                    OfflineImageRemovalResult.FILE_DELETE_DEFERRED -> {
                        _offlineImageManagement.value = _offlineImageManagement.value.copy(
                            message = "The offline copy was unlinked, but storage cleanup will retry deleting its file."
                        )
                    }
                }
                refreshStorageStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _offlineImageManagement.value = _offlineImageManagement.value.copy(
                    error = error.message ?: "Could not remove the offline image."
                )
            } finally {
                _offlineImageManagement.value = _offlineImageManagement.value.copy(
                    removingDefinitionId = null
                )
            }
        }
    }

    fun clearOfflineImageManagementMessage() {
        _offlineImageManagement.value = _offlineImageManagement.value.copy(message = null, error = null)
    }

    fun startImport() {
        if (_state.value !is WineImageImportState.Idle && _state.value !is WineImageImportState.Finished) return
        _state.value = WineImageImportState.LoadingQueue
        operationJob = viewModelScope.launch {
            try {
                startupCleanupJob?.join()
                queue = importRepository.getMissingImageDefinitions()
                    .sortedWith(compareBy<WineDefinition> { it.maker.lowercase() }.thenBy { it.name.lowercase() })
                currentIndex = 0
                importedCount = 0
                skippedCount = 0
                failedCount = 0
                pendingPreparedImage = null
                if (queue.isEmpty()) {
                    _state.value = WineImageImportState.Finished(0, 0, 0)
                    return@launch
                }

                val storedBytes = importRepository.getStoredBytes()
                val budget = _storageStatus.value.softBudgetBytes
                val estimate = queue.size.toLong() * WineImageFileStore.TARGET_DERIVATIVE_BYTES
                if (storedBytes + estimate > budget) {
                    _state.value = WineImageImportState.BatchBudgetWarning(
                        wineCount = queue.size,
                        storedBytes = storedBytes,
                        estimatedBytes = estimate,
                        budgetBytes = budget
                    )
                    return@launch
                }
                loadNextReview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = WineImageImportState.Finished(0, 0, 1)
            }
        }
    }

    fun continueAfterBatchBudgetWarning() {
        if (_state.value !is WineImageImportState.BatchBudgetWarning) return
        launchNextReview()
    }

    fun retrySearch() {
        if (_state.value !is WineImageImportState.SearchError) return
        launchNextReview()
    }

    fun skipSearchFailure() {
        if (_state.value !is WineImageImportState.SearchError) return
        failedCount++
        currentIndex++
        launchNextReview()
    }

    fun approveCandidate(candidate: WineImageCandidate, kind: WineImageKind) {
        val reviewing = _state.value as? WineImageImportState.Reviewing ?: return
        if (reviewing.candidates.none { it.id == candidate.id }) return
        prepareCandidate(reviewing.wine, candidate, kind, reviewing.position, reviewing.total)
    }

    private fun prepareCandidate(
        wine: WineDefinition,
        candidate: WineImageCandidate,
        kind: WineImageKind,
        position: Int,
        total: Int
    ) {
        _state.value = WineImageImportState.Preparing(wine, candidate, kind, position, total)
        operationJob = viewModelScope.launch {
            var prepared: PreparedWineImage? = null
            try {
                prepared = fileStore.prepare(candidate, kind)
                pendingPreparedImage = prepared
                val storedBytes = importRepository.getStoredBytes()
                val budget = _storageStatus.value.softBudgetBytes
                if (storedBytes + prepared.sizeBytes > budget) {
                    _state.value = WineImageImportState.ImageBudgetWarning(
                        wine = wine,
                        candidate = candidate,
                        kind = kind,
                        preparedBytes = prepared.sizeBytes,
                        storedBytes = storedBytes,
                        budgetBytes = budget,
                        position = position,
                        total = total
                    )
                    return@launch
                }
                savePrepared(wine, candidate, kind, prepared, position, total)
            } catch (cancelled: CancellationException) {
                fileStore.discard(prepared)
                pendingPreparedImage = null
                throw cancelled
            } catch (error: Exception) {
                fileStore.discard(prepared)
                pendingPreparedImage = null
                _state.value = WineImageImportState.ImportError(
                    wine,
                    candidate,
                    kind,
                    position,
                    total,
                    error.message ?: "Could not prepare the selected image."
                )
            }
        }
    }

    fun continuePastImageBudgetWarning() {
        val warning = _state.value as? WineImageImportState.ImageBudgetWarning ?: return
        val prepared = pendingPreparedImage ?: return
        _state.value = WineImageImportState.Saving(warning.wine, warning.position, warning.total)
        operationJob = viewModelScope.launch {
            savePrepared(
                warning.wine,
                warning.candidate,
                warning.kind,
                prepared,
                warning.position,
                warning.total
            )
        }
    }

    fun retryImport() {
        val error = _state.value as? WineImageImportState.ImportError ?: return
        prepareCandidate(error.wine, error.candidate, error.kind, error.position, error.total)
    }

    fun skipCurrent() {
        when (val current = _state.value) {
            is WineImageImportState.Reviewing -> {
                skippedCount++
                currentIndex++
                launchNextReview()
            }
            is WineImageImportState.ImageBudgetWarning -> skipPreparedCandidate()
            is WineImageImportState.ImportError -> {
                failedCount++
                discardPendingPrepared()
                currentIndex++
                launchNextReview()
            }
            is WineImageImportState.SearchError -> skipSearchFailure()
            else -> Unit
        }
    }

    fun cancelImport() {
        val active = _state.value
        if (active is WineImageImportState.Idle || active is WineImageImportState.Finished) return
        operationJob?.cancel()
        operationJob = null
        discardPendingPrepared()
        queue = emptyList()
        _state.value = WineImageImportState.Finished(
            imported = importedCount,
            skipped = skippedCount,
            failed = failedCount,
            cancelled = true
        )
        refreshStorageStatus()
    }

    fun dismiss() {
        if (_state.value is WineImageImportState.Finished || _state.value is WineImageImportState.Idle) {
            _state.value = WineImageImportState.Idle
        }
    }

    private fun launchNextReview() {
        _state.value = WineImageImportState.LoadingQueue
        operationJob = viewModelScope.launch { loadNextReview() }
    }

    private suspend fun loadNextReview() {
        while (currentIndex < queue.size) {
            val wine = queue[currentIndex]
            val position = currentIndex + 1
            val total = queue.size
            _state.value = WineImageImportState.Searching(wine, position, total)
            if (currentIndex > 0) delay(500L)
            val candidates = try {
                provider.findCandidates(wine)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = WineImageImportState.SearchError(
                    wine = wine,
                    position = position,
                    total = total,
                    message = error.message ?: "Open Food Facts search failed."
                )
                return
            }

            if (candidates.isEmpty()) {
                skippedCount++
                currentIndex++
                continue
            }
            _state.value = WineImageImportState.Reviewing(wine, candidates, position, total)
            return
        }
        queue = emptyList()
        refreshStorageStatus()
        _state.value = WineImageImportState.Finished(importedCount, skippedCount, failedCount)
    }

    private suspend fun savePrepared(
        wine: WineDefinition,
        candidate: WineImageCandidate,
        kind: WineImageKind,
        prepared: PreparedWineImage,
        position: Int,
        total: Int
    ) {
        _state.value = WineImageImportState.Saving(wine, position, total)
        try {
            importRepository.saveApprovedImage(wine.definitionId, candidate, kind, prepared)
            pendingPreparedImage = null
            importedCount++
            refreshStorageStatus()
            currentIndex++
            loadNextReview()
        } catch (cancelled: CancellationException) {
            fileStore.discard(prepared)
            pendingPreparedImage = null
            throw cancelled
        } catch (error: Exception) {
            fileStore.discard(prepared)
            pendingPreparedImage = null
            _state.value = WineImageImportState.ImportError(
                wine,
                candidate,
                kind,
                position,
                total,
                error.message ?: "Could not save the approved image."
            )
        }
    }

    private fun skipPreparedCandidate() {
        discardPendingPrepared()
        skippedCount++
        currentIndex++
        launchNextReview()
    }

    private fun discardPendingPrepared() {
        fileStore.discard(pendingPreparedImage)
        pendingPreparedImage = null
    }

    override fun onCleared() {
        operationJob?.cancel()
        discardPendingPrepared()
        super.onCleared()
    }

    private companion object {
        const val PREFERENCES_FILE = "wine_image_import_settings"
        const val SOFT_BUDGET_KEY = "soft_budget_bytes"
        const val MIN_BUDGET_MIB = 64
        const val MAX_BUDGET_MIB = 1_024
        const val MEBIBYTE = 1024L * 1024L
        const val MIN_BUDGET_BYTES = MIN_BUDGET_MIB * MEBIBYTE
        const val MAX_BUDGET_BYTES = MAX_BUDGET_MIB * MEBIBYTE
    }
}
