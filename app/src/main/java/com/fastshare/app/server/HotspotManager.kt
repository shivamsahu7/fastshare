package com.fastshare.app.server

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.net.Inet4Address
import java.net.NetworkInterface

class HotspotManager(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

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

        try {
            // Acquire locks so hotspot stays alive when screen turns off
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastShare:HotspotWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L) // 2 hrs max
            }
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FastShare:HotspotWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else {
                @Suppress("DEPRECATION")
                wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FastShare:HotspotWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }

            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(res)
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

                    val localIp = getLocalIpAddress()
                    callback.onHotspotStarted(ssid, password, localIp)
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
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            callback.onHotspotFailed("Permission denied: ${e.message}")
        } catch (e: Exception) {
            callback.onHotspotFailed("Failed to start hotspot: ${e.message}")
        }
    }

    fun stopHotspot() {
        try {
            reservation?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            reservation = null
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                if (wifiLock?.isHeld == true) wifiLock?.release()
                wifiLock = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isRunning(): Boolean = reservation != null

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name.lowercase()
                // Typical AP / wlan / tethering interfaces on Android: ap0, wlan0, swlan0, rndis0
                if (name.contains("ap") || name.contains("wlan") || name.contains("softap")) {
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: ""
                            if (host.isNotEmpty()) return host
                        }
                    }
                }
            }

            // Fallback: check any non-loopback IPv4
            val allInterfaces = NetworkInterface.getNetworkInterfaces()
            while (allInterfaces.hasMoreElements()) {
                val intf = allInterfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.isNotEmpty() && !host.startsWith("10.") && !host.startsWith("172.")) {
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
