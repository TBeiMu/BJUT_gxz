package io.github.tbeimu.gongxiaozhu.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import io.github.tbeimu.gongxiaozhu.academic.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class UpcomingExamsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clock = AtomicReference(LocalDateTime.of(2026, 11, 27, 12, 0))
    private var academic by mutableStateOf(AcademicUiState(loading = false))
    private var unobstructed by mutableStateOf(true)
    private var opens = 0
    private fun exam(id: String, start: String, end: String = "") =
        ExamRecord(id, fields = ExamFields("虚构考试$id", start, end, "虚构教室"))
    private fun timetable(): ScheduleUiState {
        val identity = ScheduleIdentity("fictional-exam-summary")
        val monday = LocalDate.of(2026, 11, 23)
        val weeks = (1..8).map { week -> ScheduleWeek(week,
            (0L..6L).map { monday.plusWeeks(week - 1L).plusDays(it).toString() }, emptyList()) }
        return ScheduleUiState(loading = false, data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, ScheduleTerm("2026-2027", "1", 8, 1), weeks, 1)))
    }
    private fun show(schedule: ScheduleUiState = timetable()): StateRestorationTester {
        return StateRestorationTester(compose).also { restoration -> restoration.setContent { GongXiaoZhuTheme {
            ScheduleHome(schedule, Modifier, {}, {}, {}, {}, todayProvider = { clock.get().toLocalDate() },
                nowProvider = { clock.get().toLocalTime() }, titleUnobstructed = unobstructed,
                academic = academic, onOpenExams = { opens++ }, examNowProvider = clock::get)
        } } }
    }
    private fun seed() { academic = academic.copy(data = AcademicData(exams = listOf(
        exam("最近", "2026-11-28 09:00", "2026-11-28 11:00"),
        exam("稍后", "2026-12-26 09:00")))) }

    @Test fun overviewFollowsTomorrowAndShowsAtMostThreeWithCountdowns() {
        seed()
        academic = academic.copy(data = academic.data.copy(exams = academic.data.exams + listOf(
            exam("第二", "2026-11-29 09:00"), exam("第三", "2026-11-30 09:00"))))
        show()
        compose.onNodeWithText("近30日考试").assertDoesNotExist()
        compose.onNodeWithTag("upcoming_exams").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("11月28日 09:00").assertExists()
        compose.onNodeWithText("剩1天").assertExists()
        compose.onNodeWithText("11月29日 09:00").assertExists()
        compose.onNodeWithText("剩2天").assertExists()
        compose.onNodeWithText("11月30日 09:00").assertExists()
        compose.onNodeWithText("剩3天").assertExists()
        compose.onAllNodes(hasText("场考试", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("虚构考试最近").assertExists()
        compose.onNodeWithText("虚构考试稍后").assertDoesNotExist()
        val tomorrow = compose.onNodeWithText("明天没有课").fetchSemanticsNode().boundsInRoot
        val summary = compose.onNodeWithTag("upcoming_exams").fetchSemanticsNode().boundsInRoot
        assertTrue(summary.top >= tomorrow.bottom)
        compose.onNodeWithTag("upcoming_exam_最近").performScrollTo().performClick()
        assertEquals(1, opens)
    }

    @Test fun browsingDateMovesWindowAndRestorationKeepsSelection() {
        seed()
        academic = academic.copy(data = academic.data.copy(exams = academic.data.exams +
            exam("所选日期", "2026-11-29 09:00")))
        val restoration = show()
        compose.onNodeWithTag("agenda_day_6").performClick()
        compose.onNodeWithTag("upcoming_exams").performScrollTo()
        compose.onNodeWithText("11月29日 09:00").assertExists()
        compose.onNodeWithText("加油！").assertExists()
        compose.onNodeWithText("12月26日 09:00").assertExists()
        compose.onNodeWithText("剩27天").assertExists()
        compose.onNodeWithText("虚构考试稍后").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("11月29日 · 第 1 周").assertExists()
        compose.onNodeWithText("11月29日 09:00").assertExists()
        compose.onNodeWithText("加油！").assertExists()
        compose.onNodeWithText("12月26日 09:00").assertExists()
        compose.onNodeWithText("剩27天").assertExists()
        compose.onNodeWithText("11月28日 09:00").assertDoesNotExist()
        compose.onNodeWithText("虚构考试稍后").assertExists()
    }

    @Test fun noExamsLoadingAndBlockedAllHideWholeOverview() {
        show()
        compose.onNodeWithTag("upcoming_exams").assertDoesNotExist()
        compose.runOnIdle { seed(); academic = academic.copy(loading = true) }
        compose.onNodeWithTag("upcoming_exams").assertDoesNotExist()
        compose.runOnIdle { academic = academic.copy(loading = false, blocked = true) }
        compose.onNodeWithTag("upcoming_exams").assertDoesNotExist()
        compose.runOnIdle { academic = academic.copy(blocked = false) }
        compose.onNodeWithTag("upcoming_exams").assertExists()
    }

    @Test fun minuteTickRemovesEndedExamWithoutQuerying() {
        academic = academic.copy(data = AcademicData(exams = listOf(exam("进行中",
            "2026-11-27 11:00", "2026-11-27 12:00"))))
        show()
        compose.onNodeWithText("11月27日 11:00").assertExists()
        compose.onNodeWithText("加油！").assertExists()
        compose.runOnIdle {
            clock.set(clock.get().plusMinutes(1))
            compose.activity.sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
        }
        compose.onNodeWithTag("upcoming_exams").assertDoesNotExist()
    }

    @Test fun foregroundRechecksExpiryAndSnapshotChangesUpdateOverview() {
        seed(); show()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        clock.set(clock.get().plusDays(2))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("12月26日 09:00").assertExists()
        compose.onNodeWithText("剩27天").assertExists()
        compose.onNodeWithText("虚构考试稍后").assertExists()
        compose.runOnIdle { academic = academic.copy(data = academic.data.copy(exams = emptyList())) }
        compose.onNodeWithTag("upcoming_exams").assertDoesNotExist()
    }

    @Test fun wholeWeekHidesOverviewAndTodayRestoresIt() {
        seed(); show()
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.onNodeWithTag("upcoming_exams").assertDoesNotExist()
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.onNodeWithTag("upcoming_exams").assertExists()
    }

    @Test fun examsRemainAvailableWithoutTimetableOrCampusAccount() {
        seed(); show(ScheduleUiState(loading = false))
        compose.onNodeWithTag("schedule_empty_scroll").assertExists()
        compose.onNodeWithTag("upcoming_exam_最近").performScrollTo().performClick()
        assertEquals(1, opens)
    }

    @Test fun obscuredPageDoesNotNavigateThroughSummary() {
        seed(); show()
        compose.onNodeWithTag("upcoming_exams").performScrollTo()
        compose.runOnIdle { unobstructed = false }
        compose.onNodeWithTag("upcoming_exam_最近").assertIsNotEnabled()
    }

    @Test fun todayShowsEncouragementAndCountdownUsesShanghaiCalendarDays() {
        clock.set(clock.get().withHour(23).withMinute(59))
        academic = academic.copy(data = AcademicData(exams = listOf(
            exam("今晚", "2026-11-27 23:00", "2026-11-28 00:30"),
            exam("明早", "2026-11-28 09:00"))))
        show()
        compose.onNodeWithText("11月27日 23:00").assertExists()
        compose.onNodeWithText("加油！").assertExists()
        compose.onNodeWithText("11月28日 09:00").assertExists()
        compose.onNodeWithText("剩1天").assertExists()
        compose.runOnIdle {
            clock.set(clock.get().plusMinutes(1))
            compose.activity.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED))
        }
        compose.onNodeWithText("11月28日 09:00").assertExists()
        compose.onNodeWithText("加油！").assertExists()
    }
}
