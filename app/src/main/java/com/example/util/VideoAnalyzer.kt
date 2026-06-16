package com.example.util

import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.example.ui.DetectedResource
import com.example.data.DownloadItem
import com.example.data.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class VideoQualityOption(
    val url: String,
    val resolution: String, // e.g., "1080p", "720p", "360p", etc.
    val sizeBytes: Long, // 0 if unknown
    val displaySize: String, // e.g., "95 MB", "1.2 GB"
    val format: String, // "mp4", "m3u8", etc.
    val isHls: Boolean = false
)

object VideoAnalyzer {
    private const val TAG = "VideoAnalyzer"
    private val sizeCache = ConcurrentHashMap<String, Long>()

    // Format file size nicely
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "Unknown size"
        if (size < 1024) return "$size B"
        val exp = (Math.log(size.toDouble()) / Math.log(1024.0)).toInt()
        val units = arrayOf("KB", "MB", "GB", "TB")
        val value = size / Math.pow(1024.0, exp.toDouble())
        return String.format("%.1f %s", value, units[exp - 1])
    }

    // Helper to request content-length securely in a background thread
    suspend fun getStreamSize(urlStr: String): Long = withContext(Dispatchers.IO) {
        if (sizeCache.containsKey(urlStr)) {
            return@withContext sizeCache[urlStr] ?: 0L
        }
        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.connect()
            
            val contentLength = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }
            connection.disconnect()
            
            if (contentLength > 0) {
                sizeCache[urlStr] = contentLength
                return@withContext contentLength
            }
        } catch (e: Exception) {
            // Fallback to GET with short timeout and Range header if HEAD fails
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.setRequestProperty("Range", "bytes=0-1")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.connect()
                
                val rangeHeader = connection.getHeaderField("Content-Range")
                val len = if (rangeHeader != null) {
                    val actualSize = rangeHeader.substringAfterLast("/").toLongOrNull()
                    actualSize ?: 0L
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        connection.contentLengthLong
                    } else {
                        connection.contentLength.toLong()
                    }
                }
                connection.disconnect()
                if (len > 0) {
                    sizeCache[urlStr] = len
                    return@withContext len
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error getting size for $urlStr: ${ex.localizedMessage}")
            }
        }
        return@withContext 0L
    }

    // Secondary analyzer to parse HLS manifest and extract sub-resolutions
    suspend fun parseHlsManifest(masterUrl: String, durationSeconds: Double): List<VideoQualityOption> = withContext(Dispatchers.IO) {
        val options = mutableListOf<VideoQualityOption>()
        try {
            val url = URL(masterUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.connect()
            
            if (connection.responseCode != 200) {
                connection.disconnect()
                return@withContext emptyList()
            }
            
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            var line: String?
            var currentBandwidth: Long = 0L
            var currentResolution: String? = null
            
            val baseUri = URI(masterUrl)
            
            while (reader.readLine().also { line = it } != null) {
                val cleanLine = line!!.trim()
                if (cleanLine.startsWith("#EXT-X-STREAM-INF:")) {
                    // Extract Bandwidth
                    val bwMatch = "BANDWIDTH=(\\d+)".toRegex().find(cleanLine)
                    if (bwMatch != null) {
                        currentBandwidth = bwMatch.groupValues[1].toLongOrNull() ?: 0L
                    }
                    // Extract Resolution
                    val resMatch = "RESOLUTION=(\\d+x\\d+)".toRegex().find(cleanLine)
                    if (resMatch != null) {
                        val resStr = resMatch.groupValues[1]
                        val height = resStr.substringAfter("x").toIntOrNull() ?: 0
                        currentResolution = if (height > 0) "${height}p" else null
                    } else {
                        // Guess height from bandwidth if resolution tag is missing
                        currentResolution = when {
                            currentBandwidth > 8000000 -> "2160p"
                            currentBandwidth > 5000000 -> "1080p"
                            currentBandwidth > 2500000 -> "720p"
                            currentBandwidth > 1200000 -> "480p"
                            currentBandwidth > 600000 -> "360p"
                            else -> "240p"
                        }
                    }
                } else if (cleanLine.isNotEmpty() && !cleanLine.startsWith("#")) {
                    // This line contains the stream URI
                    val streamUrl = try {
                        baseUri.resolve(cleanLine).toString()
                    } catch (e: Exception) {
                        if (cleanLine.startsWith("http")) cleanLine else {
                            val baseStr = masterUrl.substringBeforeLast("/")
                            "$baseStr/$cleanLine"
                        }
                    }
                    
                    val res = currentResolution ?: "720p"
                    val dur = if (durationSeconds > 0.0 && !durationSeconds.isNaN() && !durationSeconds.isInfinite()) durationSeconds else 300.0
                    val sizeBytes = if (currentBandwidth > 0L) {
                        (currentBandwidth * dur / 8.0).toLong()
                    } else {
                        guessSizeForResolution(res, dur)
                    }
                    
                    options.add(
                        VideoQualityOption(
                            url = streamUrl,
                            resolution = res,
                            sizeBytes = sizeBytes,
                            displaySize = formatFileSize(sizeBytes),
                            format = "HLS",
                            isHls = true
                        )
                    )
                    // Reset variables
                    currentBandwidth = 0L
                    currentResolution = null
                }
            }
            reader.close()
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HLS Master Playlist: ${e.localizedMessage}")
        }
        return@withContext options
    }

    private fun guessSizeForResolution(resolution: String, durationSeconds: Double): Long {
        val dur = if (durationSeconds > 0.0 && !durationSeconds.isNaN() && !durationSeconds.isInfinite()) durationSeconds else 300.0
        val kbps = when (resolution.lowercase()) {
            "144p" -> 150
            "240p" -> 350
            "360p" -> 700
            "480p" -> 1200
            "720p" -> 2500
            "1080p" -> 4500
            "1440p", "2k" -> 9000
            "2160p", "4k" -> 18000
            "4320p", "8k" -> 35000
            else -> 2000
        }
        return (kbps * 1000 * dur / 8.0).toLong()
    }

    // High level video analyzer entry point
    suspend fun analyze(resourcesInGroup: List<DetectedResource>, durationSeconds: Double): List<VideoQualityOption> {
        val options = mutableListOf<VideoQualityOption>()
        
        // Find if we have any HLS streams
        val hlsResource = resourcesInGroup.find { it.url.contains(".m3u8") || it.quality?.lowercase()?.contains("hls") == true }
        if (hlsResource != null) {
            val hlsOptions = parseHlsManifest(hlsResource.url, durationSeconds)
            if (hlsOptions.isNotEmpty()) {
                return hlsOptions.sortedWith(compareByDescending { getResolutionPriority(it.resolution) })
            }
        }

        // If not HLS or HLS parsing failed, aggregate all detected urls in the group
        // If there are multiple streams intercepted with different quality parameters, parse them:
        val seenResolutions = mutableSetOf<String>()
        for (res in resourcesInGroup) {
            val qualStr = res.quality ?: "720p"
            val cleanQual = extractResolutionLabel(qualStr, res.url)
            val formatStr = if (res.url.contains(".webm")) "webm" else "mp4"
            var sizeBytes = if (res.fileSize > 0) res.fileSize else getStreamSize(res.url)
            if (sizeBytes <= 0L) {
                sizeBytes = guessSizeForResolution(cleanQual, durationSeconds)
            }
            
            // Avoid duplicate resolution entries
            if (!seenResolutions.contains(cleanQual)) {
                seenResolutions.add(cleanQual)
                options.add(
                    VideoQualityOption(
                        url = res.url,
                        resolution = cleanQual,
                        sizeBytes = sizeBytes,
                        displaySize = formatFileSize(sizeBytes),
                        format = formatStr,
                        isHls = false
                    )
                )
            }
        }

        // What if we only have one stream playing but we want fallback/simulation?
        // Wait, for standard single quality endpoints (like Reels, TikTok), we only show ONE option (single quality option).
        // BUT if it's on a site where we can see multiple streams, they might already be detected in `resourcesInGroup`.
        // Let's make sure the options are sorted from highest resolution to lowest.
        return options.sortedWith(compareByDescending { getResolutionPriority(it.resolution) })
    }

    fun getResolutionFromItag(url: String): String? {
        val uri = try { android.net.Uri.parse(url) } catch(e: Exception) { null } ?: return null
        val itag = uri.getQueryParameter("itag") ?: ""
        if (itag.isBlank()) return null
        return when (itag) {
            "137", "299", "303", "308", "400", "22", "37" -> "1080p"
            "136", "298", "302", "399", "335" -> "720p"
            "135", "244", "398", "334" -> "480p"
            "134", "243", "397", "333", "18" -> "360p"
            "133", "242", "396", "332" -> "240p"
            "160", "278", "395", "331" -> "144p"
            "271", "304" -> "1440p"
            "313", "315", "401" -> "2160p"
            else -> null
        }
    }

    fun cleanVideoUrl(url: String): String {
        if (!url.contains("videoplayback")) return url
        try {
            val uri = android.net.Uri.parse(url)
            val builder = uri.buildUpon()
            builder.clearQuery()
            for (param in uri.queryParameterNames) {
                if (param != "range" && param != "rn" && param != "index") {
                    for (value in uri.getQueryParameters(param)) {
                        builder.appendQueryParameter(param, value)
                    }
                }
            }
            return builder.build().toString()
        } catch (e: Exception) {
            return url
        }
    }

    fun startDirectDownload(
        context: Context,
        url: String,
        title: String,
        resolution: String,
        estimatedSize: Long,
        downloadRepository: DownloadRepository,
        scope: CoroutineScope
    ) {
        val cleanUrl = cleanVideoUrl(url)
        val ext = if (cleanUrl.contains(".webm")) "webm" else if (cleanUrl.contains(".m3u8")) "m3u8" else "mp4"
        
        // Sanitize title to use as clean file name
        var cleanFileName = title.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
        if (cleanFileName.length > 120) {
            cleanFileName = cleanFileName.substring(0, 120)
        }
        val safeName = "${cleanFileName}_${resolution}.$ext"
        
        // Default storage directory
        val sharedPrefs = context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE)
        val storageMode = sharedPrefs.getString("storage_mode", "public") ?: "public"
        val defaultDir = try {
            if (storageMode == "public") {
                val rootDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val sub = File(rootDownloadDir, "Able Drama")
                if (!sub.exists()) sub.mkdirs()
                sub
            } else if (storageMode == "custom") {
                val customPath = sharedPrefs.getString("custom_storage_path", null)
                val customDir = if (!customPath.isNullOrEmpty()) File(customPath) else null
                if (customDir != null) {
                    if (!customDir.exists()) customDir.mkdirs()
                    customDir
                } else {
                    val rootDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val sub = File(rootDownloadDir, "Able Drama")
                    if (!sub.exists()) sub.mkdirs()
                    sub
                }
            } else {
                val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val sub = File(base, "Able Drama")
                if (!sub.exists()) sub.mkdirs()
                sub
            }
        } catch (e: Exception) {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val sub = File(base, "Able Drama")
            if (!sub.exists()) sub.mkdirs()
            sub
        }
        
        val targetFile = File(defaultDir, safeName)
        
        scope.launch(Dispatchers.IO) {
            val item = DownloadItem(
                url = cleanUrl,
                fileName = safeName,
                filePath = targetFile.absolutePath,
                fileSize = estimatedSize,
                bytesDownloaded = 0L,
                isResumeSupported = true,
                status = "DOWNLOADING",
                progress = 0f,
                useWebpageTitle = false,
                wifiOnly = false,
                retryOnFail = true,
                originalUrl = cleanUrl,
                referrerUrl = "",
                cookies = try { android.webkit.CookieManager.getInstance().getCookie(cleanUrl) ?: "" } catch (e: Exception) { "" }
            )
            val id = downloadRepository.insertDownload(item)
            com.example.util.DownloadEngine.startDownload(context, id, this)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Download started: $safeName", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun extractResolutionLabel(qualityStr: String, url: String): String {
        val itagRes = getResolutionFromItag(url)
        if (itagRes != null) return itagRes

        val q = qualityStr.lowercase()
        val urlLower = url.lowercase()
        return when {
            q.contains("4k") || q.contains("2160") || urlLower.contains("2160p") || urlLower.contains("2160") -> "2160p"
            q.contains("1440") || urlLower.contains("1440p") || urlLower.contains("1440") -> "1440p"
            q.contains("1080") || urlLower.contains("1080p") || urlLower.contains("1080") -> "1080p"
            q.contains("720") || urlLower.contains("720p") || urlLower.contains("720") -> "720p"
            q.contains("480") || urlLower.contains("480p") || urlLower.contains("480") -> "480p"
            q.contains("360") || urlLower.contains("360p") || urlLower.contains("360") -> "360p"
            q.contains("240") || urlLower.contains("240p") || urlLower.contains("240") -> "240p"
            q.contains("144") || urlLower.contains("144p") || urlLower.contains("144") -> "144p"
            else -> qualityStr
        }
    }

    private fun getResolutionPriority(res: String): Int {
        val lower = res.lowercase()
        return when {
            lower.contains("8k") || lower.contains("4320") -> 9
            lower.contains("4k") || lower.contains("2160") -> 8
            lower.contains("2k") || lower.contains("1440") -> 7
            lower.contains("1080") -> 6
            lower.contains("720") -> 5
            lower.contains("480") -> 4
            lower.contains("360") -> 3
            lower.contains("240") -> 2
            lower.contains("144") -> 1
            else -> 0
        }
    }
}
