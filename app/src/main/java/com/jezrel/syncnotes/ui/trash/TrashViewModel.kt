package com.jezrel.syncnotes.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jezrel.syncnotes.data.local.NoteEntity
import com.jezrel.syncnotes.data.repository.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashUiState(
    val notes: List<NoteEntity> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val notes: NotesRepository,
) : ViewModel() {

    val uiState: StateFlow<TrashUiState> =
        notes.observeTrash()
            .map { TrashUiState(notes = it, loading = false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    fun onRestore(id: String) {
        viewModelScope.launch { notes.restore(id) }
    }
}
