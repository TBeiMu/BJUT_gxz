package io.github.tbeimu.gongxiaozhu.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.schedule.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TodayReturnMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)
    private val date = AtomicReference(monday)

    private fun show(weekCount: Int = 2, reducedMotion: Boolean = false): StateRestorationTester {
        val identity = ScheduleIdentity("fictional-student")
        val term = ScheduleTerm("2026-2027", "1", weekCount, 1)
        val weeks = (1..weekCount).map { n -> ScheduleWeek(n,
            (0L..6L).map { monday.plusWeeks(n - 1L).plusDays(it).toString() }, emptyList()) }
        val state = ScheduleUiState(data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, term, weeks, 1)), loading = false)
        return StateRestorationTester(compose).also { tester ->
            tester.setContent {
                GongXiaoZhuTheme {
                    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                        AppBackdrop(WallpaperState()) {
                            ScheduleHome(state, Modifier, {}, {}, {}, {}, todayProvider = date::get,
                                nowProvider = { LocalTime.NOON })
                        }
                    }
                }
            }
            compose.onNodeWithText("今天 · 11月2日 · 第 1 周").assertExists()
        }
    }

    private fun changeDate(day: LocalDate) {
        // ACTION_DATE_CHANGED is protected on real Android; refresh the injected clock through
        // the foreground lifecycle instead. The broadcast path is covered by TodayDateTest.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        date.set(day)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun returnDayLayers() = compose.onAllNodes(SemanticsMatcher("return day layer") { node ->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].startsWith("return_day_")
    }).fetchSemanticsNodes()

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("无法取得测试截图")
        try {
            instrumentation.targetContext.getExternalFilesDir(null)?.resolve("today-return-$name.png")?.outputStream()?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun returnTodayRollsEmptyDaysRightFromTheFutureWithoutMovingTheHeader() {
        show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        val header = compose.onNodeWithTag("schedule_week_title").fetchSemanticsNode().boundsInRoot
        val dayStrip = compose.onNodeWithTag("agenda_day_0").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("today_course_viewport").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("back_current_day").performClick()
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            compose.onNodeWithText("今天 · 11月2日 · 第 1 周").assertExists()
            val leaving = compose.onNodeWithTag("return_day_3").fetchSemanticsNode().boundsInRoot
            assertTrue("An empty future day must visibly roll right", leaving.left > viewport.left + 5f)
            assertEquals(header, compose.onNodeWithTag("schedule_week_title").fetchSemanticsNode().boundsInRoot)
            assertEquals(dayStrip, compose.onNodeWithTag("agenda_day_0").fetchSemanticsNode().boundsInRoot)
            screenshot("rolling-right")
            compose.mainClock.advanceTimeBy(640); compose.waitForIdle()
            compose.onNodeWithTag("return_day_3").assertDoesNotExist()
            compose.onNodeWithTag("back_current_day").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun returnTodayRollsLeftFromThePastAndKeepsFollowingAfterwards() {
        show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        val viewport = compose.onNodeWithTag("today_course_viewport").fetchSemanticsNode().boundsInRoot
        date.set(monday.plusWeeks(1))
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("back_current_day").performClick()
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            compose.onNodeWithText("今天 · 11月9日 · 第 2 周").assertExists()
            val leaving = compose.onNodeWithTag("return_day_3").fetchSemanticsNode().boundsInRoot
            assertTrue("A past day must visibly roll left", leaving.right < viewport.right - 5f)
            screenshot("rolling-left")
            compose.mainClock.advanceTimeBy(640); compose.waitForIdle()
            compose.onNodeWithTag("return_day_3").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
        changeDate(monday.plusWeeks(1).plusDays(1))
        compose.onNodeWithText("今天 · 11月10日 · 第 2 周").assertExists()
    }

    @Test fun farReturnTodayShowsIntermediateDaysAndStopsWithinTheBound() {
        show(weekCount = 20)
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag("back_current_day").assertIsDisplayed()
        date.set(monday.plusWeeks(15))
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("back_current_day").performClick()
            compose.mainClock.advanceTimeBy(160); compose.waitForIdle()
            val layers = returnDayLayers()
            assertTrue("Returning must pass intermediate dates instead of only crossfading endpoints",
                layers.any { it.config[SemanticsProperties.TestTag].removePrefix("return_day_").toInt() in 4..104 })
            assertTrue("Only adjacent dates may be composed for the return animation",
                layers.size <= 2)
            compose.mainClock.advanceTimeBy(560); compose.waitForIdle()
            compose.onNodeWithTag("back_current_day").assertDoesNotExist()
            assertTrue("The return layers must be gone after the bounded animation",
                returnDayLayers().isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun reducedMotionReturnsTodayWithoutIntermediateLayers() {
        show(reducedMotion = true)
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag("back_current_day").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("back_current_day").performClick()
            // Dispatch, recomposition and AnimatedContent removal need separate frames; this is
            // still well before the ordinary 440 ms page transition could finish.
            repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.onNodeWithText("今天 · 11月2日 · 第 1 周").assertExists()
            compose.onNodeWithTag("return_day_3").assertDoesNotExist()
            compose.onNodeWithTag("agenda_scroll_2026-11-05").assertDoesNotExist()
            compose.onNodeWithTag("agenda_scroll_2026-11-02").assertExists()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun foregroundDateChangeDuringReturnDiscardsOldMotionAndFollowsTheNewToday() {
        show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag("back_current_day").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("back_current_day").performClick()
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            changeDate(monday.plusDays(1))
            repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.onNodeWithText("今天 · 11月3日 · 第 1 周").assertExists()
            assertTrue("A changed current date must immediately discard old return layers", returnDayLayers().isEmpty())
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            compose.onNodeWithText("今天 · 11月3日 · 第 1 周").assertExists()
            compose.onNodeWithTag("return_day_3").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun returnClickRechecksTermBoundaryBeforeDiscardingTheBrowsedDate() {
        show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        date.set(monday.plusWeeks(3))
        compose.onNodeWithTag("back_current_day").performClick()
        compose.onNodeWithText("11月5日 · 第 1 周").assertExists()
        compose.onNodeWithTag("back_current_day").assertDoesNotExist()
    }

    @Test fun choosingAnotherDayDuringReturnCannotBeOverwrittenByTheOldAnimation() {
        show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag("back_current_day").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("back_current_day").performClick()
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            compose.onNodeWithTag("agenda_day_5").performClick()
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            compose.onNodeWithText("11月7日 · 第 1 周").assertExists()
            assertTrue("A new date selection must discard the return layers", returnDayLayers().isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun newSwipeDuringReturnStartsFromTheCurrentlyVisibleDay() {
        show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag("back_current_day").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("back_current_day").performClick()
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            compose.onNodeWithTag("return_day_3").assertExists()
            // The displayed page is still nearest Thursday, while the saved target is Monday.
            // Swiping right must browse Wednesday, not attempt to move before Monday.
            compose.onNodeWithTag("today_course_viewport").performTouchInput { swipeRight() }
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            compose.onNodeWithText("11月4日 · 第 1 周").assertExists()
            assertTrue("The superseded return must not leave its layers behind", returnDayLayers().isEmpty())
            compose.onNodeWithTag("back_current_day").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun backgroundDuringReturnKeepsTodayFollowingWithoutReplayingTheOldMotion() {
        show()
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag("back_current_day").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("back_current_day").performClick()
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.mainClock.advanceTimeBy(800)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithText("今天 · 11月2日 · 第 1 周").assertExists()
            assertTrue("Returning from the background must discard old return layers", returnDayLayers().isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
        changeDate(monday.plusDays(1))
        compose.onNodeWithText("今天 · 11月3日 · 第 1 周").assertExists()
    }

}
