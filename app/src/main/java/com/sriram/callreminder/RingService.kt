package com.sriram.callreminder

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var active: Reminder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var speech: TextToSpeech? = null
    private var answering = false
    private var focus: AudioFocusRequest? = null
    private val speechTimeout = Runnable { end("speech unavailable", false) }
    private val timeout = Runnable { end("missed", true) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "dismiss") {
            if (intent.getIntExtra("id", -1) == active?.id) end(if (answering) "answered" else "dismissed", false)
            else if (active == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "answer") {
            if (intent.getIntExtra("id", -1) == active?.id && !answering) answer()
            else if (active == null) stopSelf()
            return START_NOT_STICKY
        }
        val r = Reminders.all(this).firstOrNull { it.id == intent?.getIntExtra("id", -1) && it.state == "ringing" }
        if (r == null) { if (active == null) stopSelf(); return START_NOT_STICKY }
        active?.let { Reminders.update(this, it.id, "interrupted by next reminder") }
        release()
        answering = false
        active = r
        val nm = getSystemService(NotificationManager::class.java)
        val visible = CallPresentation.visibleActivity()
        nm.createNotificationChannel(NotificationChannel("meeting_alarms", "Meeting calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ringing meeting reminders"
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel("meeting_calls_visible", "Calls while app is open", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The call screen is already visible, so no banner is needed"
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val screen = PendingIntent.getActivity(this, r.id, Intent(this, ReminderActivity::class.java).putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getService(this, r.id, Intent(this, RingService::class.java).setAction("dismiss").putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Answer opens an activity directly. A service-to-activity notification trampoline
        // is blocked on modern Android, and would lose the user's Answer action.
        val answer = PendingIntent.getActivity(this, r.id,
            Intent(this, ReminderActivity::class.java).setAction("answer").putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val caller = Person.Builder().setName(r.title).setImportant(true).build()
        val notification = Notification.Builder(this, if (visible != null) "meeting_calls_visible" else "meeting_alarms").setSmallIcon(com.sriram.callreminder.R.drawable.ic_alarm)
            .setContentTitle(r.title).setContentText(if (r.test) "Test reminder ${r.sequence} of 2" else "Starts in ${r.leadMinutes} minutes")
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setStyle(Notification.CallStyle.forIncomingCall(caller, dismiss, answer))
            .setContentIntent(screen).setOnlyAlertOnce(true).setOngoing(true)
            .apply { if (visible == null) setFullScreenIntent(screen, true) }
        startForeground(100, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
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
        visible?.let { CallPresentation.show(it, r.id) }
        sendBroadcast(Intent("com.sriram.callreminder.STATE").setPackage(packageName))
        return START_NOT_STICKY
    }
    private fun changed() { sendBroadcast(Intent("com.sriram.callreminder.STATE").setPackage(packageName)) }
    private fun answer() {
        val r = active ?: return
        release() // Stop ringtone, vibration, and the missed-call timeout before speaking.
        answering = true
        val message = Announcement.text(r, System.currentTimeMillis())
        Reminders.update(this, r.id, "answering", message)
        changed()
        val dismiss = PendingIntent.getService(this, r.id,
            Intent(this, RingService::class.java).setAction("dismiss").putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val screen = PendingIntent.getActivity(this, r.id, Intent(this, ReminderActivity::class.java).putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("meeting_calls_visible", "Calls while app is open", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        nm.notify(100, Notification.Builder(this, "meeting_calls_visible")
            .setSmallIcon(R.drawable.ic_alarm).setContentTitle(r.title).setContentText("Reading your meeting reminder")
            .setContentIntent(screen).setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "End call", dismiss).build()).build())
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callreminder:speech").apply { acquire(35_000) }
        handler.postDelayed(speechTimeout, 30_000)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    if (active?.id == r.id && answering) end("speech unavailable", false)
                }
            }, handler).build()
        if (getSystemService(AudioManager::class.java).requestAudioFocus(focus!!) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            end("speech unavailable", false); return
        }
        speech = TextToSpeech(this) { status ->
            // Some engines invoke initialization synchronously; post after the instance is assigned.
            handler.post initialized@{
                if (active?.id != r.id || !answering) return@initialized
                val engine = speech
                if (status != TextToSpeech.SUCCESS || engine == null) { end("speech unavailable", false); return@initialized }
                var language = engine.setLanguage(Locale.forLanguageTag("en-IN"))
                if (language < TextToSpeech.LANG_AVAILABLE) language = engine.setLanguage(Locale.US)
                if (language < TextToSpeech.LANG_AVAILABLE) { end("speech unavailable", false); return@initialized }
                engine.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                    ?.sortedBy { if (it.locale.country == "IN") 0 else if (it.locale.country == "US") 1 else 2 }
                    ?.firstOrNull()?.let { engine.voice = it }
                engine.setAudioAttributes(attributes)
                engine.setSpeechRate(.93f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { handler.post {
                        if (active?.id == r.id && answering) { Reminders.update(this@RingService, r.id, "speaking"); changed() }
                    } }
                    override fun onDone(utteranceId: String?) { finishSpeech("answered") }
                    @Deprecated("Required for older engines")
                    override fun onError(utteranceId: String?) { finishSpeech("speech unavailable") }
                    override fun onError(utteranceId: String?, errorCode: Int) { finishSpeech("speech unavailable") }
                    private fun finishSpeech(state: String) { handler.post {
                        if (active?.id == r.id && answering) end(state, false)
                    } }
                })
                if (engine.speak(message, TextToSpeech.QUEUE_FLUSH, Bundle(), "reminder-${r.id}") == TextToSpeech.ERROR) end("speech unavailable", false)
            }
        }
    }
    private fun end(state: String, missed: Boolean) {
        val r = active
        r?.let { Reminders.update(this, it.id, state) }
        active = null
        answering = false
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
        handler.removeCallbacks(speechTimeout)
        speech?.stop(); speech?.shutdown(); speech = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
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
