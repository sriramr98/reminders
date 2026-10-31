package com.sriram.callreminder

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneId

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

data class CalendarInfo(val id: String, val title: String, val primary: Boolean) {
    fun json() = JSONObject().put("id", id).put("title", title).put("primary", primary)
    companion object { fun read(o: JSONObject) = CalendarInfo(o.getString("id"), o.optString("title"), o.optBoolean("primary")) }
}
data class CalendarMeeting(val key: String, val calendarId: String, val eventId: String, val title: String,
                           val startAt: Long, val joinUrl: String, val updatedAt: Long) {
    fun json() = JSONObject().put("key", key).put("calendarId", calendarId).put("eventId", eventId)
        .put("title", title).put("startAt", startAt).put("joinUrl", joinUrl).put("updatedAt", updatedAt)
    companion object {
        fun read(o: JSONObject) = CalendarMeeting(o.getString("key"), o.getString("calendarId"), o.getString("eventId"),
            o.getString("title"), o.getLong("startAt"), o.optString("joinUrl"), o.optLong("updatedAt"))
    }
}

object CalendarEvents {
    private fun instant(value: String) = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    private fun eventTime(value: JSONObject?): Long? {
        value ?: return null
        val dateTime = value.optString("dateTime")
        return instant(dateTime) ?: runCatching {
            LocalDateTime.parse(dateTime).atZone(ZoneId.of(value.getString("timeZone"))).toInstant().toEpochMilli()
        }.getOrNull()
    }
    fun safeUrl(value: String): String = value.trim().takeIf {
        val uri = Uri.parse(it)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && it.none(Char::isISOControl)
    } ?: ""
    private fun joinUrl(event: JSONObject): String {
        val conference = event.optJSONObject("conferenceData")?.optJSONArray("entryPoints")?.objects()
            ?.firstOrNull { it.optString("entryPointType") == "video" }?.optString("uri").orEmpty()
        listOf(conference, event.optString("hangoutLink")).forEach { safeUrl(it).takeIf(String::isNotEmpty)?.let { url -> return url } }
        val links = Regex("https://[^\\s<>\"']+").findAll(event.optString("location") + " " + event.optString("description"))
        for (link in links) {
            val url = safeUrl(link.value.replace("&amp;", "&").trimEnd('.', ',', ')'))
            val host = Uri.parse(url).host.orEmpty().lowercase()
            if (host == "meet.google.com" || host == "teams.microsoft.com" || host == "teams.live.com" ||
                host == "zoom.us" || host.endsWith(".zoom.us") || host.endsWith(".webex.com")) return url
        }
        return ""
    }
    fun parse(calendarId: String, event: JSONObject, now: Long): CalendarMeeting? {
        if (event.optString("status") == "cancelled") return null
        if (event.optString("eventType", "default") != "default") return null
        if (event.optJSONArray("attendees")?.objects()?.any { it.optBoolean("self") && it.optString("responseStatus") == "declined" } == true) return null
        val start = eventTime(event.optJSONObject("start")) ?: return null
        if (start <= now) return null // All-day events and already-started occurrences are not calls.
        val id = event.optString("id").takeIf(String::isNotEmpty) ?: return null
        val original = eventTime(event.optJSONObject("originalStartTime"))
        val uid = event.optString("iCalUID")
        val identity = if (uid.isNotEmpty()) JSONArray().put(uid).put(original ?: 0).toString()
            else JSONArray().put(calendarId).put(id).toString()
        return CalendarMeeting(identity, calendarId, id, event.optString("summary").trim().ifEmpty { "Untitled meeting" },
            start, joinUrl(event), instant(event.optString("updated")) ?: 0)
    }
    fun deduplicate(meetings: List<CalendarMeeting>) = meetings.groupBy { it.key }.values
        .map { copies -> copies.maxBy { it.updatedAt } }.sortedBy { it.startAt }
}

class CalendarHttpException(val status: Int) : IOException(when (status) {
    401 -> "Google access expired. Reconnect Calendar."
    403 -> "Google denied Calendar access. Check API setup, consent, and account permissions."
    429 -> "Google Calendar is busy. Try syncing again later."
    else -> "Google Calendar could not sync (HTTP $status). Your previous schedule is retained."
})

/** Reads only GET endpoints. Tokens stay in memory; redirects never receive authorization. */
class CalendarApi(private val token: String, private val get: ((String) -> JSONObject)? = null) {
    private fun read(url: String): JSONObject {
        get?.let { return it(url) }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) throw CalendarHttpException(connection.responseCode)
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }
    private fun pages(base: Uri.Builder): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var page = ""
        do {
            val url = base.build().buildUpon().apply { if (page.isNotEmpty()) appendQueryParameter("pageToken", page) }.build().toString()
            val response = read(url)
            all += response.optJSONArray("items")?.objects().orEmpty()
            page = response.optString("nextPageToken")
            if (all.size > 20_000 || (page.isNotEmpty() && (!seen.add(page) || seen.size > 100))) throw IOException("Calendar response exceeded safe sync limits; previous schedule retained.")
        } while (page.isNotEmpty())
        return all
    }
    fun calendars(): List<CalendarInfo> = pages(Uri.parse("https://www.googleapis.com/calendar/v3/users/me/calendarList").buildUpon()
        .appendQueryParameter("minAccessRole", "reader").appendQueryParameter("maxResults", "250"))
        .filterNot { it.optBoolean("deleted") }.map {
            CalendarInfo(it.getString("id"), it.optString("summaryOverride", it.optString("summary", "Calendar")), it.optBoolean("primary"))
        }.sortedByDescending { it.primary }
    fun meetings(calendar: CalendarInfo, now: Long, until: Long): List<CalendarMeeting> = pages(
        Uri.parse("https://www.googleapis.com/calendar/v3/calendars").buildUpon().appendPath(calendar.id).appendPath("events")
            .appendQueryParameter("singleEvents", "true").appendQueryParameter("showDeleted", "false")
            .appendQueryParameter("eventTypes", "default").appendQueryParameter("maxResults", "2500")
            .appendQueryParameter("timeMin", Instant.ofEpochMilli(now).toString())
            .appendQueryParameter("timeMax", Instant.ofEpochMilli(until).toString()))
        .mapNotNull { CalendarEvents.parse(calendar.id, it, now) }.filter { it.startAt < until }
}
