package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BreadcrumbItem
import com.example.model.ConnectionStatus
import com.example.model.FolderEntry
import com.example.model.MediaFilter
import com.example.model.MediaItem
import com.example.model.TransferProgress
import com.example.ui.components.ConnectionBadge
import com.example.ui.components.MediaDetailDialog
import com.example.ui.components.MediaThumbnail
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Cyan600
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.Emerald500
import com.example.ui.theme.Rose500
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.viewmodel.PeerMediaViewModel

private val FolderAmber = Color(0xFFFBBF24)
private val FolderCardBackground = Color(0xFF1E232E)
private val FolderCardBorder = Color(0xFF2B3242)
private val TopBarHeaderBg = Color(0xFF4A6572)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientGalleryScreen(
    viewModel: PeerMediaViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val roomCode by viewModel.roomCode.collectAsState()
    val rawMediaItems by viewModel.rawMediaItems.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val remoteDeviceName by viewModel.remoteDeviceName.collectAsState()
    val currentFolderPath by viewModel.currentFolderPath.collectAsState()
    val folderBreadcrumbs by viewModel.folderBreadcrumbs.collectAsState()
    val currentSubfolders by viewModel.currentSubfolders.collectAsState()
    val currentFolderFiles by viewModel.currentFolderFiles.collectAsState()
    val transferMap by viewModel.transferMap.collectAsState()
    val selectedItemForDetail by viewModel.selectedItemForDetail.collectAsState()
    val selectedItemIds by viewModel.selectedItemIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()

    val isConnected = connectionStatus == ConnectionStatus.CONNECTED
    var showExitConfirmDialog by remember { mutableStateOf(false) }

    // Intercept hardware and software Back presses
    BackHandler(enabled = true) {
        if (isSelectionMode) {
            viewModel.clearSelection()
        } else if (currentFolderPath.isNotEmpty()) {
            viewModel.navigateUp()
        } else if (isConnected) {
            showExitConfirmDialog = true
        } else {
            onBack()
        }
    }

    val handleTopBarBack: () -> Unit = {
        if (isSelectionMode) {
            viewModel.clearSelection()
        } else if (currentFolderPath.isNotEmpty()) {
            viewModel.navigateUp()
        } else if (isConnected) {
            showExitConfirmDialog = true
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                // Multi-select header
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedItemIds.size} Selected",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close selection",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = "Select All",
                                tint = Cyan400
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurfaceVariant)
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            val deviceHeaderTitle = if (isConnected) {
                                if (remoteDeviceName.isNotBlank() && remoteDeviceName != "Remote Device") {
                                    "Device - $remoteDeviceName"
                                } else {
                                    "Device - Xiaomi Note 7"
                                }
                            } else {
                                "Connect to Device"
                            }
                            Text(
                                text = deviceHeaderTitle,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    fontSize = 17.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isConnected) {
                                Text(
                                    text = "Room: $roomCode • ${rawMediaItems.size} items",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Cyan400,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = handleTopBarBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (currentFolderPath.isNotEmpty()) "Up" else "Back",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        if (isConnected) {
                            // Refresh Catalog
                            IconButton(onClick = { viewModel.refreshMediaCatalog() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Catalog",
                                    tint = Cyan400,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        ConnectionBadge(
                            status = connectionStatus,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = TopBarHeaderBg)
                )
            }
        },
        bottomBar = {
            // Batch Download Bottom Bar
            AnimatedVisibility(
                visible = isSelectionMode && selectedItemIds.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    color = DarkSurfaceVariant,
                    tonalElevation = 8.dp,
                    border = BorderStroke(1.dp, Slate800),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "${selectedItemIds.size} files selected",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Slate100,
                                fontWeight = FontWeight.SemiBold
                            )
                        )

                        Button(
                            onClick = { viewModel.requestBatchDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan600),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("batch_download_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download Selected")
                        }
                    }
                }
            }
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!isConnected) {
                // Join Room Form
                ConnectRoomView(
                    initialCode = roomCode,
                    status = connectionStatus,
                    statusMessage = statusMessage,
                    onConnect = { code -> viewModel.joinRoomAsClient(code) }
                )
            } else {
                // Connected Pure Folder Hierarchy Browser
                Column(modifier = Modifier.fillMaxSize()) {
                    // Search & Type Filter Bar
                    SearchAndFilterBar(
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        selectedFilter = selectedFilter,
                        onFilterSelect = { viewModel.setFilter(it) }
                    )

                    // Breadcrumbs Path Hierarchy Bar
                    BreadcrumbsPathBar(
                        breadcrumbs = folderBreadcrumbs,
                        currentPath = currentFolderPath,
                        onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) },
                        onNavigateUp = { viewModel.navigateUp() }
                    )

                    val hasSubfolders = currentSubfolders.isNotEmpty()
                    val hasFiles = currentFolderFiles.isNotEmpty()

                    if (hasSubfolders) {
                        // STRICT FOLDER GRID: Show only subfolders when folders exist (No mixed media items displayed directly)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("folder_hierarchy_grid")
                        ) {
                            items(currentSubfolders, key = { "folder_${it.fullPath}" }) { folder ->
                                FolderGridCard(
                                    folder = folder,
                                    onClick = { viewModel.navigateToFolder(folder.fullPath) }
                                )
                            }
                        }
                    } else if (hasFiles) {
                        // LEAF FOLDER: When inside a folder with media items and no subfolders, display the media files grid
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 105.dp),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("leaf_folder_media_grid")
                        ) {
                            items(currentFolderFiles, key = { it.id }) { item ->
                                LaunchedEffect(item.id) {
                                    if (!item.isAudio && item.thumbnailBase64 == null) {
                                        viewModel.requestThumbnail(item.id)
                                    }
                                }

                                val isSelected = selectedItemIds.contains(item.id)
                                val itemTransfer = transferMap[item.id]

                                GalleryGridItem(
                                    item = item,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    transferProgress = itemTransfer,
                                    onClick = {
                                        if (isSelectionMode) {
                                            viewModel.toggleItemSelection(item.id)
                                        } else {
                                            viewModel.setDetailItem(item)
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.toggleItemSelection(item.id)
                                    }
                                )
                            }
                        }
                    } else {
                        // Empty Folder or No search results
                        EmptyFolderView(
                            currentPath = currentFolderPath,
                            searchQuery = searchQuery,
                            onNavigateUp = { viewModel.navigateUp() },
                            onRefresh = { viewModel.refreshMediaCatalog() }
                        )
                    }
                }
            }
        }
    }

    // Detail Dialog
    selectedItemForDetail?.let { item ->
        val transfer = transferMap[item.id]
        MediaDetailDialog(
            item = item,
            transferProgress = transfer,
            onDismiss = { viewModel.setDetailItem(null) },
            onDownload = { viewModel.requestDownload(item) }
        )
    }

    // Exit Confirmation Dialog
    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = {
                Text(
                    text = "Exit File Browser?",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
            },
            text = {
                Text(
                    text = "This will disconnect from \"$remoteDeviceName\" and return to the main role selection.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = Slate100)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirmDialog = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose500)
                ) {
                    Text("Exit & Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmDialog = false }) {
                    Text("Stay in Browser", color = Cyan400)
                }
            },
            containerColor = DarkSurfaceVariant
        )
    }
}

/**
 * 3-Column Folder Card matching the User Screenshot:
 * Rounded dark grey card with centered golden folder icon and centered folder label.
 */
@Composable
private fun FolderGridCard(
    folder: FolderEntry,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = FolderCardBackground),
        border = BorderStroke(1.dp, FolderCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("folder_card_${folder.name}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Prominent Folder Glyph
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = folder.name,
                tint = FolderAmber,
                modifier = Modifier.size(46.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Folder Title
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE2E8F0),
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            if (folder.fileCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${folder.fileCount} items",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Slate600,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}

/**
 * Path Breadcrumbs Hierarchy Navigation Bar
 */
@Composable
private fun BreadcrumbsPathBar(
    breadcrumbs: List<BreadcrumbItem>,
    currentPath: String,
    onBreadcrumbClick: (String) -> Unit,
    onNavigateUp: () -> Unit
) {
    Surface(
        color = Color(0xFF161B26),
        border = BorderStroke(1.dp, Slate800),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (currentPath.isNotEmpty()) {
                IconButton(
                    onClick = onNavigateUp,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Up Directory",
                        tint = Cyan400,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            LazyRow(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(breadcrumbs.size) { index ->
                    val crumb = breadcrumbs[index]
                    val isLast = index == breadcrumbs.size - 1

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onBreadcrumbClick(crumb.path) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        if (index == 0) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = if (isLast) Cyan400 else Slate600,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = crumb.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                color = if (isLast) Cyan400 else Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        )
                    }

                    if (!isLast) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Slate700,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndFilterBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedFilter: MediaFilter,
    onFilterSelect: (MediaFilter) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search folders, photos, videos, audio...", color = Slate600, fontSize = 13.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Slate600,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = Slate600,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkBackground,
                unfocusedContainerColor = DarkBackground,
                focusedBorderColor = Cyan400,
                unfocusedBorderColor = Slate800,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Type filter chips (All, Photos, Videos)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            MediaFilter.entries.forEach { filter ->
                val isSelected = selectedFilter == filter
                FilterChip(
                    selected = isSelected,
                    onClick = { onFilterSelect(filter) },
                    label = {
                        Text(
                            text = filter.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else Slate600
                            )
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Cyan600,
                        containerColor = DarkBackground
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = if (isSelected) Cyan400 else Slate800,
                        selectedBorderColor = Cyan400
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(30.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryGridItem(
    item: MediaItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    transferProgress: TransferProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) Cyan400 else FolderCardBorder,
                shape = RoundedCornerShape(8.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("gallery_item_${item.id}")
    ) {
        MediaThumbnail(
            base64String = item.thumbnailBase64,
            isVideo = item.isVideo,
            isAudio = item.isAudio,
            durationText = item.formattedDuration,
            modifier = Modifier.fillMaxSize()
        )

        // Bottom label with filename & size
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color.White,
                    fontSize = 9.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Downloaded Indicator Badge (Top Right when not in selection mode, or offset)
        if (item.isDownloaded || item.localUri != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (isSelectionMode) 30.dp else 4.dp, 4.dp, 4.dp, 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Emerald500),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Downloaded",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Selection Checkbox
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Cyan400 else Color.Black.copy(alpha = 0.5f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = DarkBackground,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // In-progress download overlay
        if (transferProgress != null && !transferProgress.isComplete) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { transferProgress.progressFraction },
                        color = Cyan400,
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${transferProgress.progressPercent}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFolderView(
    currentPath: String,
    searchQuery: String,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = Slate700,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            val msg = if (searchQuery.isNotEmpty()) {
                "No files or folders matching \"$searchQuery\""
            } else if (currentPath.isNotEmpty()) {
                "Folder \"${currentPath.substringAfterLast('/')}\" is empty"
            } else {
                "No media received from host device yet"
            }
            Text(
                text = msg,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Slate600,
                    fontWeight = FontWeight.Medium
                ),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (currentPath.isNotEmpty()) {
                    Button(
                        onClick = onNavigateUp,
                        colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Go Up")
                    }
                }
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan600),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refresh")
                }
            }
        }
    }
}

@Composable
private fun ConnectRoomView(
    initialCode: String,
    status: ConnectionStatus,
    statusMessage: String,
    onConnect: (String) -> Unit
) {
    var inputCode by remember(initialCode) { mutableStateOf(initialCode) }
    val focusManager = LocalFocusManager.current
    val isLoading = status == ConnectionStatus.CONNECTING_FIREBASE || status == ConnectionStatus.CONNECTING_WEBRTC

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            border = BorderStroke(1.dp, Cyan400.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Cyan400.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = Cyan400,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Pair with Secondary Device",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Enter the 6-digit Room Code shown on your secondary phone.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Slate600,
                        textAlign = TextAlign.Center
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Input Field
                OutlinedTextField(
                    value = inputCode,
                    onValueChange = { inputCode = it.uppercase().take(6) },
                    placeholder = {
                        Text(
                            "e.g. 842-192",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = Slate600,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    },
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                        textAlign = TextAlign.Center
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (inputCode.length == 6) onConnect(inputCode)
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground,
                        focusedBorderColor = Cyan400,
                        unfocusedBorderColor = Slate800
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("client_room_code_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Paste from clipboard button
                val clipboardManager = LocalClipboardManager.current
                Button(
                    onClick = {
                        val text = clipboardManager.getText()?.text?.trim()?.uppercase()
                        if (text != null && text.length == 6) {
                            inputCode = text
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = Cyan400,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste Code from Clipboard", color = Slate100, style = MaterialTheme.typography.labelMedium)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connect Action Button
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onConnect(inputCode.trim())
                    },
                    enabled = inputCode.trim().length >= 4 && !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cyan600,
                        disabledContainerColor = Slate800
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("client_connect_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Connecting via WebRTC P2P...")
                    } else {
                        Text(
                            text = "Connect & Browse Vault",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (status == ConnectionStatus.ERROR) Color(0xFFF43F5E) else Slate600,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                )
            }
        }
    }
}
