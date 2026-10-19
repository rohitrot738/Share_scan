package com.rohit.share_scan

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * NSE and BSE's regular session, 9:15 to 15:30 IST on weekdays. Exchange holidays are not listed here: on a holiday
 * no stock gets a bar for the day, so scans read it as a closed day (see [NseScanner]). Free of Android types so it
 * can be unit tested.
 */
object MarketClock {
    val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val CLOSE: LocalTime = LocalTime.of(15, 30)

    /** Minutes from [OPEN] to [CLOSE]. */
    const val SESSION_MINUTES = 375

    enum class Phase {
        /** A weekday before 9:15. */
        BEFORE_OPEN,

        /** A weekday from 9:15 until 15:30: prices and volumes are live. */
        OPEN,

        /** A weekday from 15:30 on. */
        AFTER_CLOSE,

        /** Saturday or Sunday. */
        WEEKEND,
    }

    fun now(millis: Long = System.currentTimeMillis()): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(INDIA)

    fun phase(at: ZonedDateTime): Phase {
        val local = at.withZoneSameInstant(INDIA)
        if (!isWeekday(local.toLocalDate())) return Phase.WEEKEND
        val time = local.toLocalTime()
        return when {
            time < OPEN -> Phase.BEFORE_OPEN
            time < CLOSE -> Phase.OPEN
            else -> Phase.AFTER_CLOSE
        }
    }

    fun isOpen(at: ZonedDateTime): Boolean = phase(at) == Phase.OPEN

    fun isWeekday(day: LocalDate): Boolean = day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY

    /** Minutes since 9:15 IST on [at]'s day; negative before the open, past [SESSION_MINUTES] after the close. */
    fun minutesSinceOpen(at: ZonedDateTime): Double {
        val local = at.withZoneSameInstant(INDIA)
        val open = local.toLocalDate().atTime(OPEN).atZone(INDIA)
        return (local.toInstant().toEpochMilli() - open.toInstant().toEpochMilli()) / 60_000.0
    }

    /** The IST calendar day of an epoch second, as Yahoo stamps each daily bar at its session's start. */
    fun dayOf(epochSecond: Long): LocalDate = Instant.ofEpochSecond(epochSecond).atZone(INDIA).toLocalDate()
}

/**
 * How much of a normal day's volume a stock's live daily bar holds at each minute of the session, so a live session's
 * volume so far can be scaled up to a full day before it is compared with full past days.
 *
 * The points are the median share of the day's final volume traded by each time, from 5,010 sessions (60 days of
 * 5-minute bars of 89 NSE stocks, large and small), plus 1.5% of the day that the live daily bar holds from the open
 * but the 5-minute bars do not (the opening auction and first trades: on 19 Oct 2026 at 9:43 the live daily bar held a
 * median 1.15 times the 5-minute bars' sum). Volume is heaviest after the open and before the close, so a straight
 * line from 9:15 to 15:30 would overrate the morning; the closing session after 15:30 adds the last few percent.
 */
object VolumeCurve {
    /** (minutes since 9:15, share of the day's volume the live bar holds by then). */
    private val POINTS = listOf(
        0.0 to 0.015,
        15.0 to 0.068,
        20.0 to 0.089,
        30.0 to 0.126,
        45.0 to 0.173,
        60.0 to 0.215,
        90.0 to 0.287,
        120.0 to 0.353,
        150.0 to 0.415,
        180.0 to 0.476,
        210.0 to 0.535,
        240.0 to 0.591,
        270.0 to 0.648,
        300.0 to 0.708,
        330.0 to 0.780,
        345.0 to 0.830,
        360.0 to 0.927,
        375.0 to 0.966,
    )

    /**
     * The first minutes say little about the day: until [FLOOR_MINUTES] the share stays at its value there, so the
     * opening burst is not blown up into a huge day.
     */
    const val FLOOR_MINUTES = 20.0

    /** Share of a full day's volume done [minutes] after the open, between the floor's share and the close's. */
    fun shareAt(minutes: Double): Double {
        val m = minutes.coerceIn(FLOOR_MINUTES, MarketClock.SESSION_MINUTES.toDouble())
        val upper = POINTS.indexOfFirst { it.first >= m }
        if (upper <= 0) return POINTS[maxOf(upper, 0)].second
        val (m0, s0) = POINTS[upper - 1]
        val (m1, s1) = POINTS[upper]
        return s0 + (s1 - s0) * (m - m0) / (m1 - m0)
    }
}
