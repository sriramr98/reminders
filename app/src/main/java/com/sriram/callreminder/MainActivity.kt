package com.sriram.callreminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.text.DateFormat
import java.util.Date

open class BaseActivity : Activity() {
    protected val mint = Color.rgb(136, 224, 194)
    protected val white = Color.rgb(237, 245, 243)
    protected fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    protected fun column(): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(30))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 27, 33)); addView(inner) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return inner
    }
    protected fun LinearLayout.text(value: String, size: Float = 16f, accent: Boolean = false) = TextView(this@BaseActivity).also {
        it.text = value; it.textSize = size; it.setTextColor(if (accent) mint else white)
        it.setPadding(0, dp(8), 0, dp(12)); addView(it)
    }
    protected fun LinearLayout.button(label: String, primary: Boolean = false, action: () -> Unit): Button = Button(this@BaseActivity).also {
        it.text = label; it.isAllCaps = false; it.textSize = 16f
        it.setTextColor(if (primary) Color.rgb(16, 27, 33) else white)
        it.background = GradientDrawable().apply { setColor(if (primary) mint else Color.rgb(36, 51, 60)); cornerRadius = dp(18).toFloat() }
        addView(it, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(10); bottomMargin = dp(4) })
        it.setOnClickListener { action() }
    }
    protected fun clock(at: Long) = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(at))
}

class MainActivity : BaseActivity() {
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { render() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"))
        render()
    }
    override fun onResume() {
        super.onResume()
        Reminders.restore(this)
        render()
    }
    override fun onDestroy() { unregisterReceiver(changes); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
    private fun notifications() = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    private fun fullScreen() = Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    private fun settings(action: String) { startActivity(Intent(action, Uri.parse("package:$packageName"))) }
    private fun runTest(test: Boolean) {
        try { Reminders.start(this, test); render() }
        catch (e: Exception) { Toast.makeText(this, e.message ?: "Could not schedule reminders", Toast.LENGTH_LONG).show() }
    }
    private fun render() {
        val root = column()
        with(root) {
            text("CALL REMINDER  /  PROTOTYPE", 12f, true)
            text("A meeting reminder\nyou can’t overlook.", 30f).setTypeface(null, Typeface.BOLD)
            text("Two calls. One meeting. Dismiss the first and the second still rings.")
            text("Ready to ring", 20f, true)
            if (!notifications()) button("Enable notifications") {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else text("✓ Notifications enabled", 14f)
            if (!Reminders.canSchedule(this@MainActivity)) button("Allow alarms & reminders") { settings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }
            else text("✓ Precise alarm scheduling enabled", 14f)
            if (!fullScreen()) button("Allow full-screen reminders") { settings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }
            else text("✓ Full-screen reminders enabled", 14f)
            text("Quick test", 22f, true)
            text("Calls ring in 10 and 50 seconds. Each rings for up to 30 seconds. Lock the screen after starting. When unlocked, Android may show a notification instead of opening the call screen.", 14f)
            button("Start two test calls", true) { runTest(true) }.isEnabled = notifications() && Reminders.canSchedule(this@MainActivity)
            button("Schedule sample meeting in 20 min") { runTest(false) }.isEnabled = notifications() && Reminders.canSchedule(this@MainActivity)
            val reminders = Reminders.all(this@MainActivity)
            if (reminders.isNotEmpty()) {
                text("${reminders.first().title} · ${clock(reminders.first().meetingAt)}", 18f, true)
                reminders.forEach { r ->
                    text("${if (r.test) "Test call ${r.sequence}" else "${r.leadMinutes} min before"} · ${clock(r.fireAt)}\n${r.state}", 15f)
                    if (r.state == "ringing") button("Open ringing reminder") {
                        startActivity(Intent(this@MainActivity, ReminderActivity::class.java).putExtra("id", r.id))
                    }
                }
                button("Cancel both reminders") { Reminders.cancel(this@MainActivity); render() }
            }
            text("Calendar connection comes next. These sample meetings stay on this device. Alarm volume and Do Not Disturb settings affect audibility.", 13f)
        }
    }
}

class ReminderActivity : BaseActivity() {
    private var reminderId = -1
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (Reminders.all(c).none { it.id == reminderId && it.state == "ringing" }) finish()
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"))
        render()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render() }
    private fun dismiss() {
        startService(Intent(this, RingService::class.java).setAction("dismiss").putExtra("id", reminderId))
        finish()
    }
    private fun render() {
        reminderId = intent.getIntExtra("id", -1)
        val r = Reminders.all(this).firstOrNull { it.id == reminderId && it.state == "ringing" }
        if (r == null) { finish(); return }
        with(column()) {
            gravity = Gravity.CENTER_HORIZONTAL
            text("INCOMING MEETING REMINDER", 12f, true)
            text("◉", 80f, true)
            text(r.title, 34f).apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) }
            text(if (r.test) "Test call ${r.sequence} of 2" else "Starts in ${r.leadMinutes} minutes", 23f, true)
            text("Meeting at ${clock(r.meetingAt)}", 16f)
            val message = when {
                r.test && r.sequence == 1 -> "This is a test of your meeting alarm.\nThe next call remains scheduled when you dismiss this one."
                r.test -> "This is your final test reminder.\nDismiss when you’re ready."
                else -> "Your meeting is coming up.\nDismiss this call when you’re ready."
            }
            text(message, 16f).gravity = Gravity.CENTER
            button("Dismiss this call", true) { dismiss() }
            button("Skip remaining reminders") { Reminders.cancel(this@ReminderActivity); finish() }
            text("Sample meeting · no meeting link attached", 13f)
            for (index in 0 until childCount) {
                (getChildAt(index) as? TextView)?.gravity = Gravity.CENTER
            }
        }
    }
    override fun onDestroy() { unregisterReceiver(changes); super.onDestroy() }
}
