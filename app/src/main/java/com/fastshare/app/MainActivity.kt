package com.fastshare.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.fastshare.app.client.FileDownloader
import com.fastshare.app.client.WifiConnector
import com.fastshare.app.data.TransferState
import com.fastshare.app.server.HotspotManager
import com.fastshare.app.ui.screens.HomeScreen
import com.fastshare.app.ui.screens.ReceiveScreen
import com.fastshare.app.ui.screens.SendScreen
import com.fastshare.app.ui.theme.BgVoid
import com.fastshare.app.ui.theme.FastShareTheme

enum class AppScreen {
    HOME,
    SEND,
    QR_SHARING,
    RECEIVE
}

class MainActivity : ComponentActivity() {

    private lateinit var hotspotManager: HotspotManager
    private lateinit var wifiConnector: WifiConnector
    private lateinit var fileDownloader: FileDownloader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hotspotManager = HotspotManager(this)
        wifiConnector = WifiConnector(this)
        fileDownloader = FileDownloader(this)

        setContent {
            FastShareTheme {
                MainAppNavHost(
                    hotspotManager = hotspotManager,
                    wifiConnector = wifiConnector,
                    fileDownloader = fileDownloader
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hotspotManager.stopHotspot()
        wifiConnector.disconnect()
        com.fastshare.app.service.TransferService.stop(this)
    }
}

@Composable
fun MainAppNavHost(
    hotspotManager: HotspotManager,
    wifiConnector: WifiConnector,
    fileDownloader: FileDownloader
) {
    var currentScreen by remember { mutableStateOf(AppScreen.SEND) }

    // Shared server & hotspot states for full-screen QR & bidirectional transfer
    var fileServer by remember { mutableStateOf<com.fastshare.app.server.FileServer?>(null) }
    var transferState by remember { mutableStateOf<TransferState>(TransferState.Idle) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val receivedFiles = remember { mutableStateListOf<com.fastshare.app.server.ReceivedFile>() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? ComponentActivity

    val isTransferActive = (fileServer != null || currentScreen == AppScreen.QR_SHARING || transferState is TransferState.Transferring || transferState is TransferState.Starting)

    DisposableEffect(isTransferActive) {
        if (isTransferActive) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun stopSharingSession() {
        try {
            fileServer?.notifyDisconnect()
        } catch (e: Exception) {}
        try {
            fileServer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        fileServer = null
        hotspotManager.stopHotspot()
        fileDownloader.activeNetwork = null
        transferState = TransferState.Idle
        qrBitmap = null
        com.fastshare.app.service.TransferService.stop(context)
    }

    var showSenderDisconnectDialog by remember { mutableStateOf(false) }

    if (showSenderDisconnectDialog) {
        com.fastshare.app.ui.components.DisconnectConfirmationDialog(
            title = "Stop Sharing Session?",
            message = "An active sharing session is running. Do you want to stop sharing and disconnect?",
            confirmButtonText = "Disconnect",
            onConfirm = {
                showSenderDisconnectDialog = false
                stopSharingSession()
            },
            onDismiss = {
                showSenderDisconnectDialog = false
            }
        )
    }

    // System Back button handling (Requirement 1: No home screen needed)
    BackHandler(enabled = true) {
        when (currentScreen) {
            AppScreen.QR_SHARING -> currentScreen = AppScreen.SEND
            AppScreen.SEND -> {
                if (fileServer != null) {
                    showSenderDisconnectDialog = true
                } else {
                    activity?.finish()
                }
            }
            AppScreen.RECEIVE -> {
                currentScreen = AppScreen.SEND
            }
            AppScreen.HOME -> {
                activity?.finish()
            }
        }
    }

    // Request necessary transfer permissions on startup
    val permissionsToRequest = remember {
        mutableListOf<String>().apply {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                if (Build.VERSION.SDK_INT >= 34) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BgVoid
    ) { innerPadding ->
        Crossfade(
            targetState = currentScreen,
            label = "MainScreenTransition"
        ) { screen ->
            when (screen) {
                AppScreen.HOME -> {
                    HomeScreen(
                        onNavigateToSend = { currentScreen = AppScreen.SEND },
                        onNavigateToReceive = { currentScreen = AppScreen.RECEIVE }
                    )
                }

                AppScreen.SEND -> {
                    SendScreen(
                        hotspotManager = hotspotManager,
                        wifiConnector = wifiConnector,
                        fileDownloader = fileDownloader,
                        fileServer = fileServer,
                        transferState = transferState,
                        onStartServer = { server, qr, state ->
                            fileServer = server
                            qrBitmap = qr
                            transferState = state
                            server.onFileReceived = { name, size, path ->
                                receivedFiles.add(com.fastshare.app.server.ReceivedFile(name, size, path))
                            }
                        },
                        onStopSharing = {
                            stopSharingSession()
                        },
                        onNavigateToQrSharing = {
                            currentScreen = AppScreen.QR_SHARING
                        },
                        onBack = {
                            if (fileServer != null) {
                                showSenderDisconnectDialog = true
                            } else {
                                activity?.finish()
                            }
                        }
                    )
                }

                AppScreen.QR_SHARING -> {
                    com.fastshare.app.ui.screens.QrSharingScreen(
                        transferState = transferState,
                        qrBitmap = qrBitmap,
                        queuedFileCount = fileServer?.getTotalCount() ?: 0,
                        queuedTotalSize = fileServer?.getTotalSize() ?: 0L,
                        receivedFiles = receivedFiles,
                        onQueueMoreFiles = {
                            currentScreen = AppScreen.SEND
                        },
                        onStopSharing = {
                            stopSharingSession()
                            currentScreen = AppScreen.HOME
                        }
                    )
                }

                AppScreen.RECEIVE -> {
                    ReceiveScreen(
                        wifiConnector = wifiConnector,
                        fileDownloader = fileDownloader,
                        onBack = { currentScreen = AppScreen.SEND }
                    )
                }
            }
        }
    }
}
