package com.jezrel.syncnotes.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jezrel.syncnotes.core.time.Clock
import com.jezrel.syncnotes.data.local.DirtyField
import com.jezrel.syncnotes.data.local.NoteEntity
import com.jezrel.syncnotes.data.local.SyncNotesDatabase
import com.jezrel.syncnotes.data.local.has
import com.jezrel.syncnotes.data.repository.NotesRepository
import com.jezrel.syncnotes.sync.SyncScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** Repository behaviour against an in-memory Room database (JVM via Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NotesRepositoryTest {

    private lateinit var db: SyncNotesDatabase
    private lateinit var repo: NotesRepository
    private val clock = MutableClock(1_000L)
    private var syncRequests = 0

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SyncNotesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NotesRepository(
            db = db,
            noteDao = db.noteDao(),
            clock = clock,
            syncScheduler = object : SyncScheduler { override fun requestSync() { syncRequests++ } },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun blankNote_createsNothing() = runTest {
        val id = UUID.randomUUID().toString()
        repo.saveNote(id, "", "   ")
        assertNull(repo.getNote(id))
        assertEquals(0, syncRequests)
    }

    @Test
    fun newNote_allFieldsDirty_andNeverSynced() = runTest {
        val id = UUID.randomUUID().toString()
        repo.saveNote(id, "Groceries", "eggs")
        val n = repo.getNote(id)!!
        assertEquals(DirtyField.ALL, n.dirtyFields)
        assertEquals(0L, n.serverVersion)
        assertEquals(1, syncRequests)
    }

    @Test
    fun edit_marksOnlyChangedFieldDirty_andKeepsBaseVersion() = runTest {
        val id = UUID.randomUUID().toString()
        upsertSynced(id, title = "Title", updatedAt = 10, pinned = false, body = "Body")
        repo.saveNote(id, "Title", "Body changed") // only body differs
        val n = repo.getNote(id)!!
        assertTrue(n.dirtyFields.has(DirtyField.BODY))
        assertFalse(n.dirtyFields.has(DirtyField.TITLE))
        assertEquals(1L, n.serverVersion) // base version preserved
    }

    @Test
    fun savingIdenticalValues_isNoOp() = runTest {
        val id = UUID.randomUUID().toString()
        repo.saveNote(id, "A", "B")
        clock.now = 2_000L
        repo.saveNote(id, "A", "B") // identical
        val n = repo.getNote(id)!!
        assertEquals(1_000L, n.updatedAt) // unchanged
        assertEquals(1, syncRequests) // only the first save requested a sync
    }

    @Test
    fun activeList_pinnedFirstThenMostRecent() = runTest {
        upsertSynced("a", title = "Alpha", updatedAt = 100, pinned = false)
        upsertSynced("b", title = "Bravo", updatedAt = 300, pinned = false)
        upsertSynced("c", title = "Charlie", updatedAt = 200, pinned = true)
        val ids = repo.observeActive("").first().map { it.id }
        assertEquals(listOf("c", "b", "a"), ids)
    }

    @Test
    fun trash_hidesFromActive_thenRestoreBringsItBack() = runTest {
        val id = "n1"
        upsertSynced(id, title = "Keep", updatedAt = 100, pinned = false)
        repo.moveToTrash(id)
        assertTrue(repo.observeActive("").first().isEmpty())
        assertTrue(repo.getNote(id)!!.dirtyFields.has(DirtyField.TRASHED))
        repo.restore(id)
        assertEquals(listOf(id), repo.observeActive("").first().map { it.id })
    }

    @Test
    fun search_treatsWildcardsLiterally() = runTest {
        upsertSynced("p", title = "100% done", updatedAt = 100, pinned = false)
        upsertSynced("q", title = "100 ok", updatedAt = 200, pinned = false)
        val hits = repo.observeActive("100%").first().map { it.id }
        assertEquals(listOf("p"), hits) // "%" matched literally, not as a wildcard
    }

    @Test
    fun observeTrash_showsOnlyTrashedNotes_andRestoreClearsThem() = runTest {
        upsertSynced("keep", title = "Keep", updatedAt = 100, pinned = false)
        upsertSynced("gone", title = "Gone", updatedAt = 200, pinned = false)
        repo.moveToTrash("gone")
        assertEquals(listOf("gone"), repo.observeTrash().first().map { it.id })
        repo.restore("gone")
        assertTrue(repo.observeTrash().first().isEmpty())
    }

    private suspend fun upsertSynced(
        id: String,
        title: String,
        updatedAt: Long,
        pinned: Boolean,
        body: String = "body",
    ) {
        db.noteDao().upsert(
            NoteEntity(
                id = id, title = title, body = body, pinned = pinned, trashed = false,
                createdAt = updatedAt, updatedAt = updatedAt, serverVersion = 1,
                dirtyFields = 0, syncError = null,
            )
        )
    }

    private class MutableClock(var now: Long) : Clock {
        override fun nowMillis(): Long = now
    }
}
