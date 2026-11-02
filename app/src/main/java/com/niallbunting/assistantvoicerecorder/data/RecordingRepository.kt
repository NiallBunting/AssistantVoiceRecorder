package com.niallbunting.assistantvoicerecorder.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecordingRepository(context: Context) {
    private val db = Database(context.applicationContext)

    suspend fun insert(recording: Recording) = io {
        db.writableDatabase.insertOrThrow("recordings", null, values(recording))
    }
    suspend fun all(): List<Recording> = io {
        db.readableDatabase.rawQuery("SELECT * FROM recordings ORDER BY recorded_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toRecording()) }
        }
    }
    suspend fun byId(id: String): Recording? = io {
        db.readableDatabase.query("recordings", null, "id=?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) c.toRecording() else null
        }
    }
    suspend fun updateStatus(id: String, audio: UploadStatus? = null, notify: UploadStatus? = null, error: String? = null) = io {
        val values = ContentValues().apply {
            audio?.let { put("audio_status", it.name) }; notify?.let { put("notify_status", it.name) }
            if (error != null) put("error", error)
        }
        db.writableDatabase.update("recordings", values, "id=?", arrayOf(id))
    }
    suspend fun updateDuration(id: String, duration: Long) = io {
        db.writableDatabase.update("recordings", ContentValues().apply { put("duration_ms", duration) }, "id=?", arrayOf(id))
    }
    suspend fun delete(id: String) = io { db.writableDatabase.delete("recordings", "id=?", arrayOf(id)) }
    suspend fun eligibleForDeletion(cutoff: Long): List<Recording> = io {
        // Locally-only recordings have no upload obligation. Records awaiting or failing an enabled upload remain protected.
        db.readableDatabase.query("recordings", null, "recorded_at<? AND audio_status IN (?, ?)", arrayOf(cutoff.toString(), UploadStatus.SUCCESS.name, UploadStatus.DISABLED.name), null, null, null).use { c -> buildList { while(c.moveToNext()) add(c.toRecording()) } }
    }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun values(r: Recording) = ContentValues().apply {
        put("id", r.id); put("filename", r.filename); put("uri", r.uri); put("recorded_at", r.recordedAt); put("duration_ms", r.durationMs)
        put("audio_status", r.audioStatus.name); put("notify_status", r.notifyStatus.name); put("error", r.error)
    }
    private fun android.database.Cursor.toRecording() = Recording(
        getString(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("filename")), getString(getColumnIndexOrThrow("uri")),
        getLong(getColumnIndexOrThrow("recorded_at")), getLong(getColumnIndexOrThrow("duration_ms")),
        UploadStatus.valueOf(getString(getColumnIndexOrThrow("audio_status"))), UploadStatus.valueOf(getString(getColumnIndexOrThrow("notify_status"))),
        getString(getColumnIndexOrThrow("error"))
    )
    private class Database(context: Context) : SQLiteOpenHelper(context, "recordings.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE recordings (id TEXT PRIMARY KEY, filename TEXT NOT NULL, uri TEXT NOT NULL, recorded_at INTEGER NOT NULL, duration_ms INTEGER NOT NULL, audio_status TEXT NOT NULL, notify_status TEXT NOT NULL, error TEXT)") }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
