package com.sriram.callreminder

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

class CallAudioTest {
    private class RouteState { @Volatile var selected = ""; @Volatile var failed = false }
    @Test fun earpieceIsDefaultSpeakerIsExplicitAndEndingRestoresAudioMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("Hardware earpiece required", audio.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE })
        val state = RouteState()
        val call = CallAudio(context, { state.selected = it }, { state.failed = true })
        try {
            instrumentation.runOnMainSync { assertTrue("Test device must expose an earpiece", call.start()) }
            waitFor { state.selected == "earpiece" || state.failed }
            assertFalse(state.failed)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, audio.communicationDevice?.type)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
            instrumentation.runOnMainSync { assertTrue(call.speaker(true)) }
            waitFor { state.selected == "speaker" || state.failed }
            assertFalse(state.failed)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
            instrumentation.runOnMainSync { assertTrue(call.speaker(false)) }
            waitFor { state.selected == "earpiece" || state.failed }
            assertFalse(state.failed)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, audio.communicationDevice?.type)
        } finally { instrumentation.runOnMainSync { call.close() } }
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
    }
    @Test fun missingEarpieceNeverFallsBackToSpeaker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue("Device without earpiece required", audio.availableCommunicationDevices.none { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE })
        val before = audio.mode
        val call = CallAudio(context, { fail("Must not select a loudspeaker as fallback") }, {})
        try { instrumentation.runOnMainSync { assertFalse(call.start()) } }
        finally { instrumentation.runOnMainSync { call.close() } }
        assertEquals(before, audio.mode)
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Audio route must settle before speech starts", condition())
    }
}
