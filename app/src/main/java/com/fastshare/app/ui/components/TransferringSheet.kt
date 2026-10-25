package com.fastshare.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastshare.app.data.SharedFileItem
import com.fastshare.app.data.TransferDirection
import com.fastshare.app.data.TransferItemStatus
import com.fastshare.app.data.TransferRecord
import com.fastshare.app.data.TransferState
import com.fastshare.app.server.ReceivedFile
import com.fastshare.app.ui.theme.*

/**
 * Unified Transferring Sheet / Popup
 *
 * Requirements addressed:
 * - Removed separate Sent / Received tabs: all files are shown in a single unified list!
 * - Each item row displays clear status badges indicating whether it is Sending, Receiving, Sent, Received, or Failed.
 * - Live speed and total transferred bandwidth banner at the top.
 * - Circular progress ring with live percentage and speeds.
 * - Resume / Retry button on failed transfers.
 * - Empty state ("No records") when no transfers are present.
 */
@Composable
fun TransferringSheet(
    transferState: TransferState,
    records: List<TransferRecord> = emptyList(),
    sentItems: List<SharedFileItem> = emptyList(),
    receivedFiles: List<ReceivedFile> = emptyList(),
    onRetryTransfer: ((TransferRecord) -> Unit)? = null,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Derive speed and overall transferred size
    val currentSpeedMb = when (transferState) {
        is TransferState.Transferring -> transferState.speedMbPerSec
        else -> records.firstOrNull { it.status == TransferItemStatus.TRANSFERRING }?.speedMbPerSec ?: 0.0
    }

    val overallBytesTransferred = when (transferState) {
        is TransferState.Transferring -> transferState.overallBytes
        is TransferState.Completed -> transferState.totalBytes
        else -> records.sumOf { it.bytesTransferred }
    }

    val speedText = if (currentSpeedMb > 0.0) {
        String.format("%.1f MB/s", currentSpeedMb)
    } else {
        "0 B/s"
    }

    val totalTransferredText = formatBytes(overallBytesTransferred)

    // Build the unified list of transfers
    val unifiedList = remember(records.toList(), sentItems, receivedFiles, transferState) {
        val list = mutableListOf<TransferRecord>()
        list.addAll(records)

        // Add any sentItems not already in list
        sentItems.forEach { item ->
            if (list.none { it.id == item.id || it.name == item.name }) {
                val isActive = (transferState is TransferState.Transferring && transferState.currentFileName == item.name)
                val isCompleted = (transferState is TransferState.Completed)
                val status = when {
                    isActive -> TransferItemStatus.TRANSFERRING
                    isCompleted -> TransferItemStatus.COMPLETED
                    else -> TransferItemStatus.QUEUED
                }
                val bytesTransferred = if (isActive && transferState is TransferState.Transferring) {
                    transferState.currentFileBytes
                } else if (isCompleted) {
                    item.size
                } else {
                    0L
                }
                val progress = if (isActive && transferState is TransferState.Transferring) {
                    transferState.progress
                } else if (isCompleted) {
                    1f
                } else {
                    0f
                }

                list.add(
                    TransferRecord(
                        id = item.id,
                        name = item.name,
                        size = item.size,
                        direction = TransferDirection.SENDING,
                        status = status,
                        bytesTransferred = bytesTransferred,
                        progress = progress,
                        speedMbPerSec = if (isActive && transferState is TransferState.Transferring) transferState.speedMbPerSec else 0.0
                    )
                )
            }
        }

        // Add any receivedFiles not already in list
        receivedFiles.forEach { rec ->
            if (list.none { it.name == rec.name }) {
                list.add(
                    TransferRecord(
                        id = rec.path,
                        name = rec.name,
                        size = rec.size,
                        direction = TransferDirection.RECEIVING,
                        status = TransferItemStatus.COMPLETED,
                        bytesTransferred = rec.size,
                        progress = 1f,
                        path = rec.path
                    )
                )
            }
        }

        // Active receiving item from transferState if not already in list
        if (transferState is TransferState.Transferring) {
            val alreadyInList = list.any { it.name == transferState.currentFileName }
            if (!alreadyInList) {
                list.add(
                    0,
                    TransferRecord(
                        name = transferState.currentFileName,
                        size = transferState.currentFileSize,
                        direction = TransferDirection.RECEIVING,
                        status = TransferItemStatus.TRANSFERRING,
                        bytesTransferred = transferState.currentFileBytes,
                        progress = transferState.progress,
                        speedMbPerSec = transferState.speedMbPerSec
                    )
                )
            }
        }

        // Failed transfer representation if error
        if (transferState is TransferState.Error) {
            val alreadyHasError = list.any { it.status == TransferItemStatus.FAILED }
            if (!alreadyHasError) {
                list.add(
                    0,
                    TransferRecord(
                        name = "Transfer Interrupted",
                        size = 0L,
                        direction = TransferDirection.RECEIVING,
                        status = TransferItemStatus.FAILED,
                        errorMessage = transferState.message
                    )
                )
            }
        }

        list
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgVoid)
    ) {
        // ---------------------------------------------------------------------------------
        // 1. Top Header Bar (Ruby Red Theme)
        // ---------------------------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RubyRed)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onCollapse,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Transferring",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (unifiedList.isNotEmpty()) "${unifiedList.size} file(s) in session" else "No active transfers",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            // Close button
            IconButton(
                onClick = onCollapse,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ---------------------------------------------------------------------------------
        // 2. High-Speed Bandwidth & Transferred Size Banner
        // ---------------------------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(RubyRed, RubyRedDark)
                    )
                )
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Speed",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = speedText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DataUsage,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Transferred",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = totalTransferredText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------------------------
        // 3. Unified Transfer Activity List (Sent + Received + Queued + Failed)
        // ---------------------------------------------------------------------------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(BgVoid),
            contentAlignment = Alignment.Center
        ) {
            if (unifiedList.isEmpty()) {
                NoRecordsEmptyView()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(unifiedList, key = { it.id }) { record ->
                        TransferRecordRow(
                            record = record,
                            onRetry = { onRetryTransfer?.invoke(record) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single Unified Transfer Row showing exact icon badge:
 * - SENDING / RECEIVING / SENT / RECEIVED / FAILED
 */
@Composable
fun TransferRecordRow(
    record: TransferRecord,
    onRetry: () -> Unit
) {
    val isSending = record.direction == TransferDirection.SENDING
    val isTransferring = record.status == TransferItemStatus.TRANSFERRING
    val isCompleted = record.status == TransferItemStatus.COMPLETED
    val isFailed = record.status == TransferItemStatus.FAILED
    val isQueued = record.status == TransferItemStatus.QUEUED

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isFailed) Color(0xFFEF4444).copy(alpha = 0.5f)
            else if (isTransferring) RubyRedLight.copy(alpha = 0.4f)
            else BorderSubtle
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Icon Badge with Direction Indicator
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            isFailed -> Color(0xFFEF4444).copy(alpha = 0.15f)
                            isCompleted -> AccentEmerald.copy(alpha = 0.15f)
                            isTransferring -> RubyRed.copy(alpha = 0.18f)
                            else -> SurfaceElevated
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isFailed -> Icons.Default.ErrorOutline
                        isCompleted -> if (isSending) Icons.Default.CloudDone else Icons.Default.CheckCircle
                        isTransferring -> if (isSending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = when {
                        isFailed -> Color(0xFFEF4444)
                        isCompleted -> AccentEmerald
                        isTransferring -> if (isSending) RubyRedLight else AccentCyan
                        else -> TextSecondary
                    },
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center details: Name + Status Subtitle
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Direction & Status Badge
                    val (badgeText, badgeBg, badgeTextColor) = when {
                        isFailed -> Triple("FAILED", Color(0xFFEF4444).copy(alpha = 0.2f), Color(0xFFEF4444))
                        isTransferring && isSending -> Triple("SENDING", RubyRed.copy(alpha = 0.25f), RubyRedLight)
                        isTransferring && !isSending -> Triple("RECEIVING", AccentCyan.copy(alpha = 0.25f), AccentCyan)
                        isCompleted && isSending -> Triple("SENT", AccentEmerald.copy(alpha = 0.2f), AccentEmerald)
                        isCompleted && !isSending -> Triple("RECEIVED", AccentEmerald.copy(alpha = 0.2f), AccentEmerald)
                        isQueued -> Triple("QUEUED", SurfaceElevated, TextSecondary)
                        else -> Triple(if (isSending) "SENT" else "RECEIVED", SurfaceElevated, TextSecondary)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = badgeTextColor
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = record.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Progress / Size / Speed Subtitle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        isTransferring -> {
                            val curBytes = formatBytes(record.bytesTransferred)
                            val totalBytes = formatBytes(record.size)
                            val speed = if (record.speedMbPerSec > 0) " • ${String.format("%.1f", record.speedMbPerSec)} MB/s" else ""
                            Text(
                                text = "$curBytes / $totalBytes$speed",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        isCompleted -> {
                            Text(
                                text = "${formatBytes(record.size)} • ${if (isSending) "Sent successfully" else "Received & Saved"}",
                                fontSize = 11.sp,
                                color = AccentEmerald
                            )
                        }
                        isFailed -> {
                            Text(
                                text = record.errorMessage ?: "Transfer failed. Tap Resume.",
                                fontSize = 11.sp,
                                color = Color(0xFFEF4444),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        isQueued -> {
                            Text(
                                text = "${formatBytes(record.size)} • Queued",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Right Status Ring / Action Badge
            when {
                isTransferring -> {
                    // Live Circular Progress Ring
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { record.progress },
                            modifier = Modifier.fillMaxSize(),
                            color = if (isSending) RubyRedLight else AccentCyan,
                            trackColor = BorderSubtle,
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "${(record.progress * 100).toInt()}%",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }
                isCompleted -> {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(AccentEmerald),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                isFailed -> {
                    // Resume / Retry Button
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = RubyRed),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Resume",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Resume",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                isQueued -> {
                    Text(
                        text = "Waiting",
                        fontSize = 11.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Clock empty view when no records are present
 */
@Composable
fun NoRecordsEmptyView() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(SurfaceElevated)
                .border(1.5.dp, BorderSubtle, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "No records",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        Text(
            text = "Sending and receiving activities will show here",
            fontSize = 12.sp,
            color = TextMuted
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.1f KB", kb)
        else -> "$bytes B"
    }
}

@Composable
fun ReceivedFileItemRow(rec: ReceivedFile) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentEmerald.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AccentEmerald,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rec.name,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatBytes(rec.size)} • Received",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun SentFileItemRow(item: SharedFileItem, transferState: TransferState = TransferState.Idle) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(RubyRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = null,
                    tint = RubyRedLight,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
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
                    text = "${formatBytes(item.size)} • Sent",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

