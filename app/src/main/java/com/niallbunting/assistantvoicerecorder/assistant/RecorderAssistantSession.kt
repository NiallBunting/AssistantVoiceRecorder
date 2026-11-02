package com.niallbunting.assistantvoicerecorder.assistant
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import com.niallbunting.assistantvoicerecorder.recording.RecordingService

class RecorderAssistantSession(context: android.content.Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val collapseDelayMs = 6_000L
    private var bubbleX = 0
    private var bubbleY = 0

    override fun onCreate() {
        super.onCreate()
        configureCompactWindow()
        showPanel(recordingPanel(), collapseAfterDelay = false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (RecordingService.isCallInProgress(context)) showPanel(microphoneUnavailablePanel(), collapseAfterDelay = false)
        else { RecordingService.start(context); scheduleCollapse() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun configureCompactWindow() {
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.BOTTOM
                x = 0
                y = 0
            }
        }
    }

    private fun showPanel(panel: View, collapseAfterDelay: Boolean = true) {
        handler.removeCallbacksAndMessages(null)
        configureCompactWindow()
        setContentView(FrameLayout(context).apply {
            setPadding(24, 0, 24, 32)
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (collapseAfterDelay) scheduleCollapse()
    }

    private fun scheduleCollapse() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (RecordingService.isRecording(context)) showBubble() }, collapseDelayMs)
    }

    private fun showBubble() {
        val screen = context.resources.displayMetrics
        if (bubbleX == 0 && bubbleY == 0) {
            bubbleX = screen.widthPixels - dp(88)
            bubbleY = screen.heightPixels - dp(140)
        }
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes = attributes.apply {
                width = dp(64)
                height = dp(64)
                gravity = Gravity.TOP or Gravity.START
                x = bubbleX
                y = bubbleY
            }
        }
        setContentView(recordingBubble())
    }

    private fun recordingBubble(): View = ImageButton(context).apply {
        // Match the active recording/destructive accent used by the controls below.
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(210, 75, 75)) }
        setImageResource(android.R.drawable.ic_media_pause)
        setColorFilter(Color.WHITE)
        contentDescription = "Recording controls"
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0; var moved = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = bubbleX; initialY = bubbleY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                    if (moved) {
                        bubbleX = (initialX + dx).coerceIn(0, context.resources.displayMetrics.widthPixels - dp(64))
                        bubbleY = (initialY + dy).coerceIn(0, context.resources.displayMetrics.heightPixels - dp(64))
                        window.window?.attributes = window.window?.attributes?.apply { x = bubbleX; y = bubbleY }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) showPanel(recordingPanel()); true }
                else -> true
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

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
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Stop"
                setOnClickListener {
                    context.startService(android.content.Intent(context, RecordingService::class.java).setAction(RecordingService.STOP))
                    finish()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 })
            addView(Button(context).apply {
                text = "Stop & delete"
                setTextColor(Color.rgb(210, 75, 75))
                setOnClickListener {
                    // VoiceInteractionSession does not own an activity window token, so use an
                    // in-session confirmation panel rather than an activity AlertDialog.
                    showPanel(deleteConfirmationPanel())
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 6 })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        addView(Button(context).apply { text = "Keep recording"; setOnClickListener { showPanel(recordingPanel()) } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
