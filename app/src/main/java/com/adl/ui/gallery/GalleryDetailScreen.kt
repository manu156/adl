package com.adl.ui.gallery

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.adl.data.db.DownloadEntity
import com.adl.data.db.DownloadStatus
import com.adl.data.db.ImageEntity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class DetailViewMode { GRID, VERTICAL_FEED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryDetailScreen(
    downloadId: Long,
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit = {},
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val images by viewModel.getImages(downloadId)
        .collectAsStateWithLifecycle(emptyList())
    val download by viewModel.getDownload(downloadId)
        .collectAsStateWithLifecycle(null)

    val gridState = rememberLazyGridState()
    var selectedImageIndex by remember { mutableStateOf<Int?>(null) }
    var viewMode by remember { mutableStateOf(DetailViewMode.GRID) }

    val totalImages = download?.totalImages ?: 0
    val isDownloading = download?.status == DownloadStatus.IN_PROGRESS || download?.status == DownloadStatus.PENDING

    val totalSlots = when {
        totalImages > 0 -> maxOf(totalImages, images.size)
        isDownloading -> images.size + 1
        else -> images.size
    }

    val imageByIndex = remember(images) { images.associateBy { it.index } }

    val subfolders = remember(images) {
        images.mapNotNull { img ->
            val file = java.io.File(img.filePath)
            val parent = file.parentFile?.name
            if (!parent.isNullOrBlank() && parent != "files" && parent != download?.siteName) parent else null
        }.distinct()
    }
    var selectedSubfolder by remember { mutableStateOf<String?>(null) }

    val activeImages = remember(images, selectedSubfolder) {
        if (selectedSubfolder == null) {
            images
        } else {
            images.filter { img ->
                java.io.File(img.filePath).parentFile?.name == selectedSubfolder
            }
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = when {
                                selectedSubfolder != null -> selectedSubfolder!!
                                subfolders.size > 1 -> "${download?.galleryName ?: "Gallery"} (${subfolders.size} folders)"
                                else -> download?.galleryName ?: "Gallery"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = when {
                                selectedSubfolder != null -> "${activeImages.size} images in this folder"
                                download?.status == DownloadStatus.IN_PROGRESS -> {
                                    val countStr = if (totalImages > 0) "$totalImages" else "?"
                                    "Downloading ${images.size} / $countStr images"
                                }
                                download?.status == DownloadStatus.PAUSED -> "Paused • ${images.size} images saved"
                                download?.status == DownloadStatus.FAILED -> "Failed • ${images.size} images saved"
                                download?.status == DownloadStatus.COMPLETED -> {
                                    if (subfolders.size > 1) "${images.size} images across ${subfolders.size} folders"
                                    else "${images.size} images saved"
                                }
                                else -> "${images.size} images"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    download?.url?.takeIf { it.isNotBlank() }?.let { url ->
                        IconButton(onClick = { onOpenInBrowser(url) }) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = "Open source in browser",
                            )
                        }
                    }
                    if (activeImages.isNotEmpty()) {
                        IconButton(onClick = {
                            viewMode = if (viewMode == DetailViewMode.GRID) DetailViewMode.VERTICAL_FEED else DetailViewMode.GRID
                        }) {
                            Icon(
                                imageVector = if (viewMode == DetailViewMode.GRID) Icons.Default.ViewStream else Icons.Default.GridView,
                                contentDescription = if (viewMode == DetailViewMode.GRID) "Vertical feed view" else "Grid view",
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Live progress bar during active download
            if (isDownloading) {
                if (totalImages > 0) {
                    val progressFloat = (images.size.toFloat() / maxOf(totalImages, images.size)).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progressFloat },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Subfolder filter chips for multi-level galleries
            if (subfolders.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item {
                        FilterChip(
                            selected = selectedSubfolder == null,
                            onClick = { selectedSubfolder = null },
                            label = { Text("All (${images.size})") },
                        )
                    }
                    items(subfolders) { folder ->
                        val count = images.count { java.io.File(it.filePath).parentFile?.name == folder }
                        FilterChip(
                            selected = selectedSubfolder == folder,
                            onClick = { selectedSubfolder = folder },
                            label = { Text("$folder ($count)") },
                            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }

            if (totalSlots == 0) {
                // Empty state based on actual status
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (download?.status) {
                        DownloadStatus.IN_PROGRESS, DownloadStatus.PENDING -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "Connecting to gallery...",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Images will appear as they complete downloading",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        DownloadStatus.PAUSED -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.PauseCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = Color(0xFFFFA000),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "Download is paused",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        DownloadStatus.FAILED -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "Download failed",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        else -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "No images in this gallery",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else if (viewMode == DetailViewMode.VERTICAL_FEED && images.isNotEmpty()) {
                // In-screen Vertical Pager: Up / Down swipe directly in the screen
                val inScreenPagerState = rememberPagerState(
                    initialPage = 0,
                    pageCount = { activeImages.size },
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    VerticalPager(
                        state = inScreenPagerState,
                        modifier = Modifier.fillMaxSize(),
                        key = { page -> activeImages.getOrNull(page)?.let { "${it.id}_$page" } ?: "page_$page" },
                    ) { page ->
                        val image = activeImages.getOrNull(page)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .clickable { selectedImageIndex = page },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (image != null && image.isDownloaded && image.filePath.isNotBlank()) {
                                AsyncImage(
                                    model = java.io.File(image.filePath),
                                    contentDescription = image.fileName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                    }

                    // Floating page indicator badge
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    ) {
                        Text(
                            text = "${inScreenPagerState.currentPage + 1} / ${activeImages.size} (Swipe \u2191\u2193)",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            } else {
                // Main Grid: shows real images and dummy/empty placeholder cards
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(activeImages, key = { "${it.id}_${it.index}" }) { image ->
                        GalleryImageCell(
                            image = image,
                            onClick = {
                                val indexInActive = activeImages.indexOfFirst { it.id == image.id }
                                selectedImageIndex = if (indexInActive >= 0) indexInActive else 0
                            },
                        )
                    }
                    if (isDownloading) {
                        item(key = "active_downloading_indicator") {
                            PendingImageCell(
                                index = activeImages.size + 1,
                                isActivelyDownloading = true,
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }
    }

    // Full-screen image viewer with Up / Down swipe navigation
    selectedImageIndex?.let { initialIndex ->
        if (activeImages.isNotEmpty()) {
            FullScreenImageViewer(
                images = activeImages,
                initialIndex = initialIndex.coerceIn(0, activeImages.size - 1),
                onDismiss = { selectedImageIndex = null },
            )
        }
    }
}

@Composable
fun GalleryImageCell(
    image: ImageEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = java.io.File(image.filePath),
            contentDescription = image.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Subtle index label
        Surface(
            shape = RoundedCornerShape(bottomStart = 6.dp),
            color = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Text(
                text = "#${image.index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun PendingImageCell(
    index: Int,
    isActivelyDownloading: Boolean,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp),
        ) {
            if (isActivelyDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Loading #$index",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    Icons.Default.HourglassEmpty,
                    contentDescription = "Pending #$index",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "#$index",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/**
 * Helper to traverse the view hierarchy to find the enclosing Window.
 * In a Compose Dialog, one of the parents is DialogWindowProvider.
 */
private fun findWindow(view: View): Window? {
    var current: ViewParent? = view.parent
    while (current != null) {
        if (current is DialogWindowProvider) {
            return current.window
        }
        current = current.parent
    }
    var context = view.context
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context.window
        }
        context = context.baseContext
    }
    return null
}

/**
 * Full-screen image viewer supporting Up / Down vertical swipe gestures
 * across all gallery images (both downloaded and not yet downloaded).
 *
 * When an image is opened:
 * - Prioritizes the current viewed image, plus a rolling window of 1 back and 5 forward.
 * - Displays a live placeholder with progress spinner for pending images.
 * - Supports tap-to-toggle edge-to-edge immersive mode.
 */
@Composable
fun FullScreenImageViewer(
    images: List<ImageEntity>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    val totalCount = images.size.coerceAtLeast(1)
    val safeInitialPage = initialIndex.coerceIn(0, totalCount - 1)
    val pagerState = rememberPagerState(
        initialPage = safeInitialPage,
        pageCount = { totalCount },
    )
    val scope = rememberCoroutineScope()
    var isImmersive by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {
            if (isImmersive) {
                isImmersive = false
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current

        // Handle back press: exit immersive first, then close dialog
        BackHandler(enabled = true) {
            if (isImmersive) {
                isImmersive = false
            } else {
                onDismiss()
            }
        }

        // Toggle system bars based on immersive state
        DisposableEffect(isImmersive, view) {
            val window = findWindow(view)
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (isImmersive) {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {
                val window = findWindow(view)
                if (window != null) {
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // ── Vertical Pager: Up / Down swipe for Prev / Next image ───────────
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { page -> images.getOrNull(page)?.let { "${it.id}_$page" } ?: "slot_$page" },
            ) { page ->
                val image = images.getOrNull(page)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            isImmersive = !isImmersive
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (image != null && image.isDownloaded && image.filePath.isNotBlank()) {
                        AsyncImage(
                            model = java.io.File(image.filePath),
                            contentDescription = image.fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Image unavailable",
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }
            }

            // ── Top Action Bar Overlay ──────────────────────────────────────────
            val currentImage = images.getOrNull(pagerState.currentPage)

            AnimatedVisibility(
                visible = !isImmersive,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = currentImage?.fileName?.takeIf { it.isNotBlank() } ?: "Image #${pagerState.currentPage + 1}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} / $totalCount (Swipe \u2191\u2193)",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    // Immersive toggle & Prev / Next arrow buttons
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isImmersive = !isImmersive }) {
                            Icon(
                                imageVector = if (isImmersive) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Toggle Immersive Mode",
                                tint = Color.White,
                            )
                        }

                        IconButton(
                            onClick = {
                                if (pagerState.currentPage > 0) {
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            },
                            enabled = pagerState.currentPage > 0,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Previous image",
                                tint = if (pagerState.currentPage > 0) Color.White else Color.DarkGray,
                            )
                        }

                        IconButton(
                            onClick = {
                                if (pagerState.currentPage < totalCount - 1) {
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                }
                            },
                            enabled = pagerState.currentPage < totalCount - 1,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Next image",
                                tint = if (pagerState.currentPage < totalCount - 1) Color.White else Color.DarkGray,
                            )
                        }
                    }
                }
            }
        }
    }
}
