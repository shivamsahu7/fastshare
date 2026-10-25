package com.fastshare.app.data

import android.net.Uri

data class SharedFileItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val size: Long,
    val mimeType: String
)

data class ShareBatch(
    val title: String,
    val items: List<SharedFileItem>
) {
    val totalSize: Long get() = items.sumOf { it.size }
    val totalCount: Int get() = items.size
}

data class QrPayload(
    val ssid: String,
    val password: String,
    val serverUrl: String,
    val title: String,
    val fileCount: Int,
    val totalSize: Long
)
