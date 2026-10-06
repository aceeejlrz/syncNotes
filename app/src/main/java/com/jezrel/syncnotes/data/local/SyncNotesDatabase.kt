package com.jezrel.syncnotes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [NoteEntity::class, SyncStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SyncNotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun syncStateDao(): SyncStateDao
}
