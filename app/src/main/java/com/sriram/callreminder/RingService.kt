package com.sriram.callreminder

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*

class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var active: Reminder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeout = Runnable { end("missed", true) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "dismiss") {
            if (intent.getIntExtra("id", -1) == active?.id) end("dismissed", false)
            return START_NOT_STICKY
        }
        val r = Reminders.all(this).firstOrNull { it.id == intent?.getIntExtra("id", -1) }
        if (r == null) { stopSelf(); return START_NOT_STICKY }
        active?.let { Reminders.update(this, it.id, "interrupted by next reminder") }
        release()
        active = r
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("meeting_alarms", "Meeting calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ringing meeting reminders"
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val screen = PendingIntent.getActivity(this, r.id, Intent(this, ReminderActivity::class.java).putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getService(this, r.id, Intent(this, RingService::class.java).setAction("dismiss").putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "meeting_alarms").setSmallIcon(com.sriram.callreminder.R.drawable.ic_alarm)
            .setContentTitle(r.title).setContentText(if (r.test) "Test reminder ${r.sequence} of 2" else "Starts in ${r.leadMinutes} minutes")
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(screen).setFullScreenIntent(screen, true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Dismiss", dismiss).build()).build()
        startForeground(100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callreminder:ring").apply { acquire(35_000) }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) { android.util.Log.e("CallReminder", "Ringtone unavailable", e) }
        vibrator = getSystemService(VibratorManager::class.java).defaultVibrator.apply {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 1200), 0),
                android.media.AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        handler.postDelayed(timeout, 30_000)
        sendBroadcast(Intent("com.sriram.callreminder.STATE").setPackage(packageName))
        return START_NOT_STICKY
    }
    private fun end(state: String, missed: Boolean) {
        val r = active
        r?.let { Reminders.update(this, it.id, state) }
        active = null
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (missed && r != null) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("missed", "Missed reminders", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(200 + r.id, Notification.Builder(this, "missed").setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("Missed meeting reminder").setContentText(r.title).setContentIntent(open).setAutoCancel(true).build())
        }
        sendBroadcast(Intent("com.sriram.callreminder.STATE").setPackage(packageName))
        stopSelf()
    }
    private fun release() {
        handler.removeCallbacks(timeout)
        player?.release(); player = null
        vibrator?.cancel(); vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }
    override fun onDestroy() {
        active?.let { Reminders.update(this, it.id, "stopped") }
        active = null
        release()
        sendBroadcast(Intent("com.sriram.callreminder.STATE").setPackage(packageName))
        super.onDestroy()
    }
}
