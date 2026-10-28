package com.jezrel.syncnotes.testing

import com.jezrel.syncnotes.data.local.NoteEntity
import com.jezrel.syncnotes.data.local.NoteSummary
import com.jezrel.syncnotes.data.repository.NotesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [NotesRepository] for ViewModel tests. It records every call and filters only on
 * `trashed` — deliberately no ordering, LIKE escaping or dirty bits: those are SQL concerns
 * already tested against real Room (NotesRepositoryTest), and copying them here would drift.
 */
class FakeNotesRepository(initial: List<NoteEntity> = emptyList()) : NotesRepository {

    val notes = MutableStateFlow(initial.associateBy { it.id })

    val activeQueries = mutableListOf<String>()
    val saves = mutableListOf<Triple<String, String, String>>()
    val pinCalls = mutableListOf<Pair<String, Boolean>>()
    val trashCalls = mutableListOf<String>()
    val restoreCalls = mutableListOf<String>()

    override fun observeActive(query: String): Flow<List<NoteSummary>> {
        activeQueries += query
        return notes.map { all -> all.values.filterNot { it.trashed }.map { it.toSummary() } }
    }

    override fun observeTrash(): Flow<List<NoteEntity>> =
        notes.map { all -> all.values.filter { it.trashed } }

    override suspend fun getNote(id: String): NoteEntity? = notes.value[id]

    override suspend fun saveNote(id: String, title: String, body: String) {
        saves += Triple(id, title, body)
        notes.update { it + (id to (it[id]?.copy(title = title, body = body) ?: note(id, title, body))) }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        pinCalls += id to pinned
        edit(id) { it.copy(pinned = pinned) }
    }

    override suspend fun moveToTrash(id: String) {
        trashCalls += id
        edit(id) { it.copy(trashed = true) }
    }

    override suspend fun restore(id: String) {
        restoreCalls += id
        edit(id) { it.copy(trashed = false) }
    }

    private fun edit(id: String, change: (NoteEntity) -> NoteEntity) =
        notes.update { all -> all[id]?.let { all + (id to change(it)) } ?: all }

    companion object {
        fun note(
            id: String,
            title: String = "",
            body: String = "",
            pinned: Boolean = false,
            trashed: Boolean = false,
        ) = NoteEntity(
            id = id, title = title, body = body, pinned = pinned, trashed = trashed,
            createdAt = 0, updatedAt = 0, serverVersion = 0, dirtyFields = 0, syncError = null,
        )

        private fun NoteEntity.toSummary() = NoteSummary(
            id = id, title = title, preview = body.take(200), pinned = pinned,
            updatedAt = updatedAt, dirtyFields = dirtyFields, syncError = syncError,
        )
    }
}
