package com.niallbunting.assistantvoicerecorder.assistant
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
class AssistantSessionService : VoiceInteractionSessionService() { override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession = RecorderAssistantSession(this) }
