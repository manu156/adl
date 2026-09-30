package com.adl.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val galleryName: String,
    val siteName: String,
    /** Absolute path of the folder where images are saved */
    val outputDir: String,
    val status: DownloadStatus,
    val totalImages: Int = 0,
    val downloadedImages: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Path to the first downloaded image, used as thumbnail */
    val thumbnailPath: String? = null,
    /** Optional user note / tag */
    val note: String = "",
)
