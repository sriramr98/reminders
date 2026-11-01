package com.sriram.callreminder

import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** Information-first alarm screen, also used by the notification's browser action. */
class ReminderActivity : BaseActivity() {
    private var reminderId = -1
    private var renderedReminder: Reminder? = null
    private var timing: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderedReminder?.let { timing?.text = ReminderInfo.remaining(it.meetingAt, System.currentTimeMillis()) }
            handler.postDelayed(this, 1000)
        }
    }
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { render() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setShowWhenLocked(true); setTurnScreenOn(true)
        volumeControlStream = android.media.AudioManager.STREAM_ALARM
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"))
        render()
        openIfRequested()
    }
    override fun onResume() { super.onResume(); render(); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); renderedReminder = null; render(); openIfRequested()
    }
    private fun openIfRequested() {
        if (intent.action != "open_meeting") return
        intent.action = null
        openMeeting()
    }
    private fun openMeeting() {
        val r = Reminders.all(this).firstOrNull { it.id == reminderId } ?: return
        if (!ReminderInfo.canOpenMeeting(this, r)) return
        MeetingLinks.open(this, r.joinUrl, allowed = {
            Reminders.all(this).firstOrNull { it.id == r.id }?.let { it.joinUrl == r.joinUrl && ReminderInfo.canOpenMeeting(this, it) } == true
        }) { Reminders.dismiss(this, r.id); finish() }
    }
    private fun render() {
        reminderId = intent.getIntExtra("id", -1)
        val r = Reminders.all(this).firstOrNull { it.id == reminderId }
        if (r == null || r.state !in setOf("ringing", "missed", "dismissed")) { finish(); return }
        // Missed/dismissed details stay available, including the final browser action.
        val ringing = r.state == "ringing"
        if (ringing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderedReminder = r
        with(column()) {
            text(if (r.test) "TEST REMINDER" else "MEETING REMINDER", 12f, true).apply { letterSpacing = .15f }
            text(if (ringing) "Your meeting is coming up" else if (r.state == "missed") "Missed reminder" else "Reminder dismissed", 18f)
            text(r.title, 32f).apply { setTypeface(null, Typeface.BOLD); tag = "meeting_title" }
            timing = text(ReminderInfo.remaining(r.meetingAt, System.currentTimeMillis()), 24f, true).apply {
                tag = "meeting_remaining"
                setPadding(0, dp(20), 0, dp(12))
            }
            text(DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(r.meetingAt)), 17f)
            val calendar = CalendarStore.calendars(this@ReminderActivity).firstOrNull { it.id == r.calendarId }
            text(if (r.sourceKey.isEmpty()) "Sample meeting" else calendar?.title ?: "Google Calendar", 15f)
            text(ReminderInfo.scheduleSummary(this@ReminderActivity, r), 15f, true)
            if (ReminderInfo.canOpenMeeting(this@ReminderActivity, r)) button("Open meeting in browser", true) { openMeeting() }.tag = "open_meeting"
            if (ringing) button("Dismiss this reminder", true) {
                Reminders.dismiss(this@ReminderActivity, r.id); finish()
            }.tag = "dismiss_reminder"
            text("Not attending? Stop all remaining reminders for this occurrence. Your Google Calendar RSVP will not change.", 14f)
            button("Not attending") {
                Reminders.skipOccurrence(this@ReminderActivity, r.id); finish()
            }.apply {
                tag = "decline_reminder"
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(Color.rgb(148, 52, 65)); cornerRadius = dp(18).toFloat() }
            }
            if (!ringing) button("Close") { finish() }
        }
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); unregisterReceiver(changes); super.onDestroy() }
}
