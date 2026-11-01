package com.sriram.callreminder

/** Display-only meeting timing. No speech engine or communication audio is used. */
object ReminderInfo {
    fun remaining(meetingAt: Long, now: Long): String {
        val seconds = ((meetingAt - now).coerceAtLeast(0) + 999) / 1000
        if (seconds == 0L) return "Starting now"
        val amount = if (seconds >= 60) (seconds + 59) / 60 else seconds
        val unit = if (seconds >= 60) "minute" else "second"
        return "Starts in $amount $unit${if (amount == 1L) "" else "s"}"
    }
    fun canOpenMeeting(c: android.content.Context, r: Reminder) =
        r.state in setOf("ringing", "missed", "dismissed") && Reminders.isFinal(c, r) && CalendarEvents.safeUrl(r.joinUrl).isNotEmpty()
    fun scheduleSummary(c: android.content.Context, r: Reminder): String {
        if (Reminders.isFinal(c, r)) return "Final reminder for this meeting"
        val next = Reminders.nextScheduled(c, r)
        return if (next != null) "Next reminder at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(next.fireAt))}"
            else "No more reminders are scheduled"
    }
}
