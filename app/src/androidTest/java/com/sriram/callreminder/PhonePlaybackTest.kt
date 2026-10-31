package com.sriram.callreminder

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Physical-device test: starts privately, then switches to Speaker explicitly. */
class PhonePlaybackTest {
    @Test fun spokenReminderUsesCallAudioAndRestoresMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("Physical earpiece required", audio.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE })
        val descriptor = instrumentation.uiAutomation.executeShellCommand("input keyevent 224")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        try {
            instrumentation.runOnMainSync {
                Reminders.start(context, true)
                val first = Reminders.all(context).first()
                AlarmReceiver().onReceive(context, Intent().putExtra("id", first.id))
            }
            val id = Reminders.all(context).first().id
            waitFor { context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == 100 } }
            instrumentation.runOnMainSync { context.startService(Intent(context, RingService::class.java).setAction("answer").putExtra("id", id)) }
            waitFor(20_000) { Reminders.all(context).first().state in setOf("speaking", "speech unavailable", "audio unavailable") }
            assertEquals("speaking", Reminders.all(context).first().state)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, audio.communicationDevice?.type)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
            Thread.sleep(700)
            instrumentation.runOnMainSync { context.startService(Intent(context, RingService::class.java).setAction("toggle_speaker").putExtra("id", id)) }
            waitFor { audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && Reminders.all(context).first().audioRoute == "speaker" }
            waitFor(20_000) { Reminders.all(context).first().state in setOf("answered", "speech unavailable", "audio unavailable") }
            assertEquals("answered", Reminders.all(context).first().state)
            assertEquals("speaker", Reminders.all(context).first().audioRoute)
            waitFor { audio.mode == AudioManager.MODE_NORMAL }
        } finally { instrumentation.runOnMainSync { Reminders.cancel(context) } }
    }
    private fun waitFor(timeout: Long = 5000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Expected playback state before timeout", condition())
    }
}
