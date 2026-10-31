package com.sriram.callreminder

import android.content.Context
import org.json.JSONArray

/** Generation checks prevent in-flight responses from restoring disconnected/deselected data. */
data class CalendarSession(val account: String, val generation: Int, val selected: Set<String>, val initialized: Boolean)

object CalendarStore {
    private fun prefs(c: Context) = c.getSharedPreferences("calendar", Context.MODE_PRIVATE)
    @Synchronized fun session(c: Context) = prefs(c).let {
        CalendarSession(it.getString("account", "").orEmpty(), it.getInt("generation", 0),
            it.getStringSet("selected", emptySet()).orEmpty().toSet(), it.getBoolean("initialized", false))
    }
    @Synchronized fun calendars(c: Context) = JSONArray(prefs(c).getString("calendars", "[]")).objects().map(CalendarInfo::read)
    private fun rawMeetings(c: Context) = JSONArray(prefs(c).getString("meetings", "[]")).objects().map(CalendarMeeting::read)
    @Synchronized fun meetings(c: Context) = CalendarEvents.deduplicate(rawMeetings(c))
    fun lastSync(c: Context) = prefs(c).getLong("lastSync", 0)
    fun error(c: Context) = prefs(c).getString("error", "").orEmpty()
    @Synchronized fun shouldRefresh(c: Context): Boolean {
        if (session(c).account.isEmpty()) return false
        val now = System.currentTimeMillis()
        if (now - maxOf(lastSync(c), prefs(c).getLong("lastAutoAttempt", 0)) < 5 * 60_000L) return false
        prefs(c).edit().putLong("lastAutoAttempt", now).apply()
        return true
    }
    @Synchronized fun connect(c: Context, account: String) {
        if (session(c).account == account) return
        val generation = session(c).generation + 1
        check(prefs(c).edit().clear().putString("account", account).putInt("generation", generation).commit())
        Reminders.reconcile(c, emptyList())
    }
    @Synchronized fun disconnect(c: Context) {
        val generation = session(c).generation + 1
        check(prefs(c).edit().clear().putInt("generation", generation).commit())
        Reminders.reconcile(c, emptyList())
        CalendarRefresh.cancel(c)
    }
    @Synchronized fun select(c: Context, ids: Set<String>) {
        val state = session(c)
        val selected = ids.intersect(calendars(c).map { it.id }.toSet())
        val retained = rawMeetings(c).filter { it.calendarId in selected }
        check(prefs(c).edit().putStringSet("selected", selected).putBoolean("initialized", true)
            .putInt("generation", state.generation + 1)
            .putString("meetings", JSONArray().apply { retained.forEach { put(it.json()) } }.toString()).commit())
        Reminders.reconcile(c, CalendarEvents.deduplicate(retained))
    }
    @Synchronized fun apply(c: Context, expected: CalendarSession, calendars: List<CalendarInfo>, ids: Set<String>, meetings: List<CalendarMeeting>, now: Long): Boolean {
        if (!current(c, expected)) return false
        check(prefs(c).edit().putString("calendars", JSONArray().apply { calendars.forEach { put(it.json()) } }.toString())
            .putStringSet("selected", ids).putBoolean("initialized", true)
            .putString("meetings", JSONArray().apply { meetings.forEach { put(it.json()) } }.toString())
            .putLong("lastSync", now).remove("error").commit())
        Reminders.reconcile(c, CalendarEvents.deduplicate(meetings), now)
        return true
    }
    @Synchronized fun failure(c: Context, expected: CalendarSession, message: String) {
        if (current(c, expected)) prefs(c).edit().putString("error", message).apply()
        Reminders.changed(c)
    }
    @Synchronized fun current(c: Context, expected: CalendarSession) = session(c).let {
        it.account.isNotEmpty() && it.account == expected.account && it.generation == expected.generation
    }
    @Synchronized fun restore(c: Context) {
        if (session(c).account.isNotEmpty()) Reminders.reconcile(c, meetings(c))
    }
}

object CalendarSync {
    /** Serialize fetches, but never hold CalendarStore's lock during network I/O. */
    @Synchronized fun refresh(c: Context, expected: CalendarSession, api: CalendarApi, now: Long = System.currentTimeMillis()): Boolean {
        if (!CalendarStore.current(c, expected)) return false
        val calendars = api.calendars()
        val ids = if (expected.initialized) expected.selected.intersect(calendars.map { it.id }.toSet())
            else calendars.firstOrNull { it.primary }?.let { setOf(it.id) } ?: calendars.firstOrNull()?.let { setOf(it.id) }.orEmpty()
        val meetings = calendars.filter { it.id in ids }.flatMap { api.meetings(it, now, now + 7 * 24 * 60 * 60_000L) }
        return CalendarStore.apply(c, expected, calendars, ids, meetings, now)
    }
}
