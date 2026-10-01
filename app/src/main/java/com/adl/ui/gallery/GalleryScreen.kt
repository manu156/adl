package com.adl.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.adl.data.db.DownloadEntity

import androidx.compose.material3.Badge
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.adl.data.db.DownloadStatus
import com.adl.data.db.ImageEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onOpenDetail: (Long) -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val downloads by viewModel.galleries.collectAsStateWithLifecycle()
    val allImages by viewModel.allImages.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gallery") },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = uiState.viewMode == GalleryViewMode.FOLDERS,
                            onClick = { viewModel.setViewMode(GalleryViewMode.FOLDERS) },
                            label = { Text("Folders") },
                            leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) },
                        )
                        FilterChip(
                            selected = uiState.viewMode == GalleryViewMode.ALL_IMAGES,
                            onClick = { viewModel.setViewMode(GalleryViewMode.ALL_IMAGES) },
                            label = { Text("All (${allImages.size})") },
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        when (uiState.viewMode) {
            GalleryViewMode.FOLDERS -> {
                if (downloads.isEmpty()) {
                    EmptyGalleryPlaceholder(
                        title = "No galleries yet",
                        subtitle = "Browse a site and tap Download to save galleries",
                        modifier = Modifier.padding(paddingValues),
                    )
                } else {
                    FolderGrid(
                        downloads = downloads,
                        columns = uiState.gridColumns,
                        onFolderClick = onOpenDetail,
                        modifier = Modifier.padding(paddingValues),
                    )
                }
            }
            GalleryViewMode.ALL_IMAGES -> {
                if (allImages.isEmpty()) {
                    EmptyGalleryPlaceholder(
                        title = "No images saved yet",
                        subtitle = "Downloaded images across all galleries will appear here",
                        modifier = Modifier.padding(paddingValues),
                    )
                } else {
                    AllImagesGrid(
                        images = allImages,
                        columns = uiState.gridColumns,
                        onImageClick = onOpenDetail,
                        modifier = Modifier.padding(paddingValues),
                    )
                }
            }
        }
    }
}

@Composable
fun FolderGrid(
    downloads: List<DownloadEntity>,
    columns: Int,
    onFolderClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(2, 5)),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(downloads, key = { it.id }) { download ->
            FolderCard(
                download = download,
                onClick = { onFolderClick(download.id) },
            )
        }
    }
}

@Composable
fun FolderCard(
    download: DownloadEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (download.thumbnailPath != null) {
                    AsyncImage(
                        model = java.io.File(download.thumbnailPath),
                        contentDescription = download.galleryName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                // Status chip / badge
                when (download.status) {
                    DownloadStatus.IN_PROGRESS -> {
                        Surface(
                            shape = RoundedCornerShape(bottomStart = 8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            val countStr = if (download.totalImages > 0) {
                                "${download.downloadedImages}/${download.totalImages}"
                            } else {
                                "${download.downloadedImages}"
                            }
                            Text(
                                text = "Downloading $countStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        Surface(
                            shape = RoundedCornerShape(bottomStart = 8.dp),
                            color = Color(0xFFFFA000).copy(alpha = 0.95f),
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Text(
                                text = "Paused",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    DownloadStatus.FAILED -> {
                        Surface(
                            shape = RoundedCornerShape(bottomStart = 8.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Text(
                                text = "Failed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = download.galleryName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (download.status) {
                        DownloadStatus.IN_PROGRESS -> "Downloading • ${download.siteName}"
                        DownloadStatus.PAUSED -> "Paused • ${download.downloadedImages} images"
                        else -> "${download.downloadedImages} images • ${download.siteName}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AllImagesGrid(
    images: List<ImageEntity>,
    columns: Int,
    onImageClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceIn(2, 5)),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(images, key = { "${it.id}_${it.downloadId}_${it.index}" }) { image ->
            AsyncImage(
                model = java.io.File(image.filePath),
                contentDescription = image.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onImageClick(image.downloadId) },
            )
        }
    }
}

@Composable
fun EmptyGalleryPlaceholder(
    title: String = "No galleries yet",
    subtitle: String = "Browse a site and tap the Download button",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
