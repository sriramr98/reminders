package com.sriram.callreminder

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class CalendarTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now get() = System.currentTimeMillis()
    @Before fun reset() { CalendarStore.disconnect(context); Reminders.cancel(context) }
    @After fun cleanup() { CalendarStore.disconnect(context); Reminders.cancel(context) }
    private fun event(start: String = "2030-10-28T15:00:00+05:30") = JSONObject("""{
        "id":"event-1", "iCalUID":"meeting@example.test", "summary":"Design review", "status":"confirmed",
        "start":{"dateTime":"$start"}, "updated":"2030-10-20T10:00:00Z",
        "attendees":[{"self":true,"responseStatus":"accepted"}],
        "conferenceData":{"entryPoints":[{"entryPointType":"video","uri":"https://meet.google.com/abc-defg-hij"}]}
    }""")
    private fun meeting(key: String, start: Long, title: String = key, calendar: String = "primary") =
        CalendarMeeting(key, calendar, key, title, start, "https://meet.google.com/abc-defg-hij", 1)
    private fun seed(items: List<CalendarMeeting>): CalendarSession {
        CalendarStore.connect(context, "calendar-test@example.test")
        val session = CalendarStore.session(context)
        assertTrue(CalendarStore.apply(context, session, listOf(CalendarInfo("primary", "Work", true), CalendarInfo("other", "Personal", false)),
            setOf("primary", "other"), items, now))
        return CalendarStore.session(context)
    }
    @Test fun parsingUsesOffsetAndFiltersCancelledDeclinedAllDayAndNonMeetings() {
        val before = Instant.parse("2030-10-28T00:00:00Z").toEpochMilli()
        val parsed = CalendarEvents.parse("primary", event(), before)!!
        assertEquals(Instant.parse("2030-10-28T09:30:00Z").toEpochMilli(), parsed.startAt)
        assertEquals(parsed.startAt, CalendarEvents.parse("primary", event().put("start",
            JSONObject().put("dateTime", "2030-10-28T15:00:00").put("timeZone", "Asia/Kolkata")), before)!!.startAt)
        assertEquals("https://meet.google.com/abc-defg-hij", parsed.joinUrl)
        assertNull(CalendarEvents.parse("primary", event().put("status", "cancelled"), before))
        assertNull(CalendarEvents.parse("primary", event().put("start", JSONObject().put("date", "2030-10-28")), before))
        val declined = event().apply { getJSONArray("attendees").getJSONObject(0).put("responseStatus", "declined") }
        assertNull(CalendarEvents.parse("primary", declined, before))
        assertNotNull(CalendarEvents.parse("primary", declined.apply { getJSONArray("attendees").getJSONObject(0).put("self", false) }, before))
        assertNull(CalendarEvents.parse("primary", event().put("eventType", "outOfOffice"), before))
        assertNull(CalendarEvents.parse("primary", event(), parsed.startAt))
    }
    @Test fun recurrenceExceptionsKeepIdentityAndDuplicateCalendarsProduceOneMeeting() {
        val before = Instant.parse("2030-10-28T00:00:00Z").toEpochMilli()
        val original = JSONObject().put("dateTime", "2030-10-28T15:00:00+05:30")
        val first = CalendarEvents.parse("primary", event().put("originalStartTime", original), before)!!
        val moved = CalendarEvents.parse("other", event("2030-10-28T16:00:00+05:30").put("originalStartTime", original)
            .put("updated", "2030-10-21T10:00:00Z"), before)!!
        assertEquals(first.key, moved.key)
        assertEquals(listOf(moved), CalendarEvents.deduplicate(listOf(first, moved)))
        val next = CalendarEvents.parse("primary", event("2030-10-29T15:00:00+05:30").put("originalStartTime", JSONObject().put("dateTime", "2030-10-29T15:00:00+05:30")), before)!!
        assertNotEquals(first.key, next.key)
    }
    @Test fun meetingLinksRejectUnsafeSchemesAndRecognizeZoomDescription() {
        assertEquals("", CalendarEvents.safeUrl("javascript:alert(1)"))
        assertEquals("", CalendarEvents.safeUrl("https://user:password@example.test/"))
        val withZoom = event().apply {
            remove("conferenceData"); put("description", "Join https://company.zoom.us/j/123?pwd=abc&amp;from=invite")
        }
        assertEquals("https://company.zoom.us/j/123?pwd=abc&from=invite", CalendarEvents.parse("primary", withZoom, 0)!!.joinUrl)
    }
    @Test fun paginationIncludesEmptyPagesAndRequestsExpandedBoundedInstances() {
        var count = 0
        val api = CalendarApi("unused", get = { url ->
            val uri = Uri.parse(url)
            assertEquals("true", uri.getQueryParameter("singleEvents"))
            assertNotNull(uri.getQueryParameter("timeMin")); assertNotNull(uri.getQueryParameter("timeMax"))
            count++
            if (uri.getQueryParameter("pageToken") == null) JSONObject("""{"items":[],"nextPageToken":"next"}""")
            else JSONObject().put("items", org.json.JSONArray().put(event()))
        })
        val start = Instant.parse("2030-10-28T00:00:00Z").toEpochMilli()
        assertEquals(1, api.meetings(CalendarInfo("a/b@example.test", "Work", true), start, start + 86_400_000).size)
        assertEquals(2, count)
    }
    @Test fun resyncPreservesDismissalsAndEditsCancelOldAlarmIds() {
        val start = now + 3_600_000
        val a = meeting("a", start)
        val b = meeting("b", start + 600_000)
        Reminders.reconcile(context, listOf(a, b))
        val first = Reminders.all(context).first { it.sourceKey == "a" && it.leadMinutes == 15 }
        val otherIds = Reminders.all(context).filter { it.sourceKey == "b" }.map { it.id }
        Reminders.update(context, first.id, "dismissed")
        Reminders.reconcile(context, listOf(a.copy(title = "Updated title"), b))
        assertEquals("dismissed", Reminders.all(context).first { it.id == first.id }.state)
        assertEquals("Updated title", Reminders.all(context).first { it.id == first.id }.title)
        Reminders.reconcile(context, listOf(a.copy(startAt = start + 1_200_000), b))
        assertNull(Reminders.claim(context, first.id))
        assertEquals(otherIds, Reminders.all(context).filter { it.sourceKey == "b" }.map { it.id })
        Reminders.reconcile(context, listOf(b))
        assertFalse(Reminders.all(context).any { it.sourceKey == "a" })
    }
    @Test fun skipSurvivesSyncAndRescheduleAndDoesNotCancelOtherMeetings() {
        val a = meeting("a", now + 3_600_000)
        val b = meeting("b", now + 7_200_000)
        Reminders.reconcile(context, listOf(a, b))
        Reminders.skipOccurrence(context, Reminders.all(context).first { it.sourceKey == "a" }.id)
        Reminders.reconcile(context, listOf(a.copy(startAt = a.startAt + 600_000), b))
        assertTrue(Reminders.all(context).filter { it.sourceKey == "a" }.all { it.state == "skipped" })
        assertTrue(Reminders.all(context).filter { it.sourceKey == "b" }.all { it.state == "scheduled" })
    }
    @Test fun lateSyncNeverCreatesOverdueCallsAndSamplesDoNotDeleteCalendarAlarms() {
        val at = now
        Reminders.reconcile(context, listOf(meeting("soon", at + 5 * 60_000), meeting("tooLate", at + 60_000)), at)
        assertEquals(listOf(2), Reminders.all(context).filter { it.sourceKey == "soon" }.map { it.leadMinutes })
        assertFalse(Reminders.all(context).any { it.sourceKey == "tooLate" })
        // A sample pair must never replace the Google Calendar schedule.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        Reminders.start(context, true)
        assertEquals(1, Reminders.all(context).count { it.sourceKey == "soon" })
        Reminders.cancelSamples(context)
        assertEquals(1, Reminders.all(context).size)
    }
    @Test fun failedMultiCalendarFetchRetainsSnapshotAndAlarms() {
        val saved = meeting("saved", now + 3_600_000)
        val state = seed(listOf(saved))
        val alarms = Reminders.all(context)
        val api = CalendarApi("unused", get = { url ->
            if (url.contains("calendarList")) JSONObject("""{"items":[{"id":"primary","summary":"Work","primary":true},{"id":"other","summary":"Other"}]}""")
            else if (url.contains("/other/")) throw CalendarHttpException(503)
            else JSONObject("""{"items":[]}""")
        })
        try { CalendarSync.refresh(context, state, api); fail("A partial fetch must fail") } catch (_: CalendarHttpException) { }
        assertEquals(listOf(saved), CalendarStore.meetings(context))
        assertEquals(alarms, Reminders.all(context))
    }
    @Test fun deselectionAndDisconnectRejectStaleResponsesAndKeepSamplesSeparate() {
        val a = meeting("a", now + 3_600_000)
        val b = meeting("b", now + 7_200_000, calendar = "other")
        val old = seed(listOf(a, b))
        CalendarStore.select(context, setOf("primary"))
        assertFalse(Reminders.all(context).any { it.sourceKey == "b" })
        assertFalse(CalendarStore.apply(context, old, CalendarStore.calendars(context), old.selected, listOf(a, b), now))
        val current = CalendarStore.session(context)
        CalendarStore.disconnect(context)
        assertFalse(CalendarStore.apply(context, current, emptyList(), setOf("primary"), listOf(a), now))
        assertEquals("", CalendarStore.session(context).account)
        assertTrue(Reminders.all(context).isEmpty())
    }
    @Test fun deselectingDuplicateCalendarRetainsMeetingOnStillSelectedCalendar() {
        val primary = meeting("same", now + 3_600_000)
        val other = primary.copy(calendarId = "other", updatedAt = 2)
        seed(listOf(primary, other))
        assertEquals(1, CalendarStore.meetings(context).size)
        val before = Reminders.all(context).map { it.id }
        CalendarStore.select(context, setOf("primary"))
        assertEquals(listOf(primary), CalendarStore.meetings(context))
        assertEquals(before, Reminders.all(context).map { it.id })
    }
}
