package com.neststudio.pocketshell.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Short "last activity" text beside a session's name: as little as tells it apart from now. */
object SessionTime {
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val MD_HM = DateTimeFormatter.ofPattern("M/d HH:mm")
    private val YMD = DateTimeFormatter.ofPattern("yyyy/M/d")

    /** Today "21:25", yesterday "어제 21:25", this year "11/16 22:56", earlier "2025/3/2". */
    fun short(at: Instant, now: Instant, zone: ZoneId): String {
        val t = at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val day = t.toLocalDate()
        return when {
            day == today -> HM.format(t)
            day == today.minusDays(1) -> "어제 " + HM.format(t)
            day.year == today.year -> MD_HM.format(t)
            else -> YMD.format(t)
        }
    }
}
