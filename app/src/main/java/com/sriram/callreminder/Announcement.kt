package com.sriram.callreminder

object Announcement {
    fun remaining(meetingAt: Long, now: Long): String {
        val seconds = ((meetingAt - now).coerceAtLeast(0) + 999) / 1000
        if (seconds == 0L) return "now"
        val amount = if (seconds >= 60) (seconds + 59) / 60 else seconds
        val unit = if (seconds >= 60) "minute" else "second"
        return "in $amount $unit${if (amount == 1L) "" else "s"}"
    }
    fun text(r: Reminder, now: Long): String {
        val title = r.title.trim().take(250).ifEmpty { "your meeting" }
        return "You have a call, $title, to join ${remaining(r.meetingAt, now)}."
    }
}
