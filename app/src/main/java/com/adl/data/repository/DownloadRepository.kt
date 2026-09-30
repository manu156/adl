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
    suspend fun deleteDownload(id: Long) {
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
        try {
            val dir = java.io.File(outputDir)
            if (!dir.exists() || !dir.isDirectory) return
            val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4")
            val files = dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in imageExtensions }
                .sortedBy { it.lastModified() }
                .toList()
            if (files.isNotEmpty()) {
                if (dao.countDownloadedImages(downloadId) == 0) {
                    files.forEachIndexed { idx, file ->
                        saveDownloadedImage(downloadId, idx, file.absolutePath)
                    }
                }
                files.firstOrNull()?.let { firstFile ->
                    dao.setThumbnail(downloadId, firstFile.absolutePath)
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun findByUrl(url: String): DownloadEntity? = dao.findByUrl(url)
}
