package com.example

import android.content.Context

object AppVersionInfo {
    private var cachedVersionName: String = ""

    /**
     * Dynamically loads the version name from Android package manager configuration.
     * Guaranteed to return a valid version string fallback if context is unavailable.
     */
    fun getVersionName(context: Context): String {
        if (cachedVersionName.isNotEmpty()) {
            return cachedVersionName
        }
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val ver = pInfo.versionName ?: "2.0.1"
            cachedVersionName = ver
            ver
        } catch (e: Exception) {
            "2.0.1"
        }
    }
}
