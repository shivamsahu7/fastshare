package com.fastshare.app.client

import android.content.ContentValues
import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fastshare.app.service.TransferService
import com.fastshare.app.util.HighSpeedSocketFactory
import com.fastshare.app.util.NOTIFICATION_INTERVAL_MS
import com.fastshare.app.util.PROGRESS_INTERVAL_MS
import com.fastshare.app.util.TRANSFER_BUFFER_SIZE
import com.fastshare.app.util.TransferLocks
import com.fastshare.app.util.formatSpeed
import com.fastshare.app.util.runOnMain
import com.fastshare.app.util.sanitizeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class FileDownloader(private val context: Context) {

    @Volatile
    var activeNetwork: Network? = null

    val completedFileIds: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** In-progress MediaStore entries (Android 10+), keyed by batch+file id, so retries can append. */
    private val pendingMediaUris = ConcurrentHashMap<String, Uri>()

    fun clearSession() {
        completedFileIds.clear()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(HighSpeedSocketFactory { activeNetwork })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val manifestClient: OkHttpClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
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

    private class MainThreadCallback(private val delegate: BatchDownloadCallback) : BatchDownloadCallback {
        override fun onProgress(
            currentFileName: String, currentFileIndex: Int, totalFiles: Int, currentFileBytes: Long,
            currentFileSize: Long, overallBytes: Long, overallTotalBytes: Long, speedMbPerSec: Double, progress: Float
        ) = runOnMain {
            delegate.onProgress(
                currentFileName, currentFileIndex, totalFiles, currentFileBytes,
                currentFileSize, overallBytes, overallTotalBytes, speedMbPerSec, progress
            )
        }
        override fun onBatchSuccess(title: String, fileCount: Int, totalBytes: Long, targetDirectory: String) =
            runOnMain { delegate.onBatchSuccess(title, fileCount, totalBytes, targetDirectory) }
        override fun onFileDownloaded(fileName: String, fileSize: Long, filePath: String) =
            runOnMain { delegate.onFileDownloaded(fileName, fileSize, filePath) }
        override fun onError(message: String) = runOnMain { delegate.onError(message) }
    }

    private class ManifestFile(
        val id: String,
        val name: String,
        val relativePath: String,
        val size: Long,
        val mimeType: String,
        val index: Int
    )

    /** Shared progress state for the parallel workers of one batch. */
    private inner class BatchProgress(
        private val cb: BatchDownloadCallback,
        private val totalFiles: Int,
        private val totalSize: Long
    ) {
        val overallBytes = AtomicLong(0L)
        private val startTime = System.currentTimeMillis()
        private val startBytes = AtomicLong(-1L)
        @Volatile private var lastUpdateTime = 0L
        @Volatile private var lastNotificationTime = 0L

        fun onBytes(file: ManifestFile, fileBytes: Long, delta: Long) {
            val overall = overallBytes.addAndGet(delta)
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime < PROGRESS_INTERVAL_MS && fileBytes < file.size) return
            lastUpdateTime = now
            startBytes.compareAndSet(-1L, overall - delta)

            val elapsedSec = (now - startTime) / 1000.0
            val speed = if (elapsedSec > 0) ((overall - startBytes.get()) / (1024.0 * 1024.0)) / elapsedSec else 0.0
            val progress = if (totalSize > 0) (overall.toFloat() / totalSize).coerceIn(0f, 1f) else 0f
            cb.onProgress(file.name, file.index + 1, totalFiles, fileBytes, file.size, overall, totalSize, speed, progress)

            if (now - lastNotificationTime >= NOTIFICATION_INTERVAL_MS) {
                lastNotificationTime = now
                val percent = (progress * 100).toInt()
                TransferService.update(
                    context,
                    title = "FastShare: Receiving ($percent%)",
                    message = "${file.index + 1}/$totalFiles: ${file.name} • ${formatSpeed(speed)} MB/s",
                    progress = percent
                )
            }
        }
    }

    suspend fun downloadBatch(serverBaseUrl: String, callback: BatchDownloadCallback) = withContext(Dispatchers.IO) {
        val cb = MainThreadCallback(callback)
        val cleanUrl = serverBaseUrl.trimEnd('/')
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        // 1. Fetch manifest
        val manifestRequest = Request.Builder()
            .url("$cleanUrl/manifest")
            .header("x-device-name", deviceModel)
            .get()
            .build()

        val manifestJsonStr = try {
            manifestClient.newCall(manifestRequest).execute().use { response ->
                if (response.code == 410) {
                    cb.onError("PEER_DISCONNECTED")
                    return@withContext
                }
                if (!response.isSuccessful) {
                    cb.onError("Failed to fetch batch manifest: HTTP ${response.code}")
                    return@withContext
                }
                response.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            cb.onError("Connection error: ${e.message ?: "server unreachable"}")
            return@withContext
        }

        if (manifestJsonStr.isEmpty()) {
            cb.onError("Empty manifest from server")
            return@withContext
        }

        val manifest = try {
            JSONObject(manifestJsonStr)
        } catch (e: Exception) {
            cb.onError("Invalid manifest from server")
            return@withContext
        }
        if (manifest.optString("status") == "disconnected") {
            cb.onError("PEER_DISCONNECTED")
            return@withContext
        }

        val batchTitle = manifest.optString("title", "FastShare_Download")
        val totalSize = manifest.optLong("totalSize", 0L)
        val filesArray = manifest.optJSONArray("files")
        val totalFiles = filesArray?.length() ?: 0

        val pending = ArrayList<ManifestFile>()
        for (i in 0 until totalFiles) {
            val obj = filesArray!!.getJSONObject(i)
            val id = obj.getString("id")
            if (completedFileIds.contains(id)) continue
            val name = obj.getString("name")
            pending.add(
                ManifestFile(
                    id = id,
                    name = name,
                    relativePath = obj.optString("relativePath", name).removePrefix("/"),
                    size = obj.optLong("size", 0L),
                    mimeType = obj.optString("mimeType", "application/octet-stream"),
                    index = i
                )
            )
        }

        if (pending.isEmpty()) {
            // Connected and idle: every queued file is already downloaded.
            cb.onBatchSuccess(batchTitle, 0, 0L, "")
            return@withContext
        }

        // Only promote to a foreground transfer once there is real work to do; this is polled every few seconds.
        runCatching {
            TransferService.start(
                context,
                title = "FastShare: Receiving Files",
                message = "Downloading files in background. Screen can be locked safely."
            )
        }
        val locks = TransferLocks(context, "Download")

        try {
            val tempDir = File(context.getExternalFilesDir(null), "fastshare_temp").apply { mkdirs() }
            val batchKey = sanitizeFileName(batchTitle)
            val targetDirName = "FastShare/$batchTitle"
            val progress = BatchProgress(cb, totalFiles, totalSize)

            // 2. Download pending files, a few at a time: per-file overhead (request + MediaStore insert)
            // dominates for batches of small photos, while big files stay bandwidth-bound either way.
            val semaphore = Semaphore(PARALLEL_DOWNLOADS)
            val failures = coroutineScope {
                pending.map { file ->
                    async {
                        semaphore.withPermit {
                            downloadWithRetry(cleanUrl, file, batchKey, targetDirName, tempDir, progress, cb)
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (failures.isNotEmpty()) {
                cb.onError("${failures.first()}. Tap Resume to continue.")
                return@withContext
            }

            TransferService.update(
                context,
                title = "FastShare: Transfer Complete",
                message = "Saved ${pending.size} file(s) to Downloads/$targetDirName",
                progress = 100
            )
            cb.onBatchSuccess(batchTitle, pending.size, progress.overallBytes.get(), "Downloads/$targetDirName")
        } catch (e: Exception) {
            cb.onError("Download error: ${e.localizedMessage ?: e.message}")
        } finally {
            locks.release()
        }
    }

    /** Returns null on success, or an error message after all retries failed. */
    private suspend fun downloadWithRetry(
        baseUrl: String,
        file: ManifestFile,
        batchKey: String,
        targetDirName: String,
        tempDir: File,
        progress: BatchProgress,
        cb: BatchDownloadCallback
    ): String? {
        val doneMarker = File(tempDir, "${batchKey}_${file.id}.done")
        val savedPath = "Downloads/$targetDirName/${file.relativePath}"
        if (doneMarker.exists()) {
            completedFileIds.add(file.id)
            progress.onBytes(file, file.size, file.size)
            cb.onFileDownloaded(file.name, file.size, savedPath)
            return null
        }

        var lastException: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStoreTarget("$batchKey/${file.id}", file, targetDirName)
                } else {
                    LegacyFileTarget(File(tempDir, "${batchKey}_${file.id}.part"), file, targetDirName)
                }
                downloadOnce(baseUrl, file, target, progress)
                runCatching { doneMarker.createNewFile() }
                completedFileIds.add(file.id)
                cb.onFileDownloaded(file.name, file.size, savedPath)
                return null
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
            }
        }
        return "Interrupted on '${file.name}': ${lastException?.localizedMessage ?: "Transfer interrupted"}"
    }

    private fun downloadOnce(baseUrl: String, file: ManifestFile, target: DownloadTarget, progress: BatchProgress) {
        val existingBytes = target.existingBytes()
        if (file.size > 0 && existingBytes == file.size) {
            target.finish()
            progress.onBytes(file, file.size, file.size)
            return
        }

        val requestBuilder = Request.Builder().url("$baseUrl/download?id=${file.id}").get()
        if (existingBytes in 1 until file.size) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        // Bytes this attempt added to the overall counter; rolled back on failure since a retry re-reports them.
        var reported = 0L
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Server returned HTTP ${response.code} for '${file.name}'")
                }
                val body = response.body ?: throw IOException("Empty response body for '${file.name}'")
                val append = response.code == 206 && existingBytes > 0
                var fileRead = if (append) existingBytes else 0L
                if (fileRead > 0) {
                    progress.onBytes(file, fileRead, fileRead)
                    reported += fileRead
                }

                target.openOutput(append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            fileRead += read
                            reported += read
                            progress.onBytes(file, fileRead, read.toLong())
                        }
                    }
                }

                if (file.size > 0 && fileRead < file.size) {
                    throw IOException("Received $fileRead of ${file.size} bytes")
                }
                target.finish()
            }
        } catch (e: Exception) {
            progress.overallBytes.addAndGet(-reported)
            throw e
        }
    }

    private interface DownloadTarget {
        fun existingBytes(): Long
        fun openOutput(append: Boolean): OutputStream
        fun finish()
    }

    /** Android 10+: stream straight into a pending MediaStore entry (no temp file, no second copy). */
    private inner class MediaStoreTarget(
        private val key: String,
        private val file: ManifestFile,
        private val targetDirName: String
    ) : DownloadTarget {
        private val resolver = context.contentResolver
        private val uri: Uri = pendingMediaUris[key]?.takeIf { exists(it) } ?: insert().also { pendingMediaUris[key] = it }

        private fun exists(uri: Uri): Boolean =
            runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

        private fun insert(): Uri {
            val parent = file.relativePath.substringBeforeLast("/", "")
            val subFolder = buildString {
                append(Environment.DIRECTORY_DOWNLOADS).append('/').append(targetDirName)
                if (parent.isNotEmpty()) append('/').append(parent)
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, file.mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, subFolder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Failed to create MediaStore entry for ${file.name}")
        }

        override fun existingBytes(): Long =
            runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)

        override fun openOutput(append: Boolean): OutputStream =
            resolver.openOutputStream(uri, if (append) "wa" else "wt")
                ?: throw IOException("Cannot open output for ${file.name}")

        override fun finish() {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            pendingMediaUris.remove(key)
        }
    }

    /** Android 9 and below: download to a .part file, then move it into Downloads (same volume, so a rename). */
    private inner class LegacyFileTarget(
        private val partFile: File,
        private val file: ManifestFile,
        private val targetDirName: String
    ) : DownloadTarget {
        override fun existingBytes(): Long = if (partFile.exists()) partFile.length() else 0L

        override fun openOutput(append: Boolean): OutputStream = FileOutputStream(partFile, append)

        override fun finish() {
            val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), targetDirName)
            val destFile = File(baseDir, file.relativePath)
            destFile.parentFile?.mkdirs()
            if (destFile.exists()) destFile.delete()
            if (!partFile.renameTo(destFile)) {
                FileInputStream(partFile).use { input ->
                    FileOutputStream(destFile).use { input.copyTo(it, TRANSFER_BUFFER_SIZE) }
                }
                runCatching { partFile.delete() }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1500L
        const val PARALLEL_DOWNLOADS = 3
    }
}
