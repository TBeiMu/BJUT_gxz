package io.github.tbeimu.gongxiaozhu.ui

import java.time.LocalDateTime
import java.time.LocalTime
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ShuttleTimetableTest {
    @Test fun suppliedDeparturesAndStopOrderArePreservedExactly() {
        assertEquals(listOf("7:20", "7:30", "7:35", "8:00", "8:15", "8:30", "8:45", "9:00", "9:15", "9:30", "9:45", "10:00"),
            shuttleRoutes[0].departures.map(::shuttleTimeLabel))
        assertEquals(listOf("20:30", "20:45", "21:00", "21:10", "21:20", "21:30", "21:40", "21:50", "22:00"),
            shuttleRoutes[1].departures.map(::shuttleTimeLabel))
        assertEquals(listOf("中蓝校区西甬路", "北工大东南门", "北工大南门", "松榆东里东门（地铁北工大西门站）", "北工大西门", "中蓝校区"), shuttleRoutes[0].stops)
        assertEquals(listOf("月亮湖西", "逸夫图书馆北", "第二教学楼西门", "中蓝校区"), shuttleRoutes[1].stops)
        assertEquals(21, shuttleRoutes.sumOf { it.departures.size })
    }

    @Test fun nextDepartureIsGlobalAndIncludesTheWholeDepartureMinute() {
        val cases = mapOf("07:19" to "07:20", "07:20:59" to "07:20", "07:21" to "07:30", "07:35" to "07:35",
            "09:46" to "10:00", "10:00:59" to "10:00", "10:01" to "20:30", "20:31" to "20:45",
            "21:55" to "22:00", "22:00:59" to "22:00")
        cases.forEach { (now, expected) ->
            val next = nextShuttleDeparture(LocalDateTime.of(2026, 11, 17, 0, 0).with(LocalTime.parse(now)))!!
            assertEquals(now, LocalTime.parse(expected), next.time)
            assertEquals(if (next.time.hour < 12) "morning" else "evening", next.route.id)
        }
    }

    @Test fun lastDepartureEndsUntilMidnightWithoutPredictingOperatingDays() {
        assertNull(nextShuttleDeparture(LocalDateTime.of(2026, 11, 17, 22, 1)))
        assertNull(nextShuttleDeparture(LocalDateTime.of(2026, 11, 17, 23, 59, 59)))
        for (day in listOf(18, 21, 22)) {
            assertEquals(LocalTime.of(7, 20), nextShuttleDeparture(LocalDateTime.of(2026, 11, day, 0, 0))?.time)
        }
    }

    @Test fun shanghaiClockIgnoresTheDeviceTimeZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val before = java.time.Instant.now().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime()
            val actual = shanghaiNow()
            val after = java.time.Instant.now().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime()
            assertFalse(actual.isBefore(before)); assertFalse(actual.isAfter(after))
        } finally { TimeZone.setDefault(original) }
    }
}
