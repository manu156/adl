package com.adl.di

import android.content.Context
import com.adl.domain.CookieExporter
import com.adl.domain.PriorityDownloadQueue
import com.adl.domain.SiteDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideSiteDetector(): SiteDetector = SiteDetector()

    @Provides
    @Singleton
    fun provideCookieExporter(@ApplicationContext context: Context): CookieExporter =
        CookieExporter(context)

    @Provides
    @Singleton
    fun providePriorityDownloadQueue(scope: CoroutineScope): PriorityDownloadQueue =
        PriorityDownloadQueue(scope)
}
