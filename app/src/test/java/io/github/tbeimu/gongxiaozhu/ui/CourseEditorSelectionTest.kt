package io.github.tbeimu.gongxiaozhu.ui

import org.junit.Assert.*
import org.junit.Test

class CourseEditorSelectionTest {
    @Test fun firstTapIsSingleAndSecondTapClosesForwardRange() {
        val first = CourseLessonSelection(listOf(1, 2)).select(5)
        assertEquals(listOf(5), first.lessons)
        assertEquals(5, first.anchor)
        assertTrue(first.changed)
        val second = first.select(7)
        assertEquals(listOf(5, 6, 7), second.lessons)
        assertNull(second.anchor)
    }

    @Test fun reverseRangeAndThirdTapStartAnotherSelection() {
        val range = CourseLessonSelection(listOf(1, 2)).select(12).select(10)
        assertEquals(listOf(10, 11, 12), range.lessons)
        assertEquals(listOf(3), range.select(3).lessons)
        assertEquals(listOf(3), range.select(3).select(3).lessons)
    }

    @Test fun lessonSelectionStopsAtTwelve() {
        assertThrows(IllegalArgumentException::class.java) { CourseLessonSelection(emptyList()).select(13) }
    }

    @Test fun originalNonconsecutiveLessonsAreRetainedUntilExplicitSelection() {
        val original = CourseLessonSelection(listOf(1, 3))
        assertFalse(original.changed)
        assertEquals(listOf(1, 3), original.lessons)
        assertEquals("第 1、3 节", courseLessonSummary(original.lessons))
        assertNull(courseLessonClock(original.lessons))
        assertEquals(listOf(4), original.select(4).lessons)
    }

    @Test fun presetsOnlySelectAvailableWeeksIncludingGaps() {
        val available = listOf(5, 2, 4, 2)
        assertEquals(listOf(2, 4, 5), CourseWeekPreset.ALL.select(available))
        assertEquals(listOf(5), CourseWeekPreset.ODD.select(available))
        assertEquals(listOf(2, 4), CourseWeekPreset.EVEN.select(available))
        assertEquals(emptyList<Int>(), CourseWeekPreset.CLEAR.select(available))
        assertEquals(emptyList<Int>(), CourseWeekPreset.EVEN.select(listOf(1)))
    }

    @Test fun oneTargetSelectionMovesTheClickedOccurrenceAndKeepsOriginalRangeForDisplay() {
        val initial = CourseWeekSelection(listOf(1, 2, 3), listOf(1))
        val moved = initial.withTargets(listOf(8, 6, 8))
        assertEquals(listOf(1, 2, 3), moved.sourceWeeks)
        assertEquals(listOf(6, 8), moved.targetWeeks)
        assertEquals(listOf(1), initial.targetWeeks)
    }

    @Test fun explicitlyClearedTargetsRemainEmptyWhenDeleteScopeChanges() {
        val selection = CourseWeekSelection(listOf(1, 2), listOf(1)).withTargets(emptyList())
        assertTrue(selection.targetWeeks.isEmpty())
        assertEquals(listOf(1, 2), selection.sourceWeeks)
    }

    @Test fun sourceWeeksOutsideDisplayTermRemainAvailableWithoutCreatingInvalidTargets() {
        val selection = CourseWeekSelection(listOf(20), emptyList())
        assertEquals(listOf(20), selection.sourceWeeks)
        assertTrue(selection.targetWeeks.isEmpty())
        assertEquals(listOf(1, 20), CourseWeekSelection(listOf(1), listOf(1, 20)).fitTerm(2, listOf(20)).targetWeeks)
    }

    @Test fun shrinkingTermFiltersTargetsAndInvalidIntermediateInputKeepsDraft() {
        val selection = CourseWeekSelection(emptyList(), listOf(1, 3, 20))
        assertEquals(listOf(1, 3), selection.fitTerm(3).targetWeeks)
        assertEquals(selection, selection.fitTerm(0))
        assertEquals(selection, selection.fitTerm(61))
        assertTrue(selection.fitTerm(2).withTargets(listOf(3)).fitTerm(2).targetWeeks.isEmpty())
    }

    @Test fun weekSummaryKeepsRangesAndBoundsLongAlternatingSelections() {
        assertEquals("未选择周次", courseWeekSummary(emptyList()))
        assertEquals("第 1–3、5–6、9 周", courseWeekSummary(listOf(9, 1, 2, 3, 5, 6, 6)))
        assertEquals("第 1、3、5 等 30 周", courseWeekSummary((1..60 step 2).toList()))
    }

    @Test fun originalScheduleSummaryCombinesWeeksOnlyWhenDayAndLessonsMatch() {
        assertEquals("原有安排：第 1–3 周 · 周一 · 第 1–2 节；第 2 周 · 周三 · 第 5–6 节",
            courseOriginalScheduleSummary(listOf(
                io.github.tbeimu.gongxiaozhu.schedule.CourseOriginalArrangement(1, 1, listOf(1, 2)),
                io.github.tbeimu.gongxiaozhu.schedule.CourseOriginalArrangement(2, 1, listOf(1, 2)),
                io.github.tbeimu.gongxiaozhu.schedule.CourseOriginalArrangement(3, 1, listOf(1, 2)),
                io.github.tbeimu.gongxiaozhu.schedule.CourseOriginalArrangement(2, 3, listOf(5, 6)),
            )))
    }

    @Test fun clocksUseBjutTimesAndNeverInferLaterOrNonconsecutiveLessons() {
        assertEquals("08:00–09:35", courseLessonClock(listOf(1, 2)))
        assertEquals("19:55–21:30", courseLessonClock(listOf(11, 12)))
        assertEquals("18:00–18:45", courseLessonClock(listOf(9)))
        assertNull(courseLessonClock(listOf(12, 13)))
        assertNull(courseLessonClock(listOf(20)))
        assertNull(courseLessonClock(listOf(1, 3)))
        assertNull(courseLessonClock(emptyList()))
    }
}
