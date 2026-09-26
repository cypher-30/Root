package com.root.app.ui

import java.util.concurrent.TimeUnit

internal fun lastPracticedCaption(markedAt: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = (now - markedAt).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    if (minutes < 1) return "Last practiced just now."
    if (minutes < 60) return "Last practiced $minutes ${if (minutes == 1L) "minute" else "minutes"} ago."
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    if (hours < 24) return "Last practiced $hours ${if (hours == 1L) "hour" else "hours"} ago."
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return if (days == 1L) "Last practiced yesterday." else "Last practiced $days days ago."
}

