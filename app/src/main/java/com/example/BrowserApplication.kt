package com.example

import android.app.Application

class BrowserApplication : Application() {
    override fun getAttributionTag(): String? {
        return "abledrama-attribution"
    }
}
