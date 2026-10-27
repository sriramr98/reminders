package com.sriram.callreminder

import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*

class ReminderActivity : BaseActivity() {
    private var reminderId = -1
    private var renderedState = ""
    private val handler = Handler(Looper.getMainLooper())
    private var connectedAt = 0L
    private var status: TextView? = null
    private val timer = object : Runnable {
        override fun run() {
            if (renderedState in setOf("answering", "speaking")) {
                val seconds = (SystemClock.elapsedRealtime() - connectedAt) / 1000
                status?.text = getString(R.string.connected_time, seconds / 60, seconds % 60)
                handler.postDelayed(this, 1000)
            }
        }
    }
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { render() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"))
        render()
        answerIfRequested()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); renderedState = ""; render(); answerIfRequested()
    }
    private fun answerIfRequested() {
        if (intent.action != "answer") return
        intent.action = null // Consume once, including across activity recreation.
        if (Reminders.all(this).any { it.id == reminderId && it.state == "ringing" }) command("answer")
    }
    private fun command(action: String) {
        startService(Intent(this, RingService::class.java).setAction(action).putExtra("id", reminderId))
    }
    private fun render() {
        reminderId = intent.getIntExtra("id", -1)
        val r = Reminders.all(this).firstOrNull { it.id == reminderId }
        if (r == null || r.state in setOf("dismissed", "missed", "stopped", "interrupted by next reminder")) { finish(); return }
        if (renderedState == r.state) return
        if (r.state == "scheduled") { finish(); return }
        renderedState = r.state
        handler.removeCallbacks(timer)
        val ringing = r.state == "ringing"
        val speaking = r.state in setOf("answering", "speaking")
        if (speaking && connectedAt == 0L) connectedAt = SystemClock.elapsedRealtime()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(26), dp(28), dp(30))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(28, 61, 60), Color.rgb(12, 25, 29), Color.rgb(7, 15, 22)))
        }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(dp(28) + bars.left, dp(26) + bars.top, dp(28) + bars.right, dp(30) + bars.bottom)
            insets
        }
        with(root) {
            text("CALL REMINDER", 12f, true).apply { letterSpacing = .2f; gravity = Gravity.CENTER }
            status = text(when {
                ringing -> "Incoming meeting call"
                speaking -> "Connecting…"
                r.state == "speech unavailable" -> "Voice unavailable"
                else -> "Call ended"
            }, 16f).apply { gravity = Gravity.CENTER; setTextColor(Color.rgb(167, 190, 188)) }
            text(r.title, 34f).apply { gravity = Gravity.CENTER; setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)) }
            text("${if (r.test) "Test meeting" else "Meeting"}  ·  ${clock(r.meetingAt)}", 15f).gravity = Gravity.CENTER
            addView(Space(this@ReminderActivity), LinearLayout.LayoutParams(1, 0, 1f))
            val avatar = TextView(this@ReminderActivity).apply {
                text = r.title.trim().firstOrNull()?.uppercase() ?: "M"
                textSize = 54f; gravity = Gravity.CENTER; setTextColor(white)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(46, 83, 80)); setStroke(dp(1), Color.rgb(89, 128, 118))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(avatar, LinearLayout.LayoutParams(dp(128), dp(128)))
            if (ringing) {
                avatar.animate().scaleX(1.04f).scaleY(1.04f).setDuration(900).withEndAction {
                    avatar.animate().scaleX(1f).scaleY(1f).setDuration(900).start()
                }.start()
            } else {
                text(if (r.state == "speech unavailable") "Install an English voice in Android’s text-to-speech settings to hear reminders."
                    else r.spokenText.ifEmpty { Announcement.text(r, System.currentTimeMillis()) }, 18f).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0)
                }
            }
            addView(Space(this@ReminderActivity), LinearLayout.LayoutParams(1, 0, 1f))
            if (ringing) {
                val decline = Button(this@ReminderActivity).apply {
                    tag = "decline_call"
                    setText(R.string.decline); isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.rgb(189, 62, 74)); cornerRadius = dp(32).toFloat() }
                    setOnClickListener { command("dismiss") }
                }
                addView(decline, LinearLayout.LayoutParams(dp(138), dp(56)).apply { bottomMargin = dp(26) })
                val swipe = SwipeAnswerView(this@ReminderActivity).apply {
                    tag = "answer_swipe"
                    setOnClickListener { command("answer") }
                }
                addView(swipe, LinearLayout.LayoutParams(-1, dp(76)))
                text("Swipe to hear your meeting details", 13f).apply { gravity = Gravity.CENTER; setTextColor(Color.rgb(167, 190, 188)) }
                val skip = TextView(this@ReminderActivity).apply {
                    setText(R.string.skip_both); gravity = Gravity.CENTER; textSize = 14f
                    setTextColor(Color.rgb(167, 190, 188)); setPadding(dp(8), dp(12), dp(8), dp(12))
                    setOnClickListener { Reminders.cancel(this@ReminderActivity); finish() }
                }
                addView(skip, LinearLayout.LayoutParams(-1, dp(48)))
            } else {
                button(if (speaking) "End call" else "Close") {
                    if (speaking) command("dismiss") else finish()
                }.apply {
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.rgb(189, 62, 74)); cornerRadius = dp(32).toFloat() }
                }
                text("${if (r.sequence == 1) "Your second reminder is still scheduled." else "This was your final reminder."}", 13f).gravity = Gravity.CENTER
            }
        }
        if (speaking) handler.post(timer)
        if (r.state == "answered") handler.postDelayed({ if (renderedState == "answered") finish() }, 1800)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); unregisterReceiver(changes); super.onDestroy() }
}
