package com.jezrel.syncnotes.ui.editor

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jezrel.syncnotes.ui.components.PinToggle
import com.jezrel.syncnotes.ui.theme.SyncNotesTheme
import com.mikepenz.markdown.m3.Markdown

private const val TITLE_MAX = 300
private const val BODY_MAX = 100_000

@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    onBack: () -> Unit,
) {
    NoteEditorContent(
        title = viewModel.title,
        body = viewModel.body,
        pinned = viewModel.pinned,
        trashed = viewModel.trashed,
        onBack = onBack,
        onTogglePin = viewModel::onTogglePin,
        onToggleTrash = viewModel::onToggleTrash,
    )
}

/** Stateless content; internal so previews and UI tests can render it without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditorContent(
    title: TextFieldState,
    body: TextFieldState,
    pinned: Boolean,
    trashed: Boolean,
    onBack: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleTrash: () -> Unit,
) {
    var preview by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                // Visible state text, so pinned/trashed never rely on color alone.
                title = {
                    Text(
                        when {
                            trashed -> "Note (in trash)"
                            pinned -> "Pinned note"
                            else -> "Note"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    PinToggle(pinned = pinned, onToggle = onTogglePin, enabled = !trashed)
                    IconButton(onClick = onToggleTrash) {
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
                // Edge-to-edge (targetSdk 35+) no longer resizes the window for the keyboard:
                // consume the Scaffold's insets first, then pad for the IME, so the cursor at
                // the bottom of a long note stays visible without double-counting the nav bar.
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                state = title,
                enabled = !trashed,
                lineLimits = TextFieldLineLimits.SingleLine,
                inputTransformation = InputTransformation.maxLength(TITLE_MAX),
                label = { Text("Title") },
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
                    content = body.text.toString(),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
            } else {
                OutlinedTextField(
                    state = body,
                    enabled = !trashed,
                    inputTransformation = InputTransformation.maxLength(BODY_MAX),
                    label = { Text("Note") },
                    placeholder = { Text("Write in markdown…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Preview(name = "Editor — light", showBackground = true)
@Preview(name = "Editor — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NoteEditorPreview() {
    SyncNotesTheme(dynamicColor = false) {
        NoteEditorContent(
            title = remember { TextFieldState("Groceries") },
            body = remember { TextFieldState("# This week\n- eggs\n- rice\n- milk") },
            pinned = true,
            trashed = false,
            onBack = {}, onTogglePin = {}, onToggleTrash = {},
        )
    }
}

@Preview(name = "Editor — trashed, dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NoteEditorTrashedPreview() {
    SyncNotesTheme(dynamicColor = false) {
        NoteEditorContent(
            title = remember { TextFieldState("Old plan") },
            body = remember { TextFieldState("Read-only until restored.") },
            pinned = false,
            trashed = true,
            onBack = {}, onTogglePin = {}, onToggleTrash = {},
        )
    }
}
