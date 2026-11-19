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
        val range = CourseLessonSelection(listOf(1, 2)).select(20).select(18)
        assertEquals(listOf(18, 19, 20), range.lessons)
        assertEquals(listOf(3), range.select(3).lessons)
        assertEquals(listOf(3), range.select(3).select(3).lessons)
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

    @Test fun batchSourceChangesFollowUntilTargetsAreExplicitlyEdited() {
        val initial = CourseWeekSelection(listOf(1), listOf(1))
        val batch = initial.withSources(listOf(3, 1, 2, 2), 20)
        assertEquals(listOf(1, 2, 3), batch.sourceWeeks)
        assertEquals(listOf(1, 2, 3), batch.targetWeeks)
        val moved = batch.withTargets(listOf(8, 6, 8)).withSources(listOf(2), 20)
        assertEquals(listOf(2), moved.sourceWeeks)
        assertEquals(listOf(6, 8), moved.targetWeeks)
        assertTrue(moved.targetsEdited)
    }

    @Test fun explicitlyClearedTargetsAreNotRecreatedBySourceChanges() {
        val selection = CourseWeekSelection(listOf(1), listOf(1)).withTargets(emptyList()).withSources(listOf(1, 2), 20)
        assertTrue(selection.targetWeeks.isEmpty())
        assertTrue(selection.targetsEdited)
    }

    @Test fun sourceWeeksOutsideDisplayTermRemainAvailableWithoutCreatingInvalidTargets() {
        val selection = CourseWeekSelection(listOf(20), emptyList()).withSources(listOf(20), 2)
        assertEquals(listOf(20), selection.sourceWeeks)
        assertTrue(selection.targetWeeks.isEmpty())
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
