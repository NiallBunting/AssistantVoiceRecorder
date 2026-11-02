package com.niallbunting.assistantvoicerecorder.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognitionSupport

/**
 * VoiceInteractionService metadata requires a recognition-service component.
 * This recorder deliberately does not transcribe or retain speech recognition data.
 */
class NoOpRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, callback: Callback?) {
        callback?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(callback: Callback?) = Unit
    override fun onStopListening(callback: Callback?) = Unit
}
