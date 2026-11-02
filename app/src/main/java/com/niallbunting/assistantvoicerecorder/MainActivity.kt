package com.niallbunting.assistantvoicerecorder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.niallbunting.assistantvoicerecorder.data.*
import com.niallbunting.assistantvoicerecorder.recording.RecordingService
import com.niallbunting.assistantvoicerecorder.ui.theme.AssistantVoiceRecorderTheme
import com.niallbunting.assistantvoicerecorder.work.WorkScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import java.nio.ByteOrder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); WorkScheduler.scheduleCleanup(this); setContent { AssistantVoiceRecorderTheme { HomeScreen() } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HomeScreen() {
    val context = LocalContext.current; val settings by remember { SettingsRepository(context) }.settings.collectAsState(initial = Settings())
    if (settings.folderUri.isBlank()) {
        val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                microphonePermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        Scaffold(topBar = { TopAppBar(title = { Text("AssistantVoiceRecorder") }) }) { padding ->
            Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Set up your recordings folder", style = MaterialTheme.typography.headlineSmall)
                Text("Choose a folder before recording. AssistantVoiceRecorder will save every meeting there and keep access after restarts.")
                Button(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) { Text("Choose recordings folder") }
            }
        }
    } else RecordingsScreen(onOpenSettings = { context.startActivity(Intent(context, SettingsActivity::class.java)) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RecordingsScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); var recordings by remember { mutableStateOf(emptyList<Recording>()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun refresh() { scope.launch { recordings = RecordingRepository(context).all() } }; LaunchedEffect(Unit) { refresh() }
    var recording by remember { mutableStateOf(RecordingService.isRecording(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(receiverContext: android.content.Context?, intent: Intent?) {
            if (intent?.action == RecordingService.ACTION_MICROPHONE_UNAVAILABLE) Toast.makeText(context, "Recording unavailable while a call is using the microphone", Toast.LENGTH_LONG).show()
            else { recording = intent?.getBooleanExtra(RecordingService.EXTRA_RECORDING_ACTIVE, false) ?: false; if (!recording) refresh() }
        } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply { addAction(RecordingService.ACTION_RECORDING_STATE); addAction(RecordingService.ACTION_MICROPHONE_UNAVAILABLE) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    DisposableEffect(lifecycleOwner) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }; lifecycleOwner.lifecycle.addObserver(observer); onDispose { lifecycleOwner.lifecycle.removeObserver(observer) } }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it[Manifest.permission.RECORD_AUDIO] == true) { RecordingService.start(context); recording = true } }
    Scaffold(topBar = { TopAppBar(title = { Text("AssistantVoiceRecorder") }, actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("The selected Digital Assistant starts an audio recording immediately.") }
            item { Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledIconButton(onClick = {
                    if (recording) { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.STOP)); recording = false }
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { RecordingService.start(context); recording = true }
                    else permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                }, modifier = Modifier.size(80.dp).semantics { contentDescription = if (recording) "Stop recording" else "Start recording" }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                    Icon(painter = painterResource(if (recording) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now), contentDescription = null, modifier = Modifier.size(38.dp))
                }
                Text(if (recording) "Recording — tap to stop" else "Tap to start recording")
            } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Recordings", style = MaterialTheme.typography.titleMedium); TextButton(onClick = ::refresh) { Text("Refresh") } } }
            items(recordings, key = { it.id }) { r -> RecordingRow(r, onDelete = { scope.launch { if (deleteRecording(context, r)) refresh() else Toast.makeText(context, "Could not delete this recording", Toast.LENGTH_LONG).show() } }, onPlay = {}) }
        }
    }
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { AssistantVoiceRecorderTheme { SettingsScreen(onDone = ::finish) } } }
}

class ApiGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { AssistantVoiceRecorderTheme { ApiGuideScreen(onDone = ::finish) } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(onDone: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val repo = remember { SettingsRepository(context) }; val settings by repo.settings.collectAsState(initial = Settings()); var apiUrl by remember(settings.apiUrl) { mutableStateOf(settings.apiUrl) }
    val folderName = remember(settings.folderUri) { settings.folderUri.takeIf { it.isNotBlank() }?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name ?: it } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); scope.launch { repo.update(SettingsRepository.setFolder(uri.toString())) } } }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onDone) { Text("Done") } }) }) { padding -> LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Storage", style = MaterialTheme.typography.titleMedium) }
        item { Column { Button(onClick = { folderPicker.launch(settings.folderUri.takeIf { it.isNotBlank() }?.let(Uri::parse)) }) { Text(if (folderName == null) "Choose recordings folder" else "Change recordings folder") }; Text(if (folderName == null) "No folder selected" else "Folder: $folderName", style = MaterialTheme.typography.bodySmall) } }
        item { HorizontalDivider(); Text("Assistant", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }) { Text("Choose default Assistant") } }
        item { HorizontalDivider(); Text("API", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(value = apiUrl, onValueChange = { apiUrl = it }, label = { Text("API URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true); Row { TextButton(onClick = { scope.launch { repo.update(SettingsRepository.setUrl(apiUrl.trim())) } }) { Text("Save API URL") }; TextButton(onClick = { context.startActivity(Intent(context, ApiGuideActivity::class.java)) }) { Text("API format guide") } } }
        item { SettingSwitch("Notify send", settings.notifySend) { scope.launch { repo.update(SettingsRepository.setNotify(it)) } } }
        item { SettingSwitch("Audio send", settings.audioSend) { scope.launch { repo.update(SettingsRepository.setAudio(it)) } } }
        item { SettingSwitch("Wi-Fi only for audio", settings.wifiOnly) { scope.launch { repo.update(SettingsRepository.setWifi(it)) } } }
        item { HorizontalDivider(); Text("Retention", style = MaterialTheme.typography.titleMedium) }
        item { SettingSwitch("Automatic deletion", settings.autoDelete) { scope.launch { repo.update(SettingsRepository.setDelete(it)); WorkScheduler.scheduleCleanup(context) } }; Text("Deletes after retention only when audio upload succeeded, or when audio sending was disabled.", style = MaterialTheme.typography.bodySmall) }
        item { var days by remember(settings.retentionDays) { mutableStateOf(settings.retentionDays.toString()) }; OutlinedTextField(value = days, onValueChange = { days = it.filter(Char::isDigit) }, label = { Text("Retention days") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()); TextButton(onClick = { scope.launch { repo.update(SettingsRepository.setDays(days.toIntOrNull()?.coerceAtLeast(1) ?: 30)) } }) { Text("Save retention") } }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ApiGuideScreen(onDone: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("API format guide") }, navigationIcon = { TextButton(onClick = onDone) { Text("Done") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Build your own backend", style = MaterialTheme.typography.headlineSmall) }
            item { Text("Enter one HTTPS endpoint in Settings. The app sends the following requests to that same URL after a recording stops. You choose independently whether metadata and audio uploads are enabled.") }
            item { ApiSection("1. Recording metadata", "POST /your-endpoint\nContent-Type: application/json", """{
  "recording_id": "UUID",
  "filename": "2026-11-02T14:30:12Z.m4a",
  "recorded_at": "2026-11-02T14:30:12.000Z",
  "duration_ms": 12345,
  "mime_type": "audio/mp4"
}""") }
            item { Text("recorded_at is UTC in ISO-8601 format. recording_id is stable and links the metadata request to the audio upload.", style = MaterialTheme.typography.bodySmall) }
            item { ApiSection("2. Audio file", "POST /your-endpoint\nContent-Type: multipart/form-data; boundary=…", """--boundary
Content-Disposition: form-data; name="recording_id"

UUID
--boundary
Content-Disposition: form-data; name="file"; filename="…m4a"
Content-Type: audio/mp4

<M4A bytes>
--boundary--""") }
            item { Text("The audio is streamed from the selected recordings folder; it is not first loaded fully into memory.", style = MaterialTheme.typography.bodySmall) }
            item { ApiSection("3. Responding", "Any HTTP status from 200 to 299 marks that upload as successful.", "Return 2xx only after you have accepted the request. Any other status or network failure is retried by Android WorkManager with backoff. Metadata and audio are separate requests, so a backend should accept either order and use recording_id to associate them.") }
            item { ApiSection("Important", "Authentication and custom headers are not implemented yet.", "Use an HTTPS endpoint. Do not put a secret in the API URL, as it is stored on the device. If your backend needs authentication, add a server-issued token flow before enabling uploads.") }
        }
    }
}

@Composable private fun ApiSection(title: String, subtitle: String, body: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        SelectionContainer { Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) { Text(body, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
    } }
}

@Composable private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange) } }
@Composable private fun RecordingRow(r: Recording, onDelete: () -> Unit, onPlay: () -> Unit) {
    val context = LocalContext.current
    var player by remember(r.id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(r.id) { mutableStateOf(false) }
    var speed by remember(r.id) { mutableFloatStateOf(1f) }
    var positionMs by remember(r.id) { mutableLongStateOf(0L) }
    var peaks by remember(r.uri) { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(r.uri) {
        peaks = withContext(Dispatchers.IO) { AudioWaveform.peaks(context, Uri.parse(r.uri)) }
    }
    DisposableEffect(r.id) { onDispose { player?.release() } }
    LaunchedEffect(playing, player) { while (playing && player != null) { positionMs = player?.currentPosition?.toLong() ?: 0L; delay(60) } }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(r.filename, style = MaterialTheme.typography.titleSmall)
        Text("${DateFormat.getDateTimeInstance().format(Date(r.recordedAt))} • ${r.durationMs / 1000}s")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = {
                if (playing) { player?.pause(); playing = false }
                else {
                    if (player == null) player = MediaPlayer().apply {
                        setDataSource(context, Uri.parse(r.uri))
                        setOnPreparedListener { media -> media.playbackParams = PlaybackParams().setSpeed(speed); media.start(); playing = true }
                        setOnCompletionListener { playing = false; positionMs = 0 }
                        setOnErrorListener { _, _, _ -> playing = false; true }
                        prepareAsync()
                    } else { player?.start(); playing = true }
                }
            }) { Icon(painter = painterResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), contentDescription = if (playing) "Pause" else "Play") }
            ActualWaveform(peaks = peaks, progress = if (r.durationMs > 0) (positionMs.toFloat() / r.durationMs).coerceIn(0f, 1f) else 0f, activeColor = MaterialTheme.colorScheme.primary, inactiveColor = MaterialTheme.colorScheme.outline.copy(alpha = .38f), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { speed = when { speed < 1.25f -> 1.5f; speed < 1.75f -> 2f; speed < 2.5f -> 3f; else -> 1f }; player?.let { media -> runCatching { media.playbackParams = PlaybackParams().setSpeed(speed) } } }, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("${"%.1f".format(Locale.US, speed)}×") }
        }
        Text("Audio: ${r.audioStatus}   Metadata: ${r.notifyStatus}")
        r.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row { TextButton(onClick = onDelete) { Text("Delete") } }
    } }
}
@Composable private fun ActualWaveform(peaks: List<Float>, progress: Float, activeColor: Color, inactiveColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(52.dp).padding(vertical = 7.dp)) {
        if (peaks.isEmpty()) return@Canvas
        val gap = size.width / peaks.size
        peaks.forEachIndexed { index, peak ->
            val height = (size.height * peak.coerceIn(.08f, 1f)).coerceAtLeast(3f); val x = index * gap + gap * .18f
            val color = if ((index + 1).toFloat() / peaks.size <= progress) activeColor else inactiveColor
            drawRoundRect(color = color, topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - height) / 2), size = androidx.compose.ui.geometry.Size(gap * .64f, height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(gap, gap))
        }
        val cursorX = size.width * progress
        drawCircle(color = activeColor, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cursorX, size.height / 2))
    }
}

/** Decodes AAC/PCM audio to compact, timeline-ordered amplitude peaks for the playback UI. */
private object AudioWaveform {
    private val cache = mutableMapOf<String, List<Float>>()
    fun peaks(context: android.content.Context, uri: Uri): List<Float> = synchronized(cache) { cache[uri.toString()] } ?: decode(context, uri).also { synchronized(cache) { cache[uri.toString()] = it } }
    private fun decode(context: android.content.Context, uri: Uri): List<Float> {
        val extractor = MediaExtractor(); val windows = ArrayList<Float>()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return emptyList()
            extractor.selectTrack(track); val format = extractor.getTrackFormat(track); val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val codec = MediaCodec.createDecoderByType(mime); codec.configure(format, null, null, 0); codec.start()
            var inputDone = false; var outputDone = false; val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) { val inputIndex = codec.dequeueInputBuffer(10_000); if (inputIndex >= 0) { val input = codec.getInputBuffer(inputIndex)!!; val size = extractor.readSampleData(input, 0); if (size < 0) { codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true } else { codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0); extractor.advance() } } }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) { if (info.size > 0) { val output = codec.getOutputBuffer(outputIndex)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN); output?.position(info.offset); output?.limit(info.offset + info.size); var sum = 0f; var count = 0; while (output != null && output.remaining() >= 2) { sum += abs(output.short.toInt()) / 32768f; count++ }; if (count > 0) windows.add((sum / count).coerceAtMost(1f)) }; codec.releaseOutputBuffer(outputIndex, false); outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 }
                }
            }
            codec.stop(); codec.release()
            val bars = 72
            val rawBars = List(bars) { bar ->
                val start = bar * windows.size / bars; val end = ((bar + 1) * windows.size / bars).coerceAtLeast(start + 1).coerceAtMost(windows.size)
                if (start >= windows.size) 0f else windows.subList(start, end).maxOrNull() ?: 0f
            }
            // Normalise per recording: even quiet speech gets a legible waveform while its relative dynamics remain intact.
            val highest = rawBars.maxOrNull() ?: 0f
            if (highest < 0.0005f) List(bars) { .12f }
            else rawBars.map { amplitude -> (.12f + .88f * (amplitude / highest).coerceIn(0f, 1f).toDouble().pow(.55).toFloat()).coerceIn(.12f, 1f) }
        } catch (_: Exception) { emptyList() } finally { extractor.release() }
    }
}
private suspend fun deleteRecording(context: android.content.Context, r: Recording): Boolean = withContext(Dispatchers.IO) {
    val uri = Uri.parse(r.uri)
    val deleted = runCatching {
        if (uri.scheme == "content") DocumentFile.fromSingleUri(context, uri)?.delete() == true
        else java.io.File(uri.path ?: "").delete()
    }.getOrDefault(false)
    if (deleted) RecordingRepository(context).delete(r.id)
    deleted
}
