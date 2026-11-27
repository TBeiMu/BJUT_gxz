package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.academic.UpcomingExam
import io.github.tbeimu.gongxiaozhu.academic.UpcomingExamOverview
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
internal fun UpcomingExamsSummary(overview: UpcomingExamOverview, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, animateEntrance: Boolean = true) {
    val palette = gxz
    BoxWithConstraints(modifier.fillMaxWidth().glass(palette).padding(6.dp).testTag("upcoming_exams")) {
        val stackCountdown = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.25f
        Column {
            overview.exams.forEachIndexed { index, exam ->
                key(exam.record.id) {
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().padding(start = 58.dp, end = 10.dp)
                            .height(.7.dp).background(palette.glassEdge))
                    }
                    UpcomingExamRow(exam, overview.viewingDate, index == 0, stackCountdown, enabled, onClick,
                        Modifier.entrance(index * Motion.STAGGER, distance = 6.dp, enabled = animateEntrance))
                }
            }
        }
    }
}

@Composable
private fun UpcomingExamRow(exam: UpcomingExam, viewingDate: LocalDate, primary: Boolean,
    stackCountdown: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val palette = gxz
    val tone = if (primary) palette.accent else palette.inkSoft
    val tint = if (primary) palette.accent else palette.ink
    val countdown = upcomingExamCountdownLabel(exam, viewingDate)
    Row(modifier.fillMaxWidth().clip(TileShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看全部考试", onClick = onClick)
        .testTag("upcoming_exam_" + exam.record.id).heightIn(min = 68.dp)
        .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(tint.copy(alpha = if (primary) .12f else .055f), CircleShape),
            contentAlignment = Alignment.Center) {
            LineGlyph(AppGlyph.EXAM, Modifier.size(18.dp), tone)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(exam.record.fields.name, Modifier.fillMaxWidth(), color = palette.ink,
                style = if (primary) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
            Text(upcomingExamDateTimeLabel(exam, viewingDate), Modifier.fillMaxWidth(),
                color = palette.inkSoft, style = MaterialTheme.typography.bodySmall)
            if (stackCountdown) ExamCountdown(countdown, primary)
        }
        if (!stackCountdown) ExamCountdown(countdown, primary)
        LineGlyph(AppGlyph.CHEVRON, Modifier.size(14.dp), palette.inkFaint)
    }
}

@Composable
private fun ExamCountdown(countdown: String, primary: Boolean) {
    val palette = gxz
    val tone = if (primary) palette.accent else palette.inkSoft
    val fill = if (primary) palette.accent.copy(alpha = .12f) else palette.ink.copy(alpha = .05f)
    Box(Modifier.background(fill, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center) {
        val style = MaterialTheme.typography.labelMedium
        if (LocalReducedMotion.current) {
            Text(countdown, color = tone, style = style)
        } else {
            AnimatedContent(countdown, contentAlignment = Alignment.Center, transitionSpec = {
                (fadeIn(tween(Motion.SHORT)) + slideInVertically(tween(Motion.SHORT)) { it / 6 }) togetherWith
                    (fadeOut(tween(Motion.SHORT)) + slideOutVertically(tween(Motion.SHORT)) { -it / 6 })
            }, label = "考试倒计时") { label ->
                Text(label, color = tone, style = style)
            }
        }
    }
}

private val ExamMonthDay = DateTimeFormatter.ofPattern("M月d日")
private val ExamYearMonthDay = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val ExamClock = DateTimeFormatter.ofPattern("HH:mm")

internal fun upcomingExamDateTimeLabel(exam: UpcomingExam, viewingDate: LocalDate): String {
    val dateFormat = if (exam.start.year == viewingDate.year) ExamMonthDay else ExamYearMonthDay
    return exam.start.format(dateFormat) + " " + exam.start.format(ExamClock)
}

internal fun upcomingExamCountdownLabel(exam: UpcomingExam, viewingDate: LocalDate): String {
    val days = ChronoUnit.DAYS.between(viewingDate, exam.start.toLocalDate()).coerceAtLeast(0)
    return if (days == 0L) "加油！" else "剩" + days + "天"
}
