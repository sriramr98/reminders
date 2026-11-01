package com.sriram.callreminder

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

object MeetingLinks {
    /** Restrict the target to browser packages, so verified meeting-app links cannot intercept it. */
    internal fun browserIntent(c: Context, url: String): Intent? {
        val safe = CalendarEvents.safeUrl(url)
        if (safe.isEmpty()) return null
        val pm = c.packageManager
        val packages = pm.queryIntentActivities(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), 0)
            .map { it.activityInfo.packageName }.distinct()
        val candidates = packages.mapNotNull { name ->
            Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(name)
                .takeIf { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
        }
        if (candidates.isEmpty()) return null
        // Resolve an unrelated HTTPS URL to identify the default browser, never a meeting deep-link handler.
        val defaultPackage = pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        candidates.firstOrNull { it.`package` == defaultPackage }?.let { return it }
        return if (candidates.size == 1) candidates.first() else Intent.createChooser(candidates.first(), "Open meeting in browser")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, candidates.drop(1).toTypedArray())
    }
    fun open(activity: Activity, url: String, allowed: () -> Boolean = { true }, onOpened: () -> Unit = {}) {
        if (CalendarEvents.safeUrl(url).isEmpty()) return
        fun launch() {
            if (activity.isDestroyed || activity.isFinishing || !allowed()) return
            val intent = browserIntent(activity, url)
            if (intent == null) {
                Toast.makeText(activity, "Install or enable a browser to open this meeting.", Toast.LENGTH_LONG).show()
                return
            }
            try { activity.startActivity(intent); onOpened() }
            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(activity, "Could not open the browser. Try again or check your browser settings.", Toast.LENGTH_LONG).show() }
            catch (_: SecurityException) { Toast.makeText(activity, "Android blocked opening the browser. Check your browser settings.", Toast.LENGTH_LONG).show() }
        }
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { launch() }
        }) else launch()
    }
}
