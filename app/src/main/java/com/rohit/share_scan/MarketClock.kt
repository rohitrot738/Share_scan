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
 * How much of a normal day's volume has traded by each minute of the session, so a live session's volume so far can
 * be scaled up to a full day before it is compared with full past days. The points are the median share of the day's
 * volume done by each time, measured from 60 sessions of 5-minute bars of 42 NSE stocks, large and small. Volume is
 * heaviest after the open and before the close, so a straight line from 9:15 to 15:30 would overrate the morning.
 */
object VolumeCurve {
    /** (minutes since 9:15, share of the day's volume done by then). */
    private val POINTS = listOf(
        0.0 to 0.0,
        10.0 to 0.031,
        15.0 to 0.054,
        30.0 to 0.113,
        45.0 to 0.161,
        60.0 to 0.203,
        90.0 to 0.275,
        120.0 to 0.345,
        150.0 to 0.414,
        180.0 to 0.482,
        210.0 to 0.547,
        240.0 to 0.608,
        270.0 to 0.670,
        300.0 to 0.735,
        330.0 to 0.813,
        345.0 to 0.869,
        360.0 to 0.983,
        375.0 to 1.0,
    )

    /**
     * The first minutes say little about the day: until [FLOOR_MINUTES] the share stays at its value there, so a few
     * early trades are not blown up into a huge day.
     */
    const val FLOOR_MINUTES = 15.0

    /** Share of a full day's volume done [minutes] after the open, between the floor's share and 1. */
    fun shareAt(minutes: Double): Double {
        val m = minutes.coerceIn(FLOOR_MINUTES, MarketClock.SESSION_MINUTES.toDouble())
        val upper = POINTS.indexOfFirst { it.first >= m }
        if (upper <= 0) return POINTS[maxOf(upper, 0)].second
        val (m0, s0) = POINTS[upper - 1]
        val (m1, s1) = POINTS[upper]
        return s0 + (s1 - s0) * (m - m0) / (m1 - m0)
    }
}
