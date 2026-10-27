package com.sriram.callreminder

import android.app.*
import android.content.*
import org.json.JSONArray
import org.json.JSONObject

/** Calendar integration will supply real occurrences to this same scheduler. */
data class Reminder(val id: Int, val title: String, val meetingAt: Long, val fireAt: Long,
                    val leadMinutes: Int, val test: Boolean, val state: String = "scheduled", val spokenText: String = "") {
    val sequence get() = if (leadMinutes == 15) 1 else 2
    fun json() = JSONObject().put("id", id).put("title", title).put("meetingAt", meetingAt)
        .put("fireAt", fireAt).put("leadMinutes", leadMinutes).put("test", test).put("state", state).put("spokenText", spokenText)
    companion object {
        fun read(o: JSONObject) = Reminder(o.getInt("id"), o.getString("title"), o.getLong("meetingAt"),
            o.getLong("fireAt"), o.getInt("leadMinutes"), o.getBoolean("test"), o.getString("state"), o.optString("spokenText"))
    }
}

object Reminders {
    private fun prefs(c: Context) = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    fun all(c: Context): List<Reminder> {
        val array = JSONArray(prefs(c).getString("items", "[]"))
        return (0 until array.length()).map { Reminder.read(array.getJSONObject(it)) }
    }
    private fun save(c: Context, items: List<Reminder>) {
        check(prefs(c).edit().putString("items", JSONArray().apply { items.forEach { put(it.json()) } }.toString()).commit())
    }
    fun update(c: Context, id: Int, state: String, spokenText: String? = null) = save(c, all(c).map {
        if (it.id == id) it.copy(state = state, spokenText = spokenText ?: it.spokenText) else it
    })
    fun times(meetingAt: Long) = listOf(15, 2).map { it to meetingAt - it * 60_000L }
    fun canSchedule(c: Context) = c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun pending(c: Context, r: Reminder) = PendingIntent.getBroadcast(c, r.id,
        Intent(c, AlarmReceiver::class.java).putExtra("id", r.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(c: Context, r: Reminder) {
        val show = PendingIntent.getActivity(c, r.id, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(r.fireAt, show), pending(c, r))
    }
    fun start(c: Context, test: Boolean) {
        check(canSchedule(c)) { "Allow alarms and reminders first" }
        cancel(c)
        val now = System.currentTimeMillis()
        val meeting = now + if (test) 70_000L else 20 * 60_000L
        val firstId = prefs(c).getInt("nextId", 1)
        check(prefs(c).edit().putInt("nextId", firstId + 2).commit())
        val items = times(meeting).mapIndexed { index, (lead, at) ->
            Reminder(firstId + index, if (test) "Your test meeting" else "Sample meeting", meeting,
                if (test) now + if (index == 0) 10_000L else 50_000L else at, lead, test)
        }
        save(c, items)
        try { items.forEach { schedule(c, it) } } catch (e: SecurityException) { cancel(c); throw e }
    }
    fun cancel(c: Context) {
        all(c).forEach { c.getSystemService(AlarmManager::class.java).cancel(pending(c, it)) }
        save(c, emptyList())
        c.stopService(Intent(c, RingService::class.java))
        c.getSystemService(NotificationManager::class.java).cancelAll()
    }
    fun restore(c: Context) {
        if (!canSchedule(c)) return
        val now = System.currentTimeMillis()
        all(c).filter { it.state == "scheduled" }.forEach {
            if (it.fireAt > now) schedule(c, it) else update(c, it.id, "missed while unavailable")
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val r = Reminders.all(c).firstOrNull { it.id == i.getIntExtra("id", -1) && it.state == "scheduled" } ?: return
        Reminders.update(c, r.id, "ringing")
        c.startForegroundService(Intent(c, RingService::class.java).putExtra("id", r.id))
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) Reminders.restore(c)
    }
}
