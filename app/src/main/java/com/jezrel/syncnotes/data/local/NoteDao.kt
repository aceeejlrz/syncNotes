package com.jezrel.syncnotes.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Lightweight row for the list screen: no full body, so scrolling stays fast. */
data class NoteSummary(
    val id: String,
    val title: String,
    val preview: String,
    val pinned: Boolean,
    val updatedAt: Long,
    val dirtyFields: Int,
    val syncError: String?,
)

@Dao
interface NoteDao {

    // List screen: summaries only (no full bodies).
    // `query` must already be escaped: %, _ and \ prefixed with \ (done in NotesRepository).
    @Query(
        """
        SELECT id, title, substr(body, 1, 200) AS preview, pinned,
               updated_at AS updatedAt, dirty_fields AS dirtyFields, sync_error AS syncError
        FROM notes
        WHERE trashed = 0
          AND (:query = ''
               OR title LIKE '%' || :query || '%' ESCAPE '\'
               OR body  LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY pinned DESC, updated_at DESC
        """
    )
    fun observeActive(query: String): Flow<List<NoteSummary>>

    @Query("SELECT * FROM notes WHERE trashed = 1 ORDER BY updated_at DESC")
    fun observeTrash(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE dirty_fields != 0 AND sync_error IS NULL LIMIT :limit")
    suspend fun dirtyBatch(limit: Int): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes WHERE dirty_fields != 0")
    fun observePendingCount(): Flow<Int>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Upsert
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}
