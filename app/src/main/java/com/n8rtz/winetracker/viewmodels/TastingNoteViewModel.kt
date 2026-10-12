package com.n8rtz.winetracker.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.n8rtz.winetracker.data.repository.WineRepository

import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.local.BottleStatus
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.displayVintage
import com.n8rtz.winetracker.ui.screens.TastingNotesFilterState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log

// TastingNoteViewModel is now a lean wrapper providing read-only access to tasting notes.
// It no longer manages database instances or import operations.
// It receives a shared repository instance injected from the MainActivity factory.
class TastingNoteViewModel(application: Application, private val repository: WineRepository) : AndroidViewModel(application) {

    val tastingNotes = repository.tastingHistory
    val allDefinitions = repository.allDefinitions

    suspend fun getTastingNoteById(noteId: Long): TastingNote? = repository.getTastingNoteById(noteId)

    fun saveTastingNote(note: TastingNote, onSaved: (Long) -> Unit) =
        saveTastingNote(note, linkedEventId = null, onSaved = onSaved)

    fun saveTastingNote(note: TastingNote, linkedEventId: Long?, onSaved: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val savedId = repository.saveTastingNote(note, linkedEventId)
                if (savedId > 0L) onSaved(savedId)
            } catch (error: Exception) {
                Log.e("TastingNoteViewModel", "Unable to save tasting note", error)
            }
        }
    }

    fun deleteTastingNote(noteId: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteTastingNote(noteId)
                onDeleted()
            } catch (error: Exception) {
                Log.e("TastingNoteViewModel", "Unable to delete tasting note", error)
            }
        }
    }

    private val _filterState = MutableStateFlow(TastingNotesFilterState())
    val filterState: StateFlow<TastingNotesFilterState> = _filterState.asStateFlow()

    fun updateFilterState(newState: TastingNotesFilterState) {
        _filterState.value = newState
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
                val vintage = displayVintage(item.note.vintage)
                val key = Pair(defId, vintage)
                if (priceMap.containsKey(key)) return@forEach

                val specificBottles = allBottles.filter {
                    it.definitionId == defId && displayVintage(it.vintage) == vintage
                }.sortedByDescending { it.vintage == vintage }
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
