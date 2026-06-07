package com.example.util

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.data.DownloadItem
import com.example.data.DownloadRepository
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap

object DownloadEngine {
    private const val TAG = "DownloadEngine"
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private var repository: DownloadRepository? = null
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(repo: DownloadRepository) {
        repository = repo
    }

    fun startDownload(context: Context, itemId: Long, scope: CoroutineScope? = null) {
        if (activeJobs.containsKey(itemId)) return // already running

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

            try {
                // Update status to DOWNLOADING
                repo.updateDownload(item.copy(status = "DOWNLOADING", downloadSpeed = "Connecting..."))

                val urlSpec = item.url
                val outputFile = File(item.filePath)
                val destinationDir = outputFile.parentFile
                if (destinationDir != null && !destinationDir.exists()) {
                    destinationDir.mkdirs()
                }

                // Quick HEAD/GET probe request to determine Content-Length and Range capability
                val probeUrl = URL(urlSpec)
                val probeConn = probeUrl.openConnection() as HttpURLConnection
                probeConn.requestMethod = "GET"
                probeConn.connectTimeout = 10000
                probeConn.readTimeout = 10000
                probeConn.setRequestProperty("Range", "bytes=0-0")
                probeConn.connect()

                val responseCode = probeConn.responseCode
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

                // Update size if it was not known
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

            } catch (e: Exception) {
                Log.e(TAG, "Error in download loop: ${e.localizedMessage}")
                e.printStackTrace()
                val currentItem = repo.getDownloadById(itemId)
                if (currentItem != null && currentItem.status == "DOWNLOADING") {
                    repo.updateDownload(currentItem.copy(
                        status = "ERROR",
                        downloadSpeed = "Failed",
                        eta = "Error: ${e.localizedMessage ?: "Network error"}"
                    ))
                }
            } finally {
                activeJobs.remove(itemId)
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
        val numParts = 4
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
                    var retriesLeft = 3
                    var partSuccess = false

                    while (retriesLeft > 0 && !partSuccess && isActive) {
                        var connection: HttpURLConnection? = null
                        var inputStream: InputStream? = null
                        var raf: RandomAccessFile? = null
                        try {
                            val currentPartDownloaded = partFiles[i].length()
                            val requestStart = startByte + currentPartDownloaded
                            
                            if (requestStart > endByte) {
                                partSuccess = true
                                break
                            }

                            val url = URL(item.url)
                            connection = url.openConnection() as HttpURLConnection
                            connection.connectTimeout = 15000
                            connection.readTimeout = 15000
                            connection.setRequestProperty("Range", "bytes=$requestStart-$endByte")
                            connection.connect()

                            val responseCode = connection.responseCode
                            if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                                throw Exception("Sever responded with $responseCode")
                            }

                            inputStream = connection.inputStream
                            raf = RandomAccessFile(partFiles[i], "rw")
                            raf.seek(currentPartDownloaded)

                            // 16KB buffer size for optimized network responses
                            val buffer = ByteArray(16384)
                            var bytesRead: Int
                            
                            while (isActive) {
                                bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break

                                raf.write(buffer, 0, bytesRead)
                                
                                synchronized(tickerMutex) {
                                    totalDownloaded += bytesRead
                                    downloadedSinceLastUpdate += bytesRead
                                }
                            }
                            
                            partSuccess = true
                        } catch (e: Exception) {
                            retriesLeft--
                            Log.w(TAG, "Chunk $i failed (${3 - retriesLeft} retries matches): ${e.localizedMessage}")
                            if (retriesLeft > 0 && isActive) {
                                delay(1000)
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
                    repo.updateDownload(
                        finalItemState.copy(
                            bytesDownloaded = totalLength,
                            progress = 100f,
                            status = "FINISHED",
                            downloadSpeed = "Completed",
                            eta = "--"
                        )
                    )
                }
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
        var existingBytes = if (outputFile.exists() && item.isResumeSupported) outputFile.length() else 0L

        if (!item.isResumeSupported) {
            existingBytes = 0L
            if (outputFile.exists()) {
                outputFile.delete()
            }
        }

        val url = URL(item.url)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        if (existingBytes > 0) {
            connection.setRequestProperty("Range", "bytes=$existingBytes-")
        }

        connection.connect()

        val responseCode = connection.responseCode
        val isPartial = (responseCode == HttpURLConnection.HTTP_PARTIAL)
        val isOk = (responseCode == HttpURLConnection.HTTP_OK)

        if (!isPartial && !isOk) {
            throw Exception("Server responded with code $responseCode")
        }

        val inputStream: InputStream = connection.inputStream
        val raf = RandomAccessFile(outputFile, "rw")
        if (isPartial) {
            raf.seek(existingBytes)
        } else {
            raf.setLength(0)
        }

        val buffer = ByteArray(32768)
        var bytesRead: Int
        var lastUpdateTime = System.currentTimeMillis()
        var downloadedSinceLastUpdate = 0L
        var totalDownloaded = existingBytes

        while (isActive) {
            bytesRead = inputStream.read(buffer)
            if (bytesRead == -1) break

            raf.write(buffer, 0, bytesRead)
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
                if (currentItem != null && currentItem.status == "DOWNLOADING") {
                    item = currentItem.copy(
                        bytesDownloaded = totalDownloaded,
                        progress = progressPercent,
                        downloadSpeed = speedText,
                        eta = etaText
                    )
                    repo.updateDownload(item)
                }

                // Reset ticker
                downloadedSinceLastUpdate = 0L
                lastUpdateTime = currentTime
            }
        }

        raf.close()
        inputStream.close()
        connection.disconnect()

        if (isActive) {
            // Completed successfully
            val finalItemState = repo.getDownloadById(item.id)
            if (finalItemState != null) {
                repo.updateDownload(
                    finalItemState.copy(
                        bytesDownloaded = totalDownloaded,
                        progress = 100f,
                        status = "FINISHED",
                        downloadSpeed = "Completed",
                        eta = "--"
                    )
                )
            }
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
                repo.updateDownload(item.copy(
                    status = "PAUSED",
                    downloadSpeed = "Paused",
                    eta = "--"
                ))
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
}
