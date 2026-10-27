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
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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

    @Volatile
    var activeNetwork: Network? = null
        private set

    private val connectionGeneration = AtomicInteger(0)
    private val disconnectExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun connectToHotspot(ssid: String, password: String, callback: ConnectionCallback) {
        disconnect()
        connectionGeneration.incrementAndGet()

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

    /**
     * Tells the sender we are leaving, *then* drops the hotspot Wi-Fi. Runs on its own thread so it
     * survives the calling screen leaving composition (which would otherwise drop Wi-Fi first and
     * the sender would never hear about it).
     */
    fun disconnectFromPeer(serverUrl: String?) {
        val generationAtRequest = connectionGeneration.get()
        val network = activeNetwork
        disconnectExecutor.execute {
            if (!serverUrl.isNullOrEmpty()) {
                runCatching { request(serverUrl, "/disconnect", network, timeoutMs = 1500) }
            }
            // A new connection may have started meanwhile; don't tear that one down.
            if (connectionGeneration.get() == generationAtRequest) {
                mainHandler.post {
                    if (connectionGeneration.get() == generationAtRequest) disconnect()
                }
            }
        }
    }

    /** Returns the HTTP status of `GET serverUrl/ping`, or null if the sender is unreachable. */
    fun ping(serverUrl: String, deviceName: String): Int? = runCatching {
        request(serverUrl, "/ping", activeNetwork, timeoutMs = 2500, deviceName = deviceName)
    }.getOrNull()

    private fun request(serverUrl: String, path: String, network: Network?, timeoutMs: Int, deviceName: String? = null): Int {
        val url = URL(serverUrl.trimEnd('/') + path)
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            if (deviceName != null) conn.setRequestProperty("x-device-name", deviceName)
            conn.responseCode
        } finally {
            conn.disconnect()
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
