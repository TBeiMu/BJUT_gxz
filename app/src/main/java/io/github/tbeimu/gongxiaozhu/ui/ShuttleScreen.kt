package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

/** Header and timetable consume one clock snapshot and one restorable, date-bound selection. */
@Composable
internal fun ShuttleTimetablePage(onBack: () -> Unit, modifier: Modifier = Modifier,
    nowProvider: () -> LocalDateTime = ::shanghaiNow, entryRequest: Long = 0L) {
    val moment by rememberForegroundMoment(nowProvider)
    val date = moment.toLocalDate().toString()
    val today = shuttleDayType(moment.toLocalDate())
    var selectedDate by rememberSaveable { mutableStateOf(date) }
    var selectedName by rememberSaveable { mutableStateOf(today.name) }
    var selectedEntry by rememberSaveable { mutableLongStateOf(entryRequest) }
    val selected = if (selectedDate == date && selectedEntry == entryRequest) ShuttleDayType.valueOf(selectedName) else today
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(date, entryRequest) {
        if (selectedDate != date || selectedEntry != entryRequest) {
            val resetScroll = selectedEntry != entryRequest || selectedName != today.name
            selectedDate = date
            selectedName = today.name
            selectedEntry = entryRequest
            if (resetScroll) scroll.scrollTo(0)
        }
    }
    Column(modifier.fillMaxSize().testTag("shuttle_page")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundIconButton(onBack, Modifier.size(48.dp).semantics { contentDescription = "返回" }) {
                LineGlyph(AppGlyph.BACK, Modifier.size(20.dp))
            }
            Text(selected.title, Modifier.weight(1f).testTag("shuttle_title"),
                style = MaterialTheme.typography.titleLarge, color = gxz.ink)
            RoundIconButton(onClick = {
                selectedDate = date
                selectedName = selected.other.name
                scope.launch { scroll.scrollTo(0) }
            }, modifier = Modifier.size(48.dp).testTag("shuttle_switch").semantics {
                contentDescription = "切换为${selected.other.suffix}时刻表"
                stateDescription = "当前${selected.suffix}时刻表"
            }) { LineGlyph(AppGlyph.SWAP, Modifier.size(21.dp)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("shuttle_scroll")
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            ShuttleTimetableContent(moment, selected)
        }
    }
}

@Composable
internal fun ShuttleTimetableContent(moment: LocalDateTime, selected: ShuttleDayType) {
    val routes = shuttleRoutes(selected)
    val isToday = selected == shuttleDayType(moment.toLocalDate())
    val next = if (isToday) nextShuttleDeparture(moment) else null
    val minute = moment.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
    val palette = gxz
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val timeStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp,
            letterSpacing = 0.sp, fontFeatureSettings = "tnum")
        val badgeStyle = MaterialTheme.typography.labelSmall
        val cellWidth = with(density) { ((maxWidth - 12.dp) / 2 - 20.dp).roundToPx().coerceAtLeast(1) }
        val timeLayouts = routes.flatMap { it.departures }.map { measurer.measure(shuttleTimeLabel(it), timeStyle, maxLines = 1) }
        val badgeLayout = measurer.measure("末车", badgeStyle, maxLines = 1)
        val badgeBelow = timeLayouts.maxOf { it.size.width } + 2 * (badgeLayout.size.width + with(density) { 6.dp.roundToPx() }) > cellWidth
        val cardHeight = maxOf(40.dp, with(density) { timeLayouts.maxOf { it.size.height }.toDp() } + 16.dp)
        val badgeHeight = with(density) { badgeLayout.size.height.toDp() }
        Column(Modifier.fillMaxWidth().testTag("shuttle_timetable"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(shuttleStatus(moment, selected), Modifier.padding(horizontal = 4.dp).testTag("shuttle_status"),
                style = MaterialTheme.typography.bodyMedium, color = if (next != null) palette.accent else palette.inkSoft)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                routes.forEach { route ->
                    Column(Modifier.weight(1f).testTag("shuttle_column_${route.id}").padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(route.period, style = MaterialTheme.typography.labelMedium, color = palette.inkSoft)
                        Text(route.origin, style = MaterialTheme.typography.titleMedium, color = palette.ink, textAlign = TextAlign.Center)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(routes.maxOf { it.departures.size }) { index ->
                    val reserveBadgeLine = badgeBelow && routes.any { it.departures.lastIndex == index }
                    val rowHeight = cardHeight + if (reserveBadgeLine) badgeHeight + 4.dp else 0.dp
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        routes.forEach { route ->
                            val time = route.departures.getOrNull(index)
                            if (time == null) Spacer(Modifier.weight(1f).height(rowHeight))
                            else ShuttleTimeCard(route, time, index, next?.route == route && next.time == time,
                                isToday && time.isBefore(minute), timeStyle, rowHeight, reserveBadgeLine, badgeHeight, Modifier.weight(1f))
                        }
                    }
                }
            }
            routes.forEach { route ->
                GlassCard(Modifier.testTag("shuttle_route_${route.id}"), padding = PaddingValues(14.dp), spacing = 6.dp) {
                    Text("${route.period}路线", style = MaterialTheme.typography.titleSmall)
                    Text(route.stops.joinToString(" → "), style = MaterialTheme.typography.bodyMedium, color = palette.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun ShuttleTimeCard(route: ShuttleRoute, time: LocalTime, index: Int, next: Boolean, past: Boolean,
    timeStyle: TextStyle, cardHeight: Dp, reserveBadgeLine: Boolean, badgeHeight: Dp, modifier: Modifier) {
    val palette = gxz
    val last = index == route.departures.lastIndex
    val shape = RoundedCornerShape(10.dp)
    val foreground = when { next -> palette.onAccent; past -> palette.inkSoft; else -> palette.ink }
    Box(modifier.height(cardHeight).clip(shape)
        .background(if (next) palette.accent else if (index % 2 == 0) palette.glass else palette.well)
        .border(.6.dp, if (next) palette.accent else palette.hairline, shape)
        .testTag("shuttle_departure_${route.id}_$index").semantics(mergeDescendants = true) {
            stateDescription = when { next -> "下一班"; past -> "已过计划发车时间"; else -> "计划发车时间" }
        }.padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(shuttleTimeLabel(time), Modifier.fillMaxWidth().testTag("shuttle_time_${route.id}_$index"),
                style = timeStyle, maxLines = 1, textAlign = TextAlign.Center, color = foreground)
            if (reserveBadgeLine) Box(Modifier.height(badgeHeight), contentAlignment = Alignment.Center) {
                if (last) Text("末车", Modifier.testTag("shuttle_badge_${route.id}_$index"),
                    style = MaterialTheme.typography.labelSmall, color = foreground)
            }
        }
        if (last && !reserveBadgeLine) Text("末车", Modifier.align(Alignment.CenterEnd).testTag("shuttle_badge_${route.id}_$index"),
            style = MaterialTheme.typography.labelSmall, color = foreground)
    }
}
