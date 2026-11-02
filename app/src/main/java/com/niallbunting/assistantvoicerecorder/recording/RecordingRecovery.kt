package com.niallbunting.assistantvoicerecorder.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.niallbunting.assistantvoicerecorder.data.RecordingRepository
import com.niallbunting.assistantvoicerecorder.data.SettingsRepository
import java.io.File
import java.nio.ByteBuffer

/** Recovers recordings left as ADTS/AAC when Android terminates the recorder unexpectedly. */
object RecordingRecovery {
    suspend fun recover(context: Context) {
        val repository = RecordingRepository(context)
        repository.unfinishedAac().forEach { recording ->
            val source = Uri.parse(recording.uri)
            val finalFilename = recording.filename.removeSuffix(".aac") + ".m4a"
            val result = remux(context, source, finalFilename)
            if (result != null) {
                repository.updateFile(recording.id, finalFilename, result.first.toString())
                repository.updateDuration(recording.id, result.second)
                delete(context, source)
            }
        }
    }

    private suspend fun remux(context: Context, source: Uri, filename: String): Pair<Uri, Long>? {
        val folder = SettingsRepository(context).folderUri()
        val target = (if (folder.isNotBlank()) DocumentFile.fromTreeUri(context, Uri.parse(folder))?.createFile("audio/mp4", filename)?.uri
        else Uri.fromFile(File(context.getExternalFilesDir(null), filename))) ?: return null
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            extractor.setDataSource(context, source, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            pfd = if (target.scheme == "content") context.contentResolver.openFileDescriptor(target, "rw") else null
            muxer = if (pfd != null) MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) else MediaMuxer(target.path!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var lastSampleTimeUs = 0L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(outputTrack, buffer, info)
                lastSampleTimeUs = extractor.sampleTime
                extractor.advance()
            }
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000 else lastSampleTimeUs / 1_000
            target to durationMs
        } catch (_: Exception) {
            delete(context, target)
            null
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            pfd?.close()
            extractor.release()
        }
    }

    private fun delete(context: Context, uri: Uri) {
        runCatching { if (uri.scheme == "content") DocumentFile.fromSingleUri(context, uri)?.delete() else File(uri.path ?: "").delete() }
    }
}
