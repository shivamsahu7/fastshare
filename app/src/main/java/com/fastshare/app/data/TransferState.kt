package com.fastshare.app.data

sealed class TransferState {
    object Idle : TransferState()
    data class Starting(val message: String) : TransferState()
    data class HotspotActive(
        val ssid: String,
        val password: String,
        val serverUrl: String,
        val title: String,
        val fileCount: Int,
        val totalSize: Long
    ) : TransferState()
    data class Transferring(
        val currentFileName: String,
        val currentFileIndex: Int,
        val totalFiles: Int,
        val currentFileBytes: Long,
        val currentFileSize: Long,
        val overallBytes: Long,
        val overallTotalBytes: Long,
        val speedMbPerSec: Double,
        val progress: Float
    ) : TransferState()
    data class Completed(
        val title: String,
        val fileCount: Int,
        val totalBytes: Long,
        val destinationPath: String? = null
    ) : TransferState()
    data class Error(val message: String) : TransferState()
}

enum class TransferDirection {
    SENDING,
    RECEIVING
}

enum class TransferItemStatus {
    QUEUED,
    TRANSFERRING,
    COMPLETED,
    FAILED
}

data class TransferRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val size: Long,
    val direction: TransferDirection,
    val status: TransferItemStatus = TransferItemStatus.QUEUED,
    val bytesTransferred: Long = 0L,
    val progress: Float = 0f,
    val speedMbPerSec: Double = 0.0,
    val path: String? = null,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
