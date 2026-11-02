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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.lifecycle.lifecycleScope
import com.niallbunting.assistantvoicerecorder.data.*
import com.niallbunting.assistantvoicerecorder.recording.RecordingRecovery
import com.niallbunting.assistantvoicerecorder.recording.RecordingService
import com.niallbunting.assistantvoicerecorder.ui.theme.AssistantVoiceRecorderTheme
import com.niallbunting.assistantvoicerecorder.work.WorkScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RecordingService.clearStaleState(this)
        WorkScheduler.scheduleCleanup(this)
        lifecycleScope.launch(Dispatchers.IO) { RecordingRecovery.recover(this@MainActivity) }
        setContent { AssistantVoiceRecorderTheme { HomeScreen() } }
    }
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
    fun refresh() { scope.launch {
        val refreshed = RecordingRepository(context).all()
        recordings = refreshed
        // Generate missing shapes immediately after a refresh, including the recording that has
        // just stopped. Rows that are on screen subscribe to the same calculation below.
        refreshed.forEach { recording -> launch { AudioWaveform.peaks(context, Uri.parse(recording.uri)) {} } }
    } }; LaunchedEffect(Unit) { refresh() }
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
            items(recordings, key = { it.id }) { r -> RecordingRow(r, inProgress = recording && r.uri.endsWith(".aac"), onDelete = { scope.launch { if (deleteRecording(context, r)) refresh() else Toast.makeText(context, "Could not delete this recording", Toast.LENGTH_LONG).show() } }, onPlay = {}) }
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
@Composable private fun RecordingRow(r: Recording, inProgress: Boolean, onDelete: () -> Unit, onPlay: () -> Unit) {
    val context = LocalContext.current
    var player by remember(r.id) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(r.id) { mutableStateOf(false) }
    var speed by remember(r.id) { mutableFloatStateOf(1f) }
    var positionMs by remember(r.id) { mutableLongStateOf(0L) }
    var peaks by remember(r.uri) { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(r.uri, r.durationMs) {
        peaks = emptyList()
        // The database row is created at recording start so an interrupted AAC stream can be
        // recovered. Do not try to decode its initial zero-byte/in-progress file yet.
        if (r.durationMs <= 0L) return@LaunchedEffect
        peaks = AudioWaveform.peaks(context, Uri.parse(r.uri)) { partialPeaks -> peaks = partialPeaks }
        // A freshly stopped M4A can briefly be unreadable while its final index becomes visible
        // through a document-provider URI. Empty results are deliberately not cached, so retry.
        if (peaks.isEmpty()) {
            delay(1_000)
            peaks = AudioWaveform.peaks(context, Uri.parse(r.uri)) { partialPeaks -> peaks = partialPeaks }
        }
    }
    DisposableEffect(r.id) { onDispose { player?.release() } }
    LaunchedEffect(playing, player) { while (playing && player != null) { positionMs = player?.currentPosition?.toLong() ?: 0L; delay(60) } }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        if (inProgress) {
            Text("Recording in progress", style = MaterialTheme.typography.titleSmall)
            Text(r.filename, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        } else {
        Text(r.filename, style = MaterialTheme.typography.titleSmall)
        Text("${DateFormat.getDateTimeInstance().format(Date(r.recordedAt))} • ${RecordingTiming.formatDuration(r.durationMs)}")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = {
                if (playing) { player?.pause(); playing = false }
                else {
                    if (player == null) player = MediaPlayer().apply {
                        setDataSource(context, Uri.parse(r.uri))
                        setOnPreparedListener { media ->
                            if (positionMs > 0) media.seekTo(positionMs.toInt())
                            media.playbackParams = PlaybackParams().setSpeed(speed); media.start(); playing = true
                        }
                        setOnCompletionListener { playing = false; positionMs = 0 }
                        setOnErrorListener { _, _, _ -> playing = false; true }
                        prepareAsync()
                    } else { player?.start(); playing = true }
                }
            }) { Icon(painter = painterResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), contentDescription = if (playing) "Pause" else "Play") }
            ActualWaveform(peaks = peaks, progress = if (r.durationMs > 0) (positionMs.toFloat() / r.durationMs).coerceIn(0f, 1f) else 0f, onSeek = { fraction ->
                positionMs = (r.durationMs * fraction.coerceIn(0f, 1f)).toLong()
                player?.seekTo(positionMs.toInt())
            }, activeColor = MaterialTheme.colorScheme.primary, inactiveColor = MaterialTheme.colorScheme.outline.copy(alpha = .38f), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                positionMs = (positionMs - 10_000L).coerceAtLeast(0L)
                player?.seekTo(positionMs.toInt())
            }, enabled = r.durationMs > 0) { Icon(painter = painterResource(android.R.drawable.ic_media_rew), contentDescription = "Rewind 10 seconds") }
            Button(onClick = { speed = when { speed < 1.25f -> 1.5f; speed < 1.75f -> 2f; speed < 2.5f -> 3f; else -> 1f }; player?.let { media -> runCatching { media.playbackParams = PlaybackParams().setSpeed(speed) } } }, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("${"%.1f".format(Locale.US, speed)}×") }
        }
        Text("Audio: ${r.audioStatus}   Metadata: ${r.notifyStatus}")
        r.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row { TextButton(onClick = onDelete) { Text("Delete") } }
        }
    } }
}
@Composable private fun ActualWaveform(peaks: List<Float>, progress: Float, onSeek: (Float) -> Unit, activeColor: Color, inactiveColor: Color, modifier: Modifier = Modifier) {
    val revealedFraction by animateFloatAsState(
        targetValue = (peaks.size / 72f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180),
        label = "waveform reveal"
    )
    Canvas(modifier.height(52.dp).padding(vertical = 7.dp)
        .pointerInput(peaks) { detectTapGestures { onSeek(if (size.width == 0) 0f else it.x / size.width) } }
        .pointerInput(peaks) { detectDragGestures(onDragStart = { onSeek(if (size.width == 0) 0f else it.x / size.width) }) { change, _ -> onSeek(if (size.width == 0) 0f else change.position.x / size.width) } }) {
        if (peaks.isEmpty()) return@Canvas
        val gap = size.width / 72f
        clipRect(right = size.width * revealedFraction) {
            peaks.forEachIndexed { index, peak ->
                val height = (size.height * peak.coerceIn(.08f, 1f)).coerceAtLeast(3f); val x = index * gap + gap * .18f
                val color = if ((index + 1).toFloat() / 72f <= progress) activeColor else inactiveColor
                drawRoundRect(color = color, topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - height) / 2), size = androidx.compose.ui.geometry.Size(gap * .64f, height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(gap, gap))
            }
        }
        val cursorX = size.width * progress
        drawCircle(color = activeColor, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(cursorX, size.height / 2))
    }
}

/** Decodes AAC/PCM audio to compact, timeline-ordered amplitude peaks for the playback UI. */
private object AudioWaveform {
    private val cache = mutableMapOf<String, List<Float>>()
    private val inFlight = mutableMapOf<String, WaveformFlight>()
    private const val BARS = 72
    private const val FULL_DECODE_LIMIT_US = 30_000_000L
    private class WaveformFlight {
        val listeners = mutableListOf<(List<Float>) -> Unit>()
        val finished = CompletableDeferred<List<Float>>()
        var latest = emptyList<Float>()
    }

    /**
     * Samples one bar at a time and publishes the partial shape immediately, so the UI can
     * reveal the waveform while the remaining audio is still being analysed.
     */
    suspend fun peaks(context: android.content.Context, uri: Uri, onProgress: (List<Float>) -> Unit): List<Float> {
        val key = uri.toString()
        synchronized(cache) { cache[key] }?.let { cached ->
            onProgress(cached)
            return cached
        }

        val flight: WaveformFlight
        val shouldCalculate: Boolean
        val latest: List<Float>
        synchronized(inFlight) {
            val existing = inFlight[key]
            if (existing != null) {
                existing.listeners += onProgress
                flight = existing
                shouldCalculate = false
                latest = existing.latest
            } else {
                flight = WaveformFlight().also { it.listeners += onProgress }
                inFlight[key] = flight
                shouldCalculate = true
                latest = emptyList()
            }
        }
        if (latest.isNotEmpty()) onProgress(latest)
        if (!shouldCalculate) return flight.finished.await()

        val finalBars = try {
            val durationUs = durationUs(context, uri) ?: return finish(key, flight, emptyList())
            if (durationUs <= FULL_DECODE_LIMIT_US) {
                // A short recording is much faster to decode once than to repeatedly create
                // 72 codecs and seek the extractor for every visual bar.
                decodeShortRecording(context, uri, durationUs) { publish(flight, it) }
            } else {
                val rawBars = mutableListOf<Float>()
                repeat(BARS) { bar ->
                    rawBars += withContext(Dispatchers.IO) { sampleAt(context, uri, (durationUs * (bar + .5) / BARS).toLong()) }
                    publish(flight, normalise(rawBars))
                }
                normalise(rawBars)
            }
        } catch (_: Exception) {
            emptyList()
        }
        return finish(key, flight, finalBars)
    }

    private fun publish(flight: WaveformFlight, values: List<Float>) {
        val listeners = synchronized(inFlight) { flight.latest = values; flight.listeners.toList() }
        listeners.forEach { it(values) }
    }

    private fun finish(key: String, flight: WaveformFlight, values: List<Float>): List<Float> {
        // Do not permanently remember a transient extractor failure for a newly finalised file.
        if (values.isNotEmpty()) synchronized(cache) { cache[key] = values }
        synchronized(inFlight) { inFlight.remove(key) }
        flight.finished.complete(values)
        return values
    }

    private suspend fun durationUs(context: android.content.Context, uri: Uri): Long? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return@withContext null
            val format = extractor.getTrackFormat(track)
            format.getLong(MediaFormat.KEY_DURATION)
        } catch (_: Exception) { null } finally { extractor.release() }
    }

    private fun normalise(rawBars: List<Float>): List<Float> {
        val highest = rawBars.maxOrNull() ?: 0f
        return if (highest < 0.0005f) List(rawBars.size) { .12f }
        else rawBars.map { amplitude -> (.12f + .88f * (amplitude / highest).coerceIn(0f, 1f).toDouble().pow(.55).toFloat()).coerceIn(.12f, 1f) }
    }

    /** One sequential decoder pass for short clips; emits the growing waveform as it decodes. */
    private suspend fun decodeShortRecording(context: android.content.Context, uri: Uri, durationUs: Long, onProgress: (List<Float>) -> Unit): List<Float> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext emptyList()
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }.apply { configure(format, null, null, 0); start() }

            val sums = FloatArray(BARS)
            val counts = IntArray(BARS)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var lastPublishedBars = 0
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(1_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 1_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val output = decoder.getOutputBuffer(outputIndex)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
                            output?.position(info.offset); output?.limit(info.offset + info.size)
                            val bar = ((info.presentationTimeUs * BARS) / durationUs).toInt().coerceIn(0, BARS - 1)
                            sums[bar] += output?.let(::pcmAmplitude) ?: 0f
                            counts[bar]++
                            val completedBars = bar + 1
                            if (completedBars > lastPublishedBars) {
                                lastPublishedBars = completedBars
                                onProgress(normalise((0 until completedBars).map { index -> if (counts[index] == 0) 0f else sums[index] / counts[index] }))
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            normalise((0 until BARS).map { index -> if (counts[index] == 0) 0f else sums[index] / counts[index] })
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun sampleAt(context: android.content.Context, uri: Uri, timeUs: Long): Float {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return 0f
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0f
            extractor.selectTrack(track)
            extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            repeat(12) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)!!
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else { codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val output = codec.getOutputBuffer(outputIndex)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
                    output?.position(info.offset); output?.limit(info.offset + info.size)
                    val amplitude = output?.let(::pcmAmplitude) ?: 0f
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.size > 0) return amplitude
                }
            }
            0f
        } catch (_: Exception) { 0f } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release() }
    }

    private fun pcmAmplitude(buffer: java.nio.ByteBuffer): Float {
        var sum = 0f; var count = 0
        while (buffer.remaining() >= 2) { sum += abs(buffer.short.toInt()) / 32768f; count++ }
        return if (count == 0) 0f else (sum / count).coerceAtMost(1f)
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
