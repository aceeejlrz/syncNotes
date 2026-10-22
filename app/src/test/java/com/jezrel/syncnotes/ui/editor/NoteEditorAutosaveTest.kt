package com.jezrel.syncnotes.ui.editor

import android.app.Application
import android.content.Context
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jezrel.syncnotes.core.time.Clock
import com.jezrel.syncnotes.data.local.SyncNotesDatabase
import com.jezrel.syncnotes.data.repository.NotesRepository
import com.jezrel.syncnotes.sync.SyncScheduler
import com.jezrel.syncnotes.ui.navigation.NoteEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The durability target (text saved within ~0.5s of the last keystroke) rests on the 400 ms
 * autosave debounce committing to Room — not on the onCleared flush, which never runs on process
 * death. Room's executors are bound to the test dispatcher so advanceUntilIdle awaits the write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [33])
class NoteEditorAutosaveTest {

    @Test
    fun autosavePersistsAfterDebounce() = runTest {
        val (db, repo) = setUpRepo()
        try {
            val id = UUID.randomUUID().toString()
            val vm = NoteEditorViewModel(NoteEditor(id, isNew = true), repo, backgroundScope)
            advanceUntilIdle() // run init; autosave collector started

            vm.title.setTextAndPlaceCursorAtEnd("Groceries")
            vm.body.setTextAndPlaceCursorAtEnd("eggs")
            Snapshot.sendApplyNotifications() // make snapshotFlow observe the edits

            advanceTimeBy(401) // pass the 400 ms debounce
            advanceUntilIdle() // let the Room write complete

            val saved = repo.getNote(id)
            assertEquals("Groceries", saved?.title)
            assertEquals("eggs", saved?.body)
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }

    /** R4: trashing a brand-new note before the debounce fires must not lose its text. */
    @Test
    fun trashingWithUnsavedText_keepsTextInTrash() = runTest {
        val (db, repo) = setUpRepo()
        try {
            val id = UUID.randomUUID().toString()
            val vm = NoteEditorViewModel(NoteEditor(id, isNew = true), repo, backgroundScope)
            advanceUntilIdle()

            vm.title.setTextAndPlaceCursorAtEnd("Draft")
            vm.body.setTextAndPlaceCursorAtEnd("don't lose me")
            Snapshot.sendApplyNotifications()

            vm.onToggleTrash()   // tapped within the 400 ms window, before autosave fires
            advanceUntilIdle()

            val saved = repo.getNote(id)
            assertTrue("note should exist in trash", saved?.trashed == true)
            assertEquals("Draft", saved?.title)
            assertEquals("don't lose me", saved?.body)
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }

    /** Pinning a brand-new note before any text must survive the first autosave. */
    @Test
    fun pinningBeforeTyping_persistsPinOnFirstSave() = runTest {
        val (db, repo) = setUpRepo()
        try {
            val id = UUID.randomUUID().toString()
            val vm = NoteEditorViewModel(NoteEditor(id, isNew = true), repo, backgroundScope)
            advanceUntilIdle()

            vm.onTogglePin()   // pinned before any text exists (no row yet)
            advanceUntilIdle()

            vm.title.setTextAndPlaceCursorAtEnd("Later")
            Snapshot.sendApplyNotifications()
            advanceTimeBy(401)
            advanceUntilIdle()

            val saved = repo.getNote(id)
            assertTrue("pin should persist to the created row", saved?.pinned == true)
            assertEquals("Later", saved?.title)
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }

    /** Builds an in-memory Room DB whose executors run on the test dispatcher (set as Main). */
    private fun TestScope.setUpRepo(): Pair<SyncNotesDatabase, NotesRepository> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, SyncNotesDatabase::class.java)
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries() // the test dispatcher runs on Robolectric's main thread
            .build()
        val repo = NotesRepository(
            db = db,
            noteDao = db.noteDao(),
            clock = object : Clock { override fun nowMillis() = 1L },
            syncScheduler = object : SyncScheduler { override fun requestSync() {} },
        )
        return db to repo
    }
}
