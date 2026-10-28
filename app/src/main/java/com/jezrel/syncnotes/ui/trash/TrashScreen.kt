package com.jezrel.syncnotes.ui.trash

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jezrel.syncnotes.data.local.NoteEntity
import com.jezrel.syncnotes.ui.theme.SyncNotesTheme

@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TrashContent(state = state, onBack = onBack, onRestore = viewModel::onRestore)
}

/** Stateless content; internal so previews and UI tests can render it without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashContent(
    state: TrashUiState,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.notes.isEmpty() -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Trash is empty.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(state.notes, key = { it.id }) { note ->
                    ListItem(
                        // Read-only preview: title + a body snippet. Restore re-activates the note.
                        headlineContent = {
                            Text(note.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            if (note.body.isNotBlank()) {
                                Text(note.body.take(200), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { onRestore(note.id) }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Restore")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun previewTrashed(id: String, title: String, body: String) = NoteEntity(
    id = id, title = title, body = body, pinned = false, trashed = true,
    createdAt = 0, updatedAt = 0, serverVersion = 0, dirtyFields = 0, syncError = null,
)

@Preview(name = "Trash — light", showBackground = true)
@Preview(name = "Trash — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TrashPreview() {
    SyncNotesTheme(dynamicColor = false) {
        TrashContent(
            state = TrashUiState(
                notes = listOf(
                    previewTrashed("1", "Old plan", "Superseded by the new one"),
                    previewTrashed("2", "", "Untitled scratch note"),
                ),
                loading = false,
            ),
            onBack = {},
            onRestore = {},
        )
    }
}

@Preview(name = "Trash — empty, dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TrashEmptyPreview() {
    SyncNotesTheme(dynamicColor = false) {
        TrashContent(state = TrashUiState(notes = emptyList(), loading = false), onBack = {}, onRestore = {})
    }
}
