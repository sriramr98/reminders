package com.sriram.callreminder

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import java.text.DateFormat
import java.util.Date

/** Plays a bounded alarm; meeting actions never start speech or claim call audio. */
class RingService : Service() {
    companion object {
        @Volatile var currentId: Int? = null; private set
        const val NOTIFICATION_ID = 100
        const val ALERT_CHANNEL = "meeting_reminders"
        const val VISIBLE_CHANNEL = "meeting_reminders_visible"
        fun notification(c: Context, r: Reminder, visible: Boolean, missed: Boolean = false): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Meeting reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ringing meeting alarms"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            nm.createNotificationChannel(NotificationChannel(VISIBLE_CHANNEL, "Reminders while app is open", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Meeting details are already on screen"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            nm.createNotificationChannel(NotificationChannel("missed", "Missed reminders", NotificationManager.IMPORTANCE_DEFAULT))
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val screen = PendingIntent.getActivity(c, r.id, Intent(c, ReminderActivity::class.java).putExtra("id", r.id), flags)
            fun action(name: String) = PendingIntent.getBroadcast(c, r.id,
                Intent(c, ReminderActionReceiver::class.java).setAction(name).putExtra("id", r.id), flags)
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.meetingAt))
            val summary = if (missed) "Missed reminder · $time" else "${ReminderInfo.remaining(r.meetingAt, System.currentTimeMillis())} · $time"
            return Notification.Builder(c, if (missed) "missed" else if (visible) VISIBLE_CHANNEL else ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_alarm).setContentTitle(r.title).setContentText(summary)
                .setStyle(Notification.BigTextStyle().bigText("$summary\n${ReminderInfo.scheduleSummary(c, r)}"))
                .setCategory(if (missed) Notification.CATEGORY_REMINDER else Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(Notification.Builder(c, if (missed) "missed" else if (visible) VISIBLE_CHANNEL else ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_alarm).setContentTitle("Meeting reminder").setContentText("Unlock to view meeting details").build())
                .setContentIntent(screen).setOnlyAlertOnce(true).setOngoing(!missed).setAutoCancel(missed)
                .setDeleteIntent(action("dismiss"))
                .addAction(Notification.Action.Builder(null, "Dismiss", action("dismiss")).build())
                .addAction(Notification.Action.Builder(null, "Not attending", action("decline")).build())
                .apply {
                    if (ReminderInfo.canOpenMeeting(c, r)) {
                        // Open an activity directly: service/receiver notification trampolines are blocked.
                        val browser = PendingIntent.getActivity(c, r.id,
                            Intent(c, ReminderActivity::class.java).setAction("open_meeting").putExtra("id", r.id), flags)
                        addAction(Notification.Action.Builder(null, "Open meeting in browser", browser).build())
                    }
                    if (!visible && !missed) setFullScreenIntent(screen, true)
                }.build()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var active: Reminder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeout = Runnable { end("missed", true) }
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val latest = Reminders.all(c).firstOrNull { it.id == active?.id }
            if (latest == null || latest.state != "ringing") {
                if (active != null) { active = null; currentId = null; release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            } else {
                active = latest
                c.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                    notification(c, latest, ReminderPresentation.visibleActivity() != null))
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(changes, IntentFilter("com.sriram.callreminder.STATE"))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep old dismiss intents safe after an upgrade; obsolete answer/speaker actions do nothing.
        if (intent?.action != null) {
            if (intent.action == "dismiss") Reminders.dismiss(this, intent.getIntExtra("id", -1))
            if (active == null) stopSelf()
            return START_NOT_STICKY
        }
        val r = Reminders.all(this).firstOrNull { it.id == intent?.getIntExtra("id", -1) && it.state == "ringing" }
        if (r == null) { if (active == null) stopSelf(); return START_NOT_STICKY }
        if (active?.id == r.id) return START_NOT_STICKY
        active?.let { Reminders.update(this, it.id, "interrupted by next reminder") }
        release()
        active = r
        currentId = r.id
        val visible = ReminderPresentation.visibleActivity()
        startForeground(NOTIFICATION_ID, notification(this, r, visible != null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callreminder:ring").apply { acquire(35_000) }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) { android.util.Log.e("CallReminder", "Alarm sound unavailable", e) }
        vibrator = getSystemService(VibratorManager::class.java).defaultVibrator.apply {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 1200), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        handler.postDelayed(timeout, 30_000)
        visible?.let { ReminderPresentation.show(it, r.id) }
        Reminders.changed(this)
        return START_NOT_STICKY
    }
    private fun end(state: String, missed: Boolean) {
        val r = active?.let { current -> Reminders.all(this).firstOrNull { it.id == current.id && it.state == "ringing" } }
        r?.let { Reminders.update(this, it.id, state) }
        active = null
        currentId = null
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (missed && r != null) getSystemService(NotificationManager::class.java)
            .notify(200 + r.id, notification(this, r.copy(state = state), false, missed = true))
        Reminders.changed(this)
        stopSelf()
    }
    private fun release() {
        handler.removeCallbacks(timeout)
        player?.release(); player = null
        vibrator?.cancel(); vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }
    override fun onDestroy() {
        active?.let { current ->
            if (Reminders.all(this).any { it.id == current.id && it.state == "ringing" }) Reminders.update(this, current.id, "stopped")
        }
        active = null
        currentId = null
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        unregisterReceiver(changes)
        Reminders.changed(this)
        super.onDestroy()
    }
}

/** Notification actions also work after the ringing service has timed out. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val r = Reminders.all(c).firstOrNull { it.id == id } ?: return
        if (r.state !in setOf("ringing", "missed")) return
        when (i.action) {
            "dismiss" -> Reminders.dismiss(c, id)
            "decline" -> Reminders.skipOccurrence(c, id)
        }
    }
}
