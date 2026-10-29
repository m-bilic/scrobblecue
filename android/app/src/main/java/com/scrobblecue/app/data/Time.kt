package com.scrobblecue.app.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 213 → "3:33", 3735 → "1:02:15". */
fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val DURATION = Regex("""^(?:(\d+):)?(\d{1,3}):(\d{2})$""")

/** "3:33" or "1:02:15" → seconds; null when not a valid duration. */
fun parseDuration(text: String): Int? {
    val match = DURATION.matchEntire(text.trim()) ?: return null
    val (h, m, s) = match.destructured
    val seconds = (h.toIntOrNull() ?: 0) * 3600 + m.toInt() * 60 + s.toInt()
    return seconds.takeIf { it > 0 }
}

/** ISO-8601 without fractions, as the backend expects: 2026-10-28T19:00:00Z. */
fun isoSeconds(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago". */
fun timeAgo(then: Instant, now: Instant = Instant.now()): String {
    val minutes = Math.round(Duration.between(then, now).seconds / 60.0)
    if (minutes < 1) return "just now"
    if (minutes < 60) return "$minutes min ago"
    val hours = Math.round(minutes / 60.0)
    if (hours < 24) return "$hours h ago"
    val days = Math.round(hours / 24.0)
    return if (days == 1L) "yesterday" else "$days days ago"
}

/** The current time, replaceable in tests. */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    fun clock(): Clock = Clock.systemUTC()
}
