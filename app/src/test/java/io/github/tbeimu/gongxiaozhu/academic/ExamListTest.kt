package io.github.tbeimu.gongxiaozhu.academic

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class ExamListTest {
    private val now = LocalDateTime.of(2026, 11, 27, 12, 0)
    private fun exam(id: String, start: String = "", end: String = "", name: String = id) =
        ExamRecord(id, fields = ExamFields(name, start, end))

    @Test fun unfinishedComeFirstWithUnknownStartLastAndFinishedUseNewestEndFirst() {
        val items = examListItems(listOf(
            exam("old", "2026-11-20 09:00", "2026-11-20 11:00"), exam("unknown"),
            exam("later", "2026-12-01 09:00", "2026-12-01 11:00"),
            exam("recent", "2026-11-26 09:00", "2026-11-26 11:00"),
            exam("ongoing", "2026-11-27 11:00", "2026-11-27 13:00")), now)
        assertEquals(listOf("ongoing", "later", "unknown", "recent", "old"), items.map { it.record.id })
        assertEquals(listOf(false, false, false, true, true), items.map { it.finished })
    }

    @Test fun endInstantIsStillOngoingAndOnlyLaterSnapshotsFinishIt() {
        val record = exam("boundary", "2026-11-27 10:00", "2026-11-27 12:00")
        assertFalse(examListItems(listOf(record), now.minusNanos(1)).single().finished)
        assertFalse(examListItems(listOf(record), now).single().finished)
        assertTrue(examListItems(listOf(record), now.plusNanos(1)).single().finished)
    }

    @Test fun unknownMissingOrReversedTimesNeverImplyFinished() {
        val records = listOf(exam("missing-end", "2026-11-26 09:00"),
            exam("unparsed-end", "2026-11-26 09:00", "另行通知"),
            exam("missing-start", end = "2026-11-26 11:00"),
            exam("unparsed-start", "时间待定", "2026-11-26 11:00"),
            exam("reversed", "2026-11-26 12:00", "2026-11-26 10:00"))
        assertTrue(examListItems(records, now).none { it.finished })
        assertEquals(records.toSet(), examListItems(records, now).map { it.record }.toSet())
    }

    @Test fun finishedOrderingUsesEndRatherThanStart() {
        val records = listOf(exam("early-start-late-end", "2026-11-25 08:00", "2026-11-27 11:00"),
            exam("late-start-early-end", "2026-11-26 09:00", "2026-11-26 11:00"))
        assertEquals(records.map { it.id }, examListItems(records.reversed(), now).map { it.record.id })
    }

    @Test fun equalTimesUseNameThenIdForStableOrdering() {
        for (end in listOf("2026-11-27 13:00", "2026-11-27 11:00")) {
            val records = listOf(exam("z", "2026-11-27 10:00", end, "A"),
                exam("a", "2026-11-27 10:00", end, "A"),
                exam("b", "2026-11-27 10:00", end, "B"))
            assertEquals(listOf("a", "z", "b"), examListItems(records, now).map { it.record.id })
        }
    }

    @Test fun crossDayCrossYearAndLeapDayUseFullDateTimes() {
        val records = listOf(exam("year", "2025-12-31 23:00", "2026-01-01 01:00"),
            exam("leap", "2024-02-29 09:00", "2024-02-29 11:00"),
            exam("night", "2026-11-26 23:00", "2026-11-27 01:00"))
        assertEquals(listOf("night", "year", "leap"), examListItems(records, now).map { it.record.id })
        assertTrue(examListItems(records, now).all { it.finished })
    }

    @Test fun personalFieldsControlStatusWithoutChangingOriginalOrHiddenFlag() {
        val original = SchoolExam("fixture", "学校考试", "虚构班级", "2026-11-26 09:00", "2026-11-26 11:00", "虚构教室")
        val edited = ExamRecord("edited", original, original.toExamFields().copy(
            start = "2026-12-01 09:00", end = "2026-12-01 11:00"), hidden = true)
        val item = examListItems(listOf(edited), now).single()
        assertFalse(item.finished)
        assertSame(edited, item.record)
        assertTrue(item.record.hidden)
        assertEquals(original, item.record.original)
    }

    @Test fun movingClockReordersTheSameRecordsWithoutMutation() {
        val records = listOf(exam("finishing", "2026-11-27 10:00", "2026-11-27 12:00"),
            exam("future", "2026-11-28 10:00", "2026-11-28 12:00"))
        assertEquals(listOf("finishing", "future"), examListItems(records, now).map { it.record.id })
        assertEquals(listOf("future", "finishing"), examListItems(records, now.plusMinutes(1)).map { it.record.id })
        assertEquals("2026-11-27 12:00", records.first().fields.end)
    }

    @Test fun emptyListStaysEmpty() { assertTrue(examListItems(emptyList(), now).isEmpty()) }
}
