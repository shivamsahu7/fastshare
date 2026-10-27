package com.fastshare.app.server

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.fastshare.app.util.TransferLocks
import java.net.Inet4Address
import java.net.NetworkInterface

private const val DEFAULT_GATEWAY_IP = "192.168.43.1"
private const val WIFI_DIRECT_GATEWAY_IP = "192.168.49.1"
private val ALPHANUMERIC = ('a'..'z') + ('A'..'Z') + ('0'..'9')

class HotspotManager(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var locks: TransferLocks? = null

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pActive = false
    /** Bumped on every start/stop so callbacks from an abandoned attempt are ignored. */
    private var sessionGeneration = 0

    interface Callback {
        fun onHotspotStarted(ssid: String, password: String, localIp: String)
        fun onHotspotFailed(reason: String)
        fun onHotspotStopped()
    }

    @SuppressLint("MissingPermission")
    fun startHotspot(callback: Callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onHotspotFailed("Hotspot API requires Android 8.0 (API 26) or higher.")
            return
        }

        // Keep the hotspot alive when the screen turns off
        locks?.release()
        locks = TransferLocks(context, "Hotspot")
        val generation = ++sessionGeneration

        if (canUseWifiDirect5Ghz()) {
            startWifiDirectGroup(generation, callback) { startLocalOnlyHotspot(generation, callback) }
        } else {
            startLocalOnlyHotspot(generation, callback)
        }
    }

    /**
     * LocalOnlyHotspot runs on 2.4 GHz on most phones (~3-8 MB/s). A Wi-Fi Direct group pinned to
     * 5 GHz is what other share apps use and is several times faster; legacy clients join it like a
     * normal WPA2 network, so the receiver's QR/connect flow is unchanged.
     */
    private fun canUseWifiDirect5Ghz(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            wifiManager.isWifiEnabled &&
            wifiManager.is5GHzBandSupported &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    @SuppressLint("MissingPermission")
    private fun startWifiDirectGroup(generation: Int, callback: Callback, fallback: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return fallback()
        val manager = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return fallback()

        val channel = try {
            manager.initialize(context.applicationContext, Looper.getMainLooper(), null)
        } catch (e: Exception) {
            null
        } ?: return fallback()
        p2pManager = manager
        p2pChannel = channel

        fun failOver() {
            if (generation != sessionGeneration) return
            releaseWifiDirect()
            fallback()
        }

        fun awaitGroup(attempt: Int) {
            if (generation != sessionGeneration) return
            try {
                manager.requestGroupInfo(channel) { group ->
                    if (generation != sessionGeneration) return@requestGroupInfo
                    if (group != null && group.isGroupOwner && !group.passphrase.isNullOrEmpty()) {
                        manager.requestConnectionInfo(channel) { info ->
                            if (generation != sessionGeneration) return@requestConnectionInfo
                            val ip = info?.groupOwnerAddress?.hostAddress ?: WIFI_DIRECT_GATEWAY_IP
                            p2pActive = true
                            callback.onHotspotStarted(group.networkName, group.passphrase, ip)
                        }
                    } else if (attempt < 20) {
                        mainHandler.postDelayed({ awaitGroup(attempt + 1) }, 250)
                    } else {
                        failOver()
                    }
                }
            } catch (e: SecurityException) {
                failOver()
            }
        }

        val suffix = (1..2).map { ALPHANUMERIC.random() }.joinToString("")
        val passphrase = (1..12).map { ALPHANUMERIC.random() }.joinToString("")
        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-$suffix-FastShare")
                .setPassphrase(passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()
        } catch (e: Exception) {
            return failOver()
        }

        val createListener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = awaitGroup(0)
            override fun onFailure(reason: Int) = failOver()
        }

        try {
            // Clear any stale group from a previous session first; createGroup fails with BUSY otherwise.
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = manager.createGroup(channel, config, createListener)
                override fun onFailure(reason: Int) = manager.createGroup(channel, config, createListener)
            })
        } catch (e: SecurityException) {
            failOver()
        }
    }

    private fun releaseWifiDirect() {
        val manager = p2pManager
        val channel = p2pChannel
        if (manager != null && channel != null) {
            runCatching { manager.removeGroup(channel, null) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { channel.close() }
        }
        p2pManager = null
        p2pChannel = null
        p2pActive = false
    }

    @SuppressLint("MissingPermission")
    private fun startLocalOnlyHotspot(generation: Int, callback: Callback) {
        if (generation != sessionGeneration) return
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(res)
                    if (generation != sessionGeneration) {
                        runCatching { res?.close() }
                        return
                    }
                    reservation = res

                    var ssid = "FastShare_AP"
                    var password = ""

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        res?.softApConfiguration?.let { config ->
                            ssid = config.ssid ?: ssid
                            password = config.passphrase ?: ""
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        res?.wifiConfiguration?.let { config ->
                            ssid = config.SSID ?: ssid
                            password = config.preSharedKey ?: ""
                        }
                    }

                    // Clean quotes if present in SSID (some OEMs enclose SSID in quotes)
                    ssid = ssid.removeSurrounding("\"")
                    password = password.removeSurrounding("\"")

                    val finalSsid = ssid
                    val finalPassword = password
                    // The SoftAP interface can take a moment to get its address; poll off the main thread.
                    Thread {
                        var localIp = DEFAULT_GATEWAY_IP
                        for (i in 0 until 10) {
                            val detected = getLocalIpAddress()
                            if (detected != DEFAULT_GATEWAY_IP && !detected.startsWith("127.") && !detected.startsWith("169.254.")) {
                                localIp = detected
                                break
                            }
                            try { Thread.sleep(100) } catch (ignored: InterruptedException) { break }
                        }
                        mainHandler.post {
                            if (reservation === res) callback.onHotspotStarted(finalSsid, finalPassword, localIp)
                        }
                    }.start()
                }

                override fun onStopped() {
                    super.onStopped()
                    reservation = null
                    callback.onHotspotStopped()
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    reservation = null
                    val errorMsg = when (reason) {
                        ERROR_NO_CHANNEL -> "Hotspot failed: No channel available"
                        ERROR_GENERIC -> "Hotspot failed: Generic system error"
                        ERROR_INCOMPATIBLE_MODE -> "Hotspot failed: Incompatible Wi-Fi mode"
                        ERROR_TETHERING_DISALLOWED -> "Hotspot failed: Tethering disallowed by policy"
                        else -> "Hotspot failed with code $reason"
                    }
                    callback.onHotspotFailed(errorMsg)
                }
            }, mainHandler)
        } catch (e: SecurityException) {
            callback.onHotspotFailed("Permission denied: ${e.message}")
        } catch (e: Exception) {
            callback.onHotspotFailed("Failed to start hotspot: ${e.message}")
        }
    }

    fun stopHotspot() {
        sessionGeneration++
        try {
            reservation?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            reservation = null
            releaseWifiDirect()
            locks?.release()
            locks = null
        }
    }

    fun isRunning(): Boolean = reservation != null || p2pActive

    fun getLocalIpAddress(): String {
        try {
            val allInterfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())

            // Priority 1: SoftAP interface names (ap0, softap0, swlan0, wlan1) with valid IPv4
            for (intf in allInterfaces) {
                val name = intf.name.lowercase()
                if (name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan") || name == "wlan1") {
                    val addrs = java.util.Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: ""
                            if (host.isNotEmpty() && !host.startsWith("127.") && !host.startsWith("169.254.")) {
                                return host
                            }
                        }
                    }
                }
            }

            // Priority 2: Standard Android LocalOnlyHotspot subnets (192.168.43.x, 192.168.44.x ... 192.168.50.x)
            for (intf in allInterfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.startsWith("192.168.4") || host.startsWith("192.168.5")) {
                            return host
                        }
                    }
                }
            }

            // Priority 3: Non-wlan0 active IPv4 interfaces
            for (intf in allInterfaces) {
                if (intf.name.lowercase() == "wlan0") continue
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.isNotEmpty() && !host.startsWith("127.") && !host.startsWith("169.254.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Default Android LocalOnlyHotspot gateway IP
        return "192.168.43.1"
    }
}
