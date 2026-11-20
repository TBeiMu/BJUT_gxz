package io.github.tbeimu.gongxiaozhu

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.ScheduleScreen
import io.github.tbeimu.gongxiaozhu.ui.ScheduleHome
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class ScheduleDateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)
    private val date = AtomicReference(monday)

    private fun showSchedule(todayView: Boolean = false, expectedTitle: String? = null) {
        val identity = ScheduleIdentity("fake-student")
        val term = ScheduleTerm("2026-2027", "1", 2, 1)
        val weeks = (1..2).map { n -> ScheduleWeek(n, (0L..6L).map { monday.plusWeeks(n - 1L).plusDays(it).toString() }, emptyList()) }
        val state = ScheduleUiState(data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, term, weeks, 1)), loading = false)
        compose.setContent {
            GongXiaoZhuTheme {
                if (todayView) ScheduleHome(state, Modifier, {}, {}, {}, {}, todayProvider = date::get)
                else ScheduleScreen(state, Modifier, {}, {}, {}, {}, todayProvider = date::get)
            }
        }
        compose.onNodeWithText(expectedTitle ?: if (todayView) "今天 · 11月2日 · 第 1 周" else "第 1 周").assertExists()
    }

    @Test fun returningToForegroundRefreshesDateWithoutReimporting() {
        showSchedule()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        date.set(monday.plusWeeks(1))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("回到本周", substring = false).performClick()
        compose.onNodeWithText("第 2 周").assertExists()
    }

    @Test fun thisWeekRechecksClockAtClickTime() {
        showSchedule()
        compose.onNodeWithContentDescription("选择周次").performClick()
        compose.onNodeWithTag("week_choice_2").performClick()
        date.set(monday.plusWeeks(1))
        compose.waitForIdle()
        compose.onNodeWithTag("back_current_week").assertIsDisplayed().performClick()
        compose.onNodeWithText("第 2 周").assertExists()
    }

    @Test fun semesterBoundaryUpdatesDisabledThisWeekButtonOnResume() {
        showSchedule()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        date.set(monday.plusWeeks(3))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("回到本周", substring = false).assertDoesNotExist()
        compose.onNodeWithText("第 1 周").assertExists()
    }

    @Test fun todayViewFollowsTheNewDateOnReturnToForeground() {
        showSchedule(todayView = true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        date.set(monday.plusWeeks(1))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("今天 · 11月9日 · 第 2 周").assertExists()
    }

    @Test fun todayViewKeepsAnExplicitlyBrowsedDayOnReturn() {
        showSchedule(todayView = true)
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        date.set(monday.plusWeeks(1))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("11月5日 · 第 1 周").assertExists()
    }

    @Test fun todayViewCanReturnToTheLatestInTermDate() {
        showSchedule(todayView = true)
        compose.onNodeWithTag("back_current_day").assertDoesNotExist()
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithText("返回今日", substring = false).assertIsDisplayed()

        // The clock may advance while the user browses; returning reads it again at click time.
        val currentDate = monday.plusWeeks(1).plusDays(1)
        date.set(currentDate)
        compose.onNodeWithTag("back_current_day").performClick()
        compose.onNodeWithText("今天 · 11月10日 · 第 2 周").assertExists()
        compose.onNodeWithTag("back_current_day").assertDoesNotExist()
    }

    @Test fun todayViewDoesNotOfferReturnWhenTodayIsOutsideTheSemester() {
        date.set(monday.plusWeeks(3))
        showSchedule(todayView = true, expectedTitle = "11月2日 · 第 1 周")
        compose.onNodeWithTag("back_current_day").assertDoesNotExist()
    }
}
