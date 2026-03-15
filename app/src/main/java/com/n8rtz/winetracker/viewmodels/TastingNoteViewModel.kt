package com.n8rtz.winetracker.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.n8rtz.winetracker.data.repository.WineRepository

// TastingNoteViewModel is now a lean wrapper providing read-only access to tasting notes.
// It no longer manages database instances or import operations.
// It receives a shared repository instance injected from the MainActivity factory.
class TastingNoteViewModel(application: Application, private val repository: WineRepository) : AndroidViewModel(application) {

    val tastingNotes = repository.tastingHistory
}
