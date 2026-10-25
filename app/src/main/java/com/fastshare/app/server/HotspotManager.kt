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

                    var localIp = "192.168.43.1"
                    for (i in 0 until 10) {
                        val detected = getLocalIpAddress()
                        if (detected.isNotEmpty() && detected != "192.168.43.1" && !detected.startsWith("127.") && !detected.startsWith("169.254.")) {
                            localIp = detected
                            break
                        }
                        if (detected == "192.168.43.1") {
                            localIp = detected
                        }
                        try { Thread.sleep(100) } catch (ignored: Exception) {}
                    }
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
