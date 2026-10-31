package com.sriram.callreminder

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Owns a communication route only for the answered portion of a reminder. */
class CallAudio(context: Context, private val selected: (String) -> Unit, private val failed: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var requested: AudioDeviceInfo? = null
    private var registered = false
    private var ownsMode = false
    private var ready = false
    private val routeTimeout = Runnable { failed() }
    private val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        val target = requested
        if (target != null && device?.id == target.id) confirm(device)
        else if (ready) failed() // Never continue on an unexpected loudspeaker route.
    }
    private val executor = context.mainExecutor
    fun start(): Boolean {
        // Do not take audio mode away from an existing phone/VoIP call.
        if (audio.mode != AudioManager.MODE_NORMAL) return false
        val earpiece = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            ?: return false
        audio.addOnCommunicationDeviceChangedListener(executor, listener)
        registered = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        ownsMode = true
        return request(earpiece)
    }
    fun speaker(enabled: Boolean): Boolean {
        if (!ownsMode) return false
        val type = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val device = audio.availableCommunicationDevices.firstOrNull { it.type == type } ?: return false
        return request(device)
    }
    private fun request(device: AudioDeviceInfo): Boolean {
        val previous = requested
        requested = device
        ready = false
        if (!audio.setCommunicationDevice(device)) { requested = previous; ready = previous != null; return false }
        handler.removeCallbacks(routeTimeout)
        handler.postDelayed(routeTimeout, 3000)
        audio.communicationDevice?.takeIf { it.id == device.id }?.let { confirm(it) }
        return true
    }
    private fun confirm(device: AudioDeviceInfo) {
        if (ready) return
        ready = true
        handler.removeCallbacks(routeTimeout)
        selected(if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "speaker" else "earpiece")
    }
    fun close() {
        handler.removeCallbacksAndMessages(null)
        requested = null
        ready = false
        if (registered) { audio.removeOnCommunicationDeviceChangedListener(listener); registered = false }
        if (ownsMode) {
            audio.clearCommunicationDevice()
            audio.mode = AudioManager.MODE_NORMAL
            ownsMode = false
        }
    }
}
