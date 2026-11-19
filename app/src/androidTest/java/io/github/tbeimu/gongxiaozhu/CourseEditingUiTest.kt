package io.github.tbeimu.gongxiaozhu

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** The real UI and Keystore, with fictional courses and no school transport. */
class CourseEditingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 9)
    private val identity = ScheduleIdentity("fictional-course-editor")
    private fun schoolData(weekCount: Int = 2, occupied: IntRange = 1..weekCount, lessons: List<Int> = listOf(1, 2)) =
        ScheduleData(identity = identity, complete = SemesterSchedule(identity,
        ScheduleTerm("2026-2027", "1", weekCount, 1), (1..weekCount).map { w ->
            val dates = (0L..6L).map { monday.plusWeeks(w - 1L).plusDays(it).toString() }
            ScheduleWeek(w, dates, if (w in occupied) listOf(ScheduleCourse("fictional-course", "测试课程", dates[0], lessons)) else emptyList())
        }, 1))
    @Test fun plusOpensOnlyMenuAndGraduateOptionUsesExistingCallback() {
        var requests = 0
        var addedWeek = 0
        var blocked = false
        compose.setContent { GongXiaoZhuTheme {
            ScheduleHome(ScheduleUiState(data = schoolData(), loading = false), Modifier, { requests++ }, {}, {}, {},
                todayProvider = { monday }, onDialogVisibilityChanged = { blocked = it },
                onAddCourse = { week, _ -> addedWeek = week })
        } }
        compose.onNodeWithTag("import_schedule").performClick()
        compose.onNodeWithTag("import_undergraduate").assertIsNotEnabled()
        assertEquals(0, requests); assertTrue(blocked)
        val anchor = compose.onNodeWithTag("import_schedule").fetchSemanticsNode()
        val menu = compose.onNodeWithTag("schedule_add_menu").fetchSemanticsNode()
        assertTrue("菜单应位于添加按钮下方", menu.layoutInfo.coordinates.localToScreen(Offset.Zero).y >=
            anchor.layoutInfo.coordinates.localToScreen(Offset.Zero).y + anchor.size.height - 2f)
        capture("schedule_add_menu", "add-menu")
        compose.onNodeWithTag("import_graduate").performClick()
        assertEquals(1, requests)
        compose.onNodeWithTag("schedule_add_menu").assertDoesNotExist()
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.onNodeWithTag("import_schedule").performClick()
        compose.onNodeWithTag("add_custom_course").performClick()
        assertEquals(1, addedWeek); assertEquals(1, requests)
    }
    @Test fun emptyScreenMenuCanCreateLocalCourseWithoutSchoolIdentityAndRestoreDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = createScheduleRepository(context)
        repository.clear(); repository.load()
        val data = mutableStateOf(repository.snapshot())
        val editor = mutableStateOf<CourseEditContext?>(null)
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { GongXiaoZhuTheme {
                ScheduleHome(ScheduleUiState(data = data.value, loading = false), Modifier, { error("No school request") }, {}, {}, {},
                    todayProvider = { monday }, titleUnobstructed = editor.value == null,
                    onAddCourse = { week, day -> editor.value = data.value.courseEditor(week = week, weekday = day) })
                editor.value?.let { edit -> CourseEditor(edit, false, null, { editor.value = null }, {
                    data.value = repository.edit(edit, it); editor.value = null
                }) }
            } }
            compose.onNodeWithText("添加课表", substring = false).performClick()
            compose.onNodeWithTag("add_custom_course").performClick()
            compose.onNodeWithTag("save_course").assertIsNotEnabled()
            compose.onNodeWithTag("course_name").performTextInput("离线实验")
            compose.onNodeWithTag("course_location").performTextInput("测试教室")
            compose.onNodeWithTag("course_day_3").performScrollTo().performClick()
            compose.onNodeWithTag("course_day_1").assertIsSelected()
            compose.onNodeWithTag("course_day_3").assertIsSelected()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("course_name").assertTextContains("离线实验")
            compose.onNodeWithTag("course_location").assertTextContains("测试教室")
            compose.onNodeWithTag("course_day_1").assertIsSelected()
            compose.onNodeWithTag("course_day_3").assertIsSelected()
            compose.onNodeWithTag("term_weeks").performScrollTo().performTextReplacement("61")
            compose.onNodeWithTag("save_course").assertIsNotEnabled()
            compose.onNodeWithTag("term_weeks").performTextReplacement("20")
            capture("course_editor", "editor")
            compose.onNodeWithTag("save_course").performClick()
            assertNull(repository.load().identity)
            assertEquals("离线实验", repository.snapshot().localTerms.single().courses.single().name)
            assertEquals(listOf(1, 3), repository.snapshot().localTerms.single().courses.single().occurrences.map { it.weekday })
            compose.onNodeWithTag("schedule_mode").assertExists().performClick()
            compose.onNodeWithTag("schedule_pager").assertExists()
        } finally { repository.clear() }
    }
    @Test fun detailsAllowEditingSchoolCourseAndDeletingSelectedOccurrence() {
        val data = mutableStateOf(schoolData())
        val editor = mutableStateOf<CourseEditContext?>(null)
        compose.setContent { GongXiaoZhuTheme {
            ScheduleHome(ScheduleUiState(data = data.value, loading = false), Modifier, {}, {}, {}, {}, todayProvider = { monday },
                titleUnobstructed = editor.value == null,
                onEditCourse = { course, delete -> editor.value = data.value.courseEditor(course, deleteOnly = delete) })
            editor.value?.let { edit -> CourseEditor(edit, false, null, { editor.value = null }, {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val repo = createScheduleRepository(context)
                repo.clear(); repo.load(); repo.begin(identity, data.value.complete!!.term)
                data.value.complete!!.weeks.forEach(repo::append); repo.finish(1)
                val current = repo.snapshot().courseEditor(edit.course, deleteOnly = edit.deleteOnly)
                data.value = repo.edit(current, it); editor.value = null; repo.clear()
            }) }
        } }
        compose.onNodeWithTag("agenda_course_0_1").performClick()
        compose.onNodeWithText("课程详情").assertDoesNotExist()
        val editBounds = compose.onNodeWithTag("course_detail_edit_0").fetchSemanticsNode().boundsInRoot
        val deleteBounds = compose.onNodeWithTag("course_detail_delete_0").fetchSemanticsNode().boundsInRoot
        val closeBounds = compose.onNodeWithTag("course_detail_close").fetchSemanticsNode().boundsInRoot
        assertEquals(editBounds.center.y, deleteBounds.center.y, 1f)
        assertEquals(editBounds.center.y, closeBounds.center.y, 1f)
        assertTrue(closeBounds.left >= deleteBounds.right)
        capture("course_details", "details")
        compose.onNodeWithText("编辑", substring = false).performClick()
        compose.onNodeWithTag("course_name").performTextReplacement("临时调课")
        compose.onNodeWithTag("course_day_3").performScrollTo().performClick()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals("临时调课", data.value.complete!!.weeks[0].courses.single().name)
        assertEquals("临时调课", data.value.complete!!.weeks[1].courses.single().name)
        compose.onNodeWithTag("agenda_day_2").performClick()
        compose.onNodeWithTag("agenda_course_2_1").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.onNodeWithTag("save_course").performClick()
        assertTrue(data.value.complete!!.weeks[0].courses.isEmpty())
        assertEquals(1, data.value.complete!!.weeks[1].courses.size)
    }
    @Test fun largeFontEditorKeepsSaveVisibleAndAllowsReverseRangeAndTapDeselection() {
        val data = ScheduleData(localTerms = listOf(LocalScheduleTerm(
            "large-font-term", monday.toString(), 2, courses = listOf(CustomCourse("large-font-course", "大字课程",
                occurrences = listOf(CustomOccurrence(1, 1, listOf(1, 2))))))), selectedLocalTermId = "large-font-term")
        val context = data.courseEditor(data.display()!!.weeks[0].courses.single())
        compose.setContent { GongXiaoZhuTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                Box(Modifier.width(360.dp).height(580.dp)) { CourseEditor(context, false, null, {}, {}) }
            }
        } }
        compose.onNodeWithTag("save_course").assertIsDisplayed()
        compose.onNodeWithTag("course_lesson_12").performScrollTo().performClick()
        compose.onNodeWithTag("course_lesson_hint").assertTextEquals("已选第 12 节，再点结束节次")
        compose.onNodeWithTag("save_course").assertIsEnabled()
        compose.onNodeWithTag("course_lesson_1").performScrollTo().performClick()
        compose.onNodeWithTag("course_lesson_12").assertIsSelected()
        compose.onNodeWithTag("course_lesson_12").performScrollTo().performClick()
        compose.onNodeWithTag("course_lesson_12").assertIsNotSelected()
        compose.onNodeWithTag("course_lesson_1").assertIsSelected()
        compose.onNodeWithTag("course_lesson_13").assertDoesNotExist()
        compose.onNodeWithTag("save_course").assertIsEnabled()
        val selectedDay = context.defaultWeekday
        compose.onNodeWithTag("course_day_$selectedDay").performScrollTo().performClick()
        compose.onNodeWithTag("save_course").assertIsNotEnabled()
        compose.onNodeWithTag("course_day_$selectedDay").performClick()
        val secondDay = if (selectedDay == 7) 1 else selectedDay + 1
        compose.onNodeWithTag("course_day_$secondDay").performClick()
        compose.onNodeWithTag("course_day_$selectedDay").assertIsSelected()
        compose.onNodeWithTag("course_day_$secondDay").assertIsSelected()
        compose.onNodeWithTag("save_course").assertIsEnabled()
        capture("course_editor", "editor-large")
    }
    @Test fun weekPresetsAndTermShrinkKeepValidSelection() {
        var saved: CourseDraft? = null
        val edit = ScheduleData().courseEditor()
        compose.setContent { GongXiaoZhuTheme {
            CourseEditor(edit, false, null, {}, { saved = it })
        } }
        compose.onNodeWithTag("course_name").performTextInput("周次选择测试")
        compose.onNodeWithTag("target_week_even").performScrollTo().performClick()
        compose.onNodeWithTag("target_week_1").assertIsNotSelected()
        compose.onNodeWithTag("target_week_2").assertIsSelected()
        // A partially entered 30 must not discard selections above the intermediate digit 3.
        compose.onNodeWithTag("term_weeks").performScrollTo().performTextReplacement("3")
        compose.onNodeWithTag("term_weeks").performTextReplacement("30")
        compose.onNodeWithTag("term_weeks").performImeAction()
        compose.onNodeWithTag("target_week_20").assertIsSelected()
        compose.onNodeWithTag("term_weeks").performScrollTo().performTextReplacement("3")
        compose.onNodeWithTag("term_weeks").performImeAction()
        compose.onNodeWithTag("target_week_4").assertDoesNotExist()
        compose.onNodeWithTag("target_week_2").assertIsSelected()
        compose.onNodeWithTag("save_course").assertIsEnabled()
        compose.onNodeWithTag("target_week_clear").performScrollTo().performClick()
        compose.onNodeWithTag("save_course").assertIsNotEnabled()
        compose.onNodeWithText("请至少选择一个上课周次").assertExists()
        compose.onNodeWithTag("target_week_odd").performScrollTo().performClick()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals(listOf(1, 3), saved!!.targetWeeks)
        assertEquals(3, saved!!.weekCount)
    }
    @Test fun editingShowsAllOriginalWeeksAndLessonsWithOneEditableWeekSelection() {
        var saved: CourseDraft? = null
        val data = schoolData()
        val edit = data.courseEditor(data.complete!!.weeks[0].courses.single())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GongXiaoZhuTheme { CourseEditor(edit, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("course_original_schedule").assertTextEquals("原有安排：第 1–2 周 · 周一 · 第 1–2 节")
        compose.onNodeWithTag("source_week_all").assertDoesNotExist()
        compose.onNodeWithTag("target_week_1").assertIsSelected()
        compose.onNodeWithTag("target_week_2").assertIsSelected()
        compose.onNodeWithTag("target_week_1").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("target_week_1").assertIsNotSelected()
        compose.onNodeWithTag("target_week_2").assertIsSelected()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals(listOf(1, 2), saved!!.sourceWeeks)
        assertEquals(listOf(2), saved!!.targetWeeks)
    }
    @Test fun fullOriginalWeeksAreSelectedAndDraggingOnlyAddsWhileTapsRemove() {
        var saved: CourseDraft? = null
        val data = schoolData(20, 10..17, (9..12).toList())
        val edit = data.courseEditor(data.complete!!.weeks[12].courses.single())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GongXiaoZhuTheme { CourseEditor(edit, false, null, {}, { saved = it }) } }
        (9..12).forEach { compose.onNodeWithTag("course_lesson_$it").assertIsSelected() }
        (1..20).forEach { week ->
            if (week in 10..17) compose.onNodeWithTag("target_week_$week").assertIsSelected()
            else compose.onNodeWithTag("target_week_$week").assertIsNotSelected()
        }
        compose.onNodeWithTag("target_week_10").performScrollTo()
        capture("course_editor", "editor-original-weeks")
        dragChoice("target_week_10", "target_week_12")
        (10..12).forEach { compose.onNodeWithTag("target_week_$it").assertIsSelected() }
        (10..12).forEach { compose.onNodeWithTag("target_week_$it").performScrollTo().performClick() }
        (13..17).forEach { compose.onNodeWithTag("target_week_$it").assertIsSelected() }
        dragChoice("target_week_8", "target_week_10")
        (15..17).forEach { compose.onNodeWithTag("target_week_$it").performScrollTo().performClick() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals((10..17).toList(), saved!!.sourceWeeks)
        assertEquals(listOf(8, 9, 10, 13, 14), saved!!.targetWeeks)
    }
    @Test fun lessonDraggingOnlyAddsAndSelectedLessonsAreRemovedByTaps() {
        var saved: CourseDraft? = null
        val data = schoolData(20, 10..17, (9..12).toList())
        val edit = data.courseEditor(data.complete!!.weeks[12].courses.single())
        compose.setContent { GongXiaoZhuTheme { CourseEditor(edit, false, null, {}, { saved = it }) } }
        dragChoice("course_lesson_12", "course_lesson_10")
        (9..12).forEach { compose.onNodeWithTag("course_lesson_$it").assertIsSelected() }
        (10..12).forEach { compose.onNodeWithTag("course_lesson_$it").performScrollTo().performClick() }
        compose.onNodeWithTag("course_lesson_9").assertIsSelected()
        (10..12).forEach { compose.onNodeWithTag("course_lesson_$it").assertIsNotSelected() }
        compose.onNodeWithTag("course_lesson_hint").assertDoesNotExist()
        dragChoice("course_lesson_6", "course_lesson_8")
        compose.onNodeWithTag("save_course").performClick()
        assertEquals((6..9).toList(), saved!!.lessons)
        (6..9).forEach { compose.onNodeWithTag("course_lesson_$it").performScrollTo().performClick() }
        compose.onNodeWithTag("save_course").assertIsNotEnabled()
        compose.onNodeWithText("请至少选择一个节次").assertExists()
    }
    private fun dragChoice(fromTag: String, toTag: String) {
        val from = compose.onNodeWithTag(fromTag)
        val to = compose.onNodeWithTag(toTag)
        from.performScrollTo()
        to.performScrollTo()
        from.assertIsDisplayed(); to.assertIsDisplayed()
        val offset = to.fetchSemanticsNode().boundsInRoot.center - from.fetchSemanticsNode().boundsInRoot.center
        from.performTouchInput { swipe(center, center + offset, durationMillis = 450) }
    }
    @Test fun originalLessonsAndPendingRangeSurviveRestoration() {
        var saved: CourseDraft? = null
        val original = schoolData()
        val data = original.copy(complete = original.complete!!.copy(weeks = original.complete.weeks.map { week ->
            week.copy(courses = week.courses.map { it.copy(lessons = listOf(1, 3)) })
        }))
        val edit = data.courseEditor(data.complete!!.weeks[0].courses.single())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GongXiaoZhuTheme { CourseEditor(edit, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("course_lesson_hint").assertDoesNotExist()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals(listOf(1, 3), saved!!.lessons)
        compose.onNodeWithTag("course_lesson_7").performScrollTo().performClick()
        compose.onNodeWithTag("course_lesson_hint").assertTextEquals("已选第 7 节，再点结束节次")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("course_lesson_5").performScrollTo().performClick()
        compose.onNodeWithTag("course_lesson_clock").assertTextEquals("13:30–16:10")
        compose.onNodeWithTag("save_course").performClick()
        assertEquals(listOf(5, 6, 7), saved!!.lessons)
        capture("course_editor", "editor-range")
    }
    @Test fun importedLessonsAboveTwelveAreShownAndMustBeReplacedBeforeMoving() {
        var saved: CourseDraft? = null
        val original = schoolData()
        val data = original.copy(complete = original.complete!!.copy(weeks = original.complete.weeks.map { week ->
            week.copy(courses = week.courses.map { it.copy(lessons = listOf(13, 14)) })
        }))
        val edit = data.courseEditor(data.complete!!.weeks[0].courses.single())
        compose.setContent { GongXiaoZhuTheme { CourseEditor(edit, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("course_lesson_13").assertDoesNotExist()
        compose.onNodeWithTag("course_original_schedule").assertTextEquals("原有安排：第 1–2 周 · 周一 · 第 13–14 节")
        compose.onNodeWithTag("save_course").assertIsEnabled()
        compose.onNodeWithTag("course_day_3").performScrollTo().performClick()
        compose.onNodeWithTag("save_course").assertIsNotEnabled()
        compose.onNodeWithTag("course_lesson_1").performScrollTo().performClick()
        compose.onNodeWithTag("save_course").assertIsEnabled()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals(listOf(1), saved!!.lessons)
    }
    @Test fun savingDisablesActionsAndFailureKeepsDraftForRetry() {
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var requests = 0
        val edit = ScheduleData().courseEditor()
        compose.setContent { GongXiaoZhuTheme {
            CourseEditor(edit, saving, error, {}, { requests++; saving = true })
        } }
        compose.onNodeWithTag("course_name").performTextInput("保存失败测试")
        compose.onNodeWithTag("save_course").performClick()
        compose.onNodeWithTag("save_course").assertIsNotEnabled()
        compose.onNodeWithTag("cancel_course").assertIsNotEnabled()
        compose.onNodeWithTag("course_name").assertIsNotEnabled()
        compose.onNodeWithTag("course_lesson_1").assertIsNotEnabled()
        compose.runOnIdle { saving = false; error = "保存未完成，原课表保留，请重试" }
        compose.onNodeWithTag("course_name").assertTextContains("保存失败测试")
        compose.onNodeWithTag("save_course").assertIsEnabled()
        compose.onNodeWithTag("course_save_error").performScrollTo().assertIsDisplayed()
        assertEquals(1, requests)
    }
    @Test fun keyboardKeepsActionsVisibleAndNextDoneUseDialogFocus() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            val edit = schoolData().courseEditor()
            compose.setContent { GongXiaoZhuTheme { CourseEditor(edit, false, null, {}, {}) } }
            compose.onNodeWithTag("course_name").performClick().performTextInput("现代设计方法")
            compose.waitUntil(5000) { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            try {
                compose.waitUntil(5000) {
                    val ime = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    if (ime == null) false else {
                        val imeBounds = android.graphics.Rect().also(ime::getBoundsInScreen)
                        val save = compose.onNodeWithTag("save_course").fetchSemanticsNode()
                        save.boundsInRoot.height > 0 && save.layoutInfo.coordinates.localToScreen(Offset.Zero).y + save.size.height <= imeBounds.top + 1
                    }
                }
            } finally {
                captureScreen("editor-keyboard")
            }
            compose.onNodeWithTag("save_course").assertIsDisplayed()
            compose.onNodeWithTag("cancel_course").assertIsDisplayed()
            compose.onNodeWithTag("course_name").assertIsDisplayed()
            assertTrue("键盘显示时正文至少保留一行可操作空间",
                compose.onNodeWithTag("course_editor_body").fetchSemanticsNode().boundsInRoot.height >= with(compose.density) { 48.dp.toPx() })
            compose.onNodeWithTag("course_name").performImeAction()
            compose.onNodeWithTag("course_location").assertIsFocused()
            compose.onNodeWithTag("course_location").performImeAction()
            compose.onNodeWithTag("course_teacher").assertIsFocused()
            compose.onNodeWithTag("course_teacher").performImeAction()
            compose.waitUntil(5000) { automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            compose.onNodeWithTag("course_name").performScrollTo()
            capture("course_editor", "editor-add")
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }
    @Test fun sixtyWeeksStayReachableAndScrollingKeepsActionsFixed() {
        val edit = ScheduleData().courseEditor().copy(weekCount = 60)
        compose.setContent { GongXiaoZhuTheme { CourseEditor(edit, false, null, {}, {}) } }
        val original = compose.onNodeWithTag("course_editor_actions").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("target_week_60").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("target_week_all").performScrollTo().performClick()
        compose.onNodeWithTag("target_week_60").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("save_course").assertIsDisplayed()
        assertEquals(original, compose.onNodeWithTag("course_editor_actions").fetchSemanticsNode().boundsInRoot)
        capture("course_editor", "editor-sixty-weeks")
        compose.onNodeWithTag("course_name").performScrollTo()
        compose.onNodeWithTag("course_name").assertIsDisplayed()
        assertEquals(original, compose.onNodeWithTag("course_editor_actions").fetchSemanticsNode().boundsInRoot)
        capture("course_editor", "editor-first-term")
    }
    @Test fun overlappingDetailsCloseFromTheRightWithLargeFonts() {
        var visible by mutableStateOf(true)
        var edited: ScheduleCourse? = null
        val courses = schoolData().complete!!.weeks[0].courses.let { listOf(it.single(), it.single().copy(id = "second", name = "重叠测试课")) }
        compose.setContent { GongXiaoZhuTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                if (visible) CourseDetails(courses, emptyMap(), editable = true, onEdit = { course, _ -> edited = course }, onDismiss = { visible = false })
            }
        } }
        compose.onNodeWithText("课程详情").assertDoesNotExist()
        compose.onNodeWithText("重叠课程 · 2 门").assertDoesNotExist()
        compose.onNodeWithTag("course_detail_edit_0").performScrollTo().performClick()
        assertEquals(courses.first(), edited)
        compose.onNodeWithTag("course_detail_close").performScrollTo().assertIsDisplayed()
        val deleteBounds = compose.onNodeWithTag("course_detail_delete_1").fetchSemanticsNode().boundsInRoot
        val closeBounds = compose.onNodeWithTag("course_detail_close").fetchSemanticsNode().boundsInRoot
        assertEquals(deleteBounds.center.y, closeBounds.center.y, 1f)
        assertTrue(closeBounds.left >= deleteBounds.right)
        capture("course_details", "details-large")
        compose.onNodeWithTag("course_detail_close").performClick()
        compose.onNodeWithTag("course_details").assertDoesNotExist()
    }
    private fun capture(tag: String, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = context.getExternalFilesDir(null)!!.resolve("course-$name.png")
        target.outputStream().use { compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun captureScreen(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext.getExternalFilesDir(null)!!.resolve("course-$name.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
