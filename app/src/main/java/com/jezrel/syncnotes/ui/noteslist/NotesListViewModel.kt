package com.jezrel.syncnotes.ui.noteslist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jezrel.syncnotes.data.local.NoteSummary
import com.jezrel.syncnotes.data.repository.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotesListUiState(
    val notes: List<NoteSummary> = emptyList(),
    val loading: Boolean = true,
)

/** See ARCHITECTURE.md §6.2. Sync status is added in Phase 4; Phase 1 shows only notes. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesListViewModel @Inject constructor(
    private val notes: NotesRepository,
) : ViewModel() {

    // Search text is Compose state, updated synchronously so the cursor never jumps.
    var query by mutableStateOf("")
        private set

    val uiState: StateFlow<NotesListUiState> =
        snapshotFlow { query }
            .debounce(250)
            .flatMapLatest { notes.observeActive(it) }
            .map { NotesListUiState(notes = it, loading = false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesListUiState())

    fun onQueryChange(value: String) {
        query = value
    }

    fun onTogglePin(id: String, pinned: Boolean) {
        viewModelScope.launch { notes.setPinned(id, pinned) }
    }

    fun onTrash(id: String) {
        viewModelScope.launch { notes.moveToTrash(id) }
    }
}
