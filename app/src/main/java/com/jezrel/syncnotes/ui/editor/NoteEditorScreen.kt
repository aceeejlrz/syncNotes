package com.jezrel.syncnotes.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown

private const val TITLE_MAX = 300
private const val BODY_MAX = 100_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    onBack: () -> Unit,
) {
    var preview by remember { mutableStateOf(false) }
    val trashed = viewModel.trashed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (trashed) "Note (in trash)" else "Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::onTogglePin, enabled = !trashed) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = if (viewModel.pinned) "Unpin" else "Pin",
                            tint = if (viewModel.pinned) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = viewModel::onToggleTrash) {
                        Icon(
                            if (trashed) Icons.Filled.Refresh else Icons.Filled.Delete,
                            contentDescription = if (trashed) "Restore" else "Move to trash",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                state = viewModel.title,
                enabled = !trashed,
                lineLimits = TextFieldLineLimits.SingleLine,
                inputTransformation = InputTransformation.maxLength(TITLE_MAX),
                placeholder = { Text("Title") },
                textStyle = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            PrimaryTabRow(selectedTabIndex = if (preview) 1 else 0) {
                Tab(selected = !preview, onClick = { preview = false }, text = { Text("Edit") })
                Tab(selected = preview, onClick = { preview = true }, text = { Text("Preview") })
            }
            Spacer(Modifier.height(8.dp))
            if (preview) {
                Markdown(
                    content = viewModel.body.text.toString(),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
            } else {
                OutlinedTextField(
                    state = viewModel.body,
                    enabled = !trashed,
                    inputTransformation = InputTransformation.maxLength(BODY_MAX),
                    placeholder = { Text("Write in markdown…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}
