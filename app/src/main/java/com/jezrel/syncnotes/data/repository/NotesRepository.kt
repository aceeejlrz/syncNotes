package com.jezrel.syncnotes.data.repository

import androidx.room.withTransaction
import com.jezrel.syncnotes.core.time.Clock
import com.jezrel.syncnotes.data.local.DirtyField
import com.jezrel.syncnotes.data.local.NoteDao
import com.jezrel.syncnotes.data.local.NoteEntity
import com.jezrel.syncnotes.data.local.NoteSummary
import com.jezrel.syncnotes.data.local.SyncNotesDatabase
import com.jezrel.syncnotes.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All note reads and writes. Marks changed fields as dirty and asks for a sync.
 * See ARCHITECTURE.md §6.1 (P1: Room is the UI's source of truth) and §7.4.
 */
@Singleton
class NotesRepository @Inject constructor(
    private val db: SyncNotesDatabase,
    private val noteDao: NoteDao,
    private val clock: Clock,
    private val syncScheduler: SyncScheduler,
) {

    fun observeActive(query: String): Flow<List<NoteSummary>> =
        noteDao.observeActive(escapeLikeQuery(query.trim()))

    fun observeTrash(): Flow<List<NoteEntity>> = noteDao.observeTrash()

    suspend fun getNote(id: String): NoteEntity? = noteDao.get(id)

    /** Autosave entry point (§7.4). Creates lazily, marks only changed fields dirty. */
    suspend fun saveNote(id: String, title: String, body: String) {
        val changed = db.withTransaction {
            val now = clock.nowMillis()
            val existing = noteDao.get(id)
            when {
                existing == null && title.isBlank() && body.isBlank() -> false // nothing typed yet
                existing == null -> {
                    noteDao.upsert(
                        NoteEntity(
                            id = id, title = title, body = body, pinned = false, trashed = false,
                            createdAt = now, updatedAt = now, serverVersion = 0,
                            dirtyFields = DirtyField.ALL, syncError = null,
                        )
                    )
                    true
                }
                existing.title == title && existing.body == body -> false // no change
                else -> {
                    var dirty = existing.dirtyFields
                    if (existing.title != title) dirty = dirty or DirtyField.TITLE
                    if (existing.body != body) dirty = dirty or DirtyField.BODY
                    noteDao.upsert(
                        existing.copy(
                            title = title, body = body, updatedAt = now,
                            dirtyFields = dirty, syncError = null,
                        )
                    )
                    true
                }
            }
        }
        if (changed) syncScheduler.requestSync()
    }

    suspend fun setPinned(id: String, pinned: Boolean) = mutate(id) { e, now ->
        if (e.pinned == pinned) null
        else e.copy(pinned = pinned, updatedAt = now, dirtyFields = e.dirtyFields or DirtyField.PINNED, syncError = null)
    }

    suspend fun moveToTrash(id: String) = mutate(id) { e, now ->
        if (e.trashed) null
        else e.copy(trashed = true, updatedAt = now, dirtyFields = e.dirtyFields or DirtyField.TRASHED, syncError = null)
    }

    suspend fun restore(id: String) = mutate(id) { e, now ->
        if (!e.trashed) null
        else e.copy(trashed = false, updatedAt = now, dirtyFields = e.dirtyFields or DirtyField.TRASHED, syncError = null)
    }

    /** Load, transform one field (null = no change), persist, request a sync. */
    private suspend fun mutate(id: String, transform: (NoteEntity, Long) -> NoteEntity?) {
        val changed = db.withTransaction {
            val existing = noteDao.get(id) ?: return@withTransaction false
            val updated = transform(existing, clock.nowMillis()) ?: return@withTransaction false
            noteDao.upsert(updated)
            true
        }
        if (changed) syncScheduler.requestSync()
    }
}

/**
 * Escape LIKE wildcards so user input is matched literally; the DAO query uses ESCAPE '\'.
 * Backslash must be escaped first. Top-level + internal so it can be unit-tested directly.
 */
internal fun escapeLikeQuery(raw: String): String =
    if (raw.isEmpty()) ""
    else raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
