package com.example.util

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.AppDatabase
import com.example.data.DownloadRepository
import java.util.concurrent.TimeUnit

class DownloadRecoveryWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("DownloadRecoveryWorker", "Running automated download recovery task...")
        try {
            val database = AppDatabase.getDatabase(applicationContext)
            val repository = DownloadRepository(database.downloadDao())
            
            if (DownloadEngine.repository == null) {
                DownloadEngine.init(applicationContext, repository)
            }

            // Find unfinished downloads that are marked as DOWNLOADING but have no active coroutine running
            val stuckDownloads = repository.getActiveDownloadsDirect()
            Log.d("DownloadRecoveryWorker", "Automated scan found ${stuckDownloads.size} active download slots.")
            
            for (item in stuckDownloads) {
                if (!DownloadEngine.activeJobs.containsKey(item.id)) {
                    Log.d("DownloadRecoveryWorker", "Recovering interrupted task: ${item.fileName} (ID: ${item.id})")
                    // Automatically trigger the Foreground Service download process to resume
                    DownloadEngine.startDownload(applicationContext, item.id)
                }
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e("DownloadRecoveryWorker", "Error executing download recovery check", e)
            return Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "DownloadRecoveryWork"

        fun schedulePeriodicRecovery(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<DownloadRecoveryWorker>(
                15, TimeUnit.MINUTES
            )
            .setConstraints(constraints)
            .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            Log.d("DownloadRecoveryWorker", "Scheduled 15-minute background periodic recovery check.")
        }

        fun runOnceImmediately(context: Context) {
            val oneTimeRequest = OneTimeWorkRequestBuilder<DownloadRecoveryWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(oneTimeRequest)
            Log.d("DownloadRecoveryWorker", "Enqueued standard direct recovery pass.")
        }
    }
}
