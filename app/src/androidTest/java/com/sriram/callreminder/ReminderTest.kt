package com.sriram.callreminder

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.app.Notification
import android.app.NotificationManager

class ReminderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
    @Before fun permissions() {
        shell("cmd statusbar collapse")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT allow")
    }
    @After fun cleanup() { Reminders.cancel(context) }
    @Test fun productionOffsetsAreFifteenAndTwoMinutes() {
        val meeting = 1_800_000_000_000L
        assertEquals(listOf(15 to meeting - 900_000L, 2 to meeting - 120_000L), Reminders.times(meeting))
    }
    @Test fun announcementUsesActualTimeRemainingAndHandlesBoundaries() {
        val now = 1_800_000_000_000L
        val r = Reminder(1, "Design review", now + 900_000, now, 15, false)
        assertEquals("You have a call, Design review, to join in 15 minutes.", Announcement.text(r, now))
        assertEquals("in 2 minutes", Announcement.remaining(now + 120_000, now))
        assertEquals("in 1 minute", Announcement.remaining(now + 60_000, now))
        assertEquals("in 59 seconds", Announcement.remaining(now + 59_000, now))
        assertEquals("in 1 second", Announcement.remaining(now + 1, now))
        assertEquals("You have a call, Design review, to join now.", Announcement.text(r, now + 900_001))
    }
    @Test fun dismissingFirstPreservesSecondAndRestoreDoesNotDuplicate() {
        Reminders.start(context, true)
        val original = Reminders.all(context)
        assertEquals(2, original.size)
        assertEquals(40_000L, original[1].fireAt - original[0].fireAt)
        Reminders.update(context, original[0].id, "dismissed")
        Reminders.restore(context)
        val restored = Reminders.all(context)
        assertEquals("dismissed", restored[0].state)
        assertEquals("scheduled", restored[1].state)
        assertEquals(original[1], restored[1])
    }
    @Test fun replacementUsesDifferentIdsAndOldDismissCannotMutateNewMeeting() {
        Reminders.start(context, true)
        val oldId = Reminders.all(context)[0].id
        Reminders.start(context, false)
        val replacement = Reminders.all(context)
        Reminders.update(context, oldId, "dismissed")
        assertEquals(replacement, Reminders.all(context))
        assertFalse(replacement.any { it.id == oldId })
        Reminders.cancel(context)
        assertTrue(Reminders.all(context).isEmpty())
    }
    @Test fun lockedScreenSwipeUsesPrivateAudioAndSecondTimesOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var monitor = instrumentation.addMonitor(ReminderActivity::class.java.name, null, false)
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { Reminders.start(context, true) }
            shell("input keyevent 223")
            val first = monitor.waitForActivityWithTimeout(20_000)
            assertNotNull("First alarm should show its screen while locked", first)
            val pair = Reminders.all(context)
            assertEquals("ringing", pair[0].state)
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                val swipe = first!!.findViewById<android.view.View>(android.R.id.content).findViewWithTag<SwipeAnswerView>("answer_swipe")
                swipe(swipe, .25f, false)
            }
            assertEquals("A partial swipe must not answer", "ringing", Reminders.all(context)[0].state)
            Thread.sleep(300) // Let the cancelled swipe return to its starting point.
            instrumentation.runOnMainSync {
                val swipe = first!!.findViewById<android.view.View>(android.R.id.content).findViewWithTag<SwipeAnswerView>("answer_swipe")
                swipe(swipe, 1f, false)
            }
            waitFor(25_000) { Reminders.all(context)[0].state in setOf("answered", "speech unavailable", "audio unavailable") }
            val privateAudio = context.getSystemService(android.media.AudioManager::class.java).availableCommunicationDevices
                .any { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val expected = if (privateAudio) "answered" else "audio unavailable"
            assertEquals("Use earpiece or fail without loudspeaker fallback", expected, Reminders.all(context)[0].state)
            if (privateAudio) assertEquals("earpiece", Reminders.all(context)[0].audioRoute)
            else instrumentation.runOnMainSync { first!!.finish() }
            assertTrue(Reminders.all(context)[0].spokenText.startsWith("You have a call, Your test meeting, to join in "))
            waitFor { first!!.isDestroyed }
            assertEquals("scheduled", Reminders.all(context)[1].state)
            monitor = instrumentation.addMonitor(ReminderActivity::class.java.name, null, false)
            shell("input keyevent 223")
            val second = monitor.waitForActivityWithTimeout(50_000)
            assertNotNull("Second alarm should still open after dismissing the first", second)
            assertEquals("ringing", Reminders.all(context)[1].state)
            waitFor(35_000) { Reminders.all(context)[1].state == "missed" }
            assertEquals(expected, Reminders.all(context)[0].state)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun swipeCancellationAndAccessibilityActivation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var answers = 0
            val view = SwipeAnswerView(context).apply { layout(0, 0, 400, 76); setOnClickListener { answers++ } }
            swipe(view, 1f, true)
            assertEquals(0, answers)
            view.performClick() // Accessibility activation must work without a physical swipe.
            view.performClick()
            assertEquals("Answer callback fires once", 1, answers)
        }
    }
    @Test fun unlockedAppOpensSwipeScreenAutomatically() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        val monitor = instrumentation.addMonitor(ReminderActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { Reminders.start(context, true) }
            val call = monitor.waitForActivityWithTimeout(20_000)
            assertNotNull("Unlocked app must open its call screen without tapping a notification", call)
            waitFor { CallPresentation.visibleActivity() === call }
            instrumentation.waitForIdleSync()
            Thread.sleep(250) // Capture the rendered frame, not just the activity-created event.
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 100 }.notification
            assertEquals("meeting_calls_visible", notification.channelId)
            shell("screencap -p /sdcard/Download/call-reminder-open.png")
            instrumentation.runOnMainSync {
                val decline = call!!.findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.widget.Button>("decline_call")
                decline.performClick()
            }
            waitFor { Reminders.all(context)[0].state == "dismissed" }
            assertEquals("scheduled", Reminders.all(context)[1].state)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun unlockedBackgroundOffersAnswerAndDeclineAndAnswerOpensCall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { Reminders.start(context, true) }
        shell("input keyevent 3") // Leave the device unlocked on the launcher.
        waitFor(20_000) { Reminders.all(context)[0].state == "ringing" }
        waitFor { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 100 } }
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 100 }.notification
        assertEquals("meeting_alarms", notification.channelId)
        assertEquals(Notification.CallStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(notification.actions.any { it.title.toString().equals("Answer", true) })
        assertTrue(notification.actions.any { it.title.toString().equals("Decline", true) })
        val monitor = instrumentation.addMonitor(ReminderActivity::class.java.name, null, false)
        try {
            shell("cmd statusbar expand-notifications")
            Thread.sleep(1000)
            shell("screencap -p /sdcard/Download/call-reminder-background.png")
            // System UI test fixture: Pixel 9a API 36 at its default resolution/font scale.
            // Tap the real green Answer control observed in the captured notification.
            val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
            assertEquals(1080, screenshot.width)
            assertEquals(2424, screenshot.height)
            screenshot.recycle()
            shell("input tap 770 865")
            assertNotNull("Notification Answer must open the call screen directly", monitor.waitForActivityWithTimeout(10_000))
            waitFor { Reminders.all(context)[0].state in setOf("answering", "speaking", "answered", "audio unavailable") }
            assertEquals("scheduled", Reminders.all(context)[1].state)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun swipe(view: SwipeAnswerView, fraction: Float, cancel: Boolean) {
        val start = view.height / 2f
        val at = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to start,
            MotionEvent.ACTION_MOVE to start + (view.width - view.height) * fraction,
            (if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP) to start + (view.width - view.height) * fraction
        ).forEachIndexed { index, (action, x) ->
            val event = MotionEvent.obtain(at, at + index * 50, action, x, view.height / 2f, 0)
            view.dispatchTouchEvent(event); event.recycle()
        }
    }
    private fun waitFor(timeout: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Expected reminder state before timeout", condition())
    }
}
