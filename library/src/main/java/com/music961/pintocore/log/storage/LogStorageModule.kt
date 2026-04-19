package com.music961.pintocore.log.storage

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room DB 및 DAO 바인딩.
 *
 * @since pinto-core-log 0.1.0
 */
@Module
@InstallIn(SingletonComponent::class)
object LogStorageModule {

    @Provides
    @Singleton
    fun providePintoLogDatabase(@ApplicationContext context: Context): PintoLogDatabase =
        Room.databaseBuilder(context, PintoLogDatabase::class.java, "PintoLog.db").build()

    @Provides
    @Singleton
    fun provideLogEntryDao(db: PintoLogDatabase): LogEntryDao = db.logEntryDao()
}
