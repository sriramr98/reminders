package com.sriram.callreminder

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import android.content.Intent
import android.os.SystemClock

class ReminderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
    @Before fun permissions() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT allow")
    }
    @After fun cleanup() { Reminders.cancel(context) }
    @Test fun productionOffsetsAreFifteenAndTwoMinutes() {
        val meeting = 1_800_000_000_000L
        assertEquals(listOf(15 to meeting - 900_000L, 2 to meeting - 120_000L), Reminders.times(meeting))
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
    @Test fun lockedScreenCallsSurviveFirstDismissAndSecondTimesOut() {
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
                context.startService(Intent(context, RingService::class.java).setAction("dismiss").putExtra("id", pair[0].id))
            }
            waitFor { Reminders.all(context)[0].state == "dismissed" }
            waitFor { first!!.isDestroyed }
            assertEquals("scheduled", Reminders.all(context)[1].state)
            monitor = instrumentation.addMonitor(ReminderActivity::class.java.name, null, false)
            shell("input keyevent 223")
            val second = monitor.waitForActivityWithTimeout(50_000)
            assertNotNull("Second alarm should still open after dismissing the first", second)
            assertEquals("ringing", Reminders.all(context)[1].state)
            waitFor(35_000) { Reminders.all(context)[1].state == "missed" }
            assertEquals("dismissed", Reminders.all(context)[0].state)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun waitFor(timeout: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Expected reminder state before timeout", condition())
    }
}
