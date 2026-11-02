package com.niallbunting.assistantvoicerecorder.data

data class Recording(
    val id: String,
    val filename: String,
    val uri: String,
    val recordedAt: Long,
    val durationMs: Long,
    val audioStatus: UploadStatus,
    val notifyStatus: UploadStatus,
    val error: String?
)

enum class UploadStatus { DISABLED, PENDING, UPLOADING, SUCCESS, FAILED }
