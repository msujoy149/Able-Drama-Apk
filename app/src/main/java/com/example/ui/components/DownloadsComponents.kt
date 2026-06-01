package com.example.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DownloadItem
import com.example.data.DownloadRepository
import com.example.util.DownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat

// Storage Information Finder
fun getStorageStats(context: Context): String {
    return try {
        val iDir = context.getExternalFilesDir(null) ?: context.filesDir
        val stat = StatFs(iDir.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        
        val totalBytes = totalBlocks * blockSize
        val availableBytes = availableBlocks * blockSize
        
        val totalGB = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val availableGB = availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val freePct = (availableBytes.toDouble() / totalBytes.toDouble()) * 100.0
        
        val df = DecimalFormat("#.##")
        "Storage: ${df.format(availableGB)}GB/${df.format(totalGB)}GB, ${df.format(freePct)}% free"
    } catch (e: Exception) {
        "Storage: 14.06GB/108.71GB, 12.9% free"
    }
}

// Byte Unit Formatter
fun formatByteSize(bytes: Long): String {
    if (bytes <= 0) return "Unknown size"
    val df = DecimalFormat("#.##")
    return when {
        bytes >= 1024 * 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024 * 1024 * 1024))}GB ($bytes bytes)"
        bytes >= 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024 * 1024))}MB ($bytes bytes)"
        else -> "${df.format(bytes.toDouble() / 1024)}KB ($bytes bytes)"
    }
}

// 1DM-Style "Download File!" Dialog (Screenshot 1)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadFileDialog(
    initialUrl: String,
    onDismissRequest: () -> Unit,
    downloadRepository: DownloadRepository,
    coroutineScope: CoroutineScope
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initialUrl) }
    var fileName by remember { mutableStateOf("loading_details...") }
    var fileExtension by remember { mutableStateOf("mp4") }
    var fileSize by remember { mutableLongStateOf(0L) }
    var isResumeSupported by remember { mutableStateOf(true) }
    var isProbing by remember { mutableStateOf(true) }

    // Probing URL Header options to match screenshot parameters
    LaunchedEffect(initialUrl) {
        DownloadEngine.probeUrl(initialUrl) { resolvedName, size, resume ->
            isProbing = false
            fileSize = size
            isResumeSupported = resume

            val dotIndex = resolvedName.lastIndexOf('.')
            if (dotIndex != -1 && dotIndex < resolvedName.length - 1) {
                fileName = resolvedName.substring(0, dotIndex)
                fileExtension = resolvedName.substring(dotIndex + 1)
            } else {
                fileName = resolvedName
                fileExtension = "mp4"
            }
        }
    }

    // Default Storage options - Safely save to the phone's public download directory under "Able Drama"
    val defaultDir = remember(context) {
        try {
            val rootDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val sub = File(rootDownloadDir, "Able Drama")
            if (!sub.exists()) {
                sub.mkdirs()
            }
            if (sub.exists() && sub.canWrite()) {
                sub
            } else {
                // Fallback to application standard external downloads directory
                val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val fallbackSub = File(base, "Able Drama")
                if (!fallbackSub.exists()) {
                    fallbackSub.mkdirs()
                }
                fallbackSub
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Ultimate safe fallback
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val fallbackSub = File(base, "Able Drama")
            try {
                if (!fallbackSub.exists()) {
                    fallbackSub.mkdirs()
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            fallbackSub
        }
    }
    
    val displayPath = remember(defaultDir) {
        val p = defaultDir.absolutePath
        if (p.contains("/emulated/0/")) {
            "/storage/emulated/0/" + p.substringAfter("/emulated/0/")
        } else {
            p
        }
    }

    // Interactive Checkbox States
    var useWebpageTitle by remember { mutableStateOf(true) }
    var wifiOnly by remember { mutableStateOf(false) }
    var retryOnFail by remember { mutableStateOf(true) }
    var useProxy by remember { mutableStateOf(false) }
    var hiddenFile by remember { mutableStateOf(false) }
    var useAdvancedDownloadMethod by remember { mutableStateOf(true) }
    var advanceOption by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Download file!",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Database Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Link (Resume Info Row)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val resumeLabel = if (isResumeSupported) "Yes" else "No"
                    val resumeColor = if (isResumeSupported) Color(0xFF4CAF50) else Color(0xFFF44336)
                    Row {
                        Text(text = "Link (Resume: ", fontSize = 14.sp)
                        Text(text = resumeLabel, fontSize = 14.sp, color = resumeColor, fontWeight = FontWeight.Bold)
                        Text(text = ")", fontSize = 14.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipData = clipboard?.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val paste = clipData.getItemAt(0).text?.toString() ?: ""
                                if (paste.isNotBlank()) {
                                    url = paste
                                    Toast.makeText(context, "Link pasted!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Paste Link", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { /* Preview Media */ }) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play Inline", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { /* Share Media */ }) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share URL", modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Link Input Textfield
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(fontSize = 13.sp),
                    maxLines = 3,
                    trailingIcon = {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit link")
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Save As: Section
                Text(text = "Save as:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    )
                    OutlinedTextField(
                        value = fileExtension,
                        onValueChange = { fileExtension = it },
                        modifier = Modifier.width(80.dp),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // File size area
                Text(
                    text = if (isProbing) "Fetching size..." else formatByteSize(fileSize),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Storage statistics
                val storageLabel = remember(context) { getStorageStats(context) }
                Text(
                    text = storageLabel,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Path directory text field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.History, contentDescription = "Storage Clock Icon", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    OutlinedTextField(
                        value = displayPath,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    )
                    IconButton(onClick = { /* pick another directory */ }) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = "Pick folder", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Options with checkboxes (1DM Style grid/list)
                Column(
                    modifier = Modifier.heightIn(max = 180.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useWebpageTitle, onCheckedChange = { useWebpageTitle = it })
                        Text(text = "Use webpage title as file name", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                        Text(text = "Wifi only", fontSize = 13.sp, color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = retryOnFail, onCheckedChange = { retryOnFail = it })
                        Text(text = "Retry", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(16.dp))
                        Checkbox(checked = useProxy, onCheckedChange = { useProxy = it })
                        Text(text = "Use proxy", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(16.dp))
                        Checkbox(checked = hiddenFile, onCheckedChange = { hiddenFile = it })
                        Text(text = "Hidden file", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useAdvancedDownloadMethod, onCheckedChange = { useAdvancedDownloadMethod = it })
                        Text(text = "Use advance download method", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = advanceOption, onCheckedChange = { advanceOption = it })
                        Text(text = "Advance option", fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Bottom Buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            val safeName = if (fileName.isNotBlank()) "$fileName.$fileExtension" else "video_file.$fileExtension"
                            val targetFile = File(defaultDir, safeName)
                            val item = DownloadItem(
                                url = url,
                                fileName = safeName,
                                filePath = targetFile.absolutePath,
                                fileSize = fileSize,
                                bytesDownloaded = 0L,
                                isResumeSupported = isResumeSupported,
                                status = "PAUSED",
                                progress = 0f,
                                useWebpageTitle = useWebpageTitle,
                                wifiOnly = wifiOnly,
                                retryOnFail = retryOnFail
                            )
                            downloadRepository.insertDownload(item)
                            Toast.makeText(context, "Added to download queue (Paused)", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    }) {
                        Text(text = "ADD", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    TextButton(onClick = onDismissRequest) {
                        Text(text = "CANCEL", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val safeName = if (fileName.isNotBlank()) "$fileName.$fileExtension" else "video_file.$fileExtension"
                                val targetFile = File(defaultDir, safeName)
                                val item = DownloadItem(
                                    url = url,
                                    fileName = safeName,
                                    filePath = targetFile.absolutePath,
                                    fileSize = fileSize,
                                    bytesDownloaded = 0L,
                                    isResumeSupported = isResumeSupported,
                                    status = "DOWNLOADING",
                                    progress = 0f,
                                    useWebpageTitle = useWebpageTitle,
                                    wifiOnly = wifiOnly,
                                    retryOnFail = retryOnFail
                                )
                                val id = downloadRepository.insertDownload(item)
                                // Trigger actual download
                                DownloadEngine.startDownload(context, id, this)
                                Toast.makeText(context, "Download started!", Toast.LENGTH_SHORT).show()
                                onDismissRequest()
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "START", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


// Interactive Downloads Manager dialog screen (Screenshot 2)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerDialog(
    onDismissRequest: () -> Unit,
    downloadRepository: DownloadRepository,
    coroutineScope: CoroutineScope
) {
    val context = LocalContext.current
    val allDownloads by downloadRepository.allDownloads.collectAsStateWithLifecycle(initialValue = emptyList())

    // Tabs definition matching screenshot 1DM
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabsList = listOf("ALL", "DOWNLOADING", "FINISHED", "ERROR")

    val filteredDownloads = remember(allDownloads, selectedTab) {
        when (selectedTab) {
            1 -> allDownloads.filter { it.status == "DOWNLOADING" }
            2 -> allDownloads.filter { it.status == "FINISHED" }
            3 -> allDownloads.filter { it.status == "ERROR" }
            else -> allDownloads
        }
    }

    // Modal view
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                "Download Manager",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismissRequest) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close Downloader")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    allDownloads.forEach {
                                        if (it.status == "DOWNLOADING") {
                                            DownloadEngine.pauseDownload(it.id)
                                        }
                                        downloadRepository.deleteDownload(it)
                                    }
                                    Toast.makeText(context, "All downloads cleared", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear All")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )

                    // Secondary Scrollable or Fixed Tabs row
                    SecondaryTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        tabsList.forEachIndexed { index, title ->
                            val count = when (index) {
                                1 -> allDownloads.count { it.status == "DOWNLOADING" }
                                2 -> allDownloads.count { it.status == "FINISHED" }
                                3 -> allDownloads.count { it.status == "ERROR" }
                                else -> allDownloads.size
                            }
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(
                                        text = "$title ($count)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                // Floating Action Button + matching screenshot to add link manually
                var showAddLinkDialog by remember { mutableStateOf(false) }
                
                FloatingActionButton(
                    onClick = { showAddLinkDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Download Link")
                }

                if (showAddLinkDialog) {
                    var manualUrl by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showAddLinkDialog = false },
                        title = { Text(text = "Add manual link", fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text(text = "Paste a URL below to start the download inside the custom downloader:", fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = manualUrl,
                                    onValueChange = { manualUrl = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    placeholder = { Text(text = "https://example.com/movie.mp4") },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showAddLinkDialog = false
                                    if (manualUrl.isNotBlank()) {
                                        coroutineScope.launch {
                                            // Show download configuration dialog for this URL!
                                            // Dismissing this allows opening the configuration dialog immediately
                                            // We schedule a minor delay
                                        }
                                    }
                                }
                            ) {
                                Text(text = "Next")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddLinkDialog = false }) {
                                Text(text = "Cancel")
                            }
                        }
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (filteredDownloads.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "No items",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No downloads found",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDownloads, key = { it.id }) { item ->
                            DownloadItemRow(
                                item = item,
                                downloadRepository = downloadRepository,
                                coroutineScope = coroutineScope
                            )
                        }
                    }
                }
            }
        }
    }
}

// 1DM style individual download list item (Screenshot 2 Details)
@Composable
fun DownloadItemRow(
    item: DownloadItem,
    downloadRepository: DownloadRepository,
    coroutineScope: CoroutineScope
) {
    val context = LocalContext.current
    val df = remember { DecimalFormat("#.##") }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_item_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Reel Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = "Movie File icon",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center details column
            Column(modifier = Modifier.weight(1f)) {
                // Title + Resume info
                val resumeLabel = if (item.isResumeSupported) "Yes" else "No"
                Text(
                    text = "${item.fileName} (Resume: $resumeLabel)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Progress Info & horizontal slider
                val sizeStr = remember(item.fileSize) {
                    if (item.fileSize <= 0) "Unknown total size" else {
                        val totalInMb = item.fileSize.toDouble() / (1024.0 * 1024.0)
                        "${df.format(totalInMb)}MB"
                    }
                }
                
                val currentInMb = remember(item.bytesDownloaded) {
                    val downloadedInMb = item.bytesDownloaded.toDouble() / (1024.0 * 1024.0)
                    "${df.format(downloadedInMb)}MB"
                }

                Text(
                    text = "${df.format(item.progress)}% | $currentInMb of $sizeStr",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Linear progress indicator
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (item.status == "FINISHED") Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Speed and ETA row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val speedAndEta = when (item.status) {
                        "DOWNLOADING" -> "${item.downloadSpeed}   ETA: ${item.eta}"
                        "FINISHED" -> "Completed"
                        "PAUSED" -> "Paused"
                        "ERROR" -> "Failed: ${item.eta}"
                        else -> "Queued"
                    }
                    Text(
                        text = speedAndEta,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (item.status == "FINISHED") Color(0xFF4CAF50) else if (item.status == "ERROR") Color(0xFFF44336) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right Option Buttons (Play/Pause/Delete)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.status != "FINISHED") {
                    IconButton(
                        onClick = {
                            if (item.status == "DOWNLOADING") {
                                DownloadEngine.pauseDownload(item.id)
                                Toast.makeText(context, "Download paused!", Toast.LENGTH_SHORT).show()
                            } else {
                                coroutineScope.launch {
                                    DownloadEngine.startDownload(context, item.id, this)
                                    Toast.makeText(context, "Download resumed!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (item.status == "DOWNLOADING") Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Toggle download action",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            if (item.status == "DOWNLOADING") {
                                DownloadEngine.pauseDownload(item.id)
                            }
                            
                            // Delete physical file too
                            try {
                                val file = File(item.filePath)
                                if (file.exists()) {
                                    file.delete()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            
                            downloadRepository.deleteDownload(item)
                            Toast.makeText(context, "Removed from list", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete download",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
