package com.niallbunting.assistantvoicerecorder.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")
data class Settings(val apiUrl: String = "", val notifySend: Boolean = false, val audioSend: Boolean = false, val wifiOnly: Boolean = true, val autoDelete: Boolean = false, val retentionDays: Int = 30, val folderUri: String = "")
class SettingsRepository(private val context: Context) {
    private object Keys { val url=stringPreferencesKey("url"); val notify=booleanPreferencesKey("notify"); val audio=booleanPreferencesKey("audio"); val wifi=booleanPreferencesKey("wifi"); val delete=booleanPreferencesKey("delete"); val days=intPreferencesKey("days"); val folder=stringPreferencesKey("folder") }
    val settings: Flow<Settings> = context.settingsDataStore.data.map { p -> Settings(p[Keys.url] ?: "", p[Keys.notify] ?: false, p[Keys.audio] ?: false, p[Keys.wifi] ?: true, p[Keys.delete] ?: false, p[Keys.days] ?: 30, p[Keys.folder] ?: "") }
    suspend fun update(transform: (MutablePreferences) -> Unit) { context.settingsDataStore.edit(transform) }
    suspend fun folderUri() = settings.first().folderUri
    companion object { fun setUrl(v:String)={ p: MutablePreferences -> p[Keys.url]=v }; fun setNotify(v:Boolean)={ p: MutablePreferences -> p[Keys.notify]=v }; fun setAudio(v:Boolean)={ p: MutablePreferences -> p[Keys.audio]=v }; fun setWifi(v:Boolean)={ p: MutablePreferences -> p[Keys.wifi]=v }; fun setDelete(v:Boolean)={ p: MutablePreferences -> p[Keys.delete]=v }; fun setDays(v:Int)={ p: MutablePreferences -> p[Keys.days]=v }; fun setFolder(v:String)={ p: MutablePreferences -> p[Keys.folder]=v } }
}
