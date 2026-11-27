package io.github.tbeimu.gongxiaozhu.academic

import java.time.LocalDateTime

/** One clock snapshot drives ordering and appearance; none of this state is persisted. */
internal data class ExamListItem(val record: ExamRecord, val start: LocalDateTime?,
    val end: LocalDateTime?, val finished: Boolean)

internal fun examListItems(records: List<ExamRecord>, now: LocalDateTime): List<ExamListItem> =
    records.map { record ->
        val start = parseAcademicExamTime(record.fields.start)
        val end = parseAcademicExamTime(record.fields.end)
        ExamListItem(record, start, end, start != null && end != null && !end.isBefore(start) && end.isBefore(now))
    }.sortedWith(compareBy<ExamListItem> { it.finished }.thenComparator { first, second ->
        if (first.finished) compareValues(second.end, first.end)
        else compareValues(first.start ?: LocalDateTime.MAX, second.start ?: LocalDateTime.MAX)
    }.thenBy { it.record.fields.name }.thenBy { it.record.id })
