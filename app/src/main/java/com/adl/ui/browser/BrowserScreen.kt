package com.adl.ui.browser

import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adl.domain.HistoryItem
import kotlinx.coroutines.launch

fun Context.findActivity(): ComponentActivity? {
    var cur = this
    while (cur is ContextWrapper) {
        if (cur is ComponentActivity) return cur
        cur = cur.baseContext
    }
    return null
}

@Composable
fun BrowserScreen() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val viewModel: BrowserViewModel = if (activity != null) hiltViewModel(activity) else hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recentUrls by viewModel.recentUrls.collectAsStateWithLifecycle()

    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    // Intercept back gesture: go back in web page if possible, else require double tap to exit
    BackHandler(enabled = true) {
        if (uiState.canGoBack) {
            viewModel.goBack()
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 2000L) {
                activity?.finish()
            } else {
                lastBackPressTime = now
                Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveWebViewState()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ─ Browser chrome (address bar + single options popup + recent URL chips)
            BrowserAddressBar(
                url = uiState.displayUrl,
                isLoading = uiState.isLoading,
                canGoBack = uiState.canGoBack,
                canGoForward = uiState.canGoForward,
                recentUrls = recentUrls,
                onNavigate = viewModel::navigate,
                onBack = viewModel::goBack,
                onForward = viewModel::goForward,
                onRefresh = viewModel::reload,
                onOpenHistory = viewModel::showHistorySheet,
                onClearHistory = viewModel::clearRecentUrls,
            )

            // Loading indicator
            if (uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ─ Persistent WebView
            AdlWebView(
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            )
        }

        // ─ Floating download button
        AnimatedVisibility(
            visible = uiState.isSupportedSite,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            ExtendedFloatingActionButton(
                text = { Text("Download ${uiState.siteName}") },
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                onClick = viewModel::showDownloadSheet,
                containerColor = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // ─ Download confirmation bottom sheet
    if (uiState.showDownloadSheet) {
        DownloadConfirmSheet(
            url = uiState.displayUrl,
            siteName = uiState.siteName,
            onConfirm = viewModel::startDownload,
            onDismiss = viewModel::dismissDownloadSheet,
        )
    }

    // ─ Recent URLs history sheet
    if (uiState.showHistorySheet) {
        RecentUrlsSheet(
            recentUrls = recentUrls,
            onNavigate = { url ->
                viewModel.navigate(url)
                viewModel.dismissHistorySheet()
            },
            onRemove = viewModel::removeRecentUrl,
            onClearAll = viewModel::clearRecentUrls,
            onDismiss = viewModel::dismissHistorySheet,
        )
    }
}

@Composable
fun AdlWebView(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val wv = viewModel.getOrCreateWebView(ctx)
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv
        },
        update = {
            // WebView retains internal rendering state and history
        },
    )
}

@Composable
fun BrowserAddressBar(
    url: String,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    recentUrls: List<HistoryItem>,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onOpenHistory: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var inputText by remember(url) { mutableStateOf(url) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            // Main search / URL input row with single options popup button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            keyboard?.hide()
                            onNavigate(inputText)
                        },
                    ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    trailingIcon = {
                        if (inputText.isNotBlank()) {
                            IconButton(onClick = { inputText = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear input",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    placeholder = {
                        Text(
                            "Search or enter URL",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                )

                Spacer(Modifier.width(4.dp))

                // Single options popup button
                Box {
                    IconButton(onClick = { showOptionsMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Browser options",
                        )
                    }

                    DropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false },
                    ) {
                        // Quick navigation button row at top of menu
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    showOptionsMenu = false
                                    onBack()
                                },
                                enabled = canGoBack,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                            }

                            IconButton(
                                onClick = {
                                    showOptionsMenu = false
                                    onForward()
                                },
                                enabled = canGoForward,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Forward",
                                    tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                            }

                            IconButton(
                                onClick = {
                                    showOptionsMenu = false
                                    onRefresh()
                                }
                            ) {
                                Icon(
                                    imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                    contentDescription = if (isLoading) "Stop" else "Reload",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            IconButton(
                                onClick = {
                                    showOptionsMenu = false
                                    onOpenHistory()
                                }
                            ) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = "Recent URLs",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        DropdownMenuItem(
                            text = { Text("Recent URLs History (${recentUrls.size}/5)") },
                            leadingIcon = {
                                Icon(Icons.Default.History, contentDescription = null)
                            },
                            onClick = {
                                showOptionsMenu = false
                                onOpenHistory()
                            },
                        )

                        DropdownMenuItem(
                            text = { Text(if (isLoading) "Stop Loading" else "Reload Page") },
                            leadingIcon = {
                                Icon(if (isLoading) Icons.Default.Close else Icons.Default.Refresh, contentDescription = null)
                            },
                            onClick = {
                                showOptionsMenu = false
                                onRefresh()
                            },
                        )

                        DropdownMenuItem(
                            text = { Text("Go Forward") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                            },
                            enabled = canGoForward,
                            onClick = {
                                showOptionsMenu = false
                                onForward()
                            },
                        )

                        DropdownMenuItem(
                            text = { Text("Go Back") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            },
                            enabled = canGoBack,
                            onClick = {
                                showOptionsMenu = false
                                onBack()
                            },
                        )

                        if (recentUrls.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = { Text("Clear URL History", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    onClearHistory()
                                },
                            )
                        }
                    }
                }
            }

            // Quick chips for last 5 URLs
            if (recentUrls.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(recentUrls, key = { it.url }) { item ->
                        val domain = try {
                            val host = android.net.Uri.parse(item.url).host
                            if (!host.isNullOrBlank()) host.removePrefix("www.") else item.url
                        } catch (_: Exception) {
                            item.url
                        }
                        val displayText = if (item.title.isNotBlank() && item.title != item.url) {
                            item.title.take(24)
                        } else {
                            domain.take(24)
                        }

                        AssistChip(
                            onClick = {
                                keyboard?.hide()
                                onNavigate(item.url)
                            },
                            label = {
                                Text(
                                    text = displayText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Public,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                        )
                    }

                    item {
                        TextButton(
                            onClick = onClearHistory,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Clear history",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Clear",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentUrlsSheet(
    recentUrls: List<HistoryItem>,
    onNavigate: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Recent URLs",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "History of last ${recentUrls.size} visited URLs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (recentUrls.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            onClearAll()
                        },
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Clear All", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            if (recentUrls.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No history yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(recentUrls, key = { it.url }) { item ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = item.title.ifBlank { item.url },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = item.url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Public,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = { onRemove(item.url) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        onNavigate(item.url)
                                    }
                                },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadConfirmSheet(
    url: String,
    siteName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Download Gallery",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Site: $siteName",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "gallery-dl will download all images from this gallery. Your browser session (cookies) will be shared so login-protected content can be downloaded.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    },
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onConfirm() }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Download") }
            }
        }
    }
}
