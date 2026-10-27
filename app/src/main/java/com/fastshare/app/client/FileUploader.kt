package com.fastshare.app.client

import android.content.Context
import android.net.Network
import com.fastshare.app.data.SharedFileItem
import com.fastshare.app.service.TransferService
import com.fastshare.app.util.HighSpeedSocketFactory
import com.fastshare.app.util.NOTIFICATION_INTERVAL_MS
import com.fastshare.app.util.PROGRESS_INTERVAL_MS
import com.fastshare.app.util.TRANSFER_BUFFER_SIZE
import com.fastshare.app.util.TransferLocks
import com.fastshare.app.util.formatSpeed
import com.fastshare.app.util.runOnMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

class FileUploader(private val context: Context) {

    @Volatile
    var activeNetwork: Network? = null

    private val client = OkHttpClient.Builder()
        .socketFactory(HighSpeedSocketFactory { activeNetwork })
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
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

    private class MainThreadCallback(private val delegate: UploadCallback) : UploadCallback {
        override fun onProgress(
            currentFileName: String, currentFileIndex: Int, totalFiles: Int, currentFileBytes: Long,
            currentFileSize: Long, overallBytes: Long, overallTotalBytes: Long, speedMbPerSec: Double, progress: Float
        ) = runOnMain {
            delegate.onProgress(
                currentFileName, currentFileIndex, totalFiles, currentFileBytes,
                currentFileSize, overallBytes, overallTotalBytes, speedMbPerSec, progress
            )
        }
        override fun onFileUploaded(fileName: String, fileIndex: Int, totalFiles: Int) =
            runOnMain { delegate.onFileUploaded(fileName, fileIndex, totalFiles) }
        override fun onBatchSuccess(totalFiles: Int, totalBytes: Long) =
            runOnMain { delegate.onBatchSuccess(totalFiles, totalBytes) }
        override fun onError(message: String) = runOnMain { delegate.onError(message) }
    }

    suspend fun uploadBatch(
        serverBaseUrl: String,
        items: List<SharedFileItem>,
        callback: UploadCallback
    ) = withContext(Dispatchers.IO) {
        val cb = MainThreadCallback(callback)
        if (items.isEmpty()) {
            cb.onError("No files selected for upload")
            return@withContext
        }

        runCatching {
            TransferService.start(
                context,
                title = "FastShare: Sending to Host",
                message = "Uploading files in background. Screen can be locked safely."
            )
        }

        val locks = TransferLocks(context, "FileUploader")
        val totalFiles = items.size
        val overallTotalBytes = items.sumOf { it.size }
        var overallBytesSent = 0L
        var lastNotificationTime = 0L
        val uploadUrl = "${serverBaseUrl.trimEnd('/')}/upload".toHttpUrl()

        try {
            for ((index, item) in items.withIndex()) {
                val fileIndex = index + 1
                var lastException: Exception? = null
                var uploaded = false

                for (attempt in 1..MAX_ATTEMPTS) {
                    try {
                        val body = ProgressRequestBody(item) { fileBytesSent, speed ->
                            val totalSoFar = overallBytesSent + fileBytesSent
                            val progress = if (overallTotalBytes > 0) {
                                (totalSoFar.toFloat() / overallTotalBytes).coerceIn(0f, 1f)
                            } else 1f
                            cb.onProgress(
                                currentFileName = item.name,
                                currentFileIndex = fileIndex,
                                totalFiles = totalFiles,
                                currentFileBytes = fileBytesSent,
                                currentFileSize = item.size,
                                overallBytes = totalSoFar,
                                overallTotalBytes = overallTotalBytes,
                                speedMbPerSec = speed,
                                progress = progress
                            )
                            val now = System.currentTimeMillis()
                            if (now - lastNotificationTime >= NOTIFICATION_INTERVAL_MS) {
                                lastNotificationTime = now
                                val percent = (progress * 100).toInt()
                                TransferService.update(
                                    context,
                                    title = "FastShare: Sending ($percent%)",
                                    message = "$fileIndex/$totalFiles: ${item.name} • ${formatSpeed(speed)} MB/s",
                                    progress = percent
                                )
                            }
                        }

                        val url = uploadUrl.newBuilder()
                            .addQueryParameter("name", item.name)
                            .addQueryParameter("relativePath", item.relativePath)
                            .addQueryParameter("size", item.size.toString())
                            .build()
                        val request = Request.Builder().url(url).post(body).build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException("Server returned error ${response.code}: ${response.message}")
                            }
                        }
                        uploaded = true
                        break
                    } catch (e: Exception) {
                        lastException = e
                        if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
                    }
                }

                if (!uploaded) {
                    throw lastException ?: IOException("Failed to upload ${item.name} after $MAX_ATTEMPTS attempts")
                }
                overallBytesSent += item.size
                cb.onFileUploaded(item.name, fileIndex, totalFiles)
            }

            TransferService.update(
                context,
                title = "FastShare: Upload Complete",
                message = "Sent $totalFiles file(s) to host successfully",
                progress = 100
            )
            cb.onBatchSuccess(totalFiles, overallTotalBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            cb.onError(e.message ?: "Upload failed")
        } finally {
            locks.release()
        }
    }

    /** Streams the file straight to the socket, reporting progress at most every [PROGRESS_INTERVAL_MS]. */
    private inner class ProgressRequestBody(
        private val item: SharedFileItem,
        private val onProgress: (bytesSent: Long, speedMbPerSec: Double) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM
        override fun contentLength(): Long = item.size

        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(item.uri)
                ?: throw IOException("Cannot read file: ${item.name}")
            input.use { inStream ->
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                val startTime = System.currentTimeMillis()
                var lastUpdateTime = 0L
                var sent = 0L
                while (true) {
                    val read = inStream.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    sent += read

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= PROGRESS_INTERVAL_MS || sent >= item.size) {
                        lastUpdateTime = now
                        val elapsedSec = (now - startTime) / 1000.0
                        val speed = if (elapsedSec > 0) (sent / (1024.0 * 1024.0)) / elapsedSec else 0.0
                        onProgress(sent, speed)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1500L
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
