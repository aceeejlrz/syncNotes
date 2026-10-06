package com.jezrel.syncnotes.core.di

import android.content.Context
import androidx.room.Room
import com.jezrel.syncnotes.data.local.NoteDao
import com.jezrel.syncnotes.data.local.SyncNotesDatabase
import com.jezrel.syncnotes.data.local.SyncStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SyncNotesDatabase =
        // No explicit SQLite driver: Room stays on the framework SQLite path, which is the
        // one Robolectric shadows for JVM tests and the one room-ktx's withTransaction targets.
        // Do not add setDriver(...) without re-checking those two together (see LEARNINGS.md).
        Room.databaseBuilder(context, SyncNotesDatabase::class.java, "syncnotes.db")
            .build()

    @Provides
    fun provideNoteDao(db: SyncNotesDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideSyncStateDao(db: SyncNotesDatabase): SyncStateDao = db.syncStateDao()
}
