package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.DownloadRepository
import com.example.util.DownloadEngine
import com.example.util.DownloadRecoveryWorker

class BrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        try {
            // Initialize Room database and DownloadRepository
            val database = AppDatabase.getDatabase(this)
            val repository = DownloadRepository(database.downloadDao())
            
            // Register Repository & App Context with central DownloadEngine
            DownloadEngine.init(this, repository)
            
            // Run immediate crash recovery for stuck tasks
            DownloadRecoveryWorker.runOnceImmediately(this)
            
            // Schedule long-term periodic recovery checks
            DownloadRecoveryWorker.schedulePeriodicRecovery(this)
        } catch (e: Exception) {
            android.util.Log.e("BrowserApplication", "Failed to initialize background engine hooks", e)
        }
    }
}
