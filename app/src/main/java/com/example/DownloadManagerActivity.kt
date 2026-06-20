package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import com.example.data.AppDatabase
import com.example.data.DownloadRepository
import com.example.ui.components.DownloadManagerDialog
import com.example.ui.theme.MyApplicationTheme

class DownloadManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = AppDatabase.getDatabase(applicationContext)
        val downloadRepository = DownloadRepository(db.downloadDao())

        // Read initial tab index from notification intent
        val initialTab = intent.getIntExtra("EXTRA_TAB_INDEX", 0)

        setContent {
            val prefs = getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE)
            var isDarkTheme by remember {
                mutableStateOf(prefs.getBoolean("is_dark_theme", true))
            }

            DisposableEffect(prefs) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == "is_dark_theme") {
                        isDarkTheme = p.getBoolean("is_dark_theme", true)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }

            MyApplicationTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val scope = rememberCoroutineScope()
                    DownloadManagerDialog(
                        onDismissRequest = { finish() },
                        downloadRepository = downloadRepository,
                        coroutineScope = scope,
                        onExitClick = { finish() },
                        initialTab = initialTab
                    )
                }
            }
        }
    }
}
