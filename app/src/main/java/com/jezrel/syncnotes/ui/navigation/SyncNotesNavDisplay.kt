package com.jezrel.syncnotes.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.jezrel.syncnotes.ui.noteslist.NotesListScreen
import java.util.UUID

/**
 * Hosts the app's back stack (ARCHITECTURE.md §6.3). The app opens on the note list
 * whether or not there is an account; signing in is never forced.
 *
 * NotesList is wired to the real screen in step 1.5; the other destinations are
 * placeholders until their steps (Editor 1.6, Trash 1.7, Settings/Sign in Phase 3).
 */
@Composable
fun SyncNotesNavDisplay() {
    val backStack = rememberNavBackStack(NotesList)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<NotesList> {
                NotesListScreen(
                    onOpenNote = { id -> backStack.add(NoteEditor(id)) },
                    onNewNote = { backStack.add(NoteEditor(UUID.randomUUID().toString(), isNew = true)) },
                    onOpenTrash = { backStack.add(Trash) },
                    onOpenSettings = { backStack.add(Settings) },
                )
            }
            entry<NoteEditor> { key -> Placeholder("Editor: ${key.noteId}") }
            entry<Trash> { Placeholder("Trash") }
            entry<Settings> { Placeholder("Settings") }
            entry<SignIn> { Placeholder("Sign in") }
        },
    )
}

@Composable
private fun Placeholder(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label) }
}
