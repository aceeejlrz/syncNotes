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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The durability target (text saved within ~0.5s of the last keystroke) rests on the 400 ms
 * autosave debounce committing to Room — not on the onCleared flush, which never runs on process
 * death. This drives that path on the JVM: type, let 400 ms pass, assert it persisted.
 *
 * Room's executors are bound to the test dispatcher so advanceUntilIdle awaits the DB write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [33])
class NoteEditorAutosaveTest {

    @Test
    fun autosavePersistsAfterDebounce() = runTest {
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
        try {
            val id = UUID.randomUUID().toString()
            val vm = NoteEditorViewModel(NoteEditor(id, isNew = true), repo, backgroundScope)
            advanceUntilIdle() // run init; autosave collector started (nothing to load for a new note)

            vm.title.setTextAndPlaceCursorAtEnd("Groceries")
            vm.body.setTextAndPlaceCursorAtEnd("eggs")
            Snapshot.sendApplyNotifications() // make snapshotFlow observe the edits

            advanceTimeBy(401) // pass the 400 ms debounce
            advanceUntilIdle() // let the Room write complete on the test dispatcher

            val saved = repo.getNote(id)
            assertEquals("Groceries", saved?.title)
            assertEquals("eggs", saved?.body)
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
