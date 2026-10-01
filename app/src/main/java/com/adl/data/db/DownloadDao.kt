package com.adl.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    // ─ Downloads ────────────────────────────────────────────────────

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('IN_PROGRESS', 'PENDING', 'PAUSED', 'FAILED', 'CANCELLED') ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY updatedAt DESC")
    fun observeCompleted(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE downloadedImages > 0 OR status IN ('COMPLETED', 'IN_PROGRESS', 'PAUSED') ORDER BY updatedAt DESC")
    fun observeGalleries(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): DownloadEntity?

    @Upsert
    suspend fun upsertDownload(entity: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status, downloadedImages = MAX(downloadedImages, :done), updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(
        id: Long,
        status: DownloadStatus,
        done: Int,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(
        id: Long,
        status: DownloadStatus,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET thumbnailPath = :path WHERE id = :id")
    suspend fun setThumbnail(id: Long, path: String)

    @Query("UPDATE downloads SET totalImages = :total WHERE id = :id")
    suspend fun setTotalImages(id: Long, total: Int)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ─ Images ───────────────────────────────────────────────────────

    @Query("SELECT * FROM images WHERE downloadId = :downloadId ORDER BY `index` ASC")
    fun observeImages(downloadId: Long): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE downloadId = :downloadId AND isDownloaded = 1 ORDER BY `index` ASC")
    fun observeDownloadedImages(downloadId: Long): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images")
    suspend fun getAllImages(): List<ImageEntity>

    @Query("SELECT * FROM downloads")
    suspend fun getAllDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM images WHERE isDownloaded = 1 AND filePath != '' ORDER BY downloadedAt DESC")
    fun observeAllDownloadedImages(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE downloadId = :downloadId AND `index` = :index LIMIT 1")
    suspend fun findImage(downloadId: Long, index: Int): ImageEntity?

    @Query("SELECT * FROM images WHERE downloadId = :downloadId AND filePath = :filePath LIMIT 1")
    suspend fun findImageByPath(downloadId: Long, filePath: String): ImageEntity?

    @Upsert
    suspend fun upsertImage(entity: ImageEntity)

    @Query("UPDATE images SET isDownloaded = 1, downloadedAt = :now, filePath = :path WHERE downloadId = :downloadId AND `index` = :index")
    suspend fun markImageDownloaded(
        downloadId: Long,
        index: Int,
        path: String,
        now: Long = System.currentTimeMillis(),
    )

    @Query("SELECT COUNT(*) FROM images WHERE downloadId = :downloadId AND isDownloaded = 1")
    suspend fun countDownloadedImages(downloadId: Long): Int

    @Query("DELETE FROM images WHERE downloadId = :downloadId")
    suspend fun deleteImagesForDownload(downloadId: Long)
}
