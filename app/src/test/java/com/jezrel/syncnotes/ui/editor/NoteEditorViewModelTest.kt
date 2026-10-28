package com.jezrel.syncnotes.ui.editor

import android.app.Application
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jezrel.syncnotes.testing.FakeNotesRepository
import com.jezrel.syncnotes.testing.FakeNotesRepository.Companion.note
import com.jezrel.syncnotes.testing.MainDispatcherRule
import com.jezrel.syncnotes.ui.navigation.NoteEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Editor ViewModel against the fake repository. Robolectric because TextFieldState needs it.
 * Durability against real Room stays in NoteEditorAutosaveTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [33])
class NoteEditorViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    /** Builds the VM inside a real ViewModelStore so clear() runs onCleared like a nav pop does. */
    private fun editorIn(store: ViewModelStore, key: NoteEditor, fake: FakeNotesRepository, appScope: CoroutineScope) =
        ViewModelProvider(
            store,
            viewModelFactory { initializer { NoteEditorViewModel(key, fake, appScope) } },
        )[NoteEditorViewModel::class.java]

    @Test
    fun openingExistingNote_fillsFields_andDoesNotSave() = runTest {
        val fake = FakeNotesRepository(listOf(note("n", "Groceries", "eggs", pinned = true)))
        val vm = NoteEditorViewModel(NoteEditor("n"), fake, backgroundScope)
        advanceUntilIdle()
        Snapshot.sendApplyNotifications()
        advanceTimeBy(1_000) // well past the 400 ms autosave debounce
        advanceUntilIdle()

        assertEquals("Groceries", vm.title.text.toString())
        assertEquals("eggs", vm.body.text.toString())
        assertTrue(vm.pinned)
        assertFalse(vm.trashed)
        // Loading must not count as an edit: a save here would mark the note dirty and sync it.
        assertTrue("opening a note saved it: ${fake.saves}", fake.saves.isEmpty())
    }

    @Test
    fun closingWithUnsavedText_flushesIt() = runTest {
        val fake = FakeNotesRepository()
        val store = ViewModelStore()
        val vm = editorIn(store, NoteEditor("new", isNew = true), fake, backgroundScope)
        advanceUntilIdle()

        vm.title.setTextAndPlaceCursorAtEnd("Unsaved")
        vm.body.setTextAndPlaceCursorAtEnd("draft")
        Snapshot.sendApplyNotifications()
        runCurrent() // edits observed; the 400 ms autosave has NOT fired yet

        store.clear() // cancels viewModelScope, then onCleared flushes in the app scope
        // advanceUntilIdle() stops once only backgroundScope (our app scope) work is left;
        // runCurrent() also runs background tasks due now, so the flush actually executes.
        advanceUntilIdle()
        runCurrent()

        assertEquals(listOf(Triple("new", "Unsaved", "draft")), fake.saves)
    }

    @Test
    fun closingATrashedNote_doesNotFlush() = runTest {
        val fake = FakeNotesRepository(listOf(note("t", "Old", "kept as is", trashed = true)))
        val store = ViewModelStore()
        val vm = editorIn(store, NoteEditor("t"), fake, backgroundScope)
        advanceUntilIdle()
        assertTrue(vm.trashed)

        store.clear()
        advanceUntilIdle()
        runCurrent() // give a (wrong) flush the chance to run, so this assertion isn't vacuous

        assertTrue("trashed note was saved on close: ${fake.saves}", fake.saves.isEmpty())
    }
}
