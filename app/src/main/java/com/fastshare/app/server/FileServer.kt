package com.fastshare.app.server

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.fastshare.app.data.ShareBatch
import com.fastshare.app.data.SharedFileItem
import com.fastshare.app.util.SOCKET_BUFFER_SIZE
import com.fastshare.app.util.TRANSFER_BUFFER_SIZE
import com.fastshare.app.util.PROGRESS_INTERVAL_MS
import com.fastshare.app.util.runOnMain
import com.fastshare.app.util.sanitizeFileName
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ReceivedFile(
    val name: String,
    val size: Long,
    val path: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Embedded HTTP server that serves the share batch and accepts uploads.
 * All callbacks are delivered on the main thread.
 */
class FileServer(
    private val context: Context,
    private val port: Int = 8080,
    private val initialBatch: ShareBatch,
    private val onProgress: (
        fileName: String,
        fileIndex: Int,
        totalFiles: Int,
        fileBytes: Long,
        fileSize: Long,
        overallBytes: Long,
        overallTotal: Long,
        speedMbPerSec: Double
    ) -> Unit,
    var onBatchComplete: (() -> Unit)? = null,
    var onFileReceived: ((fileName: String, fileSize: Long, path: String) -> Unit)? = null,
    var onPeerConnected: ((clientIp: String) -> Unit)? = null,
    var onPeerDisconnected: (() -> Unit)? = null,
    var onUploadProgress: ((fileName: String, bytes: Long, total: Long, speedMbPerSec: Double) -> Unit)? = null
) : NanoHTTPD(port) {

    /** Files received from the peer, observable from Compose. */
    val receivedFilesState = mutableStateListOf<ReceivedFile>()

    @Volatile
    var isPeerConnected: Boolean = false
        private set

    val isPeerConnectedState = mutableStateOf(false)

    @Volatile
    var isDisconnecting: Boolean = false
        private set

    @Volatile
    var lastPeerActivityTime: Long = System.currentTimeMillis()

    private val itemsList = CopyOnWriteArrayList(initialBatch.items)
    private val fileMap = ConcurrentHashMap<String, SharedFileItem>()
    private val overallBytesSent = AtomicLong(0L)
    private val completedFiles: MutableSet<String> = Collections.synchronizedSet(HashSet())
    val receivedFilesList = CopyOnWriteArrayList<ReceivedFile>()

    init {
        initialBatch.items.forEach { fileMap[it.id] = it }
        runCatching {
            serverSocketFactory = ServerSocketFactory {
                ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = SOCKET_BUFFER_SIZE
                }
            }
        }
    }

    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler {
        runCatching {
            finalAccept.tcpNoDelay = true
            finalAccept.sendBufferSize = SOCKET_BUFFER_SIZE
            finalAccept.receiveBufferSize = SOCKET_BUFFER_SIZE
        }
        return super.createClientHandler(finalAccept, inputStream)
    }

    /** Never gzip on the fly: shared .txt/.json files would be compressed by the CPU at LAN speed. */
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    fun notifyDisconnect() {
        if (isDisconnecting) return
        isDisconnecting = true
        setPeerConnected(false)
        runOnMain { onPeerDisconnected?.invoke() }
    }

    /** Adds files to the live queue. Returns how many were new. */
    fun addItems(newItems: List<SharedFileItem>): Int {
        var addedCount = 0
        for (item in newItems) {
            if (fileMap.putIfAbsent(item.id, item) == null) {
                itemsList.add(item)
                addedCount++
            }
        }
        return addedCount
    }

    fun getTotalCount(): Int = itemsList.size
    fun getTotalSize(): Long = itemsList.sumOf { it.size }
    fun getItems(): List<SharedFileItem> = itemsList.toList()
    fun getReceivedFiles(): List<ReceivedFile> = receivedFilesList.toList()

    private fun setPeerConnected(connected: Boolean) {
        isPeerConnected = connected
        runOnMain { isPeerConnectedState.value = connected }
    }

    private fun markPeerConnected(session: IHTTPSession) {
        if (isPeerConnected) return
        setPeerConnected(true)
        val deviceName = session.headers["x-device-name"]
            ?: session.parameters["device"]?.firstOrNull()
            ?: session.remoteIpAddress
            ?: "Peer Device"
        runOnMain { onPeerConnected?.invoke(deviceName) }
    }

    override fun serve(session: IHTTPSession): Response {
        lastPeerActivityTime = System.currentTimeMillis()
        val uri = session.uri

        if (uri == "/disconnect") {
            // Latch the session closed so a late poll from the peer can't mark it connected again.
            notifyDisconnect()
            return json(Response.Status.OK, JSONObject().put("status", "disconnected"))
        }

        if (isDisconnecting) {
            return json(
                Response.Status.GONE,
                JSONObject().put("status", "disconnected").put("message", "Server is disconnected")
            )
        }

        markPeerConnected(session)

        return when {
            uri.startsWith("/upload") && session.method == Method.POST -> handleUpload(session)
            uri == "/received-files" -> serveReceivedFiles()
            uri == "/connect" || uri == "/ping" -> json(
                Response.Status.OK,
                JSONObject()
                    .put("status", "ok")
                    .put("connected", true)
                    .put("serverTitle", initialBatch.title)
                    .put("deviceName", android.os.Build.MODEL)
                    .put("filesCount", itemsList.size)
            )
            uri == "/manifest" || uri == "/info" -> serveManifest()
            uri == "/download" || uri.startsWith("/download/") -> serveDownload(session)
            uri == "/download-zip" -> serveZip()
            else -> newFixedLengthResponse(Response.Status.OK, "text/html", buildIndexHtml(itemsList.toList()))
        }
    }

    // ---------------------------------------------------------------------------------
    // Upload (peer -> this device)
    // ---------------------------------------------------------------------------------

    private fun handleUpload(session: IHTTPSession): Response {
        return try {
            val contentType = session.headers["content-type"].orEmpty()
            val received = if (contentType.startsWith("multipart/", ignoreCase = true)) {
                receiveMultipart(session)
            } else {
                receiveRawStream(session)
            }

            receivedFilesList.add(received)
            runOnMain {
                receivedFilesState.add(received)
                onFileReceived?.invoke(received.name, received.size, received.path)
            }

            json(
                Response.Status.OK,
                JSONObject()
                    .put("status", "success")
                    .put("fileName", received.name)
                    .put("fileSize", received.size)
                    .put("path", received.path)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("status", "error").put("message", e.message ?: "Upload failed")
            )
        }
    }

    private fun uploadFileName(session: IHTTPSession): String {
        val rawName = session.parameters["name"]?.firstOrNull()
            ?: session.headers["x-file-name"]
            ?: session.parameters["file"]?.firstOrNull()
            ?: "received_${System.currentTimeMillis()}"
        return sanitizeFileName(File(rawName).name)
    }

    /** Body is the file itself: streamed from the socket directly to its final location (single disk write). */
    private fun receiveRawStream(session: IHTTPSession): ReceivedFile {
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: throw IOException("Missing Content-Length")
        val safeName = uploadFileName(session)
        val (destFile, out) = openUploadDestination(safeName)

        var written = 0L
        val startTime = System.currentTimeMillis()
        var lastUpdateTime = 0L
        try {
            out.use { output ->
                val input = session.inputStream
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                while (written < length) {
                    val toRead = minOf(buffer.size.toLong(), length - written).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    lastPeerActivityTime = now
                    if (now - lastUpdateTime >= PROGRESS_INTERVAL_MS || written >= length) {
                        lastUpdateTime = now
                        val elapsedSec = (now - startTime) / 1000.0
                        val speed = if (elapsedSec > 0) (written / (1024.0 * 1024.0)) / elapsedSec else 0.0
                        val bytes = written
                        runOnMain { onUploadProgress?.invoke(destFile.name, bytes, length, speed) }
                    }
                }
            }
        } catch (e: Exception) {
            destFile.delete()
            throw e
        }
        if (written < length) {
            destFile.delete()
            throw IOException("Upload truncated: $written of $length bytes")
        }
        return ReceivedFile(destFile.name, written, destFile.absolutePath)
    }

    /** Legacy multipart uploads (older app versions / browsers). */
    private fun receiveMultipart(session: IHTTPSession): ReceivedFile {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tempFile = (files["file"] ?: files["content"] ?: files.values.firstOrNull())?.let(::File)
            ?: throw IOException("No file in upload")
        val safeName = uploadFileName(session)

        try {
            val (destFile, out) = openUploadDestination(safeName)
            out.use { output -> tempFile.inputStream().use { it.copyTo(output, TRANSFER_BUFFER_SIZE) } }
            return ReceivedFile(destFile.name, destFile.length(), destFile.absolutePath)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun openUploadDestination(safeName: String): Pair<File, OutputStream> {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "FastShare/Received"
        )
        val dest = uniqueFile(publicDir.apply { mkdirs() }, safeName)
        return try {
            dest to BufferedOutputStream(FileOutputStream(dest), TRANSFER_BUFFER_SIZE)
        } catch (e: IOException) {
            // Scoped storage may forbid direct writes to public Downloads; fall back to app-specific storage.
            val fallbackDir = File(context.getExternalFilesDir(null), "Received").apply { mkdirs() }
            val fallback = uniqueFile(fallbackDir, safeName)
            fallback to BufferedOutputStream(FileOutputStream(fallback), TRANSFER_BUFFER_SIZE)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = candidate.nameWithoutExtension
        val ext = candidate.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$counter$ext")
            counter++
        }
        return candidate
    }

    private fun serveReceivedFiles(): Response {
        val array = JSONArray()
        for (rec in receivedFilesList) {
            array.put(
                JSONObject()
                    .put("name", rec.name)
                    .put("size", rec.size)
                    .put("path", rec.path)
                    .put("timestamp", rec.timestamp)
            )
        }
        return jsonText(Response.Status.OK, array.toString())
    }

    // ---------------------------------------------------------------------------------
    // Download (this device -> peer)
    // ---------------------------------------------------------------------------------

    private fun serveManifest(): Response {
        val items = itemsList.toList()
        val files = JSONArray()
        for (item in items) {
            files.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("relativePath", item.relativePath)
                    .put("size", item.size)
                    .put("mimeType", item.mimeType)
            )
        }
        val manifest = JSONObject()
            .put("title", initialBatch.title)
            .put("totalFiles", items.size)
            .put("totalSize", items.sumOf { it.size })
            .put("files", files)
        return json(Response.Status.OK, manifest)
    }

    private fun serveDownload(session: IHTTPSession): Response {
        val items = itemsList.toList()
        val id = session.parameters["id"]?.firstOrNull() ?: session.uri.removePrefix("/download/").trim()
        val item = fileMap[id] ?: items.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        val itemIndex = items.indexOf(item)
        val totalFiles = items.size
        val overallTotal = items.sumOf { it.size }

        return try {
            val rawStream = context.contentResolver.openInputStream(item.uri)
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open file: ${item.name}"
                )
            val stream = BufferedInputStream(rawStream, TRANSFER_BUFFER_SIZE)

            val rangeHeader = session.headers["range"]
            var startByte = 0L
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val parsedStart = rangeHeader.removePrefix("bytes=").substringBefore('-').trim().toLongOrNull()
                if (parsedStart != null && parsedStart in 0 until item.size) startByte = parsedStart
            }
            val isRangeRequest = startByte > 0
            if (isRangeRequest) skipFully(stream, startByte)

            val progressStream = ProgressInputStream(stream, item, startByte, itemIndex + 1, totalFiles, overallTotal)
            val status = if (isRangeRequest) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val response = newFixedLengthResponse(
                status,
                item.mimeType.ifEmpty { "application/octet-stream" },
                progressStream,
                item.size - startByte
            )
            response.addHeader("Content-Disposition", contentDisposition(item.name))
            response.addHeader("Accept-Ranges", "bytes")
            if (isRangeRequest) {
                response.addHeader("Content-Range", "bytes $startByte-${item.size - 1}/${item.size}")
            }
            response.addHeader("Access-Control-Allow-Origin", "*")
            response
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private inner class ProgressInputStream(
        input: InputStream,
        private val item: SharedFileItem,
        private val startByte: Long,
        private val fileIndex: Int,
        private val totalFiles: Int,
        private val overallTotal: Long
    ) : FilterInputStream(input) {
        private var fileRead = startByte
        private val startTime = System.currentTimeMillis()
        private var lastUpdateTime = 0L
        private var completed = false

        override fun read(): Int {
            val b = super.read()
            if (b != -1) onBytes(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) onBytes(count.toLong())
            return count
        }

        private fun onBytes(bytesRead: Long) {
            fileRead += bytesRead
            val overall = overallBytesSent.addAndGet(bytesRead)
            val done = fileRead >= item.size
            val now = System.currentTimeMillis()
            lastPeerActivityTime = now
            if (now - lastUpdateTime < PROGRESS_INTERVAL_MS && !done) return
            lastUpdateTime = now

            val elapsedSec = (now - startTime) / 1000.0
            val speed = if (elapsedSec > 0) ((fileRead - startByte) / (1024.0 * 1024.0)) / elapsedSec else 0.0
            val bytes = fileRead
            runOnMain { onProgress(item.name, fileIndex, totalFiles, bytes, item.size, overall, overallTotal, speed) }

            if (done && !completed) {
                completed = true
                completedFiles.add(item.id)
                if (completedFiles.size >= itemsList.size) {
                    runOnMain { onBatchComplete?.invoke() }
                }
            }
        }
    }

    private fun serveZip(): Response {
        val items = itemsList.toList()
        val pipedIn = PipedInputStream(TRANSFER_BUFFER_SIZE)
        val pipedOut = PipedOutputStream(pipedIn)

        Thread {
            try {
                ZipOutputStream(BufferedOutputStream(pipedOut, TRANSFER_BUFFER_SIZE)).use { zipOut ->
                    // Shared media is already compressed; deflating it just burns CPU and caps LAN throughput.
                    zipOut.setLevel(Deflater.NO_COMPRESSION)
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    for (fileItem in items) {
                        zipOut.putNextEntry(ZipEntry(fileItem.relativePath.removePrefix("/")))
                        context.contentResolver.openInputStream(fileItem.uri)?.use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                zipOut.write(buffer, 0, read)
                            }
                        }
                        zipOut.closeEntry()
                    }
                    zipOut.finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                runCatching { pipedOut.close() }
            }
        }.start()

        val zipFileName = "${sanitizeFileName(initialBatch.title)}.zip"
        val response = newChunkedResponse(Response.Status.OK, "application/zip", pipedIn)
        response.addHeader("Content-Disposition", contentDisposition(zipFileName))
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private fun json(status: Response.Status, body: JSONObject): Response = jsonText(status, body.toString())

    private fun jsonText(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }

    private fun contentDisposition(fileName: String): String {
        val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$fileName\"; filename*=UTF-8''$encoded"
    }

    private fun skipFully(inStream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val buffer = ByteArray(32 * 1024)
        while (remaining > 0) {
            val skipped = inStream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = inStream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read == -1) break
                remaining -= read
            }
        }
    }

    private fun buildIndexHtml(items: List<SharedFileItem>): String {
        val filesHtml = StringBuilder()
        for (item in items) {
            filesHtml.append(
                """
                <div class="file-item">
                    <div class="file-info">
                        <div class="file-name">${escapeHtml(item.name)}</div>
                        <div class="file-path">${escapeHtml(item.relativePath)} &bull; ${formatSize(item.size)}</div>
                    </div>
                    <a class="btn-sm" href="/download?id=${item.id}">Download</a>
                </div>
                """.trimIndent()
            )
        }
        val title = escapeHtml(initialBatch.title)

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>FastShare - $title</title>
                <style>$INDEX_CSS</style>
            </head>
            <body>
                <div class="card">
                    <div class="header">
                        <span class="badge">⚡ FastShare P2P</span>
                        <div class="title">$title</div>
                        <div class="meta">${items.size} items &bull; ${formatSize(items.sumOf { it.size })}</div>
                    </div>
                    <a class="btn-primary" href="/download-zip">📦 Download All as ZIP</a>
                    <div class="file-list">
                        $filesHtml
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        val INDEX_CSS = """
            body {
                margin: 0; padding: 20px;
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: #121212; color: #EDEDED;
                display: flex; align-items: center; justify-content: center; min-height: 90vh;
            }
            .card {
                background: #1F1F1F; border-radius: 16px; padding: 28px;
                max-width: 520px; width: 100%; box-shadow: 0 6px 20px rgba(0,0,0,0.4);
                border: 1px solid #2E2E2E;
            }
            .header { text-align: center; margin-bottom: 24px; }
            .badge {
                display: inline-block; background: #17243B; color: #9DB9F0;
                padding: 4px 14px; border-radius: 999px; font-size: 12px; font-weight: bold;
                text-transform: uppercase; margin-bottom: 12px; letter-spacing: 0.05em;
            }
            .title { font-size: 22px; font-weight: 700; margin: 0 0 6px 0; }
            .meta { color: #A3A3A3; font-size: 14px; }
            .btn-primary {
                display: block; width: 100%; box-sizing: border-box; text-align: center;
                background: #2F6BDB;
                color: white; text-decoration: none; padding: 14px 20px;
                border-radius: 12px; font-size: 16px; font-weight: 600;
                margin: 20px 0; transition: background 0.2s;
            }
            .btn-primary:hover { background: #2353B0; }
            .file-list {
                max-height: 280px; overflow-y: auto; background: #1A1A1A;
                border-radius: 12px; padding: 12px; border: 1px solid #2E2E2E;
            }
            .file-item {
                display: flex; align-items: center; justify-content: space-between;
                padding: 10px 8px; border-bottom: 1px solid #2E2E2E;
            }
            .file-item:last-child { border-bottom: none; }
            .file-info { flex: 1; min-width: 0; padding-right: 12px; }
            .file-name { font-size: 14px; font-weight: 600; color: #EDEDED; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .file-path { font-size: 12px; color: #737373; margin-top: 2px; }
            .btn-sm {
                background: #292929; color: #9DB9F0; text-decoration: none;
                padding: 6px 14px; border-radius: 8px; font-size: 12px; font-weight: 600;
                border: 1px solid #2E2E2E; white-space: nowrap;
            }
            .btn-sm:hover { background: #333333; }
        """.trimIndent()
    }
}
