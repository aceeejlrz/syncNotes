package com.jezrel.syncnotes.core.di

import com.jezrel.syncnotes.data.repository.NotesRepository
import com.jezrel.syncnotes.data.repository.RoomNotesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindNotesRepository(impl: RoomNotesRepository): NotesRepository
}
