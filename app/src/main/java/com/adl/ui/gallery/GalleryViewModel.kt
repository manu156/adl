package com.adl.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adl.data.db.DownloadEntity
import com.adl.data.db.ImageEntity
import com.adl.data.repository.DownloadRepository
import com.adl.domain.PriorityDownloadQueue
import com.adl.domain.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GalleryViewMode { FOLDERS, ALL_IMAGES }

data class GalleryUiState(
    val viewMode: GalleryViewMode = GalleryViewMode.FOLDERS,
    val gridColumns: Int = 3,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val priorityQueue: PriorityDownloadQueue,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    val galleries: StateFlow<List<DownloadEntity>> =
        repository.observeGalleries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allImages: StateFlow<List<ImageEntity>> =
        repository.observeAllDownloadedImages()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedDownloads: StateFlow<List<DownloadEntity>> get() = galleries

    init {
        viewModelScope.launch {
            settingsRepository.gridColumns.collect { cols ->
                _uiState.update { it.copy(gridColumns = cols) }
            }
        }
    }

    fun setViewMode(mode: GalleryViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun getImages(downloadId: Long): Flow<List<ImageEntity>> =
        repository.observeImages(downloadId)

    fun getDownload(downloadId: Long): Flow<DownloadEntity?> =
        repository.observeById(downloadId)

    fun updatePriority(downloadId: Long, priorityIndices: Collection<Int>) {
        priorityQueue.setPriorityImages(downloadId, priorityIndices)
    }

    fun clearPriority(downloadId: Long) {
        priorityQueue.clearPriority(downloadId)
    }

    fun deleteGallery(downloadId: Long) {
        viewModelScope.launch {
            repository.deleteDownload(downloadId, deleteFiles = true)
        }
    }
}
