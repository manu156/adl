package com.adl.data.repository

import com.adl.data.db.DownloadDao
import com.adl.data.db.DownloadEntity
import com.adl.data.db.DownloadStatus
import com.adl.data.db.ImageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadDao,
) {
    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()
    fun observeActive(): Flow<List<DownloadEntity>> = dao.observeActive()
    fun observeCompleted(): Flow<List<DownloadEntity>> = dao.observeCompleted()
    fun observeGalleries(): Flow<List<DownloadEntity>> = dao.observeGalleries()
    fun observeById(id: Long): Flow<DownloadEntity?> = dao.observeById(id)
    fun observeImages(downloadId: Long): Flow<List<ImageEntity>> = dao.observeImages(downloadId)
    fun observeDownloadedImages(downloadId: Long): Flow<List<ImageEntity>> = dao.observeDownloadedImages(downloadId)
    fun observeAllDownloadedImages(): Flow<List<ImageEntity>> = dao.observeAllDownloadedImages()

    suspend fun createDownload(entity: DownloadEntity): Long = dao.upsertDownload(entity)

    suspend fun updateProgress(id: Long, status: DownloadStatus, done: Int) {
        dao.updateProgress(id, status, done)
    }

    suspend fun updateStatus(id: Long, status: DownloadStatus) {
        dao.updateStatus(id, status)
    }

    suspend fun setThumbnail(id: Long, path: String) = dao.setThumbnail(id, path)
    suspend fun setTotalImages(id: Long, total: Int) = dao.setTotalImages(id, total)
    suspend fun deleteDownload(id: Long, deleteFiles: Boolean = false) {
        if (deleteFiles) {
            try {
                val images = dao.getImages(id)
                for (img in images) {
                    if (img.filePath.isNotBlank()) {
                        val file = java.io.File(img.filePath)
                        if (file.exists() && file.isFile) {
                            file.delete()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        dao.deleteImagesForDownload(id)
        dao.deleteById(id)
    }

    suspend fun addImage(image: ImageEntity) = dao.upsertImage(image)

    suspend fun saveDownloadedImage(downloadId: Long, index: Int, filePath: String) {
        val fileName = filePath.substringAfterLast(java.io.File.separatorChar)
        val existing = dao.findImage(downloadId, index) ?: dao.findImageByPath(downloadId, filePath)
        if (existing != null) {
            dao.upsertImage(
                existing.copy(
                    filePath = filePath,
                    fileName = fileName,
                    index = index,
                    isDownloaded = true,
                    downloadedAt = System.currentTimeMillis()
                )
            )
        } else {
            dao.upsertImage(
                ImageEntity(
                    downloadId = downloadId,
                    filePath = filePath,
                    fileName = fileName,
                    index = index,
                    isDownloaded = true,
                    downloadedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun markImageDownloaded(downloadId: Long, index: Int, path: String) {
        saveDownloadedImage(downloadId, index, path)
    }

    suspend fun syncDiskImages(downloadId: Long, outputDir: String) {
        // No-op: images are saved directly to Room DB on download
    }

    /**
     * Called once on app startup to fix downloads left in a broken state after
     * a service/process kill (e.g. app swiped away mid-download).
     *
     * Only touches IN_PROGRESS entries:
     * - If all images were already saved, marks COMPLETED.
     * - Otherwise moves to PAUSED so the user can resume with one tap.
     * COMPLETED downloads are never touched.
     */
    suspend fun fixStuckDownloads() {
        try {
            val allDownloads = dao.getAllDownloads()
            for (download in allDownloads) {
                if (download.status == DownloadStatus.IN_PROGRESS) {
                    val savedCount = dao.countDownloadedImages(download.id)
                    if (download.totalImages > 0 && savedCount >= download.totalImages) {
                        dao.updateProgress(download.id, DownloadStatus.COMPLETED, savedCount)
                    } else {
                        val correctCount = maxOf(savedCount, download.downloadedImages)
                        dao.updateProgress(download.id, DownloadStatus.PAUSED, correctCount)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun findByUrl(url: String): DownloadEntity? = dao.findByUrl(url)
}
