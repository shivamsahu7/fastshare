package com.fastshare.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.fastshare.app.client.FileDownloader
import com.fastshare.app.client.FileUploader
import com.fastshare.app.client.WifiConnector
import com.fastshare.app.data.*
import com.fastshare.app.qr.QrCodeGenerator
import com.fastshare.app.qr.QrScannerView
import com.fastshare.app.server.FileServer
import com.fastshare.app.server.HotspotManager
import com.fastshare.app.server.ReceivedFile
import com.fastshare.app.service.TransferService
import com.fastshare.app.ui.components.DisconnectConfirmationDialog
import com.fastshare.app.ui.components.TransferringSheet
import com.fastshare.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class CategoryTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val accentColor: Color) {
    HISTORY("History", Icons.Default.History, XenderOrange),
    APPS("Apps", Icons.Default.Apps, XenderGreen),
    PHOTOS("Photos", Icons.Default.PhotoLibrary, RubyRedLight),
    VIDEOS("Videos", Icons.Default.VideoLibrary, AccentCyan),
    MUSIC("Music", Icons.Default.Audiotrack, AccentPurple),
    FILES("Files", Icons.Default.Folder, AccentAmber),
    CONTACTS("Contacts", Icons.Default.Contacts, AccentEmerald)
}

private fun checkMediaImagesPermission(context: Context): Boolean {
    return when {
        Build.VERSION.SDK_INT >= 34 -> {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED ||
            Environment.isExternalStorageManager()
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        }
        else -> {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        }
    }
}

@Composable
fun SendScreen(
    hotspotManager: HotspotManager,
    wifiConnector: WifiConnector,
    fileDownloader: FileDownloader,
    fileServer: FileServer?,
    transferState: TransferState,
    onStartServer: (server: FileServer, qr: android.graphics.Bitmap, state: TransferState) -> Unit,
    onStopSharing: () -> Unit,
    onNavigateToQrSharing: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaRepo = remember { MediaRepository(context) }
    val fileUploader = remember { FileUploader(context) }

    val categoryTabs = remember { CategoryTab.values() }
    val pagerState = rememberPagerState(
        initialPage = CategoryTab.APPS.ordinal,
        pageCount = { categoryTabs.size }
    )
    val tabListState = rememberLazyListState()

    var selectedTab by remember { mutableStateOf(CategoryTab.APPS) }
    val selectedItems = remember { mutableStateMapOf<String, SharedFileItem>() }
    var searchQuery by remember { mutableStateOf("") }

    // Media datasets
    var appsList by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var photosList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var videosList by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var audiosList by remember { mutableStateOf<List<AudioItem>>(emptyList()) }
    var contactsList by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var isLoadingMedia by remember { mutableStateOf(false) }

    // Storage Explorer State
    var storageDrives by remember { mutableStateOf<List<StorageDrive>>(emptyList()) }
    var currentDirectory by remember { mutableStateOf<File?>(null) }
    var directoryFiles by remember { mutableStateOf<List<FileExplorerItem>>(emptyList()) }
    var isLoadingDirectory by remember { mutableStateOf(false) }

    // Connection & Transfer States
    var activeFileServer by remember { mutableStateOf<FileServer?>(null) }
    var isHostConnected by remember { mutableStateOf(false) }
    var isClientConnected by remember { mutableStateOf(false) }
    var connectedHostUrl by remember { mutableStateOf("http://192.168.43.1:8080") }
    var clientTransferState by remember { mutableStateOf<TransferState>(TransferState.Idle) }
    val serverReceivedFiles = remember { mutableStateListOf<ReceivedFile>() }
    val clientReceivedFiles = remember { mutableStateListOf<ReceivedFile>() }

    var currentQrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var currentHotspotSsid by remember { mutableStateOf("") }
    var currentHotspotPassword by remember { mutableStateOf("") }

    // Dialog & Sheet Visibility
    var showQrModal by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showTransferringSheet by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var peerDeviceName by remember { mutableStateOf("") }
    val sessionTransferRecords = remember { mutableStateListOf<TransferRecord>() }

    val currentServer = activeFileServer ?: fileServer
    val isPeerConnectedReactive = (currentServer?.isPeerConnectedState?.value == true) || (currentServer?.isPeerConnected == true)
    val isConnected = isHostConnected || isClientConnected || isPeerConnectedReactive
    val activeTransferState = if (isClientConnected) clientTransferState else transferState

    // Permission check for Photos & Videos (including Android 14 visual user selected)
    var hasMediaPermission by remember { mutableStateOf(checkMediaImagesPermission(context)) }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasMediaPermission = checkMediaImagesPermission(context) || grants.values.any { it }
        coroutineScope.launch {
            photosList = mediaRepo.loadPhotos()
            videosList = mediaRepo.loadVideos()
            audiosList = mediaRepo.loadAudios()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Camera permission needed to scan QR code", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val tab = categoryTabs[pagerState.currentPage]
        if (selectedTab != tab) {
            selectedTab = tab
            searchQuery = ""
        }
        tabListState.animateScrollToItem(pagerState.currentPage)
    }

    LaunchedEffect(Unit) {
        if (appsList.isEmpty()) appsList = mediaRepo.loadInstalledApps()
    }

    // Load data based on tab
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            CategoryTab.APPS -> if (appsList.isEmpty()) appsList = mediaRepo.loadInstalledApps()
            CategoryTab.PHOTOS -> if (photosList.isEmpty()) photosList = mediaRepo.loadPhotos()
            CategoryTab.VIDEOS -> if (videosList.isEmpty()) videosList = mediaRepo.loadVideos()
            CategoryTab.MUSIC -> if (audiosList.isEmpty()) audiosList = mediaRepo.loadAudios()
            CategoryTab.FILES -> {
                if (storageDrives.isEmpty()) {
                    storageDrives = mediaRepo.getStorageDrives()
                    if (currentDirectory == null && storageDrives.isNotEmpty()) {
                        currentDirectory = storageDrives.first().rootFile
                    }
                }
            }
            CategoryTab.CONTACTS -> if (contactsList.isEmpty()) contactsList = mediaRepo.loadContacts()
            CategoryTab.HISTORY -> {}
        }
    }

    // Load directory files when currentDirectory changes
    LaunchedEffect(currentDirectory) {
        val dir = currentDirectory
        if (dir != null && dir.exists() && dir.isDirectory) {
            isLoadingDirectory = true
            directoryFiles = mediaRepo.listDirectory(dir)
            isLoadingDirectory = false
        }
    }

    val totalSelectedSize = selectedItems.values.sumOf { it.size }
    val totalSelectedCount = selectedItems.size

    val liveProgress: Float = when (val state = activeTransferState) {
        is TransferState.Transferring -> state.progress
        is TransferState.Completed -> 1f
        else -> 0f
    }

    val liveSpeed: Double = when (val state = activeTransferState) {
        is TransferState.Transferring -> state.speedMbPerSec
        else -> 0.0
    }

    fun disconnectDevice(notifyPeer: Boolean = true, toastMessage: String = "Device Disconnected") {
        val s = activeFileServer ?: fileServer
        if (s != null) {
            if (notifyPeer) {
                s.notifyDisconnect()
            }
            coroutineScope.launch {
                if (notifyPeer) {
                    kotlinx.coroutines.delay(500)
                }
                try { s.stop() } catch (ignored: Exception) {}
                hotspotManager.stopHotspot()
                onStopSharing()
                activeFileServer = null
            }
        }
        if (isClientConnected) {
            if (notifyPeer && connectedHostUrl.isNotEmpty()) {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val discUrl = java.net.URL("$connectedHostUrl/disconnect")
                        val conn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && wifiConnector.activeNetwork != null) {
                            wifiConnector.activeNetwork!!.openConnection(discUrl) as java.net.HttpURLConnection
                        } else {
                            discUrl.openConnection() as java.net.HttpURLConnection
                        }
                        conn.connectTimeout = 1500
                        conn.readTimeout = 1500
                        conn.requestMethod = "GET"
                        conn.responseCode
                    } catch (ignored: Exception) {}
                }
            }
            wifiConnector.disconnect()
        }
        fileDownloader.activeNetwork = null
        fileUploader.activeNetwork = null
        isHostConnected = false
        isClientConnected = false
        peerDeviceName = ""
        clientTransferState = TransferState.Idle
        showTransferringSheet = false
        selectedItems.clear()
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(wifiConnector) {
        wifiConnector.onNetworkLost = {
            if (isClientConnected) {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    disconnectDevice(notifyPeer = false, toastMessage = "Peer device disconnected")
                }
            }
        }
    }

    LaunchedEffect(isHostConnected, activeFileServer) {
        if (isHostConnected) {
            while (isHostConnected) {
                kotlinx.coroutines.delay(3000)
                val s = activeFileServer ?: fileServer
                if (s != null && s.isPeerConnected) {
                    val silenceDuration = System.currentTimeMillis() - s.lastPeerActivityTime
                    if (silenceDuration > 60000L) {
                        disconnectDevice(notifyPeer = false, toastMessage = "Peer device disconnected")
                        break
                    }
                }
            }
        }
    }

    LaunchedEffect(transferState) {
        if (transferState is TransferState.HotspotActive) {
            if (currentHotspotSsid.isEmpty()) currentHotspotSsid = transferState.ssid
            if (currentHotspotPassword.isEmpty()) currentHotspotPassword = transferState.password
            if (currentQrBitmap == null) {
                val qrJson = org.json.JSONObject().apply {
                    put("ssid", transferState.ssid)
                    put("password", transferState.password)
                    put("serverUrl", transferState.serverUrl)
                    put("type", "fastshare_hotspot")
                }.toString()
                currentQrBitmap = com.fastshare.app.qr.QrCodeGenerator.generate(qrJson, 600)
            }
        }
    }

    LaunchedEffect(showQrModal, activeFileServer, fileServer) {
        if (showQrModal) {
            val server = activeFileServer ?: fileServer
            server?.onPeerConnected = { remoteName ->
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    isHostConnected = true
                    if (remoteName.isNotBlank()) {
                        peerDeviceName = remoteName
                    }
                    showQrModal = false
                    showQrScanner = false
                }
            }
            while (showQrModal) {
                kotlinx.coroutines.delay(200)
                val curServer = activeFileServer ?: fileServer
                if (curServer != null && curServer.isPeerConnected) {
                    isHostConnected = true
                    if (peerDeviceName.isBlank()) {
                        peerDeviceName = "Connected Device"
                    }
                    showQrModal = false
                    showQrScanner = false
                    break
                }
            }
        }
    }

    LaunchedEffect(activeFileServer, fileServer) {
        val server = activeFileServer ?: fileServer
        if (server != null) {
            server.onPeerConnected = { remoteName ->
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    isHostConnected = true
                    if (remoteName.isNotBlank()) {
                        peerDeviceName = remoteName
                    }
                    showQrModal = false
                    showQrScanner = false
                }
            }
            if (server.isPeerConnected) {
                isHostConnected = true
                showQrModal = false
                showQrScanner = false
            }
        }
    }

    LaunchedEffect(isPeerConnectedReactive) {
        if (isPeerConnectedReactive) {
            isHostConnected = true
            showQrModal = false
            showQrScanner = false
        }
    }

    LaunchedEffect(isHostConnected, isClientConnected, isConnected) {
        if (isHostConnected || isClientConnected || isConnected) {
            showQrModal = false
            showQrScanner = false
        }
    }

    // Flow Functions
    fun startSenderSharing() {
        val itemsList = selectedItems.values.toList()
        val batch = ShareBatch(
            title = if (itemsList.isEmpty()) "FastShare Ready" else if (itemsList.size == 1) itemsList.first().name else "${itemsList.size} Items",
            items = itemsList
        )

        hotspotManager.startHotspot(object : HotspotManager.Callback {
            override fun onHotspotStarted(ssid: String, password: String, localIp: String) {
                val serverPort = 8080
                val serverUrl = "http://$localIp:$serverPort"
                currentHotspotSsid = ssid
                currentHotspotPassword = password

                // Initialize transfer records for this session
                batch.items.forEach { item ->
                    if (sessionTransferRecords.none { it.id == item.id }) {
                        sessionTransferRecords.add(
                            TransferRecord(
                                id = item.id,
                                name = item.name,
                                size = item.size,
                                direction = TransferDirection.SENDING,
                                status = TransferItemStatus.QUEUED
                            )
                        )
                    }
                }

                var serverInstance: FileServer? = null
                val server = FileServer(
                    context = context,
                    port = serverPort,
                    initialBatch = batch,
                    onProgress = { fileName, fileIndex, totalFilesCount, currentBytes, currentSize, overallSent, overallTotal, speed ->
                        val progress = if (overallTotal > 0) (overallSent.toFloat() / overallTotal.toFloat()).coerceIn(0f, 1f) else 0f
                        val progressState = TransferState.Transferring(
                            currentFileName = fileName,
                            currentFileIndex = fileIndex,
                            totalFiles = totalFilesCount,
                            currentFileBytes = currentBytes,
                            currentFileSize = currentSize,
                            overallBytes = overallSent,
                            overallTotalBytes = overallTotal,
                            speedMbPerSec = speed,
                            progress = progress
                        )
                        val currentQr = currentQrBitmap ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                        serverInstance?.let { s -> onStartServer(s, currentQr, progressState) }

                        val existingIndex = sessionTransferRecords.indexOfFirst {
                            it.name == fileName && it.direction == TransferDirection.SENDING
                        }
                        if (existingIndex >= 0) {
                            val cur = sessionTransferRecords[existingIndex]
                            val fileProgress = if (currentSize > 0) (currentBytes.toFloat() / currentSize.toFloat()).coerceIn(0f, 1f) else 0f
                            val status = if (currentBytes >= currentSize && currentSize > 0) TransferItemStatus.COMPLETED else TransferItemStatus.TRANSFERRING
                            sessionTransferRecords[existingIndex] = cur.copy(
                                status = status,
                                bytesTransferred = currentBytes,
                                progress = fileProgress,
                                speedMbPerSec = speed
                            )
                        }

                        val percent = (progress * 100).toInt()
                        TransferService.update(
                            context,
                            title = "FastShare: Sending ($percent%)",
                            message = "$fileIndex/$totalFilesCount: $fileName • ${String.format("%.1f", speed)} MB/s",
                            progress = percent
                        )
                    },
                    onBatchComplete = {
                        val completedState = TransferState.Completed(
                            title = batch.title,
                            fileCount = serverInstance?.getTotalCount() ?: batch.totalCount,
                            totalBytes = serverInstance?.getTotalSize() ?: batch.totalSize
                        )
                        val currentQr = currentQrBitmap ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                        serverInstance?.let { s -> onStartServer(s, currentQr, completedState) }

                        sessionTransferRecords.indices.forEach { idx ->
                            val cur = sessionTransferRecords[idx]
                            if (cur.direction == TransferDirection.SENDING && cur.status != TransferItemStatus.COMPLETED) {
                                sessionTransferRecords[idx] = cur.copy(
                                    status = TransferItemStatus.COMPLETED,
                                    progress = 1f,
                                    bytesTransferred = cur.size
                                )
                            }
                        }

                        TransferService.update(
                            context,
                            title = "FastShare: Transfer Complete",
                            message = "Sent ${serverInstance?.getTotalCount() ?: batch.totalCount} file(s) successfully",
                            progress = 100
                        )
                    }
                )
                serverInstance = server
                activeFileServer = server

                server.onPeerConnected = { remoteName ->
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        isHostConnected = true
                        peerDeviceName = remoteName
                        showQrModal = false
                        showQrScanner = false
                    }
                }
                server.onPeerDisconnected = {
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        disconnectDevice(notifyPeer = false, toastMessage = "Peer device disconnected")
                    }
                }
                server.onFileReceived = { name, size, path ->
                    serverReceivedFiles.add(ReceivedFile(name, size, path))
                    sessionTransferRecords.add(
                        TransferRecord(
                            name = name,
                            size = size,
                            direction = TransferDirection.RECEIVING,
                            status = TransferItemStatus.COMPLETED,
                            bytesTransferred = size,
                            progress = 1f,
                            path = path
                        )
                    )
                }

                try {
                    server.start()

                    TransferService.start(
                        context,
                        title = "FastShare: Sharing Active",
                        message = "Hotspot active. Screen can be locked safely."
                    )

                    val qrJson = JSONObject().apply {
                        put("ssid", ssid)
                        put("password", password)
                        put("serverUrl", serverUrl)
                        put("title", batch.title)
                        put("fileCount", batch.totalCount)
                        put("totalSize", batch.totalSize)
                    }.toString()

                    val qr = QrCodeGenerator.generate(qrJson, 600)
                    val safeQr = qr ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    currentQrBitmap = safeQr

                    val activeState = TransferState.HotspotActive(
                        ssid = ssid,
                        password = password,
                        serverUrl = serverUrl,
                        title = batch.title,
                        fileCount = batch.totalCount,
                        totalSize = batch.totalSize
                    )
                    onStartServer(server, safeQr, activeState)
                    showQrModal = true
                } catch (e: Exception) {
                    val emptyBmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    onStartServer(server, emptyBmp, TransferState.Error("Server start failed: ${e.message}"))
                }
            }

            override fun onHotspotFailed(reason: String) {
                val emptyBmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                val dummyServer = FileServer(context, 8080, batch, { _, _, _, _, _, _, _, _ -> })
                onStartServer(dummyServer, emptyBmp, TransferState.Error("Failed to start hotspot: $reason"))
            }

            override fun onHotspotStopped() {
                isHostConnected = false
                onStopSharing()
            }
        })
    }

    fun startReceiverFlow() {
        if (hasCameraPermission) {
            showQrScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun startClientDownload(serverUrl: String) {
        clientTransferState = TransferState.Starting("Fetching files manifest...")
        coroutineScope.launch {
            fileDownloader.downloadBatch(
                serverBaseUrl = serverUrl,
                callback = object : FileDownloader.BatchDownloadCallback {
                    override fun onProgress(
                        currentFileName: String,
                        currentFileIndex: Int,
                        totalFiles: Int,
                        currentFileBytes: Long,
                        currentFileSize: Long,
                        overallBytes: Long,
                        overallTotalBytes: Long,
                        speedMbPerSec: Double,
                        progress: Float
                    ) {
                        clientTransferState = TransferState.Transferring(
                            currentFileName = currentFileName,
                            currentFileIndex = currentFileIndex,
                            totalFiles = totalFiles,
                            currentFileBytes = currentFileBytes,
                            currentFileSize = currentFileSize,
                            overallBytes = overallBytes,
                            overallTotalBytes = overallTotalBytes,
                            speedMbPerSec = speedMbPerSec,
                            progress = progress
                        )

                        val existingIndex = sessionTransferRecords.indexOfFirst {
                            it.name == currentFileName && it.direction == TransferDirection.RECEIVING
                        }
                        if (existingIndex >= 0) {
                            val cur = sessionTransferRecords[existingIndex]
                            sessionTransferRecords[existingIndex] = cur.copy(
                                status = TransferItemStatus.TRANSFERRING,
                                bytesTransferred = currentFileBytes,
                                progress = progress,
                                speedMbPerSec = speedMbPerSec
                            )
                        } else {
                            sessionTransferRecords.add(
                                0,
                                TransferRecord(
                                    name = currentFileName,
                                    size = currentFileSize,
                                    direction = TransferDirection.RECEIVING,
                                    status = TransferItemStatus.TRANSFERRING,
                                    bytesTransferred = currentFileBytes,
                                    progress = progress,
                                    speedMbPerSec = speedMbPerSec
                                )
                            )
                        }
                    }

                    override fun onFileDownloaded(fileName: String, fileSize: Long, filePath: String) {
                        if (clientReceivedFiles.none { it.path == filePath }) {
                            clientReceivedFiles.add(ReceivedFile(fileName, fileSize, filePath))
                        }
                        val existingIndex = sessionTransferRecords.indexOfFirst {
                            it.name == fileName && it.direction == TransferDirection.RECEIVING
                        }
                        if (existingIndex >= 0) {
                            val cur = sessionTransferRecords[existingIndex]
                            sessionTransferRecords[existingIndex] = cur.copy(
                                status = TransferItemStatus.COMPLETED,
                                bytesTransferred = fileSize,
                                progress = 1f,
                                path = filePath
                            )
                        } else {
                            sessionTransferRecords.add(
                                TransferRecord(
                                    name = fileName,
                                    size = fileSize,
                                    direction = TransferDirection.RECEIVING,
                                    status = TransferItemStatus.COMPLETED,
                                    bytesTransferred = fileSize,
                                    progress = 1f,
                                    path = filePath
                                )
                            )
                        }
                    }

                    override fun onBatchSuccess(
                        title: String,
                        fileCount: Int,
                        totalBytes: Long,
                        targetDirectory: String
                    ) {
                        clientTransferState = TransferState.Completed(
                            title = title,
                            fileCount = fileCount,
                            totalBytes = totalBytes,
                            destinationPath = targetDirectory
                        )
                        val targetDir = File(targetDirectory)
                        if (targetDir.exists() && targetDir.isDirectory) {
                            targetDir.listFiles()?.forEach { f ->
                                if (clientReceivedFiles.none { it.path == f.absolutePath }) {
                                    clientReceivedFiles.add(ReceivedFile(f.name, f.length(), f.absolutePath))
                                }
                            }
                        }
                    }

                    override fun onError(message: String) {
                        clientTransferState = TransferState.Error(message)
                        val activeIdx = sessionTransferRecords.indexOfFirst {
                            it.status == TransferItemStatus.TRANSFERRING && it.direction == TransferDirection.RECEIVING
                        }
                        if (activeIdx >= 0) {
                            val cur = sessionTransferRecords[activeIdx]
                            sessionTransferRecords[activeIdx] = cur.copy(
                                status = TransferItemStatus.FAILED,
                                errorMessage = message
                            )
                        }
                    }
                }
            )
        }
    }

    fun handleQrScanned(rawContent: String) {
        showQrScanner = false
        try {
            val json = JSONObject(rawContent)
            val ssid = json.getString("ssid")
            val password = json.getString("password")
            val serverUrl = json.getString("serverUrl")
            connectedHostUrl = serverUrl

            clientTransferState = TransferState.Starting("Connecting to Wi-Fi ($ssid)...")
            wifiConnector.connectToHotspot(
                ssid = ssid,
                password = password,
                callback = object : WifiConnector.ConnectionCallback {
                    override fun onConnected(network: Network?) {
                        fileDownloader.activeNetwork = network
                        fileUploader.activeNetwork = network
                        clientTransferState = TransferState.Starting("Connecting to sender...")

                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val uriPort = try {
                                val parsed = android.net.Uri.parse(serverUrl)
                                if (parsed.port > 0) parsed.port else 8080
                            } catch (e: Exception) {
                                8080
                            }

                            val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
                            var resolvedUrl: String? = null
                            var remoteModel = "Peer Device"
                            var filesCount = 0

                            for (attempt in 1..15) {
                                var dynamicGateway: String? = null
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && network != null) {
                                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                                        val linkProps = cm?.getLinkProperties(network)
                                        if (linkProps != null) {
                                            for (route in linkProps.routes) {
                                                val gw = route.gateway
                                                if (gw is java.net.Inet4Address && !gw.isLoopbackAddress && !gw.isAnyLocalAddress) {
                                                    val host = gw.hostAddress
                                                    if (!host.isNullOrEmpty() && host != "0.0.0.0") {
                                                        dynamicGateway = host
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }

                                if (dynamicGateway.isNullOrEmpty()) {
                                    try {
                                        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                                        val dhcp = wm?.dhcpInfo
                                        if (dhcp != null && dhcp.gateway != 0) {
                                            val gwInt = dhcp.gateway
                                            dynamicGateway = String.format(
                                                java.util.Locale.US,
                                                "%d.%d.%d.%d",
                                                gwInt and 0xff,
                                                (gwInt shr 8) and 0xff,
                                                (gwInt shr 16) and 0xff,
                                                (gwInt shr 24) and 0xff
                                            )
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }

                                val candidateUrls = LinkedHashSet<String>()
                                if (!dynamicGateway.isNullOrEmpty()) {
                                    candidateUrls.add("http://$dynamicGateway:$uriPort")
                                }
                                if (serverUrl.isNotEmpty()) {
                                    candidateUrls.add(serverUrl)
                                }
                                candidateUrls.add("http://192.168.43.1:$uriPort")
                                candidateUrls.add("http://192.168.49.1:$uriPort")
                                candidateUrls.add("http://192.168.44.1:$uriPort")
                                candidateUrls.add("http://192.168.50.1:$uriPort")

                                for (candidate in candidateUrls) {
                                    try {
                                        val connUrl = java.net.URL("$candidate/connect")
                                        val conn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && network != null) {
                                            network.openConnection(connUrl) as java.net.HttpURLConnection
                                        } else {
                                            connUrl.openConnection() as java.net.HttpURLConnection
                                        }
                                        conn.connectTimeout = 1200
                                        conn.readTimeout = 1200
                                        conn.requestMethod = "GET"
                                        conn.setRequestProperty("x-device-name", deviceModel)
                                        if (conn.responseCode == 200) {
                                            val resp = conn.inputStream.bufferedReader().readText()
                                            val respJson = JSONObject(resp)
                                            remoteModel = respJson.optString("deviceName", "Peer Device")
                                            filesCount = respJson.optInt("filesCount", 0)
                                            resolvedUrl = candidate
                                            break
                                        }
                                    } catch (ignored: Exception) {}
                                }
                                if (resolvedUrl != null) break
                                kotlinx.coroutines.delay(400)
                            }

                            if (resolvedUrl != null) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isClientConnected = true
                                    connectedHostUrl = resolvedUrl
                                    peerDeviceName = remoteModel
                                    if (filesCount > 0) {
                                        startClientDownload(resolvedUrl)
                                    } else {
                                        clientTransferState = TransferState.Idle
                                    }
                                }
                            } else {
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    clientTransferState = TransferState.Error("Could not reach sender device")
                                    Toast.makeText(context, "Connection failed: Could not reach sender device", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }

                    override fun onFailure(reason: String) {
                        clientTransferState = TransferState.Error("Wi-Fi connection failed: $reason")
                        Toast.makeText(context, "Connection failed: $reason", Toast.LENGTH_LONG).show()
                    }
                }
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Invalid QR Code: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun sendSelectedToPeer() {
        val items = selectedItems.values.toList()
        if (items.isEmpty()) return
        selectedItems.clear()

        // Always record queued sending items
        items.forEach { item ->
            if (sessionTransferRecords.none { it.id == item.id }) {
                sessionTransferRecords.add(
                    TransferRecord(
                        id = item.id,
                        name = item.name,
                        size = item.size,
                        direction = TransferDirection.SENDING,
                        status = TransferItemStatus.QUEUED
                    )
                )
            }
        }

        if (fileServer != null) {
            val added = fileServer.addItems(items)
            Toast.makeText(context, "Added $added file(s) to transfer queue!", Toast.LENGTH_SHORT).show()
            showTransferringSheet = true
        } else if (isClientConnected) {
            Toast.makeText(context, "Sending ${items.size} file(s) to peer...", Toast.LENGTH_SHORT).show()
            showTransferringSheet = true
            coroutineScope.launch {
                fileUploader.uploadBatch(
                    serverBaseUrl = connectedHostUrl,
                    items = items,
                    callback = object : FileUploader.UploadCallback {
                        override fun onProgress(
                            currentFileName: String,
                            currentFileIndex: Int,
                            totalFiles: Int,
                            currentFileBytes: Long,
                            currentFileSize: Long,
                            overallBytes: Long,
                            overallTotalBytes: Long,
                            speedMbPerSec: Double,
                            progress: Float
                        ) {
                            clientTransferState = TransferState.Transferring(
                                currentFileName = currentFileName,
                                currentFileIndex = currentFileIndex,
                                totalFiles = totalFiles,
                                currentFileBytes = currentFileBytes,
                                currentFileSize = currentFileSize,
                                overallBytes = overallBytes,
                                overallTotalBytes = overallTotalBytes,
                                speedMbPerSec = speedMbPerSec,
                                progress = progress
                            )

                            val existingIndex = sessionTransferRecords.indexOfFirst {
                                it.name == currentFileName && it.direction == TransferDirection.SENDING
                            }
                            if (existingIndex >= 0) {
                                val cur = sessionTransferRecords[existingIndex]
                                sessionTransferRecords[existingIndex] = cur.copy(
                                    status = TransferItemStatus.TRANSFERRING,
                                    bytesTransferred = currentFileBytes,
                                    progress = progress,
                                    speedMbPerSec = speedMbPerSec
                                )
                            }
                        }

                        override fun onFileUploaded(fileName: String, fileIndex: Int, totalFiles: Int) {
                            val existingIndex = sessionTransferRecords.indexOfFirst {
                                it.name == fileName && it.direction == TransferDirection.SENDING
                            }
                            if (existingIndex >= 0) {
                                val cur = sessionTransferRecords[existingIndex]
                                sessionTransferRecords[existingIndex] = cur.copy(
                                    status = TransferItemStatus.COMPLETED,
                                    bytesTransferred = cur.size,
                                    progress = 1f
                                )
                            }
                        }

                        override fun onBatchSuccess(totalFiles: Int, totalBytes: Long) {
                            clientTransferState = TransferState.Completed(
                                title = "Sent to Peer",
                                fileCount = totalFiles,
                                totalBytes = totalBytes
                            )
                        }

                        override fun onError(message: String) {
                            clientTransferState = TransferState.Error(message)
                            val activeIdx = sessionTransferRecords.indexOfFirst {
                                it.status == TransferItemStatus.TRANSFERRING && it.direction == TransferDirection.SENDING
                            }
                            if (activeIdx >= 0) {
                                val cur = sessionTransferRecords[activeIdx]
                                sessionTransferRecords[activeIdx] = cur.copy(
                                    status = TransferItemStatus.FAILED,
                                    errorMessage = message
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(BgVoid)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // 1. Top Header Bar (Xender Green matching Images 1-4)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(XenderGreen)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transfer",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                // Requirement 3: Once connected, show option to disconnect device
                if (isConnected) {
                    Surface(
                        onClick = { showDisconnectDialog = true },
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFDC2626).copy(alpha = 0.25f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444)),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Disconnect",
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Disconnect",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Transfer records launcher / status icon button
                if (isConnected || sessionTransferRecords.isNotEmpty()) {
                    IconButton(
                        onClick = { showTransferringSheet = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = "Transfer History & Records",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Requirement 5: Live Process Round Circle in Top Bar
                if (activeTransferState is TransferState.Transferring) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { showTransferringSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { liveProgress },
                            color = RubyRedLight,
                            trackColor = Color.White.copy(alpha = 0.3f),
                            strokeWidth = 3.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = "${(liveProgress * 100).toInt()}%",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Theme Toggle Button
                IconButton(
                    onClick = { ThemeState.toggle() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (ThemeState.isDarkMode) Icons.Default.WbSunny else Icons.Default.Brightness4,
                        contentDescription = "Toggle Theme",
                        tint = if (ThemeState.isDarkMode) AccentAmber else AccentPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 2. Category Tabs Row (Images 1-4)
            CategoryTabBar(
                selectedTab = selectedTab,
                listState = tabListState,
                onSelectTab = {
                    selectedTab = it
                    searchQuery = ""
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(it.ordinal)
                    }
                }
            )

            // 3. Search Bar: "Search local files" (Images 1, 2, 3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = TextPrimary,
                        fontSize = 13.sp
                    ),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search local files",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { searchQuery = "" }
                    )
                }
            }

            // Category Content with smooth horizontal swipe gestures
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                val pageTab = categoryTabs[page]
                when (pageTab) {
                        CategoryTab.APPS -> {
                            val filteredApps = if (searchQuery.isEmpty()) appsList else appsList.filter {
                                it.name.contains(searchQuery, ignoreCase = true)
                            }
                            AppsGrid(
                                apps = filteredApps,
                                selectedItems = selectedItems,
                                onToggle = { app ->
                                    val existing = selectedItems.values.firstOrNull { it.relativePath == "${app.name}.apk" }
                                    if (existing != null) {
                                        selectedItems.remove(existing.id)
                                    } else {
                                        val item = mediaRepo.convertAppToSharedFile(app)
                                        selectedItems[item.id] = item
                                    }
                                }
                            )
                        }

                        CategoryTab.PHOTOS -> {
                            if (photosList.isEmpty() && !hasMediaPermission) {
                                PermissionRequestBanner(
                                    title = "Gallery Access Needed",
                                    description = "Allow FastShare access to your gallery photos to share them at maximum Wi-Fi speed.",
                                    buttonText = "Grant Photo Permission",
                                    onRequest = {
                                        val perms = if (Build.VERSION.SDK_INT >= 34) {
                                            arrayOf(
                                                Manifest.permission.READ_MEDIA_IMAGES,
                                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                                            )
                                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                                        } else {
                                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                                        }
                                        mediaPermissionLauncher.launch(perms)
                                    }
                                )
                            } else {
                                // Day-Wise Grouped Photos View
                                PhotosDayWiseGrid(
                                    photos = photosList,
                                    selectedItems = selectedItems,
                                    onTogglePhoto = { photo ->
                                        val existing = selectedItems.values.firstOrNull { it.uri == photo.uri }
                                        if (existing != null) {
                                            selectedItems.remove(existing.id)
                                        } else {
                                            val item = SharedFileItem(
                                                id = UUID.randomUUID().toString(),
                                                uri = photo.uri,
                                                name = photo.name,
                                                relativePath = photo.name,
                                                size = photo.size,
                                                mimeType = photo.mimeType
                                            )
                                            selectedItems[item.id] = item
                                        }
                                    },
                                    onToggleDay = { dayGroup ->
                                        val allDaySelected = dayGroup.photos.isNotEmpty() && dayGroup.photos.all { photo ->
                                            selectedItems.values.any { it.uri == photo.uri }
                                        }
                                        if (allDaySelected) {
                                            val urisToRemove = dayGroup.photos.map { it.uri }.toSet()
                                            val toRemoveIds = selectedItems.filter { it.value.uri in urisToRemove }.keys.toList()
                                            toRemoveIds.forEach { selectedItems.remove(it) }
                                        } else {
                                            dayGroup.photos.forEach { photo ->
                                                if (!selectedItems.values.any { it.uri == photo.uri }) {
                                                    val item = SharedFileItem(
                                                        id = UUID.randomUUID().toString(),
                                                        uri = photo.uri,
                                                        name = photo.name,
                                                        relativePath = photo.name,
                                                        size = photo.size,
                                                        mimeType = photo.mimeType
                                                    )
                                                    selectedItems[item.id] = item
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        CategoryTab.VIDEOS -> {
                            if (videosList.isEmpty() && !hasMediaPermission) {
                                PermissionRequestBanner(
                                    title = "Video Access Needed",
                                    description = "Allow FastShare access to your videos to stream and share them offline.",
                                    buttonText = "Grant Video Permission",
                                    onRequest = {
                                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                                        } else {
                                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                                        }
                                        mediaPermissionLauncher.launch(perms)
                                    }
                                )
                            } else {
                                VideosGrid(
                                    videos = videosList,
                                    selectedItems = selectedItems,
                                    onToggle = { video ->
                                        val existing = selectedItems.values.firstOrNull { it.uri == video.uri }
                                        if (existing != null) {
                                            selectedItems.remove(existing.id)
                                        } else {
                                            val item = SharedFileItem(
                                                id = UUID.randomUUID().toString(),
                                                uri = video.uri,
                                                name = video.name,
                                                relativePath = video.name,
                                                size = video.size,
                                                mimeType = "video/mp4"
                                            )
                                            selectedItems[item.id] = item
                                        }
                                    }
                                )
                            }
                        }

                        CategoryTab.FILES -> {
                            StorageExplorerView(
                                drives = storageDrives,
                                currentDirectory = currentDirectory,
                                directoryFiles = directoryFiles,
                                isLoading = isLoadingDirectory,
                                selectedItems = selectedItems,
                                mediaRepo = mediaRepo,
                                searchQuery = searchQuery,
                                onSelectDrive = { drive ->
                                    currentDirectory = drive.rootFile
                                },
                                onNavigateTo = { dir ->
                                    currentDirectory = dir
                                },
                                onNavigateUp = {
                                    currentDirectory?.parentFile?.let { parent ->
                                        if (storageDrives.any { currentDirectory!!.path.startsWith(it.rootFile.path) } &&
                                            storageDrives.none { currentDirectory!!.path == it.rootFile.path }) {
                                            currentDirectory = parent
                                        }
                                    }
                                },
                                onSelectFile = { file ->
                                    coroutineScope.launch {
                                        val rootBase = storageDrives.firstOrNull { file.path.startsWith(it.rootFile.path) }?.rootFile?.path
                                        val existing = selectedItems.values.firstOrNull { it.name == file.name && it.size == file.length() }
                                        if (existing != null) {
                                            selectedItems.remove(existing.id)
                                        } else {
                                            val item = mediaRepo.convertDiskFileToSharedItem(file, rootBase)
                                            selectedItems[item.id] = item
                                        }
                                    }
                                },
                                onSelectFolder = { folder ->
                                    coroutineScope.launch {
                                        val folderItems = mediaRepo.convertFolderToSharedItems(folder)
                                        folderItems.forEach { item ->
                                            selectedItems[item.id] = item
                                        }
                                        Toast.makeText(context, "Added ${folderItems.size} items from '${folder.name}'", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDeselectFolder = { folder ->
                                    val toRemove = selectedItems.filter {
                                        it.value.relativePath.startsWith("${folder.name}/") || it.value.name == folder.name
                                    }.keys.toList()
                                    toRemove.forEach { selectedItems.remove(it) }
                                }
                            )
                        }

                        CategoryTab.CONTACTS -> {
                            val filteredContacts = if (searchQuery.isEmpty()) contactsList else contactsList.filter {
                                it.name.contains(searchQuery, ignoreCase = true) || it.phone.contains(searchQuery)
                            }
                            ContactsList(
                                contacts = filteredContacts,
                                selectedItems = selectedItems,
                                onToggle = { contact ->
                                    coroutineScope.launch {
                                        val existing = selectedItems.values.firstOrNull { it.name.startsWith(contact.name) }
                                        if (existing != null) {
                                            selectedItems.remove(existing.id)
                                        } else {
                                            val item = mediaRepo.exportContactToVcf(contact)
                                            selectedItems[item.id] = item
                                        }
                                    }
                                }
                            )
                        }

                        CategoryTab.MUSIC -> {
                            val filteredAudios = if (searchQuery.isEmpty()) audiosList else audiosList.filter {
                                it.name.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true)
                            }
                            AudiosList(
                                audios = filteredAudios,
                                selectedItems = selectedItems,
                                onToggle = { audio ->
                                    val existing = selectedItems.values.firstOrNull { it.uri == audio.uri }
                                    if (existing != null) {
                                        selectedItems.remove(existing.id)
                                    } else {
                                        val item = SharedFileItem(
                                            id = UUID.randomUUID().toString(),
                                            uri = audio.uri,
                                            name = audio.name,
                                            relativePath = audio.name,
                                            size = audio.size,
                                            mimeType = "audio/mpeg"
                                        )
                                        selectedItems[item.id] = item
                                    }
                                }
                            )
                        }

                        CategoryTab.HISTORY -> {
                            val historyFiles = if (isClientConnected) clientReceivedFiles else (fileServer?.getReceivedFiles() ?: serverReceivedFiles)
                            val sentList = if (fileServer != null) fileServer.getItems() else emptyList()
                            HistoryList(
                                sentItems = sentList,
                                receivedFiles = historyFiles
                            )
                        }
                    }
                }


            // ---------------------------------------------------------------------------------
            // Requirement 2: Send & Receive Bottom Options (when !isConnected)
            // Requirement 4: Once connected, send & receive buttons hide
            // ---------------------------------------------------------------------------------
            if (!isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // SEND Button (pill button, can be tapped with or without selected files)
                    Button(
                        onClick = {
                            if (fileServer != null) {
                                if (totalSelectedCount > 0) {
                                    fileServer.addItems(selectedItems.values.toList())
                                    selectedItems.clear()
                                }
                                if (!isConnected) {
                                    showQrModal = true
                                } else {
                                    Toast.makeText(context, "Added to transfer queue", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                startSenderSharing()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = XenderGreenLight),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (totalSelectedCount > 0) "SEND ($totalSelectedCount)" else "SEND",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // RECEIVE Button (pill button)
                    Button(
                        onClick = {
                            startReceiverFlow()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = XenderGreenLight),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "RECEIVE",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            } else {
                // When connected:
                // 1. If files are selected, show floating Send bar
                if (totalSelectedCount > 0) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = SurfaceElevated.copy(alpha = 0.98f),
                        shadowElevation = 10.dp,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, RubyRedLight)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "⚡ $totalSelectedCount items (${formatBytes(totalSelectedSize)})",
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Ready to send to $peerDeviceName",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { selectedItems.clear() }) {
                                    Text("Clear", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(6.dp))
                                Button(
                                    onClick = { sendSelectedToPeer() },
                                    colors = ButtonDefaults.buttonColors(containerColor = RubyRed),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("SEND", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                // 2. Connected Bottom Layout: Floating Transfer Button on bottom-left & Connected Devices Bar at bottom
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Sleek Connected Devices Bottom Bar (Matching user's Xender screenshot)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                        color = SurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Device 1 Avatar (This Device - Fox 🦊)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFF97316)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🦊", fontSize = 18.sp)
                                }

                                // Vertical Divider
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(20.dp)
                                        .background(BorderSubtle)
                                )

                                // Device 2 Avatar (Peer Device - Deer 🦌)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEAB308)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🦌", fontSize = 18.sp)
                                }

                                // Peer Device Name Pill (e.g. Pixel6a)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = SurfaceElevated,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = peerDeviceName.ifEmpty { "Pixel6a" },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }

                            // Disconnect Cross Button (Right side)
                            IconButton(
                                onClick = { showDisconnectDialog = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Disconnect",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Floating Circular Transfer Button (Bottom Left, directly above the connected bar)
                    Surface(
                        onClick = { showTransferringSheet = true },
                        shape = CircleShape,
                        color = RubyRed,
                        border = androidx.compose.foundation.BorderStroke(2.5.dp, Color.White),
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .padding(start = 16.dp, bottom = 56.dp)
                            .size(52.dp)
                            .align(Alignment.BottomStart)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            if (activeTransferState is TransferState.Transferring) {
                                CircularProgressIndicator(
                                    progress = { liveProgress },
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.3f),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    text = "${(liveProgress * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = "Transferring",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------------------------------
        // Requirement 3: Disconnect Confirmation Dialog
        // -------------------------------------------------------------------------------------
        if (showDisconnectDialog) {
            DisconnectConfirmationDialog(
                title = "Disconnect Device?",
                message = "Are you sure you want to disconnect from the peer device and end the current transfer session?",
                confirmButtonText = "Disconnect",
                onConfirm = {
                    showDisconnectDialog = false
                    disconnectDevice()
                },
                onDismiss = { showDisconnectDialog = false }
            )
        }

        // -------------------------------------------------------------------------------------
        // Requirement 4 & 5: Transferring Sheet / Popup (Received & Sent tabs with round circle)
        // -------------------------------------------------------------------------------------
        if (showTransferringSheet) {
            Dialog(
                onDismissRequest = { showTransferringSheet = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                val allSent = if (fileServer != null) fileServer.getItems() else emptyList()
                val allReceived = if (isClientConnected) clientReceivedFiles else (fileServer?.getReceivedFiles() ?: serverReceivedFiles)
                TransferringSheet(
                    transferState = activeTransferState,
                    records = sessionTransferRecords,
                    sentItems = allSent,
                    receivedFiles = allReceived,
                    onRetryTransfer = { record ->
                        if (isClientConnected) {
                            if (record.direction == TransferDirection.SENDING) {
                                sendSelectedToPeer()
                            } else {
                                startClientDownload(connectedHostUrl)
                            }
                        } else {
                            if (record.direction == TransferDirection.SENDING) {
                                Toast.makeText(context, "Resuming file transfer with peer...", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Waiting for peer to resend...", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onCollapse = { showTransferringSheet = false }
                )
            }
        }

        // -------------------------------------------------------------------------------------
        // Full-screen Camera QR Scanner Dialog
        // -------------------------------------------------------------------------------------
        if (showQrScanner && !isConnected) {
            Dialog(
                onDismissRequest = { showQrScanner = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    QrScannerView(
                        modifier = Modifier.fillMaxSize(),
                        onQrCodeScanned = { raw ->
                            handleQrScanned(raw)
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showQrScanner = false },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = "Scan Peer QR Code",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------------------------------------
        // Full-screen QR Code Modal (Sender Hotspot QR Code)
        // -------------------------------------------------------------------------------------
        val isSenderConnected = isConnected || isHostConnected || isClientConnected || ((activeFileServer ?: fileServer)?.isPeerConnectedState?.value == true) || ((activeFileServer ?: fileServer)?.isPeerConnected == true)
        if (showQrModal && !isSenderConnected && currentQrBitmap != null) {
            Dialog(
                onDismissRequest = { showQrModal = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgVoid
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Top Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = { showQrModal = false },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceElevated)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = TextPrimary
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Scan to Connect",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Receiver connects via QR scanner",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }

                            // Theme Toggle Button
                            IconButton(
                                onClick = { ThemeState.toggle() },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceElevated)
                            ) {
                                Icon(
                                    imageVector = if (ThemeState.isDarkMode) Icons.Default.WbSunny else Icons.Default.Brightness4,
                                    contentDescription = "Toggle Theme",
                                    tint = if (ThemeState.isDarkMode) AccentAmber else AccentPurple,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // QR Code Card
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = SurfaceCard,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, BorderSubtle),
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(220.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.White)
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = currentQrBitmap!!.asImageBitmap(),
                                        contentDescription = "FastShare QR Code",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Spacer(Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(SurfaceElevated)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = null,
                                        tint = XenderGreenLight,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Point receiver camera at this code",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }

                        // Pulsing Waiting Indicator
                        val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(900, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulseAlpha"
                        )

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = XenderGreenLight.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, XenderGreenLight.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(XenderGreenLight.copy(alpha = pulseAlpha))
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Waiting for receiver to scan & connect...",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                            }
                        }

                        // Hotspot Credentials Card
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = SurfaceCard,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(SurfaceElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Wifi,
                                            contentDescription = null,
                                            tint = XenderGreenLight,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Wi-Fi Hotspot SSID",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = currentHotspotSsid.ifEmpty { "FastShare Hotspot" },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                HorizontalDivider(
                                    color = BorderSubtle.copy(alpha = 0.5f),
                                    thickness = 1.dp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(SurfaceElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = AccentAmber,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Network Password",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = if (currentHotspotPassword.isNotEmpty()) currentHotspotPassword else "Open / Direct Wi-Fi (No password required)",
                                            fontWeight = if (currentHotspotPassword.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp,
                                            color = if (currentHotspotPassword.isNotEmpty()) TextPrimary else TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // Bottom Actions: Minimize & Select More Files
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showQrModal = false },
                                colors = ButtonDefaults.buttonColors(containerColor = XenderGreenLight),
                                shape = RoundedCornerShape(16.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Minimize & Browse Files",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color.White
                                )
                            }

                            Text(
                                text = "Hotspot stays active in background until disconnected",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Storage Explorer View with Right-Tick Selection for Folders and Files
// -----------------------------------------------------------------------------------------

enum class FilesViewMode {
    OVERVIEW,
    CATEGORY_LIST,
    DIRECTORY
}

@Composable
fun StorageExplorerView(
    drives: List<StorageDrive>,
    currentDirectory: File?,
    directoryFiles: List<FileExplorerItem>,
    isLoading: Boolean,
    selectedItems: MutableMap<String, SharedFileItem>,
    mediaRepo: MediaRepository,
    searchQuery: String = "",
    onSelectDrive: (StorageDrive) -> Unit,
    onNavigateTo: (File) -> Unit,
    onNavigateUp: () -> Unit,
    onSelectFile: (File) -> Unit,
    onSelectFolder: (File) -> Unit,
    onDeselectFolder: (File) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var viewMode by remember { mutableStateOf(FilesViewMode.OVERVIEW) }
    var selectedCategory by remember { mutableStateOf(CategorizedFileType.DOCUMENTS) }
    var categoryFiles by remember { mutableStateOf<List<CategorizedFileInfo>>(emptyList()) }
    var isLoadingCategory by remember { mutableStateOf(false) }
    var categoryCounts by remember { mutableStateOf<Map<CategorizedFileType, Int>>(emptyMap()) }

    LaunchedEffect(Unit) {
        categoryCounts = mediaRepo.getCategorizedCounts()
    }

    LaunchedEffect(selectedCategory, viewMode) {
        if (viewMode == FilesViewMode.CATEGORY_LIST) {
            isLoadingCategory = true
            categoryFiles = mediaRepo.loadCategorizedFiles(selectedCategory)
            isLoadingCategory = false
        }
    }

    androidx.activity.compose.BackHandler(enabled = viewMode != FilesViewMode.OVERVIEW) {
        if (viewMode == FilesViewMode.DIRECTORY) {
            val isAtRoot = drives.any { currentDirectory?.path == it.rootFile.path }
            if (isAtRoot || currentDirectory == null) {
                viewMode = FilesViewMode.OVERVIEW
            } else {
                onNavigateUp()
            }
        } else {
            viewMode = FilesViewMode.OVERVIEW
        }
    }

    when (viewMode) {
        FilesViewMode.OVERVIEW -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Top Full-Width Card: Phone Storage (Matching Screenshot)
                val phoneDrive = drives.firstOrNull { !it.isSdCard } ?: drives.firstOrNull()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            phoneDrive?.let { onSelectDrive(it) }
                            viewMode = FilesViewMode.DIRECTORY
                        },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = "Phone Storage",
                                    tint = Color(0xFF4ADE80),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Phone Storage",
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${formatBytes(phoneDrive?.usedSpace ?: 0L)} / ${formatBytes(phoneDrive?.totalSpace ?: 0L)}",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        LinearProgressIndicator(
                            progress = { phoneDrive?.usedPercent ?: 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = RubyRed,
                            trackColor = BorderSubtle
                        )
                    }
                }

                // If SD Card exists, show SD Card row
                val sdDrive = drives.firstOrNull { it.isSdCard }
                if (sdDrive != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectDrive(sdDrive)
                                viewMode = FilesViewMode.DIRECTORY
                            },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(SurfaceElevated),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SdCard,
                                        contentDescription = "SD Card",
                                        tint = AccentEmerald,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sdDrive.name,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${formatBytes(sdDrive.usedSpace)} / ${formatBytes(sdDrive.totalSpace)}",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            LinearProgressIndicator(
                                progress = { sdDrive.usedPercent },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = AccentEmerald,
                                trackColor = BorderSubtle
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // The 5 Categorized Folder Rows (Exact visual match to Screenshot)
                // 1. Documents
                CategoryFolderRow(
                    title = "Documents (${categoryCounts[CategorizedFileType.DOCUMENTS] ?: 0})",
                    subtitle = "Includes Word, PPT, Excel, WPS, etc.",
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF2563EB)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("W", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        }
                    },
                    onClick = {
                        selectedCategory = CategorizedFileType.DOCUMENTS
                        viewMode = FilesViewMode.CATEGORY_LIST
                    }
                )

                // 2. Ebooks
                CategoryFolderRow(
                    title = "Ebooks (${categoryCounts[CategorizedFileType.EBOOKS] ?: 0})",
                    subtitle = "Includes .umd, .ebk, .txt, .chm, etc.",
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0EA5E9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("T", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        }
                    },
                    onClick = {
                        selectedCategory = CategorizedFileType.EBOOKS
                        viewMode = FilesViewMode.CATEGORY_LIST
                    }
                )

                // 3. Apks (with "Wait Install" red badge)
                CategoryFolderRow(
                    title = "Apks (${categoryCounts[CategorizedFileType.APKS] ?: 0})",
                    subtitle = "Includes .apk files",
                    extraTitleTag = "Wait Install",
                    icon = {
                        Box(modifier = Modifier.size(48.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF22C55E)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Android, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("1", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    onClick = {
                        selectedCategory = CategorizedFileType.APKS
                        viewMode = FilesViewMode.CATEGORY_LIST
                    }
                )

                // 4. Archives
                CategoryFolderRow(
                    title = "Archives (${categoryCounts[CategorizedFileType.ARCHIVES] ?: 0})",
                    subtitle = "Includes .7z, .rar, .zip, .iso, etc.",
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF38BDF8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Inventory2, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    },
                    onClick = {
                        selectedCategory = CategorizedFileType.ARCHIVES
                        viewMode = FilesViewMode.CATEGORY_LIST
                    }
                )

                // 5. Big files
                CategoryFolderRow(
                    title = "Big files (${categoryCounts[CategorizedFileType.BIG_FILES] ?: 0})",
                    subtitle = "Includes files > 50 MB",
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF3B82F6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    },
                    onClick = {
                        selectedCategory = CategorizedFileType.BIG_FILES
                        viewMode = FilesViewMode.CATEGORY_LIST
                    }
                )

                Spacer(modifier = Modifier.height(100.dp))
            }
        }

        FilesViewMode.CATEGORY_LIST -> {
            val filteredCategoryFiles = if (searchQuery.isEmpty()) categoryFiles else categoryFiles.filter {
                it.name.contains(searchQuery, ignoreCase = true)
            }
            val allCategorySelected = filteredCategoryFiles.isNotEmpty() && filteredCategoryFiles.all { catFile ->
                selectedItems.values.any { it.name == catFile.name && it.size == catFile.size }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                // Top Navigation Bar
                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewMode = FilesViewMode.OVERVIEW },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Overview",
                                tint = RubyRedLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = "${selectedCategory.title} (${filteredCategoryFiles.size})",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Select All Toggle
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (allCategorySelected) RubyRed.copy(alpha = 0.2f) else SurfaceElevated)
                                .clickable {
                                    coroutineScope.launch {
                                        if (allCategorySelected) {
                                            filteredCategoryFiles.forEach { catFile ->
                                                val existing = selectedItems.values.firstOrNull { it.name == catFile.name && it.size == catFile.size }
                                                if (existing != null) selectedItems.remove(existing.id)
                                            }
                                        } else {
                                            filteredCategoryFiles.forEach { catFile ->
                                                val existing = selectedItems.values.firstOrNull { it.name == catFile.name && it.size == catFile.size }
                                                if (existing == null) {
                                                    val item = mediaRepo.convertDiskFileToSharedItem(catFile.file)
                                                    selectedItems[item.id] = item
                                                }
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(if (allCategorySelected) RubyRed else Color.Transparent)
                                    .border(1.5.dp, if (allCategorySelected) RubyRed else BorderSubtle, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (allCategorySelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (allCategorySelected) "Selected" else "Select All",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (allCategorySelected) RubyRedLight else TextSecondary
                            )
                        }
                    }
                }

                if (isLoadingCategory) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RubyRed)
                    }
                } else if (filteredCategoryFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No ${selectedCategory.title} found", color = TextMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 90.dp)
                    ) {
                        items(filteredCategoryFiles) { fileInfo ->
                            val isSelected = selectedItems.values.any { it.name == fileInfo.name && it.size == fileInfo.size }
                            val dateStr = remember(fileInfo.lastModified) {
                                if (fileInfo.lastModified > 0) {
                                    java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(fileInfo.lastModified))
                                } else ""
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) SurfaceElevated else SurfaceCard)
                                    .border(1.dp, if (isSelected) RubyRed.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable {
                                        coroutineScope.launch {
                                            val existing = selectedItems.values.firstOrNull { it.name == fileInfo.name && it.size == fileInfo.size }
                                            if (existing != null) {
                                                selectedItems.remove(existing.id)
                                            } else {
                                                val item = mediaRepo.convertDiskFileToSharedItem(fileInfo.file)
                                                selectedItems[item.id] = item
                                            }
                                        }
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(RubyRed.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = getFileIcon(fileInfo.extension),
                                            contentDescription = null,
                                            tint = RubyRedLight,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = fileInfo.name,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${formatBytes(fileInfo.size)}${if (dateStr.isNotEmpty()) " • $dateStr" else ""}",
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                // Right-side Selection Tick Circle
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            coroutineScope.launch {
                                                val existing = selectedItems.values.firstOrNull { it.name == fileInfo.name && it.size == fileInfo.size }
                                                if (existing != null) {
                                                    selectedItems.remove(existing.id)
                                                } else {
                                                    val item = mediaRepo.convertDiskFileToSharedItem(fileInfo.file)
                                                    selectedItems[item.id] = item
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(if (isSelected) RubyRed else Color.Transparent, CircleShape)
                                            .border(1.5.dp, if (isSelected) RubyRed else BorderSubtle, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color.White,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FilesViewMode.DIRECTORY -> {
            // Folder tree explorer with drives and breadcrumbs
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                // Top Directory Bar
                val isCurrentFolderSelected = remember(selectedItems.size, currentDirectory?.name) {
                    if (currentDirectory == null) false
                    else selectedItems.values.any { it.relativePath.startsWith("${currentDirectory.name}/") }
                }

                Surface(
                    color = SurfaceCard,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val isAtRoot = drives.any { currentDirectory?.path == it.rootFile.path }
                                if (isAtRoot || currentDirectory == null) {
                                    viewMode = FilesViewMode.OVERVIEW
                                } else {
                                    onNavigateUp()
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = RubyRedLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = currentDirectory?.name?.ifEmpty { "Root" } ?: "Phone Storage",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (currentDirectory != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isCurrentFolderSelected) RubyRed.copy(alpha = 0.2f) else SurfaceElevated)
                                    .clickable {
                                        if (isCurrentFolderSelected) {
                                            onDeselectFolder(currentDirectory)
                                        } else {
                                            onSelectFolder(currentDirectory)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(if (isCurrentFolderSelected) RubyRed else Color.Transparent)
                                        .border(1.5.dp, if (isCurrentFolderSelected) RubyRed else BorderSubtle, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isCurrentFolderSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isCurrentFolderSelected) "Selected" else "Select All",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCurrentFolderSelected) RubyRedLight else TextSecondary
                                )
                            }
                        }
                    }
                }

                // If multiple drives, show Drive selector
                if (drives.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        items(drives) { drive ->
                            val isSelectedDrive = currentDirectory?.path?.startsWith(drive.rootFile.path) == true
                            Card(
                                modifier = Modifier
                                    .width(160.dp)
                                    .clickable { onSelectDrive(drive) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelectedDrive) SurfaceElevated else SurfaceCard
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelectedDrive) 1.5.dp else 1.dp,
                                    color = if (isSelectedDrive) RubyRedLight else BorderSubtle
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (drive.isSdCard) Icons.Default.SdCard else Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = if (drive.isSdCard) AccentEmerald else RubyRedLight,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = drive.name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Directory Contents List
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RubyRed)
                    }
                } else if (directoryFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Folder is empty", color = TextMuted, fontSize = 13.sp)
                    }
                } else {
                    val filteredDirFiles = if (searchQuery.isEmpty()) directoryFiles else directoryFiles.filter {
                        it.name.contains(searchQuery, ignoreCase = true)
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 90.dp)
                    ) {
                        items(filteredDirFiles) { item ->
                            val isFolder = item.isDirectory
                            val isFileSelected = !isFolder && selectedItems.values.any { it.name == item.name && it.size == item.size }
                            val isFolderSelected = isFolder && selectedItems.values.any {
                                it.relativePath.startsWith("${item.name}/") || it.name == item.name
                            }
                            val isRowActive = isFileSelected || isFolderSelected

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isRowActive) SurfaceElevated else SurfaceCard)
                                    .border(1.dp, if (isRowActive) RubyRed.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (isFolder) {
                                            onNavigateTo(item.file)
                                        } else {
                                            onSelectFile(item.file)
                                        }
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(
                                                if (isFolder) AccentPurple.copy(alpha = 0.2f) else RubyRed.copy(alpha = 0.15f),
                                                RoundedCornerShape(10.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isFolder) Icons.Default.Folder else getFileIcon(item.extension),
                                            contentDescription = null,
                                            tint = if (isFolder) AccentPurple else RubyRedLight,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (isFolder) "${item.itemCount} items" else "${formatBytes(item.size)} • ${item.formattedDate}",
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                // Right-side Selection Tick Circle
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            if (isFolder) {
                                                if (isFolderSelected) {
                                                    onDeselectFolder(item.file)
                                                } else {
                                                    onSelectFolder(item.file)
                                                }
                                            } else {
                                                onSelectFile(item.file)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val checked = if (isFolder) isFolderSelected else isFileSelected
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(if (checked) RubyRed else Color.Transparent, CircleShape)
                                            .border(1.5.dp, if (checked) RubyRed else BorderSubtle, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (checked) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color.White,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable Row for each of the 5 Categorized Folders (Documents, Ebooks, Apks, Archives, Big files)
 */
@Composable
fun CategoryFolderRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    extraTitleTag: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 15.sp
                )
                if (extraTitleTag != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = extraTitleTag,
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextSecondary
            )
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}

// -----------------------------------------------------------------------------------------
// Category Views & Tab Bar (Apps, Photos Day-Wise, Videos, Contacts)
// -----------------------------------------------------------------------------------------

@Composable
fun CategoryTabBar(
    selectedTab: CategoryTab,
    onSelectTab: (CategoryTab) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .background(XenderGreen)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(CategoryTab.values()) { tab ->
            val isSelected = selectedTab == tab
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelectTab(tab) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = tab.label,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(if (isSelected) 28.dp else 0.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isSelected) Color.White else Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun AppsGrid(
    apps: List<InstalledAppItem>,
    selectedItems: Map<String, SharedFileItem>,
    onToggle: (InstalledAppItem) -> Unit
) {
    if (apps.isEmpty()) {
        EmptyCategoryView(Icons.Default.Apps, "No user apps found")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 90.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(apps) { app ->
            val isSelected = selectedItems.values.any { it.relativePath == "${app.name}.apk" }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) SurfaceElevated else Color.Transparent)
                    .clickable { onToggle(app) }
                    .padding(8.dp)
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    if (app.icon != null) {
                        Image(
                            painter = rememberAsyncImagePainter(app.icon),
                            contentDescription = app.name,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .background(SurfaceCard, RoundedCornerShape(12.dp))
                                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Android, contentDescription = null, tint = RubyRedLight)
                        }
                    }

                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(RubyRed, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = app.name,
                    fontSize = 12.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = formatBytes(app.size),
                    fontSize = 10.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Day-Wise Grouped Photos View with Day Header and Day Select Checkbox
// -----------------------------------------------------------------------------------------

@Composable
fun PhotosDayWiseGrid(
    photos: List<MediaItem>,
    selectedItems: Map<String, SharedFileItem>,
    onTogglePhoto: (MediaItem) -> Unit,
    onToggleDay: (DayPhotoGroup) -> Unit
) {
    if (photos.isEmpty()) {
        EmptyCategoryView(Icons.Default.PhotoLibrary, "No photos found on device")
        return
    }

    val context = LocalContext.current
    val mediaRepo = remember { MediaRepository(context) }
    val dayGroups = remember(photos) { mediaRepo.groupPhotosByDay(photos) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        dayGroups.forEach { dayGroup ->
            val allDaySelected = dayGroup.photos.isNotEmpty() && dayGroup.photos.all { photo ->
                selectedItems.values.any { it.uri == photo.uri }
            }

            // Day Header with Date & Select Day Checkbox
            item(key = "header_${dayGroup.dayKey}_${dayGroup.dateLabel}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceBase)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dayGroup.dateLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${dayGroup.photos.size})",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    // Day Select Right Tick Button / Checkbox
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (allDaySelected) RubyRed.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { onToggleDay(dayGroup) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (allDaySelected) "Deselect Day" else "Select Day",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (allDaySelected) RubyRedLight else TextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (allDaySelected) RubyRed else Color.Transparent)
                                .border(1.5.dp, if (allDaySelected) RubyRed else BorderSubtle, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (allDaySelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3-column Photo Grid for this day
            val chunkedPhotos = dayGroup.photos.chunked(3)
            items(chunkedPhotos) { rowPhotos ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (photo in rowPhotos) {
                        val isPhotoSelected = selectedItems.values.any { it.uri == photo.uri }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onTogglePhoto(photo) }
                        ) {
                            AsyncImage(
                                model = photo.uri,
                                contentDescription = photo.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Overlay with Right Tick checkmark
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(if (isPhotoSelected) BgVoid.copy(alpha = 0.45f) else Color.Transparent)
                                    .padding(6.dp),
                                contentAlignment = Alignment.TopEnd
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(if (isPhotoSelected) RubyRed else BgVoid.copy(alpha = 0.6f))
                                        .border(1.5.dp, if (isPhotoSelected) RubyRed else Color.White.copy(alpha = 0.8f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isPhotoSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (rowPhotos.size < 3) {
                        for (i in 0 until (3 - rowPhotos.size)) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            item(key = "spacer_${dayGroup.dayKey}_${dayGroup.dateLabel}") {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
fun VideosGrid(
    videos: List<VideoItem>,
    selectedItems: Map<String, SharedFileItem>,
    onToggle: (VideoItem) -> Unit
) {
    if (videos.isEmpty()) {
        EmptyCategoryView(Icons.Default.VideoLibrary, "No videos found on device")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 90.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(videos) { video ->
            val isSelected = selectedItems.values.any { it.uri == video.uri }
            Box(
                modifier = Modifier
                    .aspectRatio(1.2f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggle(video) }
            ) {
                AsyncImage(
                    model = video.uri,
                    contentDescription = video.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, BgVoid.copy(alpha = 0.85f)),
                                startY = 100f
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(if (isSelected) RubyRed else BgVoid.copy(alpha = 0.6f), CircleShape)
                            .border(1.5.dp, if (isSelected) RubyRed else Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    if (video.durationMs > 0) {
                        Text(
                            text = video.formattedDuration,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Text(
                        text = formatBytes(video.size),
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun ContactsList(
    contacts: List<ContactItem>,
    selectedItems: Map<String, SharedFileItem>,
    onToggle: (ContactItem) -> Unit
) {
    if (contacts.isEmpty()) {
        EmptyCategoryView(Icons.Default.Contacts, "No contacts found on device")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(contacts) { contact ->
            val isSelected = selectedItems.values.any { it.name.startsWith(contact.name) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) SurfaceElevated else SurfaceCard)
                    .clickable { onToggle(contact) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(RubyRed.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contact.name.take(1).uppercase(),
                            color = RubyRedLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Column {
                        Text(
                            text = contact.name,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = contact.phone,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(if (isSelected) RubyRed else Color.Transparent, CircleShape)
                        .border(1.5.dp, if (isSelected) RubyRed else BorderSubtle, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRequestBanner(
    title: String,
    description: String,
    buttonText: String,
    onRequest: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = RubyRedLight, modifier = Modifier.size(46.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary, textAlign = TextAlign.Center)
                Text(description, fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = RubyRed, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(buttonText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EmptyCategoryView(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = BorderSubtle, modifier = Modifier.size(54.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(message, color = TextMuted, fontSize = 14.sp)
        }
    }
}

// -----------------------------------------------------------------------------------------
// FastShare Docked Bottom Bar (With Queue Mode when Sharing is Active)
// -----------------------------------------------------------------------------------------

@Composable
fun FastShareDockedBottomBar(
    count: Int,
    totalSize: Long,
    isSharingActive: Boolean = false,
    onClear: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        color = SurfaceElevated.copy(alpha = 0.98f),
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 20.dp,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, BorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isSharingActive) "⚡ +$count new" else "⚡ $count items",
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• ${formatBytes(totalSize)}",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = "Clear selection",
                    color = Color(0xFFEF4444),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onClear() }
                )
            }

            // Send or Queue More Button
            Button(
                onClick = onSend,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RubyRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Icon(
                    imageVector = if (isSharingActive) Icons.Default.Add else Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isSharingActive) "SEND MORE FILES ($count)" else "SEND ($count)",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

private fun getFileIcon(ext: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (ext) {
        "pdf" -> Icons.Default.PictureAsPdf
        "zip", "rar", "7z", "tar" -> Icons.Default.FolderZip
        "apk" -> Icons.Default.Android
        "mp3", "wav", "flac", "m4a" -> Icons.Default.AudioFile
        "mp4", "mkv", "avi" -> Icons.Default.VideoFile
        "jpg", "png", "jpeg", "webp" -> Icons.Default.Image
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}


private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
fun AudiosList(
    audios: List<AudioItem>,
    selectedItems: Map<String, SharedFileItem>,
    onToggle: (AudioItem) -> Unit
) {
    if (audios.isEmpty()) {
        EmptyCategoryView(Icons.Default.Audiotrack, "No audio files found")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(audios) { audio ->
            val isSelected = selectedItems.values.any { it.uri == audio.uri }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) SurfaceElevated else SurfaceCard)
                    .clickable { onToggle(audio) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(AccentPurple.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = AccentPurple,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = audio.name,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${audio.artist} • ${formatBytes(audio.size)}${if (audio.durationMs > 0) " • ${audio.formattedDuration}" else ""}",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(if (isSelected) XenderGreen else Color.Transparent, CircleShape)
                        .border(1.5.dp, if (isSelected) XenderGreen else BorderSubtle, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryList(
    sentItems: List<SharedFileItem>,
    receivedFiles: List<ReceivedFile>
) {
    val allEmpty = sentItems.isEmpty() && receivedFiles.isEmpty()

    if (allEmpty) {
        EmptyCategoryView(Icons.Default.History, "No transfer history yet")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (receivedFiles.isNotEmpty()) {
            item {
                Text(
                    text = "Received Files (${receivedFiles.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentEmerald,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(receivedFiles) { rec ->
                com.fastshare.app.ui.components.ReceivedFileItemRow(rec = rec)
            }
        }

        if (sentItems.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sent Files (${sentItems.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = XenderOrange,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(sentItems) { item ->
                com.fastshare.app.ui.components.SentFileItemRow(
                    item = item,
                    transferState = TransferState.Completed("History", sentItems.size, sentItems.sumOf { it.size })
                )
            }
        }
    }
}
