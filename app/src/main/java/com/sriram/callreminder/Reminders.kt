package com.sriram.callreminder

import android.app.*
import android.content.*
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(val id: Int, val title: String, val meetingAt: Long, val fireAt: Long,
                    val leadMinutes: Int, val test: Boolean, val state: String = "scheduled", val spokenText: String = "",
                    val audioRoute: String = "", val sourceKey: String = "", val calendarId: String = "", val joinUrl: String = "") {
    val sequence get() = if (leadMinutes == 15) 1 else 2
    fun json() = JSONObject().put("id", id).put("title", title).put("meetingAt", meetingAt)
        .put("fireAt", fireAt).put("leadMinutes", leadMinutes).put("test", test).put("state", state).put("spokenText", spokenText)
        .put("audioRoute", audioRoute).put("sourceKey", sourceKey).put("calendarId", calendarId).put("joinUrl", joinUrl)
    companion object {
        fun read(o: JSONObject) = Reminder(o.getInt("id"), o.getString("title"), o.getLong("meetingAt"),
            o.getLong("fireAt"), o.getInt("leadMinutes"), o.getBoolean("test"), o.getString("state"), o.optString("spokenText"),
            o.optString("audioRoute"), o.optString("sourceKey"), o.optString("calendarId"), o.optString("joinUrl"))
    }
}

object Reminders {
    private fun prefs(c: Context) = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    @Synchronized fun all(c: Context): List<Reminder> = JSONArray(prefs(c).getString("items", "[]")).objects().map(Reminder::read)
    private fun save(c: Context, items: List<Reminder>) {
        check(prefs(c).edit().putString("items", JSONArray().apply { items.forEach { put(it.json()) } }.toString()).commit())
    }
    private fun nextId(c: Context): Int {
        val id = prefs(c).getInt("nextId", 1)
        check(prefs(c).edit().putInt("nextId", id + 1).commit())
        return id
    }
    @Synchronized fun update(c: Context, id: Int, state: String, spokenText: String? = null) = save(c, all(c).map {
        if (it.id == id && it.state != "skipped") it.copy(state = state, spokenText = spokenText ?: it.spokenText) else it
    })
    @Synchronized fun route(c: Context, id: Int, route: String) = save(c, all(c).map { if (it.id == id) it.copy(audioRoute = route) else it })
    fun times(meetingAt: Long) = listOf(15, 2).map { it to meetingAt - it * 60_000L }
    fun canSchedule(c: Context) = c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun pending(c: Context, r: Reminder) = PendingIntent.getBroadcast(c, r.id,
        Intent(c, AlarmReceiver::class.java).putExtra("id", r.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(c: Context, r: Reminder) {
        val show = PendingIntent.getActivity(c, r.id, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(r.fireAt, show), pending(c, r))
    }
    private fun cancelAlarms(c: Context, items: List<Reminder>) {
        items.forEach {
            c.getSystemService(AlarmManager::class.java).cancel(pending(c, it))
            c.getSystemService(NotificationManager::class.java).cancel(200 + it.id)
        }
        if (items.any { it.id == RingService.currentId }) c.stopService(Intent(c, RingService::class.java))
    }
    @Synchronized fun start(c: Context, test: Boolean) {
        check(canSchedule(c)) { "Allow alarms and reminders first" }
        cancelSamples(c)
        val now = System.currentTimeMillis()
        val meeting = now + if (test) 70_000L else 20 * 60_000L
        val items = times(meeting).mapIndexed { index, (lead, at) ->
            Reminder(nextId(c), if (test) "Your test meeting" else "Sample meeting", meeting,
                if (test) now + if (index == 0) 10_000L else 50_000L else at, lead, test)
        }
        save(c, all(c) + items)
        try { items.forEach { schedule(c, it) } } catch (e: SecurityException) { cancelSamples(c); throw e }
    }
    @Synchronized fun cancelSamples(c: Context) {
        val previous = all(c)
        save(c, previous.filter { it.sourceKey.isNotEmpty() })
        cancelAlarms(c, previous.filter { it.sourceKey.isEmpty() })
    }
    @Synchronized fun cancel(c: Context) {
        val previous = all(c)
        save(c, emptyList())
        cancelAlarms(c, previous)
    }
    @Synchronized fun skipOccurrence(c: Context, id: Int) {
        val previous = all(c)
        val target = previous.firstOrNull { it.id == id } ?: return
        val group = previous.filter { if (target.sourceKey.isEmpty()) it.sourceKey.isEmpty() else it.sourceKey == target.sourceKey }
        save(c, previous.map { if (it in group) it.copy(state = "skipped") else it })
        cancelAlarms(c, group)
        changed(c)
    }
    /** Apply only a complete successful calendar snapshot. Stable IDs preserve dismissals. */
    @Synchronized fun reconcile(c: Context, meetings: List<CalendarMeeting>, now: Long = System.currentTimeMillis()) {
        val previous = all(c)
        val oldCalendar = previous.filter { it.sourceKey.isNotEmpty() }
        val skipped = oldCalendar.filter { it.state == "skipped" }.map { it.sourceKey }.toSet()
        val next = meetings.filter { it.startAt > now }.flatMap { meeting ->
            times(meeting.startAt).mapNotNull { (lead, at) ->
                val old = oldCalendar.firstOrNull { it.sourceKey == meeting.key && it.leadMinutes == lead }
                when {
                    meeting.key in skipped -> (old ?: Reminder(nextId(c), meeting.title, meeting.startAt, at, lead, false))
                        .copy(title = meeting.title, meetingAt = meeting.startAt, fireAt = at, state = "skipped", sourceKey = meeting.key,
                            calendarId = meeting.calendarId, joinUrl = meeting.joinUrl)
                    old != null && old.meetingAt == meeting.startAt -> old.copy(title = meeting.title, calendarId = meeting.calendarId, joinUrl = meeting.joinUrl,
                        state = if (old.state == "scheduled" && at <= now) "missed while unavailable" else old.state)
                    at > now -> Reminder(nextId(c), meeting.title, meeting.startAt, at, lead, false,
                        sourceKey = meeting.key, calendarId = meeting.calendarId, joinUrl = meeting.joinUrl)
                    else -> null // Never fire missed offsets immediately during a late sync.
                }
            }
        }
        save(c, previous.filter { it.sourceKey.isEmpty() } + next)
        cancelAlarms(c, oldCalendar.filter { old -> next.none { it.id == old.id } })
        if (canSchedule(c)) next.filter { it.state == "scheduled" && it.fireAt > now }.forEach {
            try { schedule(c, it) } catch (_: SecurityException) { /* Cached reminders are armed on permission restoration. */ }
        }
        changed(c)
    }
    @Synchronized fun restore(c: Context) {
        if (!canSchedule(c)) return
        val now = System.currentTimeMillis()
        all(c).filter { it.state == "scheduled" }.forEach {
            if (it.fireAt > now) {
                try { schedule(c, it) } catch (_: SecurityException) { return }
            } else update(c, it.id, "missed while unavailable")
        }
    }
    @Synchronized fun claim(c: Context, id: Int): Reminder? {
        val r = all(c).firstOrNull { it.id == id && it.state == "scheduled" } ?: return null
        update(c, id, "ringing")
        return r
    }
    fun changed(c: Context) { c.sendBroadcast(Intent("com.sriram.callreminder.STATE").setPackage(c.packageName)) }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val r = Reminders.claim(c, i.getIntExtra("id", -1)) ?: return
        c.startForegroundService(Intent(c, RingService::class.java).putExtra("id", r.id))
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            CalendarStore.restore(c)
            Reminders.restore(c)
            CalendarRefresh.schedule(c)
        }
    }
}
