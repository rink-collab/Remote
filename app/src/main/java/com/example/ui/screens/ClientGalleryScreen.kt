package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ConnectionStatus
import com.example.model.MediaFilter
import com.example.model.MediaItem
import com.example.model.TransferProgress
import com.example.ui.components.ConnectionBadge
import com.example.ui.components.MediaThumbnail
import com.example.ui.dialogs.MediaDetailDialog
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Cyan600
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.Emerald500
import com.example.ui.theme.Indigo400
import com.example.ui.theme.Indigo600
import com.example.ui.theme.Slate100
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.viewmodel.PeerMediaViewModel

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
    val filteredItems by viewModel.filteredMediaItems.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val transferMap by viewModel.transferMap.collectAsState()
    val selectedItemForDetail by viewModel.selectedItemForDetail.collectAsState()
    val selectedItemIds by viewModel.selectedItemIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()

    val isConnected = connectionStatus == ConnectionStatus.CONNECTED

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
                            Text(
                                text = if (isConnected) "Remote Media Vault" else "Connect to Device",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            if (isConnected) {
                                Text(
                                    text = "Room: $roomCode • ${filteredItems.size} items",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Cyan400,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        ConnectionBadge(
                            status = connectionStatus,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
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
                // Connected Gallery Grid
                Column(modifier = Modifier.fillMaxSize()) {
                    // Search & Filter Row
                    GalleryControlsRow(
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.setSearchQuery(it) },
                        selectedFilter = selectedFilter,
                        onFilterSelect = { viewModel.setFilter(it) }
                    )

                    // Media Grid
                    if (filteredItems.isEmpty()) {
                        EmptyGalleryView(searchQuery = searchQuery)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("media_gallery_grid")
                        ) {
                            items(filteredItems, key = { it.id }) { item ->
                                // Lazy Thumbnail Trigger
                                LaunchedEffect(item.id) {
                                    if (item.thumbnailBase64 == null) {
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
}

@Composable
private fun ConnectRoomView(
    initialCode: String,
    status: ConnectionStatus,
    statusMessage: String,
    onConnect: (String) -> Unit
) {
    val context = LocalContext.current
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
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (inputCode.isNotBlank()) onConnect(inputCode)
                        }
                    ),
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val pasted = clip.getItemAt(0).text.toString().trim()
                                inputCode = pasted.uppercase().take(6)
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste",
                                tint = Cyan400
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan400,
                        unfocusedBorderColor = Slate700,
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("room_code_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Connect Button
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (inputCode.isNotBlank()) onConnect(inputCode)
                    },
                    enabled = inputCode.length >= 4 && !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cyan600,
                        disabledContainerColor = Slate800
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("connect_room_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isLoading) "Establishing P2P Link..." else "Connect & Browse",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }

                if (statusMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (status == ConnectionStatus.ERROR) Color(0xFFF43F5E) else Cyan400
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryControlsRow(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedFilter: MediaFilter,
    onFilterSelect: (MediaFilter) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search photos & videos...", color = Slate600) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = Slate600
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = Slate600
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan400,
                unfocusedBorderColor = Slate800,
                focusedContainerColor = DarkBackground,
                unfocusedContainerColor = DarkBackground,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Filter chips
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
                        selectedContainerColor = Indigo600,
                        containerColor = DarkSurfaceVariant
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = Slate800,
                        selectedBorderColor = Indigo400
                    ),
                    shape = RoundedCornerShape(8.dp)
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
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = if (isSelected) Cyan400 else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
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
            durationText = item.formattedDuration,
            modifier = Modifier.fillMaxSize()
        )

        // Downloaded Indicator Badge
        if (item.isDownloaded || item.localUri != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
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
                        tint = Slate900,
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
                        modifier = Modifier.size(32.dp),
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
private fun EmptyGalleryView(searchQuery: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = Slate700,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (searchQuery.isNotEmpty()) "No media matching \"$searchQuery\"" else "No media found on secondary device",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Slate600,
                    fontWeight = FontWeight.Medium
                ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private val Slate900 = Color(0xFF0F172A)
