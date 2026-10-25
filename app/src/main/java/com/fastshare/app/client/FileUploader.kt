package com.fastshare.app.client

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.fastshare.app.data.SharedFileItem
import com.fastshare.app.service.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

class FileUploader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS) // No timeout for large uploads
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    interface UploadCallback {
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
        fun onFileUploaded(fileName: String, fileIndex: Int, totalFiles: Int)
        fun onBatchSuccess(totalFiles: Int, totalBytes: Long)
        fun onError(message: String)
    }

    suspend fun uploadBatch(
        serverBaseUrl: String,
        items: List<SharedFileItem>,
        callback: UploadCallback
    ) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) {
            callback.onError("No files selected for upload")
            return@withContext
        }

        try {
            TransferService.start(
                context,
                title = "FastShare: Sending to Host",
                message = "Uploading files in background. Screen can be locked safely."
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FastShare:FileUploaderWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L) // 2 hours max
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiLock = wifiManager?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "FastShare:FileUploaderWifiLock"
        )?.apply {
            setReferenceCounted(false)
            acquire()
        }

        val totalFiles = items.size
        val overallTotalBytes = items.sumOf { it.size }
        var overallBytesSent = 0L

        try {
            val normalizedBaseUrl = if (serverBaseUrl.endsWith("/")) serverBaseUrl.dropLast(1) else serverBaseUrl
            val uploadUrl = "$normalizedBaseUrl/upload"

            for ((index, item) in items.withIndex()) {
                val fileIndex = index + 1
                var fileUploadedSuccessfully = false
                var attempts = 0
                val maxAttempts = 3
                var lastException: Exception? = null

                while (!fileUploadedSuccessfully && attempts < maxAttempts) {
                    attempts++
                    try {
                        var fileBytesSent = 0L
                        val startTime = System.currentTimeMillis()
                        var lastUpdateTime = startTime

                        val countingBody = object : RequestBody() {
                            override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
                            override fun contentLength(): Long = item.size

                            override fun writeTo(sink: BufferedSink) {
                                context.contentResolver.openInputStream(item.uri)?.use { inStream ->
                                    val buffer = ByteArray(64 * 1024)
                                    var read: Int
                                    while (inStream.read(buffer).also { read = it } != -1) {
                                        sink.write(buffer, 0, read)
                                        sink.flush()

                                        fileBytesSent += read
                                        val totalSoFar = overallBytesSent + fileBytesSent

                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdateTime >= 100 || fileBytesSent >= item.size) {
                                            val elapsedSec = (now - startTime) / 1000.0
                                            val speed = if (elapsedSec > 0) (fileBytesSent / (1024.0 * 1024.0)) / elapsedSec else 0.0
                                            val progress = if (overallTotalBytes > 0) totalSoFar.toFloat() / overallTotalBytes else 1f

                                            callback.onProgress(
                                                currentFileName = item.name,
                                                currentFileIndex = fileIndex,
                                                totalFiles = totalFiles,
                                                currentFileBytes = fileBytesSent,
                                                currentFileSize = item.size,
                                                overallBytes = totalSoFar,
                                                overallTotalBytes = overallTotalBytes,
                                                speedMbPerSec = speed,
                                                progress = progress.coerceIn(0f, 1f)
                                            )

                                            val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
                                            TransferService.update(
                                                context,
                                                title = "FastShare: Sending ($percent%)",
                                                message = "$fileIndex/$totalFiles: ${item.name} • ${String.format("%.1f", speed)} MB/s",
                                                progress = percent
                                            )

                                            lastUpdateTime = now
                                        }
                                    }
                                } ?: throw IOException("Cannot read file: ${item.name}")
                            }
                        }

                        val multipartBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("name", item.name)
                            .addFormDataPart("relativePath", item.relativePath)
                            .addFormDataPart("size", item.size.toString())
                            .addFormDataPart("file", item.name, countingBody)
                            .build()

                        val request = Request.Builder()
                            .url(uploadUrl)
                            .post(multipartBody)
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException("Server returned error ${response.code}: ${response.message}")
                            }
                        }

                        overallBytesSent += item.size
                        fileUploadedSuccessfully = true
                    } catch (e: Exception) {
                        lastException = e
                        if (attempts < maxAttempts) {
                            delay(1500)
                        }
                    }
                }

                if (!fileUploadedSuccessfully) {
                    throw lastException ?: IOException("Failed to upload ${item.name} after $maxAttempts attempts")
                }

                callback.onFileUploaded(item.name, fileIndex, totalFiles)
            }

            TransferService.update(
                context,
                title = "FastShare: Upload Complete",
                message = "Sent $totalFiles file(s) to host successfully",
                progress = 100
            )

            callback.onBatchSuccess(totalFiles, overallTotalBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            callback.onError(e.message ?: "Upload failed")
        } finally {
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (ignored: Exception) {}
            try {
                if (wifiLock?.isHeld == true) wifiLock.release()
            } catch (ignored: Exception) {}
        }
    }
}
