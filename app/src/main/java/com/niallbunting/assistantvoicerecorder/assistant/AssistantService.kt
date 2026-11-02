package com.niallbunting.assistantvoicerecorder.assistant

import android.content.Intent
import android.service.voice.VoiceInteractionService

class AssistantService : VoiceInteractionService() {
    companion object {
        const val ACTION_SHOW_OVERLAY = "com.niallbunting.assistantvoicerecorder.SHOW_RECORDING_OVERLAY"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_OVERLAY) showSession(null, 0)
        return super.onStartCommand(intent, flags, startId)
    }
}
