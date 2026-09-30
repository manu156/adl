package com.adl.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [DownloadEntity::class, ImageEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(AdlDatabase.Converters::class)
abstract class AdlDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    class Converters {
        @TypeConverter
        fun fromStatus(status: DownloadStatus): String = status.name

        @TypeConverter
        fun toStatus(name: String): DownloadStatus = DownloadStatus.valueOf(name)
    }
}
