package com.sriram.callreminder

import android.content.Context
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

class CalendarRefresh(c: Context, parameters: WorkerParameters) : Worker(c, parameters) {
    override fun doWork(): Result {
        val state = CalendarStore.session(applicationContext)
        if (state.account.isEmpty()) return Result.success()
        var token = ""
        return try {
            val result = Tasks.await(GoogleCalendarAuth.client(applicationContext).authorize(GoogleCalendarAuth.request(state.account)), 30, TimeUnit.SECONDS)
            if (result.hasResolution()) {
                CalendarStore.failure(applicationContext, state, "Calendar access needs your attention. Open Calendar and tap Reconnect.")
                Result.success()
            } else {
                token = GoogleCalendarAuth.token(result)
                CalendarSync.refresh(applicationContext, state, CalendarApi(token))
                Result.success()
            }
        } catch (e: Exception) {
            if (e is CalendarHttpException && e.status == 401 && token.isNotEmpty()) GoogleCalendarAuth.clear(applicationContext, token)
            CalendarStore.failure(applicationContext, state, GoogleCalendarAuth.message(e))
            if (e is java.io.IOException && e !is CalendarHttpException) Result.retry() else Result.success()
        }
    }
    companion object {
        private const val NAME = "google-calendar-refresh"
        fun schedule(c: Context) {
            if (CalendarStore.session(c).account.isEmpty()) return
            val job = PeriodicWorkRequest.Builder(CalendarRefresh::class.java, 15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, job)
        }
        fun cancel(c: Context) {
            WorkManager.getInstance(c).cancelUniqueWork(NAME)
            WorkManager.getInstance(c).cancelUniqueWork("$NAME-now")
        }
        fun refreshSoon(c: Context) {
            if (!CalendarStore.shouldRefresh(c)) return
            WorkManager.getInstance(c).enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(CalendarRefresh::class.java)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
