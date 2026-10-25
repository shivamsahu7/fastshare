package com.fastshare.app.client

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import com.fastshare.app.service.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class FileDownloader(private val context: Context) {

    val completedFileIds = java.util.Collections.synchronizedSet(HashSet<String>())

    fun clearSession() {
        completedFileIds.clear()
    }

    private class HighSpeedSocketFactory : javax.net.SocketFactory() {
        private val delegate = javax.net.SocketFactory.getDefault()
        override fun createSocket(): java.net.Socket = configure(delegate.createSocket())
        override fun createSocket(host: String?, port: Int): java.net.Socket = configure(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket = configure(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket = configure(delegate.createSocket(host, port))
        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket = configure(delegate.createSocket(address, port, localAddress, localPort))

        private fun configure(socket: java.net.Socket): java.net.Socket {
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 1024 * 1024 // 1 MB high-speed TCP window
                socket.receiveBufferSize = 1024 * 1024 // 1 MB high-speed TCP window
                socket.trafficClass = 0x10 // IPTOS_LOWDELAY / Maximum throughput
            } catch (ignored: Exception) {}
            return socket
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(HighSpeedSocketFactory())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    interface BatchDownloadCallback {
        fun onProgress(
            currentFileName: String,
            currentFileIndex: Int,
            totalFiles: Int,
            currentFileBytes: Long,
            currentFileSize: Long,
            overallBytes: Long,
            overallTotalBytes: Long,
            speedMbPerSec: Double,
            progress: Float
        )
        fun onBatchSuccess(title: String, fileCount: Int, totalBytes: Long, targetDirectory: String)
        fun onFileDownloaded(fileName: String, fileSize: Long, filePath: String) {}
        fun onError(message: String)
    }

    suspend fun downloadBatch(serverBaseUrl: String, callback: BatchDownloadCallback) = withContext(Dispatchers.IO) {
        val cleanUrl = if (serverBaseUrl.endsWith("/")) serverBaseUrl.dropLast(1) else serverBaseUrl

        // Start Foreground Service so Android doesn't suspend network or throttle when screen is locked
        try {
            TransferService.start(
                context,
                title = "FastShare: Receiving Files",
                message = "Downloading files in background. Screen can be locked safely."
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastShare:DownloadWakeLock")?.apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L) // 2 hr safety timeout
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FastShare:DownloadWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            @Suppress("DEPRECATION")
            wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FastShare:DownloadWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        try {
            // 1. Fetch Manifest with peer device name header
            val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
            val manifestRequest = Request.Builder()
                .url("$cleanUrl/manifest")
                .header("x-device-name", deviceModel)
                .get()
                .build()

            val manifestResponse = try {
                client.newBuilder()
                    .connectTimeout(8000, TimeUnit.MILLISECONDS)
                    .readTimeout(8000, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
                    .newCall(manifestRequest)
                    .execute()
            } catch (e: Exception) {
                callback.onError("Connection error: ${e.message ?: "server unreachable"}")
                return@withContext
            }

            if (manifestResponse.code == 410) {
                callback.onError("PEER_DISCONNECTED")
                return@withContext
            }
            if (!manifestResponse.isSuccessful) {
                callback.onError("Failed to fetch batch manifest: HTTP ${manifestResponse.code}")
                return@withContext
            }

            val manifestJsonStr = manifestResponse.body?.string() ?: ""
            if (manifestJsonStr.isEmpty()) {
                callback.onError("Empty manifest from server")
                return@withContext
            }

            val manifest = JSONObject(manifestJsonStr)
            if (manifest.optString("status") == "disconnected") {
                callback.onError("PEER_DISCONNECTED")
                return@withContext
            }
            val batchTitle = manifest.optString("title", "FastShare_Download")
            val totalSize = manifest.optLong("totalSize", 0L)
            val filesArray = manifest.getJSONArray("files")
            val totalFiles = filesArray.length()

            // Filter for files not yet downloaded in this session
            val pendingIndices = mutableListOf<Int>()
            for (i in 0 until totalFiles) {
                val item = filesArray.getJSONObject(i)
                val id = item.getString("id")
                if (!completedFileIds.contains(id)) {
                    pendingIndices.add(i)
                }
            }

            if (pendingIndices.isEmpty()) {
                // Not an error! Devices are connected and ready, all currently queued files are downloaded.
                callback.onBatchSuccess(batchTitle, 0, 0L, "")
                return@withContext
            }

            // Temp directory for partial/resumable downloads (.part files)
            val tempDir = File(context.getExternalFilesDir(null), "fastshare_temp").apply { mkdirs() }
            val cleanBatchPrefix = batchTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetDirName = "FastShare/$batchTitle"

            var overallBytesDownloaded = 0L
            var lastUpdateTime = System.currentTimeMillis()

            // 2. Download each pending file (with auto-resume and auto-retry)
            for ((seqIdx, i) in pendingIndices.withIndex()) {
                val item = filesArray.getJSONObject(i)
                val id = item.getString("id")
                val name = item.getString("name")
                val relativePath = item.optString("relativePath", name).removePrefix("/")
                val fileSize = item.optLong("size", 0L)
                val mimeType = item.optString("mimeType", "application/octet-stream")

                val partFile = File(tempDir, "${cleanBatchPrefix}_${id}.part")
                val completedMarkerFile = File(tempDir, "${cleanBatchPrefix}_${id}.done")

                // Check if this file was already completely downloaded in a previous attempt
                if (completedMarkerFile.exists()) {
                    completedFileIds.add(id)
                    overallBytesDownloaded += fileSize
                    val savedPath = "Downloads/$targetDirName/$relativePath"
                    callback.onFileDownloaded(name, fileSize, savedPath)
                    continue
                }

                // If partFile is already equal to fileSize, finalize it
                if (partFile.exists() && partFile.length() == fileSize && fileSize > 0) {
                    val savedPath = saveCompletedFile(partFile, relativePath, name, mimeType, targetDirName)
                    try { completedMarkerFile.createNewFile() } catch (ignored: Exception) {}
                    completedFileIds.add(id)
                    overallBytesDownloaded += fileSize
                    callback.onFileDownloaded(name, fileSize, savedPath)
                    continue
                }

                // Auto-retry loop for transient drops / screen lock reconnection
                var fileDownloadedSuccessfully = false
                var attempts = 0
                val maxAttempts = 3
                var lastException: Exception? = null

                while (!fileDownloadedSuccessfully && attempts < maxAttempts) {
                    attempts++
                    try {
                        val existingBytes = if (partFile.exists()) partFile.length() else 0L

                        val downloadRequestBuilder = Request.Builder()
                            .url("$cleanUrl/download?id=$id")
                            .get()

                        if (existingBytes in 1 until fileSize) {
                            downloadRequestBuilder.header("Range", "bytes=$existingBytes-")
                        }

                        val downloadRequest = downloadRequestBuilder.build()

                        client.newCall(downloadRequest).execute().use { response ->
                            if (!response.isSuccessful && response.code != 206) {
                                throw IOException("Server returned HTTP ${response.code} for '$name'")
                            }

                            val isPartial = (response.code == 206)
                            val body = response.body ?: throw IOException("Empty response body for '$name'")

                            // If not partial, overwrite; otherwise append
                            val appendMode = isPartial && existingBytes > 0
                            var fileRead = if (appendMode) existingBytes else 0L
                            if (!appendMode && existingBytes > 0) {
                                overallBytesDownloaded -= existingBytes
                            }

                            val startTime = System.currentTimeMillis()
                            val rawOutput = FileOutputStream(partFile, appendMode)
                            val outputStream = java.io.BufferedOutputStream(rawOutput, 256 * 1024)

                            outputStream.use { out ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(256 * 1024)
                                    var read: Int

                                    while (input.read(buffer).also { read = it } != -1) {
                                        out.write(buffer, 0, read)
                                        fileRead += read
                                        overallBytesDownloaded += read

                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdateTime >= 250 || fileRead >= fileSize) {
                                            val elapsedSec = (now - startTime) / 1000.0
                                            val speed = if (elapsedSec > 0) ((fileRead - (if (appendMode) existingBytes else 0L)) / (1024.0 * 1024.0)) / elapsedSec else 0.0
                                            val progress = if (totalSize > 0) (overallBytesDownloaded.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f) else 0f

                                            callback.onProgress(
                                                currentFileName = name,
                                                currentFileIndex = i + 1,
                                                totalFiles = totalFiles,
                                                currentFileBytes = fileRead,
                                                currentFileSize = fileSize,
                                                overallBytes = overallBytesDownloaded,
                                                overallTotalBytes = totalSize,
                                                speedMbPerSec = speed,
                                                progress = progress
                                            )

                                            // Update ongoing notification
                                            val percent = (progress * 100).toInt()
                                            TransferService.update(
                                                context,
                                                title = "FastShare: Receiving ($percent%)",
                                                message = "${i + 1}/$totalFiles: $name • ${String.format("%.1f", speed)} MB/s",
                                                progress = percent
                                            )

                                            lastUpdateTime = now
                                        }
                                    }
                                    out.flush()
                                }
                            }

                            // Verify file size
                            if (fileSize > 0 && partFile.length() < fileSize) {
                                throw IOException("Downloaded file length (${partFile.length()}) less than expected ($fileSize)")
                            }

                            // Successfully completed this file -> save to user's Downloads folder
                            val savedPath = saveCompletedFile(partFile, relativePath, name, mimeType, targetDirName)
                            try { completedMarkerFile.createNewFile() } catch (ignored: Exception) {}
                            completedFileIds.add(id)
                            fileDownloadedSuccessfully = true
                            callback.onFileDownloaded(name, fileSize, savedPath)
                        }
                    } catch (e: Exception) {
                        lastException = e
                        if (attempts < maxAttempts) {
                            delay(1500) // Brief delay to allow Wi-Fi or screen wake-up reconnect
                        }
                    }
                }

                if (!fileDownloadedSuccessfully) {
                    val errMsg = lastException?.localizedMessage ?: lastException?.message ?: "Transfer interrupted"
                    callback.onError("Interrupted on '$name': $errMsg. Tap Resume to continue.")
                    return@withContext
                }
            }

            TransferService.update(
                context,
                title = "FastShare: Transfer Complete",
                message = "Saved ${pendingIndices.size} file(s) to Downloads/$targetDirName",
                progress = 100
            )

            callback.onBatchSuccess(batchTitle, pendingIndices.size, overallBytesDownloaded, "Downloads/$targetDirName")
        } catch (e: Exception) {
            callback.onError("Download error: ${e.localizedMessage ?: e.message}")
        } finally {
            // Stop foreground service if batch finished or failed
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                if (wifiLock?.isHeld == true) wifiLock.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveCompletedFile(
        sourceFile: File,
        relativePath: String,
        name: String,
        mimeType: String,
        targetDirName: String
    ): String {
        val destRelativePath = "Downloads/$targetDirName/$relativePath"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val subFolder = if (relativePath.contains("/")) {
                val parentDir = relativePath.substringBeforeLast("/")
                "${Environment.DIRECTORY_DOWNLOADS}/$targetDirName/$parentDir"
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/$targetDirName"
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, subFolder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create MediaStore entry for $name")

            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                FileInputStream(sourceFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
                outStream.flush()
            }

            val doneValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, doneValues, null, null)
        } else {
            val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), targetDirName)
            val destFile = File(baseDir, relativePath)
            destFile.parentFile?.mkdirs()
            sourceFile.copyTo(destFile, overwrite = true)
        }

        // Delete the temporary .part file now that it's safe in destination
        try { sourceFile.delete() } catch (ignored: Exception) {}
        return destRelativePath
    }
}
