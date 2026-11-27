package io.github.tbeimu.gongxiaozhu.ui

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ShuttleTimetableTest {
    @Test fun suppliedWorkdayDeparturesAndStopOrderArePreservedExactly() {
        val routes = shuttleRoutes(ShuttleDayType.WORKDAY)
        assertEquals(listOf("7:20", "7:30", "7:35", "7:45", "8:00", "8:15", "8:30", "8:40", "8:50",
            "9:00", "9:10", "9:20", "9:30", "9:40", "9:50", "10:00", "10:15"), routes[0].departures.map(::shuttleTimeLabel))
        assertEquals(listOf("19:45", "20:00", "20:15", "20:30", "20:45", "21:00", "21:10", "21:20", "21:30",
            "21:40", "21:50", "22:00", "22:10", "22:20", "22:30"), routes[1].departures.map(::shuttleTimeLabel))
        assertEquals(listOf("中蓝校区西北门", "北工大东南门", "北工大南门", "松榆东里东门（地铁14号线）", "北工大西门"), routes[0].stops)
        assertEquals(listOf("月亮湖西", "逸夫图书馆北", "第二教学楼西门", "中蓝校区"), routes[1].stops)
        assertEquals(listOf("中蓝校区", "月亮湖西"), routes.map { it.origin })
        assertEquals(listOf("校外运营", "校内运营"), routes.map { it.operation })
        assertEquals(32, routes.sumOf { it.departures.size })
    }

    @Test fun suppliedWeekendDeparturesAndFourStopsArePreservedExactly() {
        val routes = shuttleRoutes(ShuttleDayType.WEEKEND)
        assertEquals(listOf("8:00", "8:30", "9:00", "9:30", "10:00"), routes[0].departures.map(::shuttleTimeLabel))
        assertEquals(listOf("20:00", "20:30", "21:00", "21:30", "22:00"), routes[1].departures.map(::shuttleTimeLabel))
        assertEquals(listOf("中蓝校区", "月亮湖", "逸夫图书馆", "北工大西门"), routes[0].stops)
        assertEquals(listOf("月亮湖", "逸夫图书馆", "北工大西门", "中蓝校区"), routes[1].stops)
        assertEquals(listOf("中蓝校区", "月亮湖"), routes.map { it.origin })
        assertTrue(routes.all { it.operation == null })
        assertEquals(10, routes.sumOf { it.departures.size })
    }

    @Test fun naturalWeekdaysSelectTheRightTableAndTitles() {
        (23..29).forEach { day ->
            assertEquals(if (day < 28) ShuttleDayType.WORKDAY else ShuttleDayType.WEEKEND,
                shuttleDayType(LocalDate.of(2026, 11, day)))
        }
        assertEquals(ShuttleDayType.WORKDAY, shuttleDayType(LocalDate.of(2026, 11, 30)))
        assertEquals("校车时刻表（工作日）", ShuttleDayType.WORKDAY.title)
        assertEquals("校车时刻表（休息日）", ShuttleDayType.WEEKEND.title)
        assertEquals(ShuttleDayType.WORKDAY, ShuttleDayType.WORKDAY.other.other)
    }

    @Test fun everyDepartureMinuteIncludesItsLastSecondAndThenMovesForward() {
        listOf(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 28)).forEach { date ->
            val all = shuttleRoutes(shuttleDayType(date)).flatMap { route -> route.departures.map { ShuttleDeparture(route, it) } }
                .sortedBy { it.time }
            all.forEachIndexed { index, departure ->
                assertEquals(departure, nextShuttleDeparture(date.atTime(departure.time).minusSeconds(1)))
                assertEquals(departure, nextShuttleDeparture(date.atTime(departure.time).plusSeconds(59)))
                assertEquals(all.getOrNull(index + 1), nextShuttleDeparture(date.atTime(departure.time).plusMinutes(1)))
            }
        }
    }

    @Test fun finalBusesEndForTodayAndMidnightChangesTheSchedule() {
        assertNull(nextShuttleDeparture(LocalDateTime.of(2026, 11, 27, 22, 31)))
        assertNull(nextShuttleDeparture(LocalDateTime.of(2026, 11, 28, 22, 1)))
        assertEquals(LocalTime.of(8, 0), nextShuttleDeparture(LocalDateTime.of(2026, 11, 28, 0, 0))?.time)
        assertEquals(LocalTime.of(8, 0), nextShuttleDeparture(LocalDateTime.of(2026, 11, 29, 0, 0))?.time)
        assertEquals(LocalTime.of(7, 20), nextShuttleDeparture(LocalDateTime.of(2026, 11, 30, 0, 0))?.time)
    }

    @Test fun otherDayTypeIsOnlyAPreviewAndDoesNotClaimAnUpcomingBus() {
        val friday = LocalDateTime.of(2026, 11, 27, 7, 21)
        assertEquals("下一班预计 7:30 · 中蓝校区发车", shuttleStatus(friday, ShuttleDayType.WORKDAY))
        assertEquals("非今日时刻表", shuttleStatus(friday, ShuttleDayType.WEEKEND))
        assertEquals("今日班次已结束", shuttleStatus(friday.withHour(23), ShuttleDayType.WORKDAY))
        assertEquals("下一班预计 20:00 · 月亮湖发车",
            shuttleStatus(LocalDateTime.of(2026, 11, 28, 10, 1), ShuttleDayType.WEEKEND))
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
