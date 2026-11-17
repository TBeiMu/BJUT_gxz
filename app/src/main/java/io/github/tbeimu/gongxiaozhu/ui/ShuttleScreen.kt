package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Content of the existing scrollable personal-detail page; both columns share that one scroll. */
@Composable
internal fun ShuttleTimetableContent(nowProvider: () -> LocalDateTime = ::shanghaiNow) {
    val moment by rememberForegroundMoment(nowProvider)
    val next = nextShuttleDeparture(moment)
    val minute = moment.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
    val palette = gxz
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val cellWidth = with(density) { ((maxWidth - 12.dp) / 2 - 16.dp).roundToPx().coerceAtLeast(1) }
        val baseStyle = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum")
        val widestTime = shuttleRoutes.flatMap { it.departures }.maxOf {
            measurer.measure(shuttleTimeLabel(it), baseStyle, maxLines = 1).size.width
        }
        val scale = minOf(1f, (cellWidth - 1).coerceAtLeast(1).toFloat() / widestTime)
        val timeStyle = baseStyle.copy(fontSize = baseStyle.fontSize * scale, lineHeight = baseStyle.lineHeight * scale)
        val textHeight = shuttleRoutes.flatMap { it.departures }.maxOf {
            measurer.measure(shuttleTimeLabel(it), timeStyle, maxLines = 1).size.height
        }
        val labelHeight = measurer.measure("下一班 · 末车", MaterialTheme.typography.labelSmall,
            constraints = Constraints(maxWidth = cellWidth)).size.height
        val cardHeight = maxOf(76.dp, with(density) { (textHeight + labelHeight).toDp() } + 20.dp)
        Column(Modifier.fillMaxWidth().testTag("shuttle_timetable"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            GlassCard(padding = PaddingValues(16.dp), spacing = 8.dp) {
                Text("中蓝校区—北工大专线", style = MaterialTheme.typography.titleMedium)
                Text("计划发车时间", style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
                Text(next?.let { "下一班 ${shuttleTimeLabel(it.time)} · ${it.route.origin}发车" }
                    ?: "今日所列班次已结束", Modifier.testTag("shuttle_status"),
                    style = MaterialTheme.typography.bodyMedium, color = if (next != null) palette.accent else palette.inkSoft)
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                shuttleRoutes.forEach { route ->
                    Column(Modifier.weight(1f).testTag("shuttle_column_${route.id}").padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(route.period, style = MaterialTheme.typography.labelLarge, color = palette.inkSoft)
                        Text(route.origin, style = MaterialTheme.typography.titleMedium, color = palette.ink)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(shuttleRoutes.maxOf { it.departures.size }) { index ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        shuttleRoutes.forEach { route ->
                            val time = route.departures.getOrNull(index)
                            if (time == null) Spacer(Modifier.weight(1f))
                            else ShuttleTimeCard(route, time, index, next?.route == route && next.time == time,
                                time.isBefore(minute), timeStyle, cardHeight, Modifier.weight(1f))
                        }
                    }
                }
            }
            shuttleRoutes.forEach { route ->
                GlassCard(Modifier.testTag("shuttle_route_${route.id}"), padding = PaddingValues(16.dp), spacing = 8.dp) {
                    Text("${route.period}路线", style = MaterialTheme.typography.titleMedium)
                    Text(route.stops.joinToString(" → "), style = MaterialTheme.typography.bodyMedium, color = palette.inkSoft)
                }
            }
        }
    }
}

@Composable
private fun ShuttleTimeCard(route: ShuttleRoute, time: LocalTime, index: Int, next: Boolean, past: Boolean,
    timeStyle: TextStyle, cardHeight: Dp, modifier: Modifier) {
    val palette = gxz
    val labels = listOfNotNull("下一班".takeIf { next }, "末车".takeIf { time == route.departures.last() }).joinToString(" · ")
    Column(modifier.height(cardHeight).clip(RoundedCornerShape(12.dp))
        .background(if (next) palette.accent else if (index % 2 == 0) palette.glass else palette.well)
        .testTag("shuttle_departure_${route.id}_$index").semantics(mergeDescendants = true) {
            stateDescription = when { next -> "下一班"; past -> "已过计划发车时间"; else -> "计划发车时间" }
        }.padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(shuttleTimeLabel(time), Modifier.fillMaxWidth().testTag("shuttle_time_${route.id}_$index"),
            style = timeStyle, maxLines = 1, textAlign = TextAlign.Center,
            color = when { next -> palette.onAccent; past -> palette.inkSoft; else -> palette.ink })
        Text(labels, Modifier.testTag("shuttle_badge_${route.id}_$index"), minLines = 1,
            style = MaterialTheme.typography.labelSmall, color = if (next) palette.onAccent else palette.inkSoft)
    }
}
