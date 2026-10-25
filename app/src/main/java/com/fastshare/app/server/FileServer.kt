package com.fastshare.app.server

import android.content.Context
import android.os.Environment
import com.fastshare.app.data.ShareBatch
import com.fastshare.app.data.SharedFileItem
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ReceivedFile(
    val name: String,
    val size: Long,
    val path: String,
    val timestamp: Long = System.currentTimeMillis()
)

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
    var onPeerDisconnected: (() -> Unit)? = null
) : NanoHTTPD(port) {

    @Volatile
    var isPeerConnected: Boolean = false
        private set

    val isPeerConnectedState = androidx.compose.runtime.mutableStateOf(false)

    @Volatile
    var isDisconnecting: Boolean = false
        private set

    @Volatile
    var lastPeerActivityTime: Long = System.currentTimeMillis()

    fun notifyDisconnect() {
        if (isDisconnecting) return
        isDisconnecting = true
        isPeerConnected = false
        isPeerConnectedState.value = false
        onPeerDisconnected?.invoke()
    }

    private val itemsList = CopyOnWriteArrayList<SharedFileItem>(initialBatch.items)
    private val fileMap = ConcurrentHashMap<String, SharedFileItem>()
    private var overallBytesSent = 0L
    private val completedFiles = java.util.Collections.synchronizedSet(HashSet<String>())
    val receivedFilesList = CopyOnWriteArrayList<ReceivedFile>()

    init {
        initialBatch.items.forEach { fileMap[it.id] = it }
        try {
            serverSocketFactory = ServerSocketFactory {
                val ss = java.net.ServerSocket()
                ss.reuseAddress = true
                ss.receiveBufferSize = 1024 * 1024 // 1 MB high-speed TCP window buffer
                ss
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Add additional files dynamically to the transfer queue while server is running.
     */
    fun addItems(newItems: List<SharedFileItem>): Int {
        var addedCount = 0
        for (item in newItems) {
            if (!fileMap.containsKey(item.id)) {
                fileMap[item.id] = item
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

    override fun serve(session: IHTTPSession): Response {
        lastPeerActivityTime = System.currentTimeMillis()
        val uri = session.uri
        val parms = session.parameters
        val currentItems = itemsList.toList()

        if (isDisconnecting && uri != "/disconnect") {
            val respObj = JSONObject().apply {
                put("status", "disconnected")
                put("message", "Server is disconnected")
            }
            val resp = newFixedLengthResponse(Response.Status.GONE, "application/json", respObj.toString())
            resp.addHeader("Access-Control-Allow-Origin", "*")
            return resp
        }

        if (uri != "/disconnect" && !isDisconnecting) {
            val remoteIp = session.remoteIpAddress ?: "Peer Device"
            val deviceName = session.headers["x-device-name"] ?: session.parameters["device"]?.firstOrNull() ?: remoteIp
            isPeerConnected = true
            isPeerConnectedState.value = true
            onPeerConnected?.invoke(deviceName)
        }

        return when {
            (uri == "/upload" || uri.startsWith("/upload")) && session.method == Method.POST -> {
                try {
                    val files = HashMap<String, String>()
                    session.parseBody(files)

                    // Find uploaded file location from NanoHTTPD temp files
                    val tempFilePath = files["file"] ?: files["content"] ?: files.values.firstOrNull()

                    val rawName = session.parameters["name"]?.firstOrNull()
                        ?: session.headers["x-file-name"]
                        ?: session.parameters["file"]?.firstOrNull()
                        ?: "received_${System.currentTimeMillis()}"

                    val safeName = File(rawName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = File(downloadsDir, "FastShare/Received").apply { mkdirs() }

                    var destFile = File(targetDir, safeName)
                    var counter = 1
                    val baseName = destFile.nameWithoutExtension
                    val ext = if (destFile.extension.isNotEmpty()) ".${destFile.extension}" else ""
                    while (destFile.exists()) {
                        destFile = File(targetDir, "${baseName}_$counter$ext")
                        counter++
                    }

                    var bytesWritten = 0L
                    if (tempFilePath != null) {
                        val tempFile = File(tempFilePath)
                        if (tempFile.exists()) {
                            try {
                                tempFile.copyTo(destFile, overwrite = true)
                                bytesWritten = destFile.length()
                            } catch (e: Exception) {
                                try {
                                    val fallbackDir = File(context.getExternalFilesDir(null), "Received").apply { mkdirs() }
                                    val fallbackFile = File(fallbackDir, destFile.name)
                                    tempFile.copyTo(fallbackFile, overwrite = true)
                                    destFile = fallbackFile
                                    bytesWritten = destFile.length()
                                } catch (e2: Exception) {
                                    e2.printStackTrace()
                                }
                            }
                            try { tempFile.delete() } catch (ignored: Exception) {}
                        }
                    }

                    val received = ReceivedFile(
                        name = destFile.name,
                        size = bytesWritten,
                        path = destFile.absolutePath
                    )
                    receivedFilesList.add(received)
                    onFileReceived?.invoke(destFile.name, bytesWritten, destFile.absolutePath)

                    val jsonResp = JSONObject().apply {
                        put("status", "success")
                        put("fileName", destFile.name)
                        put("fileSize", bytesWritten)
                        put("path", destFile.absolutePath)
                    }
                    val resp = newFixedLengthResponse(Response.Status.OK, "application/json", jsonResp.toString())
                    resp.addHeader("Access-Control-Allow-Origin", "*")
                    resp
                } catch (e: Exception) {
                    e.printStackTrace()
                    val errResp = JSONObject().apply {
                        put("status", "error")
                        put("message", e.message ?: "Upload failed")
                    }
                    val resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", errResp.toString())
                    resp.addHeader("Access-Control-Allow-Origin", "*")
                    resp
                }
            }

            uri == "/received-files" -> {
                val array = JSONArray()
                for (rec in receivedFilesList) {
                    val obj = JSONObject().apply {
                        put("name", rec.name)
                        put("size", rec.size)
                        put("path", rec.path)
                        put("timestamp", rec.timestamp)
                    }
                    array.put(obj)
                }
                val resp = newFixedLengthResponse(Response.Status.OK, "application/json", array.toString())
                resp.addHeader("Access-Control-Allow-Origin", "*")
                resp
            }

            uri == "/connect" || uri == "/ping" -> {
                isPeerConnected = true
                val remoteIp = session.remoteIpAddress ?: "Peer Device"
                val deviceName = session.headers["x-device-name"] ?: session.parameters["device"]?.firstOrNull() ?: remoteIp
                onPeerConnected?.invoke(deviceName)
                val respObj = JSONObject().apply {
                    put("status", "ok")
                    put("connected", true)
                    put("serverTitle", initialBatch.title)
                    put("deviceName", android.os.Build.MODEL)
                    put("filesCount", currentItems.size)
                }
                val resp = newFixedLengthResponse(Response.Status.OK, "application/json", respObj.toString())
                resp.addHeader("Access-Control-Allow-Origin", "*")
                resp
            }

            uri == "/disconnect" -> {
                isPeerConnected = false
                isPeerConnectedState.value = false
                onPeerDisconnected?.invoke()
                val respObj = JSONObject().apply {
                    put("status", "disconnected")
                }
                val resp = newFixedLengthResponse(Response.Status.OK, "application/json", respObj.toString())
                resp.addHeader("Access-Control-Allow-Origin", "*")
                resp
            }

            uri == "/manifest" || uri == "/info" -> {
                if (!isPeerConnected) {
                    isPeerConnected = true
                    val deviceName = session.headers["x-device-name"] ?: session.parameters["device"]?.firstOrNull() ?: (session.remoteIpAddress ?: "Peer Device")
                    onPeerConnected?.invoke(deviceName)
                }
                val jsonArray = JSONArray()
                for (item in currentItems) {
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("relativePath", item.relativePath)
                        put("size", item.size)
                        put("mimeType", item.mimeType)
                    }
                    jsonArray.put(obj)
                }

                val manifest = JSONObject().apply {
                    put("title", initialBatch.title)
                    put("totalFiles", currentItems.size)
                    put("totalSize", currentItems.sumOf { it.size })
                    put("files", jsonArray)
                }

                val resp = newFixedLengthResponse(Response.Status.OK, "application/json", manifest.toString())
                resp.addHeader("Access-Control-Allow-Origin", "*")
                resp
            }

            uri == "/download" || uri.startsWith("/download/") -> {
                val id = parms["id"]?.firstOrNull() ?: uri.removePrefix("/download/").trim()
                val item = fileMap[id] ?: currentItems.firstOrNull()

                if (item == null) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
                }

                val itemIndex = currentItems.indexOf(item)

                try {
                    val rawStream = context.contentResolver.openInputStream(item.uri)
                        ?: return newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            "Cannot open file: ${item.name}"
                        )
                    val bufferedStream = java.io.BufferedInputStream(rawStream, 256 * 1024)

                    // Check for HTTP Range header (resumable download)
                    val rangeHeader = session.headers["range"] ?: session.headers["Range"]
                    var startByte = 0L
                    var isRangeRequest = false

                    if (!rangeHeader.isNullOrEmpty() && rangeHeader.startsWith("bytes=")) {
                        val rangeVal = rangeHeader.removePrefix("bytes=").trim()
                        val parts = rangeVal.split("-")
                        val parsedStart = parts[0].toLongOrNull()
                        if (parsedStart != null && parsedStart in 0 until item.size) {
                            startByte = parsedStart
                            isRangeRequest = true
                        }
                    }

                    if (isRangeRequest && startByte > 0) {
                        skipFully(bufferedStream, startByte)
                    }

                    val sendSize = item.size - startByte
                    val startTime = System.currentTimeMillis()
                    var lastUpdateTime = startTime
                    var lastBytes = startByte

                    val progressStream = object : FilterInputStream(bufferedStream) {
                        var fileRead = startByte

                        override fun read(): Int {
                            val b = super.read()
                            if (b != -1) update(1)
                            return b
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val count = super.read(b, off, len)
                            if (count > 0) update(count.toLong())
                            return count
                        }

                        private fun update(bytesRead: Long) {
                            fileRead += bytesRead
                            overallBytesSent += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime >= 250 || fileRead >= item.size) {
                                val elapsedSec = (now - startTime) / 1000.0
                                val speed = if (elapsedSec > 0) ((fileRead - startByte) / (1024.0 * 1024.0)) / elapsedSec else 0.0

                                onProgress(
                                    item.name,
                                    itemIndex + 1,
                                    currentItems.size,
                                    fileRead,
                                    item.size,
                                    overallBytesSent,
                                    currentItems.sumOf { it.size },
                                    speed
                                )
                                lastUpdateTime = now
                                lastBytes = fileRead

                                if (fileRead >= item.size) {
                                    completedFiles.add(item.id)
                                    if (completedFiles.size >= currentItems.size) {
                                        onBatchComplete?.invoke()
                                    }
                                }
                            }
                        }
                    }

                    val encodedName = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
                    val status = if (isRangeRequest) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                    val response = newFixedLengthResponse(
                        status,
                        item.mimeType.ifEmpty { "application/octet-stream" },
                        progressStream,
                        sendSize
                    )
                    response.addHeader(
                        "Content-Disposition",
                        "attachment; filename=\"${item.name}\"; filename*=UTF-8''$encodedName"
                    )
                    response.addHeader("Accept-Ranges", "bytes")
                    if (isRangeRequest) {
                        val endByte = item.size - 1
                        response.addHeader("Content-Range", "bytes $startByte-$endByte/${item.size}")
                    }
                    response.addHeader("Access-Control-Allow-Origin", "*")
                    response
                } catch (e: Exception) {
                    e.printStackTrace()
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
                }
            }

            uri == "/download-zip" -> {
                // Stream dynamic ZIP containing all files preserving relative folder paths
                val pipedIn = PipedInputStream(64 * 1024)
                val pipedOut = PipedOutputStream(pipedIn)

                Thread {
                    try {
                        ZipOutputStream(BufferedOutputStream(pipedOut)).use { zipOut ->
                            val buffer = ByteArray(64 * 1024)
                            for (fileItem in currentItems) {
                                val entryPath = fileItem.relativePath.removePrefix("/")
                                val entry = ZipEntry(entryPath)
                                zipOut.putNextEntry(entry)

                                context.contentResolver.openInputStream(fileItem.uri)?.use { inStream ->
                                    var readBytes: Int
                                    while (inStream.read(buffer).also { readBytes = it } != -1) {
                                        zipOut.write(buffer, 0, readBytes)
                                    }
                                }
                                zipOut.closeEntry()
                            }
                            zipOut.finish()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try { pipedOut.close() } catch (ignored: Exception) {}
                    }
                }.start()

                val zipFileName = "${initialBatch.title.replace(Regex("[^a-zA-Z0-9_.-]"), "_")}.zip"
                val encodedZipName = URLEncoder.encode(zipFileName, "UTF-8").replace("+", "%20")
                val response = newChunkedResponse(Response.Status.OK, "application/zip", pipedIn)
                response.addHeader("Content-Disposition", "attachment; filename=\"$zipFileName\"; filename*=UTF-8''$encodedZipName")
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }

            else -> {
                // Serves interactive HTML page with all files and ZIP download
                val formattedTotalSize = formatSize(currentItems.sumOf { it.size })
                val filesHtml = StringBuilder()

                for (item in currentItems) {
                    val sizeStr = formatSize(item.size)
                    filesHtml.append("""
                        <div class="file-item">
                            <div class="file-info">
                                <div class="file-name">${escapeHtml(item.name)}</div>
                                <div class="file-path">${escapeHtml(item.relativePath)} &bull; $sizeStr</div>
                            </div>
                            <a class="btn-sm" href="/download?id=${item.id}">Download</a>
                        </div>
                    """.trimIndent())
                }

                val html = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>FastShare - ${escapeHtml(initialBatch.title)}</title>
                        <style>
                            body {
                                margin: 0; padding: 20px;
                                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                background: #090C12; color: #f8fafc;
                                display: flex; align-items: center; justify-content: center; min-height: 90vh;
                            }
                            .card {
                                background: #161D2B; border-radius: 20px; padding: 28px;
                                max-width: 520px; width: 100%; box-shadow: 0 10px 30px rgba(0,0,0,0.6);
                                border: 1px solid #28334A;
                            }
                            .header { text-align: center; margin-bottom: 24px; }
                            .badge {
                                display: inline-block; background: #E11D48; color: #ffffff;
                                padding: 4px 14px; border-radius: 999px; font-size: 12px; font-weight: bold;
                                text-transform: uppercase; margin-bottom: 12px; letter-spacing: 0.05em;
                            }
                            .title { font-size: 22px; font-weight: 700; margin: 0 0 6px 0; }
                            .meta { color: #94a3b8; font-size: 14px; }
                            .btn-primary {
                                display: block; width: 100%; box-sizing: border-box; text-align: center;
                                background: linear-gradient(135deg, #FF3366, #E11D48);
                                color: white; text-decoration: none; padding: 14px 20px;
                                border-radius: 14px; font-size: 16px; font-weight: 700;
                                margin: 20px 0; transition: opacity 0.2s; box-shadow: 0 4px 14px rgba(225,29,72,0.4);
                            }
                            .btn-primary:hover { opacity: 0.9; }
                            .file-list {
                                max-height: 280px; overflow-y: auto; background: #101520;
                                border-radius: 12px; padding: 12px; border: 1px solid #1F273A;
                            }
                            .file-item {
                                display: flex; align-items: center; justify-content: space-between;
                                padding: 10px 8px; border-bottom: 1px solid #1F273A;
                            }
                            .file-item:last-child { border-bottom: none; }
                            .file-info { flex: 1; min-width: 0; padding-right: 12px; }
                            .file-name { font-size: 14px; font-weight: 600; color: #f1f5f9; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                            .file-path { font-size: 12px; color: #64748b; margin-top: 2px; }
                            .btn-sm {
                                background: #1F273A; color: #FF3366; text-decoration: none;
                                padding: 6px 14px; border-radius: 8px; font-size: 12px; font-weight: 700;
                                border: 1px solid #28334A; white-space: nowrap;
                            }
                            .btn-sm:hover { background: #28334A; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <div class="header">
                                <span class="badge">⚡ FastShare P2P</span>
                                <div class="title">${escapeHtml(initialBatch.title)}</div>
                                <div class="meta">${currentItems.size} items &bull; $formattedTotalSize</div>
                            </div>
                            <a class="btn-primary" href="/download-zip">📦 Download All as ZIP</a>
                            <div class="file-list">
                                $filesHtml
                            </div>
                        </div>
                    </body>
                    </html>
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }
        }
    }

    private fun skipFully(inStream: java.io.InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val buffer = ByteArray(32 * 1024)
        while (remaining > 0) {
            val skipped = inStream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                val read = inStream.read(buffer, 0, toRead)
                if (read == -1) break
                remaining -= read
            }
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
