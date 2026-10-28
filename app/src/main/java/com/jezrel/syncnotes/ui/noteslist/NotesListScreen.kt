package com.jezrel.syncnotes.ui.noteslist

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jezrel.syncnotes.data.local.NoteSummary
import com.jezrel.syncnotes.ui.components.PinToggle
import com.jezrel.syncnotes.ui.theme.SyncNotesTheme

@Composable
fun NotesListScreen(
    onOpenNote: (String) -> Unit,
    onNewNote: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: NotesListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NotesListContent(
        query = viewModel.query,
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onOpenNote = onOpenNote,
        onNewNote = onNewNote,
        onOpenTrash = onOpenTrash,
        onOpenSettings = onOpenSettings,
        onTogglePin = viewModel::onTogglePin,
    )
}

/** Stateless content; internal so previews and UI tests can render it without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesListContent(
    query: String,
    state: NotesListUiState,
    onQueryChange: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onNewNote: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SyncNotes") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Trash") },
                            onClick = { menuOpen = false; onOpenTrash() },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { menuOpen = false; onOpenSettings() },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewNote) {
                Icon(Icons.Filled.Add, contentDescription = "New note")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search notes") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.notes.isEmpty() -> EmptyState(query)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.notes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            onClick = { onOpenNote(note.id) },
                            onTogglePin = { onTogglePin(note.id, !note.pinned) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(query: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (query.isBlank()) {
                "No notes yet.\nTap + to create one."
            } else {
                "No notes match “$query”."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoteRow(
    note: NoteSummary,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    ListItem(
        // A visible label, so pinned state never relies on color alone.
        overlineContent = if (note.pinned) {
            { Text("Pinned") }
        } else {
            null
        },
        headlineContent = {
            Text(
                text = note.title.ifBlank { "Untitled" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            if (note.preview.isNotBlank()) {
                Text(note.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = { PinToggle(pinned = note.pinned, onToggle = onTogglePin) },
        modifier = Modifier.clickable(onClickLabel = "Open note", onClick = onClick),
    )
}

private val previewNotes = listOf(
    NoteSummary("1", "Groceries", "- eggs\n- rice\n- milk", pinned = true, updatedAt = 3, dirtyFields = 0, syncError = null),
    NoteSummary("2", "Trip ideas", "Lisbon in spring, maybe Porto too", pinned = false, updatedAt = 2, dirtyFields = 0, syncError = null),
    NoteSummary("3", "", "A note without a title", pinned = false, updatedAt = 1, dirtyFields = 0, syncError = null),
)

@Preview(name = "Notes list — light", showBackground = true)
@Preview(name = "Notes list — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NotesListPreview() {
    SyncNotesTheme(dynamicColor = false) {
        NotesListContent(
            query = "",
            state = NotesListUiState(notes = previewNotes, loading = false),
            onQueryChange = {}, onOpenNote = {}, onNewNote = {},
            onOpenTrash = {}, onOpenSettings = {}, onTogglePin = { _, _ -> },
        )
    }
}

@Preview(name = "Notes list — empty, dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NotesListEmptyPreview() {
    SyncNotesTheme(dynamicColor = false) {
        NotesListContent(
            query = "",
            state = NotesListUiState(notes = emptyList(), loading = false),
            onQueryChange = {}, onOpenNote = {}, onNewNote = {},
            onOpenTrash = {}, onOpenSettings = {}, onTogglePin = { _, _ -> },
        )
    }
}
