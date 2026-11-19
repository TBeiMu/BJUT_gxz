package io.github.tbeimu.gongxiaozhu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseUndoTimerTest {
    @Test fun showingAgainKeepsTheFirstDeadlineAndAccessibilityDuration() {
        var time = 100L
        val timer = CourseUndoTimer { time }
        assertEquals(20_000L, timer.remaining("first", 20_000L))
        time += 7_000
        assertEquals(13_000L, timer.remaining("first", 10_000L))
        time += 14_000
        assertEquals(0L, timer.remaining("first", 10_000L))
    }

    @Test fun newActionAndExplicitInvalidationStartTheirOwnWindow() {
        var time = 100L
        val timer = CourseUndoTimer { time }
        timer.remaining("first", 10_000L)
        time += 7_000
        assertEquals(10_000L, timer.remaining("second", 10_000L))
        timer.clear()
        assertEquals(20_000L, timer.remaining("second", 20_000L))
    }

    @Test fun indefiniteAccessibilityRecommendationDoesNotOverflow() {
        var time = 100L
        val timer = CourseUndoTimer { time }
        assertEquals(Long.MAX_VALUE, timer.remaining("first", Long.MAX_VALUE))
        time += 1_000
        assertEquals(Long.MAX_VALUE - 1_000L, timer.remaining("first", Long.MAX_VALUE))
    }
}
