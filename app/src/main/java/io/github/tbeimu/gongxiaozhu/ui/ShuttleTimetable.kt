package io.github.tbeimu.gongxiaozhu.ui

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal data class ShuttleRoute(
    val id: String,
    val period: String,
    val origin: String,
    val departures: List<LocalTime>,
    val stops: List<String>,
)

internal data class ShuttleDeparture(val route: ShuttleRoute, val time: LocalTime)

/** User-supplied departure plans; no operating-day calendar or live vehicle feed. */
internal val shuttleRoutes = listOf(
    ShuttleRoute("morning", "早间", "中蓝公寓",
        listOf("07:20", "07:30", "07:35", "08:00", "08:15", "08:30", "08:45", "09:00", "09:15", "09:30", "09:45", "10:00")
            .map(LocalTime::parse),
        listOf("中蓝校区西甬路", "北工大东南门", "北工大南门", "松榆东里东门（地铁北工大西门站）", "北工大西门", "中蓝校区")),
    ShuttleRoute("evening", "晚间", "月亮湖西站",
        listOf("20:30", "20:45", "21:00", "21:10", "21:20", "21:30", "21:40", "21:50", "22:00")
            .map(LocalTime::parse),
        listOf("月亮湖西", "逸夫图书馆北", "第二教学楼西门", "中蓝校区")),
)

private val shuttleTimeFormat = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)
internal fun shuttleTimeLabel(time: LocalTime): String = time.format(shuttleTimeFormat)

/** The departure minute is inclusive. After the final minute, wait for the next date to reset. */
internal fun nextShuttleDeparture(moment: LocalDateTime): ShuttleDeparture? {
    val minute = moment.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
    return shuttleRoutes.asSequence().flatMap { route ->
        route.departures.asSequence().filter { !it.isBefore(minute) }.map { ShuttleDeparture(route, it) }
    }.minByOrNull { it.time }
}
