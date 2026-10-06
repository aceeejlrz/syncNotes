package com.jezrel.syncnotes.core.di

import com.jezrel.syncnotes.sync.NoOpSyncScheduler
import com.jezrel.syncnotes.sync.SyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    @Singleton
    abstract fun bindSyncScheduler(impl: NoOpSyncScheduler): SyncScheduler
}
