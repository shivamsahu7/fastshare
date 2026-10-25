package com.fastshare.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fastshare.app.client.FileDownloader
import com.fastshare.app.client.FileUploader
import com.fastshare.app.client.WifiConnector
import com.fastshare.app.data.*
import com.fastshare.app.qr.QrScannerView
import com.fastshare.app.service.TransferService
import com.fastshare.app.ui.components.TransferringSheet
import com.fastshare.app.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ReceiveScreenTab {
    DOWNLOAD,
    SEND_TO_HOST
}

enum class ReceiveMode {
    SCAN_QR,
    MANUAL_INPUT
}

@Composable
fun ReceiveScreen(
    wifiConnector: WifiConnector,
    fileDownloader: FileDownloader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaRepo = remember { MediaRepository(context) }
    val fileUploader = remember { FileUploader(context) }

    var currentScreenTab by remember { mutableStateOf(ReceiveScreenTab.DOWNLOAD) }
    var receiveMode by remember { mutableStateOf(ReceiveMode.SCAN_QR) }
    var transferState by remember { mutableStateOf<TransferState>(TransferState.Idle) }
    var uploadState by remember { mutableStateOf<TransferState>(TransferState.Idle) }
    var showTransferringSheet by remember { mutableStateOf(false) }
    val receiverTransferRecords = remember { mutableStateListOf<TransferRecord>() }

    // Server connection details
    var connectedServerUrl by remember { mutableStateOf("http://192.168.43.1:8080") }
    var manualSsid by remember { mutableStateOf("") }
    var manualPassword by remember { mutableStateOf("") }
    var manualServerUrl by remember { mutableStateOf("http://192.168.43.1:8080") }

    // Upload items selection state
    val selectedUploadItems = remember { mutableStateMapOf<String, SharedFileItem>() }
    var selectedCategoryTab by remember { mutableStateOf(CategoryTab.APPS) }
    var appsList by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var photosList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var videosList by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var contactsList by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var storageDrives by remember { mutableStateOf<List<StorageDrive>>(emptyList()) }
    var currentDirectory by remember { mutableStateOf<File?>(null) }
    var directoryFiles by remember { mutableStateOf<List<FileExplorerItem>>(emptyList()) }
    var isLoadingMedia by remember { mutableStateOf(false) }

    // Camera permission check
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Camera permission needed to scan QR code", Toast.LENGTH_SHORT).show()
        }
    }

    // Media permissions launcher for Send tab
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        coroutineScope.launch {
            photosList = mediaRepo.loadPhotos()
            videosList = mediaRepo.loadVideos()
        }
    }

    // Load media when Send to Host tab is active
    LaunchedEffect(currentScreenTab, selectedCategoryTab) {
        if (currentScreenTab == ReceiveScreenTab.SEND_TO_HOST) {
            isLoadingMedia = true
            when (selectedCategoryTab) {
                CategoryTab.APPS -> if (appsList.isEmpty()) appsList = mediaRepo.loadInstalledApps()
                CategoryTab.PHOTOS -> if (photosList.isEmpty()) photosList = mediaRepo.loadPhotos()
                CategoryTab.VIDEOS -> if (videosList.isEmpty()) videosList = mediaRepo.loadVideos()
                CategoryTab.FILES -> {
                    if (storageDrives.isEmpty()) {
                        storageDrives = mediaRepo.getStorageDrives()
                        if (currentDirectory == null && storageDrives.isNotEmpty()) {
                            currentDirectory = storageDrives.first().rootFile
                        }
                    }
                }
                CategoryTab.CONTACTS -> if (contactsList.isEmpty()) contactsList = mediaRepo.loadContacts()
                CategoryTab.HISTORY, CategoryTab.MUSIC -> {}
            }
            isLoadingMedia = false
        }
    }

    LaunchedEffect(currentDirectory) {
        val dir = currentDirectory
        if (dir != null && dir.exists() && dir.isDirectory) {
            directoryFiles = mediaRepo.listDirectory(dir)
        }
    }

    fun startDownload(serverUrl: String) {
        connectedServerUrl = serverUrl
        transferState = TransferState.Starting("Fetching files manifest...")
        showTransferringSheet = true
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
                        transferState = TransferState.Transferring(
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

                        val existingIndex = receiverTransferRecords.indexOfFirst {
                            it.name == currentFileName && it.direction == TransferDirection.RECEIVING
                        }
                        if (existingIndex >= 0) {
                            val cur = receiverTransferRecords[existingIndex]
                            receiverTransferRecords[existingIndex] = cur.copy(
                                status = TransferItemStatus.TRANSFERRING,
                                bytesTransferred = currentFileBytes,
                                progress = progress,
                                speedMbPerSec = speedMbPerSec
                            )
                        } else {
                            receiverTransferRecords.add(
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
                        val existingIndex = receiverTransferRecords.indexOfFirst {
                            it.name == fileName && it.direction == TransferDirection.RECEIVING
                        }
                        if (existingIndex >= 0) {
                            val cur = receiverTransferRecords[existingIndex]
                            receiverTransferRecords[existingIndex] = cur.copy(
                                status = TransferItemStatus.COMPLETED,
                                bytesTransferred = fileSize,
                                progress = 1f,
                                path = filePath
                            )
                        } else {
                            receiverTransferRecords.add(
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
                        TransferService.stop(context)
                        transferState = TransferState.Completed(
                            title = title,
                            fileCount = fileCount,
                            totalBytes = totalBytes,
                            destinationPath = targetDirectory
                        )
                    }

                    override fun onError(message: String) {
                        transferState = TransferState.Error(message)
                        val activeIdx = receiverTransferRecords.indexOfFirst {
                            it.status == TransferItemStatus.TRANSFERRING && it.direction == TransferDirection.RECEIVING
                        }
                        if (activeIdx >= 0) {
                            val cur = receiverTransferRecords[activeIdx]
                            receiverTransferRecords[activeIdx] = cur.copy(
                                status = TransferItemStatus.FAILED,
                                errorMessage = message
                            )
                        }
                    }
                }
            )
        }
    }

    fun connectAndReceive(ssid: String, password: String, serverUrl: String) {
        connectedServerUrl = serverUrl
        transferState = TransferState.Starting("Connecting to sender's Wi-Fi...")
        wifiConnector.connectToHotspot(
            ssid = ssid,
            password = password,
            callback = object : WifiConnector.ConnectionCallback {
                override fun onConnected(network: Network?) {
                    startDownload(serverUrl)
                }

                override fun onFailure(reason: String) {
                    transferState = TransferState.Error(reason)
                }
            }
        )
    }

    fun startUploadToHost() {
        val itemsToUpload = selectedUploadItems.values.toList()
        if (itemsToUpload.isEmpty()) {
            Toast.makeText(context, "Select at least 1 file to send", Toast.LENGTH_SHORT).show()
            return
        }

        val targetUrl = connectedServerUrl.ifEmpty { manualServerUrl }
        uploadState = TransferState.Starting("Connecting to Host...")
        selectedUploadItems.clear()
        showTransferringSheet = true

        itemsToUpload.forEach { item ->
            if (receiverTransferRecords.none { it.id == item.id }) {
                receiverTransferRecords.add(
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

        coroutineScope.launch {
            fileUploader.uploadBatch(
                serverBaseUrl = targetUrl,
                items = itemsToUpload,
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
                        uploadState = TransferState.Transferring(
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

                        val existingIndex = receiverTransferRecords.indexOfFirst {
                            it.name == currentFileName && it.direction == TransferDirection.SENDING
                        }
                        if (existingIndex >= 0) {
                            val cur = receiverTransferRecords[existingIndex]
                            receiverTransferRecords[existingIndex] = cur.copy(
                                status = TransferItemStatus.TRANSFERRING,
                                bytesTransferred = currentFileBytes,
                                progress = progress,
                                speedMbPerSec = speedMbPerSec
                            )
                        }
                    }

                    override fun onFileUploaded(fileName: String, fileIndex: Int, totalFiles: Int) {
                        val existingIndex = receiverTransferRecords.indexOfFirst {
                            it.name == fileName && it.direction == TransferDirection.SENDING
                        }
                        if (existingIndex >= 0) {
                            val cur = receiverTransferRecords[existingIndex]
                            receiverTransferRecords[existingIndex] = cur.copy(
                                status = TransferItemStatus.COMPLETED,
                                bytesTransferred = cur.size,
                                progress = 1f
                            )
                        }
                    }

                    override fun onBatchSuccess(totalFiles: Int, totalBytes: Long) {
                        TransferService.stop(context)
                        uploadState = TransferState.Completed(
                            title = "Upload to Host",
                            fileCount = totalFiles,
                            totalBytes = totalBytes
                        )
                    }

                    override fun onError(message: String) {
                        uploadState = TransferState.Error(message)
                        val activeIdx = receiverTransferRecords.indexOfFirst {
                            it.status == TransferItemStatus.TRANSFERRING && it.direction == TransferDirection.SENDING
                        }
                        if (activeIdx >= 0) {
                            val cur = receiverTransferRecords[activeIdx]
                            receiverTransferRecords[activeIdx] = cur.copy(
                                status = TransferItemStatus.FAILED,
                                errorMessage = message
                            )
                        }
                    }
                }
            )
        }
    }

    val activity = context as? android.app.Activity
    DisposableEffect(transferState, uploadState) {
        val isTransferring = transferState is TransferState.Transferring ||
                transferState is TransferState.Starting ||
                uploadState is TransferState.Transferring ||
                uploadState is TransferState.Starting

        if (isTransferring) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            wifiConnector.disconnect()
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val totalSelectedCount = selectedUploadItems.size
    val totalSelectedSize = selectedUploadItems.values.sumOf { it.size }

    val isSessionActive = (transferState is TransferState.Transferring ||
                           uploadState is TransferState.Transferring ||
                           transferState is TransferState.Starting ||
                           uploadState is TransferState.Starting)

    var showReceiverDisconnectDialog by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = isSessionActive) {
        showReceiverDisconnectDialog = true
    }

    if (showReceiverDisconnectDialog) {
        com.fastshare.app.ui.components.DisconnectConfirmationDialog(
            title = "Disconnect from Sender?",
            message = "An active file transfer is currently in progress. You cannot return to Home until you disconnect.\n\nDo you want to disconnect now?",
            confirmButtonText = "Disconnect & Exit",
            onConfirm = {
                showReceiverDisconnectDialog = false
                try {
                    wifiConnector.disconnect()
                } catch (ignored: Exception) {}
                TransferService.stop(context)
                transferState = TransferState.Idle
                uploadState = TransferState.Idle
                onBack()
            },
            onDismiss = {
                showReceiverDisconnectDialog = false
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgVoid)
    ) {
        // ---------------------------------------------------------------------------------
        // Top App Bar
        // ---------------------------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceBase)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (isSessionActive) {
                        showReceiverDisconnectDialog = true
                    } else {
                        onBack()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Home",
                    tint = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "FastShare Receiver",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = if (currentScreenTab == ReceiveScreenTab.DOWNLOAD) "Download files from Sender" else "Send files back to Sender",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            // Disconnect Button with Cross Icon (prominent red badge)
            val isConnectedToHost = isSessionActive || (connectedServerUrl.isNotEmpty() && connectedServerUrl != "http://192.168.43.1:8080") || receiverTransferRecords.isNotEmpty()
            if (isConnectedToHost) {
                Surface(
                    onClick = { showReceiverDisconnectDialog = true },
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

            // Theme Toggle Button
            IconButton(
                onClick = { ThemeState.toggle() },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SurfaceElevated)
                    .size(34.dp)
            ) {
                Icon(
                    imageVector = if (ThemeState.isDarkMode) Icons.Default.WbSunny else Icons.Default.Brightness4,
                    contentDescription = "Toggle Dark/Light Mode",
                    tint = if (ThemeState.isDarkMode) AccentAmber else AccentPurple,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            val activeState = when {
                transferState is TransferState.Transferring -> transferState as TransferState.Transferring
                uploadState is TransferState.Transferring -> uploadState as TransferState.Transferring
                else -> null
            }

            if (activeState != null) {
                Surface(
                    onClick = { showTransferringSheet = true },
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, RubyRedLight),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { activeState.progress },
                                modifier = Modifier.fillMaxSize(),
                                color = RubyRedLight,
                                trackColor = BorderSubtle,
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = "${(activeState.progress * 100).toInt()}%",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "${activeState.currentFileIndex}/${activeState.totalFiles}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${String.format("%.1f", activeState.speedMbPerSec)} MB/s",
                                fontSize = 9.sp,
                                color = AccentEmerald,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else if (receiverTransferRecords.isNotEmpty()) {
                // Transfer activity popup launcher button
                IconButton(
                    onClick = { showTransferringSheet = true },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(SurfaceElevated)
                        .size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = "Transfer Activity",
                        tint = RubyRedLight,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentCyan.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "RECEIVE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // ---------------------------------------------------------------------------------
        // Top Dual Navigation Mode: [ 📥 Download from Host ] and [ 📤 Send to Host ]
        // ---------------------------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceBase)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = { currentScreenTab = ReceiveScreenTab.DOWNLOAD },
                shape = RoundedCornerShape(14.dp),
                color = if (currentScreenTab == ReceiveScreenTab.DOWNLOAD) RubyRed else SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (currentScreenTab == ReceiveScreenTab.DOWNLOAD) RubyRedLight else BorderSubtle
                ),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = if (currentScreenTab == ReceiveScreenTab.DOWNLOAD) Color.White else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Download",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (currentScreenTab == ReceiveScreenTab.DOWNLOAD) Color.White else TextPrimary
                    )
                }
            }

            Surface(
                onClick = { currentScreenTab = ReceiveScreenTab.SEND_TO_HOST },
                shape = RoundedCornerShape(14.dp),
                color = if (currentScreenTab == ReceiveScreenTab.SEND_TO_HOST) RubyRed else SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (currentScreenTab == ReceiveScreenTab.SEND_TO_HOST) RubyRedLight else BorderSubtle
                ),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = if (currentScreenTab == ReceiveScreenTab.SEND_TO_HOST) Color.White else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Send to Host",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (currentScreenTab == ReceiveScreenTab.SEND_TO_HOST) Color.White else TextPrimary
                    )
                }
            }
        }

        // ---------------------------------------------------------------------------------
        // Tab 1: Download from Host Content
        // ---------------------------------------------------------------------------------
        if (currentScreenTab == ReceiveScreenTab.DOWNLOAD) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Mode Selector: [ Scan QR ] [ Manual Wi-Fi ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceCard)
                        .padding(4.dp)
                ) {
                    Button(
                        onClick = { receiveMode = ReceiveMode.SCAN_QR },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (receiveMode == ReceiveMode.SCAN_QR) RubyRed else Color.Transparent,
                            contentColor = if (receiveMode == ReceiveMode.SCAN_QR) Color.White else TextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan QR", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { receiveMode = ReceiveMode.MANUAL_INPUT },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (receiveMode == ReceiveMode.MANUAL_INPUT) RubyRed else Color.Transparent,
                            contentColor = if (receiveMode == ReceiveMode.MANUAL_INPUT) Color.White else TextSecondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Manual Wi-Fi", fontWeight = FontWeight.Bold)
                    }
                }

                // Active Transfer / Progress Card
                AnimatedVisibility(
                    visible = transferState is TransferState.Starting ||
                            transferState is TransferState.Transferring ||
                            transferState is TransferState.Completed ||
                            transferState is TransferState.Error
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when (val state = transferState) {
                                is TransferState.Starting -> {
                                    CircularProgressIndicator(color = RubyRed)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(state.message, color = TextPrimary, fontSize = 14.sp)
                                }

                                is TransferState.Transferring -> {
                                    Text(
                                        text = "Receiving (${state.currentFileIndex}/${state.totalFiles})",
                                        fontWeight = FontWeight.Bold,
                                        color = RubyRedLight,
                                        fontSize = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = state.currentFileName,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))
                                    LinearProgressIndicator(
                                        progress = { state.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = RubyRed,
                                        trackColor = SurfaceElevated
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${(state.progress * 100).toInt()}% (${formatBytes(state.overallBytes)} / ${formatBytes(state.overallTotalBytes)})",
                                            fontSize = 12.sp,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = String.format("🚀 %.1f MB/s", state.speedMbPerSec),
                                            fontSize = 12.sp,
                                            color = AccentEmerald,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                is TransferState.Completed -> {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Download Completed!", color = AccentEmerald, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("${state.fileCount} files received (${formatBytes(state.totalBytes)})", color = TextPrimary, fontSize = 14.sp)
                                    if (state.destinationPath != null) {
                                        Text("Saved to: ${state.destinationPath}", color = TextSecondary, fontSize = 12.sp)
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path), "*/*")
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Saved in Downloads folder", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald, contentColor = Color.White),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Open Downloads Folder", fontWeight = FontWeight.Bold)
                                    }
                                }

                                is TransferState.Error -> {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(40.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Transfer Interrupted", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(state.message, color = TextMuted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)

                                    Spacer(modifier = Modifier.height(14.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = {
                                                val target = connectedServerUrl.ifEmpty { manualServerUrl }
                                                if (target.isNotEmpty()) {
                                                    startDownload(target)
                                                } else {
                                                    transferState = TransferState.Idle
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("RESUME TRANSFER", fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = { transferState = TransferState.Idle },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Scan QR")
                                        }
                                    }
                                }

                                else -> {}
                            }
                        }
                    }
                }

                // Mode 1: Camera QR Scanner View
                if (receiveMode == ReceiveMode.SCAN_QR && transferState is TransferState.Idle) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Point camera at Sender's QR Code",
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                fontSize = 15.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            if (hasCameraPermission) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(320.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(BgVoid)
                                ) {
                                    QrScannerView { qrContent ->
                                        try {
                                            val json = JSONObject(qrContent)
                                            val ssid = json.getString("ssid")
                                            val password = json.optString("password", "")
                                            val serverUrl = json.getString("serverUrl")
                                            connectedServerUrl = serverUrl
                                            connectAndReceive(ssid, password, serverUrl)
                                        } catch (e: Exception) {
                                            if (qrContent.startsWith("http://") || qrContent.startsWith("https://")) {
                                                connectedServerUrl = qrContent
                                                startDownload(qrContent)
                                            } else {
                                                transferState = TransferState.Error("Invalid QR Code content")
                                            }
                                        }
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                    colors = ButtonDefaults.buttonColors(containerColor = RubyRed, contentColor = Color.White),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Grant Camera Permission", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Mode 2: Manual Wi-Fi & URL Form
                if (receiveMode == ReceiveMode.MANUAL_INPUT && transferState is TransferState.Idle) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Enter Sender Details Manually",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 16.sp
                            )

                            OutlinedTextField(
                                value = manualSsid,
                                onValueChange = { manualSsid = it },
                                label = { Text("Hotspot SSID / Name") },
                                placeholder = { Text("e.g. AndroidShare_1234") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RubyRed,
                                    unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )

                            OutlinedTextField(
                                value = manualPassword,
                                onValueChange = { manualPassword = it },
                                label = { Text("Hotspot Password (Optional)") },
                                placeholder = { Text("Password if protected") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RubyRed,
                                    unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )

                            OutlinedTextField(
                                value = manualServerUrl,
                                onValueChange = { manualServerUrl = it },
                                label = { Text("Server URL or Address") },
                                placeholder = { Text("http://192.168.43.1:8080") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RubyRed,
                                    unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = {
                                    connectedServerUrl = manualServerUrl
                                    if (manualSsid.isNotEmpty()) {
                                        connectAndReceive(manualSsid, manualPassword, manualServerUrl)
                                    } else {
                                        startDownload(manualServerUrl)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = RubyRed, contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connect & Download", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------------------
        // Tab 2: Send to Host Content (Receiver uploading files back to Host)
        // ---------------------------------------------------------------------------------
        if (currentScreenTab == ReceiveScreenTab.SEND_TO_HOST) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Active Upload Card if in progress
                AnimatedVisibility(
                    visible = uploadState is TransferState.Starting ||
                            uploadState is TransferState.Transferring ||
                            uploadState is TransferState.Completed ||
                            uploadState is TransferState.Error
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when (val state = uploadState) {
                                is TransferState.Starting -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(color = RubyRed, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(state.message, color = TextPrimary, fontSize = 13.sp)
                                    }
                                }

                                is TransferState.Transferring -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Sending (${state.currentFileIndex}/${state.totalFiles}): ${state.currentFileName}",
                                            fontWeight = FontWeight.Bold,
                                            color = RubyRedLight,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = String.format("🚀 %.1f MB/s", state.speedMbPerSec),
                                            fontSize = 12.sp,
                                            color = AccentEmerald,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { state.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = RubyRed,
                                        trackColor = SurfaceElevated
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${(state.progress * 100).toInt()}%",
                                            fontSize = 11.sp,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "${formatBytes(state.overallBytes)} / ${formatBytes(state.overallTotalBytes)}",
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }

                                is TransferState.Completed -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(24.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "✓ Sent ${state.fileCount} files to Host!",
                                                color = AccentEmerald,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                        TextButton(onClick = { uploadState = TransferState.Idle }) {
                                            Text("Dismiss", color = TextSecondary, fontSize = 12.sp)
                                        }
                                    }
                                }

                                is TransferState.Error -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(24.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Upload interrupted: ${state.message}",
                                                color = Color(0xFFEF4444),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row {
                                            TextButton(onClick = { startUploadToHost() }) {
                                                Text("Resume", color = RubyRedLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                            TextButton(onClick = { uploadState = TransferState.Idle }) {
                                                Text("Dismiss", color = TextSecondary, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }

                                else -> {}
                            }
                        }
                    }
                }

                // Category Tab Bar (Apps, Photos, Videos, Files, Contacts)
                CategoryTabBar(
                    selectedTab = selectedCategoryTab,
                    onSelectTab = { selectedCategoryTab = it }
                )

                // Category Content
                Box(modifier = Modifier.weight(1f)) {
                    if (isLoadingMedia) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = RubyRed)
                        }
                    } else {
                        when (selectedCategoryTab) {
                            CategoryTab.APPS -> AppsGrid(
                                apps = appsList,
                                selectedItems = selectedUploadItems,
                                onToggle = { app ->
                                    val existing = selectedUploadItems.values.firstOrNull { it.relativePath == "${app.name}.apk" }
                                    if (existing != null) {
                                        selectedUploadItems.remove(existing.id)
                                    } else {
                                        val item = mediaRepo.convertAppToSharedFile(app)
                                        selectedUploadItems[item.id] = item
                                    }
                                }
                            )

                            CategoryTab.PHOTOS -> {
                                PhotosDayWiseGrid(
                                    photos = photosList,
                                    selectedItems = selectedUploadItems,
                                    onTogglePhoto = { photo ->
                                        val existing = selectedUploadItems.values.firstOrNull { it.uri == photo.uri }
                                        if (existing != null) {
                                            selectedUploadItems.remove(existing.id)
                                        } else {
                                            val item = SharedFileItem(
                                                id = UUID.randomUUID().toString(),
                                                uri = photo.uri,
                                                name = photo.name,
                                                relativePath = photo.name,
                                                size = photo.size,
                                                mimeType = photo.mimeType
                                            )
                                            selectedUploadItems[item.id] = item
                                        }
                                    },
                                    onToggleDay = { dayGroup ->
                                        val allDaySelected = dayGroup.photos.isNotEmpty() && dayGroup.photos.all { photo ->
                                            selectedUploadItems.values.any { it.uri == photo.uri }
                                        }
                                        if (allDaySelected) {
                                            val urisToRemove = dayGroup.photos.map { it.uri }.toSet()
                                            val toRemoveIds = selectedUploadItems.filter { it.value.uri in urisToRemove }.keys.toList()
                                            toRemoveIds.forEach { selectedUploadItems.remove(it) }
                                        } else {
                                            dayGroup.photos.forEach { photo ->
                                                if (!selectedUploadItems.values.any { it.uri == photo.uri }) {
                                                    val item = SharedFileItem(
                                                        id = UUID.randomUUID().toString(),
                                                        uri = photo.uri,
                                                        name = photo.name,
                                                        relativePath = photo.name,
                                                        size = photo.size,
                                                        mimeType = photo.mimeType
                                                    )
                                                    selectedUploadItems[item.id] = item
                                                }
                                            }
                                        }
                                    }
                                )
                            }

                            CategoryTab.VIDEOS -> {
                                VideosGrid(
                                    videos = videosList,
                                    selectedItems = selectedUploadItems,
                                    onToggle = { video ->
                                        val existing = selectedUploadItems.values.firstOrNull { it.uri == video.uri }
                                        if (existing != null) {
                                            selectedUploadItems.remove(existing.id)
                                        } else {
                                            val item = SharedFileItem(
                                                id = UUID.randomUUID().toString(),
                                                uri = video.uri,
                                                name = video.name,
                                                relativePath = video.name,
                                                size = video.size,
                                                mimeType = "video/mp4"
                                            )
                                            selectedUploadItems[item.id] = item
                                        }
                                    }
                                )
                            }

                            CategoryTab.FILES -> {
                                StorageExplorerView(
                                    drives = storageDrives,
                                    currentDirectory = currentDirectory,
                                    directoryFiles = directoryFiles,
                                    isLoading = false,
                                    selectedItems = selectedUploadItems,
                                    mediaRepo = mediaRepo,
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
                                            val existing = selectedUploadItems.values.firstOrNull { it.name == file.name && it.size == file.length() }
                                            if (existing != null) {
                                                selectedUploadItems.remove(existing.id)
                                            } else {
                                                val item = mediaRepo.convertDiskFileToSharedItem(file, rootBase)
                                                selectedUploadItems[item.id] = item
                                            }
                                        }
                                    },
                                    onSelectFolder = { folder ->
                                        coroutineScope.launch {
                                            val folderItems = mediaRepo.convertFolderToSharedItems(folder)
                                            folderItems.forEach { item ->
                                                selectedUploadItems[item.id] = item
                                            }
                                            Toast.makeText(context, "Added ${folderItems.size} items from '${folder.name}'", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onDeselectFolder = { folder ->
                                        val toRemove = selectedUploadItems.filter {
                                            it.value.relativePath.startsWith("${folder.name}/") || it.value.name == folder.name
                                        }.keys.toList()
                                        toRemove.forEach { selectedUploadItems.remove(it) }
                                    }
                                )
                            }

                            CategoryTab.CONTACTS -> {
                                ContactsList(
                                    contacts = contactsList,
                                    selectedItems = selectedUploadItems,
                                    onToggle = { contact ->
                                        coroutineScope.launch {
                                            val existing = selectedUploadItems.values.firstOrNull { it.name.startsWith(contact.name) }
                                            if (existing != null) {
                                                selectedUploadItems.remove(existing.id)
                                            } else {
                                                val item = mediaRepo.exportContactToVcf(contact)
                                                selectedUploadItems[item.id] = item
                                            }
                                        }
                                    }
                                )
                            }
                            else -> {}
                        }
                    }
                }

                // Docked Bottom Send Bar for Receiver
                AnimatedVisibility(
                    visible = totalSelectedCount > 0,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
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
                                        text = "⚡ $totalSelectedCount items",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = TextPrimary,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "• ${formatBytes(totalSelectedSize)}",
                                        color = TextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                                Text(
                                    text = "Clear selection",
                                    color = Color(0xFFEF4444),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable { selectedUploadItems.clear() }
                                )
                            }

                            Button(
                                onClick = { startUploadToHost() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RubyRed,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SEND TO HOST ($totalSelectedCount)",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Docked Transferring Mini-Bar for Receiver
        val isTransferActive = transferState is TransferState.Transferring || uploadState is TransferState.Transferring
        if (isTransferActive || receiverTransferRecords.isNotEmpty()) {
            val curActive = when {
                transferState is TransferState.Transferring -> transferState as TransferState.Transferring
                uploadState is TransferState.Transferring -> uploadState as TransferState.Transferring
                else -> null
            }
            Surface(
                onClick = { showTransferringSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(18.dp),
                color = SurfaceElevated.copy(alpha = 0.98f),
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp,
                    if (curActive != null) RubyRedLight else BorderSubtle
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { curActive?.progress ?: 0f },
                            color = RubyRedLight,
                            trackColor = BorderSubtle,
                            strokeWidth = 3.5.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = "${((curActive?.progress ?: 0f) * 100).toInt()}%",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        if (curActive != null) {
                            Text(
                                text = "${curActive.currentFileIndex}/${curActive.totalFiles}: ${curActive.currentFileName}",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatBytes(curActive.overallBytes)} of ${formatBytes(curActive.overallTotalBytes)} • ${String.format("%.1f", curActive.speedMbPerSec)} MB/s",
                                color = RubyRedLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "Transfer Records (${receiverTransferRecords.size})",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Tap to view incoming & outgoing files",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Expand",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        if (showTransferringSheet) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showTransferringSheet = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                TransferringSheet(
                    transferState = if (uploadState is TransferState.Transferring) uploadState else transferState,
                    records = receiverTransferRecords,
                    onRetryTransfer = { record ->
                        if (record.direction == TransferDirection.RECEIVING) {
                            startDownload(connectedServerUrl)
                        } else {
                            startUploadToHost()
                        }
                    },
                    onCollapse = { showTransferringSheet = false }
                )
            }
        }
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
