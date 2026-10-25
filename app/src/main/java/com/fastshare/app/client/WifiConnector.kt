package com.fastshare.app.client

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

class WifiConnector(private val context: Context) {

    private val connectivityManager: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    var onNetworkLost: (() -> Unit)? = null
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    interface ConnectionCallback {
        fun onConnected(network: Network?)
        fun onFailure(reason: String)
    }

    var activeNetwork: Network? = null
        private set

    @SuppressLint("MissingPermission")
    fun connectToHotspot(ssid: String, password: String, callback: ConnectionCallback) {
        disconnect()

        val cleanSsid = ssid.removeSurrounding("\"")
        val cleanPassword = password.removeSurrounding("\"")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(cleanSsid)
                    .apply {
                        if (cleanPassword.isNotEmpty()) {
                            setWpa2Passphrase(cleanPassword)
                        }
                    }
                    .build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

                val networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        activeNetwork = network
                        // Bind process so all subsequent HTTP requests route through the Hotspot
                        connectivityManager.bindProcessToNetwork(network)
                        callback.onConnected(network)
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        activeNetwork = null
                        callback.onFailure("Failed to connect: User declined or network unavailable")
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        activeNetwork = null
                        connectivityManager.bindProcessToNetwork(null)
                        onNetworkLost?.invoke()
                    }
                }

                activeCallback = networkCallback
                connectivityManager.requestNetwork(request, networkCallback)
            } catch (e: Exception) {
                callback.onFailure("Wi-Fi connection error: ${e.message}")
            }
        } else {
            // Android 9 and below
            try {
                @Suppress("DEPRECATION")
                val wifiConfig = WifiConfiguration().apply {
                    SSID = "\"$cleanSsid\""
                    if (cleanPassword.isNotEmpty()) {
                        preSharedKey = "\"$cleanPassword\""
                    } else {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                }
                @Suppress("DEPRECATION")
                val netId = wifiManager.addNetwork(wifiConfig)
                if (netId != -1) {
                    @Suppress("DEPRECATION")
                    wifiManager.disconnect()
                    @Suppress("DEPRECATION")
                    wifiManager.enableNetwork(netId, true)
                    @Suppress("DEPRECATION")
                    wifiManager.reconnect()
                    callback.onConnected(null)
                } else {
                    callback.onFailure("Could not configure Wi-Fi profile on legacy Android")
                }
            } catch (e: Exception) {
                callback.onFailure("Legacy Wi-Fi error: ${e.message}")
            }
        }
    }

    fun disconnect() {
        try {
            activeNetwork = null
            activeCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                activeCallback = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
