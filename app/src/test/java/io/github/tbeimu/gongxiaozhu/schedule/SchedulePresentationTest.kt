package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.ui.weekDragDirection
import io.github.tbeimu.gongxiaozhu.ui.todayStatus
import io.github.tbeimu.gongxiaozhu.ui.DayState
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class SchedulePresentationTest {
    @Test fun upwardSwipesAdvanceAndDownwardSwipesGoBack() {
        assertEquals(-1, weekDragDirection(50f))
        assertEquals(1, weekDragDirection(-50f))
        assertEquals(0, weekDragDirection(0f))
    }
    @Test fun unknownLessonTimesCannotClaimThatAllClassesHaveEnded() {
        val week = demoWeek(1)
        val late = week.courses.single().copy(lessons = listOf(13, 14))
        assertNull(todayStatus(week.copy(courses = listOf(late)), LocalDate.parse(late.date), LocalTime.of(8, 0)))
    }
    @Test fun anUnscheduledLateClassPreventsFinishedStatusAfterKnownClassesEnd() {
        val week = demoWeek(1)
        val late = week.courses.single().copy(id = "late-course", lessons = listOf(13, 14))
        assertNull(todayStatus(week.copy(courses = week.courses + late), LocalDate.parse(late.date), LocalTime.of(10, 0)))
    }
    @Test fun knownLessonTimesKeepNextLiveAndFinishedStatuses() {
        val week = demoWeek(1); val today = LocalDate.parse(week.dates.first())
        assertEquals(DayState.NEXT, todayStatus(week, today, LocalTime.of(7, 50))!!.state)
        assertEquals(DayState.IN_CLASS, todayStatus(week, today, LocalTime.of(8, 10))!!.state)
        assertEquals("今天的课都上完了", todayStatus(week, today, LocalTime.of(10, 0))!!.text)
    }
}
