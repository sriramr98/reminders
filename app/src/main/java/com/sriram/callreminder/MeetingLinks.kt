package com.sriram.callreminder

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object MeetingLinks {
    fun open(activity: Activity, url: String, beforeOpen: () -> Unit = {}) {
        val safe = CalendarEvents.safeUrl(url)
        if (safe.isEmpty()) return
        fun launch() {
            if (activity.isDestroyed || activity.isFinishing) return
            try { beforeOpen(); activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))) }
            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(activity, "Install a browser or meeting app to open this link.", Toast.LENGTH_LONG).show() }
        }
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { launch() }
        }) else launch()
    }
}
