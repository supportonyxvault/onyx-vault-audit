package com.onyxvault.messenger.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(private val context: Context) {
    
    companion object {
        private const val POLLING_INTERVAL_NORMAL = 30 * 60 * 1000L // 30 Minuten
        private const val POLLING_INTERVAL_RECOVERY = 5 * 60 * 1000L  // 5 Minuten
        private const val MIN_BATTERY_LEVEL = 15 // Mindest-Akku in Prozent
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val _isOnline = MutableStateFlow(false)
    val isOnline = _isOnline.asStateFlow()
    
    private val _networkType = MutableStateFlow("unknown")
    val networkType = _networkType.asStateFlow()
    
    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel = _batteryLevel.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        registerNetworkCallback()
        startBatteryMonitoring()
    }
    
    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = true
                updateNetworkType()
            }
            
            override fun onLost(network: Network) {
                _isOnline.value = false
                _networkType.value = "unknown"
            }
            
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                updateNetworkType(caps)
            }
        })
    }
    
    private fun updateNetworkType(caps: NetworkCapabilities? = null) {
        val capabilities = caps ?: connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        
        _networkType.value = when {
            capabilities == null -> "unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
    
    private fun startBatteryMonitoring() {
        scope.launch {
            while (isActive) {
                updateBatteryLevel()
                delay(60_000)
            }
        }
    }
    
    private fun updateBatteryLevel() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        _batteryLevel.value = level
    }
    
    fun shouldPoll(isRecoveryMode: Boolean = false): Boolean {
        if (!_isOnline.value) return false
        if (isRecoveryMode) {
            return _batteryLevel.value >= MIN_BATTERY_LEVEL
        }
        return _networkType.value == "wifi" || _batteryLevel.value > 30
    }
    
    fun getPollingInterval(isRecoveryMode: Boolean = false): Long {
        return if (isRecoveryMode) {
            POLLING_INTERVAL_RECOVERY
        } else {
            POLLING_INTERVAL_NORMAL
        }
    }
    
    fun getWorkManagerConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
    }
}
