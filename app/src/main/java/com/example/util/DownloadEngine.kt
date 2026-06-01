package com.example.util

import android.content.Context
import android.util.Log
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

                var existingBytes = if (outputFile.exists()) outputFile.length() else 0L

                // If resume is not supported, we must overwrite
                if (!item.isResumeSupported) {
                    existingBytes = 0L
                    if (outputFile.exists()) {
                        outputFile.delete()
                    }
                }

                val url = URL(urlSpec)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                // Request partial content if resuming
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
                val totalLength = if (isPartial) {
                    existingBytes + connection.contentLength
                } else {
                    connection.contentLength.toLong()
                }

                // If size is unknown, update it
                val itemToUpdate = if (item.fileSize <= 0 && totalLength > 0) {
                    item.copy(fileSize = totalLength)
                } else {
                    item
                }
                item = itemToUpdate
                repo.updateDownload(item)

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

                        item = item.copy(
                            bytesDownloaded = totalDownloaded,
                            progress = progressPercent,
                            downloadSpeed = speedText,
                            eta = etaText
                        )
                        repo.updateDownload(item)

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
                    repo.updateDownload(
                        item.copy(
                            bytesDownloaded = totalDownloaded,
                            progress = 100f,
                            status = "FINISHED",
                            downloadSpeed = "Completed",
                            eta = "--"
                        )
                    )
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
