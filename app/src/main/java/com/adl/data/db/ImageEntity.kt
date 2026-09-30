package com.adl.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "images",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("downloadId")],
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: Long,
    /** Absolute path to the image file on disk */
    val filePath: String,
    val fileName: String,
    /** Position/index in the gallery (0-based) */
    val index: Int,
    val downloadedAt: Long? = null,
    val isDownloaded: Boolean = false,
)
