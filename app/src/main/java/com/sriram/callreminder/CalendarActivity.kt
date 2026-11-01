package com.sriram.callreminder

import android.accounts.AccountManager
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.Toast
import com.google.android.gms.auth.api.identity.AuthorizationResult
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class CalendarActivity : BaseActivity() {
    private var busy = false
    private var message = ""
    private var pendingAccount = ""
    private var operationGeneration = 0
    private val changes = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { render() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pendingAccount = state?.getString("pendingAccount").orEmpty()
        operationGeneration = state?.getInt("generation") ?: CalendarStore.session(this).generation
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(changes, android.content.IntentFilter("com.sriram.callreminder.STATE"), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(changes, android.content.IntentFilter("com.sriram.callreminder.STATE"))
        render()
    }
    override fun onResume() { super.onResume(); render() }
    override fun onStop() {
        if (busy && message == "Connecting to Google…") {
            busy = false
            message = "Connection paused. Tap Connect or Sync to try again."
        }
        super.onStop()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("pendingAccount", pendingAccount); out.putInt("generation", operationGeneration)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { unregisterReceiver(changes); super.onDestroy() }
    private fun chooseAccount() {
        operationGeneration = CalendarStore.session(this).generation
        try {
            startActivityForResult(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null), 201)
        } catch (_: Exception) { message = "Add a Google account in Android settings and install/update Google Play services."; render() }
    }
    private fun authorize() {
        if (busy) return
        pendingAccount = pendingAccount.ifEmpty { CalendarStore.session(this).account }
        if (pendingAccount.isEmpty()) { chooseAccount(); return }
        operationGeneration = CalendarStore.session(this).generation
        busy = true; message = "Connecting to Google…"; render()
        GoogleCalendarAuth.client(this).authorize(GoogleCalendarAuth.request(pendingAccount))
            .addOnSuccessListener(this) { result ->
                if (result.hasResolution()) {
                    busy = false
                    try { startIntentSenderForResult(result.pendingIntent!!.intentSender, 202, null, 0, 0, 0) }
                    catch (_: Exception) { message = "Google consent could not open. Please try again."; render() }
                } else accept(result)
            }.addOnFailureListener(this) { fail(it) }
    }
    @Deprecated("Android activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(201, 202)) return
        if (resultCode != RESULT_OK || data == null) { busy = false; message = "Connection cancelled. Your existing schedule is unchanged."; render(); return }
        if (requestCode == 201) {
            pendingAccount = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty()
            authorize()
        } else try { accept(GoogleCalendarAuth.client(this).getAuthorizationResultFromIntent(data)) } catch (e: Exception) { fail(e) }
    }
    private fun accept(result: AuthorizationResult) {
        if (CalendarStore.session(this).generation != operationGeneration) { busy = false; render(); return }
        try {
            val token = GoogleCalendarAuth.token(result)
            CalendarStore.connect(this, pendingAccount)
            CalendarRefresh.schedule(this)
            val expected = CalendarStore.session(this)
            busy = true; message = "Syncing the next seven days…"; render()
            val app = applicationContext
            executor.execute {
                try {
                    val applied = CalendarSync.refresh(app, expected, CalendarApi(token))
                    runOnUiThread {
                        if (!isDestroyed) {
                            busy = false
                            message = if (applied) "Calendar is up to date."
                                else if (CalendarStore.session(app).account.isEmpty()) "Disconnected."
                                else "Calendar selection changed; sync again to refresh."
                            render()
                        }
                    }
                } catch (e: Exception) {
                    if (e is CalendarHttpException && e.status == 401) GoogleCalendarAuth.clear(app, token)
                    CalendarStore.failure(app, expected, GoogleCalendarAuth.message(e))
                    runOnUiThread {
                        if (!isDestroyed) {
                            if (CalendarStore.current(app, expected)) fail(e)
                            else { busy = false; message = ""; render() }
                        }
                    }
                }
            }
        } catch (e: Exception) { fail(e) }
    }
    private fun fail(e: Exception) { busy = false; message = GoogleCalendarAuth.message(e); render() }
    private fun disconnect() {
        val account = CalendarStore.session(this).account
        CalendarStore.disconnect(this)
        busy = false; pendingAccount = ""; message = "Disconnected. Calendar meetings and their reminders were removed from this phone."; render()
        if (account.isNotEmpty()) GoogleCalendarAuth.revoke(applicationContext, account).addOnFailureListener {
            runOnUiThread { if (!isDestroyed) Toast.makeText(this, "Disconnected locally. To revoke Google access too, remove Call Reminder in your Google Account connections.", Toast.LENGTH_LONG).show() }
        }
    }
    private fun render() {
        if (isDestroyed) return
        val session = CalendarStore.session(this)
        with(column()) {
            text("GOOGLE CALENDAR", 12f, true)
            text("Your meetings,\nready to ring.", 30f)
            text("Read-only access. Choose calendars and receive ringing reminders 15 and 2 minutes before timed meetings. Cancelled, declined, and all-day events are excluded.", 15f)
            if (message.isNotEmpty()) text(message, 15f, true)
            if (session.account.isEmpty()) {
                button(if (busy) "Connecting…" else "Connect Google Calendar", true) { chooseAccount() }.isEnabled = !busy
                text("Your Google Calendar stays read-only. Disconnect at any time to remove synced meetings and their reminders from this phone.", 14f)
            } else {
                text(session.account, 17f, true)
                val last = CalendarStore.lastSync(this@CalendarActivity)
                text(if (last == 0L) "No successful sync yet." else "Last synced ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}", 14f)
                val error = CalendarStore.error(this@CalendarActivity)
                if (error.isNotEmpty()) text(error, 14f)
                button(if (busy) "Syncing…" else if (error.isNotEmpty()) "Reconnect / sync now" else "Sync now", true) {
                    pendingAccount = session.account; authorize()
                }.isEnabled = !busy
                text("Calendars", 22f, true)
                val boxes = CalendarStore.calendars(this@CalendarActivity).map { calendar ->
                    CheckBox(this@CalendarActivity).apply {
                        text = if (calendar.primary) getString(R.string.primary_calendar, calendar.title) else calendar.title
                        setTextColor(white); textSize = 16f; minHeight = dp(48)
                        isChecked = calendar.id in session.selected; isEnabled = !busy
                        addView(this)
                    } to calendar.id
                }
                if (boxes.isNotEmpty()) button("Apply calendar selection") {
                    CalendarStore.select(this@CalendarActivity, boxes.filter { it.first.isChecked }.map { it.second }.toSet())
                    pendingAccount = session.account; authorize()
                }.isEnabled = !busy
                if (!Reminders.canSchedule(this@CalendarActivity)) text("Meetings can sync, but precise alarms need permission on the home screen.", 14f)
                text("Upcoming · next 7 days", 22f, true)
                val meetings = CalendarStore.meetings(this@CalendarActivity).filter { it.startAt > System.currentTimeMillis() }
                if (meetings.isEmpty()) text(if (session.selected.isEmpty()) "Choose at least one calendar to enable meeting reminders." else "No upcoming timed meetings in the last successful sync.", 15f)
                meetings.take(30).forEach { meeting ->
                    text(meeting.title, 18f)
                    text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(meeting.startAt)), 14f, true)
                    if (meeting.joinUrl.isNotEmpty()) button("Open meeting in browser") { MeetingLinks.open(this@CalendarActivity, meeting.joinUrl) }
                }
                text("Background refresh runs roughly every 15 minutes when Android and network access allow it. Last-minute calendar edits may arrive late; saved alarms work offline.", 14f)
                button("Disconnect Google Calendar") {
                    AlertDialog.Builder(this@CalendarActivity).setTitle("Disconnect Calendar?")
                        .setMessage("Remove synced meetings and their reminders from this phone, and revoke this app’s Google Calendar access?")
                        .setNegativeButton("Keep connected", null).setPositiveButton("Disconnect") { _, _ -> disconnect() }.show()
                }
            }
            button("Back") { finish() }
        }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
