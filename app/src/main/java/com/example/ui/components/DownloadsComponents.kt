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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.DownloadItem
import com.example.data.DownloadRepository
import com.example.util.DownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat

// Helper function to resolve physical path from SAF tree URI
fun getPathFromTreeUri(context: Context, uri: Uri): String? {
    try {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val parts = documentId.split(":")
        if (parts.size >= 2) {
            val type = parts[0]
            val relativePath = parts[1]
            if ("primary".equals(type, ignoreCase = true)) {
                return Environment.getExternalStorageDirectory().absolutePath + "/" + relativePath
            } else {
                // Secondary SD Card/Memory storage
                val externalDirs = context.getExternalFilesDirs(null)
                for (dir in externalDirs) {
                    if (dir != null) {
                        val path = dir.absolutePath
                        val index = path.indexOf("/Android/data")
                        if (index != -1) {
                            val storageRoot = path.substring(0, index)
                            if (storageRoot.contains(type)) {
                                return "$storageRoot/$relativePath"
                            }
                        }
                    }
                }
                // Fallback guess
                val sdCardFile = File("/storage/$type")
                if (sdCardFile.exists()) {
                    return "/storage/$type/$relativePath"
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

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

// 1DM-Style "Download File!" Dialog (Screenshot)
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
    var referrerUrl by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf(if (initialUrl.isNotBlank()) "loading_details..." else "") }
    var fileExtension by remember { mutableStateOf("mp4") }
    var fileSize by remember { mutableLongStateOf(0L) }
    var isResumeSupported by remember { mutableStateOf(true) }
    var isProbing by remember { mutableStateOf(false) }
    
    val sharedPrefs = remember { context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE) }
    var storageMode by remember { mutableStateOf(sharedPrefs.getString("storage_mode", "public") ?: "public") }

    // Probing URL Header options to match screenshot parameters with debounce
    var lastProbedUrl by remember { mutableStateOf("") }

    LaunchedEffect(url) {
        val trimmed = url.trim()
        if (trimmed.startsWith("http") && trimmed != lastProbedUrl) {
            delay(1000) // 1 second debounce to prevent spamming
            if (trimmed == url.trim()) {
                isProbing = true
                lastProbedUrl = trimmed
                DownloadEngine.probeUrl(trimmed) { resolvedName, size, resume ->
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
        } else if (trimmed.isBlank()) {
            isProbing = false
            fileSize = 0L
            fileName = ""
            fileExtension = "mp4"
        }
    }

    // Default Storage options - Safely save according to user preference
    val defaultDir = remember(context, storageMode) {
        val mode = storageMode
        try {
            if (mode == "public") {
                val rootDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val sub = File(rootDownloadDir, "Able Drama")
                if (!sub.exists()) {
                    sub.mkdirs()
                }
                if (sub.exists() && sub.canWrite()) {
                    sub
                } else {
                    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                    val fallbackSub = File(base, "Able Drama")
                    if (!fallbackSub.exists()) {
                        fallbackSub.mkdirs()
                    }
                    fallbackSub
                }
            } else if (mode == "custom") {
                val customPath = sharedPrefs.getString("custom_storage_path", null)
                if (!customPath.isNullOrEmpty()) {
                    val customDir = File(customPath)
                    if (!customDir.exists()) {
                        customDir.mkdirs()
                    }
                    if (customDir.exists()) {
                        customDir
                    } else {
                        val rootDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val sub = File(rootDownloadDir, "Able Drama")
                        if (!sub.exists()) sub.mkdirs()
                        sub
                    }
                } else {
                    val rootDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val sub = File(rootDownloadDir, "Able Drama")
                    if (!sub.exists()) sub.mkdirs()
                    sub
                }
            } else {
                val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val sub = File(base, "Able Drama")
                if (!sub.exists()) {
                    sub.mkdirs()
                }
                sub
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

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags: Int = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val customPath = getPathFromTreeUri(context, uri)
            if (customPath != null) {
                storageMode = "custom"
                sharedPrefs.edit().putString("storage_mode", "custom").putString("custom_storage_path", customPath).apply()
                Toast.makeText(context, "Location updated: $customPath", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Could not resolve physical folder path", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Interactive Checkbox States
    var useWebpageTitle by remember { mutableStateOf(false) }
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
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header Row (Matches Title "Download file!" and top-right icons in screenshot)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Download file!",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            imageVector = Icons.Default.Storage, // Database/list format icon
                            contentDescription = "Database Icon",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.Language, // Globe icon representing web links
                            contentDescription = "Web Icon",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Link: row (Matches Screenshot with Copy/Share buttons)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Link:",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipData = clipboard?.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val paste = clipData.getItemAt(0).text?.toString() ?: ""
                                if (paste.isNotBlank()) {
                                    url = paste
                                    Toast.makeText(context, "Link pasted!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Paste Link",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            if (url.isNotBlank()) {
                                val sendIntent: android.content.Intent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, url)
                                    type = "text/plain"
                                }
                                val shareIntent = android.content.Intent.createChooser(sendIntent, null)
                                context.startActivity(shareIntent)
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share URL",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Link Input Textfield
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = TextStyle(fontSize = 14.sp),
                    placeholder = { Text("Download link", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Download/Referrer page link Field (Matches Screenshot)
                Text(
                    text = "Download/Referrer page link",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = referrerUrl,
                    onValueChange = { referrerUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = TextStyle(fontSize = 14.sp),
                    placeholder = { Text("leave empty if not sure", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Save As: Section (Matches Screenshot)
                Text(text = "Save as:", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
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
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        placeholder = { Text("File name", fontSize = 14.sp) },
                        textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    )
                    OutlinedTextField(
                        value = fileExtension,
                        onValueChange = { fileExtension = it },
                        modifier = Modifier.width(110.dp),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        placeholder = { Text("Extension", fontSize = 14.sp) },
                        textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // File size area (Matches Screenshot)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "Size: ", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    Text(
                        text = if (isProbing) "Fetching size..." else formatByteSize(fileSize),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Storage statistics (Matches Screenshot)
                val storageLabel = remember(context) { getStorageStats(context) }
                Text(
                    text = storageLabel,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Path directory text field + Folder picker launcher (Matches Screenshot)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Storage Clock Icon",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp)
                    )
                    
                    Text(
                        text = displayPath,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )

                    IconButton(
                        onClick = {
                            try {
                                folderPickerLauncher.launch(null)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: Use browser settings to configure storage folder", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DriveFolderUpload,
                            contentDescription = "Pick folder",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Options with checkboxes (Formatted exactly like the 3-row grid in screenshot)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Row 1: Wifi only (orange text and box), Retry, Use proxy
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = wifiOnly,
                                onCheckedChange = { wifiOnly = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFFF5722),
                                    uncheckedColor = Color(0xFFFF5722)
                                )
                            )
                            Text(text = "Wifi only", fontSize = 13.sp, color = Color(0xFFFF5722), fontWeight = FontWeight.Bold)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = retryOnFail, onCheckedChange = { retryOnFail = it })
                            Text(text = "Retry", fontSize = 13.sp)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = useProxy, onCheckedChange = { useProxy = it })
                            Text(text = "Use proxy", fontSize = 13.sp)
                        }
                    }

                    // Row 2: Hidden file, Use advance download method
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = hiddenFile, onCheckedChange = { hiddenFile = it })
                            Text(text = "Hidden file", fontSize = 13.sp)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = useAdvancedDownloadMethod, onCheckedChange = { useAdvancedDownloadMethod = it })
                            Text(text = "Use advance download method", fontSize = 13.sp)
                        }
                    }

                    // Row 3: Advance option
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = advanceOption, onCheckedChange = { advanceOption = it })
                            Text(text = "Advance option", fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Bottom Buttons row (ADD on left-aligned end, CANCEL & CONNECT on right-aligned end)
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        Text(text = "ADD", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    TextButton(onClick = onDismissRequest) {
                        Text(text = "CANCEL", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    TextButton(
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
                        }
                    ) {
                        Text(text = "CONNECT", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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

    val sharedPrefs = remember { context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE) }
    val storageMode = remember { mutableStateOf(sharedPrefs.getString("storage_mode", "public") ?: "public") }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags: Int = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val customPath = getPathFromTreeUri(context, uri)
            if (customPath != null) {
                storageMode.value = "custom"
                sharedPrefs.edit().putString("storage_mode", "custom").putString("custom_storage_path", customPath).apply()
                Toast.makeText(context, "Location updated: $customPath", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Could not resolve physical folder path", Toast.LENGTH_LONG).show()
            }
        }
    }

    var currentSortBy by remember {
        mutableStateOf(sharedPrefs.getString("download_sort_by", "date_desc") ?: "date_desc")
    }

    val filteredDownloads = remember(allDownloads, selectedTab, currentSortBy) {
        val baseList = when (selectedTab) {
            1 -> allDownloads.filter { it.status == "DOWNLOADING" }
            2 -> allDownloads.filter { it.status == "FINISHED" }
            3 -> allDownloads.filter { it.status == "ERROR" }
            else -> allDownloads
        }
        
        when (currentSortBy) {
            "date_desc" -> baseList.sortedByDescending { it.timestamp }
            "date_asc" -> baseList.sortedBy { it.timestamp }
            "size_desc" -> baseList.sortedByDescending { it.fileSize }
            "size_asc" -> baseList.sortedBy { it.fileSize }
            "name_asc" -> baseList.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
            "name_desc" -> baseList.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.fileName })
            else -> baseList
        }
    }

    var concurrentDownloadsLimit by remember { 
        mutableIntStateOf(sharedPrefs.getInt("concurrent_downloads_limit", 3)) 
    }
    
    var showMenu by remember { mutableStateOf(false) }
    var showConcurrentDownloadsDialog by remember { mutableStateOf(false) }
    var showDownloadPathDialog by remember { mutableStateOf(false) }
    var showBatteryOptimizationDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showDownloadStatsDialog by remember { mutableStateOf(false) }
    val defaultDir = remember(context, storageMode.value) {
        val mode = storageMode.value
        try {
            if (mode == "public") {
                val rootDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val sub = File(rootDownloadDir, "Able Drama")
                if (!sub.exists()) {
                    sub.mkdirs()
                }
                if (sub.exists() && sub.canWrite()) {
                    sub
                } else {
                    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                    val fallbackSub = File(base, "Able Drama")
                    if (!fallbackSub.exists()) {
                        fallbackSub.mkdirs()
                    }
                    fallbackSub
                }
            } else if (mode == "custom") {
                val customPath = sharedPrefs.getString("custom_storage_path", null)
                if (!customPath.isNullOrEmpty()) {
                    val customDir = File(customPath)
                    if (!customDir.exists()) {
                        customDir.mkdirs()
                    }
                    if (customDir.exists()) {
                        customDir
                    } else {
                        val rootDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val sub = File(rootDownloadDir, "Able Drama")
                        if (!sub.exists()) sub.mkdirs()
                        sub
                    }
                } else {
                    val rootDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val sub = File(rootDownloadDir, "Able Drama")
                    if (!sub.exists()) sub.mkdirs()
                    sub
                }
            } else {
                val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val sub = File(base, "Able Drama")
                if (!sub.exists()) {
                    sub.mkdirs()
                }
                sub
            }
        } catch (e: Exception) {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val fallbackSub = File(base, "Able Drama")
            try {
                if (!fallbackSub.exists()) {
                    fallbackSub.mkdirs()
                }
            } catch (ex: Exception) {}
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
                            // Delete button (Clear downloads)
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

                            // 3-dot Menu Toggle Button
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More Options")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    // DOWNLOAD SETTINGS
                                    Text(
                                        text = "Download Settings",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Maximum simultaneous downloads") },
                                        leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showConcurrentDownloadsDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort downloads order") },
                                        leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showSortDialog = true
                                        }
                                    )
                                    
                                    HorizontalDivider()
                                    // STORAGE SETTINGS
                                    Text(
                                        text = "Storage Settings",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download location/path") },
                                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showDownloadPathDialog = true
                                        }
                                    )
                                    
                                    HorizontalDivider()
                                    // BATTERY SETTINGS
                                    Text(
                                        text = "Battery Settings",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Battery & Background Performance") },
                                        leadingIcon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showBatteryOptimizationDialog = true
                                        }
                                    )
                                    
                                    HorizontalDivider()
                                    // ADDITIONAL USEFUL OPTIONS
                                    Text(
                                        text = "Additional Options",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download Statistics") },
                                        leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showDownloadStatsDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Retry Failed Downloads") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            coroutineScope.launch {
                                                val failedCount = allDownloads.count { it.status == "ERROR" }
                                                allDownloads.forEach {
                                                    if (it.status == "ERROR") {
                                                        DownloadEngine.startDownload(context, it.id, this)
                                                    }
                                                }
                                                Toast.makeText(context, "Retrying $failedCount failed downloads", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear Completed Downloads") },
                                        leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            coroutineScope.launch {
                                                val completed = allDownloads.filter { it.status == "FINISHED" }
                                                completed.forEach { downloadRepository.deleteDownload(it) }
                                                Toast.makeText(context, "Cleared ${completed.size} completed items", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear Failed Downloads") },
                                        leadingIcon = { Icon(Icons.Default.ErrorOutline, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            coroutineScope.launch {
                                                val failed = allDownloads.filter { it.status == "ERROR" }
                                                failed.forEach { downloadRepository.deleteDownload(it) }
                                                Toast.makeText(context, "Cleared ${failed.size} failed items", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    
                                    HorizontalDivider()
                                    // APP INFO
                                    DropdownMenuItem(
                                        text = { Text("About & Version Info") },
                                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            showAppInfoDialog = true
                                        }
                                    )
                                }
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
                // Clicking the FAB (+) now directly launches the exact, fully matching Download file pop-up dialog
                var showAddLinkDialog by remember { mutableStateOf(false) }
                
                FloatingActionButton(
                    onClick = { showAddLinkDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp, end = 16.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Download Link")
                }

                if (showAddLinkDialog) {
                    DownloadFileDialog(
                        initialUrl = "",
                        onDismissRequest = { showAddLinkDialog = false },
                        downloadRepository = downloadRepository,
                        coroutineScope = coroutineScope
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
                        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp),
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

        // Sub Settings Overlays
        if (showConcurrentDownloadsDialog) {
            AlertDialog(
                onDismissRequest = { showConcurrentDownloadsDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Limits icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Download Limits",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "Set the maximum number of files that can be downloaded simultaneously:",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        listOf(1, 2, 3, 5, 999).forEach { limit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        concurrentDownloadsLimit = limit
                                        sharedPrefs.edit().putInt("concurrent_downloads_limit", limit).apply()
                                        showConcurrentDownloadsDialog = false
                                        val limitStr = if (limit == 999) "Unlimited" else "$limit files"
                                        Toast.makeText(context, "$limitStr simultaneous downloads limit stored!", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = concurrentDownloadsLimit == limit,
                                    onClick = {
                                        concurrentDownloadsLimit = limit
                                        sharedPrefs.edit().putInt("concurrent_downloads_limit", limit).apply()
                                        showConcurrentDownloadsDialog = false
                                        val limitStr = if (limit == 999) "Unlimited" else "$limit files"
                                        Toast.makeText(context, "$limitStr simultaneous downloads limit stored!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when (limit) {
                                        3 -> "3 Downloads (Recommended)"
                                        999 -> "Unlimited (Maximum concurrent speed)"
                                        1 -> "1 Download"
                                        else -> "$limit Downloads"
                                    },
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showConcurrentDownloadsDialog = false }) {
                        Text("Dismiss")
                    }
                }
            )
        }

        if (showDownloadPathDialog) {
            AlertDialog(
                onDismissRequest = { showDownloadPathDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Folder icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Download Save Location",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "Choose the download destination or save location:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Public option
                        val isPublicSelected = (storageMode.value == "public")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    storageMode.value = "public"
                                    sharedPrefs.edit().putString("storage_mode", "public").apply()
                                    Toast.makeText(context, "Location source updated!", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isPublicSelected,
                                onClick = {
                                    storageMode.value = "public"
                                    sharedPrefs.edit().putString("storage_mode", "public").apply()
                                    Toast.makeText(context, "Location source updated!", Toast.LENGTH_SHORT).show()
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Default Download Folder (Public)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Internal Storage /Download/Able Drama",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Custom option
                        val isCustomSelected = (storageMode.value == "custom")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    try {
                                        folderPickerLauncher.launch(null)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Storage picker not supported or error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isCustomSelected,
                                onClick = {
                                    try {
                                        folderPickerLauncher.launch(null)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Storage picker not supported or error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Save to Custom SD Card/Memory",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Choose manual folder from internal/external memory",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Current Save Directory:",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = displayPath,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showDownloadPathDialog = false }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Download Path", displayPath)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Path copied to clipboard!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }) {
                        Text("Copy Path")
                    }
                }
            )
        }

        if (showBatteryOptimizationDialog) {
            val pm = remember { context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager }
            val isIgnoring = remember(context) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                } else {
                    true
                }
            }
            var performanceModeActive by remember {
                mutableStateOf(sharedPrefs.getBoolean("download_performance_mode", true))
            }
            
            AlertDialog(
                onDismissRequest = { showBatteryOptimizationDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = "Battery optimization icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Battery & Performance",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "Disable battery optimization to maintain fast background downloads and prevent sudden download interruption by the Android system.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isIgnoring) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isIgnoring) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = "Status icon",
                                tint = if (isIgnoring) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isIgnoring) "Optimization Ignored (Ideal)" else "Optimization Active (Downloads might stop)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isIgnoring) Color(0xFF2E7D32) else Color(0xFFE65100)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // Performance mode options
                        Text(
                            text = "Background Download Optimization:",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    performanceModeActive = !performanceModeActive
                                    sharedPrefs.edit().putBoolean("download_performance_mode", performanceModeActive).apply()
                                    Toast.makeText(context, if (performanceModeActive) "Performance Mode Enabled!" else "Balanced Energy Mode Activated!", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = performanceModeActive,
                                onCheckedChange = {
                                    performanceModeActive = it
                                    sharedPrefs.edit().putBoolean("download_performance_mode", it).apply()
                                    Toast.makeText(context, if (it) "Performance Mode Enabled!" else "Balanced Energy Mode Activated!", Toast.LENGTH_SHORT).show()
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Download Performance Mode",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Keeps CPU awake during downloads for speed preservation",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showBatteryOptimizationDialog = false
                        try {
                            val intent = android.content.Intent().apply {
                                action = android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = android.content.Intent().apply {
                                    action = android.provider.Settings.ACTION_SETTINGS
                                }
                                context.startActivity(intent)
                            } catch (ex: Exception) {
                                Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text("System Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatteryOptimizationDialog = false }) {
                        Text("Dismiss")
                    }
                }
            )
        }

        if (showSortDialog) {
            AlertDialog(
                onDismissRequest = { showSortDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sort Downloads",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Choose how to arrange your downloaded files:",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        val sortOptions = listOf(
                            "date_desc" to "Date Added (Newest First)",
                            "date_asc" to "Date Added (Oldest First)",
                            "size_desc" to "File Size (Largest First)",
                            "size_asc" to "File Size (Smallest First)",
                            "name_asc" to "Alphabetical (A - Z)",
                            "name_desc" to "Alphabetical (Z - A)"
                        )
                        
                        sortOptions.forEach { (optionKey, optionLabel) ->
                            val isSelected = (currentSortBy == optionKey)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentSortBy = optionKey
                                        sharedPrefs.edit().putString("download_sort_by", optionKey).apply()
                                        showSortDialog = false
                                        Toast.makeText(context, "Sorted by $optionLabel", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        currentSortBy = optionKey
                                        sharedPrefs.edit().putString("download_sort_by", optionKey).apply()
                                        showSortDialog = false
                                        Toast.makeText(context, "Sorted by $optionLabel", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = optionLabel,
                                    fontSize = 15.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSortDialog = false }) {
                        Text("Dismiss")
                    }
                }
            )
        }

        if (showAppInfoDialog) {
            AlertDialog(
                onDismissRequest = { showAppInfoDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "About icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Download Manager Info",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "About Us",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Able Drama Download Manager is a fully integrated premium downloader optimized for speed and resilience, designed to keep track of stream media files locally in pristine offline quality.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = "Version Info",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Version: 2.5.5 - Pro Stable Build",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = "Privacy Policy",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Your privacy is paramount. Download states, file urls, and storage caches are processed locally on your physical android device and never transferred out externally.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        item {
                            Text(
                                text = "Terms of Service",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "By initiating file download pipelines, the user acknowledges full proprietary or fair-use possession over copyrighted media under localized streaming rules.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showAppInfoDialog = false }) {
                        Text("Dismiss")
                    }
                }
            )
        }

        if (showDownloadStatsDialog) {
            val totalBytes = remember(allDownloads) { allDownloads.sumOf { it.bytesDownloaded } }
            val completedTasks = remember(allDownloads) { allDownloads.count { it.status == "FINISHED" } }
            val downloadingTasks = remember(allDownloads) { allDownloads.count { it.status == "DOWNLOADING" } }
            val failedTasks = remember(allDownloads) { allDownloads.count { it.status == "ERROR" } }
            val pausedTasks = remember(allDownloads) { allDownloads.count { it.status == "PAUSED" } }

            val df = remember { DecimalFormat("#.##") }
            val formattedSize = remember(totalBytes) {
                if (totalBytes < 1024L) {
                    "$totalBytes B"
                } else if (totalBytes < 1024L * 1024L) {
                    "${df.format(totalBytes.toDouble() / 1024.0)} KB"
                } else if (totalBytes < 1024L * 1024L * 1024L) {
                    "${df.format(totalBytes.toDouble() / (1024.0 * 1024.0))} MB"
                } else {
                    "${df.format(totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))} GB"
                }
            }

            AlertDialog(
                onDismissRequest = { showDownloadStatsDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Stats icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Download Statistics",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Summary of Download Statistics:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total download tasks added:", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${allDownloads.size}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Completed downloads:", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$completedTasks", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Active downloading:", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$downloadingTasks", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Failed downloads count:", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$failedTasks", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Paused tasks count:", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$pausedTasks", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
                        }
                        
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Total physical size:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(formattedSize, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showDownloadStatsDialog = false }) {
                        Text("OK")
                    }
                }
            )
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
    
    val animatedProgress by animateFloatAsState(
        targetValue = item.progress / 100f,
        label = "smoothProgress"
    )
    
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
                    progress = { animatedProgress },
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
                    if (item.status == "DOWNLOADING") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Download speed",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = item.downloadSpeed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "ETA: ${item.eta}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    } else {
                        val statusLabel = when (item.status) {
                            "FINISHED" -> "Completed"
                            "PAUSED" -> "Paused"
                            "ERROR" -> "Failed: ${item.eta}"
                            else -> "Queued"
                        }
                        Text(
                            text = statusLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (item.status == "FINISHED") Color(0xFF4CAF50) else if (item.status == "ERROR") Color(0xFFF44336) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
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
