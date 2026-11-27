package io.github.tbeimu.gongxiaozhu.academic

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class UpcomingExamsTest {
    private val now = LocalDateTime.of(2026, 11, 27, 12, 0)
    private val today = now.toLocalDate()
    private fun exam(id: String, start: String, end: String = "", name: String = id) =
        ExamRecord(id, fields = ExamFields(name, start, end))

    @Test fun windowIncludesSelectedDayAndDay29ButExcludesDay30() {
        val records = listOf(exam("first", "2026-11-27 12:00"),
            exam("last", "2026-12-26 23:59"), exam("outside", "2026-12-27 00:00"))
        val summary = upcomingExamOverview(records.reversed(), today, now)!!
        assertEquals(2, summary.exams.size)
        assertEquals("first", summary.exams.first().record.id)
    }

    @Test fun selectedDateMovesBothWindowBoundaries() {
        val records = listOf(exam("before", "2026-12-01 23:59"),
            exam("first", "2026-12-02 00:00"), exam("last", "2026-12-31 23:59"),
            exam("outside", "2027-01-01 00:00"))
        val summary = upcomingExamOverview(records, LocalDate.of(2026, 12, 2), now)!!
        assertEquals(2, summary.exams.size)
        assertEquals("first", summary.exams.first().record.id)
    }

    @Test fun historicalBrowsingStillExcludesActuallyFinishedExams() {
        val records = listOf(exam("past", "2026-11-20 09:00", "2026-11-20 11:00"),
            exam("next", "2026-11-28 09:00"))
        val summary = upcomingExamOverview(records, today.minusDays(14), now)!!
        assertEquals(1, summary.exams.size)
        assertEquals("next", summary.exams.first().record.id)
    }

    @Test fun inProgressAndExactEndAreRetainedUntilClockPassesEnd() {
        val record = exam("live", "2026-11-27 11:00", "2026-11-27 12:00")
        assertNotNull(upcomingExamOverview(listOf(record), today, now))
        assertNull(upcomingExamOverview(listOf(record), today, now.plusSeconds(1)))
    }

    @Test fun crossMidnightExamUsesItsStartDateForWindow() {
        val record = exam("overnight", "2026-11-26 23:00", "2026-11-27 13:00")
        assertNotNull(upcomingExamOverview(listOf(record), today.minusDays(1), now))
        assertNull(upcomingExamOverview(listOf(record), today, now))
    }

    @Test fun unknownStartIsNotInventedAndUnknownEndRequiresFutureStart() {
        val records = listOf(exam("unknown", "学校待通知"), exam("expired", "2026-11-27 11:00"),
            exam("next", "2026-11-27 13:00", "结束待通知"))
        val summary = upcomingExamOverview(records, today, now)!!
        assertEquals(1, summary.exams.size)
        assertEquals("next", summary.exams.first().record.id)
        assertNull(summary.exams.first().end)
    }

    @Test fun endBeforeStartIsUnknownRatherThanProvingExamFinished() {
        val record = exam("invalid-end", "2026-11-28 09:00", "2026-11-27 10:00")
        assertNull(upcomingExamOverview(listOf(record), today, now)!!.exams.first().end)
        assertNull(upcomingExamOverview(listOf(record), today, now.plusDays(2)))
    }

    @Test fun hiddenRecordsAreExcludedAndEditedFieldsAreAuthoritative() {
        val school = SchoolExam("fictional", "学校原名", "虚构班级",
            "2026-11-01 09:00", "2026-11-01 11:00", "学校原地点")
        val edited = ExamRecord("edited", school, ExamFields("修改后的考试",
            "2026-11-28 09:00", "2026-11-28 11:00", "修改后的地点"))
        val summary = upcomingExamOverview(listOf(edited, edited.copy(id = "hidden", hidden = true)), today, now)!!
        assertEquals(1, summary.exams.size)
        assertEquals(edited.fields, summary.exams.first().record.fields)
    }

    @Test fun namesDoNotMergeDistinctManualSchoolOrRetainedRecords() {
        val manual = exam("manual", "2026-11-28 09:00", name = "同名考试")
        val school = SchoolExam("fictional", "同名考试", "", manual.fields.start, "", "")
        val records = listOf(manual, manual.copy(id = "school", original = school),
            manual.copy(id = "retained", original = school, retained = true))
        assertEquals(3, upcomingExamOverview(records, today, now)!!.exams.size)
    }

    @Test fun tiesAreStableAcrossInputOrderAndUseNameThenId() {
        val records = listOf(exam("b", "2026-11-28 09:00", name = "A"),
            exam("a", "2026-11-28 09:00", name = "A"), exam("z", "2026-11-28 09:00", name = "B"))
        assertEquals("a", upcomingExamOverview(records, today, now)!!.exams.first().record.id)
        assertEquals("a", upcomingExamOverview(records.reversed(), today, now)!!.exams.first().record.id)
    }

    @Test fun yearAndLeapMonthBoundariesUseCalendarDays() {
        val december = LocalDate.of(2026, 12, 20)
        assertEquals(1, upcomingExamOverview(listOf(exam("included", "2027-01-18 23:59"),
            exam("excluded", "2027-01-19 00:00")), december, now)!!.exams.size)
        assertEquals(1, upcomingExamOverview(listOf(exam("included", "2028-03-20 23:59"),
            exam("excluded", "2028-03-21 00:00")), LocalDate.of(2028, 2, 20), now)!!.exams.size)
    }

    @Test fun onlyTheFirstThreeChronologicalExamsAreReturned() {
        val records = (1..4).map { day -> exam(day.toString(), "2026-12-0$day 09:00") }
        val summary = upcomingExamOverview(records.reversed(), today, now)!!
        assertEquals(listOf("1", "2", "3"), summary.exams.map { it.record.id })
    }

    @Test fun noMatchingExamsProducesNoOverview() {
        assertNull(upcomingExamOverview(emptyList(), today, now))
        assertNull(upcomingExamOverview(listOf(exam("later", "2027-01-01 09:00")), today, now))
    }
}
