package com.niallbunting.assistantvoicerecorder.work

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkScheduler {
    const val RECORDING_ID = "recording_id"
    fun enqueueFinished(context: Context, id: String, notify: Boolean, audio: Boolean, wifiOnly: Boolean) {
        if (notify) WorkManager.getInstance(context).enqueueUniqueWork("notify-$id", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<NotifyWorker>().setInputData(workDataOf(RECORDING_ID to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        if (audio) WorkManager.getInstance(context).enqueueUniqueWork("audio-$id", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AudioUploadWorker>().setInputData(workDataOf(RECORDING_ID to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(if(wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun scheduleCleanup(context: Context) { WorkManager.getInstance(context).enqueueUniquePeriodicWork("retention-cleanup", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<CleanupWorker>(24, TimeUnit.HOURS).build()) }
}
