package io.github.tbeimu.gongxiaozhu.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import io.github.tbeimu.gongxiaozhu.academic.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real-device layout checks, using only fictional data and inert callbacks. */
class AcademicDeviceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = LocalDateTime.of(2026, 11, 25, 12, 0)
    private val course = "示例工程数学课程（仅测试）"
    private val location = "示例教学楼三层 301 教室（虚构地点）"

    private fun fixture(): AcademicUiState {
        val school = SchoolExam("FICTIONAL-601", course, "虚构教学班 01",
            "2026-12-02 09:00", "2026-12-02 11:00", location)
        val updated = now.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        return AcademicUiState(loading = false, networkAvailable = true, networkLabel = "WiFi · 可手动刷新",
            data = AcademicData(identity = AcademicIdentity("fictional-student-2026", "示例同学"), revision = 17,
                examUpdatedAt = updated, exams = listOf(
                    ExamRecord("school", school, school.toExamFields().copy(note = "仅供界面检查的虚构备注")),
                    ExamRecord("past", fields = ExamFields("已结束的手动示例考试", "2026-11-24 09:00", "2026-11-24 11:00")),
                    ExamRecord("unset", fields = ExamFields("时间待通知的手动示例考试", location = "地点待通知"))),
                grades = GradeSnapshot(
                    degree = listOf(GradeRecord(course, "3", "2026-2027 秋", "优秀"),
                        GradeRecord("示例专业英语", "2", "2026-2027 秋", "学校未公布", restricted = true)),
                    elective = listOf(GradeRecord("示例科研方法", "1", "2025-2026 春", "合格")),
                    credits = mapOf("已修学分" to "20", "应修学分" to "26", "学位学分" to "18", "选修学分" to "8"),
                    updatedAt = updated)))
    }

    private fun render(state: () -> AcademicUiState = { fixture() }, grades: () -> Boolean = { false },
        fontScale: Float = 1f, width: Int = 393, height: Int = 780, actions: AcademicActions = AcademicActions(),
        clock: () -> LocalDateTime = { now }) {
        compose.setContent {
            val moment by rememberForegroundMoment(clock)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GongXiaoZhuTheme { AppBackdrop(WallpaperState()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = width.dp).fillMaxWidth().heightIn(max = height.dp).fillMaxHeight()) {
                            key(grades()) { AcademicContentBody(grades(), state(), actions, Modifier.weight(1f), moment) }
                        }
                    }
                } }
            }
        }
    }

    private fun reach(tag: String, matcher: SemanticsMatcher) {
        compose.onNodeWithTag(tag).performScrollToNode(matcher)
    }

    private fun readable(text: String, details: Boolean = false) {
        val node = if (details) compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("academic_details_body")), useUnmergedTree = true)
            else compose.onNodeWithText(text, useUnmergedTree = true)
        if (details) node.performScrollTo()
        node.assertIsDisplayed().performSemanticsAction(
            SemanticsActions.GetTextLayoutResult) { get ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(get(layouts))
            assertTrue(layouts.isNotEmpty())
            val layout = layouts.single()
            assertFalse("Text must remain fully laid out: $text; lines=${layout.lineCount}, size=${layout.size}, " +
                "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, " +
                "ellipsis=${(0 until layout.lineCount).any(layout::isLineEllipsized)}", layout.hasVisualOverflow)
        }
    }

    private fun preview(name: String) {
        if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") != "true") return
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getExternalFilesDir(null)?.resolve("academic-device-$name.png")?.outputStream()?.use {
            val target = if (compose.onAllNodesWithTag("exam_editor_overlay").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("exam_editor_overlay")
            else if (compose.onAllNodesWithTag("academic_details_overlay").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("academic_details_overlay") else compose.onRoot()
            target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun examsAndGradesStayReadableAcrossIntermediateCardTransparency() {
        val data = fixture()
        val showGrades = mutableStateOf(false)
        val wallpaper = WallpaperState().apply {
            image = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(20, 55, 100))
                android.graphics.Canvas(this).drawRect(100f, 0f, 200f, 400f, android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(245, 205, 125)
                })
            }.asImageBitmap()
            preferences = WallpaperPreferences(mode = WallpaperMode.PHOTO, photoBlurDp = 0, photoName = "wallpaper.jpg")
        }
        compose.setContent {
            GongXiaoZhuTheme(wallpaper.preferences) { AppBackdrop(wallpaper) {
                Column(Modifier.fillMaxSize()) {
                    key(showGrades.value) {
                        AcademicContentBody(showGrades.value, data, AcademicActions(), Modifier.weight(1f), now)
                    }
                }
            } }
        }
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            for (transparency in listOf(34, 66, 98, 100)) {
                for (grades in listOf(false, true)) {
                    compose.runOnIdle {
                        wallpaper.preferences = wallpaper.preferences.copy(themeMode = mode, cardOpacityPercent = 100 - transparency)
                        showGrades.value = grades
                    }
                    reach(if (grades) "academic_grades" else "academic_exams", hasText(course))
                    readable(course)
                    preview("transparency-${mode.name}-$transparency-${if (grades) "grades" else "exams"}")
                }
            }
        }
    }

    @Test fun examDetailsCloseOnBlankTapsButKeepContentGesturesAndEditingSafe() {
        var editStarts = 0
        var deletions = 0
        var restorations = 0
        render(actions = AcademicActions(beginEdit = { editStarts++ }, deleteExam = { _, _ -> deletions++ },
            restoreExam = { _, _ -> restorations++ }))
        fun open() {
            reach("academic_exams", hasTestTag("exam_school"))
            compose.onNodeWithTag("exam_school").performClick()
            compose.onNodeWithTag("academic_exam_details").assertIsDisplayed()
        }
        open()
        compose.onNodeWithTag("academic_details_body").performTouchInput { click(center) }
        compose.onNodeWithTag("academic_exam_details").assertIsDisplayed()
        compose.onNodeWithTag("academic_details_body").performTouchInput { swipeUp() }
        compose.onNodeWithTag("academic_exam_details").assertIsDisplayed()
        compose.onNodeWithTag("academic_details_overlay").performTouchInput {
            swipe(Offset(2f, centerY), Offset(2f, centerY - 100f))
        }
        compose.onNodeWithTag("academic_exam_details").assertIsDisplayed()
        preview("exam-details-outside-tap")
        for (edge in 0..3) {
            if (edge > 0) open()
            tapDetailsBlankArea(edge)
            compose.onNodeWithTag("academic_exam_details").assertDoesNotExist()
            compose.onNodeWithTag("exam_editor").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(0, editStarts); assertEquals(0, deletions); assertEquals(0, restorations) }
        open()
        compose.onNodeWithTag("edit_exam_school").performClick()
        compose.onNodeWithTag("academic_exam_details").assertDoesNotExist()
        compose.onNodeWithTag("exam_editor").assertIsDisplayed()
        compose.onNodeWithTag("exam_name").performTextReplacement("修改后的虚构考试")
        compose.onNodeWithTag("exam_editor_overlay").performTouchInput { click(Offset(2f, centerY)) }
        compose.onNodeWithTag("exam_editor").assertIsDisplayed()
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
        compose.onNodeWithTag("cancel_exam").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertIsDisplayed()
        compose.onNodeWithTag("discard_changes").performClick()
        compose.runOnIdle { assertEquals(1, editStarts); assertEquals(0, deletions); assertEquals(0, restorations) }
    }

    @Test fun gradeDetailsCloseOnBlankTapsButKeepContentGesturesAndCloseButtonWorking() {
        render(grades = { true })
        fun open() {
            reach("academic_grades", hasTestTag("grade_0_1"))
            compose.onNodeWithTag("grade_0_1").performClick()
            compose.onNodeWithTag("academic_grade_details").assertIsDisplayed()
        }
        open()
        compose.onNodeWithTag("academic_details_body").performTouchInput { click(center) }
        compose.onNodeWithTag("academic_grade_details").assertIsDisplayed()
        compose.onNodeWithTag("academic_details_body").performTouchInput { swipeUp() }
        compose.onNodeWithTag("academic_grade_details").assertIsDisplayed()
        compose.onNodeWithTag("academic_details_overlay").performTouchInput {
            swipe(Offset(2f, centerY), Offset(2f, centerY - 100f))
        }
        compose.onNodeWithTag("academic_grade_details").assertIsDisplayed()
        preview("grade-details-outside-tap")
        for (edge in 0..3) {
            if (edge > 0) open()
            tapDetailsBlankArea(edge)
            compose.onNodeWithTag("academic_grade_details").assertDoesNotExist()
        }
        open()
        compose.onNodeWithTag("academic_close_details").performClick()
        compose.onNodeWithTag("academic_grade_details").assertDoesNotExist()
    }

    private fun tapDetailsBlankArea(edge: Int) {
        compose.onNodeWithTag("academic_details_overlay").performTouchInput {
            click(when (edge) {
                0 -> Offset(2f, centerY)
                1 -> Offset(width - 2f, centerY)
                2 -> Offset(centerX, 2f)
                else -> Offset(centerX, height - 2f)
            })
        }
    }

    @Test fun unifiedExamsKeepSchoolFieldsUnknownTimesAndFinishedCardsReachable() {
        render()
        compose.onNodeWithTag("academic_identity").assertDoesNotExist()
        compose.onNodeWithTag("academic_refresh").assertIsDisplayed().assertIsEnabled()
        preview("exams-header")
        listOf("ALL", "UPCOMING", "FINISHED", "UNSET").forEach { compose.onNodeWithTag("exam_filter_$it").assertDoesNotExist() }
        reach("academic_exams", hasText(course))
        readable(course)
        reach("academic_exams", hasText(location))
        readable(location)
        compose.onNodeWithTag("exam_school").performClick()
        compose.onNodeWithTag("edit_exam_school").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("delete_exam_school").assertIsDisplayed().assertTextEquals("隐藏")
        readable("FICTIONAL-601", details = true)
        readable("虚构教学班 01", details = true)
        preview("exams-data")
        compose.onNodeWithTag("academic_close_details").performClick()
        reach("academic_exams", hasTestTag("exam_past"))
        compose.onNodeWithText("已结束的手动示例考试").assertIsDisplayed()
        compose.onNodeWithText("已结束").assertIsDisplayed()
        compose.onNodeWithTag("exam_past").performClick()
        readable("2026-11-24 09:00", details = true)
        readable("2026-11-24 11:00", details = true)
        compose.onNodeWithTag("academic_close_details").performClick()
        reach("academic_exams", hasTestTag("exam_unset"))
        compose.onNodeWithText("时间待通知的手动示例考试").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("exam_unset").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("exam_past").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun finishedExamStaysGrayReadableAndDatedAcrossThemesTransparencyAndLargeFont() {
        var appearance by mutableStateOf(WallpaperPreferences(themeMode = AppThemeMode.LIGHT))
        var fontScale by mutableStateOf(1f)
        var gray = Color.Unspecified
        val past = fixture().data.exams.single { it.id == "past" }
        val wallpaper = WallpaperState().apply {
            image = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(70, 105, 140)) }.asImageBitmap()
            preferences = appearance.copy(mode = WallpaperMode.PHOTO, photoBlurDp = 0, photoName = "wallpaper.jpg")
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GongXiaoZhuTheme(appearance) {
                    gray = gxz.inkSoft
                    AppBackdrop(wallpaper) {
                        Column(Modifier.widthIn(max = 320.dp).fillMaxSize()) {
                            AcademicContentBody(false, AcademicUiState(loading = false, data = AcademicData(exams = listOf(past))),
                                AcademicActions(), Modifier.weight(1f), now)
                        }
                    }
                }
            }
        }
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) for (opacity in listOf(100, 0)) for (scale in listOf(1f, 2f)) {
            compose.runOnIdle {
                appearance = appearance.copy(themeMode = mode, cardOpacityPercent = opacity)
                wallpaper.preferences = wallpaper.preferences.copy(themeMode = mode, cardOpacityPercent = opacity)
                fontScale = scale
            }
            val status = if (scale == 2f) "11月24日 · 已结束" else "已结束"
            reach("academic_exams", hasText(status))
            readable(status)
            preview("finished-${mode.name}-$opacity-$scale")
            val texts = compose.onAllNodes(hasAnyAncestor(hasTestTag("exam_past")) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            for (index in texts.fetchSemanticsNodes().indices) texts[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(get(layouts))
                layouts.forEach {
                    assertEquals(gray, it.layoutInput.style.color)
                    val context = "${it.layoutInput.text.text}; theme=$mode, opacity=$opacity, font=$scale"
                    assertFalse(context, it.didOverflowHeight)
                    // A badge may retain a wider paragraph container; check the actual text lines.
                    for (line in 0 until it.lineCount) {
                        assertFalse(context, it.isLineEllipsized(line))
                        assertTrue(context, it.getLineLeft(line) >= -1f && it.getLineRight(line) <= it.size.width + 1f)
                    }
                }
            }
        }
    }

    @Test fun examClockUpdatesReorderAndFinishOnMinuteAndForeground() {
        val clock = AtomicReference(now)
        val exams = listOf(ExamRecord("finishing", fields = ExamFields("正在结束的虚构考试", "2026-11-25 10:00", "2026-11-25 12:00")),
            ExamRecord("future", fields = ExamFields("未来的虚构考试", "2026-11-26 10:00", "2026-11-26 12:00")))
        render(state = { AcademicUiState(loading = false, data = AcademicData(exams = exams)) }, clock = clock::get)
        compose.onNodeWithText("已结束").assertDoesNotExist()
        compose.runOnIdle { clock.set(now.plusMinutes(1)) }
        // TIME_TICK is protected on real devices; wait for the actual system minute broadcast.
        compose.waitUntil(65_000) { compose.onAllNodesWithText("已结束").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("已结束").assertExists()
        assertTrue(compose.onNodeWithTag("exam_future").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("exam_finishing").fetchSemanticsNode().boundsInRoot.top)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        clock.set(now.plusDays(2))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onAllNodesWithText("已结束").assertCountEquals(2)
    }

    @Test fun loadedGradesKeepRawResultsCreditSummaryAndTermSelection() {
        render(grades = { true })
        preview("grades-header")
        listOf("已修学分", "应修学分", "学位学分", "选修学分").forEach { label ->
            reach("academic_grades", hasText(label))
            readable(label)
        }
        reach("academic_grades", hasTestTag("grade_0_0"))
        readable(course)
        compose.onNodeWithText("优秀").assertIsDisplayed()
        preview("grades-data")
        reach("academic_grades", hasTestTag("grade_0_1"))
        compose.onNodeWithText("学校未公布").assertIsDisplayed()
        compose.onNodeWithTag("grade_0_1").performClick()
        readable("成绩受限 · 以学校显示为准", details = true)
        compose.onNodeWithTag("academic_close_details").performClick()
        reach("academic_grades", hasTestTag("grade_term_filter"))
        compose.onNodeWithTag("grade_term_filter").performClick()
        compose.onNodeWithTag("grade_term_1").performClick()
        compose.onNodeWithTag("grade_term_filter").assertTextEquals("2025-2026 春")
        reach("academic_grades", hasTestTag("grade_1_0"))
        readable("示例科研方法")
        compose.onNodeWithText("合格").assertIsDisplayed()
        compose.onNodeWithTag("grade_0_0").assertDoesNotExist()
    }

    @Test fun narrowWindowAtDoubleFontKeepsExamAndGradeDetailsAndActionsReachable() {
        var grades by mutableStateOf(false)
        render(grades = { grades }, fontScale = 2f, width = 320, height = 600)
        compose.onNodeWithTag("academic_refresh").assertIsDisplayed().assertIsEnabled()
        preview("narrow-exams-header")
        reach("academic_exams", hasTestTag("exam_school"))
        compose.onNodeWithTag("exam_school").performClick()
        listOf(course, "2026-12-02 09:00", "2026-12-02 11:00", location, "虚构教学班 01").forEach { value ->
            readable(value, details = true)
        }
        compose.onNodeWithTag("edit_exam_school").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("delete_exam_school").assertIsDisplayed().assertHasClickAction()
        preview("narrow-exams-data")
        compose.onNodeWithTag("academic_close_details").performClick()
        compose.runOnIdle { grades = true }
        reach("academic_grades", hasText("已修学分"))
        readable("已修学分")
        preview("narrow-grades-header")
        listOf(course, "优秀").forEach { value ->
            reach("academic_grades", hasText(value))
            readable(value)
        }
        reach("academic_grades", hasTestTag("grade_0_1"))
        compose.onNodeWithTag("grade_0_1").performClick()
        readable("成绩受限 · 以学校显示为准", details = true)
        compose.onNodeWithTag("academic_close_details").performClick()
        preview("narrow-grades-data")
    }

    @Test fun landscapeAtLargeFontKeepsEditorActionsFixedWhileAllFieldsScroll() {
        val previous = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) { GongXiaoZhuTheme {
                    AcademicExamEditor(fixture().data.exams.first(), 17, false, 0, null, {}, { _, _, _ -> })
                } }
            }
            val save = compose.onNodeWithTag("save_exam").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val cancel = compose.onNodeWithTag("cancel_exam").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("save_exam").assertIsNotEnabled()
            preview("landscape-editor-header")
            listOf("exam_name", "exam_start", "exam_end", "exam_location", "exam_note").forEach { field ->
                compose.onNodeWithTag(field).performScrollTo().assertIsDisplayed()
                assertEquals(save, compose.onNodeWithTag("save_exam").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
                assertEquals(cancel, compose.onNodeWithTag("cancel_exam").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
            }
            preview("landscape-editor-fields")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previous }
        }
    }

    @Test fun editedDraftIgnoresOutsideTapAndRequiresExplicitDiscardConfirmation() {
        var open by mutableStateOf(true)
        var saves = 0
        compose.setContent { GongXiaoZhuTheme {
            if (open) AcademicExamEditor(null, 9, false, 0, null, { open = false }, { _, _, _ -> saves++ })
            else Text("编辑已关闭")
        } }
        compose.onNodeWithTag("exam_name").performTextInput("虚构的未保存考试")
        compose.onNodeWithTag("exam_editor_overlay").performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("exam_editor").assertExists()
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
        compose.onNodeWithTag("cancel_exam").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertIsDisplayed()
        compose.onNodeWithTag("exam_editor").assertDoesNotExist()
        compose.onNodeWithTag("continue_editing").performClick()
        compose.onNodeWithTag("exam_name").assertTextContains("虚构的未保存考试")
        compose.onNodeWithTag("cancel_exam").performClick()
        compose.onNodeWithTag("discard_changes").performClick()
        compose.onNodeWithText("编辑已关闭").assertIsDisplayed()
        compose.runOnIdle { assertFalse(open); assertEquals(0, saves) }
    }

    @Test fun landscapeGraduateLoginKeepsActionsReachableWithLargeFontAndKeyboard() {
        val previous = compose.activity.requestedOrientation
        val bytes = ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(90, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            var draft by mutableStateOf(AcademicLoginDraft("fictional-student", "fictional-password"))
            var busy by mutableStateOf(false)
            var submissions = 0
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { GongXiaoZhuTheme {
                    val page = remember { AcademicLoginPage("fictional-device-page", bytes) }
                    AcademicLoginForm(page, busy, null, {}, {}, { _, _, _, _ -> submissions++; busy = true },
                        draft, { draft = it }, actionLabel = "刷新成绩")
                } }
            }
            compose.onNodeWithTag("academic_submit").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithTag("academic_remember_password").performScrollTo().performClick().assertIsOn()
            compose.onNodeWithTag("academic_captcha").performScrollTo().performClick().performTextInput("1234")
            compose.onNodeWithTag("academic_cancel_login").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("academic_submit").assertIsDisplayed().assertIsEnabled()
            compose.runOnIdle { assertEquals(0, submissions) }
            compose.onNodeWithTag("academic_captcha").performImeAction()
            compose.runOnIdle { assertEquals(1, submissions) }
            compose.onNodeWithTag("academic_submit").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithTag("academic_cancel_login").assertIsDisplayed().assertIsEnabled()
        } finally { compose.activityRule.scenario.onActivity { it.requestedOrientation = previous } }
    }

    @Test fun emptyOfflineAndStorageFailureStatesKeepTheirMeaningAndSafeActions() {
        var state by mutableStateOf(AcademicUiState(loading = false))
        var grades by mutableStateOf(false)
        var retries = 0
        render(state = { state }, grades = { grades }, actions = AcademicActions(reload = { retries++ }))
        compose.onNodeWithTag("academic_refresh").assertIsNotEnabled()
        compose.onNodeWithText("离线").assertExists()
        compose.onNodeWithTag("academic_add").performClick()
        compose.onNodeWithTag("academic_import_graduate").assertIsNotEnabled()
        compose.onNodeWithTag("academic_add_exam").assertIsEnabled().performClick()
        compose.onNodeWithTag("cancel_exam").performClick()
        reach("academic_exams", hasText("还没有考试安排"))
        readable("还没有考试安排")
        preview("empty-offline-exams")
        compose.runOnIdle { grades = true }
        reach("academic_grades", hasText("成绩等待导入"))
        readable("成绩等待导入")
        compose.runOnIdle {
            state = state.copy(blocked = true, message = AcademicProblem.STORAGE.hint)
        }
        reach("academic_grades", hasText("教务资料暂不可用，原资料未被清除。"))
        readable("教务资料暂不可用，原资料未被清除。")
        compose.onNodeWithText("重新读取教务资料").assertIsEnabled().performClick()
        compose.onNodeWithTag("academic_refresh").assertIsNotEnabled()
        compose.onNodeWithTag("academic_add_exam").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries) }
        preview("storage-failure")
    }
}
