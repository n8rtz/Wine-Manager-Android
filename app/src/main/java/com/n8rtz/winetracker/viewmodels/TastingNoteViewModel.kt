package com.n8rtz.winetracker.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.n8rtz.winetracker.data.repository.WineRepository

import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.data.models.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log
import com.n8rtz.winetracker.ui.screens.TastingNotesFilterState

// TastingNoteViewModel is now a lean wrapper providing read-only access to tasting notes.
// It no longer manages database instances or import operations.
// It receives a shared repository instance injected from the MainActivity factory.
class TastingNoteViewModel(application: Application, private val repository: WineRepository) : AndroidViewModel(application) {

    val tastingNotes = repository.tastingHistory

    private val _filterState = MutableStateFlow(TastingNotesFilterState())
    val filterState: StateFlow<TastingNotesFilterState> = _filterState.asStateFlow()

    fun updateFilterState(newState: TastingNotesFilterState) {
        _filterState.value = newState
    }

    // --- Note Editor State ---
    private val _editorSensoryData = MutableStateFlow(SensoryData())
    val editorSensoryData: StateFlow<SensoryData> = _editorSensoryData.asStateFlow()

    fun updateSensoryData(update: (SensoryData) -> SensoryData) {
        _editorSensoryData.value = update(_editorSensoryData.value)
    }

    fun loadNoteForEditing(noteId: Long, onLoaded: (TastingNoteWithDefinition) -> Unit) {
        viewModelScope.launch {
            val item = repository.getNoteWithDefinitionById(noteId)
            if (item != null) {
                _editorSensoryData.value = item.note.sensoryData ?: SensoryData(
                    overallScore = if (item.note.rating > 0) item.note.rating.toInt() else null
                )
                onLoaded(item)
            }
        }
    }

    fun saveTastingNote(
        definitionId: Long,
        vintage: String,
        bottleId: Long?,
        dateTasted: Long,
        notes: String?,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            val score = _editorSensoryData.value.overallScore 
                ?: com.n8rtz.winetracker.ui.components.calculateBaseScore(_editorSensoryData.value)
            val updatedSensory = _editorSensoryData.value.copy(overallScore = score)
            val note = TastingNote(
                definitionId = definitionId,
                vintage = vintage,
                dateTasted = dateTasted,
                rating = score.toDouble(),
                notes = notes,
                bottleId = bottleId,
                sensoryData = updatedSensory
            )
            repository.insertTastingNote(note)
            
            _editorSensoryData.value = SensoryData()
            onSuccess()
        }
    }

    fun updateExistingTastingNote(
        noteId: Long,
        definitionId: Long,
        vintage: String,
        bottleId: Long?,
        dateTasted: Long,
        notes: String?,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            val score = _editorSensoryData.value.overallScore 
                ?: com.n8rtz.winetracker.ui.components.calculateBaseScore(_editorSensoryData.value)
            val updatedSensory = _editorSensoryData.value.copy(overallScore = score)
            val note = TastingNote(
                noteId = noteId,
                definitionId = definitionId,
                vintage = vintage,
                dateTasted = dateTasted,
                rating = score.toDouble(),
                notes = notes,
                bottleId = bottleId,
                sensoryData = updatedSensory
            )
            repository.updateTastingNote(note)
            
            _editorSensoryData.value = SensoryData()
            onSuccess()
        }
    }

    fun deleteTastingNote(noteId: Long, onSuccess: () -> Unit) {
        viewModelScope.launch {
            repository.deleteTastingNote(noteId)
            Log.d("WineTracker", "Deleted tasting note id=$noteId")
            _editorSensoryData.value = SensoryData()
            onSuccess()
        }
    }

    private fun String.normalizeLocation(): String {
        return this.trim()
            .lowercase()
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    }

    val tastingNotesLocationData: StateFlow<Map<String, Map<String, List<String>>>> = tastingNotes
        .map { list ->
            val map = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
            for (item in list) {
                val def = item.definition
                val country = def.country.normalizeLocation()
                val region = def.region?.normalizeLocation()
                val subregion = def.subregion?.normalizeLocation()

                val countryMap = map.getOrPut(country) { mutableMapOf() }
                if (!region.isNullOrBlank()) {
                    val regionList = countryMap.getOrPut(region) { mutableListOf() }
                    if (!subregion.isNullOrBlank()) {
                        if (regionList.none { it.equals(subregion, ignoreCase = true) }) {
                            regionList.add(subregion)
                        }
                    }
                }
            }
            map.mapValues { (_, countryMap) ->
                countryMap.mapValues { (_, regionList) ->
                    regionList.sorted()
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val rankingsPrices: StateFlow<Map<Pair<Long, String>, Double?>> = tastingNotes
        .map { history ->
            val definitionIds = history.map { it.note.definitionId }.distinct()
            if (definitionIds.isEmpty()) return@map emptyMap()
            
            val allBottles = repository.getAllPricesForDefinitions(definitionIds)
            val priceMap = mutableMapOf<Pair<Long, String>, Double?>()
            
            history.forEach { item ->
                val defId = item.note.definitionId
                val vintage = item.note.vintage
                val key = Pair(defId, vintage)
                if (priceMap.containsKey(key)) return@forEach

                val specificBottles = allBottles.filter { it.definitionId == defId && it.vintage == vintage }
                var price = specificBottles.firstOrNull { it.status == BottleStatus.IN_CELLAR && (it.price ?: 0.0) > 0.0 }?.price
                if (price == null || price <= 0.0) {
                    price = specificBottles.firstOrNull { (it.price ?: 0.0) > 0.0 }?.price
                }
                
                if (price == null || price <= 0.0) {
                    price = allBottles.filter { it.definitionId == defId && (it.price ?: 0.0) > 0.0 }
                        .sortedByDescending { it.status == BottleStatus.IN_CELLAR }
                        .firstOrNull()?.price
                }
                priceMap[key] = price
            }
            priceMap
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )
}
