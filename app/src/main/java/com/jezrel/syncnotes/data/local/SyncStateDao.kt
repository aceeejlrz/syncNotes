package com.jezrel.syncnotes.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?        // null means "defaults"

    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun observe(): Flow<SyncStateEntity?>

    @Upsert
    suspend fun upsert(state: SyncStateEntity)
}
