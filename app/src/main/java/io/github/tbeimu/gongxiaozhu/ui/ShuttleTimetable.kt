package io.github.tbeimu.gongxiaozhu.ui

import java.time.DayOfWeek
import java.time.LocalDate
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
    val operation: String? = null,
)

internal data class ShuttleDeparture(val route: ShuttleRoute, val time: LocalTime)

internal enum class ShuttleDayType(val suffix: String) {
    WORKDAY("工作"), WEEKEND("周末");
    val title: String get() = "校车时刻表（$suffix）"
    val other: ShuttleDayType get() = if (this == WORKDAY) WEEKEND else WORKDAY
}

/** Natural weekdays only; no inferred holiday/adjusted-workday calendar or live vehicle feed. */
internal fun shuttleDayType(date: LocalDate): ShuttleDayType =
    if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY)
        ShuttleDayType.WEEKEND else ShuttleDayType.WORKDAY

private val workdayShuttleRoutes = listOf(
    ShuttleRoute("morning", "早间", "中蓝校区",
        listOf("07:20", "07:30", "07:35", "07:45", "08:00", "08:15", "08:30", "08:40", "08:50",
            "09:00", "09:10", "09:20", "09:30", "09:40", "09:50", "10:00", "10:15")
            .map(LocalTime::parse),
        listOf("中蓝校区西北门", "北工大东南门", "北工大南门", "松榆东里东门（地铁14号线）", "北工大西门"), "校外运营"),
    ShuttleRoute("evening", "晚间", "月亮湖西",
        listOf("19:45", "20:00", "20:15", "20:30", "20:45", "21:00", "21:10", "21:20", "21:30",
            "21:40", "21:50", "22:00", "22:10", "22:20", "22:30")
            .map(LocalTime::parse),
        listOf("月亮湖西", "逸夫图书馆北", "第二教学楼西门", "中蓝校区"), "校内运营"),
)

private val weekendShuttleRoutes = listOf(
    ShuttleRoute("morning", "早间", "中蓝校区",
        listOf("08:00", "08:30", "09:00", "09:30", "10:00").map(LocalTime::parse),
        listOf("中蓝校区", "月亮湖", "逸夫图书馆", "北工大西门")),
    ShuttleRoute("evening", "晚间", "月亮湖",
        listOf("20:00", "20:30", "21:00", "21:30", "22:00").map(LocalTime::parse),
        listOf("月亮湖", "逸夫图书馆", "北工大西门", "中蓝校区")),
)

internal fun shuttleRoutes(type: ShuttleDayType): List<ShuttleRoute> =
    if (type == ShuttleDayType.WORKDAY) workdayShuttleRoutes else weekendShuttleRoutes

private val shuttleTimeFormat = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)
internal fun shuttleTimeLabel(time: LocalTime): String = time.format(shuttleTimeFormat)

/** The departure minute is inclusive. After the final minute, wait for the next date to reset. */
internal fun nextShuttleDeparture(moment: LocalDateTime): ShuttleDeparture? {
    val minute = moment.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
    return shuttleRoutes(shuttleDayType(moment.toLocalDate())).asSequence().flatMap { route ->
        route.departures.asSequence().filter { !it.isBefore(minute) }.map { ShuttleDeparture(route, it) }
    }.minByOrNull { it.time }
}

internal fun shuttleStatus(moment: LocalDateTime, viewedType: ShuttleDayType): String =
    if (viewedType != shuttleDayType(moment.toLocalDate())) "非今日时刻表"
    else nextShuttleDeparture(moment)?.let { "下一班预计 ${shuttleTimeLabel(it.time)} · ${it.route.origin}发车" }
        ?: "今日班次已结束"
