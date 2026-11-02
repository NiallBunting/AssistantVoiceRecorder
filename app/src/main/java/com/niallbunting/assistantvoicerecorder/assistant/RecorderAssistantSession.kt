package com.niallbunting.assistantvoicerecorder.assistant
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.niallbunting.assistantvoicerecorder.recording.RecordingService

class RecorderAssistantSession(context: android.content.Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        setContentView(recordingPanel())
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (RecordingService.isCallInProgress(context)) setContentView(microphoneUnavailablePanel()) else RecordingService.start(context)
    }

    private fun microphoneUnavailablePanel(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(48, 40, 48, 40)
        background = GradientDrawable().apply { setColor(Color.rgb(30, 30, 34)); cornerRadius = 48f }
        addView(TextView(context).apply {
            text = "Microphone is in use"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = "Recording cannot start while a call is using the microphone."
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 28)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(Button(context).apply { text = "Close"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun recordingPanel(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(48, 40, 48, 40)
        background = GradientDrawable().apply { setColor(Color.rgb(30, 30, 34)); cornerRadius = 48f }
        addView(TextView(context).apply {
            text = "●  Recording meeting"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = "Recording continues in the background until you stop it."
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 28)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(Button(context).apply {
            text = "Stop recording"
            setOnClickListener {
                context.startService(android.content.Intent(context, RecordingService::class.java).setAction(RecordingService.STOP))
                finish()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(Button(context).apply {
            text = "Stop & delete"
            setTextColor(Color.rgb(210, 75, 75))
            setOnClickListener {
                // VoiceInteractionSession does not own an activity window token, so use an
                // in-session confirmation panel rather than an activity AlertDialog.
                setContentView(deleteConfirmationPanel())
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
    }

    private fun deleteConfirmationPanel(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(48, 40, 48, 40)
        background = GradientDrawable().apply { setColor(Color.rgb(30, 30, 34)); cornerRadius = 48f }
        addView(TextView(context).apply {
            text = "Delete this recording?"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = "The recording made so far will be permanently deleted."
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 28)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(Button(context).apply { text = "Keep recording"; setOnClickListener { setContentView(recordingPanel()) } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(Button(context).apply {
            text = "Stop & delete"
            setTextColor(Color.rgb(210, 75, 75))
            setOnClickListener {
                context.startService(android.content.Intent(context, RecordingService::class.java).setAction(RecordingService.DISCARD))
                finish()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
    }
}
