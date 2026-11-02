package com.niallbunting.assistantvoicerecorder.work

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.niallbunting.assistantvoicerecorder.data.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(WorkScheduler.RECORDING_ID) ?: return Result.failure()
        val repo = RecordingRepository(applicationContext); val r = repo.byId(id) ?: return Result.failure(); val settings = SettingsRepository(applicationContext).settings.first()
        if (!settings.notifySend || settings.apiUrl.isBlank()) return Result.success()
        return try {
            repo.updateStatus(id, notify = UploadStatus.UPLOADING)
            val json = JSONObject().put("recording_id", r.id).put("filename", r.filename).put("recorded_at", Instant.ofEpochMilli(r.recordedAt).toString()).put("duration_ms", r.durationMs).put("mime_type", "audio/mp4").toString().toByteArray()
            (URL(settings.apiUrl).openConnection() as HttpURLConnection).run { requestMethod="POST"; doOutput=true; setRequestProperty("Content-Type", "application/json"); connectTimeout=15_000; readTimeout=30_000; outputStream.use { it.write(json) }; if(responseCode in 200..299) { repo.updateStatus(id, notify=UploadStatus.SUCCESS); Result.success() } else { repo.updateStatus(id, notify=UploadStatus.FAILED, error="Metadata HTTP $responseCode"); Result.retry() } }
        } catch (e: Exception) { repo.updateStatus(id, notify=UploadStatus.FAILED, error=e.message ?: "Metadata request failed"); Result.retry() }
    }
}

class AudioUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(WorkScheduler.RECORDING_ID) ?: return Result.failure()
        val repo = RecordingRepository(applicationContext); val r = repo.byId(id) ?: return Result.failure(); val settings = SettingsRepository(applicationContext).settings.first()
        if (!settings.audioSend || settings.apiUrl.isBlank()) return Result.success()
        return try {
            val uri = Uri.parse(r.uri); val input = applicationContext.contentResolver.openInputStream(uri) ?: return Result.failure()
            repo.updateStatus(id, audio=UploadStatus.UPLOADING)
            val boundary = "----MeetingRecorder${System.currentTimeMillis()}"
            val result = (URL(settings.apiUrl).openConnection() as HttpURLConnection).run {
                requestMethod="POST"; doOutput=true; setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary"); setChunkedStreamingMode(8192); connectTimeout=20_000; readTimeout=60_000
                outputStream.buffered().use { out ->
                    fun text(name:String, value:String) { out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()) }
                    text("recording_id", r.id); out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${r.filename}\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray()); input.use { it.copyTo(out, 8192) }; out.write("\r\n--$boundary--\r\n".toByteArray())
                }; responseCode
            }
            if(result in 200..299) { repo.updateStatus(id, audio=UploadStatus.SUCCESS); Result.success() } else { repo.updateStatus(id, audio=UploadStatus.FAILED, error="Audio HTTP $result"); Result.retry() }
        } catch (e: Exception) { repo.updateStatus(id, audio=UploadStatus.FAILED, error=e.message ?: "Audio upload failed"); Result.retry() }
    }
}

class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).settings.first(); if (!settings.autoDelete) return Result.success()
        val repo = RecordingRepository(applicationContext); val cutoff = RecordingTiming.retentionCutoff(System.currentTimeMillis(), settings.retentionDays)
        repo.eligibleForDeletion(cutoff).forEach { r ->
            val uri=Uri.parse(r.uri); val deleted=runCatching { if(uri.scheme=="content") DocumentFile.fromSingleUri(applicationContext, uri)?.delete() == true else java.io.File(uri.path ?: "").delete() }.getOrDefault(false)
            if(deleted) repo.delete(r.id)
        }; return Result.success()
    }
}
