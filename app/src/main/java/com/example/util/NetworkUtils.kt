package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class NetworkMonitor(private val context: Context) {
    val isOnline: Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager == null) {
            trySend(true)
            awaitClose {}
            return@callbackFlow
        }
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (t: Throwable) {
            android.util.Log.e("NetworkMonitor", "Failed to register network callback safely", t)
            trySend(true)
        }

        // Set initial state safely
        var hasInternet = true
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (t: Throwable) {
            android.util.Log.e("NetworkMonitor", "Failed to determine initial network state safely", t)
        }
        trySend(hasInternet)

        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (t: Throwable) {
                android.util.Log.e("NetworkMonitor", "Failed to unregister network callback safely", t)
            }
        }
    }
}
