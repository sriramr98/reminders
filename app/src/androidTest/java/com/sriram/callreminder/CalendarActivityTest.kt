package com.sriram.callreminder

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CalendarActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun launch(): Activity {
        val monitor = instrumentation.addMonitor(CalendarActivity::class.java.name, null, false)
        try {
            context.startActivity(Intent(context, CalendarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = monitor.waitForActivityWithTimeout(10_000)!!
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (ReminderPresentation.visibleActivity() !== activity && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertSame(activity, ReminderPresentation.visibleActivity())
            instrumentation.waitForIdleSync(); Thread.sleep(250)
            return activity
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun nodes(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { nodes(root.getChildAt(it)) } else emptyList()
    private fun capture(name: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    @Test fun disconnectedScreenHasGoogleConnectAction() {
        CalendarStore.disconnect(context)
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                val text = nodes(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                assertTrue(text.any { it.text.toString() == "Connect Google Calendar" && it.isClickable })
            }
            capture("call-reminder-calendar-connect")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun offlineCacheRendersMeetingsAndSelectedCalendars() {
        CalendarStore.disconnect(context)
        CalendarStore.connect(context, "calendar-fixture@example.test")
        val now = System.currentTimeMillis()
        CalendarStore.apply(context, CalendarStore.session(context),
            listOf(CalendarInfo("work", "Work meetings", true), CalendarInfo("personal", "Personal", false)), setOf("work"),
            listOf(CalendarMeeting("fixture", "work", "fixture", "Design review", now + 3_600_000, "https://meet.google.com/abc-defg-hij", now)), now)
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                val views = nodes(activity.findViewById(android.R.id.content))
                val boxes = views.filterIsInstance<CheckBox>()
                assertEquals(2, boxes.size)
                assertEquals(1, boxes.count { it.isChecked })
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Design review" })
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Open meeting in browser" })
            }
            capture("call-reminder-calendar-cached")
        } finally {
            instrumentation.runOnMainSync { CalendarStore.disconnect(context); Reminders.cancel(context); activity.finish() }
        }
    }
}
