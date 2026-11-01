package com.sriram.callreminder

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class ReminderTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
    @Before fun permissions() {
        shell("cmd statusbar collapse")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT allow")
        CalendarStore.disconnect(context)
        Reminders.cancel(context)
    }
    @After fun cleanup() {
        Reminders.cancel(context)
        CalendarStore.disconnect(context)
        instrumentation.runOnMainSync { ReminderPresentation.visibleActivity()?.finish() }
    }
    @Test fun productionOffsetsAreFifteenAndTwoMinutes() {
        val meeting = 1_800_000_000_000L
        assertEquals(listOf(15 to meeting - 900_000L, 2 to meeting - 120_000L), Reminders.times(meeting))
    }
    @Test fun meetingTimingUsesActualTimeRemainingAndHandlesBoundaries() {
        val now = 1_800_000_000_000L
        assertEquals("Starts in 15 minutes", ReminderInfo.remaining(now + 900_000, now))
        assertEquals("Starts in 2 minutes", ReminderInfo.remaining(now + 120_000, now))
        assertEquals("Starts in 1 minute", ReminderInfo.remaining(now + 60_000, now))
        assertEquals("Starts in 59 seconds", ReminderInfo.remaining(now + 59_000, now))
        assertEquals("Starts in 1 second", ReminderInfo.remaining(now + 1, now))
        assertEquals("Starting now", ReminderInfo.remaining(now, now + 1))
    }
    @Test fun oldSpeechStateAndFieldsMigrateWithoutRestoringVoicePlayback() {
        val legacy = Reminder(1, "Review", 1000, 100, 15, false).json()
            .put("state", "speaking").put("spokenText", "Private meeting").put("audioRoute", "speaker")
        val restored = Reminder.read(legacy)
        assertEquals("dismissed", restored.state)
        assertFalse(restored.json().has("spokenText"))
        assertFalse(restored.json().has("audioRoute"))
        assertEquals(restored, Reminder.read(JSONObject(restored.json().toString())))
    }
    @Test fun dismissingFirstPreservesSecondAndRestoreDoesNotDuplicate() {
        Reminders.start(context, true)
        val original = Reminders.all(context)
        assertEquals(2, original.size)
        assertEquals(40_000L, original[1].fireAt - original[0].fireAt)
        Reminders.claim(context, original[0].id)
        ReminderActionReceiver().onReceive(context, Intent("dismiss").putExtra("id", original[0].id))
        Reminders.restore(context)
        assertEquals("dismissed", Reminders.all(context)[0].state)
        assertEquals(original[1], Reminders.all(context)[1])
    }
    @Test fun decliningFirstSuppressesRemainingAndStaleActionsDoNotChangeReplacement() {
        Reminders.start(context, true)
        val oldId = Reminders.all(context)[0].id
        Reminders.claim(context, oldId)
        ReminderActionReceiver().onReceive(context, Intent("decline").putExtra("id", oldId))
        assertTrue(Reminders.all(context).all { it.state == "skipped" })
        Reminders.restore(context)
        assertNull(Reminders.claim(context, Reminders.all(context)[1].id))
        Reminders.start(context, false)
        val replacement = Reminders.all(context)
        ReminderActionReceiver().onReceive(context, Intent("decline").putExtra("id", oldId))
        assertEquals(replacement, Reminders.all(context))
        assertFalse(replacement.any { it.id == oldId })
    }
    private fun calendarPair(url: String): List<Reminder> {
        val start = System.currentTimeMillis() + 20 * 60_000L
        Reminders.reconcile(context, listOf(CalendarMeeting("meeting", "primary", "event", "Design review", start, url, 0)))
        return Reminders.all(context)
    }
    @Test fun onlyFinalReminderOffersBrowserAndMissingOrUnsafeLinksAreHidden() {
        val pair = calendarPair("https://meet.google.com/abc-defg-hij")
        val first = pair[0].copy(state = "ringing")
        val last = pair[1].copy(state = "ringing")
        assertFalse(ReminderInfo.canOpenMeeting(context, first))
        assertTrue(ReminderInfo.canOpenMeeting(context, last))
        assertFalse(ReminderInfo.canOpenMeeting(context, last.copy(joinUrl = "")))
        assertFalse(ReminderInfo.canOpenMeeting(context, last.copy(joinUrl = "javascript:alert(1)")))
        assertFalse(ReminderInfo.canOpenMeeting(context, last.copy(state = "skipped")))
        val earlyNotification = RingService.notification(context, first, false)
        val finalNotification = RingService.notification(context, last, false)
        assertEquals(Notification.CATEGORY_ALARM, finalNotification.category)
        assertEquals(Notification.BigTextStyle::class.java.name, finalNotification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertFalse(earlyNotification.actions.any { it.title.toString() == "Open meeting in browser" })
        assertTrue(finalNotification.actions.any { it.title.toString() == "Open meeting in browser" })
        assertNotNull(finalNotification.fullScreenIntent)
        assertEquals("Meeting reminder", finalNotification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(setOf("Dismiss", "Not attending"), earlyNotification.actions.map { it.title.toString() }.toSet())
        assertNull(MeetingLinks.browserIntent(context, "intent://unsafe"))
    }
    @Test fun lateSyncWithOnlyOneFutureOffsetStillOffersBrowserAndDeclineSurvivesSync() {
        val start = System.currentTimeMillis() + 5 * 60_000L
        val meeting = CalendarMeeting("late", "primary", "event", "Late meeting", start, "https://meet.google.com/abc-defg-hij", 0)
        Reminders.reconcile(context, listOf(meeting))
        val r = Reminders.all(context).single()
        assertEquals(2, r.leadMinutes)
        assertTrue(ReminderInfo.canOpenMeeting(context, r.copy(state = "ringing")))
        Reminders.claim(context, r.id)
        ReminderActionReceiver().onReceive(context, Intent("decline").putExtra("id", r.id))
        Reminders.reconcile(context, listOf(meeting))
        assertEquals("skipped", Reminders.all(context).single().state)
    }
    @Test fun missedNotificationActionsWorkWithoutRingingService() {
        val r = calendarPair("https://meet.google.com/abc-defg-hij").last()
        Reminders.update(context, r.id, "missed")
        val notification = RingService.notification(context, r.copy(state = "missed"), false, missed = true)
        assertNull(notification.fullScreenIntent)
        assertTrue(notification.actions.any { it.title.toString() == "Open meeting in browser" })
        notification.actions.first { it.title.toString() == "Not attending" }.actionIntent.send()
        waitFor { Reminders.all(context).all { it.state == "skipped" } }
    }
    @Test fun finalNotificationBrowserActionLaunchesBrowserAndDismissesOnlyCurrentReminder() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        val pair = calendarPair("https://meet.google.com/abc-defg-hij")
        val last = pair.last()
        val browser = MeetingLinks.browserIntent(context, last.joinUrl)
        org.junit.Assume.assumeTrue("Requires an installed/default browser rather than a chooser", browser?.`package` != null)
        assertEquals(last.joinUrl, browser!!.dataString)
        assertEquals(Intent.ACTION_VIEW, browser.action)
        Reminders.update(context, last.id, "missed")
        val filter = android.content.IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }
        // Intercept external navigation so the test does not access a real meeting.
        val browserMonitor = instrumentation.addMonitor(filter, android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null), true)
        val reminderMonitor = instrumentation.addMonitor(ReminderActivity::class.java.name, null, false)
        try {
            val notification = RingService.notification(context, last.copy(state = "missed"), false, missed = true)
            notification.actions.first { it.title.toString() == "Open meeting in browser" }.actionIntent.send()
            assertNotNull(reminderMonitor.waitForActivityWithTimeout(10_000))
            waitFor { browserMonitor.hits == 1 && Reminders.all(context).last().state == "dismissed" }
            assertEquals("scheduled", Reminders.all(context).first().state)
        } finally {
            instrumentation.removeMonitor(browserMonitor)
            instrumentation.removeMonitor(reminderMonitor)
        }
    }
    @Test fun foregroundReminderShowsDetailsAndDeclineStopsBothWithoutCallAudio() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        val audio = context.getSystemService(AudioManager::class.java)
        val originalMode = audio.mode
        val monitor = instrumentation.addMonitor(ReminderActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { Reminders.start(context, true) }
            val reminder = monitor.waitForActivityWithTimeout(20_000)
            assertNotNull("Foreground app should show meeting details", reminder)
            waitFor { ReminderPresentation.visibleActivity() === reminder }
            instrumentation.runOnMainSync {
                val root = reminder!!.findViewById<View>(android.R.id.content)
                assertEquals("Your test meeting", root.findViewWithTag<TextView>("meeting_title").text.toString())
                assertNull(root.findViewWithTag<View>("open_meeting"))
                assertNotNull(root.findViewWithTag<Button>("dismiss_reminder"))
                root.findViewWithTag<Button>("decline_reminder").performClick()
            }
            waitFor { RingService.currentId == null }
            assertTrue(Reminders.all(context).all { it.state == "skipped" })
            assertEquals("Ringing must not enter communication mode", originalMode, audio.mode)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun backgroundNotificationDismissStopsOnlyCurrentAlert() {
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { Reminders.start(context, true) }
        shell("input keyevent 3")
        waitFor(20_000) { RingService.currentId == Reminders.all(context)[0].id }
        val nm = context.getSystemService(NotificationManager::class.java)
        waitFor { nm.activeNotifications.any { it.id == RingService.NOTIFICATION_ID } }
        val notification = nm.activeNotifications.first { it.id == RingService.NOTIFICATION_ID }.notification
        assertEquals(RingService.ALERT_CHANNEL, notification.channelId)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        notification.actions.first { it.title.toString() == "Dismiss" }.actionIntent.send()
        waitFor { RingService.currentId == null }
        assertEquals("dismissed", Reminders.all(context)[0].state)
        assertEquals("scheduled", Reminders.all(context)[1].state)
    }
    @Test fun lockedScreenShowsMeetingDetailsAndTimeoutPreservesFinalReminder() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        val monitor = instrumentation.addMonitor(ReminderActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { Reminders.start(context, true) }
            shell("input keyevent 223")
            val reminder = monitor.waitForActivityWithTimeout(20_000)
            assertNotNull("Alarm should show meeting details while locked", reminder)
            waitFor(35_000) { Reminders.all(context)[0].state == "missed" }
            assertEquals("scheduled", Reminders.all(context)[1].state)
            instrumentation.runOnMainSync { reminder!!.finish() }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun waitFor(timeout: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Expected reminder state before timeout", condition())
    }
}
