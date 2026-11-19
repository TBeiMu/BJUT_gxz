package io.github.tbeimu.gongxiaozhu

import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.schedule.*
import io.github.tbeimu.gongxiaozhu.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises destructive-action boundaries using fictional offline course data. */
class CourseOperationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pressDialogBack() {
        // Native input does not synchronize with Compose's test clock or updated Dialog callbacks.
        compose.waitForIdle()
        var dialog: View? = null
        // Compose clicks target a View directly; a system key instead follows native window focus.
        compose.waitUntil(5000) {
            compose.runOnUiThread {
                dialog = WindowInspector.getGlobalWindowViews().firstOrNull {
                    it !== compose.activity.window.decorView && it.hasWindowFocus()
                }
            }
            dialog != null
        }
        compose.runOnUiThread { dialog!!.windowInsetsController?.hide(WindowInsets.Type.ime()) }
        compose.waitUntil(5000) {
            var hidden = false
            compose.runOnUiThread { hidden = dialog!!.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
            hidden
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private fun editorContext(deleteOnly: Boolean = false, creatingTerm: Boolean = false): CourseEditContext {
        val data = ScheduleData(localTerms = listOf(LocalScheduleTerm("operation-term", "2026-11-09", 20,
            courses = listOf(CustomCourse("operation-course", "测试课程", occurrences =
                (10..17).map { CustomOccurrence(it, 2, listOf(1, 2)) })))), selectedLocalTermId = "operation-term")
        return if (creatingTerm) ScheduleData().courseEditor(weekday = 1)
        else data.courseEditor(data.display()!!.weeks[12].courses.single(), deleteOnly = deleteOnly)
    }

    @Test fun cancelUsesOneConfirmationAndContinuingRetainsDraftAndPendingRangeAfterRestore() {
        val context = editorContext()
        var dismissed = 0
        var saved: CourseDraft? = null
        val restore = StateRestorationTester(compose)
        restore.setContent { GongXiaoZhuTheme { CourseEditor(context, false, null, { dismissed++ }, { saved = it }) } }
        compose.onNodeWithTag("course_name").performTextReplacement("修改课程")
        compose.onNodeWithTag("course_lesson_9").performScrollTo().performClick()
        compose.onNodeWithTag("cancel_course").performClick()
        compose.onNodeWithTag("course_editor").assertDoesNotExist()
        compose.onNodeWithTag("discard_changes_dialog").assertIsDisplayed()
        assertEquals(0, dismissed)
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("discard_changes_dialog").assertIsDisplayed()
        compose.onNodeWithTag("continue_editing").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
        compose.onNodeWithTag("course_name").assertTextContains("修改课程")
        compose.onNodeWithTag("course_lesson_hint").performScrollTo().assertTextEquals("已选第 9 节，再点结束节次")
        compose.onNodeWithTag("course_lesson_12").performScrollTo().performClick()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals((9..12).toList(), saved?.lessons)
        assertEquals("修改课程", saved?.name)
    }

    @Test fun revertingToOriginalValuesAllowsCancelWithoutConfirmation() {
        val context = editorContext()
        val visible = mutableStateOf(true)
        var dismissed = 0
        compose.setContent { GongXiaoZhuTheme { if (visible.value) CourseEditor(context, false, null,
            { dismissed++; visible.value = false }, {}) } }
        compose.onNodeWithTag("course_name").performTextReplacement("修改课程")
        compose.onNodeWithTag("course_name").performTextReplacement("测试课程")
        compose.onNodeWithTag("cancel_course").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
        compose.onNodeWithTag("course_editor").assertDoesNotExist()
        assertEquals(1, dismissed)
    }

    @Test fun outsideTapKeepsChangedEditorAndOnlyDismissesAfterChangesAreReverted() {
        val context = editorContext()
        val visible = mutableStateOf(true)
        var dismissed = 0
        compose.setContent { GongXiaoZhuTheme { if (visible.value) CourseEditor(context, false, null,
            { dismissed++; visible.value = false }, {}) } }
        compose.onNodeWithTag("course_day_3").performScrollTo().performClick()
        compose.onNodeWithTag("course_editor_overlay").performTouchInput { click(Offset(1f, center.y)) }
        compose.onNodeWithTag("course_editor").assertExists()
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
        assertEquals(0, dismissed)
        compose.onNodeWithTag("course_day_3").performScrollTo().performClick()
        compose.onNodeWithTag("course_editor_overlay").performTouchInput { click(Offset(1f, center.y)) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("course_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("course_editor").assertDoesNotExist()
        assertEquals(1, dismissed)
    }

    @Test fun systemBackProtectsChangedDraftAndDiscardExits() {
        val context = editorContext()
        val visible = mutableStateOf(true)
        var dismissed = 0
        compose.setContent { GongXiaoZhuTheme { if (visible.value) CourseEditor(context, false, null,
            { dismissed++; visible.value = false }, {}) } }
        compose.onNodeWithTag("course_day_3").performScrollTo().performClick()
        pressDialogBack()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("discard_changes_dialog").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("discard_changes_dialog").assertIsDisplayed()
        compose.onNodeWithTag("course_editor").assertDoesNotExist()
        assertEquals(0, dismissed)
        compose.onNodeWithTag("discard_changes").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
        assertEquals(1, dismissed)
    }

    @Test fun savingDisablesCancelOutsideAndSystemBack() {
        val context = editorContext()
        var dismissed = 0
        compose.setContent { GongXiaoZhuTheme { CourseEditor(context, true, null, { dismissed++ }, {}) } }
        compose.onNodeWithTag("cancel_course").assertIsNotEnabled()
        compose.onNodeWithTag("save_course").assertIsNotEnabled()
        compose.onNodeWithTag("course_editor_overlay").performTouchInput { click(Offset(1f, center.y)) }
        pressDialogBack()
        compose.onNodeWithTag("course_editor").assertExists()
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
        assertEquals(0, dismissed)
    }

    @Test fun cancellingDatePickerReturnsToDraftWithoutDiscardConfirmation() {
        val context = editorContext(creatingTerm = true)
        compose.setContent { GongXiaoZhuTheme { CourseEditor(context, false, null, {}, {}) } }
        compose.onNodeWithTag("course_name").performTextInput("新课程")
        compose.onNodeWithTag("term_monday").performScrollTo().performClick()
        compose.onNodeWithTag("course_editor").assertDoesNotExist()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithTag("course_name").assertTextContains("新课程")
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
        compose.onNodeWithTag("cancel_course").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertIsDisplayed()
    }

    @Test fun deletingDefaultsToOneOccurrenceAndShowsActualMultiweekCount() {
        val context = editorContext(deleteOnly = true)
        var saved: CourseDraft? = null
        compose.setContent { GongXiaoZhuTheme { CourseEditor(context, false, null, {}, { saved = it }) } }
        compose.onNodeWithTag("course_delete_summary").assertTextEquals("第 13 周 · 周二 · 第 1–2 节，共 1 次安排")
        compose.onNodeWithTag("save_course").assertTextContains("删除这 1 次安排")
        compose.onNodeWithTag("source_week_all").performScrollTo().performClick()
        compose.onNodeWithTag("course_delete_summary").assertTextEquals("第 10–17 周 · 周二 · 第 1–2 节，共 8 次安排")
        compose.onNodeWithTag("save_course").assertTextContains("删除这 8 次安排")
        compose.onNodeWithTag("source_week_all").performClick()
        compose.onNodeWithTag("course_delete_summary").assertTextEquals("未选择要删除的安排")
        compose.onNodeWithTag("save_course").assertIsNotEnabled()
        compose.onNodeWithTag("source_week_13").performScrollTo().performClick()
        compose.onNodeWithTag("save_course").performClick()
        assertEquals(listOf(13), saved?.sourceWeeks)
    }
}
