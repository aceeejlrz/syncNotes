package com.jezrel.syncnotes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row table holding sync bookkeeping. See ARCHITECTURE.md §7.1 / §7.2. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,                                      // always a single row
    @ColumnInfo(name = "account_id") val accountId: String? = null,   // whose notes are on this device
    val cursor: Long = 0,                                             // highest server version pulled
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)
