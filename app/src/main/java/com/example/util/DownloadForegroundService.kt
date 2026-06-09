package com.example.util

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.DownloadItem
import kotlinx.coroutines.*
import java.io.File

class DownloadForegroundService : Service() {
    private val TAG = "DownloadForegroundService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val NOTIFICATION_ID = 2026
        const val CHANNEL_ID = "download_channel_id"
        
        const val ACTION_START = "com.example.ACTION_START"
        const val ACTION_PAUSE = "com.example.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.ACTION_RESUME"
        const val EXTRA_ITEM_ID = "EXTRA_ITEM_ID"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DownloadForegroundService created")
        createNotificationChannel()
        
        // Acquire WakeLock to keep the CPU running when the screen is turned off
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AbleDrama:DownloadLock")
            wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3 hours timeout max
            Log.d(TAG, "WakeLock acquired safely")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val itemId = intent?.getLongExtra(EXTRA_ITEM_ID, -1L) ?: -1L
        
        Log.d(TAG, "Received action: $action, ItemId: $itemId")

        when (action) {
            ACTION_START -> {
                showInitialNotification()
                startUpdateLoop()
            }
            ACTION_PAUSE -> {
                if (itemId != -1L) {
                    DownloadEngine.pauseDownload(itemId)
                }
            }
            ACTION_RESUME -> {
                if (itemId != -1L) {
                    DownloadEngine.startDownload(this, itemId)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun showInitialNotification() {
        val notification = buildProgressNotification(
            title = "Preparing Download...",
            text = "Starting the background downloader...",
            progress = 0,
            isIndeterminate = true,
            itemId = -1L,
            isDownloading = false
        )
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service in foreground", e)
        }
    }

    private fun formatSize(bytes: Long): String {
        val df = java.text.DecimalFormat("#.##")
        return when {
            bytes >= 1024 * 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
            bytes >= 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024 * 1024))} MB"
            bytes >= 1024 -> "${df.format(bytes.toDouble() / 1024)} KB"
            else -> "$bytes B"
        }
    }

    private fun startUpdateLoop() {
        if (updateJob != null && updateJob?.isActive == true) return

        updateJob = serviceScope.launch {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            
            while (isActive) {
                delay(1000L)
                
                val activeIds = DownloadEngine.activeJobs.keys().toList()
                if (activeIds.isEmpty()) {
                    Log.d(TAG, "No more active downloads. Stopping foreground service.")
                    break
                }

                // Retrieve information about the main active download item
                val repo = DownloadEngine.repository
                val currentActiveId = activeIds.firstOrNull()
                var activeItem: DownloadItem? = null
                
                if (repo != null && currentActiveId != null) {
                    activeItem = repo.getDownloadById(currentActiveId)
                }

                if (activeItem != null && activeItem.status == "DOWNLOADING") {
                    val progressInt = activeItem.progress.toInt().coerceIn(0, 100)
                    val speed = activeItem.downloadSpeed
                    val eta = activeItem.eta
                    val fileName = activeItem.fileName
                    val bytesDownloaded = activeItem.bytesDownloaded
                    val fileSize = activeItem.fileSize

                    val downloadedStr = formatSize(bytesDownloaded)
                    val totalStr = formatSize(fileSize)
                    val remainingBytes = (fileSize - bytesDownloaded).coerceAtLeast(0L)
                    val remainingStr = formatSize(remainingBytes)

                    val contentTitle = "Downloading $fileName"
                    val contentText = "$speed • $downloadedStr / $totalStr (Left: $remainingStr) • ETA: $eta ($progressInt%)"
                    
                    val notification = buildProgressNotification(
                        title = contentTitle,
                        text = contentText,
                        progress = progressInt,
                        isIndeterminate = progressInt <= 0,
                        itemId = activeItem.id,
                        isDownloading = true
                    )
                    notificationManager?.notify(NOTIFICATION_ID, notification)
                } else {
                    // Update notification summary for general progress description
                    val count = activeIds.size
                    val contentTitle = "Downloading $count files"
                    val contentText = "Downloads are running in the background."
                    
                    val notification = buildProgressNotification(
                        title = contentTitle,
                        text = contentText,
                        progress = 0,
                        isIndeterminate = true,
                        itemId = -1L,
                        isDownloading = false
                    )
                    notificationManager?.notify(NOTIFICATION_ID, notification)
                }
            }
            
            // All active downloads finished or stopped
            stopSelf()
        }
    }

    private fun buildProgressNotification(
        title: String,
        text: String,
        progress: Int,
        isIndeterminate: Boolean,
        itemId: Long,
        isDownloading: Boolean
    ): Notification {
        // Main tap intent to open MainActivity
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent)
            .setProgress(100, progress, isIndeterminate)
            .setAutoCancel(false)

        // Add pause support
        if (isDownloading && itemId != -1L) {
            val pauseIntent = Intent(this, DownloadForegroundService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_ITEM_ID, itemId)
            }
            val pausePendingIntent = PendingIntent.getService(
                this,
                itemId.toInt() + 1000,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                pausePendingIntent
            )
        } else if (!isDownloading && itemId != -1L) {
            // Add resume support if paused
            val resumeIntent = Intent(this, DownloadForegroundService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_ITEM_ID, itemId)
            }
            val resumePendingIntent = PendingIntent.getService(
                this,
                itemId.toInt() + 2000,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Resume",
                resumePendingIntent
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Downloads"
            val descriptionText = "Shows progress of current background downloads"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                description = descriptionText
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "DownloadForegroundService destroyed")
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released safely")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during WakeLock release", e)
        }
        updateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
