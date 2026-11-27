package io.github.tbeimu.gongxiaozhu.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.academic.*
import io.github.tbeimu.gongxiaozhu.schedule.*
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UpcomingExamsDeviceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = LocalDateTime.of(2026, 11, 27, 12, 0)
    private val name = "虚构工程数学课程与综合应用考试（仅供布局检查）"
    private val location = "虚构教学楼三层东侧 301 教室（并非真实考试地点）"
    private fun fixture(): AcademicUiState = AcademicUiState(loading = false, data = AcademicData(exams = listOf(
        ExamRecord("next", fields = ExamFields(name, "2026-11-28 09:00", "2026-11-28 11:00", location)),
        ExamRecord("later", fields = ExamFields("另一场虚构考试", "2026-12-26 09:00")))))
    private fun timetable(): ScheduleUiState {
        val identity = ScheduleIdentity("fictional-exam-overview")
        val monday = now.toLocalDate().minusDays(4)
        return ScheduleUiState(loading = false, data = ScheduleData(identity = identity,
            complete = SemesterSchedule(identity, ScheduleTerm("2026-2027", "1", 1, 1),
                listOf(ScheduleWeek(1, (0L..6L).map { monday.plusDays(it).toString() }, emptyList())), 1)))
    }
    private fun preview(label: String) {
        if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getExternalFilesDir(null)!!.resolve("upcoming-exams-$label.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun readable(value: String) {
        compose.onNodeWithText(value, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
            val result = mutableListOf<TextLayoutResult>()
            assertTrue(get(result))
            val layout = result.single()
            assertFalse("Exam text must wrap without clipping: $value; size=${layout.size}; lines=${layout.lineCount}; " +
                "width=${layout.didOverflowWidth}; height=${layout.didOverflowHeight}", layout.hasVisualOverflow)
        }
    }

    @Test fun followsBrowsedDateAndRestoresTodayAfterWeekSwitch() {
        var opens = 0
        compose.setContent { GongXiaoZhuTheme { ScheduleHome(timetable(), Modifier, {}, {}, {}, {},
            todayProvider = { now.toLocalDate() }, nowProvider = { now.toLocalTime() },
            academic = fixture(), onOpenExams = { opens++ }, examNowProvider = { now }) } }
        compose.onNodeWithTag("upcoming_exam_next").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, opens)
        preview("today")
        compose.onNodeWithTag("agenda_day_6").performClick()
        compose.onNodeWithTag("upcoming_exams").performScrollTo()
        compose.onNodeWithText("12月26日 09:00 · 剩29天").assertExists()
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.onNodeWithTag("upcoming_exams").assertDoesNotExist()
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.onNodeWithText("今天 · 11月27日 · 第 1 周").assertExists()
    }

    @Test fun compactSummaryWrapsAt320dpAcrossThemesAndFontSizes() {
        val appearance = mutableStateOf(WallpaperPreferences(themeMode = AppThemeMode.LIGHT))
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                GongXiaoZhuTheme(appearance.value) { AppBackdrop(WallpaperState()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        ScheduleHome(timetable(), Modifier.width(320.dp), {}, {}, {}, {},
                            todayProvider = { now.toLocalDate() }, nowProvider = { now.toLocalTime() },
                            academic = fixture(), examNowProvider = { now })
                    }
                } }
            }
        }
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) for (scale in listOf(1f, 1.6f)) {
            compose.runOnIdle { appearance.value = appearance.value.copy(themeMode = mode); fontScale.value = scale }
            compose.onNodeWithTag("upcoming_exams").performScrollTo()
            compose.onNodeWithTag("agenda_scroll_2026-11-27").performTouchInput { swipeUp() }
            preview("${mode.name}-$scale")
            listOf(name, "11月28日 09:00 · 剩1天").forEach(::readable)
            compose.onNodeWithText("近30日考试").assertDoesNotExist()
            compose.onAllNodes(hasText("场考试", substring = true)).assertCountEquals(0)
            compose.onNodeWithText(location).assertDoesNotExist()
        }
    }

    @Test fun atMostThreeRowsAndTodayEncouragementAreVisible() {
        val exams = listOf(
            ExamRecord("today", fields = ExamFields("今天的虚构考试", "2026-11-27 11:00", "2026-11-27 13:00")),
            ExamRecord("next", fields = ExamFields("明天的虚构考试", "2026-11-28 09:00")),
            ExamRecord("third", fields = ExamFields("第三场虚构考试", "2026-11-29 09:00")),
            ExamRecord("fourth", fields = ExamFields("不应显示的第四场", "2026-11-30 09:00")))
        compose.setContent { GongXiaoZhuTheme { ScheduleHome(timetable(), Modifier, {}, {}, {}, {},
            todayProvider = { now.toLocalDate() }, nowProvider = { now.toLocalTime() },
            academic = AcademicUiState(loading = false, data = AcademicData(exams = exams)), examNowProvider = { now }) } }
        listOf("today", "next", "third").forEach { compose.onNodeWithTag("upcoming_exam_$it").assertExists() }
        compose.onNodeWithTag("upcoming_exam_fourth").assertDoesNotExist()
        compose.onNodeWithText("11月27日 11:00 · 加油！").assertExists()
        compose.onNodeWithText("11月28日 09:00 · 剩1天").assertExists()
    }

    @Test fun emptyAndBlockedSnapshotsDoNotClaimThereAreExams() {
        val academic = mutableStateOf(fixture())
        compose.setContent { GongXiaoZhuTheme { ScheduleHome(ScheduleUiState(loading = false), Modifier, {}, {}, {}, {},
            academic = academic.value, examNowProvider = { now }) } }
        compose.onNodeWithTag("upcoming_exams").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { academic.value = academic.value.copy(blocked = true) }
        compose.onNodeWithTag("upcoming_exams").assertDoesNotExist()
        compose.runOnIdle { academic.value = AcademicUiState(loading = false) }
        compose.onNodeWithTag("upcoming_exams").assertDoesNotExist()
    }
}
