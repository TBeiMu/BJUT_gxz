package io.github.tbeimu.gongxiaozhu.academic

import java.time.LocalDate
import java.time.LocalDateTime

/** A derived view of existing records; never persisted or used to initiate a query. */
internal data class UpcomingExam(val record: ExamRecord,
    val start: LocalDateTime, val end: LocalDateTime?)
internal data class UpcomingExamOverview(val viewingDate: LocalDate, val exams: List<UpcomingExam>)

/** The selected day owns the window; the real Shanghai clock always owns expiry. */
internal fun upcomingExamOverview(records: List<ExamRecord>, from: LocalDate,
    now: LocalDateTime): UpcomingExamOverview? {
    val limit = from.plusDays(30)
    val matches = records.asSequence().filterNot { it.hidden }.mapNotNull { record ->
        val start = parseAcademicExamTime(record.fields.start) ?: return@mapNotNull null
        if (start.toLocalDate() < from || start.toLocalDate() >= limit) return@mapNotNull null
        val end = parseAcademicExamTime(record.fields.end)?.takeUnless { it < start }
        if (end?.let { it < now } ?: (start < now)) return@mapNotNull null
        UpcomingExam(record, start, end)
    }.sortedWith(compareBy<UpcomingExam> { it.start }
        .thenBy { it.record.fields.name }.thenBy { it.record.id }).take(3).toList()
    return matches.takeIf { it.isNotEmpty() }?.let { UpcomingExamOverview(from, it) }
}
