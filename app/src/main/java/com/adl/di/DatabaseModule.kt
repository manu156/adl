package com.adl.di

import android.content.Context
import androidx.room.Room
import com.adl.data.db.AdlDatabase
import com.adl.data.db.DownloadDao
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
    fun provideDatabase(@ApplicationContext context: Context): AdlDatabase =
        Room.databaseBuilder(context, AdlDatabase::class.java, "adl.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideDownloadDao(db: AdlDatabase): DownloadDao = db.downloadDao()
}
