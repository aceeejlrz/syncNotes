package com.jezrel.syncnotes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A note as stored on the device. See ARCHITECTURE.md §7.1 / §7.2. */
@Entity(
    tableName = "notes",
    indices = [Index("trashed", "pinned", "updated_at"), Index("dirty_fields")],
)
data class NoteEntity(
    @PrimaryKey val id: String,                                    // UUID, generated on a device
    val title: String,
    val body: String,
    val pinned: Boolean,
    val trashed: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,          // epoch ms, display only
    @ColumnInfo(name = "updated_at") val updatedAt: Long,          // epoch ms, display and sorting only
    @ColumnInfo(name = "server_version") val serverVersion: Long,  // the "base version"; 0 = never synced
    @ColumnInfo(name = "dirty_fields") val dirtyFields: Int,       // bitmask, see DirtyField
    @ColumnInfo(name = "sync_error") val syncError: String?,       // set when the server rejected this note
)

/** Bitmask of fields changed locally and not yet confirmed by the server. */
object DirtyField {
    const val TITLE = 1
    const val BODY = 2
    const val PINNED = 4
    const val TRASHED = 8
    const val ALL = TITLE or BODY or PINNED or TRASHED
}

fun Int.has(flag: Int): Boolean = (this and flag) != 0
