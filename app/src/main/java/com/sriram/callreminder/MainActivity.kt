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
import java.lang.ref.WeakReference

/** Only a visibly resumed activity may open the reminder screen directly. */
object ReminderPresentation {
    private var resumed = WeakReference<Activity>(null)
    fun resumed(activity: Activity) { resumed = WeakReference(activity) }
    fun paused(activity: Activity) { if (resumed.get() === activity) resumed.clear() }
    fun visibleActivity(): Activity? = resumed.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    fun show(activity: Activity, id: Int) {
        activity.startActivity(Intent(activity, ReminderActivity::class.java).putExtra("id", id))
    }
}

open class BaseActivity : Activity() {
    override fun onResume() {
        super.onResume()
        window.insetsController?.setSystemBarsAppearance(0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        ReminderPresentation.resumed(this)
    }
    override fun onPause() { ReminderPresentation.paused(this); super.onPause() }
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
        CalendarStore.restore(this)
        Reminders.restore(this)
        CalendarRefresh.schedule(this)
        CalendarRefresh.refreshSoon(this)
        render()
        Reminders.all(this).firstOrNull { it.state == "ringing" && it.id == RingService.currentId }
            ?.let { ReminderPresentation.show(this, it.id) }
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
            text("MEETING REMINDERS", 12f, true)
            text("A meeting reminder\nyou can’t overlook.", 30f).setTypeface(null, Typeface.BOLD)
            text("Ringing reminders with your meeting details. Dismiss one alert to keep the next, or choose Not attending to stop reminders for that meeting.")
            text("Google Calendar", 22f, true)
            val calendarSession = CalendarStore.session(this@MainActivity)
            text(if (calendarSession.account.isEmpty()) "Connect your account and choose which calendars should ring." else
                "${calendarSession.account} · ${calendarSession.selected.size} calendars selected", 14f)
            button(if (calendarSession.account.isEmpty()) "Connect Google Calendar" else "Calendar & upcoming meetings", true) {
                startActivity(Intent(this@MainActivity, CalendarActivity::class.java))
            }
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
            text("Test reminders ring in 10 and 50 seconds, for up to 30 seconds each. Meeting details open here or on the lock screen when allowed. While using another app, use the reminder notification. There is no spoken playback.", 14f)
            button("Start two test reminders", true) { runTest(true) }.isEnabled = notifications() && Reminders.canSchedule(this@MainActivity)
            button("Schedule sample meeting in 20 min") { runTest(false) }.isEnabled = notifications() && Reminders.canSchedule(this@MainActivity)
            val reminders = Reminders.all(this@MainActivity).filter { it.sourceKey.isEmpty() }
            if (reminders.isNotEmpty()) {
                text("${reminders.first().title} · ${clock(reminders.first().meetingAt)}", 18f, true)
                reminders.forEach { r ->
                    text("${if (r.test) "Test reminder" else "${r.leadMinutes} min before"} · ${clock(r.fireAt)}\n${r.state}", 15f)
                    if (r.state == "ringing") button("Open ringing reminder") {
                        startActivity(Intent(this@MainActivity, ReminderActivity::class.java).putExtra("id", r.id))
                    }
                }
                button("Cancel sample reminders") { Reminders.cancelSamples(this@MainActivity); render() }
            }
            text("Sample reminders are separate from your synced meetings. Alarm volume and Do Not Disturb settings affect ringing.", 13f)
        }
    }
}
