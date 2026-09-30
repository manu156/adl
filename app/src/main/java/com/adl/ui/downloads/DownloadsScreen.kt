package com.adl.ui.downloads

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.adl.data.db.DownloadEntity
import com.adl.data.db.DownloadStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onOpenGallery: (Long) -> Unit,
    onOpenInBrowser: (String) -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val active by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val completed by viewModel.completedDownloads.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedDownloadForLogs by remember { mutableStateOf<DownloadEntity?>(null) }
    var downloadToDelete by remember { mutableStateOf<DownloadEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Downloads") })
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Active & Paused (${active.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Completed (${completed.size})") },
                )
            }

            when (selectedTab) {
                0 -> DownloadList(
                    downloads = active,
                    emptyMessage = "No active downloads",
                    onOpen = onOpenGallery,
                    onOpenInBrowser = onOpenInBrowser,
                    onPause = viewModel::pauseDownload,
                    onResume = viewModel::resumeDownload,
                    onDelete = { downloadToDelete = it },
                    onViewLogs = { selectedDownloadForLogs = it },
                    isActive = true,
                )
                1 -> DownloadList(
                    downloads = completed,
                    emptyMessage = "No completed downloads yet",
                    onOpen = onOpenGallery,
                    onOpenInBrowser = onOpenInBrowser,
                    onPause = {},
                    onResume = {},
                    onDelete = { downloadToDelete = it },
                    onViewLogs = { selectedDownloadForLogs = it },
                    isActive = false,
                )
            }
        }
    }

    selectedDownloadForLogs?.let { download ->
        DownloadLogsBottomSheet(
            download = download,
            viewModel = viewModel,
            onOpenInBrowser = onOpenInBrowser,
            onDismiss = { selectedDownloadForLogs = null },
        )
    }

    downloadToDelete?.let { download ->
        AlertDialog(
            onDismissRequest = { downloadToDelete = null },
            title = { Text("Delete Download?") },
            text = { Text("Are you sure you want to delete '${download.galleryName}'? Downloaded files and records will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDownload(download, deleteFiles = true)
                        downloadToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { downloadToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DownloadList(
    downloads: List<DownloadEntity>,
    emptyMessage: String,
    onOpen: (Long) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onPause: (DownloadEntity) -> Unit,
    onResume: (DownloadEntity) -> Unit,
    onDelete: (DownloadEntity) -> Unit,
    onViewLogs: (DownloadEntity) -> Unit,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Inbox,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    emptyMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(downloads, key = { it.id }) { download ->
                DownloadItem(
                    download = download,
                    isActive = isActive,
                    onOpen = { onOpen(download.id) },
                    onOpenInBrowser = { onOpenInBrowser(download.url) },
                    onPause = { onPause(download) },
                    onResume = { onResume(download) },
                    onDelete = { onDelete(download) },
                    onViewLogs = { onViewLogs(download) },
                )
            }
        }
    }
}

@Composable
fun DownloadItem(
    download: DownloadEntity,
    isActive: Boolean,
    onOpen: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onViewLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onViewLogs),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            // Top Section: Thumbnail + Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Thumbnail
                if (download.thumbnailPath != null) {
                    AsyncImage(
                        model = download.thumbnailPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when (download.status) {
                                DownloadStatus.PAUSED -> Icons.Default.PauseCircle
                                DownloadStatus.FAILED -> Icons.Default.Error
                                else -> Icons.Default.Download
                            },
                            contentDescription = null,
                            tint = when (download.status) {
                                DownloadStatus.PAUSED -> Color(0xFFFFA000)
                                DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                // Info Column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = download.galleryName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = download.siteName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = download.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onOpenInBrowser),
                    )

                    Spacer(Modifier.height(6.dp))

                    when (download.status) {
                        DownloadStatus.IN_PROGRESS -> {
                            if (download.totalImages > 0) {
                                LinearProgressIndicator(
                                    progress = { download.downloadedImages.toFloat() / download.totalImages },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${download.downloadedImages} / ${download.totalImages} images",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (download.downloadedImages > 0) "${download.downloadedImages} images (downloading...)" else "Starting engine...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        DownloadStatus.PENDING -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Pending...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DownloadStatus.PAUSED -> {
                            Text(
                                text = "Paused (${download.downloadedImages} images saved)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFA000),
                            )
                        }
                        DownloadStatus.FAILED -> {
                            Text(
                                text = "Failed (tap to view error logs)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        DownloadStatus.COMPLETED -> {
                            Text(
                                text = "${download.downloadedImages} images downloaded",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DownloadStatus.CANCELLED -> {
                            Text(
                                text = "Cancelled",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(4.dp))

            // Bottom Actions Row: Status pill on left, Action buttons on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Status indicator pill
                val (statusText, statusBgColor, statusFgColor) = when (download.status) {
                    DownloadStatus.IN_PROGRESS -> Triple(
                        if (download.totalImages > 0) "${download.downloadedImages}/${download.totalImages}" else "Downloading",
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    DownloadStatus.PENDING -> Triple(
                        "Pending",
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DownloadStatus.PAUSED -> Triple(
                        "Paused",
                        Color(0xFFFFF3E0),
                        Color(0xFFE65100),
                    )
                    DownloadStatus.FAILED -> Triple(
                        "Failed",
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                    )
                    DownloadStatus.COMPLETED -> Triple(
                        "Done",
                        Color(0xFFE8F5E9),
                        Color(0xFF2E7D32),
                    )
                    DownloadStatus.CANCELLED -> Triple(
                        "Cancelled",
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusBgColor,
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusFgColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }

                // Action buttons row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Pause / Continue button
                    when (download.status) {
                        DownloadStatus.IN_PROGRESS, DownloadStatus.PENDING -> {
                            IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Default.Pause,
                                    contentDescription = "Pause",
                                    tint = Color(0xFFFFA000),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Continue",
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DownloadStatus.COMPLETED -> Unit
                    }

                    // Open in Browser button
                    IconButton(onClick = onOpenInBrowser, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = "Open in browser",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Open Gallery button
                    IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open gallery",
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Logs button
                    IconButton(onClick = onViewLogs, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = "View logs",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Delete button
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadLogsBottomSheet(
    download: DownloadEntity,
    viewModel: DownloadsViewModel,
    onOpenInBrowser: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val allLogs by viewModel.downloadLogs.collectAsStateWithLifecycle()
    val liveLogs = allLogs[download.id] ?: emptyList()

    var pythonFallbackLogs by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(download.id) {
        pythonFallbackLogs = viewModel.getCombinedLogs(download.id)
    }

    LaunchedEffect(liveLogs.size) {
        if (liveLogs.isNotEmpty()) {
            listState.animateScrollToItem(liveLogs.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Engine Logs",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = download.galleryName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row {
                    IconButton(
                        onClick = {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                onOpenInBrowser(download.url)
                                onDismiss()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Public, contentDescription = "Open in browser")
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                pythonFallbackLogs = viewModel.getCombinedLogs(download.id)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh logs")
                    }
                    IconButton(
                        onClick = {
                            val fullLogText = if (liveLogs.isNotEmpty()) {
                                liveLogs.joinToString("\n")
                            } else {
                                pythonFallbackLogs
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("ADL Logs", fullLogText))
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(
                        onClick = {
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // URL badge (clickable to open in browser)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onOpenInBrowser(download.url)
                            onDismiss()
                        }
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = download.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Terminal Console
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF141414))
                    .padding(8.dp),
            ) {
                if (liveLogs.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(liveLogs) { line ->
                            val textColor = when {
                                line.contains("[ERROR]") || line.contains("error", ignoreCase = true) -> Color(0xFFFF5252)
                                line.contains("[WARN]") || line.contains("[WARNING]") -> Color(0xFFFFD740)
                                line.contains("[SUCCESS]") -> Color(0xFF69F0AE)
                                line.contains("[DEBUG]") -> Color(0xFF90A4AE)
                                else -> Color(0xFFE0E0E0)
                            }
                            Text(
                                text = line,
                                color = textColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                } else if (pythonFallbackLogs.isNotBlank()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(pythonFallbackLogs.lines()) { line ->
                            val textColor = when {
                                line.contains("[ERROR]") || line.contains("error", ignoreCase = true) -> Color(0xFFFF5252)
                                line.contains("[WARN]") || line.contains("[WARNING]") -> Color(0xFFFFD740)
                                line.contains("[SUCCESS]") -> Color(0xFF69F0AE)
                                else -> Color(0xFFE0E0E0)
                            }
                            Text(
                                text = line,
                                color = textColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Waiting for gallery-dl output...",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
