package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import com.example.data.DownloadItem
import com.example.data.DownloadRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap

object DownloadEngine {
    private const val TAG = "DownloadEngine"
    val activeJobs = ConcurrentHashMap<Long, Job>()
    var repository: DownloadRepository? = null
    var appContext: Context? = null
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Global in-memory registry of newly detected media URLs mapped by filename signature
    val recentlyDetectedUrls = ConcurrentHashMap<String, String>()

    fun registerDetectedUrl(title: String, url: String) {
        val key = title.trim().lowercase()
        if (key.isNotEmpty()) {
            recentlyDetectedUrls[key] = url
        }
    }

    fun findFreshUrlForFile(fileName: String, originalUrl: String): String? {
        val cleanName = fileName.substringBeforeLast(".").trim().lowercase()
        if (cleanName.isBlank()) return null

        // 1. Check direct filename match in recently detected URLs
        for ((title, url) in recentlyDetectedUrls) {
            val cleanTitle = title.substringBeforeLast(".").trim().lowercase()
            if (cleanName.contains(cleanTitle) || cleanTitle.contains(cleanName)) {
                Log.d(TAG, "Dynamic URL match found in memory registry: $url")
                return url
            }
        }
        return null
    }

    private suspend fun tryReacquireFromReferrer(referrerUrl: String, fileName: String, fileExtension: String): String? {
        if (referrerUrl.isBlank()) return null
        
        try {
            Log.d(TAG, "Attempting to reacquire link by fetching referrer page: $referrerUrl")
            val url = URL(referrerUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.connect()
            
            if (conn.responseCode == 200) {
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                // Use regex to locate candidate media URLs matching current extension on page
                val regexHttp = "https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*\\.$fileExtension[-a-zA-Z0-9+&@#/%=~_|]*".toRegex()
                val foundUrls = regexHttp.findAll(html).map { it.value }.toList()
                Log.d(TAG, "Found ${foundUrls.size} candidate links with extension $fileExtension on referrer page")
                
                if (foundUrls.isNotEmpty()) {
                    val cleanFileName = fileName.substringBeforeLast(".").substringBefore("(").trim().lowercase()
                    if (cleanFileName.length >= 3) {
                        for (found in foundUrls) {
                            if (found.lowercase().contains(cleanFileName)) {
                                Log.d(TAG, "A perfect match found on page: $found")
                                return found
                            }
                        }
                    }
                    Log.d(TAG, "No perfect match; returning the first candidate: ${foundUrls[0]}")
                    return foundUrls[0]
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to reacquire from referrer: ${e.localizedMessage}")
        }
        return null
    }

    fun init(context: Context, repo: DownloadRepository) {
        appContext = context.applicationContext
        repository = repo
        
        ensureFolderStructure(context)
        
        // Download recovery system for validation, cleanup, and broken task recovery
        engineScope.launch {
            try {
                val stuckTasks = repo.getActiveDownloadsDirect()
                for (task in stuckTasks) {
                    repo.updateDownload(task.copy(
                        status = "PAUSED",
                        downloadSpeed = "Recovered",
                        eta = "Paused on startup"
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in startup download recovery system", e)
            }
        }

        // Auto-resume failed downloads upon network connectivity restoration
        engineScope.launch {
            var wasOffline = false
            try {
                NetworkMonitor(context.applicationContext).isOnline.collect { isOnline ->
                    if (isOnline) {
                        if (wasOffline) {
                            val prefs = context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE)
                            val autoResumeEnabled = prefs.getBoolean("auto_resume_failed", true)
                            if (autoResumeEnabled) {
                                delay(3000L) // Wait a brief moment for the connection to fully stabilize
                                val allTasks = repo.allDownloads.first()
                                val failedTasks = allTasks.filter { it.status == "ERROR" }
                                for (task in failedTasks) {
                                    Log.d(TAG, "Auto-resuming failed download task: ${task.fileName}")
                                    startDownload(context, task.id)
                                }
                            }
                        }
                        wasOffline = false
                    } else {
                        wasOffline = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in auto-resume network monitor loop", e)
            }
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun startDownload(context: Context, itemId: Long, scope: CoroutineScope? = null) {
        if (activeJobs.containsKey(itemId)) return // already running

        // Cancel previous notifications for this itemId if any
        DownloadForegroundService.cancelDownloadNotification(context, itemId)

        // Ask for battery optimization exemption once directly if not already whitelisted
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val prefs = context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE)
                val prompted = prefs.getBoolean("battery_prompted_once", false)
                if (!prompted) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        prefs.edit().putBoolean("battery_prompted_once", true).apply()
                    } catch (e: Exception) {
                        Log.e(TAG, "Direct ignore battery optimization prompt was blocked or unhandled", e)
                    }
                }
            }
        }

        // Start the Foreground service
        try {
            val serviceIntent = android.content.Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_START
                putExtra(DownloadForegroundService.EXTRA_ITEM_ID, itemId)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting DownloadForegroundService", e)
        }

        // Enforce maximum concurrent downloads limit set in Settings
        val sharedPrefs = context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE)
        val limit = sharedPrefs.getInt("concurrent_downloads_limit", 3)
        if (limit != 999 && activeJobs.size >= limit) {
            val repo = repository
            if (repo != null) {
                engineScope.launch {
                    val item = repo.getDownloadById(itemId)
                    if (item != null) {
                        repo.updateDownload(item.copy(
                            status = "PAUSED",
                            downloadSpeed = "Queued",
                            eta = "Simultaneous limit reached"
                        ))
                    }
                }
            }
            try {
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(context, "Download limit reached ($limit max allowed concurrently).", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {}
            return
        }

        val job = engineScope.launch(Dispatchers.IO) {
            val repo = repository ?: return@launch
            var item = repo.getDownloadById(itemId) ?: return@launch

            // Automatic Folder Categorization & Duplicate File Handling Preprocessing
            val prefs = context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE)
            val autoCategorize = prefs.getBoolean("auto_categorize_downloads", true)
            
            var currentFile = File(item.filePath)
            var currentDir = currentFile.parentFile ?: android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            var currentName = item.fileName
            
            if (autoCategorize) {
                val ext = currentName.substringAfterLast(".", "").lowercase()
                val category = when (ext) {
                    "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "3gp", "ts", "m3u8" -> "Videos"
                    "mp3", "aac", "wav", "m4a", "flac", "ogg", "wma", "opus" -> "Audio"
                    "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt", "rtf", "epub", "csv" -> "Documents"
                    "apk", "xapk", "apks" -> "APK"
                    "jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "svg" -> "Images"
                    "zip", "rar", "7z", "tar", "gz", "bz2" -> "Archives"
                    else -> "Others"
                }
                val rootDownloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val ableDramaFolder = File(rootDownloadDir, "AbleDrama")
                if (!ableDramaFolder.exists()) ableDramaFolder.mkdirs()
                val targetSubFolder = File(ableDramaFolder, category)
                if (!targetSubFolder.exists()) targetSubFolder.mkdirs()
                
                currentDir = targetSubFolder
            }
            
            val duplicateRule = prefs.getString("duplicate_file_handling", "Rename") ?: "Rename"
            var finalFile = File(currentDir, currentName)
            
            if (finalFile.exists()) {
                when (duplicateRule) {
                    "Rename" -> {
                        val baseName = currentName.substringBeforeLast(".")
                        val ext = currentName.substringAfterLast(".", "")
                        var counter = 1
                        var testName = if (ext.isNotEmpty()) "$baseName($counter).$ext" else "$baseName($counter)"
                        var testFile = File(currentDir, testName)
                        while (testFile.exists()) {
                            counter++
                            testName = if (ext.isNotEmpty()) "$baseName($counter).$ext" else "$baseName($counter)"
                            testFile = File(currentDir, testName)
                        }
                        currentName = testName
                        finalFile = testFile
                    }
                    "Replace", "Replace Existing File" -> {
                        try {
                            if (finalFile.exists()) {
                                finalFile.delete()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting existing file for replace rule", e)
                        }
                    }
                    "Skip", "Skip Download" -> {
                        val fileSize = if (item.fileSize > 0) item.fileSize else finalFile.length()
                        repo.updateDownload(item.copy(
                            status = "FINISHED",
                            bytesDownloaded = fileSize,
                            progress = 100f,
                            downloadSpeed = "Skipped",
                            eta = "Completed"
                        ))
                        try {
                            CoroutineScope(Dispatchers.Main).launch {
                                Toast.makeText(context, "Download skipped: $currentName already exists!", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {}
                        return@launch
                    }
                }
            }
            
            if (finalFile.absolutePath != item.filePath) {
                item = item.copy(
                    filePath = finalFile.absolutePath,
                    fileName = currentName
                )
                repo.updateDownload(item)
            }

            var urlSpec = item.url

            var consecutiveErrors = 0
            val maxConsecutiveErrors = 5
            val baseDelayMs = 2000L

            try {
                while (isActive) {
                    try {
                        // Update status indicating connecting attempts
                        val statusText = if (consecutiveErrors == 0) "Connecting..." else "Reconnecting..."
                        repo.updateDownload(item.copy(
                            status = "DOWNLOADING",
                            downloadSpeed = statusText,
                            eta = if (consecutiveErrors == 0) "Validating..." else "Attempt ${consecutiveErrors + 1}/$maxConsecutiveErrors"
                        ))

                        val outputFile = File(item.filePath)
                        val destinationDir = outputFile.parentFile
                        if (destinationDir != null && !destinationDir.exists()) {
                            destinationDir.mkdirs()
                        }

                        // Attempt link probe connection
                        var probeUrl = URL(urlSpec)
                        var probeConn = probeUrl.openConnection() as HttpURLConnection
                        probeConn.requestMethod = "GET"
                        probeConn.connectTimeout = 8000
                        probeConn.readTimeout = 8000
                        probeConn.setRequestProperty("Range", "bytes=0-0")
                        if (item.cookies.isNotBlank()) {
                            probeConn.setRequestProperty("Cookie", item.cookies)
                        }
                        probeConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

                        var responseCode = -1
                        try {
                            probeConn.connect()
                            responseCode = probeConn.responseCode
                        } catch (e: Exception) {
                            Log.w(TAG, "Connection probe caught Exception: ${e.localizedMessage}")
                        }

                        // Expired token/web page invalidation check (HTTP 401, 403, 404, 410, or unresolvable connection)
                        if (responseCode == 401 || responseCode == 403 || responseCode == 404 || responseCode == 410 || responseCode == -1) {
                            Log.d(TAG, "Probe returned invalid HTTP code ($responseCode). Activating resume recovery system...")

                            val fileExtension = item.fileName.substringAfterLast(".", "mp4")
                            var refreshedUrl = findFreshUrlForFile(item.fileName, item.url)

                            if (refreshedUrl == null && item.referrerUrl.isNotBlank()) {
                                refreshedUrl = tryReacquireFromReferrer(item.referrerUrl, item.fileName, fileExtension)
                            }

                            if (refreshedUrl != null && refreshedUrl != urlSpec) {
                                Log.d(TAG, "Successfully reacquired new URL: $refreshedUrl")
                                urlSpec = refreshedUrl
                                item = item.copy(url = refreshedUrl)
                                repo.updateDownload(item)
                                
                                // Reconnect immediately with updated URL spec
                                consecutiveErrors = 0
                                continue
                            } else if (responseCode == 403 || responseCode == 404 || responseCode == 410) {
                                // Definitive expiration / file removed
                                val diedMsg = "Download source is no longer available."
                                val failedItem = item.copy(
                                    status = "ERROR",
                                    downloadSpeed = "Expired",
                                    eta = diedMsg
                                )
                                repo.updateDownload(failedItem)
                                appContext?.let { DownloadForegroundService.showFailedNotification(it, failedItem) }
                                return@launch
                            }
                        }

                        // Proceeding since probe connection succeeded
                        val acceptsRanges = (responseCode == HttpURLConnection.HTTP_PARTIAL) || 
                                            (probeConn.getHeaderField("Accept-Ranges") == "bytes")
                        
                        var totalLength = probeConn.contentLength.toLong()
                        if (totalLength <= 0) {
                            totalLength = item.fileSize
                        }

                        if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                            val rangeHeader = probeConn.getHeaderField("Content-Range")
                            if (rangeHeader != null) {
                                try {
                                    totalLength = rangeHeader.substringAfterLast("/").toLong()
                                } catch (e: Exception) {}
                            }
                        }
                        probeConn.disconnect()

                        // Synchronize and persist download metadata
                        if (item.fileSize <= 0 && totalLength > 0) {
                            item = item.copy(fileSize = totalLength)
                            repo.updateDownload(item)
                        } else if (totalLength <= 0) {
                            totalLength = item.fileSize
                        }

                        val canUseMultiPart = acceptsRanges && totalLength > 1024 * 1024 && item.isResumeSupported // > 1MB

                        if (canUseMultiPart) {
                            downloadMultiPart(context, repo, item, totalLength)
                        } else {
                            downloadSingleThread(context, repo, item, totalLength)
                        }
                        break // Succeeded/launched, exit starting validation retry loop

                    } catch (e: Exception) {
                        consecutiveErrors++
                        Log.w(TAG, "Start connection failed (Error count: $consecutiveErrors): ${e.localizedMessage}")

                        if (e is java.io.FileNotFoundException) {
                            val failedItem = item.copy(
                                status = "ERROR",
                                downloadSpeed = "Expired",
                                eta = "Download source is no longer available."
                            )
                            repo.updateDownload(failedItem)
                            appContext?.let { DownloadForegroundService.showFailedNotification(it, failedItem) }
                            break
                        }

                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            val failedItem = item.copy(
                                status = "ERROR",
                                downloadSpeed = "Failed",
                                eta = e.localizedMessage ?: "Connecting error"
                            )
                            repo.updateDownload(failedItem)
                            appContext?.let { DownloadForegroundService.showFailedNotification(it, failedItem) }
                            break
                        }

                        val delayMs = (baseDelayMs * (1 shl (consecutiveErrors - 1))).coerceAtMost(30000L)
                        repo.updateDownload(item.copy(
                            downloadSpeed = "Retrying...",
                            eta = "Reconnect in ${delayMs / 1000}s"
                        ))
                        delay(delayMs)
                    }
                }
            } finally {
                activeJobs.remove(itemId)
                appContext?.let { triggerNextDownload(it) }
            }
        }

        activeJobs[itemId] = job
    }

    private suspend fun downloadMultiPart(
        context: Context,
        repo: DownloadRepository,
        initialItem: DownloadItem,
        totalLength: Long
    ) {
        var item = initialItem
        val sharedPrefs = context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE)
        
        // Backward-compatible thread count detector:
        var numParts = sharedPrefs.getInt("download_threads_limit", 4)
        var existingPartsCount = 0
        for (idx in 0..100) {
            if (File(item.filePath + ".part$idx").exists()) {
                existingPartsCount = idx + 1
            }
        }
        if (existingPartsCount > 0) {
            numParts = existingPartsCount
        }

        val partSize = totalLength / numParts
        
        val partBytesDownloaded = LongArray(numParts)
        val partFiles = Array(numParts) { i -> File(item.filePath + ".part$i") }

        // Load size offset of existing `.part` files
        for (i in 0 until numParts) {
            val partFile = partFiles[i]
            if (partFile.exists()) {
                partBytesDownloaded[i] = partFile.length()
            } else {
                partBytesDownloaded[i] = 0L
            }
        }

        var lastUpdateTime = System.currentTimeMillis()
        var downloadedSinceLastUpdate = 0L
        var totalDownloaded = partBytesDownloaded.sum()
        val tickerMutex = Any()

        coroutineScope {
            val jobs = List(numParts) { i ->
                val startByte = i * partSize
                val endByte = if (i == numParts - 1) totalLength - 1 else (i + 1) * partSize - 1
                
                async(Dispatchers.IO) {
                    var retriesLeft = 15
                    var partSuccess = false

                    while (retriesLeft > 0 && !partSuccess && isActive) {
                        var connection: HttpURLConnection? = null
                        var inputStream: InputStream? = null
                        var raf: RandomAccessFile? = null
                        try {
                            // Wait for network connectivity if offline
                            while (!isNetworkAvailable(context) && isActive) {
                                delay(3000L)
                            }

                            val currentPartDownloaded = partFiles[i].length()
                            val requestStart = startByte + currentPartDownloaded
                            
                            if (requestStart > endByte) {
                                partSuccess = true
                                break
                            }

                            val currentItem = repo.getDownloadById(item.id)
                            val currentUrl = currentItem?.url ?: item.url
                            val url = URL(currentUrl)
                            connection = url.openConnection() as HttpURLConnection
                            connection.connectTimeout = 15000
                            connection.readTimeout = 15000
                            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            val userCookies = currentItem?.cookies ?: item.cookies
                            if (userCookies.isNotBlank()) {
                                connection.setRequestProperty("Cookie", userCookies)
                            }
                            connection.setRequestProperty("Range", "bytes=$requestStart-$endByte")
                            connection.connect()

                            val responseCode = connection.responseCode
                            if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                                throw Exception("Sever responded with $responseCode")
                            }

                            inputStream = connection.inputStream
                            raf = RandomAccessFile(partFiles[i], "rw")
                            raf.seek(currentPartDownloaded)

                            // 32KB buffer size for optimized network responses (prevents context switching & limits bottlenecking)
                            val buffer = ByteArray(32768)
                            var bytesRead: Int
                            
                            // Speed Limit Throttling state per worker thread
                            val limitBps = getSpeedLimitBytesPerSecond(context)
                            val threadLimitBps = if (limitBps < Long.MAX_VALUE) limitBps / numParts else Long.MAX_VALUE
                            var speedWindowStart = System.currentTimeMillis()
                            var speedWindowBytes = 0L

                            while (isActive) {
                                // Rate limiting throttling
                                if (threadLimitBps < Long.MAX_VALUE) {
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - speedWindowStart
                                    if (elapsed >= 1000) {
                                        speedWindowStart = now
                                        speedWindowBytes = 0L
                                    } else {
                                        val maxAllowedBytes = (threadLimitBps * elapsed) / 1000L
                                        if (speedWindowBytes > maxAllowedBytes) {
                                            val sleepTime = ((speedWindowBytes * 1000L) / threadLimitBps) - elapsed
                                            if (sleepTime > 0) {
                                                delay(sleepTime)
                                            }
                                        }
                                    }
                                }

                                bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break

                                raf.write(buffer, 0, bytesRead)
                                speedWindowBytes += bytesRead
                                
                                synchronized(tickerMutex) {
                                    totalDownloaded += bytesRead
                                    downloadedSinceLastUpdate += bytesRead
                                }
                            }
                            
                            partSuccess = true
                        } catch (e: Exception) {
                            retriesLeft--
                            Log.w(TAG, "Chunk $i failed (${15 - retriesLeft}/15 retries): ${e.localizedMessage}")
                            if (retriesLeft > 0 && isActive) {
                                delay(2000)
                            }
                        } finally {
                            try { raf?.close() } catch (e: Exception) {}
                            try { inputStream?.close() } catch (e: Exception) {}
                            try { connection?.disconnect() } catch (e: Exception) {}
                        }
                    }
                    partSuccess
                }
            }

            // Real-time UI and speed updates monitoring coroutine
            val monitorJob = launch {
                while (isActive) {
                    delay(1000)
                    val currentTime = System.currentTimeMillis()
                    val timeDiff = currentTime - lastUpdateTime
                    if (timeDiff > 0) {
                        var speedText = ""
                        var progressPercent = 0f
                        var etaText = "--"

                        synchronized(tickerMutex) {
                            val speedBytesPerSec = (downloadedSinceLastUpdate * 1000) / timeDiff
                            speedText = formatSpeed(speedBytesPerSec)
                            
                            progressPercent = if (totalLength > 0) {
                                (totalDownloaded.toFloat() / totalLength.toFloat()) * 100f
                            } else {
                                0f
                            }

                            val remainingBytes = totalLength - totalDownloaded
                            etaText = if (speedBytesPerSec > 0 && remainingBytes > 0) {
                                formatEta(remainingBytes / speedBytesPerSec)
                            } else {
                                "--"
                            }
                            downloadedSinceLastUpdate = 0L
                        }

                        lastUpdateTime = currentTime

                        val currentItem = repo.getDownloadById(item.id)
                        if (currentItem != null && currentItem.status == "DOWNLOADING") {
                            item = currentItem.copy(
                                bytesDownloaded = totalDownloaded,
                                progress = progressPercent,
                                downloadSpeed = speedText,
                                eta = etaText
                            )
                            repo.updateDownload(item)
                        }
                    }
                }
            }

            // Wait for all download chunk streams to complete
            val results = jobs.awaitAll()
            monitorJob.cancel()

            val allPartsSucceeded = results.all { it }
            if (allPartsSucceeded && isActive) {
                // Update file status list cards to Merging
                val currentItem = repo.getDownloadById(item.id)
                if (currentItem != null) {
                    repo.updateDownload(currentItem.copy(
                        downloadSpeed = "Merging...",
                        eta = "Processing"
                    ))
                }

                val destFile = File(item.filePath)
                if (destFile.exists()) {
                    destFile.delete()
                }
                
                // Super fast buffered NIO stream merge (using 128KB buffer chunks)
                val buffer = ByteArray(131072)
                destFile.outputStream().buffered(262144).use { out ->
                    for (i in 0 until numParts) {
                        val partFile = partFiles[i]
                        if (partFile.exists()) {
                            partFile.inputStream().buffered(262144).use { input ->
                                var bytesRead: Int
                                while (true) {
                                    bytesRead = input.read(buffer)
                                    if (bytesRead == -1) break
                                    out.write(buffer, 0, bytesRead)
                                }
                            }
                            partFile.delete()
                        }
                    }
                }

                // Complete Download!
                val finalItemState = repo.getDownloadById(item.id)
                if (finalItemState != null) {
                    val completedItem = finalItemState.copy(
                        bytesDownloaded = totalLength,
                        progress = 100f,
                        status = "FINISHED",
                        downloadSpeed = "Completed",
                        eta = "--"
                    )
                    repo.updateDownload(completedItem)
                    appContext?.let { DownloadForegroundService.showCompletedNotification(it, completedItem) }
                }
                appContext?.let { triggerNextDownload(it) }
            } else {
                throw Exception("Threads cancelled or connection failed.")
            }
        }
    }

    private suspend fun CoroutineScope.downloadSingleThread(
        context: Context,
        repo: DownloadRepository,
        initialItem: DownloadItem,
        totalLength: Long
    ) {
        var item = initialItem
        val outputFile = File(item.filePath)
        val destinationDir = outputFile.parentFile
        if (destinationDir != null && !destinationDir.exists()) {
            destinationDir.mkdirs()
        }

        var totalDownloaded = if (outputFile.exists() && item.isResumeSupported) outputFile.length() else 0L
        if (!item.isResumeSupported) {
            totalDownloaded = 0L
            if (outputFile.exists()) {
                outputFile.delete()
            }
        }

        var lastUpdateTime = System.currentTimeMillis()
        var downloadedSinceLastUpdate = 0L
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 15 // Retry up to 15 times before failing

        while (isActive && (totalLength <= 0 || totalDownloaded < totalLength)) {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var raf: RandomAccessFile? = null

            try {
                // Wait for network connectivity if offline
                while (!isNetworkAvailable(context) && isActive) {
                    val currentItem = repo.getDownloadById(item.id)
                    if (currentItem == null || currentItem.status != "DOWNLOADING") return
                    repo.updateDownload(currentItem.copy(
                        downloadSpeed = "Offline",
                        eta = "Waiting for connection..."
                    ))
                    delay(3000L)
                }

                val currentItem = repo.getDownloadById(item.id)
                val currentUrl = currentItem?.url ?: item.url
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                val userCookies = currentItem?.cookies ?: item.cookies
                if (userCookies.isNotBlank()) {
                    connection.setRequestProperty("Cookie", userCookies)
                }

                if (totalDownloaded > 0) {
                    connection.setRequestProperty("Range", "bytes=$totalDownloaded-")
                }

                connection.connect()

                val responseCode = connection.responseCode
                val isPartial = (responseCode == HttpURLConnection.HTTP_PARTIAL)
                val isOk = (responseCode == HttpURLConnection.HTTP_OK)

                if (!isPartial && !isOk) {
                    throw Exception("Server responded with code $responseCode")
                }

                inputStream = connection.inputStream
                raf = RandomAccessFile(outputFile, "rw")
                if (isPartial) {
                    raf.seek(totalDownloaded)
                } else {
                    if (totalDownloaded > 0) {
                        totalDownloaded = 0L
                    }
                    raf.setLength(0)
                }

                consecutiveErrors = 0 // Reset error counter on successful connect/read

                // 64KB buffer size for high-speed single-connection downloads
                val buffer = ByteArray(65536)
                var bytesRead: Int

                // Speed Limit Throttling state for single thread
                val limitBps = getSpeedLimitBytesPerSecond(context)
                var speedWindowStart = System.currentTimeMillis()
                var speedWindowBytes = 0L

                while (isActive) {
                    // Rate limiting throttling
                    if (limitBps < Long.MAX_VALUE) {
                        val now = System.currentTimeMillis()
                        val elapsed = now - speedWindowStart
                        if (elapsed >= 1000) {
                            speedWindowStart = now
                            speedWindowBytes = 0L
                        } else {
                            val maxAllowedBytes = (limitBps * elapsed) / 1000L
                            if (speedWindowBytes > maxAllowedBytes) {
                                val sleepTime = ((speedWindowBytes * 1000L) / limitBps) - elapsed
                                if (sleepTime > 0) {
                                    delay(sleepTime)
                                }
                            }
                        }
                    }

                    try {
                        bytesRead = inputStream.read(buffer)
                    } catch (e: Exception) {
                        throw e
                    }
                    if (bytesRead == -1) break

                    raf.write(buffer, 0, bytesRead)
                    speedWindowBytes += bytesRead
                    totalDownloaded += bytesRead
                    downloadedSinceLastUpdate += bytesRead

                    val currentTime = System.currentTimeMillis()
                    val timeDiff = currentTime - lastUpdateTime
                    if (timeDiff >= 1000) {
                        val speedBytesPerSec = (downloadedSinceLastUpdate * 1000) / timeDiff
                        val speedText = formatSpeed(speedBytesPerSec)
                        
                        val progressPercent = if (totalLength > 0) {
                            (totalDownloaded.toFloat() / totalLength.toFloat()) * 100f
                        } else {
                            0f
                        }

                        val remainingBytes = totalLength - totalDownloaded
                        val etaText = if (speedBytesPerSec > 0 && remainingBytes > 0) {
                            formatEta(remainingBytes / speedBytesPerSec)
                        } else {
                            "--"
                        }

                        val currentItem = repo.getDownloadById(item.id)
                        if (currentItem == null || currentItem.status != "DOWNLOADING") {
                            // Cancelled or paused
                            return
                        }
                        item = currentItem.copy(
                            bytesDownloaded = totalDownloaded,
                            progress = progressPercent,
                            downloadSpeed = speedText,
                            eta = etaText
                        )
                        repo.updateDownload(item)

                        downloadedSinceLastUpdate = 0L
                        lastUpdateTime = currentTime
                    }
                }

                if (totalDownloaded >= totalLength || totalLength <= 0) {
                    break
                }
            } catch (e: Exception) {
                consecutiveErrors++
                Log.w(TAG, "Download error in downloadSingleThread (attempt $consecutiveErrors): ${e.localizedMessage}")
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    throw e
                }
                val waitTime = (consecutiveErrors * 2000L).coerceAtMost(15000L)
                val currentItem = repo.getDownloadById(item.id)
                if (currentItem == null || currentItem.status != "DOWNLOADING") return
                repo.updateDownload(currentItem.copy(
                    downloadSpeed = "Connecting...",
                    eta = "Retry $consecutiveErrors/$maxConsecutiveErrors"
                ))
                delay(waitTime)
            } finally {
                try { raf?.close() } catch (e: Exception) {}
                try { inputStream?.close() } catch (e: Exception) {}
                try { connection?.disconnect() } catch (e: Exception) {}
            }
        }

        if (isActive) {
            val finalItemState = repo.getDownloadById(item.id)
            if (finalItemState != null && finalItemState.status == "DOWNLOADING") {
                val completedItem = finalItemState.copy(
                    bytesDownloaded = totalDownloaded,
                    progress = 100f,
                    status = "FINISHED",
                    downloadSpeed = "Completed",
                    eta = "--"
                )
                repo.updateDownload(completedItem)
                appContext?.let { DownloadForegroundService.showCompletedNotification(it, completedItem) }
            }
            appContext?.let { triggerNextDownload(it) }
        }
    }

    fun pauseDownload(itemId: Long) {
        val job = activeJobs.remove(itemId)
        if (job != null) {
            job.cancel()
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            val repo = repository ?: return@launch
            val item = repo.getDownloadById(itemId)
            if (item != null) {
                val pausedItem = item.copy(
                    status = "PAUSED",
                    downloadSpeed = "Paused",
                    eta = "--"
                )
                repo.updateDownload(pausedItem)
                appContext?.let { DownloadForegroundService.showPausedNotification(it, pausedItem) }
            }
            appContext?.let { triggerNextDownload(it) }
        }
    }

    fun cancelDownload(itemId: Long) {
        val job = activeJobs.remove(itemId)
        if (job != null) {
            job.cancel()
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            val repo = repository ?: return@launch
            val item = repo.getDownloadById(itemId)
            if (item != null) {
                val canceledItem = item.copy(
                    status = "PAUSED",
                    progress = 0f,
                    bytesDownloaded = 0L,
                    downloadSpeed = "Canceled",
                    eta = "--"
                )
                repo.updateDownload(canceledItem)
                
                // Free OS memory logs and remove physical payload fragments to avoid visual corrupted resuming
                try {
                    val destFile = File(item.filePath)
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    val numParts = 4
                    for (i in 0 until numParts) {
                        val partFile = File(item.filePath + ".part$i")
                        if (partFile.exists()) {
                            partFile.delete()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed cleaning local storage for download item cancellation", e)
                }
                
                // Clear state notifications immediately
                appContext?.let { DownloadForegroundService.cancelDownloadNotification(it, itemId) }
            }
            appContext?.let { triggerNextDownload(it) }
        }
    }

    fun triggerNextDownload(context: Context) {
        val repo = repository ?: return
        val sharedPrefs = context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE)
        val limit = sharedPrefs.getInt("concurrent_downloads_limit", 3)
        
        if (limit != 999 && activeJobs.size >= limit) return
        
        engineScope.launch {
            try {
                val dbItems = repo.allDownloads.first()
                val queuedItem = dbItems.firstOrNull { it.status == "PAUSED" && it.eta == "Simultaneous limit reached" }
                if (queuedItem != null) {
                    withContext(Dispatchers.Main) {
                        startDownload(context.applicationContext, queuedItem.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering next download in queue", e)
            }
        }
    }

    fun isDownloading(itemId: Long): Boolean {
        return activeJobs.containsKey(itemId)
    }

    fun probeUrl(urlSpec: String, onCompleted: (fileName: String, fileSize: Long, isResumeSupported: Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(urlSpec)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                
                val contentDisposition = conn.getHeaderField("Content-Disposition")
                // Most modern streaming file hosters support byte ranges, even if Accept-Ranges is omitted.
                // We default to true to ensure pausing and resuming works smoothly across the board.
                val isResumeSupported = true
                var size = conn.contentLength.toLong()
                if (size < 0) {
                    val contentRange = conn.getHeaderField("Content-Range")
                    if (contentRange != null) {
                        try {
                            size = contentRange.substringAfterLast("/").toLong()
                        } catch (e: Exception) {}
                    }
                }
                
                var resolvedName = ""
                if (!contentDisposition.isNullOrBlank()) {
                    if (contentDisposition.contains("filename=", ignoreCase = true)) {
                        resolvedName = contentDisposition.substringAfter("filename=")
                            .trim().removeSurrounding("\"").removeSurrounding("'")
                    }
                }
                if (resolvedName.isBlank()) {
                    resolvedName = urlSpec.substringAfterLast("/").substringBefore("?").trim()
                }
                if (resolvedName.isBlank()) {
                    resolvedName = "video_file.mp4"
                }

                conn.disconnect()
                withContext(Dispatchers.Main) {
                    onCompleted(resolvedName, size, isResumeSupported)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val defaultName = urlSpec.substringAfterLast("/").substringBefore("?").trim().ifBlank { "download.mp4" }
                withContext(Dispatchers.Main) {
                    onCompleted(defaultName, 0L, true)
                }
            }
        }
    }

    private fun getSpeedLimitBytesPerSecond(context: Context): Long {
        val prefs = context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE)
        val option = prefs.getString("download_speed_limit_option", "Unlimited") ?: "Unlimited"
        if (option == "Unlimited") return Long.MAX_VALUE
        
        val kbLimit = when (option) {
            "100KB/s" -> 100
            "500KB/s" -> 500
            "1MB/s" -> 1024
            "5MB/s" -> 5 * 1024
            "10MB/s" -> 10 * 1024
            "Custom" -> prefs.getInt("download_speed_limit_custom_kb", 1000)
            else -> return Long.MAX_VALUE
        }
        return kbLimit.toLong() * 1024L
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            bytesPerSec >= 1024 * 1024 -> "${df.format(bytesPerSec.toDouble() / (1024 * 1024))} MB/s"
            bytesPerSec >= 1024 -> "${df.format(bytesPerSec.toDouble() / 1024)} KB/s"
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    fun ensureFolderStructure(context: Context) {
        try {
            val rootDownloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val ableDramaFolder = File(rootDownloadDir, "AbleDrama")
            if (!ableDramaFolder.exists()) {
                ableDramaFolder.mkdirs()
            }
            val subFolders = listOf("Videos", "Audio", "Documents", "APK", "Images", "Archives", "Others")
            for (sub in subFolders) {
                val f = File(ableDramaFolder, sub)
                if (!f.exists()) {
                    f.mkdirs()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing folders", e)
        }
    }
}
