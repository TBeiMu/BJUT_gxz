package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.academic.UpcomingExamOverview
import io.github.tbeimu.gongxiaozhu.academic.UpcomingExam
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
internal fun UpcomingExamsSummary(overview: UpcomingExamOverview, now: LocalDateTime,
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier.fillMaxWidth().testTag("upcoming_exams")) {
        overview.exams.forEach { exam -> key(exam.record.id) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看全部考试", onClick = onClick)
                .heightIn(min = 48.dp).padding(vertical = 12.dp).testTag("upcoming_exam_${exam.record.id}"),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LineGlyph(AppGlyph.EXAM, Modifier.size(18.dp), gxz.inkSoft)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(exam.record.fields.name, Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium, color = gxz.ink)
                    Text(upcomingExamTimeLabel(exam, now), Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
                }
                LineGlyph(AppGlyph.CHEVRON, Modifier.size(16.dp), gxz.inkFaint)
            }
        } }
    }
}

internal fun upcomingExamTimeLabel(exam: UpcomingExam, now: LocalDateTime): String {
    val start = exam.start
    val date = DateTimeFormatter.ofPattern(if (start.year == now.year) "M月d日" else "yyyy年M月d日")
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), start.toLocalDate()).coerceAtLeast(0)
    val reminder = if (days == 0L) "加油！" else "剩${days}天"
    return "${start.format(date)} ${start.format(clock)} · $reminder"
}
