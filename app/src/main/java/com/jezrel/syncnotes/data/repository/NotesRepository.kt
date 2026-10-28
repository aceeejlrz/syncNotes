package com.jezrel.syncnotes.data.repository

import com.jezrel.syncnotes.data.local.NoteEntity
import com.jezrel.syncnotes.data.local.NoteSummary
import kotlinx.coroutines.flow.Flow

/**
 * All note reads and writes (ARCHITECTURE.md §4.2). An interface so ViewModel tests can use a
 * fake; the real implementation is [RoomNotesRepository].
 */
interface NotesRepository {
    /** Active notes matching [query] literally (empty = all), pinned first, newest first. */
    fun observeActive(query: String): Flow<List<NoteSummary>>

    fun observeTrash(): Flow<List<NoteEntity>>

    suspend fun getNote(id: String): NoteEntity?

    /** Autosave entry point: creates lazily, marks only changed fields dirty (§7.4). */
    suspend fun saveNote(id: String, title: String, body: String)

    suspend fun setPinned(id: String, pinned: Boolean)

    suspend fun moveToTrash(id: String)

    suspend fun restore(id: String)
}
