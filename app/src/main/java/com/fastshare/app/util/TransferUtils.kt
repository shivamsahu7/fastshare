package com.fastshare.app.util

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.net.InetAddress
import java.net.Socket
import java.util.Locale
import javax.net.SocketFactory

private val mainHandler = Handler(Looper.getMainLooper())

/** Runs [block] on the main thread; Compose snapshot state must not be mutated from I/O threads. */
fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}

const val TRANSFER_BUFFER_SIZE = 256 * 1024
const val SOCKET_BUFFER_SIZE = 1024 * 1024
const val PROGRESS_INTERVAL_MS = 250L
const val NOTIFICATION_INTERVAL_MS = 1000L

private val unsafeFileChars = Regex("[^a-zA-Z0-9._-]")

fun sanitizeFileName(name: String): String = name.replace(unsafeFileChars, "_")

fun formatSpeed(mbPerSec: Double): String = String.format(Locale.US, "%.1f", mbPerSec)

/** Holds a partial wake lock and a high-performance Wi-Fi lock for the duration of a transfer. */
class TransferLocks(context: Context, tag: String, wakeTimeoutMs: Long = 2 * 60 * 60 * 1000L) {
    private val wakeLock: PowerManager.WakeLock?
    private val wifiLock: WifiManager.WifiLock?

    init {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastShare:${tag}WakeLock")?.apply {
                setReferenceCounted(false)
                acquire(wakeTimeoutMs)
            }
        }.getOrNull()

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = runCatching {
            wm?.createWifiLock(mode, "FastShare:${tag}WifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    fun release() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
    }
}

/** Socket factory that binds to the hotspot [Network] (if any) and enlarges TCP buffers. */
class HighSpeedSocketFactory(private val networkProvider: () -> Network?) : SocketFactory() {
    private fun delegate(): SocketFactory = networkProvider()?.socketFactory ?: getDefault()

    override fun createSocket(): Socket = configure(delegate().createSocket())
    override fun createSocket(host: String?, port: Int): Socket =
        configure(delegate().createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        configure(delegate().createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket =
        configure(delegate().createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        configure(delegate().createSocket(address, port, localAddress, localPort))

    private fun configure(socket: Socket): Socket {
        runCatching { networkProvider()?.bindSocket(socket) }
        runCatching {
            socket.tcpNoDelay = true
            socket.sendBufferSize = SOCKET_BUFFER_SIZE
            socket.receiveBufferSize = SOCKET_BUFFER_SIZE
            socket.trafficClass = 0x10
        }
        return socket
    }
}
