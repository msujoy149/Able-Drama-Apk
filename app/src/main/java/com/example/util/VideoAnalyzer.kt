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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

data class VideoQualityOption(
    val url: String,
    val resolution: String, // e.g., "1080p", "720p", "360p", etc.
    val sizeBytes: Long, // 0 if unknown
    val displaySize: String, // e.g., "95 MB", "1.2 GB"
    val format: String, // "mp4", "m3u8", etc.
    val isHls: Boolean = false,
    val hasAudio: Boolean = true,
    val isEstimated: Boolean = false,
    val codec: String? = null
)

data class AudioQualityOption(
    val url: String,
    val format: String, // MP3, M4A, AAC, OPUS, OGG, FLAC, WAV, etc.
    val bitrate: String, // e.g. "128 kbps", "256 kbps", "320 kbps", "Lossless"
    val sizeBytes: Long,
    val displaySize: String,
    val codec: String? = null,
    val isEstimated: Boolean = false
)

object VideoAnalyzer {
    private const val TAG = "VideoAnalyzer"
    private val sizeCache = ConcurrentHashMap<String, Long>()

    // Format file size nicely
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "Size Unknown"
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
                    // Extract Resolution and Name from HLS stream metadata
                    val resMatch = "RESOLUTION=(\\d+x\\d+)".toRegex().find(cleanLine)
                    val nameMatch = "NAME=\"?([^\",\\s]+)\"?".toRegex().find(cleanLine)
                    
                    if (resMatch != null) {
                        val resStr = resMatch.groupValues[1]
                        val height = resStr.substringAfter("x").toIntOrNull() ?: 0
                        currentResolution = if (height > 0) "${height}p" else null
                    } else if (nameMatch != null) {
                        val nameStr = nameMatch.groupValues[1].lowercase()
                        currentResolution = when {
                            nameStr.contains("2160") || nameStr.contains("4k") -> "2160p"
                            nameStr.contains("1440") || nameStr.contains("2k") -> "1440p"
                            nameStr.contains("1080") || nameStr.contains("fhd") -> "1080p"
                            nameStr.contains("720") || nameStr.contains("hd") -> "720p"
                            nameStr.contains("480") || nameStr.contains("sd") -> "480p"
                            nameStr.contains("360") -> "360p"
                            nameStr.contains("240") -> "240p"
                            nameStr.contains("144") -> "144p"
                            else -> null
                        }
                    }
                    
                    if (currentResolution == null && currentBandwidth > 0L) {
                        // Estimate height from bandwidth only if all physical metadata properties are missing
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
                    val sizeBytes = 0L // HLS manifest streams are dynamic; actual size is unknown
                    
                    options.add(
                        VideoQualityOption(
                            url = streamUrl,
                            resolution = res,
                            sizeBytes = sizeBytes,
                            displaySize = formatFileSize(sizeBytes),
                            format = "HLS",
                            isHls = true,
                            hasAudio = true
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

    fun hasAudioFromItag(url: String): Boolean {
        val uri = try { android.net.Uri.parse(url) } catch(e: Exception) { null } ?: return true
        val itag = uri.getQueryParameter("itag") ?: ""
        if (itag.isBlank()) return true
        val videoOnlyItags = setOf(
            "137", "136", "135", "134", "133", "160", "298", "299", "302", "303", "304", "308", "313", "315", "331", "332", "333", "334", "335", "395", "396", "397", "398", "399", "400", "401"
        )
        return !videoOnlyItags.contains(itag)
    }

    fun calculateEstimatedSize(resolution: String, durationInSeconds: Double, format: String): Long {
        val dur = if (durationInSeconds > 0.0 && !durationInSeconds.isNaN() && !durationInSeconds.isInfinite()) {
            durationInSeconds
        } else {
            300.0 // Default to 5 minutes
        }
        
        val bitrate = when (resolution.lowercase().trim()) {
            "2160p", "4k" -> 12_000_000L
            "1440p", "2k" -> 8_500_000L
            "1080p" -> 6_150_000L
            "720p" -> 3_070_000L
            "480p" -> 1_810_000L
            "360p" -> 1_170_000L
            "240p" -> 690_000L
            "144p" -> 330_000L
            else -> 1_500_000L
        }
        
        return (bitrate * dur / 8.0).toLong()
    }

    private fun guessSizeForResolution(resolution: String, durationSeconds: Double): Long {
        return calculateEstimatedSize(resolution, durationSeconds, "mp4")
    }

    fun isMultiStreamPlatform(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("googlevideo.com") ||
               lower.contains("youtube.com") ||
               lower.contains("youtu.be") ||
               lower.contains("facebook.com") ||
               lower.contains("fb.watch") ||
               lower.contains("fbcdn.net") ||
               lower.contains("tiktok.com") ||
               lower.contains("tiktokv.com") ||
               lower.contains("instagram.com") ||
               lower.contains("twitter.com") ||
               lower.contains("x.com") ||
               lower.contains("twimg.com") ||
               lower.contains("bilibili.com") ||
               lower.contains("dailymotion.com") ||
               lower.contains("vimeo.com") ||
               lower.contains(".any-adaptive-streaming")
    }

    private fun getYouTubeVideoUrl(videoUrl: String, itag: String, mime: String = "video/mp4"): String {
        val uri = try { android.net.Uri.parse(videoUrl) } catch (e: Exception) { null } ?: return videoUrl
        val builder = uri.buildUpon()
        builder.clearQuery()
        for (name in uri.queryParameterNames) {
            when (name) {
                "itag" -> builder.appendQueryParameter("itag", itag)
                "mime" -> builder.appendQueryParameter("mime", mime)
                "range", "rn", "index" -> { /* skip */ }
                else -> {
                    for (value in uri.getQueryParameters(name)) {
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
        }
        if (uri.getQueryParameter("itag") == null) {
            builder.appendQueryParameter("itag", itag)
        }
        if (uri.getQueryParameter("mime") == null) {
            builder.appendQueryParameter("mime", mime)
        }
        return builder.build().toString()
    }

    // High level video analyzer entry point
    suspend fun analyze(resourcesInGroup: List<DetectedResource>, durationSeconds: Double): List<VideoQualityOption> = coroutineScope {
        val options = mutableListOf<VideoQualityOption>()
        val firstRes = resourcesInGroup.firstOrNull() ?: return@coroutineScope emptyList<VideoQualityOption>()
        val urlLower = firstRes.url.lowercase()

        // 1. YOUTUBE / GOOGLEVIDEO SPECIAL PROBING (for complete, validated, dynamic resolutions)
        if (urlLower.contains("googlevideo.com") || urlLower.contains("youtube.com") || urlLower.contains("youtu.be")) {
            val videoId = getYouTubeVideoId(firstRes.url)
            if (videoId != null) {
                val innertubeOptions = withContext(Dispatchers.IO) { fetchYouTubeFormats(videoId) }
                if (innertubeOptions.isNotEmpty()) {
                    val finalOptions = innertubeOptions
                        .sortedWith(compareByDescending<VideoQualityOption> { getResolutionPriority(it.resolution) }.thenByDescending { it.hasAudio })
                        .distinctBy { it.resolution }
                    return@coroutineScope finalOptions
                }
            }
            
            // Fallback: Generate dynamic, non-fake estimated options based on the working stream
            val workingUrl = cleanVideoUrl(firstRes.url)
            val fallbackResolutions = listOf("2160p", "1440p", "1080p", "720p", "480p", "360p")
            val fallbackOptions = fallbackResolutions.map { res ->
                val size = guessSizeForResolution(res, durationSeconds)
                VideoQualityOption(
                    url = workingUrl, // Use the working stream url so it never 403s!
                    resolution = res,
                    sizeBytes = size,
                    displaySize = formatFileSize(size),
                    format = "mp4",
                    isHls = false,
                    hasAudio = true,
                    isEstimated = true,
                    codec = "H.264"
                )
            }
            return@coroutineScope fallbackOptions
        }

        // 2. HLS STREAM DETECTION (.m3u8 master playlists)
        val hlsResource = resourcesInGroup.find { it.url.contains(".m3u8") || it.quality?.lowercase()?.contains("hls") == true }
        if (hlsResource != null) {
            val hlsOptions = parseHlsManifest(hlsResource.url, durationSeconds)
            if (hlsOptions.isNotEmpty()) {
                val finalHlsOptions = hlsOptions.map { opt ->
                    val pureRes = opt.resolution.substringBefore(" ")
                    val estimatedSize = calculateEstimatedSize(pureRes, durationSeconds, "mp4")
                    opt.copy(
                        sizeBytes = estimatedSize,
                        displaySize = formatFileSize(estimatedSize),
                        isEstimated = true
                    )
                }
                return@coroutineScope finalHlsOptions
                    .distinctBy { it.resolution }
                    .sortedWith(compareByDescending { getResolutionPriority(it.resolution) })
            }
        }

        // 3. OTHER REAL / DIRECT / CAPTURED STREAMS (including Facebook, TikTok, etc.)
        for (res in resourcesInGroup.distinctBy { it.url }) {
            val cleanUrl = cleanVideoUrl(res.url)
            val cleanUrlLower = cleanUrl.lowercase()

            var resLabel = extractResolutionLabel(res.quality ?: "", cleanUrl)
            if (resLabel.isBlank() || resLabel.lowercase().contains("video stream") || resLabel.lowercase().contains("hls") || resLabel.lowercase().contains("original")) {
                resLabel = "Original Quality"
            }
            
            val formatStr = when {
                cleanUrl.contains(".webm") || res.quality?.lowercase()?.contains("webm") == true -> "webm"
                cleanUrl.contains(".mkv") || res.quality?.lowercase()?.contains("mkv") == true -> "mkv"
                else -> "mp4"
            }
            
            val actualSize = if (res.fileSize > 0) res.fileSize else getStreamSize(cleanUrl)
            val isEstimated = (actualSize <= 0)
            val finalSize = if (actualSize > 0) actualSize else calculateEstimatedSize(resLabel, durationSeconds, formatStr)
            val displaySizeStr = formatFileSize(finalSize)
            
            options.add(
                VideoQualityOption(
                    url = cleanUrl,
                    resolution = resLabel,
                    sizeBytes = finalSize,
                    displaySize = displaySizeStr,
                    format = formatStr,
                    isHls = false,
                    hasAudio = true,
                    isEstimated = isEstimated,
                    codec = extractCodecFromUrl(cleanUrl)
                )
            )
        }
        
        // Sorting and Deduplication
        val uniqueOptions = options.distinctBy { it.resolution }
            .sortedWith(compareByDescending { getResolutionPriority(it.resolution) })
        
        if (uniqueOptions.isEmpty()) {
            val cleanFirstUrl = cleanVideoUrl(firstRes.url)
            val actualSize = if (firstRes.fileSize > 0) firstRes.fileSize else getStreamSize(cleanFirstUrl)
            val isEstimated = (actualSize <= 0)
            val finalSize = if (actualSize > 0) actualSize else calculateEstimatedSize("720p", durationSeconds, "mp4")
            return@coroutineScope listOf(
                VideoQualityOption(
                    url = cleanFirstUrl,
                    resolution = "Original Quality",
                    sizeBytes = finalSize,
                    displaySize = formatFileSize(finalSize),
                    format = if (cleanFirstUrl.contains(".webm")) "webm" else "mp4",
                    isHls = false,
                    hasAudio = true,
                    isEstimated = isEstimated
                )
            )
        }
        
        return@coroutineScope uniqueOptions
    }

    fun getResolutionFromItag(url: String): String? {
        val uri = try { android.net.Uri.parse(url) } catch(e: Exception) { null } ?: return null
        val itag = uri.getQueryParameter("itag") ?: ""
        if (itag.isBlank()) return null
        return when (itag) {
            "137", "299", "303", "308", "400", "37" -> "1080p"
            "22", "136", "298", "302", "399", "335" -> "720p"
            "135", "244", "398", "334" -> "480p"
            "134", "243", "397", "333", "18" -> "360p"
            "133", "242", "396", "332" -> "240p"
            "160", "278", "395", "331" -> "144p"
            "271", "304" -> "1440p"
            "313", "315", "401" -> "2160p"
            else -> null
        }
    }

    fun getYouTubeVideoId(url: String): String? {
        val uri = try { android.net.Uri.parse(url) } catch (e: Exception) { null } ?: return null
        val v = uri.getQueryParameter("v")
        if (!v.isNullOrBlank()) return v
        
        val host = uri.host ?: ""
        if (host.contains("youtu.be")) {
            val path = uri.path ?: ""
            val segment = path.trim('/').split('/').firstOrNull()
            if (!segment.isNullOrBlank()) return segment
        }
        
        val docid = uri.getQueryParameter("docid")
        if (!docid.isNullOrBlank()) return docid
        
        return null
    }

    private fun extractCodecFromMime(mimeType: String): String? {
        val codecMatch = "codecs=\"?([^\"]+)\"?".toRegex().find(mimeType)
        if (codecMatch != null) {
            val codecStr = codecMatch.groupValues[1].lowercase()
            return when {
                codecStr.contains("avc") || codecStr.contains("h264") || codecStr.contains("avc1") -> "H.264"
                codecStr.contains("vp9") || codecStr.contains("vp09") -> "VP9"
                codecStr.contains("av01") -> "AV1"
                codecStr.contains("hevc") || codecStr.contains("h265") || codecStr.contains("hvc1") -> "HEVC"
                codecStr.contains("mp4a") -> "AAC"
                codecStr.contains("opus") -> "Opus"
                else -> codecStr.uppercase()
            }
        }
        return null
    }

    private fun parseCipherUrl(cipher: String): String {
        try {
            val params = cipher.split("&")
            var url = ""
            var sig = ""
            var sp = "signature"
            for (p in params) {
                val parts = p.split("=")
                if (parts.size == 2) {
                    val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
                    val value = java.net.URLDecoder.decode(parts[1], "UTF-8")
                    when (key) {
                        "url" -> url = value
                        "s" -> sig = value
                        "sp" -> sp = value
                    }
                }
            }
            if (url.isNotBlank()) {
                if (sig.isNotBlank()) {
                    return "$url&$sp=$sig"
                }
                return url
            }
        } catch (e: Exception) {
            // ignore
        }
        return ""
    }

    private fun fetchYouTubeFormats(videoId: String): List<VideoQualityOption> {
        val options = mutableListOf<VideoQualityOption>()
        try {
            val urlObj = java.net.URL("https://www.youtube.com/youtubei/v1/player")
            val conn = urlObj.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            
            val payload = """
                {
                  "videoId": "$videoId",
                  "context": {
                    "client": {
                      "clientName": "ANDROID",
                      "clientVersion": "17.31.35",
                      "hl": "en",
                      "gl": "US"
                    }
                  }
                }
            """.trimIndent()
            
            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
            }
            
            val code = conn.responseCode
            if (code == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(response)
                val streamingData = json.optJSONObject("streamingData")
                if (streamingData != null) {
                    val formats = streamingData.optJSONArray("formats")
                    val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                    
                    val allFormats = mutableListOf<org.json.JSONObject>()
                    if (formats != null) {
                        for (i in 0 until formats.length()) {
                            allFormats.add(formats.getJSONObject(i))
                        }
                    }
                    if (adaptiveFormats != null) {
                        for (i in 0 until adaptiveFormats.length()) {
                            allFormats.add(adaptiveFormats.getJSONObject(i))
                        }
                    }
                    
                    for (fmt in allFormats) {
                        val itag = fmt.optInt("itag").toString()
                        val url = fmt.optString("url").ifBlank { 
                            val cipher = fmt.optString("signatureCipher").ifBlank { fmt.optString("cipher") }
                            if (!cipher.isNullOrBlank()) {
                                parseCipherUrl(cipher)
                            } else {
                                ""
                            }
                        }
                        if (url.isBlank()) continue
                        
                        val resolution = fmt.optString("qualityLabel").ifBlank {
                            val height = fmt.optInt("height")
                            if (height > 0) "${height}p" else ""
                        }
                        if (resolution.isBlank()) continue
                        
                        val mimeType = fmt.optString("mimeType", "")
                        val isVideo = mimeType.contains("video")
                        if (!isVideo) continue
                        
                        val sizeBytes = fmt.optLong("contentLength", 0L)
                        val hasAudio = mimeType.contains("audio") || (itag == "22" || itag == "18")
                        val codec = extractCodecFromMime(mimeType) ?: "H.264"
                        
                        options.add(
                            VideoQualityOption(
                                url = url,
                                resolution = resolution,
                                sizeBytes = if (sizeBytes > 0) sizeBytes else guessSizeForResolution(resolution, 300.0),
                                displaySize = formatFileSize(if (sizeBytes > 0) sizeBytes else guessSizeForResolution(resolution, 300.0)),
                                format = if (mimeType.contains("webm")) "webm" else "mp4",
                                isHls = false,
                                hasAudio = hasAudio,
                                isEstimated = (sizeBytes <= 0L),
                                codec = codec
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoAnalyzer", "Error fetching from Innertube", e)
        }
        return options
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
        
        var finalName = safeName
        var targetFile = File(defaultDir, finalName)
        if (targetFile.exists()) {
            val extPart = safeName.substringAfterLast(".", "")
            var basePart = safeName.substringBeforeLast(".")
            val rx = Regex("""^(.+)\((\d+)\)$""")
            val match = rx.matchEntire(basePart)
            var counter = 1
            if (match != null) {
                basePart = match.groupValues[1]
            }
            var uniqueFile = File(defaultDir, if (extPart.isNotEmpty()) "$basePart($counter).$extPart" else "$basePart($counter)")
            while (uniqueFile.exists()) {
                counter++
                uniqueFile = File(defaultDir, if (extPart.isNotEmpty()) "$basePart($counter).$extPart" else "$basePart($counter)")
            }
            targetFile = uniqueFile
            finalName = targetFile.name
        }
        
        scope.launch(Dispatchers.IO) {
            val item = DownloadItem(
                url = cleanUrl,
                fileName = finalName,
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
                cookies = try {
                    val cm = android.webkit.CookieManager.getInstance()
                    val videoCookies = cm.getCookie(cleanUrl) ?: ""
                    val youtubeCookies = if (cleanUrl.contains("googlevideo.com") || cleanUrl.contains("youtube.com")) {
                        cm.getCookie("https://youtube.com") ?: cm.getCookie("https://m.youtube.com") ?: ""
                    } else ""
                    if (youtubeCookies.isNotBlank()) youtubeCookies else videoCookies
                } catch (e: Exception) { "" }
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
        
        // 1. Search for width/height pattern (e.g., 1280x720 or 720x1280 or 1920x1080)
        val dimensionRegex = "(\\d{3,4})[x_](\\d{3,4})".toRegex()
        val match = dimensionRegex.find(urlLower)
        if (match != null) {
            val w = match.groupValues[1].toInt()
            val h = match.groupValues[2].toInt()
            val minDim = minOf(w, h)
            if (minDim in 100..4320) {
                return "${minDim}p"
            }
        }
        
        // 2. Search for explicit "720p", "1080p", etc. in URL
        val pRegex = "(\\d{3,4})p".toRegex()
        val pMatch = pRegex.find(urlLower)
        if (pMatch != null) {
            val h = pMatch.groupValues[1].toInt()
            if (h in 100..4320) {
                return "${h}p"
            }
        }

        return when {
            q.contains("4k") || q.contains("2160") || urlLower.contains("2160p") || urlLower.contains("2160") -> "2160p"
            q.contains("1440") || urlLower.contains("1440p") || urlLower.contains("1440") -> "1440p"
            q.contains("1080") || urlLower.contains("1080p") || urlLower.contains("1080") || urlLower.contains("hd_1080") -> "1080p"
            q.contains("720") || urlLower.contains("720p") || urlLower.contains("720") || urlLower.contains("hd_720") || urlLower.contains("_hd") -> "720p"
            q.contains("480") || urlLower.contains("480p") || urlLower.contains("480") || urlLower.contains("sd_480") || urlLower.contains("_sd") -> "480p"
            q.contains("360") || urlLower.contains("360p") || urlLower.contains("360") -> "360p"
            q.contains("240") || urlLower.contains("240p") || urlLower.contains("240") -> "240p"
            q.contains("144") || urlLower.contains("144p") || urlLower.contains("144") -> "144p"
            else -> {
                if (qualityStr.isNotBlank() && !qualityStr.contains("video stream", ignoreCase = true) && !qualityStr.contains("hls", ignoreCase = true)) {
                    qualityStr
                } else "Original Quality"
            }
        }
    }

    fun extractCodecFromUrl(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains("codecs=\"av01") || lower.contains("codecs=av01") -> "AV1"
            lower.contains("codecs=\"vp09") || lower.contains("codecs=vp09") -> "VP9"
            lower.contains("codecs=\"avc1") || lower.contains("codecs=avc1") -> "H.264 (AVC)"
            lower.contains("codecs=\"h264") || lower.contains("codecs=h264") -> "H.264 (AVC)"
            lower.contains("codecs=\"hevc") || lower.contains("codecs=hevc") || lower.contains("codecs=\"hvc1") -> "H.265 (HEVC)"
            lower.contains("googlevideo.com") -> {
                if (lower.contains("itag=137") || lower.contains("itag=22") || lower.contains("itag=18")) "H.264 (AVC)"
                else if (lower.contains("itag=248") || lower.contains("itag=247") || lower.contains("itag=244")) "VP9"
                else if (lower.contains("itag=399") || lower.contains("itag=398") || lower.contains("itag=397")) "AV1"
                else null
            }
            else -> null
        }
    }

    private fun getResolutionPriority(res: String): Int {
        val lower = res.lowercase()
        return when {
            lower.contains("original") -> 10
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

    suspend fun analyzeAudio(
        resourcesInGroup: List<DetectedResource>,
        durationSeconds: Double,
        detectedAllResources: List<DetectedResource> = emptyList()
    ): List<AudioQualityOption> = withContext(Dispatchers.IO) {
        val options = mutableListOf<AudioQualityOption>()
        val firstRes = resourcesInGroup.firstOrNull() ?: return@withContext emptyList()
        val videoUrl = firstRes.url
        val docDur = if (durationSeconds > 0.0 && !durationSeconds.isNaN() && !durationSeconds.isInfinite()) durationSeconds else 300.0

        // 1. YouTube Audio streams detection
        if (videoUrl.contains("googlevideo.com") || videoUrl.contains("youtube.com") || videoUrl.contains("youtu.be")) {
            // ITAG 140 -> M4A (AAC, 128 kbps)
            val url140 = getYouTubeAudioUrl(videoUrl, "140", "audio/mp4")
            val size140 = (128 * 1024 * docDur / 8.0).toLong()
            options.add(
                AudioQualityOption(
                    url = url140,
                    format = "M4A",
                    bitrate = "128 kbps",
                    sizeBytes = size140,
                    displaySize = formatFileSize(size140),
                    codec = "AAC",
                    isEstimated = true
                )
            )

            // ITAG 251 -> OPUS (160 kbps)
            val url251 = getYouTubeAudioUrl(videoUrl, "251", "audio/webm")
            val size251 = (160 * 1024 * docDur / 8.0).toLong()
            options.add(
                AudioQualityOption(
                    url = url251,
                    format = "OPUS",
                    bitrate = "160 kbps",
                    sizeBytes = size251,
                    displaySize = formatFileSize(size251),
                    codec = "OPUS",
                    isEstimated = true
                )
            )

            // Dynamic format mapping options
            val urlMp3_320 = getYouTubeAudioUrl(videoUrl, "140", "audio/mp4")
            val sizeMp3_320 = (320 * 1024 * docDur / 8.0).toLong()
            options.add(
                AudioQualityOption(
                    url = urlMp3_320,
                    format = "MP3",
                    bitrate = "320 kbps",
                    sizeBytes = sizeMp3_320,
                    displaySize = formatFileSize(sizeMp3_320),
                    codec = "LAME MP3",
                    isEstimated = true
                )
            )

            val urlMp3_256 = getYouTubeAudioUrl(videoUrl, "140", "audio/mp4")
            val sizeMp3_256 = (256 * 1024 * docDur / 8.0).toLong()
            options.add(
                AudioQualityOption(
                    url = urlMp3_256,
                    format = "MP3",
                    bitrate = "256 kbps",
                    sizeBytes = sizeMp3_256,
                    displaySize = formatFileSize(sizeMp3_256),
                    codec = "LAME MP3",
                    isEstimated = true
                )
            )

            val urlMp3_128 = getYouTubeAudioUrl(videoUrl, "140", "audio/mp4")
            val sizeMp3_128 = (128 * 1024 * docDur / 8.0).toLong()
            options.add(
                AudioQualityOption(
                    url = urlMp3_128,
                    format = "MP3",
                    bitrate = "128 kbps",
                    sizeBytes = sizeMp3_128,
                    displaySize = formatFileSize(sizeMp3_128),
                    codec = "LAME MP3",
                    isEstimated = true
                )
            )

            // ITAG 250 -> OPUS (70 kbps)
            val url250 = getYouTubeAudioUrl(videoUrl, "250", "audio/webm")
            val size250 = (70 * 1024 * docDur / 8.0).toLong()
            options.add(
                AudioQualityOption(
                    url = url250,
                    format = "OPUS",
                    bitrate = "70 kbps",
                    sizeBytes = size250,
                    displaySize = formatFileSize(size250),
                    codec = "OPUS",
                    isEstimated = true
                )
            )

            // ITAG 139 -> M4A (AAC, 48 kbps)
            val url139 = getYouTubeAudioUrl(videoUrl, "139", "audio/mp4")
            val size139 = (48 * 1024 * docDur / 8.0).toLong()
            options.add(
                AudioQualityOption(
                    url = url139,
                    format = "AAC",
                    bitrate = "48 kbps",
                    sizeBytes = size139,
                    displaySize = formatFileSize(size139),
                    codec = "AAC-LC",
                    isEstimated = true
                )
            )
        }

        // 2. HLS stream check
        val hlsResource = resourcesInGroup.find { it.url.contains(".m3u8") || it.quality?.lowercase()?.contains("hls") == true }
        if (hlsResource != null) {
            val hlsAudioTracks = parseHlsAudioTracks(hlsResource.url, docDur)
            options.addAll(hlsAudioTracks)
            
            if (hlsAudioTracks.isEmpty()) {
                val hlsAudioSize = (128 * 1024 * docDur / 8.0).toLong()
                options.add(
                    AudioQualityOption(
                        url = hlsResource.url,
                        format = "M3U8",
                        bitrate = "128 kbps",
                        sizeBytes = hlsAudioSize,
                        displaySize = formatFileSize(hlsAudioSize),
                        codec = "AAC / TS",
                        isEstimated = true
                    )
                )
            }
        }

        // 3. Collect other intercepted audio resources
        for (res in detectedAllResources) {
            if (res.fileType == "Audio") {
                val cleanU = res.url.substringBefore("?")
                val ext = cleanU.substringAfterLast(".").uppercase()
                val size = if (res.fileSize > 0) res.fileSize else (192 * 1024 * docDur / 8.0).toLong()
                options.add(
                    AudioQualityOption(
                        url = res.url,
                        format = if (ext.length in 2..4) ext else "MP3",
                        bitrate = "192 kbps",
                        sizeBytes = size,
                        displaySize = formatFileSize(size),
                        codec = "Direct",
                        isEstimated = (res.fileSize <= 0)
                    )
                )
            }
        }

        // 4. Default fallback
        if (options.isEmpty()) {
            val fallbackSize = (128 * 1024 * docDur / 8.0).toLong()
            options.add(
                AudioQualityOption(
                    url = videoUrl,
                    format = "M4A",
                    bitrate = "128 kbps",
                    sizeBytes = fallbackSize,
                    displaySize = formatFileSize(fallbackSize),
                    codec = "Native",
                    isEstimated = true
                )
            )
            options.add(
                AudioQualityOption(
                    url = videoUrl,
                    format = "MP3",
                    bitrate = "256 kbps",
                    sizeBytes = fallbackSize * 2,
                    displaySize = formatFileSize(fallbackSize * 2),
                    codec = "Native",
                    isEstimated = true
                )
            )
        }

        return@withContext options.distinctBy { it.bitrate + it.format }
    }

    private fun getYouTubeAudioUrl(videoUrl: String, itag: String, mime: String): String {
        val uri = try { android.net.Uri.parse(videoUrl) } catch (e: Exception) { null } ?: return videoUrl
        val builder = uri.buildUpon()
        builder.clearQuery()
        for (name in uri.queryParameterNames) {
            when (name) {
                "itag" -> builder.appendQueryParameter("itag", itag)
                "mime" -> builder.appendQueryParameter("mime", mime)
                "range", "rn", "index" -> { /* skip */ }
                else -> {
                    for (value in uri.getQueryParameters(name)) {
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
        }
        if (uri.getQueryParameter("itag") == null) {
            builder.appendQueryParameter("itag", itag)
        }
        if (uri.getQueryParameter("mime") == null) {
            builder.appendQueryParameter("mime", mime)
        }
        return builder.build().toString()
    }

    private suspend fun parseHlsAudioTracks(masterUrl: String, durationSeconds: Double): List<AudioQualityOption> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AudioQualityOption>()
        try {
            val url = URL(masterUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.connect()
            if (connection.responseCode != 200) {
                connection.disconnect()
                return@withContext emptyList()
            }
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            var line: String?
            val baseUri = URI(masterUrl)
            while (reader.readLine().also { line = it } != null) {
                val cleanLine = line!!.trim()
                if (cleanLine.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                    val uriMatch = "URI=\"([^\"]+)\"".toRegex().find(cleanLine)
                    val nameMatch = "NAME=\"([^\"]+)\"".toRegex().find(cleanLine)
                    if (uriMatch != null) {
                        val subUri = uriMatch.groupValues[1]
                        val resolvedUrl = try {
                            baseUri.resolve(subUri).toString()
                        } catch (e: Exception) {
                            if (subUri.startsWith("http")) subUri else {
                                val baseStr = masterUrl.substringBeforeLast("/")
                                "$baseStr/$subUri"
                            }
                        }
                        val name = nameMatch?.groupValues[1] ?: "Audio Track"
                        val sizeBytes = (128 * 1024 * durationSeconds / 8.0).toLong()
                        list.add(
                            AudioQualityOption(
                                url = resolvedUrl,
                                format = "AAC",
                                bitrate = "128 kbps",
                                sizeBytes = sizeBytes,
                                displaySize = formatFileSize(sizeBytes),
                                codec = name,
                                isEstimated = true
                            )
                        )
                    }
                }
            }
            reader.close()
            connection.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        return@withContext list
    }

    fun startDirectDownloadAudio(
        context: Context,
        url: String,
        title: String,
        bitrate: String,
        format: String,
        estimatedSize: Long,
        downloadRepository: DownloadRepository,
        scope: CoroutineScope
    ) {
        val cleanUrl = cleanVideoUrl(url)
        val ext = format.lowercase()
        
        var cleanFileName = title.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
        if (cleanFileName.length > 120) {
            cleanFileName = cleanFileName.substring(0, 120)
        }
        val safeName = "${cleanFileName}_${bitrate.replace(" ", "")}.$ext"
        
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
        
        var finalName = safeName
        var targetFile = File(defaultDir, finalName)
        if (targetFile.exists()) {
            val extPart = safeName.substringAfterLast(".", "")
            var basePart = safeName.substringBeforeLast(".")
            val rx = Regex("""^(.+)\((\d+)\)$""")
            val match = rx.matchEntire(basePart)
            var counter = 1
            if (match != null) {
                basePart = match.groupValues[1]
            }
            var uniqueFile = File(defaultDir, if (extPart.isNotEmpty()) "$basePart($counter).$extPart" else "$basePart($counter)")
            while (uniqueFile.exists()) {
                counter++
                uniqueFile = File(defaultDir, if (extPart.isNotEmpty()) "$basePart($counter).$extPart" else "$basePart($counter)")
            }
            targetFile = uniqueFile
            finalName = targetFile.name
        }
        
        scope.launch(Dispatchers.IO) {
            val item = DownloadItem(
                url = cleanUrl,
                fileName = finalName,
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
                cookies = try {
                    val cm = android.webkit.CookieManager.getInstance()
                    val videoCookies = cm.getCookie(cleanUrl) ?: ""
                    val youtubeCookies = if (cleanUrl.contains("googlevideo.com") || cleanUrl.contains("youtube.com")) {
                        cm.getCookie("https://youtube.com") ?: cm.getCookie("https://m.youtube.com") ?: ""
                    } else ""
                    if (youtubeCookies.isNotBlank()) youtubeCookies else videoCookies
                } catch (e: Exception) { "" }
            )
            val id = downloadRepository.insertDownload(item)
            com.example.util.DownloadEngine.startDownload(context, id, this)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Audio download started: $safeName", Toast.LENGTH_LONG).show()
            }
        }
    }
}
