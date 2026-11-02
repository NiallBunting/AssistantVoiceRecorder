package com.niallbunting.assistantvoicerecorder.recording

import android.app.*
import android.content.*
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.niallbunting.assistantvoicerecorder.R
import com.niallbunting.assistantvoicerecorder.assistant.AssistantService
import com.niallbunting.assistantvoicerecorder.data.*
import com.niallbunting.assistantvoicerecorder.work.WorkScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    companion object { const val START = "START"; const val STOP = "STOP"; const val DISCARD = "DISCARD"; const val ACTION_RECORDING_STATE = "com.niallbunting.assistantvoicerecorder.RECORDING_STATE"; const val ACTION_MICROPHONE_UNAVAILABLE = "com.niallbunting.assistantvoicerecorder.MICROPHONE_UNAVAILABLE"; const val EXTRA_RECORDING_ACTIVE = "active"; private const val CHANNEL = "recording"; private const val NOTIFICATION = 42; private const val WARNING_NOTIFICATION = 43
        private const val STATE_PREFS = "recording_state"; private const val STATE_KEY = "active"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(START))
        fun isRecording(context: Context) = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getBoolean(STATE_KEY, false)
        /** A process kill skips onDestroy(), so do not trust the persisted flag on a fresh launch. */
        @Suppress("DEPRECATION")
        fun clearStaleState(context: Context) {
            if (!isRecording(context)) return
            val serviceIsRunning = context.getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == RecordingService::class.java.name }
            if (!serviceIsRunning) context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(STATE_KEY, false).apply()
        }
        fun isCallInProgress(context: Context): Boolean = context.getSystemService(AudioManager::class.java).mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: MediaRecorder? = null; private var output: Uri? = null; private var recordingId: String? = null; private var finalFilename: String? = null; private var startedAt = 0L
    @Volatile private var initializing = false
    @Volatile private var stopRequested = false
    @Volatile private var discardRequested = false
    private val finalizing = AtomicBoolean(false)
    private val filenameTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { when(intent?.action) { START -> { stopRequested = false; discardRequested = false; if(recorder == null && !initializing) begin() }; STOP -> requestFinish(discard = false); DISCARD -> requestFinish(discard = true) }; return START_NOT_STICKY }
    private fun begin() = scope.launch {
        initializing = true
        try {
            createChannel()
            if (isCallInProgress(this@RecordingService)) {
                reportMicrophoneUnavailable()
                stopSelf()
                return@launch
            }
            startForeground(NOTIFICATION, notification())
            val filenameTime = System.currentTimeMillis(); val finalName = "${filenameTimestamp.format(Instant.ofEpochMilli(filenameTime))}.m4a"; val name = finalName.removeSuffix(".m4a") + ".aac"
            val settings = SettingsRepository(this@RecordingService).folderUri()
            val uri = if (settings.isNotBlank()) {
                val tree = DocumentFile.fromTreeUri(this@RecordingService, Uri.parse(settings)) ?: error("Saved folder is unavailable")
                // The MIME type matters here: DocumentsProvider appends an extension based on
                // it, so using audio/mp4 turned a temporary `*.aac` into `*.aac.m4a`.
                tree.createFile("audio/aac", name)?.uri ?: error("Cannot create a recording in the selected folder")
            } else Uri.fromFile(File(getExternalFilesDir(null), name))
            val pfd = if (uri.scheme == "content") contentResolver.openFileDescriptor(uri, "rw") ?: error("Cannot open selected folder") else null
            val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this@RecordingService) else @Suppress("DEPRECATION") MediaRecorder()
            media.setAudioSource(MediaRecorder.AudioSource.MIC); media.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS); media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); media.setAudioEncodingBitRate(128000); media.setAudioSamplingRate(44100)
            if (pfd != null) media.setOutputFile(pfd.fileDescriptor) else media.setOutputFile(uri.path)
            media.prepare(); media.start(); pfd?.close()
            val started = System.currentTimeMillis()
            val id = UUID.randomUUID().toString(); val s = SettingsRepository(this@RecordingService).settings.first()
            // Show the real, crash-safe file while it is being written. It becomes finalName
            // only after the recorder is stopped and the stream has been remuxed.
            RecordingRepository(this@RecordingService).insert(Recording(id, name, uri.toString(), started, 0, if(s.audioSend) UploadStatus.PENDING else UploadStatus.DISABLED, if(s.notifySend) UploadStatus.PENDING else UploadStatus.DISABLED, null))
            recorder = media; output = uri; recordingId = id; finalFilename = finalName; startedAt = started; setRecordingState(true)
            stopWhenCallStarts()
            if (stopRequested) finishRecording()
        } catch (t: Throwable) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } finally { initializing = false }
    }
    private fun requestFinish(discard: Boolean) {
        stopRequested = true
        discardRequested = discardRequested || discard
        finishRecording()
    }
    private fun finishRecording() {
        // A stop can arrive while MediaRecorder is still preparing. Wait for begin() to
        // hand us a recorder, then finish it, rather than losing the request.
        if (recorder == null) return
        if (!finalizing.compareAndSet(false, true)) return
        scope.launch { finalizeRecording(discardRequested) }
    }
    private suspend fun finalizeRecording(discard: Boolean) {
        val media = recorder ?: return
        // MediaRecorder streams an independently playable ADTS/AAC stream while recording.
        val id = recordingId
        try { media.stop() } catch (_: RuntimeException) { } finally { runCatching { media.reset() }; runCatching { media.release() }; recorder = null }
        if (discard) {
            output?.let { uri -> runCatching { if (uri.scheme == "content") DocumentFile.fromSingleUri(this@RecordingService, uri)?.delete() else File(uri.path ?: "").delete() } }
            if (id != null) runCatching { RecordingRepository(this@RecordingService).delete(id) }
        } else if (id != null) {
            val repo = RecordingRepository(this@RecordingService)
            val source = output
            val finalUri = source?.let { remuxToM4a(it, finalFilename ?: return@let null) }
            if (finalUri != null) { repo.updateFile(id, finalFilename!!, finalUri.toString()); source?.let(::deleteFile) }
            val duration = RecordingTiming.durationMs(startedAt, System.currentTimeMillis()); repo.updateDuration(id, duration)
            val s = SettingsRepository(this@RecordingService).settings.first(); WorkScheduler.enqueueFinished(this@RecordingService, id, s.notifySend, s.audioSend, s.wifiOnly)
        }
        setRecordingState(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    /** Converts the finalised, crash-safe ADTS stream to M4A without re-encoding it. */
    private fun remuxToM4a(source: Uri, name: String): Uri? {
        val settings = runBlocking { SettingsRepository(this@RecordingService).folderUri() }
        val target = if (settings.isNotBlank()) DocumentFile.fromTreeUri(this, Uri.parse(settings))?.createFile("audio/mp4", name)?.uri
            else Uri.fromFile(File(getExternalFilesDir(null), name))
        target ?: return null
        val extractor = MediaExtractor(); var muxer: MediaMuxer? = null; var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            extractor.setDataSource(this, source, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(track)
            pfd = if (target.scheme == "content") contentResolver.openFileDescriptor(target, "rw") else null
            muxer = if (pfd != null) MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) else MediaMuxer(target.path!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(extractor.getTrackFormat(track)); muxer.start()
            val buffer = java.nio.ByteBuffer.allocate(256 * 1024); val info = MediaCodec.BufferInfo()
            while (true) { val size = extractor.readSampleData(buffer, 0); if (size < 0) break; info.set(0, size, extractor.sampleTime, extractor.sampleFlags); muxer.writeSampleData(outTrack, buffer, info); extractor.advance() }
            target
        } catch (_: Exception) { deleteFile(target); null } finally { runCatching { muxer?.stop() }; runCatching { muxer?.release() }; pfd?.close(); extractor.release() }
    }
    private fun deleteFile(uri: Uri) { runCatching { if (uri.scheme == "content") DocumentFile.fromSingleUri(this, uri)?.delete() else File(uri.path ?: "").delete() } }
    private fun stopWhenCallStarts() = scope.launch {
        while (recorder != null && !finalizing.get()) {
            delay(750)
            if (isCallInProgress(this@RecordingService)) {
                reportMicrophoneUnavailable()
                requestFinish(discard = false)
                return@launch
            }
        }
    }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(STOP).setData(Uri.parse("assistantvoicerecorder://stop"))
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val overlayIntent = Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_SHOW_OVERLAY).setData(Uri.parse("assistantvoicerecorder://overlay"))
        val overlayPendingIntent = PendingIntent.getService(this, 2, overlayIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setOngoing(true)
            // Tapping the notification itself keeps the existing quick-stop behaviour.
            .setContentIntent(stopPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_recording), stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Overlay", overlayPendingIntent)
            .build()
    }
    private fun reportMicrophoneUnavailable() {
        getSystemService(NotificationManager::class.java).notify(WARNING_NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Recording stopped")
            .setContentText("A call is using the microphone, so no silent recording was created.")
            .setAutoCancel(true)
            .build())
        setRecordingState(false)
        sendBroadcast(Intent(ACTION_MICROPHONE_UNAVAILABLE).setPackage(packageName))
    }
    private fun setRecordingState(active: Boolean) { getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putBoolean(STATE_KEY, active).apply(); sendBroadcast(Intent(ACTION_RECORDING_STATE).setPackage(packageName).putExtra(EXTRA_RECORDING_ACTIVE, active)) }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // The foreground service deliberately continues recording after the task/UI is dismissed.
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        // Android gives graceful service shutdowns a chance to finish; do not cancel the final M4A write.
        if (recorder != null && finalizing.compareAndSet(false, true)) runBlocking(Dispatchers.IO) { finalizeRecording(discardRequested) }
        setRecordingState(false); scope.cancel(); super.onDestroy()
    }
}
