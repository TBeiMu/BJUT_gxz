package io.github.tbeimu.gongxiaozhu.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.tbeimu.gongxiaozhu.schedule.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class CourseWeekdayUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)
    private fun schoolData(): ScheduleData {
        val identity = ScheduleIdentity("fictional-weekday-editor")
        val weeks = (1..2).map { week ->
            val dates = (0L..6L).map { monday.plusWeeks(week - 1L).plusDays(it).toString() }
            ScheduleWeek(week, dates, listOf(ScheduleCourse("fictional-course", "虚构课程", dates[0], listOf(1, 2))))
        }
        return ScheduleData(identity = identity, complete = SemesterSchedule(identity,
            ScheduleTerm("2026-2027", "1", 2, 1), weeks, 1))
    }
    private fun assertPlainWeekdayTitle() {
        compose.onNodeWithText("星期", substring = false).assertExists()
        for (hint in listOf("单选", "多选", "可多选")) compose.onNodeWithText(hint, substring = true).assertDoesNotExist()
    }
    @Test fun schoolWeekdaysToggleIndependentlyRestoreAndSaveEverySelectedDay() {
        val data = schoolData(); val context = data.courseEditor(data.complete!!.weeks[0].courses.single())
        var saved: CourseDraft? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GongXiaoZhuTheme { CourseEditor(context, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("course_day_3").performScrollTo().performClick()
        assertPlainWeekdayTitle()
        for (day in listOf(1, 3)) compose.onNodeWithTag("course_day_$day").assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        restoration.emulateSavedInstanceStateRestore()
        for (day in listOf(1, 3)) compose.onNodeWithTag("course_day_$day").assertIsOn()
        compose.onNodeWithTag("course_day_1").performScrollTo().performClick()
        compose.onNodeWithTag("course_day_3").performClick()
        compose.onNodeWithTag("save_course").assertIsNotEnabled()
        compose.onNodeWithText("请至少选择一个星期").assertExists()
        compose.onNodeWithTag("course_day_3").performClick()
        compose.onNodeWithTag("course_day_7").performClick()
        compose.onNodeWithTag("save_course").assertIsEnabled().performClick()
        assertEquals(listOf(3, 7), saved!!.weekdays)
        val result = data.editCourse(context, saved!!)
        assertTrue(result.complete!!.weeks.all { week ->
            week.courses.map { LocalDate.parse(it.date).dayOfWeek.value }.sorted() == listOf(3, 7)
        })
    }
    @Test fun newCustomCourseKeepsMultipleDaysWithoutSelectionHint() {
        val context = ScheduleData().courseEditor(weekday = 1)
        var saved: CourseDraft? = null
        compose.setContent { GongXiaoZhuTheme { CourseEditor(context, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("course_name").performTextInput("虚构自定义课程")
        compose.onNodeWithTag("course_day_3").performScrollTo().performClick()
        assertPlainWeekdayTitle()
        compose.onNodeWithTag("course_day_1").assertIsOn()
        compose.onNodeWithTag("course_day_3").assertIsOn()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals(listOf(1, 3), saved!!.weekdays)
    }
}
