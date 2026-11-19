package io.github.tbeimu.gongxiaozhu.ui

import io.github.tbeimu.gongxiaozhu.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CourseOperationSelectionTest {
    private val monday = LocalDate.of(2026, 11, 9)

    private fun localData(lessons: List<Int> = (9..12).toList()): ScheduleData {
        val occurrences = (10..17).map { CustomOccurrence(it, 2, lessons) } +
            CustomOccurrence(13, 3, lessons) + CustomOccurrence(13, 2, listOf(5, 6))
        return ScheduleData(localTerms = listOf(LocalScheduleTerm("term", monday.toString(), 20,
            courses = listOf(CustomCourse("course", "测试课程", "教室", "教师", occurrences)))), selectedLocalTermId = "term")
    }

    private fun ScheduleData.localContext(deleteOnly: Boolean = false): CourseEditContext =
        courseEditor(display()!!.weeks[12].courses.first(), deleteOnly = deleteOnly)

    @Test fun unchangedAndRestoredCourseValuesAreClean() {
        val context = localData().localContext()
        val initial = initialCourseDraft(context)
        assertFalse(courseDraftHasChanges(context, initial))
        assertTrue(courseDraftHasChanges(context, initial.copy(name = "新课程名")))
        assertFalse(courseDraftHasChanges(context, initial.copy(name = "新课程名").copy(name = initial.name)))
        assertFalse(courseDraftHasChanges(context, initial.copy(name = "  ${initial.name}  ",
            location = " ${initial.location} ", teacher = " ${initial.teacher} ",
            sourceWeeks = initial.sourceWeeks.reversed() + initial.sourceWeeks.first(),
            targetWeeks = initial.targetWeeks.reversed(), lessons = initial.lessons.reversed() + initial.lessons.first(),
            weekdays = initial.weekdays + initial.weekdays)))
    }

    @Test fun everyPersistedCourseValueIncludingInvalidDraftCountsAsAChange() {
        val context = localData().localContext()
        val initial = initialCourseDraft(context)
        listOf(initial.copy(name = ""), initial.copy(location = "其他教室"), initial.copy(teacher = "其他教师"),
            initial.copy(weekdays = emptyList()), initial.copy(sourceWeeks = emptyList()),
            initial.copy(targetWeeks = emptyList()), initial.copy(lessons = emptyList()),
            initial.copy(firstMonday = "2026-11-16"), initial.copy(weekCount = 0)).forEach { draft ->
            assertTrue(courseDraftHasChanges(context, draft))
        }
    }

    @Test fun aNewCourseStartsCleanAndRangeInteractionFlagsDoNotAffectDirtyState() {
        val context = ScheduleData().courseEditor(weekday = 1)
        val initial = initialCourseDraft(context)
        assertFalse(courseDraftHasChanges(context, initial))
        assertTrue(courseDraftHasChanges(context, initial.copy(name = "新课程")))
        assertFalse(courseDraftHasChanges(context, initial.copy(name = "新课程").copy(name = "")))
        val changedSelection = CourseLessonSelection(initial.lessons, anchor = 2, changed = true)
        assertFalse(courseDraftHasChanges(context, initial.copy(lessons = changedSelection.lessons)))
    }

    @Test fun localDeletionCountsOnlyTheClickedWeekdayAndLessonSeries() {
        val context = localData().localContext(deleteOnly = true)
        val default = courseDeletionArrangements(context, CourseWeekSelection.initial(context).sourceWeeks)
        assertEquals(listOf(CourseOriginalArrangement(13, 2, (9..12).toList())), default)
        assertEquals("第 13 周 · 周二 · 第 9–12 节，共 1 次安排", courseDeletionSummary(default))
        val all = courseDeletionArrangements(context, (10..17).toList() + 13)
        assertEquals(8, all.size)
        assertEquals("第 10–17 周 · 周二 · 第 9–12 节，共 8 次安排", courseDeletionSummary(all))
        assertTrue(courseDeletionArrangements(context, emptyList()).isEmpty())
        assertTrue(courseDeletionArrangements(context, listOf(1)).isEmpty())
        assertEquals("未选择要删除的安排", courseDeletionSummary(emptyList()))
    }

    @Test fun nonconsecutiveLessonsStillFormOneArrangementAndOtherLocalRecordsAreExcluded() {
        val data = localData(listOf(1, 3))
        val term = data.localTerms.single()
        val another = term.courses.single().copy(id = "another-course")
        val context = data.copy(localTerms = listOf(term.copy(courses = term.courses + another))).localContext(true)
        val selected = courseDeletionArrangements(context, listOf(13, 13))
        assertEquals(1, selected.size)
        assertEquals("第 13 周 · 周二 · 第 1、3 节，共 1 次安排", courseDeletionSummary(selected))
    }

    @Test fun schoolDeletionFiltersAllSeriesFieldsAndDeduplicatesRepeatedOccurrences() {
        val identity = ScheduleIdentity("fictional-operation-review")
        val weeks = (1..20).map { number ->
            val dates = (0L..6L).map { monday.plusWeeks(number - 1L).plusDays(it).toString() }
            val source = ScheduleCourse("course", "测试课程", dates[0], listOf(1, 3), "教室", "教师")
            val courses = if (number in 10..17) listOf(source, source, source.copy(name = "其他名称"),
                source.copy(location = "其他教室"), source.copy(teacher = "其他教师"), source.copy(lessons = listOf(5, 6)),
                source.copy(date = dates[1]), source.copy(source = CourseSource.CUSTOM), source.copy(id = "other-id"))
            else emptyList()
            ScheduleWeek(number, dates, courses)
        }
        val data = ScheduleData(identity = identity, complete = SemesterSchedule(identity,
            ScheduleTerm("2026-2027", "1", 20, 13), weeks, 1))
        val context = data.courseEditor(weeks[12].courses.first(), deleteOnly = true)
        assertTrue(context.originalArrangements.size > 8)
        val actual = courseDeletionArrangements(context, (1..20).toList())
        assertEquals((10..17).map { CourseOriginalArrangement(it, 1, listOf(1, 3)) }, actual)
        assertEquals(1, courseDeletionArrangements(context, listOf(13)).size)
        assertTrue(courseDeletionArrangements(context, emptyList()).isEmpty())
    }
}
