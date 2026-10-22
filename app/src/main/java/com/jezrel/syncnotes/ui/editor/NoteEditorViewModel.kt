package com.jezrel.syncnotes.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jezrel.syncnotes.core.di.ApplicationScope
import com.jezrel.syncnotes.data.repository.NotesRepository
import com.jezrel.syncnotes.ui.navigation.NoteEditor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** See ARCHITECTURE.md §6.5. Autosave 400 ms after typing stops; a final flush on close. */
@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = NoteEditorViewModel.Factory::class)
class NoteEditorViewModel @AssistedInject constructor(
    @Assisted key: NoteEditor,
    private val notes: NotesRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: NoteEditor): NoteEditorViewModel
    }

    private val noteId = key.noteId
    private val isNew = key.isNew

    val title = TextFieldState()
    val body = TextFieldState()

    var pinned by mutableStateOf(false)
        private set
    var trashed by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            if (!isNew) {
                notes.getNote(noteId)?.let {
                    title.setTextAndPlaceCursorAtEnd(it.title)
                    body.setTextAndPlaceCursorAtEnd(it.body)
                    pinned = it.pinned
                    trashed = it.trashed
                }
            }
            snapshotFlow { title.text.toString() to body.text.toString() }
                .drop(1)                 // skip the values we just loaded
                .debounce(400)
                .collectLatest { (t, b) -> if (!trashed) notes.saveNote(noteId, t, b) }
        }
    }

    fun onTogglePin() {
        val next = !pinned
        pinned = next
        viewModelScope.launch { notes.setPinned(noteId, next) }
    }

    fun onToggleTrash() {
        val next = !trashed
        trashed = next
        viewModelScope.launch {
            if (next) notes.moveToTrash(noteId) else notes.restore(noteId)
        }
    }

    override fun onCleared() {
        if (trashed) return
        // Flush any edit made in the last 400 ms in a scope that outlives this ViewModel.
        val t = title.text.toString()
        val b = body.text.toString()
        appScope.launch { notes.saveNote(noteId, t, b) }
    }
}
