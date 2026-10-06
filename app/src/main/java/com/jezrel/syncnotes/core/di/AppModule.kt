package com.jezrel.syncnotes.core.di

import com.jezrel.syncnotes.core.time.Clock
import com.jezrel.syncnotes.core.time.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock()
}
