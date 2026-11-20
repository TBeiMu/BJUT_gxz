package io.github.tbeimu.gongxiaozhu.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.tbeimu.gongxiaozhu.schedule.*
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class TodayReturnScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)
    private val scrollTag = "agenda_scroll_" + monday.plusDays(3)

    private fun show(): StateRestorationTester {
        val dates = (0L..6L).map { monday.plusDays(it).toString() }
        val identity = ScheduleIdentity("fictional-return-scroll")
        val courses = (1..12).map { lesson ->
            ScheduleCourse("fictional-$lesson", "虚构课程$lesson", dates[3], listOf(lesson), "虚构教室")
        }
        val state = ScheduleUiState(loading = false, data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, ScheduleTerm("2026-2027", "1", 1, 1),
                listOf(ScheduleWeek(1, dates, courses)), 1)))
        return StateRestorationTester(compose).also { tester ->
            tester.setContent { GongXiaoZhuTheme {
                ScheduleHome(state, Modifier, {}, {}, {}, {}, todayProvider = { monday }, nowProvider = { LocalTime.NOON })
            } }
        }
    }

    @Test fun horizontalReturnPreservesTheOutgoingDaysVerticalScrollPosition() {
        show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag(scrollTag).performTouchInput { swipeUp() }
        val before = compose.onNodeWithTag(scrollTag).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The test must start below the top of the long timeline", before > 100f)
        compose.onNodeWithTag("back_current_day").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("back_current_day").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            compose.onNodeWithTag("return_day_3").assertExists()
            val during = compose.onNodeWithTag(scrollTag).fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals("Starting horizontal return must not jump the day back to its top", before, during, 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun ordinaryBrowsingKeepsItsVerticalScrollAfterRestoration() {
        val restoration = show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag(scrollTag).performTouchInput { swipeUp() }
        val before = compose.onNodeWithTag(scrollTag).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(before > 100f)
        restoration.emulateSavedInstanceStateRestore()
        val restored = compose.onNodeWithTag(scrollTag).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("The return layer must not regress normal scroll restoration", before, restored, 1f)
    }
}
